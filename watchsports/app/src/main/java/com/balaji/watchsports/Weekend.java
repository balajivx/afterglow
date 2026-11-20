package com.balaji.watchsports;

import java.util.ArrayList;
import java.util.List;

/** An F1 race weekend as stations (FP1 -> FP2 -> FP3 -> Quali -> Race) and where we are in it. Plain Java (tested). */
final class Weekend {
    static final class Station {
        final String name;
        final long time;
        Station(String name, long time) {
            this.name = name;
            this.time = time;
        }
    }

    private Weekend() {
    }

    /** Jolpica's session key -> the station's label. */
    static String shortName(String key) {
        switch (key) {
            case "FirstPractice": return "FP1";
            case "SecondPractice": return "FP2";
            case "ThirdPractice": return "FP3";
            case "SprintQualifying": return "SQ";
            case "Sprint": return "Sprint";
            case "Qualifying": return "Quali";
            default: return key;
        }
    }

    /** "FP1=ms,FP2=ms,…,Race=ms" (Feed.Item.sessions) -> stations in order; empty when there's nothing usable. */
    static List<Station> parse(String s) {
        List<Station> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.add(new Station(part.substring(0, eq), Long.parseLong(part.substring(eq + 1))));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** How long a session runs: the race two hours, the rest one. */
    static long length(Station st) {
        return "Race".equals(st.name) ? 2 * 3600_000L : 3600_000L;
    }

    /** Index of the station on now, else the next one; stations.size() once the weekend is over. */
    static int current(List<Station> stations, long now) {
        for (int i = 0; i < stations.size(); i++) {
            Station st = stations.get(i);
            if (now < st.time + length(st)) return i;
        }
        return stations.size();
    }

    static boolean onNow(Station st, long now) {
        return now >= st.time && now < st.time + length(st);
    }
}
