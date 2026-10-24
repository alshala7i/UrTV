package sa.urtv.screensaver;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Everything the user picks in settings, and the dashboard URL built from it. */
final class Prefs {
    static final String TZ = "Asia/Riyadh";   // until ADB sets another (SaverReceiver: --es tz)

    // Aladhan's prayer calculation method (sent to the page as #method=), Arabic label; Umm al-Qura by default
    static final String[][] PRAYER_METHODS = {
        {"4", "أم القرى"}, {"8", "منطقة الخليج"}, {"9", "الكويت"}, {"10", "قطر"}, {"16", "دبي"},
        {"5", "الهيئة المصرية"}, {"3", "رابطة العالم الإسلامي"}, {"13", "تركيا"},
    };

    // TheSportsDB id, Arabic name; more teams come from the in-app search
    static final String[][] TEAM_PRESETS = {
        {"136013", "الهلال"}, {"136137", "المنتخب السعودي"},
    };

    // League name exactly as TheSportsDB spells it (checked from CI by .github/tools/team_ids.py), Arabic label.
    // Their clubs come from assets/shared/teams.json (Teams), which CI builds from these leagues' matches
    // (.github/tools/team_ids.py reads this list); a new league here needs its minimum club count there.
    static final String[][] LEAGUES = {
        {"Saudi-Arabian Pro League", "دوري روشن السعودي"},
        {"Saudi First Division League", "دوري يلو (الدرجة الأولى)"},
        {"English Premier League", "الدوري الإنجليزي"},
        {"Spanish La Liga", "الدوري الإسباني"},
        {"Italian Serie A", "الدوري الإيطالي"},
        {"German Bundesliga", "الدوري الألماني"},
        {"French Ligue 1", "الدوري الفرنسي"},
        {"Portuguese Primeira Liga", "الدوري البرتغالي"},
        {"Dutch Eredivisie", "الدوري الهولندي"},
        {"Turkish Super Lig", "الدوري التركي"},
        {"Egyptian Premier League", "الدوري المصري"},
        {"UAE Pro League", "الدوري الإماراتي"},
        {"Qatar Stars League", "الدوري القطري"},
        {"American Major League Soccer", "الدوري الأمريكي"},
    };

    // TVMaze genre (sent to the page), Arabic label
    static final String[][] GENRES = {
        {"Drama", "دراما"}, {"Comedy", "كوميديا"}, {"Action", "أكشن"}, {"Crime", "جريمة"},
        {"Thriller", "إثارة"}, {"Mystery", "غموض"}, {"Science-Fiction", "خيال علمي"},
        {"Fantasy", "فانتازيا"}, {"Adventure", "مغامرة"}, {"Romance", "رومانسي"},
        {"Family", "عائلي"}, {"History", "تاريخي"}, {"Horror", "رعب"},
        {"Anime", "أنمي"}, {"Documentary", "وثائقي"}, {"Sports", "رياضة"},
    };

    // TVMaze language, Arabic label
    static final String[][] LANGS = {
        {"English", "الإنجليزية"}, {"Arabic", "العربية"}, {"Korean", "الكورية"},
        {"Japanese", "اليابانية"}, {"Turkish", "التركية"}, {"Spanish", "الإسبانية"},
        {"French", "الفرنسية"}, {"Hindi", "الهندية"}, {"German", "الألمانية"},
    };

    static final String[] MIN_RATINGS = {"0", "6", "6.5", "7", "7.5", "8"};

    // minutes between background photos (sent to the page as #photos=; 30 is what it always did)
    static final String[] PHOTO_MINUTES = {"5", "10", "15", "30", "60"};

    // minutes of idle before Android starts the screensaver (the TV's own screen_off_timeout; not stored here)
    static final String[] IDLE_MINUTES = {"1", "2", "5", "10", "15", "30"};

    /** May this app change system settings (API 23+, and only if the owner allowed it for the app)? */
    static boolean canWriteSettings(Context c) {
        return Build.VERSION.SDK_INT >= 23 && Settings.System.canWrite(c);
    }

    /** The TV's idle delay in ms; readable without any permission. -1 when the TV does not say. */
    static long idleMs(Context c) {
        return Settings.System.getLong(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, -1);
    }

    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    /** {name, latitude, longitude}; Riyadh until the user picks */
    String[] city() {
        return new String[] {sp.getString("cityName", "الرياض"), sp.getString("cityLat", "24.6877"), sp.getString("cityLon", "46.7219")};
    }
    void setCity(String name, String lat, String lon) {
        sp.edit().putString("cityName", name).putString("cityLat", lat).putString("cityLon", lon).apply();
    }

    /** The city's time zone, for the clock and prayer times; Riyadh's until ADB sets another. */
    String tz() { return sp.getString("tz", TZ); }
    void setTz(String v) { sp.edit().putString("tz", v).apply(); }

    String prayerMethod() { return sp.getString("prayerMethod", "4"); }
    void setPrayerMethod(String v) { sp.edit().putString("prayerMethod", v).apply(); }

    /** The next-prayer block under the clock; on unless the user turns it off. */
    boolean showPrayers() { return sp.getBoolean("showPrayers", true); }
    void setShowPrayers(boolean v) { sp.edit().putBoolean("showPrayers", v).apply(); }

    static final int MAX_TEAMS = 10;

    /** Followed teams in priority order, "id|name". Migrates the old two team fields once. */
    List<String> teams() {
        if (!sp.contains("teams")) {
            List<String> v = new ArrayList<>();
            for (int slot = 1; slot <= 2; slot++) {
                String[] def = TEAM_PRESETS[slot - 1];
                String id = sp.getString("team" + slot + "Id", def[0]);
                if (!id.isEmpty()) v.add(id + "|" + sp.getString("team" + slot + "Name", def[1]));
            }
            return v;
        }
        return list("teams");
    }

    /** false when already followed or the list is full */
    boolean addTeam(String id, String name) {
        List<String> v = teams();
        for (String t : v) if (t.startsWith(id + "|")) return false;
        if (v.size() >= MAX_TEAMS) return false;
        v.add(id + "|" + name.replace("|", " ").replace(";", " "));
        setList("teams", v);
        return true;
    }

    // The card beside the clock: value sent to the page (#panel=), Arabic label
    static final String[][] PANELS = {
        {"shows", "جديد المسلسلات"}, {"prayers", "مواقيت الصلاة اليوم"}, {"forecast", "طقس الأيام القادمة"},
        {"matches", "مباريات فرقك القادمة"}, {"verse", "آية أو حديث اليوم"}, {"none", "لا شيء"},
    };

    /** The cards beside the clock, comma-separated; more than one take turns. Older builds had an on/off switch
     *  for the card: off becomes "none", otherwise the verse card. */
    String panel() { return sp.getString("panel", sp.getBoolean("showWeek", true) ? "verse" : "none"); }
    void setPanel(String v) { sp.edit().putString("panel", v).apply(); }
    boolean showsPanel(String one) { return java.util.Arrays.asList(panel().split(",")).contains(one); }

    /** "prayers,matches" -> "مواقيت الصلاة اليوم، مباريات فرقك القادمة" */
    static String panelLabel(String v) {
        StringBuilder b = new StringBuilder();
        for (String one : v.split(","))
            for (String[] p : PANELS) if (p[0].equals(one)) b.append(b.length() > 0 ? "، " : "").append(p[1]);
        return b.length() > 0 ? b.toString() : PANELS[0][1];
    }

    /** The cards in PANELS' order without repeats, "none" only alone; null if any is unknown or none is given. */
    static String panels(String raw) {
        List<String> want = java.util.Arrays.asList(raw.split(","));
        for (String w : want) {
            boolean known = false;
            for (String[] p : PANELS) if (p[0].equals(w.trim())) known = true;
            if (!known) return null;
        }
        List<String> out = new ArrayList<>();
        for (String[] p : PANELS) for (String w : want) if (p[0].equals(w.trim()) && !out.contains(p[0])) out.add(p[0]);
        if (out.size() > 1) out.remove("none");
        return out.isEmpty() ? null : join(out, ",");
    }

    List<String> list(String key) {
        List<String> out = new ArrayList<>();
        for (String s : sp.getString(key, "").split(";")) if (!s.isEmpty()) out.add(s);
        return out;
    }
    void setList(String key, List<String> v) { sp.edit().putString(key, join(v, ";")).apply(); }

    /** shows picked one by one, stored as "id|name" */
    List<String> shows() { return list("shows"); }

    String minRating() { return sp.getString("minRating", "7"); }
    void setMinRating(String v) { sp.edit().putString("minRating", v).apply(); }

    String photoMinutes() { return sp.getString("photoMinutes", "30"); }
    void setPhotoMinutes(String v) { sp.edit().putString("photoMinutes", v).apply(); }

    boolean autoUpdate() { return sp.getBoolean("autoUpdate", true); }
    void setAutoUpdate(boolean v) { sp.edit().putBoolean("autoUpdate", v).apply(); }

    SharedPreferences raw() { return sp; }

    /** The page reads its settings from the fragment; a ?query fails on file:// in WebView. */
    String dashboardUrl(Context c) {
        File remote = Updater.pageFile(c);
        // A downloaded page older than the installed app is stale: the APK carries a newer copy.
        long installed = 0;
        try { installed = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime; }
        catch (Exception ignored) { }
        String base = autoUpdate() && remote.isFile() && remote.lastModified() > installed
            ? Uri.fromFile(remote).toString()
            : "file:///android_asset/tv.html";
        return base + "#" + fragment();
    }

    /** Everything the page needs, as "city=..&lat=..&..."; also read by the launcher (ConfigProvider). */
    String fragment() {
        String[] city = city();
        List<String> ids = new ArrayList<>();
        for (String s : shows()) ids.add(s.split("\\|", 2)[0]);
        return "city=" + Uri.encode(city[0]) + "&lat=" + city[1] + "&lon=" + city[2]
            + "&tz=" + Uri.encode(tz())
            + "&method=" + prayerMethod()
            + "&prayers=" + (showPrayers() ? "1" : "0")
            + "&teams=" + join(teamIds(), ",")
            + "&panel=" + panel()
            + "&week=" + ("none".equals(panel()) ? "0" : "1")   // for a page older than #panel
            + "&genres=" + Uri.encode(join(list("genres"), ","))
            + "&langs=" + Uri.encode(join(list("langs"), ","))
            + "&shows=" + join(ids, ",")
            + "&minrating=" + minRating()
            + "&photos=" + photoMinutes();
    }

    /** The card on the launcher's home screen: one of PANELS, off ("none") until the user picks one. */
    String homePanel() { return sp.getString("homePanel", "none"); }
    void setHomePanel(String v) { sp.edit().putString("homePanel", v).apply(); }

    List<String> teamIds() {
        List<String> ids = new ArrayList<>();
        for (String t : teams()) ids.add(t.split("\\|", 2)[0]);
        return ids;
    }

    static String join(List<String> v, String sep) {
        StringBuilder b = new StringBuilder();
        for (String s : v) { if (b.length() > 0) b.append(sep); b.append(s); }
        return b.toString();
    }
}
