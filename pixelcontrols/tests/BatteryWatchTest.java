package com.pixelcontrols.battery;

import java.util.*;

/** Battery watch decisions (BatteryWatch.pick / backgroundRun), plain Java. See tests/run_java_tests.sh */
public class BatteryWatchTest {
    static int pass, fail;
    static void eq(Object got, Object want, String what) {
        if (Objects.equals(got, want)) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what + " (got " + got + ", want " + want + ")"); }
    }
    static BatteryWatch.Usage u(String p, long minutes, long mb) {
        BatteryWatch.Usage x = new BatteryWatch.Usage(p); x.bgRunMs = minutes * 60_000L; x.bgBytes = mb * 1_000_000L; return x;
    }
    static List<String> names(List<BatteryWatch.Usage> l) { List<String> o = new ArrayList<>(); for (BatteryWatch.Usage x : l) o.add(x.pkg); return o; }
    public static void main(String[] a) {
        List<BatteryWatch.Usage> all = Arrays.asList(u("truecaller", 45, 2), u("spotify", 31, 0), u("x", 5, 120),
                u("gmail", 10, 3), u("maps", 29, 49), u("insta", 90, 0));
        eq(names(BatteryWatch.pick(all, new HashSet<>(), new HashSet<>(), 0)), Arrays.asList("insta", "truecaller", "spotify"),
                "over 30 min or 50 MB, worst first, at most 3 a day");
        eq(names(BatteryWatch.pick(all, new HashSet<>(Collections.singletonList("insta")), new HashSet<>(), 0)),
                Arrays.asList("truecaller", "spotify", "x"), "an app marked fine is never reported");
        eq(names(BatteryWatch.pick(all, new HashSet<>(), new HashSet<>(Arrays.asList("insta", "truecaller")), 2)),
                Arrays.asList("spotify"), "one per app a day; the daily cap counts what was already sent");
        eq(BatteryWatch.pick(all, new HashSet<>(), new HashSet<>(), 3).size(), 0, "3 sent today: nothing more");
        eq(names(BatteryWatch.pick(Arrays.asList(u("com.lookout.enterprise", 240, 500)),
                new HashSet<>(Arrays.asList(BatteryWatch.DEFAULT_EXEMPT)), new HashSet<>(), 0)).size(), 0,
                "an exempt app (Lookout for Work, exempt by default) is never reported");
        eq(names(BatteryWatch.pick(Arrays.asList(u("maps", 29, 49)), new HashSet<>(), new HashSet<>(), 0)).size(), 0,
                "just under both thresholds: nothing");

        long t0 = 1_000_000_000L, m = 60_000L;
        List<Object[]> ev = new ArrayList<>();
        ev.add(new Object[]{19, "music", t0});            // service starts
        ev.add(new Object[]{1, "music", t0 + 10 * m});    // its screen shown 10 min later
        ev.add(new Object[]{2, "music", t0 + 20 * m});    // left after 10 min
        ev.add(new Object[]{20, "music", t0 + 50 * m});   // service stops
        ev.add(new Object[]{19, "sync", t0 + 30 * m});    // still running at the end
        Map<String, Long> bg = BatteryWatch.backgroundRun(ev, t0, t0 + 60 * m);
        eq(bg.get("music") / m, 40L, "service time minus on-screen time (50 - 10 min)");
        eq(bg.get("sync") / m, 30L, "a service still running counts up to now");
        System.out.println("battery watch: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
