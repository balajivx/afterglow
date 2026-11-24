package com.pixelcontrols.adblock;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import com.pixelcontrols.LowSignal;
import com.pixelcontrols.auto.CarService;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Low signal "focus": the weak link goes to the app you have open. While Low signal is on, only these apps may make
 * new DNS lookups over the network (every connection starts with one):
 *  - the app on screen, and any app that was on screen in the last minute (quick switching, a song that just started);
 *  - the system (uid < 10000), Pixel Controls, Google Play services (notifications, sign-in), the keyboard;
 *  - in the car: Android Auto and the navigation / music apps it shows on the car screen.
 * Other (background) apps get cached answers (even expired ones) or a refusal, so they can't start new downloads.
 * Connections already open are not cut. Works on battery and on the charger, no helper needed.
 *
 * Which app is on screen comes from usage events (usage access, granted once by install.sh). Without it, or if an
 * app's lookup can't be attributed, nothing is held back (fail open: never block the app you're using).
 */
final class FocusGate implements DnsFilterEngine.Gate {
    private static final String TAG = "FocusGate";
    private static final long GRACE_MS = 60_000;
    private static final Set<String> ALWAYS = new HashSet<>(Arrays.asList(
            "com.google.android.gms", "com.google.android.gsf"));
    private static final Set<String> IN_CAR = new HashSet<>(Arrays.asList(
            "com.google.android.projection.gearhead", "com.google.android.apps.maps", "com.waze",
            "com.spotify.music", "com.google.android.apps.youtube.music", "com.jio.media.jiobeats", "com.gaana",
            "com.bsbportal.music", "com.amazon.mp3", "com.apple.android.music"));

    interface UidLookup { int ownerUid(DnsPacket.Query q); }

    private final Context c;
    private final UidLookup uids;
    private final UsageStatsManager usm;
    private final PackageManager pm;
    private final Map<String, Long> lastOnScreen = new HashMap<>();
    private String onScreen;
    private long lastRefresh;
    private final Map<String, Long> lastLogged = new HashMap<>();   // one log line per app per 30 s

    FocusGate(Context c, UidLookup uids) {
        this.c = c.getApplicationContext();
        this.uids = uids;
        this.usm = this.c.getSystemService(UsageStatsManager.class);
        this.pm = this.c.getPackageManager();
    }

    static boolean hasUsageAccess(Context c) {
        try {
            AppOpsManager ao = c.getSystemService(AppOpsManager.class);
            return ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                    == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean allowUpstream(DnsPacket.Query q) {
        if (!LowSignal.isActive(c) || usm == null || !hasUsageAccess(c)) return true;
        int uid = uids.ownerUid(q);
        if (uid < 0 || uid < Process.FIRST_APPLICATION_UID || uid == Process.myUid()) return true;
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs == null) return true;
        long now = System.currentTimeMillis();
        String ime = keyboard();
        synchronized (this) {
            refresh(now);
            Decision d = decide(pkgs, onScreen, lastOnScreen, now, ime, CarService.running);
            return d.allowed ? (d.why == null ? true : log(d.pkg, now, d.why, true)) : log(d.pkg, now, d.why, false);
        }
    }

    /** The decision itself, plain Java (tested on a computer: tests/FocusTest.java). */
    static final class Decision {
        final boolean allowed; final String pkg, why;
        Decision(boolean allowed, String pkg, String why) { this.allowed = allowed; this.pkg = pkg; this.why = why; }
    }

    static Decision decide(String[] pkgs, String onScreen, Map<String, Long> lastOnScreen, long now, String ime, boolean inCar) {
        for (String p : pkgs) {
            if (p.equals(onScreen)) return new Decision(true, p, "allowed (on screen)");
            if (ALWAYS.contains(p) || p.equals(ime)) return new Decision(true, p, null);
            Long t = lastOnScreen.get(p);
            if (t != null && now - t <= GRACE_MS) return new Decision(true, p, "allowed (on screen in the last minute)");
            if (inCar && IN_CAR.contains(p)) return new Decision(true, p, null);
        }
        return new Decision(false, pkgs[0], "held back (background)");
    }

    private boolean log(String pkg, long now, String what, boolean result) {
        Long t = lastLogged.get(pkg + result);
        if (t == null || now - t > 30_000) {
            lastLogged.put(pkg + result, now);
            Log.i(TAG, pkg + " " + what);
        }
        return result;
    }

    /** Reads new usage events: which app is on screen, and when each app was last on screen. */
    private void refresh(long now) {
        if (now - lastRefresh < 1000) return;
        long from = lastRefresh == 0 ? now - 10 * 60_000 : lastRefresh - 2000;
        lastRefresh = now;
        try {
            UsageEvents ev = usm.queryEvents(from, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int t = e.getEventType();
                if (t == UsageEvents.Event.ACTIVITY_RESUMED) {
                    onScreen = e.getPackageName();
                    lastOnScreen.put(e.getPackageName(), e.getTimeStamp());
                } else if (t == UsageEvents.Event.ACTIVITY_PAUSED || t == UsageEvents.Event.ACTIVITY_STOPPED) {
                    lastOnScreen.put(e.getPackageName(), e.getTimeStamp());   // the grace minute starts now
                }
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "usage events unavailable", ex);
        }
    }

    private String keyboard() {
        try {
            String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            return id == null ? null : id.substring(0, Math.max(0, id.indexOf('/')));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
