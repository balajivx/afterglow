package com.pixelcontrols;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * Global settings the app changes itself, with WRITE_SECURE_SETTINGS (granted once over USB by install.sh:
 * `pm grant com.pixelcontrols android.permission.WRITE_SECURE_SETTINGS`; the grant survives restarts and works with
 * USB/Wireless debugging off). No helper daemon needed.
 *
 * Every value is backed up before the first change and put back exactly (same rules as the daemon):
 *  - Private DNS: one backup shared by Lite adblock, the full filter and Low signal; restored when the last of them
 *    lets go.
 *  - Low signal: Battery Saver, mobile data always on, leave weak Wi-Fi sooner.
 */
public final class SecureSettings {
    private static final String PREFS = "secure_backup";
    private static final String K_DNS_SAVED = "dns_saved", K_DNS_MODE = "dns_mode", K_DNS_SPEC = "dns_spec";
    private static final String K_STRICT_BY_US = "dns_strict_by_us", K_FALLBACK = "dns_fallback";
    private static final String K_LS_SAVED = "ls_saved";
    public static final String LITE_HOST = "dns.adguard-dns.com";
    // Restored in this order on OFF: Battery Saver goes off first, its constants are put back after (so the dark
    // theme never flips on the way out either).
    private static final String[] LS_KEYS = {"low_power", "low_power_sticky", "mobile_data_always_on", "network_avoid_bad_wifi",
            "battery_saver_constants"};
    private static final String NULL = "\u0000null";

    private SecureSettings() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean canWrite(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    private static String get(ContentResolver cr, String key) {
        return Settings.Global.getString(cr, key);
    }

    /** Writes, then reads back: false when Android refused the change. */
    private static boolean put(ContentResolver cr, String key, String value) {
        try {
            Settings.Global.putString(cr, key, value);
            String now = Settings.Global.getString(cr, key);
            return value == null ? now == null : value.equals(now);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ Private DNS

    private static void backupDns(Context c) {
        SharedPreferences sp = p(c);
        if (sp.getBoolean(K_DNS_SAVED, false)) return;
        ContentResolver cr = c.getContentResolver();
        String m = get(cr, "private_dns_mode"), s = get(cr, "private_dns_specifier");
        sp.edit().putBoolean(K_DNS_SAVED, true)
                .putString(K_DNS_MODE, m == null ? NULL : m)
                .putString(K_DNS_SPEC, s == null ? NULL : s).commit();
    }

    /** Automatic (opportunistic) Private DNS: never bypasses the local filter, never "no internet". */
    public static synchronized boolean privateDnsAuto(Context c) {
        if (!canWrite(c)) return false;
        backupDns(c);
        p(c).edit().putBoolean(K_STRICT_BY_US, false).commit();
        return put(c.getContentResolver(), "private_dns_mode", "opportunistic");
    }

    /** Lite adblock: strict Private DNS through AdGuard DNS. */
    public static synchronized boolean privateDnsLite(Context c) {
        if (!canWrite(c)) return false;
        backupDns(c);
        ContentResolver cr = c.getContentResolver();
        boolean ok = put(cr, "private_dns_specifier", LITE_HOST) && put(cr, "private_dns_mode", "hostname");
        p(c).edit().putBoolean(K_STRICT_BY_US, ok).putBoolean(K_FALLBACK, false).commit();
        return ok;
    }

    /** Network doctor / Lite watchdog: strict DNS unreachable, go Automatic (backup kept for later). */
    public static synchronized boolean privateDnsFix(Context c, boolean fallback) {
        if (!canWrite(c)) return false;
        backupDns(c);
        p(c).edit().putBoolean(K_STRICT_BY_US, false).putBoolean(K_FALLBACK, fallback).commit();
        return put(c.getContentResolver(), "private_dns_mode", "opportunistic");
    }

    /** Puts the user's own Private DNS back exactly. */
    public static synchronized boolean privateDnsRestore(Context c) {
        SharedPreferences sp = p(c);
        if (!sp.getBoolean(K_DNS_SAVED, false)) return true;
        if (!canWrite(c)) return false;
        ContentResolver cr = c.getContentResolver();
        String m = sp.getString(K_DNS_MODE, NULL), s = sp.getString(K_DNS_SPEC, NULL);
        put(cr, "private_dns_specifier", NULL.equals(s) ? null : s);
        put(cr, "private_dns_mode", NULL.equals(m) ? null : m);
        sp.edit().remove(K_DNS_SAVED).remove(K_DNS_MODE).remove(K_DNS_SPEC)
                .putBoolean(K_STRICT_BY_US, false).putBoolean(K_FALLBACK, false).commit();
        return true;
    }

    public static boolean strictByUs(Context c) {
        return p(c).getBoolean(K_STRICT_BY_US, false);
    }

    /** Lite adblock's DNS was unreachable and the watchdog switched to Automatic. */
    public static boolean liteFellBack(Context c) {
        return p(c).getBoolean(K_FALLBACK, false);
    }

    // ------------------------------------------------------------------ Low signal

    /** Battery Saver on, mobile data kept ready, weak Wi-Fi left sooner, Private DNS Automatic. */
    public static synchronized boolean lowSignalOn(Context c) {
        if (!canWrite(c)) return false;
        SharedPreferences sp = p(c);
        ContentResolver cr = c.getContentResolver();
        if (!sp.getBoolean(K_LS_SAVED, false)) {
            SharedPreferences.Editor e = sp.edit().putBoolean(K_LS_SAVED, true);
            for (String k : LS_KEYS) {
                String v = get(cr, k);
                e.putString("ls_" + k, v == null ? NULL : v);
            }
            e.commit();
        }
        // Battery Saver tuned for Low signal (no helper needed):
        //  - enable_datasaver=true: Data Saver comes on with it, so background apps stop using the weak link
        //    (checked on the Pixel 9a: restrict-background enabled). Battery Saver only runs on battery.
        //  - enable_night_mode=false: no switch to dark theme (that would close the Quick Settings panel).
        put(cr, "battery_saver_constants", lowSignalConstants(get(cr, "battery_saver_constants")));
        boolean ok = put(cr, "low_power_sticky", "1");
        ok &= put(cr, "low_power", "1");
        ok &= put(cr, "mobile_data_always_on", "1");
        ok &= put(cr, "network_avoid_bad_wifi", "1");
        ok &= privateDnsAuto(c);
        return ok;
    }

    /** Battery Saver constants with Data Saver on and dark theme off, everything else unchanged. */
    static String lowSignalConstants(String constants) {
        String c = constants == null || "null".equals(constants) ? "" : constants;
        c = set(c, "enable_night_mode", "false");
        return set(c, "enable_datasaver", "true");
    }

    private static String set(String constants, String key, String value) {
        if (constants.isEmpty()) return key + "=" + value;
        if (constants.contains(key + "=")) return constants.replaceAll(key + "=[a-z]+", key + "=" + value);
        return constants + "," + key + "=" + value;
    }

    /** Every Low signal setting back exactly as it was. */
    public static synchronized boolean lowSignalOff(Context c) {
        SharedPreferences sp = p(c);
        if (!sp.getBoolean(K_LS_SAVED, false)) return true;
        if (!canWrite(c)) return false;
        ContentResolver cr = c.getContentResolver();
        SharedPreferences.Editor e = sp.edit().remove(K_LS_SAVED);
        for (String k : LS_KEYS) {
            if (!sp.contains("ls_" + k)) continue;      // not backed up (older version): leave it alone
            String v = sp.getString("ls_" + k, NULL);
            put(cr, k, NULL.equals(v) ? null : v);
            e.remove("ls_" + k);
        }
        e.commit();
        return true;
    }
}
