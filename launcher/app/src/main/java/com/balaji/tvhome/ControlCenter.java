package com.balaji.tvhome;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Control Center: a panel over the home screen, opened from the button top right and closed with Back.
 * Left, the controls, laid out like a phone's: StandBy (the screensaver now), Wi-Fi and Bluetooth with
 * their state, Inputs, Settings, and two rows of round buttons: voice search, sound, a sleep timer,
 * picture off; Cast (Google Cast's settings, the TV's cast name under it), the box on a set HDMI port
 * (config.xml: quick_input), the screensaver's settings (StandBy starts it), restart (on a second press). Sound is where
 * AirPlay would be, AirPlay being switched off on this TV. Right: the picture keys' profiles, volume (the TV's, which drives the Streambar over HDMI-CEC),
 * and the apps used lately with the memory each holds and when it was used (OK opens one, its × closes
 * it), the memory free, and Close all (the running apps, but never those on config.xml's
 * close_all_keep), and Free storage (caches, and the unused apps on config.xml's storage_clear and
 * storage_unupdate). Closing, cleaning and the memory figures go through the TV's own debugging service (Adb):
 * Android lets no app close another or read its memory, and the TV asks once to allow it. There's no Close all: this
 * TV's Android refuses killBackgroundProcesses to every app ("Invalid packageName"), and each app's own
 * memory isn't shown, the system's memory report being for system apps only. quiet-apps.sh keeps the
 * streaming apps from holding memory in the background instead.
 *
 * Panel and tiles are drawn in code on the home screen's frosted glass; focus is the usual colour change
 * and fine ring, and a tile that's on (Bluetooth, the sleep timer, a muted volume) turns white.
 * Everything that waits on the TV or the network runs off the main thread.
 */
final class ControlCenter extends ViewGroup {
    /** Set by MainActivity: closes the panel and gives the focus back. */
    Runnable onClose;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final View glass;
    private final TextPaint headP, captionP;
    private final Paint scrimP = new Paint();
    private final Tile standby, wifi, bluetooth, inputs, settings, mic, sound, sleep, pictureOff;
    private final Tile cast, quickInput, screensaver, restart;
    private final Tile volDown, volUp, mute, closeAll, freeStorage;
    private final Tile volume;
    private final List<Tile> profiles = new ArrayList<>(), recents = new ArrayList<>(), closers = new ArrayList<>();
    private final List<Tile> rounds;
    private boolean open;
    private String memoryLine = "";

    private static final int RECENTS = 5;
    private static final int[] SLEEP_STEPS = {0, 30, 60, 90};

    ControlCenter(MainActivity a, Backdrop.SceneView scene) {
        super(a);
        setWillNotDraw(false);
        setVisibility(GONE);
        scrimP.setColor(0x8C000000);
        glass = new Backdrop.Glass(a, scene, Ui.dp(a, 28f), 24f);
        addView(glass);
        headP = Ui.textPaint(a, Ui.semibold(a), 20f, Ui.TEXT, -0.01f, false);
        captionP = Ui.textPaint(a, Ui.semibold(a), Ui.T_CAPTION, Ui.TEXT_2, 0.04f, false);

        standby = add(new Tile(a, Tile.BIG, R.drawable.ph_clock_bold, "StandBy"));
        wifi = add(new Tile(a, Tile.WIDE, R.drawable.ph_wifi_high_bold, "Wi-Fi"));
        bluetooth = add(new Tile(a, Tile.WIDE, R.drawable.ph_bluetooth_bold, "Bluetooth"));
        inputs = add(new Tile(a, Tile.WIDE, R.drawable.ph_arrow_square_in_bold, "Inputs"));
        settings = add(new Tile(a, Tile.WIDE, R.drawable.ph_gear_six_bold, "Settings"));
        mic = add(new Tile(a, Tile.ROUND, R.drawable.ph_microphone_bold, "Voice"));
        sound = add(new Tile(a, Tile.ROUND, R.drawable.ph_speaker_high_bold, "Sound"));
        sleep = add(new Tile(a, Tile.ROUND, R.drawable.ph_moon_bold, "Sleep"));
        pictureOff = add(new Tile(a, Tile.ROUND, R.drawable.ph_monitor_bold, "Picture off"));
        cast = add(new Tile(a, Tile.ROUND, R.drawable.ph_screencast_bold, "Cast"));
        quickInput = add(new Tile(a, Tile.ROUND, R.drawable.ph_television_simple_bold, a.getString(R.string.quick_input)));
        screensaver = add(new Tile(a, Tile.ROUND, R.drawable.ph_mountains_bold, "Saver settings")); // StandBy starts it
        restart = add(new Tile(a, Tile.ROUND, R.drawable.ph_arrow_clockwise_bold, "Restart"));
        rounds = java.util.Arrays.asList(mic, sound, sleep, pictureOff, cast, quickInput, screensaver, restart);
        for (Tile t : rounds) t.diameter = Ui.dp(a, 56f);
        for (int n = 1; n <= 5; n++) {
            String[] p = profile(n);
            if (p == null) continue;
            Tile t = add(new Tile(a, Tile.PILL, 0, p[0]));
            int key = n;
            t.onPress = () -> {
                if (Hotkeys.running != null) Hotkeys.running.pictureKey(key);
            };
            profiles.add(t);
        }
        volDown = add(new Tile(a, Tile.ROUND, R.drawable.ph_speaker_low_bold, "Volume down"));
        volume = new Tile(a, Tile.LABEL, 0, "Volume");
        addView(volume);
        volUp = add(new Tile(a, Tile.ROUND, R.drawable.ph_speaker_high_bold, "Volume up"));
        mute = add(new Tile(a, Tile.ROUND, R.drawable.ph_speaker_slash_bold, "Mute"));
        for (Tile t : new Tile[]{volDown, volUp, mute}) t.label = ""; // their icons say it; no name under them
        for (int i = 0; i < RECENTS; i++) {
            recents.add(add(new Tile(a, Tile.ROW, 0, "")));
            Tile x = add(new Tile(a, Tile.ROUND, R.drawable.ph_x_bold, "Close"));
            x.label = "";
            x.setIcon(R.drawable.ph_x_bold, Ui.dpi(a, 14f)); // small, in a small circle
            closers.add(x);
        }
        closeAll = add(new Tile(a, Tile.WIDE, R.drawable.ph_broom_bold, "Close all"));
        closeAll.onPress = this::closeAll;
        freeStorage = add(new Tile(a, Tile.WIDE, R.drawable.ph_hard_drives_bold, "Free storage"));
        freeStorage.onPress = this::freeStorage;

        standby.onPress = () -> {
            close();
            a.startScreensaver();
        };
        wifi.onPress = () -> launch(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
        // Opens Bluetooth settings rather than switching it: the TV's remote talks over Bluetooth, and
        // switching it off from here would leave only the remote's infrared keys to switch it back.
        bluetooth.onPress = () -> launch(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
        inputs.onPress = () -> {
            close();
            a.openInputs();
        };
        settings.onPress = () -> launch(new Intent(android.provider.Settings.ACTION_SETTINGS));
        mic.onPress = () -> launch(new Intent(Intent.ACTION_ASSIST));
        // Sony's Display & Sound: Picture, Sound, and Audio output (the Streambar's settings).
        sound.onPress = () -> {
            close();
            a.openFirstOf(new Intent().setClassName("com.sony.dtv.configsettings", "com.sony.dtv.configsettings.DisplaySoundActivity"),
                    new Intent(android.provider.Settings.ACTION_SOUND_SETTINGS));
        };
        sleep.onPress = this::stepSleep;
        pictureOff.onPress = () -> {
            close();
            // The TV's own Picture off: the screen goes dark, the sound plays on; any key brings it back.
            io.execute(() -> Hotkeys.press(Hotkeys.PICTURE_OFF));
        };
        cast.onPress = () -> launch(new Intent().setClassName("com.google.android.apps.mediashell",
                "com.google.android.apps.mediashell.settings.CastSettingsActivity"));
        quickInput.onPress = () -> {
            close();
            String uri = "extInput:hdmi?port=" + getResources().getInteger(R.integer.quick_input_port);
            io.execute(() -> {
                try {
                    Hotkeys.call("avContent", "setPlayContent", new JSONArray().put(new JSONObject().put("uri", uri)));
                } catch (IOException | JSONException ignored) {
                }
            });
        };
        screensaver.onPress = () -> launch(new Intent(android.provider.Settings.ACTION_DREAM_SETTINGS));
        restart.onPress = this::restart;
        volDown.onPress = () -> volume("-2");
        volUp.onPress = () -> volume("+2");
        mute.onPress = this::toggleMute;
    }

    private Tile add(Tile t) {
        addView(t);
        return t;
    }

    boolean isOpen() {
        return open;
    }

    void open() {
        open = true;
        setVisibility(VISIBLE);
        refresh();
        standby.requestFocus();
    }

    void close() {
        if (!open) return;
        open = false;
        setVisibility(GONE);
        if (onClose != null) onClose.run();
    }

    private void launch(Intent i) {
        close();
        try {
            getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // not on this TV
        }
    }

    // ---- State: read each time the panel opens

    private void refresh() {
        Context c = getContext();
        WifiManager wm = c.getSystemService(WifiManager.class);
        WifiInfo info = wm == null ? null : wm.getConnectionInfo();
        String ssid = info == null ? "" : info.getSSID().replace("\"", "");
        boolean wifiOn = wm != null && wm.isWifiEnabled();
        wifi.set(!wifiOn ? "Off" : ssid.isEmpty() || ssid.contains("unknown ssid") ? "On" : ssid, false);
        wifi.setIcon(wifiOn ? R.drawable.ph_wifi_high_bold : R.drawable.ph_wifi_slash_bold);
        showBluetooth();
        inputs.set("HDMI and TV", false);
        String castName = android.provider.Settings.Global.getString(c.getContentResolver(), "device_name");
        cast.label = castName == null || castName.isEmpty() ? "Cast" : "Cast · " + castName;
        cast.invalidate();
        settings.set("All settings", false);
        showSleep();
        io.execute(() -> {
            JSONObject v = volumeInfo();
            handler.post(() -> showVolume(v));
        });
        loadRecents();
    }

    private void showBluetooth() {
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        boolean on = false;
        try {
            on = bt != null && bt.isEnabled();
        } catch (SecurityException ignored) {
        }
        bluetooth.set(on ? "On" : "Off", on);
        bluetooth.setIcon(on ? R.drawable.ph_bluetooth_bold : R.drawable.ph_bluetooth_slash_bold);
    }

    /** Restarts the TV on a second press within 4 s; the first only asks. */
    private void restart() {
        if (!restart.on) {
            restart.on = true;
            restart.label = "Press again";
            restart.invalidate();
            handler.postDelayed(() -> {
                restart.on = false;
                restart.label = "Restart";
                restart.invalidate();
            }, 4000);
            return;
        }
        close();
        io.execute(() -> {
            try {
                Hotkeys.call("system", "requestReboot", new JSONArray());
            } catch (IOException | JSONException ignored) {
            }
        });
    }

    // ---- Volume, through the TV (it passes it to the Streambar over HDMI-CEC)

    private static JSONObject volumeInfo() {
        try {
            JSONArray r = Hotkeys.call("audio", "getVolumeInformation", new JSONArray()).optJSONArray("result");
            JSONArray all = r == null ? null : r.optJSONArray(0);
            return all == null ? null : all.optJSONObject(0);
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private void showVolume(JSONObject v) {
        boolean muted = v != null && v.optBoolean("mute");
        volume.set(v == null ? "–" : muted ? "Muted" : String.valueOf(v.optInt("volume")), false);
        volume.level = v == null ? -1f : v.optInt("volume") / (float) Math.max(1, v.optInt("maxVolume", 100));
        mute.on = muted;
        mute.invalidate();
        volume.invalidate();
    }

    private void volume(String step) {
        io.execute(() -> {
            try {
                Hotkeys.call("audio", "setAudioVolume", new JSONArray().put(new JSONObject()
                        .put("target", "speaker").put("volume", step)));
            } catch (IOException | JSONException ignored) {
            }
            JSONObject v = volumeInfo();
            handler.post(() -> showVolume(v));
        });
    }

    private void toggleMute() {
        boolean to = !mute.on;
        io.execute(() -> {
            try {
                Hotkeys.call("audio", "setAudioMute", new JSONArray().put(new JSONObject().put("status", to)));
            } catch (IOException | JSONException ignored) {
            }
            JSONObject v = volumeInfo();
            handler.post(() -> showVolume(v));
        });
    }

    // ---- Sleep timer: Off, 30, 60, 90 minutes; then the TV goes to standby (Sleeper, below)

    private void stepSleep() {
        SharedPreferences p = prefs(getContext());
        long until = p.getLong("sleep_until", 0), now = System.currentTimeMillis();
        int left = until > now ? (int) Math.ceil((until - now) / 60000.0) : 0;
        int next = 0;
        for (int s : SLEEP_STEPS) {
            if (s > left + 1) {
                next = s;
                break;
            }
        }
        Sleeper.set(getContext(), next == 0 ? 0 : now + next * 60000L);
        showSleep();
    }

    private void showSleep() {
        long until = prefs(getContext()).getLong("sleep_until", 0), now = System.currentTimeMillis();
        boolean on = until > now;
        sleep.label = on ? "Sleep " + (int) Math.ceil((until - now) / 60000.0) + " m" : "Sleep";
        sleep.on = on;
        sleep.invalidate();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("control_center", Context.MODE_PRIVATE);
    }

    /** Puts the TV in standby when the sleep timer runs out. */
    public static final class Sleeper extends BroadcastReceiver {
        static void set(Context c, long until) {
            prefs(c).edit().putLong("sleep_until", until).apply();
            AlarmManager am = c.getSystemService(AlarmManager.class);
            PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Sleeper.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pi);
            if (until == 0) return;
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pi);
        }

        @Override
        public void onReceive(Context c, Intent intent) {
            prefs(c).edit().putLong("sleep_until", 0).apply();
            PendingResult done = goAsync();
            new Thread(() -> {
                try {
                    Hotkeys.call("system", "setPowerStatus", new JSONArray().put(new JSONObject().put("status", false)));
                } catch (IOException | JSONException ignored) {
                } finally {
                    done.finish();
                }
            }).start();
        }
    }

    // ---- Recent apps, their memory, and closing them (through the TV's debugging service)

    /** One recently used app: its package, name, icon, when it was last used, and its memory now (KB; -1 unknown). */
    private static final class Recent {
        String pkg, name;
        Drawable icon;
        long lastUsed, kb = -1;
    }

    /**
     * The recent apps at once, then their memory from the debugging service, but only once the TV has
     * allowed it (a first × or Close all asks); before that, just when each was used.
     */
    private void loadRecents() {
        Context c = getContext();
        io.execute(() -> {
            List<Recent> list = recentApps(c);
            String line = freeLine(c);
            handler.post(() -> showRecents(list, line));
            if (!prefs(c).getBoolean("adb_ok", false)) return;
            try {
                Map<String, Long> pss = memoryByPackage(Adb.shell(c, "dumpsys meminfo"));
                for (Recent r : list) r.kb = pss.getOrDefault(r.pkg, 0L);
                handler.post(() -> showRecents(list, line));
            } catch (IOException ignored) {
                // the figures stay as they are; closing says why if it can't either
            }
        });
    }

    private static String freeLine(Context c) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        c.getSystemService(ActivityManager.class).getMemoryInfo(mi);
        return (mi.availMem >> 20) + " MB free of " + Math.round(mi.totalMem / 1e9 * 10) / 10.0 + " GB";
    }

    private void showRecents(List<Recent> list, String line) {
        memoryLine = line;
        for (int i = 0; i < recents.size(); i++) {
            Tile t = recents.get(i), x = closers.get(i);
            Recent r = i < list.size() ? list.get(i) : null;
            t.setVisibility(r == null ? INVISIBLE : VISIBLE);
            // The × only where there's something to close (or while the memory isn't known yet).
            x.setVisibility(r == null || r.kb == 0 ? INVISIBLE : VISIBLE);
            if (r == null) continue;
            t.title = r.name;
            t.set((r.kb < 0 ? "" : r.kb == 0 ? "closed · " : (r.kb >> 10) + " MB · ") + ago(r.lastUsed), false);
            t.setAppIcon(r.icon);
            t.onPress = () -> launch(getContext().getPackageManager().getLeanbackLaunchIntentForPackage(r.pkg));
            x.onPress = () -> closeApps(java.util.Collections.singletonList(r.pkg), t);
        }
        if (closeAll.sub.isEmpty() || closeAll.sub.equals("Frees memory")) closeAll.set("Frees memory", false);
        if (freeStorage.sub.isEmpty() || freeStorage.sub.endsWith(" MB free")) freeStorage.set(storageFree() + " MB free", false);
        invalidate();
    }

    /** Closes every running app with a TV launcher entry, but never those on config.xml's close_all_keep. */
    private void closeAll() {
        Context c = getContext();
        closeAll.set("Closing…", false);
        io.execute(() -> {
            try {
                // What's running: the processes' names (fast; the memory report takes seconds).
                java.util.Set<String> running = new java.util.HashSet<>();
                for (String l : Adb.shell(c, "ps -A -o NAME").split("\n")) {
                    String name = l.trim();
                    int colon = name.indexOf(':');
                    running.add(colon > 0 ? name.substring(0, colon) : name);
                }
                java.util.Set<String> keep = new java.util.HashSet<>(java.util.Arrays.asList(c.getResources().getStringArray(R.array.close_all_keep)));
                keep.add(c.getPackageName());
                List<String> go = new ArrayList<>();
                for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
                    String p = ri.activityInfo.packageName;
                    if (running.contains(p) && !keep.contains(p) && !go.contains(p)) go.add(p);
                }
                handler.post(() -> closeApps(go, closeAll));
            } catch (IOException e) {
                handler.post(() -> closeAll.set(why(e), false));
            }
        });
    }

    /** am force-stop for each of [pkgs], then how much memory came free, on [where]'s second line. */
    private void closeApps(List<String> pkgs, Tile where) {
        Context c = getContext();
        if (pkgs.isEmpty()) {
            where.set("Nothing to close", false);
            return;
        }
        where.set("Closing…", false);
        io.execute(() -> {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo(), after = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(before);
            StringBuilder cmd = new StringBuilder();
            for (String p : pkgs) cmd.append(cmd.length() == 0 ? "" : "; ").append("am force-stop ").append(p);
            String result;
            try {
                Adb.shell(c, cmd.toString());
                prefs(c).edit().putBoolean("adb_ok", true).apply();
                try {
                    Thread.sleep(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                am.getMemoryInfo(after);
                long freed = Math.max(0, (after.availMem - before.availMem) >> 20);
                String n = pkgs.size() == 1 ? "Closed" : "Closed " + pkgs.size() + " apps";
                result = freed > 0 ? n + " · " + freed + " MB freed" : n;
            } catch (IOException e) {
                result = why(e);
            }
            String done = result;
            handler.post(() -> {
                loadRecents();
                handler.postDelayed(() -> where.set(done, false), 300);
            });
        });
    }

    /** The internal storage free for apps, in MB. */
    private static long storageFree() {
        return new android.os.StatFs(android.os.Environment.getDataDirectory().getPath()).getAvailableBytes() >> 20;
    }

    /**
     * Every app's cache, then the data and the updates of config.xml's unused apps; how much came free.
     * Safe to press any time: with nothing left to clear, each step does nothing.
     */
    private void freeStorage() {
        Context c = getContext();
        freeStorage.set("Cleaning…", false);
        io.execute(() -> {
            long before = storageFree();
            StringBuilder cmd = new StringBuilder("pm trim-caches 100G");
            for (String p : c.getResources().getStringArray(R.array.storage_clear)) cmd.append("; pm clear ").append(p);
            for (String p : c.getResources().getStringArray(R.array.storage_unupdate)) cmd.append("; pm uninstall-system-updates ").append(p);
            String result;
            try {
                Adb.shell(c, cmd.toString());
                prefs(c).edit().putBoolean("adb_ok", true).apply();
                long freed = Math.max(0, storageFree() - before);
                result = (freed > 0 ? "Freed " + freed + " MB · " : "") + storageFree() + " MB free";
            } catch (IOException e) {
                result = why(e);
            }
            String done = result;
            handler.post(() -> freeStorage.set(done, false));
        });
    }

    private static String why(IOException e) {
        return e instanceof Adb.NotAllowed ? "Allow it on the TV's prompt, then again"
                : "Needs network debugging on (Developer options)";
    }

    private static final java.util.regex.Pattern PSS = java.util.regex.Pattern.compile("^\\s*([\\d,]+)K: ([^\\s(]+)");

    /** Memory per package (PSS, KB, a package's processes added up) from dumpsys meminfo's text. */
    private static Map<String, Long> memoryByPackage(String meminfo) {
        Map<String, Long> out = new HashMap<>();
        boolean in = false;
        for (String l : meminfo.split("\n")) {
            if (l.startsWith("Total PSS by process")) in = true;
            else if (in && l.trim().isEmpty()) break;
            else if (in) {
                java.util.regex.Matcher m = PSS.matcher(l);
                if (!m.find()) continue;
                String pkg = m.group(2);
                int colon = pkg.indexOf(':');
                if (colon > 0) pkg = pkg.substring(0, colon);
                try {
                    out.put(pkg, out.getOrDefault(pkg, 0L) + Long.parseLong(m.group(1).replace(",", "")));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    /** The apps with a TV launcher entry used in the last three days, latest first (not this one). */
    private static List<Recent> recentApps(Context c) {
        List<Recent> out = new ArrayList<>();
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm == null ? null : usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 3 * 86400000L, now);
        if (stats == null) return out;
        PackageManager pm = c.getPackageManager();
        Map<String, ResolveInfo> tv = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            tv.put(ri.activityInfo.packageName, ri);
        }
        Map<String, Long> last = new HashMap<>();
        for (UsageStats s : stats) {
            String p = s.getPackageName();
            if (!tv.containsKey(p) || p.equals(c.getPackageName()) || s.getLastTimeUsed() <= 0) continue;
            last.put(p, Math.max(s.getLastTimeUsed(), last.getOrDefault(p, 0L)));
        }
        List<String> order = new ArrayList<>(last.keySet());
        order.sort((x, y) -> Long.compare(last.get(y), last.get(x)));
        for (String p : order) {
            if (out.size() >= RECENTS) break;
            Recent r = new Recent();
            r.pkg = p;
            ResolveInfo ri = tv.get(p);
            CharSequence label = ri.loadLabel(pm);
            r.name = label == null ? p : label.toString();
            r.icon = ri.loadIcon(pm);
            r.lastUsed = last.get(p);
            out.add(r);
        }
        return out;
    }

    /** "now", "12 m ago", "3 h ago", "yesterday", "2 days ago". */
    private static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 2) return "now";
        if (m < 60) return m + " m ago";
        if (m < 24 * 60) return m / 60 + " h ago";
        return m < 48 * 60 ? "yesterday" : m / (24 * 60) + " days ago";
    }

    /** hotkey_[n] from config.xml: its name, then its settings; null when there's none. */
    private String[] profile(int n) {
        int id = getResources().getIdentifier("hotkey_" + n, "array", getContext().getPackageName());
        return id == 0 ? null : getResources().getStringArray(id);
    }

    // ---- Layout: the panel centred on the 960 x 540 dp screen, everything placed in dp

    private static final float PX = 80, PY = 24, PW = 800, PH = 492;

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            float[] r = place(v);
            if (r == null) continue;
            v.measure(MeasureSpec.makeMeasureSpec(Math.round(Ui.dp(getContext(), r[2]) + extra(v)), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(Ui.dp(getContext(), r[3]) + extra(v)), MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            float[] p = place(v);
            if (p == null) continue;
            int x = Math.round(Ui.dp(getContext(), p[0]) - extra(v) / 2f), y = Math.round(Ui.dp(getContext(), p[1]) - extra(v) / 2f);
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
        }
    }

    /** Tiles reach a ring's width past their art on each side. */
    private float extra(View v) {
        return v instanceof Tile ? 2 * ((Tile) v).ringSpace : 0;
    }

    /** Where [v] goes: x, y, width, height in dp (its art, not counting the ring's room). */
    private float[] place(View v) {
        float lx = PX + 24, rx = PX + 424, top = PY + 64;
        if (v == glass) return new float[]{PX, PY, PW, PH};
        if (v == standby) return new float[]{lx, top, 150, 150};
        if (v == wifi) return new float[]{lx + 162, top, 214, 69};
        if (v == bluetooth) return new float[]{lx + 162, top + 81, 214, 69};
        if (v == inputs) return new float[]{lx, top + 162, 182, 56};
        if (v == settings) return new float[]{lx + 194, top + 162, 182, 56};
        int ri = rounds.indexOf(v);
        // 56 dp circles in four columns; each view a column wide, so a long name under it has room.
        if (ri >= 0) return new float[]{lx + 28 + ri % 4 * 106.67f - 50, top + 234 + ri / 4 * 94, 100, 56 + 22};
        int pi = profiles.indexOf(v);
        if (pi >= 0) {
            float w = (352 - 8f * (profiles.size() - 1)) / profiles.size();
            return new float[]{rx + pi * (w + 8), top + 20, w, 40};
        }
        if (v == volDown) return new float[]{rx, top + 100, 40, 40};
        if (v == volume) return new float[]{rx + 50, top + 100, 152, 40};
        if (v == volUp) return new float[]{rx + 212, top + 100, 40, 40};
        if (v == mute) return new float[]{rx + 262, top + 100, 40, 40};
        int ci = recents.indexOf(v);
        if (ci >= 0) return new float[]{rx, top + 178 + ci * 38, 306, 32};
        int xi = closers.indexOf(v);
        if (xi >= 0) return new float[]{rx + 318, top + 178 + xi * 38, 32, 32};
        if (v == closeAll) return new float[]{rx, top + 372, 172, 40};
        if (v == freeStorage) return new float[]{rx + 180, top + 372, 172, 40};
        return null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), scrimP);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        // Headings over the glass, after the children so the glass sits under them.
        Context c = getContext();
        float lx = Ui.dp(c, PX + 24), rx = Ui.dp(c, PX + 424), top = Ui.dp(c, PY + 64);
        canvas.drawText("Control Center", lx, Ui.dp(c, PY + 44), headP);
        canvas.drawText("PICTURE", rx, top + Ui.dp(c, 10), captionP);
        canvas.drawText("VOLUME", rx, top + Ui.dp(c, 90), captionP);
        canvas.drawText("RECENT APPS", rx, top + Ui.dp(c, 168), captionP);
        if (!memoryLine.isEmpty()) {
            float w = captionP.measureText(memoryLine);
            canvas.drawText(memoryLine, rx + Ui.dp(c, 352) - w, top + Ui.dp(c, 168), captionP);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        io.shutdownNow();
    }

    // ---- The tiles

    /**
     * One control: BIG (icon over its name), WIDE (icon, name and state), ROUND (icon, its name under it),
     * PILL (a name), ROW (an app: icon, name, memory), LABEL (not focusable: the volume level and its bar).
     * Off, a dark translucent fill; on, white with dark ink; focused, brighter, with the fine ring.
     */
    static final class Tile extends ArtView {
        static final int BIG = 0, WIDE = 1, ROUND = 2, PILL = 3, ROW = 4, LABEL = 5;
        final int kind;
        String title, label, sub = "";
        boolean on;
        /** A ROUND tile's circle, when narrower than the tile (its name gets the width under it). */
        float diameter;
        float level = -1f;
        private Bitmap glyph, appIcon;
        private int glyphSize;
        private final TextPaint titleP, subP;
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG), glyphP = new Paint(Paint.FILTER_BITMAP_FLAG),
                barP = new Paint(Paint.ANTI_ALIAS_FLAG);

        Tile(Context c, int kind, int glyphRes, String title) {
            super(c);
            this.kind = kind;
            this.title = title;
            this.label = title;
            glyphSize = Ui.dpi(c, kind == BIG ? 44f : kind == ROUND ? 22f : kind == ROW ? 24f : 20f);
            if (glyphRes != 0) glyph = Ui.glyph(c, glyphRes, glyphSize);
            titleP = Ui.textPaint(c, Ui.semibold(c), kind == BIG ? 16f : kind == PILL || kind == ROW ? 13f : Ui.T_BODY, Ui.TEXT, 0f, false);
            subP = Ui.textPaint(c, Ui.medium(c), kind == ROUND ? 11f : Ui.T_LABEL, Ui.TEXT_2, 0f, kind == LABEL || kind == ROW);
            if (kind == LABEL) {
                setFocusable(false);
                setClickable(false);
            }
            setContentDescription(title);
        }

        void set(String sub, boolean on) {
            this.sub = sub == null ? "" : sub;
            this.on = on;
            setContentDescription(title + (this.sub.isEmpty() ? "" : ", " + this.sub));
            invalidate();
        }

        void setIcon(int res) {
            glyph = Ui.glyph(getContext(), res, glyphSize);
            invalidate();
        }

        void setIcon(int res, int sizePx) {
            glyphSize = sizePx;
            setIcon(res);
        }

        void setAppIcon(Drawable d) {
            int s = Ui.dpi(getContext(), 24f);
            Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            if (d != null) {
                d.setBounds(0, 0, s, s);
                d.draw(new Canvas(b));
            }
            appIcon = b;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float aw = w - 2 * ringSpace, ah = h - 2 * ringSpace;
            if (kind == ROUND) { // a circle, centred at the top
                float d = diameter > 0 ? diameter : aw;
                placeArt((w - d) / 2f, ringSpace, d, d, d / 2f);
                return;
            }
            float corner = kind == PILL || kind == ROW ? ah / 2f : Ui.dp(getContext(), kind == BIG ? 26f : 22f);
            placeArt(ringSpace, ringSpace, aw, ah, corner);
        }

        @Override
        protected void drawEmptyArt(Canvas canvas, float focused) {
            Context c = getContext();
            int ink = on ? 0xFF161616 : Ui.TEXT, ink2 = on ? 0xFF4A4A4A : Ui.TEXT_2;
            if (kind != LABEL) {
                fillP.setColor(on ? Ui.mix(0xFFE6E6E6, 0xFFFFFFFF, focused) : Ui.mix(0x2EFFFFFF, 0x52FFFFFF, focused));
                canvas.drawRoundRect(art, radius, radius, fillP);
            }
            titleP.setColor(ink);
            subP.setColor(ink2);
            glyphP.setColor(ink);
            float pad = Ui.dp(c, 16f);
            switch (kind) {
                case BIG: {
                    float gy = art.top + art.height() * 0.42f - glyphSize / 2f;
                    drawGlyph(canvas, art.centerX() - glyphSize / 2f, gy);
                    centred(canvas, title, titleP, art.top + art.height() * 0.78f);
                    break;
                }
                case WIDE: {
                    drawGlyph(canvas, art.left + pad, art.centerY() - glyphSize / 2f);
                    float x = art.left + pad + glyphSize + Ui.dp(c, 12f), w = art.right - pad - x;
                    if (sub.isEmpty()) {
                        canvas.drawText(title, x, Ui.baselineIn(titleP, art.top, art.height()), titleP);
                    } else {
                        canvas.drawText(title, x, art.centerY() - Ui.dp(c, 3f), titleP);
                        CharSequence s = TextUtils.ellipsize(sub, subP, w, TextUtils.TruncateAt.END);
                        canvas.drawText(s, 0, s.length(), x, art.centerY() + Ui.dp(c, 15f), subP);
                    }
                    break;
                }
                case ROUND: {
                    drawGlyph(canvas, art.centerX() - glyphSize / 2f, art.centerY() - glyphSize / 2f);
                    break;
                }
                case PILL:
                    centred(canvas, title, titleP, Ui.baselineIn(titleP, art.top, art.height()));
                    break;
                case ROW: {
                    float x = art.left + Ui.dp(c, 12f);
                    if (appIcon != null) canvas.drawBitmap(appIcon, x, art.centerY() - appIcon.getHeight() / 2f, null);
                    float tx = x + Ui.dp(c, 34f);
                    float sw = subP.measureText(sub);
                    CharSequence t = TextUtils.ellipsize(title, titleP, art.right - pad - sw - Ui.dp(c, 12f) - tx, TextUtils.TruncateAt.END);
                    canvas.drawText(t, 0, t.length(), tx, Ui.baselineIn(titleP, art.top, art.height()), titleP);
                    canvas.drawText(sub, art.right - pad - sw, Ui.baselineIn(subP, art.top, art.height()), subP);
                    break;
                }
                case LABEL: {
                    // The level as a number over a thin bar.
                    centred(canvas, sub.isEmpty() ? "–" : sub, titleP, art.top + Ui.dp(c, 18f));
                    float y = art.bottom - Ui.dp(c, 9f), h = Ui.dp(c, 4f);
                    barP.setColor(0x33FFFFFF);
                    canvas.drawRoundRect(art.left + pad, y, art.right - pad, y + h, h / 2, h / 2, barP);
                    if (level > 0) {
                        barP.setColor(Ui.TEXT);
                        canvas.drawRoundRect(art.left + pad, y, art.left + pad + (art.width() - 2 * pad) * Math.min(1f, level), y + h, h / 2, h / 2, barP);
                    }
                    break;
                }
            }
        }

        @Override
        protected void drawUnderArt(Canvas canvas, float focused) {
            // A round button's name sits under it, outside the circle (and the ring).
            if (kind != ROUND || label == null || label.isEmpty()) return;
            subP.setColor(Ui.mix(Ui.TEXT_2, Ui.TEXT, focused));
            centred(canvas, label, subP, art.bottom + ringSpace + Ui.dp(getContext(), 16f));
        }

        private void drawGlyph(Canvas canvas, float x, float y) {
            if (glyph != null) canvas.drawBitmap(glyph, x, y, glyphP);
        }

        private void centred(Canvas canvas, String s, TextPaint p, float baseline) {
            canvas.drawText(s, art.centerX() - p.measureText(s) / 2f, baseline, p);
        }
    }
}
