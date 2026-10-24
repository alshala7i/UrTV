package sa.urtv.screensaver;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone app's door to what the screensaver already has: its Saudi city list (Cities) and its club search
 * (Teams over assets/shared/teams.json). android/phone/build.gradle copies those files in unchanged, so the phone
 * offers exactly the names the TV accepts.
 */
public final class Shared {
    private Shared() {}

    /** {region, city, latitude, longitude} for every built-in Saudi city */
    public static List<String[]> cities() {
        List<String[]> out = new ArrayList<>();
        for (Cities.Region r : Cities.REGIONS)
            for (String[] c : r.cities) out.add(new String[] {r.name, c[0], c[1], c[2]});
        return out;
    }

    /** {id, English name, Arabic label} for the clubs matching a name in Arabic or English, best first */
    public static List<String[]> searchTeams(Context c, String query) {
        List<String[]> out = new ArrayList<>();
        for (TeamSearch.Team t : Teams.search(c, query)) out.add(new String[] {t.id, t.en, Teams.label(c, t)});
        return out;
    }
}
