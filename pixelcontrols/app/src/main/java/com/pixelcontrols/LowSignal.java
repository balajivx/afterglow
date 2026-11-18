package com.pixelcontrols;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import com.pixelcontrols.adblock.Adblock;
import com.pixelcontrols.adblock.AdblockDefaults;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Low signal mode, app side. The daemon handles Data Saver, 5G, Wi-Fi handover, Private DNS and
 * keeping the on-screen app online. This adds the DNS accelerator: the Full filter (local DNS with
 * cache, serve-stale and racing resolvers, plus ad/tracker blocking that frees the weak link) runs
 * for as long as Low signal is on, and payment/banking apps are kept online. Whatever adblock
 * state the user had before is restored when Low signal turns off.
 */
public final class LowSignal {
    private static final String K_ACTIVE = "ls_active", K_PREV_ACTIVE = "ls_prev_active", K_PREV_MODE = "ls_prev_mode";

    private LowSignal() {}

    public static boolean isActive(Context c) { return Adblock.prefs(c).getBoolean(K_ACTIVE, false); }

    /** Call after Low signal ON. Returns Android's VPN consent intent if it must be shown first. */
    public static Intent onEnabled(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        SharedPreferences p = Adblock.prefs(c);
        if (!p.getBoolean(K_ACTIVE, false)) {
            p.edit().putBoolean(K_ACTIVE, true)
                    .putBoolean(K_PREV_ACTIVE, Adblock.isActive(c))
                    .putString(K_PREV_MODE, Adblock.mode(c)).apply();
        }
        Intent consent = null;
        // Lite adblock uses strict encrypted DNS, the first thing to fail on weak signal: use the
        // local accelerator instead while Low signal is on.
        if (Adblock.MODE_DNS.equals(Adblock.mode(c))) consent = Adblock.setMode(c, Adblock.MODE_VPN);
        if (consent == null && !Adblock.isActive(c)) {
            consent = Adblock.consentIntent(c);
            if (consent == null) Adblock.start(c);
        }
        new Thread(new Runnable() { public void run() {
            for (String pkg : paymentApps(c)) DaemonClient.sendOk("KEEP " + pkg);
        } }).start();
        return consent;
    }

    /** Call after Low signal OFF: puts adblock back exactly as it was. */
    public static void onDisabled(Context ctx) {
        Context c = ctx.getApplicationContext();
        SharedPreferences p = Adblock.prefs(c);
        if (!p.getBoolean(K_ACTIVE, false)) return;
        boolean prevActive = p.getBoolean(K_PREV_ACTIVE, false);
        String prevMode = p.getString(K_PREV_MODE, Adblock.MODE_VPN);
        if (!prevActive) {
            Adblock.stop(c);
            Adblock.setMode(c, prevMode);              // inactive: only restores the setting
        } else if (Adblock.MODE_DNS.equals(prevMode)) {
            Adblock.setMode(c, Adblock.MODE_DNS);      // back to Lite
        }
        p.edit().remove(K_ACTIVE).remove(K_PREV_ACTIVE).remove(K_PREV_MODE).apply();
    }

    /** Installed UPI/banking apps plus anything the user set to bypass the filter. */
    static Set<String> paymentApps(Context c) {
        Set<String> out = new LinkedHashSet<>();
        PackageManager pm = c.getPackageManager();
        for (String pkg : AdblockDefaults.EXCLUDED_APPS) {
            try { pm.getApplicationInfo(pkg, 0); out.add(pkg); } catch (PackageManager.NameNotFoundException ignored) { }
        }
        out.addAll(Adblock.excludedApps(c));
        return out;
    }
}
