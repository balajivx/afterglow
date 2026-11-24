package com.pixelcontrols.battery;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feasibility probe for "Battery watch" (2026-11-23): which per-app signals Android 17 gives Pixel Controls.
 * Run from a computer (SimReceiver, adb only): logs under the tag "PCBattery".
 *   1. Android's own per-app battery use (BatteryStatsManager.getBatteryUsageStats; needs BATTERY_STATS, granted
 *      with `pm grant`; a system API, so Android may refuse it to an app).
 *   2. Background mobile + Wi-Fi data per app over the last 2 h (NetworkStatsManager, usage access).
 *   3. Foreground-service time per app over the last 2 h (usage events; apps working while not on screen).
 */
public final class BatteryProbe {
    private static final String TAG = "PCBattery";

    private BatteryProbe() {}

    public static void run(Context c) {
        long now = System.currentTimeMillis(), from = now - 2 * 3600_000L;
        try {
            android.content.Intent u = c.registerReceiver(null, new android.content.IntentFilter("android.hardware.usb.action.USB_STATE"));
            StringBuilder b = new StringBuilder();
            if (u != null && u.getExtras() != null) for (String k : u.getExtras().keySet()) b.append(k).append('=').append(u.getExtras().get(k)).append(' ');
            Log.i(TAG, "0 USB_STATE as the app sees it: " + (u == null ? "null (not given to apps)" : b));
            android.content.Intent bat = c.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            Log.i(TAG, "0 battery: plugged=" + bat.getIntExtra("plugged", -1) + " max_current=" + bat.getIntExtra("max_charging_current", -1)
                    + " max_voltage=" + bat.getIntExtra("max_charging_voltage", -1));
        } catch (Throwable t) { Log.i(TAG, "0 usb: " + t); }
        try { batteryStats(c); } catch (Throwable t) { Log.i(TAG, "1 battery stats: NOT available: " + t); }
        try { backgroundData(c, from, now); } catch (Throwable t) { Log.i(TAG, "2 background data: NOT available: " + t); }
        try { serviceTime(c, from, now); } catch (Throwable t) { Log.i(TAG, "3 service time: NOT available: " + t); }
        Log.i(TAG, "done");
    }

    private static String name(Context c, int uid) {
        String[] p = c.getPackageManager().getPackagesForUid(uid);
        return p == null || p.length == 0 ? "uid " + uid : p[0];
    }

    /** Android's battery usage since the last full charge, per app. */
    private static void batteryStats(Context c) throws Exception {
        Log.i(TAG, "1 BATTERY_STATS granted: " + (c.checkSelfPermission("android.permission.BATTERY_STATS") == PackageManager.PERMISSION_GRANTED));
        Object bsm = c.getSystemService("batterystats");
        if (bsm == null) throw new IllegalStateException("no batterystats service object");
        StringBuilder ms = new StringBuilder();
        for (Method m : bsm.getClass().getMethods()) if (m.getDeclaringClass() == bsm.getClass()) ms.append(m.getName()).append(' ');
        Log.i(TAG, "1 methods the app can see: " + ms);
        Object stats = null;
        for (Method m : bsm.getClass().getMethods()) {
            if (!m.getName().equals("getBatteryUsageStats") && !m.getName().equals("getBatteryUsageStatsList")) continue;
            try {
                if (m.getParameterTypes().length == 0) stats = m.invoke(bsm);
                else if (m.getParameterTypes().length == 1) {
                    Class<?> q = m.getParameterTypes()[0];
                    Object query = q.getField("DEFAULT").get(null);
                    stats = m.invoke(bsm, query);
                }
                if (stats instanceof List) stats = ((List<?>) stats).get(0);
                if (stats != null) break;
            } catch (Throwable t) {
                Log.i(TAG, "1 " + m + " -> " + t);
            }
        }
        if (stats == null) throw new NoSuchMethodException("no usable getBatteryUsageStats");
        double total = (double) stats.getClass().getMethod("getConsumedPower").invoke(stats);
        List<?> uids = (List<?>) stats.getClass().getMethod("getUidBatteryConsumers").invoke(stats);
        Log.i(TAG, "1 battery stats OK: total " + Math.round(total) + " mAh, " + uids.size() + " apps");
        List<double[]> rows = new ArrayList<>();
        for (Object u : uids) {
            int uid = (int) u.getClass().getMethod("getUid").invoke(u);
            double mah = (double) u.getClass().getMethod("getConsumedPower").invoke(u);
            double bgMs = -1;
            try {
                Method m = u.getClass().getMethod("getTimeInStateMs", int.class);
                bgMs = ((Number) m.invoke(u, 1)).doubleValue();   // UidBatteryConsumer.STATE_BACKGROUND
            } catch (Throwable ignored) {
            }
            rows.add(new double[]{uid, mah, bgMs});
        }
        rows.sort((a, b) -> Double.compare(b[1], a[1]));
        for (int i = 0; i < Math.min(10, rows.size()); i++) {
            double[] r = rows.get(i);
            Log.i(TAG, String.format(java.util.Locale.US, "1   %-45s %6.1f mAh  background %s min", name(c, (int) r[0]), r[1],
                    r[2] < 0 ? "?" : String.valueOf(Math.round(r[2] / 60000))));
        }
    }

    /** Background (not on screen) mobile + Wi-Fi bytes per app. */
    private static void backgroundData(Context c, long from, long now) throws Exception {
        NetworkStatsManager nsm = c.getSystemService(NetworkStatsManager.class);
        Map<Integer, Long> bg = new HashMap<>();
        for (int type : new int[]{ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI}) {
            NetworkStats s = nsm.querySummary(type, null, from, now);
            NetworkStats.Bucket b = new NetworkStats.Bucket();
            while (s.hasNextBucket()) {
                s.getNextBucket(b);
                if (b.getState() == NetworkStats.Bucket.STATE_FOREGROUND) continue;
                bg.merge(b.getUid(), b.getRxBytes() + b.getTxBytes(), Long::sum);
            }
            s.close();
        }
        List<Map.Entry<Integer, Long>> top = new ArrayList<>(bg.entrySet());
        top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        Log.i(TAG, "2 background data OK: " + top.size() + " apps");
        for (int i = 0; i < Math.min(10, top.size()); i++)
            Log.i(TAG, String.format(java.util.Locale.US, "2   %-45s %6.1f MB", name(c, top.get(i).getKey()), top.get(i).getValue() / 1e6));
    }

    /** Time each app spent running a foreground service (working in the background: music, sync, location). */
    private static void serviceTime(Context c, long from, long now) {
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        UsageEvents ev = usm.queryEvents(from, now);
        UsageEvents.Event e = new UsageEvents.Event();
        Map<String, Long> started = new HashMap<>(), total = new HashMap<>();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int t = e.getEventType();
            if (t == 19) started.put(e.getPackageName(), e.getTimeStamp());              // FOREGROUND_SERVICE_START
            else if (t == 20) {                                                          // FOREGROUND_SERVICE_STOP
                Long s = started.remove(e.getPackageName());
                if (s != null) total.merge(e.getPackageName(), e.getTimeStamp() - s, Long::sum);
            }
        }
        for (Map.Entry<String, Long> s : started.entrySet()) total.merge(s.getKey(), now - s.getValue(), Long::sum);
        List<Map.Entry<String, Long>> top = new ArrayList<>(total.entrySet());
        top.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        Log.i(TAG, "3 service time OK: " + top.size() + " apps");
        for (int i = 0; i < Math.min(10, top.size()); i++)
            Log.i(TAG, String.format(java.util.Locale.US, "3   %-45s %4d min", top.get(i).getKey(), top.get(i).getValue() / 60000));
    }
}
