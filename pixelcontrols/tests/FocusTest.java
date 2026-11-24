package com.pixelcontrols.adblock;

import java.util.HashMap;
import java.util.Map;

/** Low signal focus decisions (FocusGate.decide), plain Java.  See tests/run_java_tests.sh */
public class FocusTest {
    static int pass, fail;
    static void eq(boolean got, boolean want, String what) {
        if (got == want) { pass++; System.out.println("  PASS  " + what); }
        else { fail++; System.out.println("  FAIL  " + what + " (got " + got + ")"); }
    }
    static boolean d(String pkg, String onScreen, Map<String, Long> last, long now, boolean car) {
        return FocusGate.decide(new String[]{pkg}, onScreen, last, now, "com.google.android.inputmethod.latin", car).allowed;
    }
    public static void main(String[] a) {
        long now = 10_000_000L;
        Map<String, Long> last = new HashMap<>();
        last.put("com.spotify.music", now - 30_000);               // left the screen 30 s ago
        last.put("com.whatsapp", now - 5 * 60_000);                // 5 min ago
        eq(d("in.startv.hotstar", "in.startv.hotstar", last, now, false), true, "the app on screen (OTT) gets the link");
        eq(d("com.phonepe.app", "com.phonepe.app", last, now, false), true, "UPI app on screen gets the link");
        eq(d("com.google.android.youtube", "in.startv.hotstar", last, now, false), false, "a background app is held back");
        eq(d("com.whatsapp", "in.startv.hotstar", last, now, false), false, "an app left 5 min ago is held back");
        eq(d("com.spotify.music", "in.startv.hotstar", last, now, false), true, "an app left 30 s ago still gets through (grace minute)");
        eq(d("com.google.android.gms", "in.startv.hotstar", last, now, false), true, "Play services (notifications) always");
        eq(d("com.google.android.inputmethod.latin", "in.startv.hotstar", last, now, false), true, "the keyboard always");
        eq(d("com.google.android.apps.maps", "com.google.android.projection.gearhead", last, now, true), true, "in the car: Maps on the car screen");
        eq(d("com.google.android.apps.maps", "in.startv.hotstar", last, now, false), false, "out of the car: background Maps held back");
        eq(FocusGate.decide(new String[]{"com.x.shared", "in.startv.hotstar"}, "in.startv.hotstar", last, now, null, false).allowed,
                true, "shared uid: allowed if any of its packages is on screen");
        System.out.println("focus: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
