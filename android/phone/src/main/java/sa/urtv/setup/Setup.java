package sa.urtv.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The whole TV setup, the same steps and in the same order as optimize/run.sh (without the memory measuring),
 * then the owner's choices sent to the screensaver, and optimize/reset.sh to undo it. Every finished step is
 * remembered per TV, so after any failure the same button carries on where it stopped. Plain Java: Env is
 * what the phone gives it (the app's files, the network), so tests/SetupTest.java runs it on a computer.
 */
final class Setup {
    interface Env {
        /** a text file the app carries: lists/keep.txt, lists/tcl-safe.txt, deadman.sh … */
        String asset(String path) throws IOException;
        byte[] download(String url) throws IOException;
        String load(String key);
        void save(String key, String value);
        void sleep(long ms);
    }

    /** A stop the owner has to see: the message is Arabic and says what to do. */
    static final class Stop extends IOException {
        Stop(String m) { super(m); }
    }

    /** What the owner answered on the phone. */
    static final class Choices {
        String city = "الرياض", lat = "24.6877", lon = "46.7219", tz = "Asia/Riyadh", method = "4";
        List<String> teams = new ArrayList<>();   // "id:EnglishName", in order
        String panel = "verse";
        boolean prayers = true, launcher = true, extras = true;
    }

    static final String REL = "https://github.com/alshala7i/UrTV/releases/download/apk-latest/";
    static final String LAUNCHERX = "com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity";
    static final String TVLAUNCHER = "com.google.android.tvlauncher/.MainActivity";
    static final String[] STEPS = {"backup", "keep", "safe", "tweaks", "screensaver", "settings", "launcher", "extras", "check"};

    static String title(String step) {
        switch (step) {
            case "backup": return "حفظ حالة التلفزيون قبل أي تغيير";
            case "keep": return "التأكد إن الخدمات المهمة شغالة (البث، الصوت، التحديثات)";
            case "safe": return "إطفاء التتبع والإعلانات والعروض";
            case "tweaks": return "تسريع الحركات وتخفيف الذاكرة";
            case "screensaver": return "تركيب شاشة التوقف";
            case "settings": return "ضبط مدينتك وأنديتك وشاشة التوقف";
            case "launcher": return "تركيب الواجهة الرئيسية (مع مفتاح الأمان)";
            case "extras": return "تركيب المتصفح ويوتيوب بدون إعلانات";
            case "check": return "الفحص الأخير";
            default: return step;
        }
    }

    private final Tv tv;
    private final Env env;
    /** where uploads land on the TV (tests point it at a scratch folder) */
    String tmp = "/data/local/tmp";
    private final Tv.Log log;
    String serial = "", model = "", android = "";
    /** filled by the last step: what the owner sees at the end */
    final List<String> report = new ArrayList<>();

    Setup(Tv tv, Env env, Tv.Log log) { this.tv = tv; this.env = env; this.log = log; }

    // ---- the run

    /** Connects (the TV may ask «Allow USB debugging?» now) and finds out which TV this is. */
    void probe() throws IOException {
        tv.open();
        model = tv.get("getprop ro.product.manufacturer") + " " + tv.get("getprop ro.product.model");
        android = tv.get("getprop ro.build.version.release");
        serial = tv.get("getprop ro.serialno");
        if (serial.isEmpty()) serial = tv.host;
        log.line("التلفزيون: " + model.trim() + " · أندرويد " + android);
    }

    boolean done(String step) { return "1".equals(env.load("done:" + serial + ":" + step)); }

    /** Every step not done yet on this TV, in order. Call probe() first. */
    void run(Choices c) throws IOException {
        for (String step : STEPS) {
            if (done(step)) continue;
            log.line("▶ " + title(step));
            step(step, c);
            env.save("done:" + serial + ":" + step, "1");
        }
    }

    private void step(String step, Choices c) throws IOException {
        switch (step) {
            case "backup": backup(); break;
            case "keep": keep(); break;
            case "safe": disable(list("lists/tcl-safe.txt")); break;
            case "tweaks": tweaks(true); break;
            case "screensaver": screensaver(); break;
            case "settings": settings(c); break;
            case "launcher":
                if (c.launcher) launcher(); else log.line("   تخطيت: تبقى واجهة التلفزيون الأصلية");
                break;
            case "extras":
                if (c.extras) extras(); else log.line("   تخطيت");
                break;
            case "check": check(c); break;
            default: throw new IllegalArgumentException(step);
        }
    }

    // ---- steps

    /** optimize/backup.sh: what the TV was like before, for keep() and reset(). The first run's copy is kept. */
    void backup() throws IOException {
        if (env.load("backup:" + serial) != null) { log.line("   محفوظة من قبل"); return; }
        StringBuilder b = new StringBuilder();
        b.append("== model\n").append(model).append('\n').append(android).append('\n');
        b.append("== disabled packages\n").append(tv.get("pm list packages -d")).append('\n');
        b.append("== all packages\n").append(tv.get("pm list packages")).append('\n');
        b.append("== home\n").append(tv.get("cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN")).append('\n');
        b.append("== screensaver\n").append(tv.get("settings get secure screensaver_components")).append('\n')
            .append(tv.get("settings get secure screensaver_enabled")).append('\n')
            .append(tv.get("settings get system screen_off_timeout")).append('\n');
        b.append("== animation\n").append(tv.get("settings get global window_animation_scale")).append('\n');
        env.save("backup:" + serial, b.toString());
    }

    static List<String> section(String backup, String name) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String line : backup.split("\n")) {
            if (line.startsWith("== ")) { in = line.substring(3).startsWith(name); continue; }
            if (in && !line.trim().isEmpty()) out.add(line.trim());
        }
        return out;
    }

    /** optimize/keep.sh: anything protected (casting, voice, remote, updates…) that is off goes back on. */
    void keep() throws IOException {
        List<String> keep = list("lists/keep.txt");
        keep.removeAll(Arrays.asList("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
            "com.google.android.tungsten.setupwraith", "com.tcl.keycustomfunctionservice"));   // install's own
        List<String> off = disabledOf(keep);
        if (off.isEmpty()) { log.line("   كلها شغالة"); return; }
        for (String p : off) tv.shell("pm enable " + p);
        List<String> still = disabledOf(off);
        for (String p : still) tv.shell("pm enable --user 0 " + p);   // the way Android 9 sets want it
        still = disabledOf(off);
        if (still.isEmpty()) { log.line("   رجّعت: " + String.join("، ", off)); return; }
        // the TV itself ships some off (a TCL outside Alexa's regions): only one that was on before is ours
        String backup = env.load("backup:" + serial);
        List<String> before = backup == null ? new ArrayList<>() : packages(section(backup, "disabled packages"));
        List<String> ours = new ArrayList<>();
        for (String p : still) if (backup != null && !before.contains(p)) ours.add(p);
        if (!ours.isEmpty()) throw new Stop("خدمات مهمة ما رجعت تشتغل: " + String.join("، ", ours) + ". أوقفت الإعداد عشان ما تنكسر.");
        log.line("   مطفية من التلفزيون نفسه من قبل، وتبقى كذا: " + String.join("، ", still));
    }

    private List<String> disabledOf(List<String> pkgs) throws IOException {
        List<String> disabled = packages(Arrays.asList(tv.get("pm list packages -d").split("\n")));
        List<String> out = new ArrayList<>();
        for (String p : pkgs) if (disabled.contains(p)) out.add(p);
        return out;
    }

    static List<String> packages(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String l : lines) if (l.trim().startsWith("package:")) out.add(l.trim().substring(8).trim());
        return out;
    }

    /** optimize/disable.sh: off for user 0, never uninstalled; refuses anything in keep.txt. */
    void disable(List<String> pkgs) throws IOException {
        List<String> keep = list("lists/keep.txt");
        for (String p : pkgs) if (keep.contains(p)) throw new Stop("رفضت أطفي " + p + " لأنه في قائمة الحماية.");
        int n = 0;
        for (String p : pkgs) if (tv.get("pm disable-user --user 0 " + p).contains("disabled")) n++;
        log.line("   أطفيت " + n + " (والباقي مو موجود في هالموديل)");
    }

    /** optimize/tweaks.sh apply | restore */
    void tweaks(boolean apply) throws IOException {
        String v = apply ? "0.5" : "1.0", bucket = apply ? "restricted" : "active", bg = apply ? "deny" : "allow";
        List<String> cmds = new ArrayList<>(Arrays.asList(
            "settings put global window_animation_scale " + v,
            "settings put global transition_animation_scale " + v,
            "settings put global animator_duration_scale " + v,
            "am set-standby-bucket com.android.vending " + bucket,
            "cmd appops set com.android.vending RUN_IN_BACKGROUND " + bg,
            "cmd appops set com.android.vending RUN_ANY_IN_BACKGROUND " + bg,
            "device_config delete activity_manager max_cached_processes"));
        if (apply) { cmds.add("settings put global cached_apps_freezer enabled"); cmds.add("pm trim-caches 999G"); }
        else cmds.add("settings put global cached_apps_freezer device_default");
        for (String cmd : cmds) tv.shell(cmd);
    }

    /** optimize/install.sh saver */
    void screensaver() throws IOException {
        install("urtv-screensaver.apk", "sa.urtv.screensaver", null);
        tv.shell("settings put secure screensaver_enabled 1");
        tv.shell("settings put secure screensaver_components sa.urtv.screensaver/.ScreenDream");
        tv.shell("settings put secure screensaver_default_component sa.urtv.screensaver/.ScreenDream");
    }

    /** optimize/saver.sh city/teams/card/prayers/tz/method, all in one SET: nothing half-saved. */
    void settings(Choices c) throws IOException {
        String extras = "--es city " + q(c.city) + " --es lat " + q(c.lat) + " --es lon " + q(c.lon)
            + " --es teams " + q(String.join(",", c.teams)) + " --es panel " + q(c.panel)
            + " --es home_panel none --es prayers " + (c.prayers ? "on" : "off")
            + " --es tz " + q(c.tz) + " --es method " + q(c.method);
        String out = saver("SET", extras);
        if (out.startsWith("error:")) throw new Stop("شاشة التوقف رفضت الإعدادات: " + out);
        saver("UPDATE", "");
        log.line("   " + c.city + (c.teams.isEmpty() ? "" : " · " + c.teams.size() + " أندية"));
    }

    String saver(String action, String extras) throws IOException {
        return data(tv.shell("am broadcast -n sa.urtv.screensaver/.SaverReceiver -a sa.urtv.screensaver." + action
            + (extras.isEmpty() ? "" : " " + extras)));
    }

    String dock(String action, String extras) throws IOException {
        return data(tv.shell("am broadcast -n sa.urtv.home/.DockReceiver -a sa.urtv.home." + action
            + (extras.isEmpty() ? "" : " " + extras)));
    }

    /** The receiver's answer: the "data" of am broadcast's output. */
    static String data(String out) {
        int i = out.indexOf("data=\"");
        if (i < 0) return "";
        String s = out.substring(i + 6).trim();
        return s.endsWith("\"") ? s.substring(0, s.length() - 1) : s;
    }

    /** one shell-safe argument for the TV's shell (values come from our own lists, never an apostrophe) */
    static String q(String v) {
        if (v.contains("'")) throw new IllegalArgumentException("apostrophe in " + v);
        return "'" + v + "'";
    }

    String stockHome() throws IOException {
        return tv.get("pm list packages com.google.android.apps.tv.launcherx").contains("launcherx") ? LAUNCHERX : TVLAUNCHER;
    }

    static String pkg(String component) { return component.substring(0, component.indexOf('/')); }

    /** optimize/install.sh launcher: our home, behind a safety switch that puts the stock one back in 5 minutes. */
    void launcher() throws IOException {
        String stock = stockHome();
        install("urtv-home.apk", "sa.urtv.home", stock);
        tv.shell("pm grant sa.urtv.home android.permission.ACCESS_FINE_LOCATION");
        int rc;
        try {
            rc = switchHome(stock, false);
        } catch (Tv.GoneException e) {   // Wi-Fi dropped mid-switch: wait for the TV, see where Home is, go on
            log.line("   انقطع الاتصال وقت التبديل، أنتظر التلفزيون يرجع…");
            tv.waitBack(360000);
            boolean ours = tv.get("cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN").contains("sa.urtv.home");
            rc = switchHome(stock, ours);
        }
        if (rc == 2) throw new Stop("الواجهة الجديدة ما فتحت من زر الرئيسية. خلّ التلفزيون 5 دقايق وترجع واجهته الأصلية لحالها. "
            + "بعض أجهزة تي سي إل تقفل الواجهة الأصلية: جرّب مرة ثانية، أو كمّل بدون الواجهة الجديدة.");
        if (rc == 3) throw new Stop("مفتاح الأمان رجّع الواجهة الأصلية لأن الاتصال كان بطيء. ما انكسر شي: اضغط كمّل.");
        for (String p : list("lists/home-replaced.txt")) tv.shell("pm disable-user --user 0 " + p);
        log.line("   الواجهة الجديدة صارت الرئيسية");
    }

    /** 0: our home is on screen and the switch is off; 2: Home did not open it; 3: the switch fired first. */
    private int switchHome(String stock, boolean verifyOnly) throws IOException {
        if (!verifyOnly) {
            tv.push(env.asset("deadman.sh").getBytes(StandardCharsets.UTF_8), tmp + "/deadman.sh", "مفتاح الأمان");
            tv.shell("rm -f /data/local/tmp/home_ok");   // deadman.sh watches this exact path
            tv.shell("nohup sh " + tmp + "/deadman.sh " + stock + " 300 > /dev/null 2>&1 &");
            log.line("   مفتاح الأمان شغال: إذا ما اشتغلت الواجهة الجديدة، ترجع الأصلية لحالها بعد 5 دقايق");
            for (String cmd : new String[] {
                "cmd role add-role-holder --user 0 android.app.role.HOME sa.urtv.home",
                "cmd package set-home-activity --user 0 sa.urtv.home/.MainActivity",
                "pm disable-user --user 0 com.google.android.tungsten.setupwraith",
                "pm disable-user --user 0 " + pkg(stock),
                "cmd package set-home-activity --user 0 sa.urtv.home/.MainActivity",
                "am force-stop com.android.tv.settings",
                "input keyevent KEYCODE_HOME"}) tv.shell(cmd);
        }
        boolean ours = false;
        for (int i = 0; i < 3 && !ours; i++) {
            env.sleep(6000);
            ours = tv.shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'").contains("sa.urtv.home");
            if (!ours) tv.shell("input keyevent KEYCODE_HOME");
        }
        if (!ours) return 2;
        tv.shell("touch /data/local/tmp/home_ok");
        String res = tv.get("cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN");
        return res.contains("sa.urtv.home") ? 0 : 3;
    }

    /** push + pm install, as optimize/install.sh's push_install */
    void install(String apk, String pkg, String stock) throws IOException {
        log.line("   تنزيل " + apk + "…");
        byte[] data = env.download(REL + apk);
        String remote = tmp + "/" + apk;
        tv.push(data, remote, apk);
        String out = tv.get("pm install -r " + remote);
        if (!out.contains("Success")) {
            // another key's build (the original project's) or a higher version number: it has to go first
            if (out.contains("UPDATE_INCOMPATIBLE") || out.contains("signatures do not match") || out.contains("VERSION_DOWNGRADE")) {
                log.line("   نسخة ثانية من " + pkg + " موجودة: أحذفها وأركّب نسختك");
                if (stock != null) tv.shell("pm enable " + pkg(stock));   // never leave the TV with no home
                tv.shell("pm uninstall " + pkg);
                out = tv.get("pm install -r " + remote);
            }
            if (!out.contains("Success")) {
                tv.shell("rm -f " + remote);
                throw new Stop("ما قدرت أركّب " + apk + ": " + out);
            }
        }
        tv.shell("rm -f " + remote);
    }

    /** optimize/install.sh extras: TV Bro and SmartTube from their own GitHub releases, the build for this TV's processor */
    void extras() throws IOException {
        String abis = tv.get("getprop ro.product.cpu.abilist");
        extra("truefedex/tv-bro", "com.phlox.tvwebbrowser", "tvbro-[0-9.]+-generic-geckoExcluded\\.apk", abis);
        extra("yuliskov/SmartTube", "org.smarttube.stable", "SmartTube_stable_[0-9.]+_{abi}\\.apk", abis);
    }

    private void extra(String repo, String pkg, String pattern, String abis) throws IOException {
        String body;
        try {
            body = new String(env.download("https://api.github.com/repos/" + repo + "/releases/latest"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.line("   !! ما قدرت أوصل لـ " + repo + " (جرب بعدين): " + e.getMessage());
            return;
        }
        String url = pickAsset(body, pattern, abis);
        if (url == null) { log.line("   !! ما فيه نسخة من " + repo + " لهالتلفزيون"); return; }
        String name = url.substring(url.lastIndexOf('/') + 1);
        byte[] data = env.download(url);
        String remote = tmp + "/" + name;
        tv.push(data, remote, name);
        String out = tv.get("pm install -r " + remote);
        tv.shell("rm -f " + remote);
        if (out.contains("Success")) log.line("   تركّب " + name);
        else if (out.contains("UPDATE_INCOMPATIBLE") || out.contains("signatures do not match"))
            log.line("   " + pkg + " موجود من مصدر ثاني: تركته مثل ما هو");
        else log.line("   !! ما تركّب " + name + ": " + out);
    }

    /** The release asset for the best of this TV's processor types ({abi} in the pattern), else a universal one. */
    static String pickAsset(String releaseJson, String pattern, String abis) {
        Map<String, String> assets = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(releaseJson);
        while (m.find()) assets.put(m.group(1).substring(m.group(1).lastIndexOf('/') + 1), m.group(1));
        List<String> tries = new ArrayList<>();
        for (String a : abis.split(",")) if (!a.trim().isEmpty()) tries.add(a.trim());
        tries.add("universal");
        tries.add("");
        for (String abi : tries) {
            Pattern p = Pattern.compile(pattern.replace("{abi}", Pattern.quote(abi)) + "$");
            List<String> hits = new ArrayList<>();
            for (String name : assets.keySet()) if (p.matcher(name).find()) hits.add(name);
            if (!hits.isEmpty()) {
                java.util.Collections.sort(hits);
                return assets.get(hits.get(0));
            }
        }
        return null;
    }

    /** What run.sh's report and step 6 check: protected features on, Home and the screensaver ours. */
    void check(Choices c) throws IOException {
        report.clear();
        keep();
        String home = tv.get("cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN");
        String saver = tv.get("settings get secure screensaver_components");
        boolean homeOk = !c.launcher || home.contains("sa.urtv.home");
        boolean saverOk = saver.contains("sa.urtv.screensaver");
        report.add((homeOk ? "✓ " : "!! ") + "الواجهة الرئيسية: " + (home.contains("sa.urtv.home") ? "تلفزيونك" : "الأصلية"));
        report.add((saverOk ? "✓ " : "!! ") + "شاشة التوقف: " + (saverOk ? "شاشة تلفزيونك" : saver));
        report.add("✓ الخدمات المهمة شغالة: البث من الآيفون والأندرويد، والصوت، والتحديثات");
        for (String s : report) log.line("   " + s);
        if (!homeOk || !saverOk) throw new Stop("الفحص الأخير لقى مشكلة. اضغط كمّل عشان أعيد الخطوات الناقصة.");
    }

    /**
     * Before a run on a TV set up already: the owner may have changed answers since, so the choices are sent again,
     * and the home screen or the extras are done again if they were skipped then and wanted now.
     */
    void refreshChoices(Choices c) throws IOException {
        env.save("done:" + serial + ":settings", null);
        env.save("done:" + serial + ":check", null);
        if (c.launcher && !tv.get("cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN").contains("sa.urtv.home"))
            env.save("done:" + serial + ":launcher", null);
        if (c.extras) {
            List<String> have = packages(Arrays.asList(tv.get("pm list packages").split("\n")));
            if (!have.contains("com.phlox.tvwebbrowser") || !have.contains("org.smarttube.stable"))
                env.save("done:" + serial + ":extras", null);
        }
    }

    void clearMarks() {
        for (String step : STEPS) env.save("done:" + serial + ":" + step, null);
    }

    // ---- undo

    /** optimize/reset.sh: the TV as it came. Our apps go, what we turned off comes back, the stock home returns. */
    void reset() throws IOException {
        log.line("١/٤ الواجهة الأصلية أول (ما نخلي التلفزيون بدون واجهة)…");
        String stock = stockHome();
        List<String> back = new ArrayList<>();
        back.addAll(list("lists/tcl-safe.txt"));
        back.addAll(list("lists/tcl-optional.txt"));
        back.addAll(list("lists/home-replaced.txt"));
        for (String p : back) tv.shell("pm enable " + p);
        for (String cmd : new String[] {
            "pm enable com.google.android.tungsten.setupwraith",
            "touch /data/local/tmp/home_ok",
            "pm enable com.google.android.apps.tv.launcherx",
            "pm enable com.google.android.tvlauncher",
            "cmd notification allow_listener com.google.android.apps.tv.launcherx/.coreservices.notificationlistener.TvNotificationListenerService",
            "cmd role add-role-holder --user 0 android.app.role.HOME " + pkg(stock),
            "cmd package set-home-activity --user 0 " + stock,
            "input keyevent KEYCODE_HOME"}) tv.shell(cmd);
        boolean onScreen = false;
        for (int i = 0; i < 4 && !onScreen; i++) {
            env.sleep(6000);
            String top = tv.shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'");
            onScreen = top.contains("launcherx") || top.contains("tvlauncher");
            if (!onScreen) tv.shell("input keyevent KEYCODE_HOME");
        }
        if (!onScreen) throw new Stop("الواجهة الأصلية ما طلعت للحين. اضغط زر الرئيسية في الريموت، وبعدها اضغط الإرجاع مرة ثانية.");
        try { keep(); } catch (Stop ignored) { }

        log.line("٢/٤ شاشة توقف التلفزيون الأصلية…");
        tv.shell("settings delete secure screensaver_components");
        tv.shell("settings delete secure screensaver_default_component");
        String backup = env.load("backup:" + serial);
        if (backup != null) {
            List<String> vals = section(backup, "screensaver");
            String[][] keys = {{"secure", "screensaver_enabled"}, {"system", "screen_off_timeout"}};
            for (int i = 0; i < 2 && i + 1 < vals.size(); i++) {
                String val = vals.get(i + 1);
                if ("null".equals(val)) tv.shell("settings delete " + keys[i][0] + " " + keys[i][1]);
                else if (val.matches("[0-9]+")) tv.shell("settings put " + keys[i][0] + " " + keys[i][1] + " " + val);
            }
        }

        log.line("٣/٤ حذف تطبيقاتنا…");
        for (String p : new String[] {"sa.urtv.home", "sa.urtv.screensaver", "sa.yourtv.home", "sa.yourtv.screensaver",
                "com.abdullah.tvhome", "com.abdullah.tvsaver"})
            if (tv.get("pm uninstall " + p).contains("Success")) log.line("   حذفت " + p);
        if (backup != null) {   // TV Bro and SmartTube only if the setup put them there
            List<String> before = packages(section(backup, "all packages"));
            for (String p : new String[] {"com.phlox.tvwebbrowser", "org.smarttube.stable"})
                if (!before.contains(p) && tv.get("pm uninstall " + p).contains("Success")) log.line("   حذفت " + p);
        }

        log.line("٤/٤ رجّعت السرعات لإعدادات أندرويد…");
        tweaks(false);
        clearMarks();
        env.save("backup:" + serial, null);
        log.line("خلصت: التلفزيون رجع مثل ما كان.");
    }

    // ---- lists

    /** A package list the app carries (optimize/lists): first word of each line, comments and blanks skipped. */
    List<String> list(String path) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : env.asset(path).split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            out.add(t.split("\\s+")[0]);
        }
        return out;
    }

}
