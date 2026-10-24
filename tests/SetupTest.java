package sa.urtv.setup;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone app's whole setup and its undo, against a pretend TCL Google TV (tests/fake_adbd.py running the
 * pretend pm, settings, cmd, am … of tests/fake_tv): what ends up disabled, installed, set and put back.
 *
 *   tests/adb_test.sh      (runs AdbTest, then this)
 */
public class SetupTest {
    static int failed = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
        if (!ok) failed++;
    }

    static String read(File f) throws IOException {
        return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
    }

    static List<String> lines(File f) throws IOException { return Arrays.asList(read(f).split("\n")); }

    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        File dir = new File(a[1]), repo = new File(a[2]), state = new File(dir, "state");
        state.mkdirs();
        new File(dir, "tmp").mkdirs();
        // a TCL Google TV: telemetry on, voice search off before we came, Google TV's home
        Files.write(new File(state, "installed").toPath(), String.join("\n",
            "com.tcl.bi", "com.tcl.logkit", "com.tcl.messagebox", "com.google.android.katniss",
            "com.google.android.apps.mediashell", "com.google.android.apps.tv.launcherx",
            "com.google.android.tungsten.setupwraith", "com.tcl.tv.tclhome_passive", "sa.urtv.screensaver", "").getBytes());
        Files.write(new File(state, "disabled").toPath(), "com.google.android.katniss\n".getBytes());
        Files.write(new File(state, "otherkey").toPath(), "sa.urtv.screensaver\n".getBytes());   // the original project's build

        Map<String, String> store = new HashMap<>();
        StringBuilder said = new StringBuilder();
        Setup.Env env = new Setup.Env() {
            public String asset(String path) throws IOException {
                return read(new File(repo, "optimize/" + path));
            }
            public byte[] download(String url) throws IOException {
                if (url.contains("api.github.com")) {
                    String n = url.contains("tv-bro") ? "tvbro-2.1.0-generic-geckoExcluded.apk"
                        : "SmartTube_stable_29.10_armeabi-v7a.apk\"},{\"browser_download_url\":\"https://x/SmartTube_stable_29.10_arm64-v8a.apk";
                    return ("{\"assets\":[{\"browser_download_url\":\"https://x/" + n + "\"}]}").getBytes();
                }
                return ("apk from " + url).getBytes();
            }
            public String load(String key) { return store.get(key); }
            public void save(String key, String value) { if (value == null) store.remove(key); else store.put(key, value); }
            public void sleep(long ms) { }
        };
        Tv tv = new Tv("127.0.0.1", port, AdbKey.load(new File(dir, "key")), s -> said.append(s).append('\n'));
        Setup s = new Setup(tv, env, line -> said.append(line).append('\n'));
        s.tmp = new File(dir, "tmp").getAbsolutePath();

        Setup.Choices c = new Setup.Choices();
        c.city = "الكويت"; c.lat = "29.3759"; c.lon = "47.9774"; c.tz = "Asia/Kuwait"; c.method = "9";
        c.teams.add("137721:Al-Ahli"); c.panel = "prayers"; c.prayers = false;

        s.probe();
        check("the TV is identified", s.serial.equals("FAKE123") && s.model.contains("TCL"), s.serial + " " + s.model);
        s.run(c);
        List<String> installed = lines(new File(state, "installed")), disabled = lines(new File(state, "disabled"));
        check("telemetry and ads off", disabled.containsAll(Arrays.asList("com.tcl.bi", "com.tcl.logkit", "com.tcl.messagebox")), disabled.toString());
        check("voice search (protected) back on", !disabled.contains("com.google.android.katniss"), disabled.toString());
        check("casting never touched", !disabled.contains("com.google.android.apps.mediashell"), disabled.toString());
        check("the other key's screensaver replaced by ours", installed.contains("sa.urtv.screensaver")
            && read(new File(state, "log")).contains("pm uninstall sa.urtv.screensaver"), read(new File(state, "log")));
        check("our screensaver selected", read(new File(state, "settings/secure.screensaver_components")).contains("sa.urtv.screensaver/.ScreenDream"), "");
        String saver = read(new File(state, "saver"));
        check("choices sent in one SET: city, tz, method, prayers off, card, team",
            saver.contains("--es city الكويت") && saver.contains("--es lat 29.3759") && saver.contains("--es tz Asia/Kuwait")
            && saver.contains("--es method 9") && saver.contains("--es prayers off") && saver.contains("--es panel prayers")
            && saver.contains("--es teams 137721:Al-Ahli") && saver.contains("--es home_panel none"), saver);
        check("our home is Home", read(new File(state, "home")).contains("sa.urtv.home"), read(new File(state, "home")));
        check("the safety switch was armed with the stock home", read(new File(state, "log")).contains("deadman.sh com.google.android.apps.tv.launcherx/"), "");
        check("Google TV's home and setup app off after the switch", disabled.containsAll(Arrays.asList(
            "com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith", "com.tcl.tv.tclhome_passive")), disabled.toString());
        check("TV Bro and the arm64 SmartTube installed", installed.contains("com.phlox.tvwebbrowser") && installed.contains("org.smarttube.stable")
            && said.toString().contains("SmartTube_stable_29.10_arm64-v8a.apk"), said.toString());
        check("animations faster", read(new File(state, "settings/global.window_animation_scale")).trim().equals("0.5"), "");
        check("every step remembered", Arrays.stream(Setup.STEPS).allMatch(s::done), store.toString());
        check("the report says Home and screensaver are ours", s.report.toString().contains("✓ الواجهة الرئيسية: تلفزيونك")
            && s.report.toString().contains("✓ شاشة التوقف"), s.report.toString());

        // the same button again: nothing left to do, nothing redone
        int before = read(new File(state, "log")).length();
        s.run(c);
        check("a second run redoes nothing", read(new File(state, "log")).length() == before, "");

        // favourites
        String apps = s.dock("LIST_APPS", "");
        check("favourites list read from the home app", apps.contains("com.netflix.ninja | Netflix"), apps);

        // undo
        s.reset();
        installed = lines(new File(state, "installed"));
        disabled = lines(new File(state, "disabled"));
        check("reset: our apps and the extras removed", !installed.contains("sa.urtv.home") && !installed.contains("sa.urtv.screensaver")
            && !installed.contains("com.phlox.tvwebbrowser") && !installed.contains("org.smarttube.stable"), installed.toString());
        check("reset: everything we turned off is back on", !disabled.contains("com.tcl.bi")
            && !disabled.contains("com.google.android.apps.tv.launcherx") && !disabled.contains("com.tcl.tv.tclhome_passive"), disabled.toString());
        check("reset: Google TV's home is Home again", read(new File(state, "home")).contains("launcherx"), read(new File(state, "home")));
        check("reset: the TV's own screensaver back", !new File(state, "settings/secure.screensaver_components").exists(), "");
        check("reset: animations back to normal", read(new File(state, "settings/global.window_animation_scale")).trim().equals("1.0"), "");
        check("reset: a new setup starts from the top", !s.done("backup") && store.get("backup:FAKE123") == null, store.toString());
        tv.close();

        System.out.println(failed == 0 ? "\nall setup tests passed" : "\n" + failed + " failed\n\n" + said);
        System.exit(failed == 0 ? 0 : 1);
    }
}
