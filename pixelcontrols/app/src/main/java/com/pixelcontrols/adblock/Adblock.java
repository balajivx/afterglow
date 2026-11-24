package com.pixelcontrols.adblock;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import com.pixelcontrols.SecureSettings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Settings and on/off control for both adblock modes. */
public final class Adblock {

    public static final String MODE_VPN = "vpn";   // local DNS filter, per-app exclusions
    public static final String MODE_DNS = "dns";   // Private DNS -> AdGuard DNS (set by the app)

    private static final String PREFS = "adblock";
    private static final String K_ENABLED = "enabled", K_MODE = "mode", K_VIDEO = "video_compat",
            K_EXCLUDED = "excluded", K_ALLOW = "allowlist", K_LIST_UPDATED = "list_updated",
            K_EXCLUDED_INIT = "excluded_init", K_BLOCK = "user_blocklist", K_PACKS_OFF = "packs_off";
    private static final String LIST_FILE = "blocklist.txt";

    private Adblock() {}

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ settings
    public static String mode(Context c) { return prefs(c).getString(K_MODE, MODE_VPN); }
    public static boolean videoCompat(Context c) { return prefs(c).getBoolean(K_VIDEO, true); }
    public static boolean wantedOn(Context c) { return prefs(c).getBoolean(K_ENABLED, false); }

    public static void setVideoCompat(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_VIDEO, on).apply();
        reloadIfRunning(c);
    }

    public static List<String> userAllowlist(Context c) {
        String raw = prefs(c).getString(K_ALLOW, "");
        List<String> out = new ArrayList<>();
        for (String line : raw.split("[\\s,]+")) if (!line.trim().isEmpty()) out.add(line.trim().toLowerCase());
        return out;
    }

    public static String allowlistText(Context c) { return prefs(c).getString(K_ALLOW, ""); }

    public static void setAllowlistText(Context c, String text) {
        prefs(c).edit().putString(K_ALLOW, text).apply();
        reloadIfRunning(c);
    }

    public static void allowDomain(Context c, String domain) {
        List<String> blocks = userBlocklist(c);
        if (blocks.remove(domain)) prefs(c).edit().putString(K_BLOCK, join(blocks)).apply();
        List<String> cur = userAllowlist(c);
        if (!cur.contains(domain)) {
            String t = allowlistText(c).trim();
            setAllowlistText(c, t.isEmpty() ? domain : t + "\n" + domain);
        }
    }

    // The user's own block entries (e.g. ad servers spotted in "Recent lookups" during an OTT ad).
    public static List<String> userBlocklist(Context c) {
        List<String> out = new ArrayList<>();
        for (String line : prefs(c).getString(K_BLOCK, "").split("[\\s,]+")) if (!line.trim().isEmpty()) out.add(line.trim().toLowerCase());
        return out;
    }

    public static String userBlocklistText(Context c) { return prefs(c).getString(K_BLOCK, ""); }

    public static void setUserBlocklistText(Context c, String text) {
        prefs(c).edit().putString(K_BLOCK, text).apply();
        reloadIfRunning(c);
    }

    /** Blocks a domain; also removes it from the user's allowlist so the block takes effect. */
    public static void blockDomain(Context c, String domain) {
        List<String> allow = userAllowlist(c);
        if (allow.remove(domain)) prefs(c).edit().putString(K_ALLOW, join(allow)).apply();
        List<String> cur = userBlocklist(c);
        if (!cur.contains(domain)) cur.add(domain);
        setUserBlocklistText(c, join(cur));
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String x : l) { if (sb.length() > 0) sb.append('\n'); sb.append(x); }
        return sb.toString();
    }

    /** OTT packs the user switched off (all are on by default). */
    public static Set<String> disabledPacks(Context c) {
        return new HashSet<>(prefs(c).getStringSet(K_PACKS_OFF, Collections.<String>emptySet()));
    }

    public static void setDisabledPacks(Context c, Set<String> off) {
        prefs(c).edit().putStringSet(K_PACKS_OFF, new HashSet<>(off)).apply();
        reloadIfRunning(c);
    }

    /** User blocks + OTT packs (packs only with "Maximum OTT ad blocking"). */
    public static List<String> effectiveUserBlocks(Context c) {
        List<String> out = userBlocklist(c);
        if (!videoCompat(c)) out.addAll(AdblockDefaults.packDomains(disabledPacks(c)));
        return out;
    }

    public static List<String> effectiveAllowlist(Context c) {
        List<String> user = userAllowlist(c);
        return AdblockDefaults.allowlist(videoCompat(c), user.toArray(new String[0]));
    }

    /** Excluded apps; first call seeds it with the installed payment/banking apps. */
    public static Set<String> excludedApps(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean(K_EXCLUDED_INIT, false)) {
            Set<String> seed = new HashSet<>();
            PackageManager pm = c.getPackageManager();
            for (String pkg : AdblockDefaults.EXCLUDED_APPS) {
                try { pm.getApplicationInfo(pkg, 0); seed.add(pkg); } catch (PackageManager.NameNotFoundException ignored) { }
            }
            p.edit().putStringSet(K_EXCLUDED, seed).putBoolean(K_EXCLUDED_INIT, true).apply();
        }
        return new HashSet<>(p.getStringSet(K_EXCLUDED, Collections.<String>emptySet()));
    }

    public static void setExcludedApps(Context c, Set<String> pkgs) {
        prefs(c).edit().putStringSet(K_EXCLUDED, new HashSet<>(pkgs)).apply();
        // Exclusions are part of the VPN configuration, so the tunnel must be rebuilt.
        AdblockVpnService s = AdblockVpnService.instance;
        if (s != null) s.restartTunnel();
    }

    // ------------------------------------------------------------------ state + control
    public static boolean isActive(Context c) {
        if (MODE_VPN.equals(mode(c))) return AdblockVpnService.isRunning();
        return wantedOn(c);
    }

    public static boolean isPaused() {
        AdblockVpnService s = AdblockVpnService.instance;
        return s != null && s.isPaused();
    }

    /** Non-null when Android must show the one-time VPN consent dialog first. */
    public static Intent consentIntent(Context c) {
        return MODE_VPN.equals(mode(c)) ? VpnService.prepare(c) : null;
    }

    /** Starts the active mode. For VPN mode the consent must already have been granted. */
    public static void start(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        prefs(c).edit().putBoolean(K_ENABLED, true).apply();
        if (MODE_VPN.equals(mode(c))) {
            c.startService(new Intent(c, AdblockVpnService.class).setAction(AdblockVpnService.ACTION_START));
        } else {
            new Thread(new Runnable() { @Override public void run() {
                boolean ok = liteOn(c);
                if (!ok) {
                    prefs(c).edit().putBoolean(K_ENABLED, false).apply();
                    toast(c, "Lite mode needs the one-time permission (run install.sh with the phone on USB), or switch to Full filter.");
                }
                refreshTile(c);
            } }).start();
        }
        refreshTile(c);
    }

    public static void stop(Context ctx) {
        final Context c = ctx.getApplicationContext();
        prefs(c).edit().putBoolean(K_ENABLED, false).apply();
        AdblockVpnService s = AdblockVpnService.instance;
        if (s != null) s.shutdown();
        if (MODE_DNS.equals(mode(c))) {
            new Thread(new Runnable() { @Override public void run() { liteOff(c); refreshTile(c); } }).start();
        }
        refreshTile(c);
    }

    /** Switches mode, carrying the on/off state across. Returns a consent intent if needed. */
    public static Intent setMode(Context c, String mode) {
        if (mode.equals(mode(c))) return null;
        boolean was = isActive(c);
        if (was) stop(c);
        prefs(c).edit().putString(K_MODE, mode).apply();
        if (was) {
            Intent consent = consentIntent(c);
            if (consent != null) return consent;
            start(c);
        }
        return null;
    }

    /** Re-applies the Private DNS adblock needs (Lite = AdGuard, full filter = Automatic) after Low signal changed it. */
    public static void reassertAfterDaemonChange(Context c) {
        if (!wantedOn(c)) return;
        if (MODE_DNS.equals(mode(c))) liteOn(c);
        else privateDnsAuto(c);   // strict Private DNS would bypass the filter
    }

    // Private DNS is changed by the app itself (WRITE_SECURE_SETTINGS, granted once over USB by install.sh).

    /** Lite adblock: strict Private DNS via AdGuard DNS, watched so a blocked DNS never cuts the internet. */
    public static boolean liteOn(Context c) {
        boolean ok = SecureSettings.privateDnsLite(c);
        if (ok) LiteDnsWatch.register(c);
        return ok;
    }

    public static void liteOff(Context c) {
        LiteDnsWatch.unregister(c);
        SecureSettings.privateDnsRestore(c);
    }

    /** Full filter: strict Private DNS would bypass it, so Automatic while it runs. */
    public static boolean privateDnsAuto(Context c) {
        return SecureSettings.privateDnsAuto(c);
    }

    public static void privateDnsRestore(Context c) {
        SecureSettings.privateDnsRestore(c);
    }

    public static boolean privateDnsFix(Context c) {
        return SecureSettings.privateDnsFix(c, false);
    }

    static void reloadIfRunning(Context c) {
        AdblockVpnService s = AdblockVpnService.instance;
        if (s != null) s.reloadListsAsync();
    }

    public static void refreshTile(Context c) {
        try {
            TileService.requestListeningState(c, new ComponentName(c, AdblockTileService.class));
        } catch (RuntimeException ignored) { }
    }

    static void toast(final Context c, final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() { Toast.makeText(c, msg, Toast.LENGTH_LONG).show(); } });
    }

    // ------------------------------------------------------------------ blocklist
    static Blocklist loadBlocklist(Context c) throws IOException {
        File f = new File(c.getFilesDir(), LIST_FILE);
        try (InputStream in = f.isFile() ? new FileInputStream(f) : c.getAssets().open(LIST_FILE)) {
            return Blocklist.load(in, effectiveAllowlist(c)).withUserBlocks(effectiveUserBlocks(c));
        }
    }

    public static long listUpdatedAt(Context c) { return prefs(c).getLong(K_LIST_UPDATED, 0); }

    /** Downloads the latest list. Blocking; returns the number of domains. */
    public static int updateBlocklist(Context c) throws IOException {
        File tmp = new File(c.getFilesDir(), LIST_FILE + ".tmp");
        HttpURLConnection conn = (HttpURLConnection) new URL(AdblockDefaults.BLOCKLIST_URL).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
        int size;
        try (InputStream in = new FileInputStream(tmp)) {
            size = Blocklist.load(in, Collections.<String>emptyList()).size();
        }
        if (size < 50_000) {           // truncated or wrong file: keep the old list
            tmp.delete();
            throw new IOException("downloaded list looks incomplete (" + size + " domains)");
        }
        if (!tmp.renameTo(new File(c.getFilesDir(), LIST_FILE))) throw new IOException("could not save list");
        prefs(c).edit().putLong(K_LIST_UPDATED, System.currentTimeMillis()).apply();
        reloadIfRunning(c);
        return size;
    }
}
