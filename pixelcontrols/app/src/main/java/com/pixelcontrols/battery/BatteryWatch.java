package com.pixelcontrols.battery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import com.pixelcontrols.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Battery watch (2026-11-23, the user's request): now and then, a notification when an app has been busy in the
 * background, so the user can decide to stop it, restrict it, or leave it.
 *
 * What Android lets an app see (checked on the Pixel 9a, Android 17): NOT per-app battery use (that API is
 * hidden from apps even with BATTERY_STATS), but per app, with usage access:
 *  - background running time: a foreground service (sync, location, music...) while none of its screens is shown;
 *  - background data (mobile + Wi-Fi, not while on screen).
 * Every hour or so (JobScheduler, on battery only) the last 2 hours are checked; an app is reported when it ran
 * >= 30 min in the background or moved >= 50 MB in the background. At most 3 notifications a day, one per app a
 * day, none for apps marked "It's fine", none while charging.
 */
public final class BatteryWatch {
    static final String TAG = "BatteryWatch";
    static final long WINDOW_MS = 2 * 3600_000L;
    static final long BG_RUN_MS = 30 * 60_000L;       // the user's threshold
    static final long BG_DATA_BYTES = 50_000_000L;
    static final int MAX_A_DAY = 3;
    private static final int JOB_ID = 7301;
    /** Exempt from the start (the user's list in the app can drop it): Lookout for Work, office security. */
    static final String[] DEFAULT_EXEMPT = {"com.lookout.enterprise"};
    private static final String CHANNEL = "battery_watch";

    private BatteryWatch() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("battery_watch", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) { return prefs(c).getBoolean("on", true); }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("on", on).apply();
        if (on) schedule(c); else c.getSystemService(JobScheduler.class).cancel(JOB_ID);
    }

    /** Hourly check (Android may batch it; nothing runs in between). Called at app start and after a restart. */
    public static void schedule(Context c) {
        if (!enabled(c)) return;
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, BatteryWatchJob.class))
                .setPeriodic(3600_000L, 20 * 60_000L).setPersisted(true).build());
    }

    // ------------------------------------------------------------------ the decision (plain Java, tested)

    /** One app's last 2 hours in the background. */
    public static final class Usage {
        public final String pkg;
        public long bgRunMs, bgBytes;
        Usage(String pkg) { this.pkg = pkg; }
    }

    /**
     * Which apps to report now: over a threshold, not muted, not reported today, at most MAX_A_DAY a day (already
     * sent today: sentToday), worst first (running time, then data).
     */
    static List<Usage> pick(Collection<Usage> all, Set<String> muted, Set<String> reportedToday, int sentToday) {
        List<Usage> out = new ArrayList<>();
        for (Usage u : all) {
            if (muted.contains(u.pkg) || reportedToday.contains(u.pkg)) continue;
            if (u.bgRunMs >= BG_RUN_MS || u.bgBytes >= BG_DATA_BYTES) out.add(u);
        }
        out.sort((a, b) -> a.bgRunMs != b.bgRunMs ? Long.compare(b.bgRunMs, a.bgRunMs) : Long.compare(b.bgBytes, a.bgBytes));
        int room = Math.max(0, MAX_A_DAY - sentToday);
        return out.size() > room ? new ArrayList<>(out.subList(0, room)) : out;
    }

    /**
     * Background running time per app from usage events: foreground-service time minus the time one of its
     * screens was showing. Events in order: {type, packageName, timestamp}; type 19/20 = service start/stop,
     * 1/2 = screen shown/left (ACTIVITY_RESUMED / ACTIVITY_PAUSED).
     */
    static Map<String, Long> backgroundRun(List<Object[]> events, long from, long to) {
        Map<String, Long> svcStart = new HashMap<>(), visStart = new HashMap<>(), bg = new HashMap<>();
        Map<String, Boolean> svc = new HashMap<>(), vis = new HashMap<>();
        Map<String, Long> last = new HashMap<>();   // last time we accounted for this app
        for (Object[] e : events) {
            int type = (Integer) e[0];
            String p = (String) e[1];
            long t = Math.max(from, Math.min(to, (Long) e[2]));
            account(p, t, svc, vis, last, bg);
            if (type == 19) svc.put(p, true);
            else if (type == 20) svc.put(p, false);
            else if (type == 1) vis.put(p, true);
            else if (type == 2 || type == 23) vis.put(p, false);
        }
        Set<String> pkgs = new HashSet<>(svc.keySet());
        for (String p : pkgs) account(p, to, svc, vis, last, bg);
        return bg;
    }

    private static void account(String p, long t, Map<String, Boolean> svc, Map<String, Boolean> vis,
                                Map<String, Long> last, Map<String, Long> bg) {
        Long l = last.get(p);
        if (l != null && t > l && Boolean.TRUE.equals(svc.get(p)) && !Boolean.TRUE.equals(vis.get(p)))
            bg.merge(p, t - l, Long::sum);
        last.put(p, t);
    }

    // ------------------------------------------------------------------ the check (on the phone)

    static boolean charging(Context c) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int p = b == null ? 0 : b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return p != 0;
    }

    /** The last 2 hours, per app (user-installed and updated system apps; not Pixel Controls, not the system). */
    public static Map<String, Usage> measure(Context c) {
        long to = System.currentTimeMillis(), from = to - WINDOW_MS;
        Map<String, Usage> out = new HashMap<>();
        PackageManager pm = c.getPackageManager();
        // background running time
        try {
            UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
            UsageEvents ev = usm.queryEvents(from - 6 * 3600_000L, to);   // earlier events say what was already running
            UsageEvents.Event e = new UsageEvents.Event();
            List<Object[]> list = new ArrayList<>();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int t = e.getEventType();
                if (t == 1 || t == 2 || t == 23 || t == 19 || t == 20) list.add(new Object[]{t, e.getPackageName(), e.getTimeStamp()});
            }
            for (Map.Entry<String, Long> r : backgroundRun(list, from, to).entrySet()) usage(out, r.getKey()).bgRunMs = r.getValue();
        } catch (Exception ex) {
            Log.w(TAG, "usage events", ex);
        }
        // background data
        try {
            NetworkStatsManager nsm = c.getSystemService(NetworkStatsManager.class);
            for (int type : new int[]{ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI}) {
                NetworkStats s = nsm.querySummary(type, null, from, to);
                NetworkStats.Bucket b = new NetworkStats.Bucket();
                while (s.hasNextBucket()) {
                    s.getNextBucket(b);
                    if (b.getState() == NetworkStats.Bucket.STATE_FOREGROUND || b.getUid() < Process.FIRST_APPLICATION_UID) continue;
                    String[] pk = pm.getPackagesForUid(b.getUid());
                    if (pk == null || pk.length == 0) continue;
                    usage(out, pk[0]).bgBytes += b.getRxBytes() + b.getTxBytes();
                }
                s.close();
            }
        } catch (Exception ex) {
            Log.w(TAG, "network stats", ex);
        }
        out.remove(c.getPackageName());
        out.keySet().removeAll(muted(c));   // exempt apps aren't tracked at all
        // only apps the user can do something about (installed or updated by them), not the core system
        out.keySet().removeIf(p -> {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                return system || ai.uid < Process.FIRST_APPLICATION_UID;
            } catch (PackageManager.NameNotFoundException ex) {
                return true;
            }
        });
        return out;
    }

    private static Usage usage(Map<String, Usage> m, String p) {
        Usage u = m.get(p);
        if (u == null) { u = new Usage(p); m.put(p, u); }
        return u;
    }

    /** The hourly check. */
    public static void check(Context c) { check(c, false); }

    /** force: also on the charger (tests from a computer). */
    public static void check(Context c, boolean force) {
        if (!enabled(c)) return;
        if (!force && charging(c)) { Log.i(TAG, "charging: skipped"); return; }
        Map<String, Usage> all = measure(c);
        SharedPreferences p = prefs(c);
        String today = new java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(new java.util.Date());
        if (!today.equals(p.getString("day", ""))) p.edit().putString("day", today).putStringSet("today", new HashSet<>()).putInt("sent", 0).apply();
        Set<String> reported = new HashSet<>(p.getStringSet("today", new HashSet<>()));
        int sent = p.getInt("sent", 0);
        List<Usage> report = pick(all.values(), muted(c), reported, sent);
        // the app's list: the top background users of the last 2 hours
        List<Usage> top = new ArrayList<>(all.values());
        top.sort((a, b) -> a.bgRunMs != b.bgRunMs ? Long.compare(b.bgRunMs, a.bgRunMs) : Long.compare(b.bgBytes, a.bgBytes));
        StringBuilder last = new StringBuilder();
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            Usage u = top.get(i);
            if (u.bgRunMs < 60_000 && u.bgBytes < 1_000_000) break;
            last.append(label(c, u.pkg)).append(": ").append(describe(u)).append('\n');
        }
        p.edit().putString("last", last.toString().trim()).putLong("lastAt", System.currentTimeMillis()).apply();
        for (Usage u : report) {
            notify(c, u);
            reported.add(u.pkg);
            sent++;
        }
        p.edit().putStringSet("today", reported).putInt("sent", sent).apply();
        Log.i(TAG, "checked " + all.size() + " apps, reported " + report.size());
    }

    public static String describeUsage(Usage u) { return describe(u); }

    public static String labelOf(Context c, String pkg) { return label(c, pkg); }

    static String describe(Usage u) {
        List<String> parts = new ArrayList<>();
        if (u.bgRunMs >= 60_000) parts.add("running " + (u.bgRunMs / 60_000) + " min");
        if (u.bgBytes >= 1_000_000) parts.add(String.format(Locale.US, "%.0f MB of data", u.bgBytes / 1e6));
        return String.join(", ", parts) + " in the background (last 2 h)";
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ------------------------------------------------------------------ notifications and "It's fine"

    /** Apps exempt from Battery watch: chosen in the app ("Exempt apps") or with "It's fine" on a notification. */
    public static Set<String> muted(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("seeded", false)) {
            Set<String> m = new HashSet<>(p.getStringSet("muted", new HashSet<>()));
            m.addAll(java.util.Arrays.asList(DEFAULT_EXEMPT));
            p.edit().putStringSet("muted", m).putBoolean("seeded", true).apply();
        }
        return new HashSet<>(p.getStringSet("muted", new HashSet<>()));
    }

    public static void setExempt(Context c, Set<String> pkgs) {
        muted(c);
        prefs(c).edit().putStringSet("muted", new HashSet<>(pkgs)).apply();
    }

    public static void setMuted(Context c, String pkg, boolean mute) {
        Set<String> m = muted(c);
        if (mute) m.add(pkg); else m.remove(pkg);
        prefs(c).edit().putStringSet("muted", m).apply();
    }

    /** Test hook: a sample notification for pkg (tests from a computer). */
    public static void sample(Context c, String pkg) {
        Usage u = new Usage(pkg);
        u.bgRunMs = 42 * 60_000L;
        u.bgBytes = 12_000_000L;
        notify(c, u);
    }

    static void notify(Context c, Usage u) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Battery watch", NotificationManager.IMPORTANCE_DEFAULT));
        int id = 7400 + (u.pkg.hashCode() & 0xFFF);
        Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + u.pkg))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent stop = PendingIntent.getActivity(c, id, info, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent fine = PendingIntent.getBroadcast(c, id, new Intent(c, BatteryWatchReceiver.class)
                .setAction(BatteryWatchReceiver.ACTION_FINE).putExtra("pkg", u.pkg).putExtra("id", id),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String name = label(c, u.pkg);
        nm.notify(id, new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(name + " is busy in the background")
                .setContentText(describe(u))
                .setStyle(new Notification.BigTextStyle().bigText(describe(u)
                        + ". Stop it (Force stop) or restrict it (Battery → Restricted) on its app page, or leave it."))
                .setContentIntent(stop)
                .addAction(new Notification.Action.Builder(null, "Stop / restrict", stop).build())
                .addAction(new Notification.Action.Builder(null, "It's fine", fine).build())
                .setAutoCancel(true).build());
    }
}
