package com.pixelcontrols;

import android.content.Context;
import android.content.Intent;

import android.content.SharedPreferences;

import com.pixelcontrols.adblock.Adblock;


/**
 * Low signal mode, app side. The daemon handles Data Saver, 5G, Wi-Fi handover, Private DNS and
 * keeping the on-screen app online. This adds the DNS accelerator: the Full filter (local DNS with
 * cache, serve-stale and racing resolvers, plus ad/tracker blocking that frees the weak link) runs
 * for as long as Low signal is on, and payment/banking apps are kept online. Whatever adblock
 * state the user had before is restored when Low signal turns off.
 */
public final class LowSignal {
    private static final String K_ACTIVE = "ls_active", K_PREV_ACTIVE = "ls_prev_active", K_PREV_MODE = "ls_prev_mode";
    // The helper's extras (Data Saver, 4G lock, apps kept online) are on / still need undoing (helper wasn't running at OFF).
    private static final String K_EXTRAS_ON = "ls_extras_on", K_EXTRAS_PENDING_OFF = "ls_extras_pending_off";

    private LowSignal() {}

    public static boolean isActive(Context c) { return Adblock.prefs(c).getBoolean(K_ACTIVE, false); }

    /** What turnOn / turnOff did, for the toast. */
    public static final class Result {
        public final boolean settings, extras;   // app's own settings applied; daemon extras applied
        public final Intent consent;              // VPN consent to show (DNS accelerator), or null
        public boolean extrasPending;             // OFF: the helper's extras still wait to be undone
        Result(boolean settings, boolean extras, Intent consent) { this.settings = settings; this.extras = extras; this.consent = consent; }
    }

    /**
     * Low signal ON. Never call on the main thread.
     * The app itself (no daemon): the app on screen gets the weak link (FocusGate holds back background apps' new
     * connections, on battery and charger), Battery Saver with Data Saver (on battery), mobile data kept ready, weak
     * Wi-Fi left sooner, Private DNS Automatic, DNS accelerator. If the helper daemon happens to be running it adds
     * the 4G lock and Data Saver while charging.
     */
    public static Result turnOn(Context ctx) {
        Context c = ctx.getApplicationContext();
        boolean settings = SecureSettings.lowSignalOn(c);
        boolean extras = DaemonClient.sendOk("BOOST_ON");   // helper extras, if it's running
        Adblock.prefs(c).edit().putBoolean(K_EXTRAS_ON, extras || Adblock.prefs(c).getBoolean(K_EXTRAS_ON, false))
                .remove(K_EXTRAS_PENDING_OFF).apply();
        Intent consent = onEnabled(c);
        return new Result(settings, extras, consent);
    }

    /** Low signal OFF: every setting back exactly. Never call on the main thread. */
    public static Result turnOff(Context ctx) {
        Context c = ctx.getApplicationContext();
        boolean extras = DaemonClient.sendOk("OFF");   // daemon undoes only what it changed
        SharedPreferences ap = Adblock.prefs(c);
        boolean extrasWereOn = ap.getBoolean(K_EXTRAS_ON, false) || ap.getBoolean(K_EXTRAS_PENDING_OFF, false);
        // helper not running (e.g. after a phone restart): undo its extras as soon as it's reachable again
        ap.edit().remove(K_EXTRAS_ON).putBoolean(K_EXTRAS_PENDING_OFF, !extras && extrasWereOn).apply();
        boolean settings = SecureSettings.lowSignalOff(c);
        onDisabled(c);                                  // adblock back as it was
        // Private DNS: whatever adblock needs now (Lite = AdGuard, full filter = Automatic), else the user's own.
        if (Adblock.wantedOn(c)) Adblock.reassertAfterDaemonChange(c);
        else Adblock.privateDnsRestore(c);
        Result r = new Result(settings, extras, null);
        r.extrasPending = extrasPendingOff(c);
        return r;
    }

    /** True when Data Saver / 4G lock from an earlier Low signal still wait for the helper to undo them. */
    public static boolean extrasPendingOff(Context c) {
        return Adblock.prefs(c).getBoolean(K_EXTRAS_PENDING_OFF, false);
    }

    /** Call whenever the helper is found running: finishes an OFF that happened while it wasn't. Not on the main thread. */
    public static void flushPendingOff(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (!extrasPendingOff(c) || isActive(c)) return;
        if (DaemonClient.sendOk("OFF")) Adblock.prefs(c).edit().remove(K_EXTRAS_PENDING_OFF).apply();
    }

    public static String describe(boolean on, Result r) {
        if (!on && !r.extras && r.extrasPending)
            return "Low signal OFF. Data Saver and the 4G lock stay on until the helper runs again (it then undoes them), "
                    + "or turn them off in Settings";
        if (!on) return "Low signal OFF: settings restored";
        if (r.settings && r.extras) return "Low signal ON: data goes to the app on screen · 4G lock";
        if (r.settings) return "Low signal ON: data goes to the app on screen";
        return "Low signal ON: only partly (run install.sh with the phone on USB to grant the permissions)";
    }

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

}
