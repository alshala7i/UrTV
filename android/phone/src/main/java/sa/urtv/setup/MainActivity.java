package sa.urtv.setup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sa.urtv.screensaver.Shared;

/**
 * The phone app, in three parts: the questions (everything the setup needs, asked first), getting the TV ready
 * (developer mode, the checklist), then the setup itself (Setup, the steps of optimize/run.sh) with the
 * favourites and the undo. One activity; screens are built in code, like the TV apps' settings.
 */
public class MainActivity extends Activity {
    private static final int GOLD = 0xFFD6AF6B, TEXT = 0xFFF6EFE0, SUB = 0xFFC6BBA4, CARD = 0x14FFFFFF, WARN = 0xFFFFB4A0;

    private SharedPreferences answers;
    private LinearLayout page;
    private ScrollView scroll;
    private final ArrayDeque<Runnable> history = new ArrayDeque<>();
    private Runnable current;
    private boolean fromSummary;
    private volatile boolean busy;
    private TextView logView, statusView;
    private final StringBuilder log = new StringBuilder();
    private LinearLayout actions;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        answers = getSharedPreferences("answers", MODE_PRIVATE);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {0xFF0A2016, 0xFF04120C, 0xFF020806}));
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        page.setPadding(dp(20), dp(36), dp(20), dp(36));
        scroll.addView(page);
        setContentView(scroll);
        getWindow().setStatusBarColor(0xFF0A2016);
        getWindow().setNavigationBarColor(0xFF020806);
        current = answers.getBoolean("answered", false) ? this::summary : this::welcome;
        render();
    }

    // ---- navigation

    private void go(Runnable screen) {
        if (current != null) history.push(current);
        current = screen;
        render();
    }

    /** the next question, or back to the summary when the owner came from there to change one answer */
    private void next(Runnable screen) {
        if (fromSummary) { fromSummary = false; go(this::summary); } else go(screen);
    }

    private void render() {
        page.removeAllViews();
        current.run();
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    @Override
    public void onBackPressed() {
        if (busy) { note("الإعداد شغال. خلّ التطبيق مفتوح لين يخلص."); return; }
        if (history.isEmpty()) { super.onBackPressed(); return; }
        current = history.pop();
        render();
    }

    // ---- ① the questions

    private void welcome() {
        title("تلفزيونك");
        text("يركّب لتلفزيونك واجهة عربية، وشاشة توقف فيها الساعة ومواقيت الصلاة ومباريات أنديتك، ويسرّعه بأمان.");
        text("بسألك كم سؤال، وبعدها أوريك وش تسوي في التلفزيون، وأنا أكمل الباقي من الجوال. تقدر ترجّع التلفزيون مثل ما كان بأي وقت.");
        space(16);
        button("ابدأ", true, () -> go(this::tvType));
    }

    private void question(int n, String title, String hint) {
        small("سؤال " + n + " من 8");
        title(title);
        if (hint != null) text(hint);
        space(8);
    }

    private void tvType() {
        question(1, "وش نوع واجهة تلفزيونك؟", "شوف الشاشة الرئيسية في التلفزيون.");
        String t = answers.getString("tvType", "");
        option("جوجل تي في", "فوق الشاشة تبويبات مثل «لك» و«التطبيقات». أغلب التلفزيونات الجديدة: تي سي إل، سوني، هايسنس، شاومي.",
            "google".equals(t), () -> { save("tvType", "google"); next(this::country); });
        option("أندرويد تي في", "واجهة أقدم: صفوف تطبيقات، والإعدادات من رمز الترس.",
            "android".equals(t), () -> { save("tvType", "android"); next(this::country); });
        option("ما أدري", "بوريك الطريقتين.", "unknown".equals(t), () -> { save("tvType", "unknown"); next(this::country); });
        space(12);
        option("سامسونج، أو إل جي، أو فاير تي في", null, false, () -> dialog("هالتلفزيونات ما تنفع",
            "أنظمتها مقفلة وما تسمح بتغيير الواجهة أو شاشة التوقف. الحل: جهاز جوجل تي في صغير ينركّب في منفذ HDMI، وبعدها كل شي يشتغل عليه.", null));
    }

    private void country() {
        question(2, "وين تسكن؟", "عشان توقيت مدينتك وطريقة حساب الصلاة فيها.");
        String now = answers.getString("country", "");
        for (Places.Country c : Places.COUNTRIES) {
            option(c.name, null, c.name.equals(now), () -> {
                boolean changed = !c.name.equals(now);
                SharedPreferences.Editor e = answers.edit().putString("country", c.name).putString("tz", c.tz).putString("method", c.method);
                if (changed) e.remove("city");
                e.apply();
                if (changed) fromSummary = false;   // a new country needs its city too
                next(this::city);
            });
        }
    }

    private void city() {
        Places.Country country = Places.byName(answers.getString("country", ""));
        question(3, "أي مدينة؟", null);
        String now = answers.getString("city", "");
        if (country.cities.length > 0) {
            for (String[] c : country.cities) option(c[0], null, c[0].equals(now), () -> pickCity(c[0], c[1], c[2]));
            return;
        }
        final List<String[]> all = Shared.cities();
        EditText q = edit("اكتب اسم المدينة");
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        page.addView(results);
        Runnable fill = () -> {
            results.removeAllViews();
            String s = q.getText().toString().trim();
            int n = 0;
            for (String[] c : all) {
                if (!s.isEmpty() && !c[1].contains(s)) continue;
                if (s.isEmpty() && !c[0].equals("منطقة الرياض") && !Arrays.asList("جدة", "مكة المكرمة", "المدينة المنورة", "الدمام", "الخبر", "بريدة", "أبها", "تبوك").contains(c[1])) continue;
                results.addView(optionView(c[1], c[0], c[1].equals(now), () -> pickCity(c[1], c[2], c[3])));
                if (++n >= 25) break;
            }
            if (n == 0) results.addView(textView("ما لقيتها. جرّب اسم أقرب مدينة كبيرة.", SUB, 15));
        };
        q.addTextChangedListener(watcher(fill));
        fill.run();
    }

    private void pickCity(String name, String lat, String lon) {
        answers.edit().putString("city", name).putString("lat", lat).putString("lon", lon).apply();
        next(this::clubs);
    }

    private void clubs() {
        question(4, "أي أندية تتابع؟", "لين 10 أندية. تطلع مبارياتها في شاشة التوقف.");
        final List<String> picked = new ArrayList<>(list("teams"));
        LinearLayout chosen = new LinearLayout(this);
        chosen.setOrientation(LinearLayout.VERTICAL);
        page.addView(chosen);
        EditText q = edit("اكتب اسم النادي، بالعربي أو الإنجليزي");
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        page.addView(results);
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            chosen.removeAllViews();
            for (String t : new ArrayList<>(picked)) {
                String[] p = t.split(":", 3);
                chosen.addView(optionView("✓ " + (p.length > 2 ? p[2] : p[1]), "اضغط عشان تشيله", true, () -> {
                    picked.remove(t); saveList("teams", picked); refresh[0].run(); }));
            }
            results.removeAllViews();
            String s = q.getText().toString().trim();
            if (s.length() < 2) return;
            int n = 0;
            for (String[] t : Shared.searchTeams(this, s)) {
                String entry = t[0] + ":" + t[1].replace(":", " ").replace(";", " ") + ":" + t[2];
                boolean has = false;
                for (String x : picked) if (x.startsWith(t[0] + ":")) has = true;
                if (has) continue;
                results.addView(optionView(t[2], null, false, () -> {
                    if (picked.size() >= 10) { note("10 أندية بالأكثر"); return; }
                    picked.add(entry); saveList("teams", picked); q.setText(""); refresh[0].run(); }));
                if (++n >= 12) break;
            }
            if (n == 0) results.addView(textView("ما لقيته. جرّب الاسم بالإنجليزي.", SUB, 15));
        };
        q.addTextChangedListener(watcher(refresh[0]));
        refresh[0].run();
        space(12);
        button("التالي", true, () -> next(this::panel));
        button("ما أتابع أندية", false, () -> { saveList("teams", new ArrayList<>()); next(this::panel); });
    }

    private void panel() {
        question(5, "وش يطلع جنب الساعة؟", null);
        String now = answers.getString("panel", "verse");
        for (String[] p : Places.PANELS) {
            String sub = "matches".equals(p[0]) && list("teams").isEmpty() ? "ما اخترت أندية، فبتكون فاضية" : null;
            option(p[1], sub, p[0].equals(now), () -> { save("panel", p[0]); next(this::prayers); });
        }
    }

    private void prayers() {
        question(6, "تبي الصلاة القادمة والإقامة تحت الساعة؟", "في رمضان يبقى عدّاد الإفطار شغال حتى لو أخفيتها.");
        boolean now = answers.getBoolean("prayers", true);
        option("نعم، تظهر", null, now, () -> { saveBool("prayers", true); next(this::launcher); });
        option("لا، أخفيها", null, !now, () -> { saveBool("prayers", false); next(this::launcher); });
    }

    private void launcher() {
        question(7, "تبي واجهة عربية جديدة للتلفزيون؟", "فيها تطبيقاتك المفضلة، والمدخلات، والواي فاي، والإعدادات. وفيها مفتاح أمان: إذا ما اشتغلت، ترجع الواجهة الأصلية لحالها.");
        boolean now = answers.getBoolean("launcher", true);
        option("نعم، ركّبها", null, now, () -> { saveBool("launcher", true); next(this::extras); });
        option("لا، أبقي واجهة التلفزيون", "تنركّب شاشة التوقف والتسريع بس.", !now, () -> { saveBool("launcher", false); next(this::extras); });
    }

    private void extras() {
        question(8, "أركّب لك تطبيقين مجانيين؟", "متصفح للتلفزيون (TV Bro)، ويوتيوب بدون إعلانات (SmartTube).");
        boolean now = answers.getBoolean("extras", true);
        option("نعم", null, now, () -> { saveBool("extras", true); next(this::summary); });
        option("لا", null, !now, () -> { saveBool("extras", false); next(this::summary); });
    }

    private void summary() {
        title("إجاباتك");
        text("اضغط أي سطر عشان تغيّره.");
        space(4);
        String type = answers.getString("tvType", "unknown");
        row("نوع الواجهة", "google".equals(type) ? "جوجل تي في" : "android".equals(type) ? "أندرويد تي في" : "ما أدري", this::tvType);
        row("المدينة", answers.getString("city", "—") + "، " + answers.getString("country", "—"), this::country);
        List<String> teams = list("teams");
        StringBuilder names = new StringBuilder();
        for (String t : teams) { String[] p = t.split(":", 3); names.append(names.length() > 0 ? "، " : "").append(p.length > 2 ? p[2] : p[1]); }
        row("الأندية", teams.isEmpty() ? "ما أتابع" : names.toString(), this::clubs);
        row("جنب الساعة", Places.panelLabel(answers.getString("panel", "verse")), this::panel);
        row("الصلاة القادمة", answers.getBoolean("prayers", true) ? "تظهر" : "مخفية", this::prayers);
        row("الواجهة الجديدة", answers.getBoolean("launcher", true) ? "نعم" : "لا", this::launcher);
        row("المتصفح ويوتيوب", answers.getBoolean("extras", true) ? "نعم" : "لا", this::extras);
        space(16);
        if (!answers.contains("city")) {
            text("باقي تختار المدينة.");
            return;
        }
        button(answers.getBoolean("finished", false) ? "أرسل التغييرات للتلفزيون" : "التالي: تجهيز التلفزيون", true, () -> {
            saveBool("answered", true);
            go(answers.getBoolean("finished", false) ? this::run : this::devSteps);
        });
        if (answers.getBoolean("finished", false)) {
            button("ترتيب المفضلة في الواجهة", false, () -> go(this::favourites));
            button("إرجاع التلفزيون مثل ما كان", false, this::askReset);
        }
    }

    private void row(String label, String value, Runnable edit) {
        page.addView(optionView(label, value, false, () -> { fromSummary = true; go(edit); }));
    }

    // ---- ② getting the TV ready

    private void devSteps() {
        title("في التلفزيون: وضع المطوّر");
        text("مرة وحدة بس. أسماء القوائم تطلع بالإنجليزي في أغلب التلفزيونات، فكتبتها تحت كل خطوة.");
        String type = answers.getString("tvType", "unknown");
        if (!"android".equals(type)) {
            if ("unknown".equals(type)) header("إذا واجهتك جوجل تي في");
            steps(new String[][] {
                {"افتح الإعدادات من الرمز اللي فوق يمين الشاشة الرئيسية.", "Settings"},
                {"ادخل النظام.", "System"},
                {"ادخل حول.", "About"},
                {"انزل لين رقم النسخة، واضغط عليه 7 مرات ورا بعض لين تطلع رسالة إنك صرت مطوّر. إذا طلب رمز، اكتب رمز التلفزيون.", "Android TV OS build"},
                {"ارجع للنظام، بتلقى خيار جديد: خيارات المطوّر.", "Developer options"},
                {"شغّل تصحيح الأخطاء.", "USB debugging"},
                {"إذا فيه خيار تصحيح الشبكة، شغّله بعد.", "Network debugging"},
            });
        }
        if (!"google".equals(type)) {
            if ("unknown".equals(type)) header("إذا واجهتك أندرويد تي في");
            steps(new String[][] {
                {"افتح الإعدادات.", "Settings"},
                {"ادخل تفضيلات الجهاز.", "Device Preferences"},
                {"ادخل حول.", "About"},
                {"اضغط على رقم النسخة 7 مرات ورا بعض.", "Build"},
                {"ارجع لتفضيلات الجهاز، وادخل خيارات المطوّر.", "Developer options"},
                {"شغّل تصحيح الأخطاء.", "USB debugging"},
                {"إذا فيه خيار تصحيح الشبكة، شغّله بعد.", "Network debugging"},
            });
        }
        small("ما لقيت الخيار؟ دوّر في الإعدادات على كلمة Build. بعض الموديلات تحطه في مكان مختلف شوي.");
        space(12);
        button("سويتها", true, () -> go(this::checklist));
    }

    private void steps(String[][] s) {
        for (int i = 0; i < s.length; i++) {
            LinearLayout card = card();
            card.addView(textView((i + 1) + ". " + s[i][0], TEXT, 16));
            TextView en = textView(s[i][1], GOLD, 15);
            en.setTextDirection(View.TEXT_DIRECTION_LTR);
            en.setGravity(Gravity.START);
            card.addView(en);
        }
    }

    private void checklist() {
        title("قبل ما نبدأ");
        boolean wifi = Discovery.wifiAddress(this) != null;
        TextView w = textView(wifi ? "✓ جوالك متصل بالواي فاي" : "!! جوالك مو متصل بالواي فاي. اتصل بنفس شبكة التلفزيون.", wifi ? GOLD : WARN, 16);
        page.addView(w);
        space(8);
        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        boolean charging = bm != null && bm.isCharging();
        final List<CheckBox> boxes = new ArrayList<>();
        String[] items = {
            "الجوال والتلفزيون على نفس شبكة الواي فاي (مو شبكة الضيوف)",
            "التلفزيون شغال، مو في وضع الاستعداد",
            "شغّلت تصحيح الأخطاء من الخطوات اللي قبل",
            "الريموت قريب: التلفزيون بيطلب منك ضغطة موافقة وحدة",
            charging ? "الجوال على الشاحن ✓" : "الجوال على الشاحن، لأن الإعداد ياخذ حوالي نص ساعة",
        };
        for (String item : items) {
            CheckBox b = new CheckBox(this);
            b.setText(item);
            b.setTextColor(TEXT);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            b.setTypeface(font("Thmanyah-Regular"));
            b.setButtonTintList(android.content.res.ColorStateList.valueOf(GOLD));
            b.setPadding(dp(8), dp(10), dp(8), dp(10));
            page.addView(b);
            boxes.add(b);
        }
        space(8);
        small("خلّ التطبيق مفتوح طول الإعداد. التلفزيون بيعيد التشغيل مرة أو مرتين.");
        space(12);
        button("ابدأ الإعداد", true, () -> {
            for (CheckBox b : boxes) if (!b.isChecked()) { note("علّم على كل النقاط أول"); return; }
            if (Discovery.wifiAddress(this) == null) { note("اتصل بالواي فاي أول"); return; }
            go(this::run);
        });
    }

    // ---- ③ the setup

    private void run() {
        title("الإعداد");
        statusView = textView("", TEXT, 17);
        page.addView(statusView);
        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        page.addView(actions);
        space(8);
        logView = textView(log.toString(), SUB, 13);
        page.addView(logView);
        start(null);
    }

    private void status(String s) { runOnUiThread(() -> { if (statusView != null) statusView.setText(s); }); }

    private void say(String line) {
        runOnUiThread(() -> {
            log.append(line).append('\n');
            if (logView != null) logView.setText(log);
        });
    }

    private void actionsClear() { runOnUiThread(() -> { if (actions != null) actions.removeAllViews(); }); }

    private void action(String label, boolean primary, Runnable r) {
        runOnUiThread(() -> { if (actions != null) actions.addView(buttonView(label, primary, r)); });
    }

    /** host: the TV to use; null = the one used last time, else look for it on the Wi-Fi */
    private void start(String host) {
        if (busy) return;
        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        actionsClear();
        new Thread(() -> {
            try {
                String h = host != null ? host : findTv();
                if (h == null) return;
                Tv tv = new Tv(h, AdbKey.load(new java.io.File(getFilesDir(), "adb")), this::say);
                Setup setup = new Setup(tv, new AndroidEnv(), this::say);
                status("الحين بتطلع رسالة على التلفزيون: «Allow USB debugging?»\nاضغط Allow، وعلّم «Always allow».");
                setup.probe();
                answers.edit().putString("host", h).apply();
                setup.refreshChoices(choices());
                status("شغّال… خلّ التطبيق مفتوح. التلفزيون بيعيد التشغيل مرة أو مرتين.");
                setup.run(choices());
                tv.close();
                saveBool("finished", true);
                runOnUiThread(() -> { history.clear(); go(answers.getBoolean("launcher", true) ? this::favourites : this::done); });
            } catch (Adb.AuthException e) {
                failed("التلفزيون ما سمح للجوال. شغّل الإعداد مرة ثانية، ولما تطلع الرسالة اضغط Allow وعلّم Always allow.", null);
            } catch (Adb.TlsOnlyException e) {
                failed("هالتلفزيون يقبل بس الاقتران برمز (Wireless debugging)، والتطبيق ما يدعمه للحين. استخدم طريقة الكمبيوتر اللي في صفحة المشروع.", null);
            } catch (Tv.GoneException | java.net.ConnectException | java.net.SocketTimeoutException e) {
                failed("التلفزيون ما يرد. تأكد إنه شغال، ومو في وضع الاستعداد، وعلى نفس شبكة الجوال. بعدها اضغط كمّل: يكمل من مكان ما وقف.", null);
            } catch (Setup.Stop e) {
                failed(e.getMessage(), null);
            } catch (Exception e) {
                failed("صار خطأ: " + e.getMessage() + "\nاضغط كمّل: يكمل من مكان ما وقف.", null);
            } finally {
                busy = false;
                runOnUiThread(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            }
        }).start();
    }

    private void failed(String message, String host) {
        status("!! " + message);
        action("كمّل", true, () -> start(host));
        action("إرجاع التلفزيون مثل ما كان", false, this::askReset);
    }

    /** The TV's address: last time's if it still answers, else a scan of the Wi-Fi; null when the owner has to act. */
    private String findTv() {
        String last = answers.getString("host", null);
        if (last != null && Discovery.open(last, Tv.PORT)) return last;
        status("أدوّر على التلفزيون في الشبكة…");
        Discovery.Found f = Discovery.scan(this);
        if (f.myAddress == null) {
            status("!! جوالك مو متصل بالواي فاي. اتصل بنفس شبكة التلفزيون.");
            action("أدوّر مرة ثانية", true, () -> start(null));
            return null;
        }
        if (f.adb.size() == 1) return f.adb.get(0);
        if (f.adb.size() > 1) {
            status("لقيت أكثر من جهاز. أي واحد التلفزيون؟");
            for (String h : f.adb) action(h, false, () -> start(h));
            return null;
        }
        status(f.cast.isEmpty()
            ? "!! ما لقيت التلفزيون. تأكد إنه شغال وعلى نفس الواي فاي، وإن تصحيح الأخطاء مفعّل."
            : "!! لقيت تلفزيون بس تصحيح الأخطاء مطفي فيه. شغّله من خيارات المطوّر وارجع.");
        action("أدوّر مرة ثانية", true, () -> start(null));
        runOnUiThread(() -> {
            if (actions == null) return;
            actions.addView(textView("أو اكتب عنوان التلفزيون. تلقاه في التلفزيون: الإعدادات، الشبكة، اسم الواي فاي.", SUB, 14));
            EditText ip = new EditText(this);
            ip.setHint("192.168.x.x");
            ip.setInputType(InputType.TYPE_CLASS_PHONE);
            ip.setTextColor(TEXT);
            ip.setHintTextColor(0x66C6BBA4);
            ip.setTextDirection(View.TEXT_DIRECTION_LTR);
            actions.addView(ip);
            actions.addView(buttonView("اتصل", false, () -> {
                String s = ip.getText().toString().trim();
                if (s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) start(s); else note("اكتب العنوان مثل المثال");
            }));
        });
        return null;
    }

    private Setup.Choices choices() {
        Setup.Choices c = new Setup.Choices();
        c.city = answers.getString("city", c.city);
        c.lat = answers.getString("lat", c.lat);
        c.lon = answers.getString("lon", c.lon);
        c.tz = answers.getString("tz", c.tz);
        c.method = answers.getString("method", c.method);
        for (String t : list("teams")) { String[] p = t.split(":", 3); c.teams.add(p[0] + ":" + p[1]); }
        c.panel = answers.getString("panel", "verse");
        c.prayers = answers.getBoolean("prayers", true);
        c.launcher = answers.getBoolean("launcher", true);
        c.extras = answers.getBoolean("extras", true);
        return c;
    }

    // ---- after the setup

    private void favourites() {
        title("التطبيقات المفضلة");
        text("اضغط التطبيقات بالترتيب اللي تبيه في الواجهة، من 5 إلى 10. تقدر تتخطى وترتبها بعدين.");
        statusView = textView("أجيب تطبيقات التلفزيون…", SUB, 15);
        page.addView(statusView);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list);
        final List<String> order = new ArrayList<>();
        final Map<String, String> names = new HashMap<>();
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        page.addView(bottom);
        bottom.addView(buttonView("احفظ الترتيب", true, () -> {
            if (order.isEmpty()) { note("اختر تطبيق واحد على الأقل"); return; }
            withTv("أحفظ…", s -> {
                String out = s.dock("SET_DOCK", "--es apps " + String.join(",", order));
                if (out.startsWith("error:")) throw new Setup.Stop("ما انحفظ: " + out);
                runOnUiThread(() -> go(this::done));
            });
        }));
        bottom.addView(buttonView("تخطي", false, () -> go(this::done)));
        withTv(null, s -> {
            String out = s.dock("LIST_APPS", "");
            runOnUiThread(() -> {
                statusView.setText(out.isEmpty() ? "ما وصلت للواجهة الجديدة. تقدر ترتبها من التلفزيون: «ترتيب المفضلة»." : "");
                for (String line : out.split("\n")) {
                    String[] p = line.split(" \\| ", 2);
                    if (p.length < 2) continue;
                    names.put(p[0].trim(), p[1].trim());
                }
                final Runnable[] fill = new Runnable[1];
                fill[0] = () -> {
                    list.removeAllViews();
                    for (Map.Entry<String, String> e : names.entrySet()) {
                        int at = order.indexOf(e.getKey());
                        list.addView(optionView((at >= 0 ? (at + 1) + ". " : "") + e.getValue(), null, at >= 0, () -> {
                            if (order.contains(e.getKey())) order.remove(e.getKey());
                            else if (order.size() < 10) order.add(e.getKey());
                            fill[0].run();
                        }));
                    }
                };
                fill[0].run();
            });
        });
    }

    private void done() {
        title("خلص الإعداد");
        text("جرّب: اطلع من أي تطبيق واضغط زر الرئيسية، وخلّ التلفزيون شوي لين تطلع شاشة التوقف. وجرّب البث من جوالك مرة.");
        text("تغيّر الإعدادات بعدين من هالتطبيق، أو من تطبيق «شاشة تلفزيونك» في التلفزيون.");
        space(12);
        button("إجاباتي والتغييرات", true, () -> { history.clear(); go(this::summary); });
        if (answers.getBoolean("launcher", true)) button("ترتيب المفضلة", false, () -> go(this::favourites));
        button("إرجاع التلفزيون مثل ما كان", false, this::askReset);
    }

    private void askReset() {
        dialog("ترجّع التلفزيون مثل ما كان؟",
            "تنحذف تطبيقاتنا، واللي أطفيناه يرجع، وواجهة التلفزيون وشاشة توقفه الأصلية ترجع. تطبيقاتك وحساباتك ما تنلمس.",
            () -> go(this::resetScreen));
    }

    private void resetScreen() {
        title("إرجاع التلفزيون");
        statusView = textView("", TEXT, 17);
        page.addView(statusView);
        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        page.addView(actions);
        logView = textView("", SUB, 13);
        page.addView(logView);
        log.setLength(0);
        withTv("أرجّع التلفزيون…", s -> {
            s.reset();
            answers.edit().putBoolean("finished", false).apply();
            status("خلص: التلفزيون رجع مثل ما كان.");
            action("إعداد من جديد", true, () -> { history.clear(); go(this::summary); });
        });
    }

    interface TvJob { void run(Setup s) throws Exception; }

    /** Something short on the TV (favourites, reset): connects to the last TV, runs it, says what went wrong. */
    private void withTv(String what, TvJob job) {
        if (busy) return;
        busy = true;
        if (what != null) status(what);
        new Thread(() -> {
            Tv tv = null;
            try {
                String h = answers.getString("host", null);
                if (h == null) throw new Setup.Stop("ما أعرف عنوان التلفزيون. سوّ الإعداد أول.");
                tv = new Tv(h, AdbKey.load(new java.io.File(getFilesDir(), "adb")), this::say);
                Setup s = new Setup(tv, new AndroidEnv(), this::say);
                s.probe();
                job.run(s);
            } catch (Setup.Stop e) {
                status("!! " + e.getMessage());
            } catch (Exception e) {
                status("!! ما وصلت للتلفزيون. تأكد إنه شغال وعلى نفس الشبكة، وجرّب مرة ثانية.");
            } finally {
                if (tv != null) tv.close();
                busy = false;
            }
        }).start();
    }

    /** What Setup needs from the phone: its files, the network, its storage. */
    private final class AndroidEnv implements Setup.Env {
        private final SharedPreferences store = getSharedPreferences("setup", MODE_PRIVATE);

        public String asset(String path) throws IOException {
            try (InputStream in = getAssets().open(path)) { return new String(readAll(in), StandardCharsets.UTF_8); }
        }

        public byte[] download(String url) throws IOException {
            for (int attempt = 0; ; attempt++) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "UrTV-setup");   // GitHub's API wants one
                try {
                    int code = c.getResponseCode();
                    if (code != 200) throw new IOException("HTTP " + code + " " + url);
                    try (InputStream in = c.getInputStream()) { return readAll(in); }
                } catch (IOException e) {
                    if (attempt >= 2) throw e;
                    sleep(3000L * (attempt + 1));
                } finally {
                    c.disconnect();
                }
            }
        }

        public String load(String key) { return store.getString(key, null); }
        public void save(String key, String value) {
            if (value == null) store.edit().remove(key).apply(); else store.edit().putString(key, value).apply();
        }
        public void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ---- answers

    private void save(String k, String v) { answers.edit().putString(k, v).apply(); }
    private void saveBool(String k, boolean v) { answers.edit().putBoolean(k, v).apply(); }
    private List<String> list(String k) {
        List<String> out = new ArrayList<>();
        for (String s : answers.getString(k, "").split(";")) if (!s.isEmpty()) out.add(s);
        return out;
    }
    private void saveList(String k, List<String> v) { answers.edit().putString(k, String.join(";", v)).apply(); }

    // ---- views

    private final Map<String, Typeface> fonts = new HashMap<>();

    private Typeface font(String name) {
        Typeface tf = fonts.get(name);
        if (tf == null) {
            try { tf = Typeface.createFromAsset(getAssets(), "fonts/" + name + ".otf"); }
            catch (RuntimeException e) { tf = Typeface.DEFAULT; }
            fonts.put(name, tf);
        }
        return tf;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView textView(String s, int color, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(font(sp >= 22 ? "ThmanyahDisplay-Bold" : "Thmanyah-Regular"));
        t.setLineSpacing(0, 1.25f);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private void title(String s) { page.addView(textView(s, TEXT, 28)); space(6); }
    private void text(String s) { page.addView(textView(s, SUB, 16)); }
    private void small(String s) { page.addView(textView(s, 0x99C6BBA4, 13)); }

    private void header(String s) {
        TextView t = textView(s, GOLD, 16);
        t.setTypeface(font("Thmanyah-Medium"));
        t.setPadding(0, dp(16), 0, dp(4));
        page.addView(t);
    }

    private void space(int h) {
        View v = new View(this);
        page.addView(v, new LinearLayout.LayoutParams(1, dp(h)));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        page.addView(card, lp);
        return card;
    }

    private View optionView(String label, String sub, boolean selected, Runnable onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0x26D6AF6B : CARD);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(selected ? 2 : 1), selected ? GOLD : 0x1FD6AF6B);
        card.setBackground(bg);
        TextView name = textView(label, TEXT, 17);
        name.setTypeface(font("Thmanyah-Medium"));
        card.addView(name);
        if (sub != null) card.addView(textView(sub, SUB, 14));
        card.setOnClickListener(v -> onClick.run());
        card.setClickable(true);
        card.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        return card;
    }

    private void option(String label, String sub, boolean selected, Runnable onClick) {
        page.addView(optionView(label, sub, selected, onClick));
    }

    private View buttonView(String label, boolean primary, Runnable onClick) {
        TextView b = textView(label, primary ? 0xFF0A2016 : GOLD, 17);
        b.setTypeface(font("Thmanyah-Medium"));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? GOLD : 0x00000000);
        bg.setCornerRadius(dp(28));
        bg.setStroke(dp(2), GOLD);
        b.setBackground(bg);
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private void button(String label, boolean primary, Runnable onClick) { page.addView(buttonView(label, primary, onClick)); }

    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(0x66C6BBA4);
        e.setTypeface(font("Thmanyah-Regular"));
        e.setSingleLine(true);
        e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GOLD));
        page.addView(e);
        return e;
    }

    private static TextWatcher watcher(Runnable r) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { r.run(); }
        };
    }

    private void note(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

    private void dialog(String title, String message, Runnable yes) {
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title).setMessage(message);
        if (yes != null) b.setPositiveButton("نعم", (d, w) -> yes.run()).setNegativeButton("لا", null);
        else b.setPositiveButton("تمام", null);
        b.show();
    }
}
