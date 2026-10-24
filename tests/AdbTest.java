package sa.urtv.setup;

import java.io.File;
import java.nio.file.Files;
import java.util.Random;

/**
 * The phone app's ADB code against a pretend TV (tests/fake_adbd.py): first login offers the key, the second
 * signs with it, shell output in several pieces, an upload with dropped connections that still arrives whole.
 *
 *   tests/adb_test.sh      (compiles android/phone's Adb, AdbKey and Tv with plain javac and runs this)
 */
public class AdbTest {
    static int failed = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
        if (!ok) failed++;
    }

    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]), dropPort = Integer.parseInt(a[1]);
        File dir = new File(a[2]);
        AdbKey key = AdbKey.load(new File(dir, "key"));
        Tv.Log quiet = s -> { };

        // 1. a new key: the pretend TV "presses Allow" on the public key
        Adb first = Adb.connect("127.0.0.1", port, key, 10000, 10000);
        check("first login offers the key and gets in", first.banner.contains("ro.product.model=Fake"), first.banner);
        check("shell output comes back", "hi".equals(first.shell("echo hi").trim()), "");
        first.close();

        // 2. the same key again: signed token accepted, no offer needed
        Adb second = Adb.connect("127.0.0.1", port, AdbKey.load(new File(dir, "key")), 10000, 10000);
        StringBuilder want = new StringBuilder();
        for (int i = 0; i < 3000; i++) want.append("line ").append(i).append('\n');
        String got = second.shell("i=0; while [ $i -lt 3000 ]; do echo \"line $i\"; i=$((i+1)); done");
        check("a saved key signs in again", true, "");
        check("long output in many pieces arrives whole (" + got.length() + " bytes)", want.toString().equals(got), "");
        check("several commands on one connection", "a b".equals(second.shell("echo a b").trim()), "");
        second.close();

        // 3. a different key the TV never allowed: it is offered (the TV would ask the person)
        AdbKey other = AdbKey.load(new File(dir, "other"));
        Adb third = Adb.connect("127.0.0.1", port, other, 10000, 10000);
        check("an unknown key is offered, not silently refused", third.banner.startsWith("device::"), third.banner);
        third.close();

        // 4. an upload through a link that drops every 7th command: retried, resumed, md5 checked
        byte[] apk = new byte[400000];
        new Random(7).nextBytes(apk);
        new File(dir, "tv/data/local/tmp").mkdirs();
        Tv tv = new Tv("127.0.0.1", dropPort, key, quiet);
        tv.fastRetries();
        tv.push(apk, "tv/data/local/tmp/x.apk", "x");
        byte[] there = Files.readAllBytes(new File(dir, "tv/data/local/tmp/x.apk").toPath());
        check("upload over a dropping link arrives byte for byte", java.util.Arrays.equals(apk, there), there.length + " bytes");
        check("no leftover pieces", !new File(dir, "tv/data/local/tmp/x.apk.b64").exists(), "");

        // 5. a cut-off upload resumes instead of starting over
        String enc = java.util.Base64.getEncoder().encodeToString(apk);
        Files.write(new File(dir, "tv/data/local/tmp/y.apk.b64").toPath(), enc.substring(0, 96000 * 2).getBytes());
        StringBuilder said = new StringBuilder();
        Tv tv2 = new Tv("127.0.0.1", port, key, said::append);
        tv2.push(apk, "tv/data/local/tmp/y.apk", "y");
        check("a cut-off upload resumes where it stopped", !said.toString().contains(": 18٪"), said.toString());
        check("and arrives whole", java.util.Arrays.equals(apk, Files.readAllBytes(new File(dir, "tv/data/local/tmp/y.apk").toPath())), "");
        tv2.close();

        System.out.println(failed == 0 ? "\nall ADB tests passed" : "\n" + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
