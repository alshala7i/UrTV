package sa.urtv.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * The TV over a flaky Wi-Fi ADB link, as optimize/tv.py does it: one connection, reopened when it drops, every
 * command retried with a growing pause; uploads in base64 pieces that resume; restarts sent once. Plain Java.
 */
final class Tv {
    interface Log { void line(String s); }

    /** The TV stopped answering after every retry: the person has to check it (on, awake, same Wi-Fi). */
    static final class GoneException extends IOException {
        GoneException(String m, Throwable cause) { super(m, cause); }
    }

    static final int PORT = 5555;
    private static final int TRIES = 4, IO_MS = 60000, AUTH_MS = 120000;
    private static final int CHUNK = 96000;   // one shell argument may not exceed 128 KB on Android

    final String host;
    private final int port;
    private final AdbKey key;
    private final Log log;
    private Adb adb;
    private int pauseMs = 5000;   // tests shorten it

    Tv(String host, AdbKey key, Log log) { this(host, PORT, key, log); }
    Tv(String host, int port, AdbKey key, Log log) { this.host = host; this.port = port; this.key = key; this.log = log; }

    void fastRetries() { pauseMs = 50; }

    /** The first connection: waits up to 2 minutes for «Allow USB debugging?» on the TV. */
    void open() throws IOException {
        close();
        adb = Adb.connect(host, port, key, IO_MS, AUTH_MS);
    }

    String shell(String cmd) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < TRIES; attempt++) {
            try {
                if (adb == null) open();
                return adb.shell(cmd);
            } catch (Adb.AuthException e) {
                close();
                throw e;   // retrying cannot press Allow for them
            } catch (IOException e) {
                close();
                last = e;
                if (attempt < TRIES - 1) {
                    int wait = pauseMs * (attempt + 1);
                    log.line("   (انقطع الاتصال لحظة، أعيد المحاولة بعد " + wait / 1000 + " ثواني)");
                    sleep(wait);
                }
            }
        }
        throw new GoneException("TV not answering after " + TRIES + " tries", last);
    }

    /** shell() with the trailing newline and \r of older sets cut off. */
    String get(String cmd) throws IOException { return shell(cmd).replace("\r", "").trim(); }

    /** Uploads bytes to a path on the TV, resuming a cut-off upload, and checks the md5 at the end. */
    void push(byte[] data, String remote, String label) throws IOException {
        String enc = Base64.getEncoder().encodeToString(data);
        String folder = remote.substring(0, remote.lastIndexOf('/'));
        if (!"y".equals(get("[ -d '" + folder + "' ] && echo y")))
            throw new IOException(folder + " is not a folder on the TV");
        String tmp = remote + ".b64";
        // a piece and its command must fit in one ADB message: 256 KB and more on today's TVs, 4 KB on old ones
        int chunk = adb == null ? CHUNK : Math.min(CHUNK, (adb.maxData() - 2 * tmp.length() - 512) / 1000 * 1000);
        int done = 0;
        String have = get("[ -f " + tmp + " ] && wc -c < " + tmp + " || echo 0");
        if (have.matches("\\d+")) {
            int n = Integer.parseInt(have);
            if (n > 0 && n % chunk == 0 && n <= enc.length()
                    && md5(enc.substring(0, n).getBytes(StandardCharsets.US_ASCII)).equals(firstWord(get("md5sum " + tmp))))
                done = n;   // only exactly what we would have written: a killed run may have left a piece twice
        }
        if (done == 0) shell("rm -f " + tmp + " " + remote);
        int lastPct = -1;
        for (int i = done; i < enc.length(); i += chunk) {
            String piece = enc.substring(i, Math.min(i + chunk, enc.length()));
            String want = String.valueOf(Math.min(i + chunk, enc.length()));
            String size = get("echo -n '" + piece + "' >> " + tmp + "; wc -c < " + tmp);
            for (int t = 0; t < 5 && !want.equals(size); t++)   // a retried call wrote it twice, or not at all
                size = get("head -c " + i + " " + tmp + " > " + tmp + ".x && mv " + tmp + ".x " + tmp
                    + "; echo -n '" + piece + "' >> " + tmp + "; wc -c < " + tmp);
            if (!want.equals(size))
                throw new IOException("upload keeps failing (the TV wrote " + size + " of " + want
                    + "; space: " + get("df -h " + folder + " | tail -1") + ")");
            int pct = Integer.parseInt(want) * 100 / enc.length();
            if (pct / 10 != lastPct / 10) log.line("   رفع " + label + ": " + pct + "٪");
            lastPct = pct;
        }
        shell("[ -f " + tmp + " ] && base64 -d " + tmp + " > " + remote + " && rm -f " + tmp);
        String theirs = firstWord(get("[ -f " + remote + " ] && md5sum " + remote));
        if (!md5(data).equals(theirs)) {
            shell("rm -f " + remote + " " + tmp);
            throw new IOException("the uploaded file came out different (md5), try again");
        }
    }

    /** Restarts the TV (sent once: a retried reboot could restart it twice) and waits until it is back. */
    void reboot() throws IOException {
        try {
            if (adb == null) open();
            adb.reboot();
        } catch (IOException ignored) { }
        close();
        log.line("   التلفزيون يعيد التشغيل…");
        sleep(45000);
        waitBack(300000);
    }

    /** Waits until the TV answers and has finished booting. */
    void waitBack(long limitMs) throws IOException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < limitMs) {
            try {
                Adb a = Adb.connect(host, port, key, 15000, 30000);
                String booted = a.shell("getprop sys.boot_completed").trim();
                a.close();
                if ("1".equals(booted)) return;
            } catch (IOException ignored) { }
            sleep(10000);
        }
        throw new GoneException("TV did not come back within " + limitMs / 60000 + " minutes", null);
    }

    void close() {
        if (adb != null) adb.close();
        adb = null;
    }

    static String firstWord(String s) {
        String t = s.trim();
        int sp = t.indexOf(' ');
        return sp < 0 ? t : t.substring(0, sp);
    }

    static String md5(byte[] b) {
        try {
            StringBuilder s = new StringBuilder();
            for (byte x : MessageDigest.getInstance("MD5").digest(b)) s.append(String.format("%02x", x));
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
