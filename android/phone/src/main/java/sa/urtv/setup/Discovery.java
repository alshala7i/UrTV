package sa.urtv.setup;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Finds the TV on this phone's Wi-Fi, as optimize/find-tv.py does: ADB's port 5555, and Cast's 8008 to tell
 *  "a TV with debugging off" from "no TV at all". */
final class Discovery {
    private Discovery() {}

    static final class Found {
        final List<String> adb = new ArrayList<>(), cast = new ArrayList<>();
        String myAddress;   // null: this phone is not on Wi-Fi
    }

    /** This phone's IPv4 address on Wi-Fi (or Ethernet), or null. */
    static Inet4Address wifiAddress(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (LinkAddress la : lp.getLinkAddresses())
                if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress())
                    return (Inet4Address) la.getAddress();
        }
        return null;
    }

    /** Every address of this phone's /24 with port 5555 (and 8008) open; about 10 seconds. */
    static Found scan(Context c) {
        Found f = new Found();
        Inet4Address me = wifiAddress(c);
        if (me == null) return f;
        f.myAddress = me.getHostAddress();
        byte[] b = me.getAddress();
        String prefix = (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + ".";
        ExecutorService pool = Executors.newFixedThreadPool(48);
        List<Future<int[]>> jobs = new ArrayList<>();
        for (int i = 1; i < 255; i++) {
            final int host = i;
            if (prefix.concat(String.valueOf(i)).equals(f.myAddress)) continue;
            jobs.add(pool.submit(() -> new int[] {host, open(prefix + host, Tv.PORT) ? 1 : 0, open(prefix + host, 8008) ? 1 : 0}));
        }
        for (Future<int[]> j : jobs) {
            try {
                int[] r = j.get(15, TimeUnit.SECONDS);
                if (r[1] == 1) f.adb.add(prefix + r[0]);
                else if (r[2] == 1) f.cast.add(prefix + r[0]);
            } catch (Exception ignored) { }
        }
        pool.shutdownNow();
        Collections.sort(f.adb);
        return f;
    }

    static boolean open(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 700);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
