package sa.urtv.setup;

/**
 * The countries the setup offers and what each one sets on the screensaver: its time zone and Aladhan's prayer
 * calculation method (SaverReceiver: --es tz, --es method). Saudi cities come from the screensaver's own list
 * (Shared.cities); the others are here, with their coordinates for the prayer times and the weather.
 */
final class Places {
    private Places() {}

    static final class Country {
        final String name, tz, method;
        final String[][] cities;   // name, latitude, longitude; empty = the Saudi list
        Country(String name, String tz, String method, String[][] cities) {
            this.name = name; this.tz = tz; this.method = method; this.cities = cities;
        }
    }

    static final Country[] COUNTRIES = {
        new Country("السعودية", "Asia/Riyadh", "4", new String[0][]),
        new Country("الكويت", "Asia/Kuwait", "9", new String[][] {
            {"الكويت", "29.3759", "47.9774"}, {"حولي", "29.3328", "48.0286"}, {"الفروانية", "29.2775", "47.9586"},
            {"الجهراء", "29.3375", "47.6581"}, {"الأحمدي", "29.0769", "48.0838"}, {"مبارك الكبير", "29.1869", "48.0950"},
        }),
        new Country("قطر", "Asia/Qatar", "10", new String[][] {
            {"الدوحة", "25.2854", "51.5310"}, {"الريان", "25.2919", "51.4244"}, {"الوكرة", "25.1715", "51.6034"},
            {"الخور", "25.6804", "51.4969"},
        }),
        new Country("الإمارات", "Asia/Dubai", "16", new String[][] {
            {"دبي", "25.2048", "55.2708"}, {"أبوظبي", "24.4539", "54.3773"}, {"الشارقة", "25.3463", "55.4209"},
            {"العين", "24.2075", "55.7447"}, {"عجمان", "25.4052", "55.5136"}, {"رأس الخيمة", "25.8007", "55.9762"},
            {"الفجيرة", "25.1288", "56.3265"},
        }),
        new Country("البحرين", "Asia/Bahrain", "8", new String[][] {
            {"المنامة", "26.2285", "50.5860"}, {"المحرق", "26.2572", "50.6119"}, {"الرفاع", "26.1300", "50.5550"},
        }),
        new Country("عُمان", "Asia/Muscat", "8", new String[][] {
            {"مسقط", "23.5880", "58.3829"}, {"صلالة", "17.0151", "54.0924"}, {"صحار", "24.3460", "56.7470"},
            {"نزوى", "22.9333", "57.5333"},
        }),
        new Country("مصر", "Africa/Cairo", "5", new String[][] {
            {"القاهرة", "30.0444", "31.2357"}, {"الجيزة", "30.0131", "31.2089"}, {"الإسكندرية", "31.2001", "29.9187"},
        }),
    };

    static Country byName(String name) {
        for (Country c : COUNTRIES) if (c.name.equals(name)) return c;
        return COUNTRIES[0];
    }

    // the card beside the clock: value sent to the screensaver (Prefs.PANELS), Arabic label
    static final String[][] PANELS = {
        {"verse", "آية أو حديث اليوم"}, {"prayers", "مواقيت الصلاة اليوم"}, {"matches", "مباريات أنديتك"},
        {"forecast", "طقس الأيام الجاية"}, {"shows", "جديد المسلسلات"}, {"none", "لا شي"},
    };

    static String panelLabel(String v) {
        for (String[] p : PANELS) if (p[0].equals(v)) return p[1];
        return PANELS[0][1];
    }
}
