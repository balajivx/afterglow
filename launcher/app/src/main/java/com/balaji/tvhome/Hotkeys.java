package com.balaji.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The remote's number keys, wherever the TV is (inside Hotstar or Netflix too): each switches the picture
 * to a profile from config.xml (hotkey_1 to hotkey_9: Sports, Movies...) through Sony's own picture API
 * on the TV (http://127.0.0.1/sony/: IP control, signed with the pre-shared key from the build).
 * 0 shows what each key does. Keys with no profile do what they always did.
 *
 * Motionflow isn't in Sony's API, and on this TV it doesn't follow the picture mode, so a profile's
 * motionflow (auto, custom, off) is set in Sony's own picture menu, the way a person would: the menu
 * opened as Quick Settings' Picture settings tile opens it, then remote presses sent over IP control
 * (IRCC) down to Motion and Motionflow, the value stepped round, the menu closed. Accessibility only reads which row is highlighted.
 * It shows for a few seconds. Each input keeps its own picture settings, and the menu can open on one
 * other than the one playing, so it only acts when the menu names the input the TV says is playing
 * ("Apps" for an app, "HDMI 4" for a Fire TV Stick on HDMI 4). Smoothness and Clearness stay
 * as set by hand: Sony ignores them set any other way, and they keep their values as Motionflow changes.
 *
 * An accessibility service, as that's the only way Android lets an app see keys meant for other apps and
 * work Sony's menu; it reads no other screen. A small card at the top says what happened, then fades.
 */
public class Hotkeys extends AccessibilityService {
    private static final String API = "http://127.0.0.1/sony/";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Card card;
    /** The service while it runs: the Control Center's picture buttons go through it. */
    static Hotkeys running;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        // For testing from a computer: keys sent over adb never reach this service, so adb can press one as
        // `am broadcast -a com.balaji.tvhome.KEY --ei n 7`. Only the shell holds DUMP, so no app can send it.
        android.content.IntentFilter f = new android.content.IntentFilter("com.balaji.tvhome.KEY");
        registerReceiver(testKey, f, android.Manifest.permission.DUMP, null);
    }

    private final android.content.BroadcastReceiver testKey = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, android.content.Intent i) {
            int n = i.getIntExtra("n", -1);
            String[] p = profile(n);
            if (n == 0) showKeys();
            else if (p != null) apply(n, p);
        }
    };

    /** Applies picture profile [n] as its number key would; false when there's no such profile. */
    boolean pictureKey(int n) {
        String[] p = profile(n);
        if (p == null) return false;
        apply(n, p);
        return true;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (running == this) running = null;
        try {
            unregisterReceiver(testKey);
        } catch (IllegalArgumentException ignored) {
        }
        handler.removeCallbacksAndMessages(null); // nothing queued may run after this
        io.shutdownNow();
        if (card != null) card.remove();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int n = e.getKeyCode() - KeyEvent.KEYCODE_0;
        if (n < 0 || n > 9) return false;
        String[] profile = profile(n);
        if (n != 0 && profile == null) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (n == 0) showKeys();
            else apply(n, profile);
        }
        return true; // both halves of the press, so the app in front never sees it
    }

    /** hotkey_[n] from config.xml: its name, then "target=value" settings; null when there's none. */
    private String[] profile(int n) {
        int id = getResources().getIdentifier("hotkey_" + n, "array", getPackageName());
        if (id == 0) return null;
        String[] p = getResources().getStringArray(id);
        return p.length > 1 ? p : null;
    }

    private void apply(int n, String[] profile) {
        String name = profile[0];
        if (java.util.Arrays.asList(profile).contains("action=pictureOff")) {
            // The TV's own Picture off: dark screen, sound on; any key but ours brings the picture back.
            show(n + "  " + name, "Screen off, sound on · any key turns it back on");
            run(() -> {
                pause(1500); // the card is read first
                press(PICTURE_OFF);
            });
            return;
        }
        if (java.util.Arrays.asList(profile).contains("action=sound")) {
            String setups = "sound";
            for (String p : profile) if (p.startsWith("setups=")) setups = p.substring(7).trim();
            nextSound(n, setups);
            return;
        }
        show(n + "  " + name, "Changing the picture…");
        List<String> settings = new ArrayList<>(Arrays.asList(profile).subList(1, profile.length));
        Motion motion = Motion.from(settings); // takes its settings out of the list
        run(() -> {
            // The picture mode first, on its own: the rest are that mode's own settings.
            String mode = null;
            List<String> rest = new ArrayList<>();
            for (String s : settings) {
                if (s.startsWith("pictureMode=")) mode = s;
                else rest.add(s);
            }
            String failed = mode == null ? null : set(Arrays.asList(mode));
            // Some settings don't apply to every input (no light sensor for HDMI, say); one the TV can't
            // take fails the whole batch, so those are left out.
            if (failed == null) rest.retainAll(available(rest));
            if (failed == null && !rest.isEmpty()) failed = set(rest);
            String detail = failed != null ? failed
                    : mode == null ? "Picture adjusted" : "Picture mode: " + title(mode.substring(mode.indexOf('=') + 1));
            String flow = motion == null ? null : setMotion(motion);
            handler.post(() -> show(n + "  " + name, flow == null ? detail : detail + " · " + flow));
        });
    }

    // ---- Motionflow, through Sony's picture menu

    private static final String MENU = "com.sony.dtv.configsettings";
    /** What Quick Settings' Picture settings tile opens: Sony's picture menu, for the input playing. */
    private static final String PICTURE_SETTINGS = "com.sony.dtv.base.picturesettings";

    /** A profile's motionflow: auto, custom or off. */
    static final class Motion {
        String flow;

        /** The motionflow setting in [settings], taken out of the list; null when there's none. */
        static Motion from(List<String> settings) {
            for (java.util.Iterator<String> it = settings.iterator(); it.hasNext(); ) {
                String s = it.next();
                if (!s.startsWith("motionflow=")) continue;
                it.remove();
                Motion m = new Motion();
                m.flow = s.substring(s.indexOf('=') + 1).trim().toLowerCase(Locale.UK);
                return m;
            }
            return null;
        }
    }

    // Sony remote buttons, as IP control's IRCC codes (from the TV's getRemoteControllerInfo).
    static final String PICTURE_OFF = "AAAAAQAAAAEAAAA+Aw==";
    private static final String DOWN = "AAAAAQAAAAEAAAB1Aw==",
            LEFT = "AAAAAQAAAAEAAAA0Aw==", RIGHT = "AAAAAQAAAAEAAAAzAw==", CONFIRM = "AAAAAQAAAAEAAABlAw==",
            GEAR = "AAAAAgAAAMQAAABLAw=="; // ActionMenu: Quick Settings
    /** Motionflow's values, as Left and Right step through them in its card. */
    private static final List<String> FLOWS = Arrays.asList("auto", "custom", "off");

    /**
     * Sets [m] in Sony's picture menu; a few words on how it went. Blocking (runs on [io], a press at a
     * time, each given a moment to land); the menu is always closed again.
     */
    private String setMotion(Motion m) {
        String input = playingInput();
        if (input == null) return "Motionflow not changed (couldn't tell what's playing)";
        // Sony's menu is slow to settle now and then; a second go nearly always lands.
        String how = walkOnce(m, input);
        if (!how.equals(done(m))) {
            android.util.Log.i("Hotkeys", "Motionflow, first go: " + how);
            how = walkOnce(m, input);
        }
        return how;
    }

    /**
     * The input playing, as Sony's picture menu names it: "Apps" for an app, "HDMI 4" for whatever is on
     * HDMI port 4 (the API calls a device it knows over HDMI-CEC by its own name, so the port is read from
     * its address); null when it can't tell.
     */
    private static String playingInput() {
        try {
            JSONObject r = call("avContent", "getPlayingContentInfo", new JSONArray());
            JSONArray err = r.optJSONArray("error");
            if (err != null) return err.optInt(0) == 7 ? "Apps" : null; // 7: no input is playing, an app is
            String uri = r.getJSONArray("result").getJSONObject(0).optString("uri");
            java.util.regex.Matcher port = java.util.regex.Pattern.compile("[?&]port=(\\d+)").matcher(uri);
            boolean hdmi = uri.startsWith("extInput:hdmi") || uri.startsWith("extInput:cec");
            return hdmi && port.find() ? "HDMI " + port.group(1) : null;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** What [walkMenu] says when it worked. */
    private static String done(Motion m) {
        return "Motionflow " + title(m.flow);
    }

    private String walkOnce(Motion m, String input) {
        // Picture settings straight away, the way Quick Settings opens it (saves going through its tiles);
        // through the gear if the TV won't.
        boolean direct;
        try {
            startActivity(new android.content.Intent(PICTURE_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            direct = true;
        } catch (RuntimeException e) {
            press(GEAR);
            direct = false;
        }
        try {
            return walkMenu(m, input, direct);
        } catch (RuntimeException e) {
            // Sony's screens changed under us; never let that take the home screen down with it.
            return "Motionflow: the menu went its own way";
        } finally {
            closeMenu(); // out of the menu and Quick Settings, back to the picture
        }
    }

    /**
     * The steps to Motionflow's card, for the settings of [playing]; how it went. [direct]: Picture
     * settings was opened straight away, else Quick Settings is showing.
     */
    private String walkMenu(Motion m, String playing, boolean direct) {
        if (!direct) {
            // Quick Settings usually opens on its Picture settings tile, but on Settings when a BRAVIA
            // notifications tile sits between them; Right steps along the row to Picture settings.
            AccessibilityNodeInfo tile = null;
            for (int i = 0; i < 20 && tile == null; i++) {
                tile = quickTile();
                if (tile == null) pause(100);
            }
            for (int i = 0; i < 4 && tile != null && !"Picture settings".equals(tileName(tile)); i++) {
                press(RIGHT);
                tile = quickTile();
            }
            if (tile == null || !"Picture settings".equals(tileName(tile))) {
                return "Motionflow: no Picture settings in Quick Settings";
            }
            press(CONFIRM);
        }
        // Whose settings the menu shows. It can open with the input it showed last time and correct itself
        // a moment later, so give it up to 3 s to name [playing] before taking its word for another input.
        String input = "";
        for (int i = 0; i < 30 && !sameInput(input, playing); i++) {
            input = text(menu(), "decor_summary");
            if (!sameInput(input, playing)) pause(100);
        }
        if (input.isEmpty()) return "Motionflow: the menu didn't open";
        if (!sameInput(input, playing)) return "Motionflow not changed (menu was for " + input + ", not " + playing + ")";
        // The menu opens on whichever row was highlighted last time: back to the top (Picture mode) first.
        for (int i = 0; i < 40 && !"Picture mode".equals(label(focused())); i++) press(UP);
        if (!moveTo("Motion")) return "Motionflow: no Motion in the menu";
        // Sony remembers which groups were left open, and OK on an open one folds it: look below first,
        // and open the group only when Motionflow isn't there.
        boolean open = false;
        for (int i = 0; i < 3 && !open; i++) {
            press(DOWN);
            open = "Motionflow".equals(label(focused()));
        }
        if (!open) {
            for (int i = 0; i < 4 && !"Motion".equals(label(focused())); i++) press(UP);
            press(CONFIRM); // opens the Motion group
        }
        if (!moveTo("Motionflow")) return "Motionflow: not in the menu";
        // OK opens a strip at the foot of the screen on Motionflow's value: Left and Right step Auto,
        // Custom, Off; OK keeps it.
        if (!openStrip()) return "Motionflow: its strip didn't open";
        int want = FLOWS.indexOf(m.flow);
        for (int i = 0; i < 4; i++) {
            int now = FLOWS.indexOf(text(focused(), "text").toLowerCase(Locale.UK));
            if (now < 0 || now == want) break;
            press(now < want ? RIGHT : LEFT);
        }
        boolean found = m.flow.equalsIgnoreCase(text(focused(), "text"));
        press(CONFIRM);
        if (!waitStrip(false)) press(CONFIRM); // OK sometimes lands before the strip is listening
        return found ? done(m) : "Motionflow: no " + m.flow + " here";
    }

    /**
     * Whether Sony's menu, naming its input [menu], is for [input]: while a video plays it adds what's
     * playing, "Apps (Video)" for "Apps", "HDMI 4 (Video)" for "HDMI 4" (and never "HDMI 10" for "HDMI 1").
     */
    static boolean sameInput(String menu, String input) {
        return menu.equals(input) || menu.startsWith(input + " (");
    }

    private static final String QUICK = "com.android.systemui";

    /** The highlighted tile of Sony's Quick Settings, when it's showing. */
    private AccessibilityNodeInfo quickTile() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null || r.findAccessibilityNodeInfosByViewId(QUICK + ":id/quick_settings_title_text").isEmpty()) return null;
        return r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
    }

    /** A Quick Settings tile's name (Picture settings, Picture mode...). */
    private static String tileName(AccessibilityNodeInfo tile) {
        return text(tile, "quick_settings_tile_header_title", QUICK);
    }

    /** Presses Down until the highlighted row is [title] (at most 40: groups left open add rows). */
    private boolean moveTo(String title) {
        for (int i = 0; i < 40; i++) {
            if (title.equals(label(focused()))) return true;
            press(DOWN);
        }
        return title.equals(label(focused()));
    }

    /** A row's name: plain rows carry it as title, rows with a value (Motionflow: Auto) as expandedTitle. */
    private static String label(AccessibilityNodeInfo row) {
        String t = text(row, "title");
        return t.isEmpty() ? text(row, "expandedTitle") : t;
    }

    /** The value strip is open: it sits along the foot of the screen and takes the focus. */
    private boolean stripOpen() {
        AccessibilityNodeInfo root = menu(), f = focused();
        if (root == null || f == null) return false;
        android.graphics.Rect screen = new android.graphics.Rect(), r = new android.graphics.Rect();
        root.getBoundsInScreen(screen);
        f.getBoundsInScreen(r);
        return r.top > screen.top + screen.height() * 0.85f;
    }

    /**
     * OK on the Motionflow row, once it has settled (it slides its sliders in as it's reached, and an OK
     * meanwhile is lost), tried twice; whether the strip opened.
     */
    private boolean openStrip() {
        for (int i = 0; i < 2; i++) {
            pause(500);
            press(CONFIRM);
            if (waitStrip(true)) return true;
        }
        return false;
    }

    /** Waits (up to a second) for the strip to be [open] or closed; whether it got there. */
    private boolean waitStrip(boolean open) {
        for (int i = 0; i < 10; i++) {
            if (stripOpen() == open) return true;
            pause(100);
        }
        return stripOpen() == open;
    }

    /** Sony's picture menu, when it's the window in front. */
    private AccessibilityNodeInfo menu() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        return r != null && r.getPackageName() != null && MENU.contentEquals(r.getPackageName()) ? r : null;
    }

    private AccessibilityNodeInfo focused() {
        AccessibilityNodeInfo r = menu();
        return r == null ? null : r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
    }

    /** The text of the first node with view id [id] (in Sony's menu, or [pkg]) in [n], or "". */
    private static String text(AccessibilityNodeInfo n, String id) {
        return text(n, id, MENU);
    }

    private static String text(AccessibilityNodeInfo n, String id, String pkg) {
        if (n == null) return "";
        List<AccessibilityNodeInfo> l = n.findAccessibilityNodeInfosByViewId(pkg + ":id/" + id);
        return l.isEmpty() || l.get(0).getText() == null ? "" : l.get(0).getText().toString();
    }

    /** One remote button press through IP control, then a moment for the menu to follow. */
    static void press(String code) {
        try {
            String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                    + "<u:X_SendIRCC xmlns:u=\"urn:schemas-sony-com:service:IRCC:1\"><IRCCCode>" + code
                    + "</IRCCCode></u:X_SendIRCC></s:Body></s:Envelope>";
            HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1/sony/IRCC").openConnection();
            try {
                c.setConnectTimeout(2000);
                c.setReadTimeout(3000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "text/xml; charset=UTF-8");
                c.setRequestProperty("SOAPACTION", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"");
                if (!BuildConfig.BRAVIA_PSK.isEmpty()) c.setRequestProperty("X-Auth-PSK", BuildConfig.BRAVIA_PSK);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
                c.getResponseCode();
            } finally {
                c.disconnect();
            }
        } catch (IOException ignored) {
            // the next look at the menu shows it didn't move
        }
        pause(250);
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- Sound setups (action=sound), through Sony's sound menus

    /** Sony's Audio output menu (Digital audio out, Dolby Digital Plus output...), and its Sound menu. */
    private static final String AUDIO_OUTPUT = "com.sony.dtv.base.audiosystemsettings",
            SOUND_SETTINGS = "com.sony.dtv.base.soundsettings";
    private static final String UP = "AAAAAQAAAAEAAAB0Aw==";
    /**
     * Rows that live in Sound → Volume level, not Audio output; Sony shows them only while an app plays.
     * Sony writes HE‑AAC with a non-breaking hyphen (U+2011), and so must config.xml.
     */
    private static final List<String> VOLUME_LEVEL_ROWS = Arrays.asList("Dolby Dynamic Range", "HE\u2011AAC Dynamic Range");

    /** [name]_1, [name]_2... from config.xml (sound_1...): each its name, then "Menu row=Option" settings. */
    private List<String[]> soundSetups(String name) {
        List<String[]> out = new ArrayList<>();
        for (int i = 1; ; i++) {
            int id = getResources().getIdentifier(name + "_" + i, "array", getPackageName());
            if (id == 0) return out;
            out.add(getResources().getStringArray(id));
        }
    }

    /**
     * Key [n]: one of the [name]_1, _2... setups, chosen when the key's turn comes on [io] (so presses made
     * while another is still at work each see what the one before did). A key whose setups choose the
     * speakers (8) goes to the other speakers from where the sound is now. Any other key (7) steps to its
     * next setup, but when the sound is on the TV speakers its first press only brings the sound back to the
     * sound system, as it was.
     */
    private void nextSound(int n, String name) {
        List<String[]> setups = soundSetups(name);
        if (setups.isEmpty()) {
            show(n + "  Sound", "No " + name + "_1 in config.xml");
            return;
        }
        String[] key = profile(n);
        show(n + "  " + (key == null ? "Sound" : key[0]), "Changing the sound…");
        run(() -> {
            android.content.SharedPreferences prefs = getSharedPreferences("hotkeys", MODE_PRIVATE);
            android.content.SharedPreferences now = getSharedPreferences("sound_now", MODE_PRIVATE);
            boolean onTv = "TV speakers".equals(now.getString("Speakers", null));
            int last = Math.max(-1, prefs.getInt(name, -1)), i = (last + 1) % setups.size();
            int tv = -1;
            for (int k = 0; k < setups.size(); k++) if (Arrays.asList(setups.get(k)).contains("Speakers=TV speakers")) tv = k;
            if (tv >= 0) i = onTv ? (tv + 1) % setups.size() : tv;
            else if (onTv) {
                int first = firstIndex(name);
                i = first >= 0 ? first : last >= 0 ? last % setups.size() : 0;
            }
            String[] s = setups.get(i);
            String title = n + "  " + s[0];
            handler.post(() -> show(title, "Changing the sound…"));
            prefs.edit().putInt(name, i).apply();
            now.edit().putInt("_key", n).apply();
            // "restore=sound": the settings of the sound setup chosen last on that key (back on the sound system,
            // the sound it had).
            List<String> settings = new ArrayList<>();
            String restored = null;
            for (String x : Arrays.asList(s).subList(1, s.length)) {
                if (!x.startsWith("restore=")) {
                    settings.add(x);
                    continue;
                }
                String other = x.substring(8).trim();
                List<String[]> those = soundSetups(other);
                int first = firstIndex(other);
                int at = those.isEmpty() ? -1 : first >= 0 ? first : Math.max(0, prefs.getInt(other, 0)) % those.size();
                if (at >= 0) {
                    prefs.edit().putInt(other, at).apply(); // that key's next press goes on from here
                    settings.addAll(Arrays.asList(those.get(at)).subList(1, those.get(at).length));
                    restored = those.get(at)[0];
                }
            }
            String failed = setSound(settings);
            String how = (restored == null ? "" : restored + " · ")
                    + (setups.size() == 2 ? "Press " + n + " again for " + setups.get(1 - i)[0]
                    : "Sound " + (i + 1) + " of " + setups.size());
            android.util.Log.i("Hotkeys", "Sound " + s[0] + ": " + (failed == null ? "set" : failed));
            handler.post(() -> show(title, failed == null ? how : how + " · " + failed));
        });
    }

    /**
     * The setup the sound system starts on, coming back from the TV speakers: "first=Clear voice" on the key whose
     * setups are [name] (hotkey_7 for sound_N); -1 when none is named.
     */
    private int firstIndex(String name) {
        for (int n = 1; n <= 9; n++) {
            String[] p = profile(n);
            if (p == null || !Arrays.asList(p).contains("action=sound")) continue;
            String setups = "sound", first = null;
            for (String x : p) {
                if (x.startsWith("setups=")) setups = x.substring(7).trim();
                if (x.startsWith("first=")) first = x.substring(6).trim();
            }
            if (!setups.equals(name) || first == null) continue;
            List<String[]> all = soundSetups(name);
            for (int i = 0; i < all.size(); i++) if (all.get(i)[0].equals(first)) return i;
        }
        return -1;
    }

    /**
     * Sets [settings] ("Menu row=Option", as Sony's menus word them) by walking Audio output, then Sound →
     * Volume level, a press at a time; null when all took, else what didn't. Blocking (on [io]).
     */
    private String setSound(List<String> settings) {
        List<String> output = new ArrayList<>(), level = new ArrayList<>(), missed = new ArrayList<>();
        for (String s : settings) {
            if (row(s).equals("HDMI control")) {
                // Off, a soundbar can't take the sound back (it asks for it over HDMI every few seconds);
                // on again, it gets it and the TV remote's volume drives it once more.
                boolean on = s.endsWith("=on");
                try {
                    android.provider.Settings.Global.putInt(getContentResolver(), "hdmi_control_enabled", on ? 1 : 0);
                    pause(1500);
                } catch (SecurityException e) {
                    missed.add("HDMI control (no permission)");
                }
                continue;
            }
            (VOLUME_LEVEL_ROWS.contains(row(s)) ? level : output).add(s);
        }
        try {
            if (!output.isEmpty()) {
                if (openMenu(AUDIO_OUTPUT, "Audio output")) {
                    for (String s : output) if (pickIn(s)) remember(s); else missed.add(row(s));
                } else {
                    missed.add("Audio output didn't open");
                }
                closeMenu();
            }
            if (!level.isEmpty()) {
                boolean open = openMenu(SOUND_SETTINGS, "Sound") && findRow("Volume level");
                if (open) {
                    press(CONFIRM);
                    open = waitTitle("Volume level");
                }
                if (!open) missed.add("Volume level didn't open");
                // Dynamic Range is offered only while an app plays (an HDMI box decodes its own sound).
                else if (!sameInput(text(menu(), "decor_summary"), "Apps")) missed.add("Dynamic Range only in apps");
                else {
                    List<String> again = new ArrayList<>();
                    for (String s : level) if (pickIn(s)) remember(s); else again.add(s);
                    // A row Sony was still settling (just after a change of speakers): once more.
                    for (String s : again) if (pickIn(s)) remember(s); else missed.add(row(s));
                }
                closeMenu();
            }
        } catch (RuntimeException e) {
            // Sony's screens changed under us; never let that take the home screen down with it.
            closeMenu();
            return "the menu went its own way";
        }
        return missed.isEmpty() ? null : "not set: " + android.text.TextUtils.join(", ", missed);
    }

    /** Notes a sound setting Sony's menu took ("Digital audio out=PCM"), for key 0 to tell from. */
    private void remember(String setting) {
        getSharedPreferences("sound_now", MODE_PRIVATE).edit()
                .putString(row(setting), setting.substring(setting.indexOf('=') + 1).trim()).apply();
    }

    /**
     * "Sound now: Clear voice (7)": the one setup, across the sound keys, whose settings all match what the
     * keys last set in Sony's menu (the fullest match, should a smaller setup also fit); empty before any press.
     * A change made by hand in Sony's menu isn't seen.
     */
    private String soundNow() {
        android.content.SharedPreferences now = getSharedPreferences("sound_now", MODE_PRIVATE);
        String best = null, speakersKey = null;
        int bestSize = 0;
        boolean any = false;
        for (int n = 1; n <= 9; n++) {
            String[] p = profile(n);
            if (p == null || !Arrays.asList(p).contains("action=sound")) continue;
            any = true;
            String name = "sound";
            for (String x : p) if (x.startsWith("setups=")) name = x.substring(7).trim();
            if (name.equals("speakers")) {
                speakersKey = String.valueOf(n);
                continue;
            }
            for (String[] setup : soundSetups(name)) {
                boolean all = setup.length > 1;
                for (int i = 1; i < setup.length && all; i++) {
                    if (row(setup[i]).equals("HDMI control")) continue; // not a menu row: not remembered
                    all = setup[i].substring(setup[i].indexOf('=') + 1).trim().equals(now.getString(row(setup[i]), null));
                }
                // The same setup on two keys: named for the key pressed last.
                if (all && (setup.length - 1 > bestSize || setup.length - 1 == bestSize && n == now.getInt("_key", 0))) {
                    best = setup[0] + " (" + n + ")";
                    bestSize = setup.length - 1;
                }
            }
        }
        if (!any) return "";
        if ("TV speakers".equals(now.getString("Speakers", null))) {
            return "Sound now: TV speakers" + (speakersKey == null ? "" : " (" + speakersKey + ")");
        }
        int named = getResources().getIdentifier("sound_system", "string", getPackageName());
        String system = named == 0 ? "the sound system" : getString(named);
        return "Sound now: " + (best != null ? best + " on " + system
                : now.getAll().size() <= 1 ? "press 7 once" : "changed by hand");
    }

    private static String row(String setting) {
        return setting.substring(0, setting.indexOf('=')).trim();
    }

    /** Opens Sony's menu by [action] and waits for it to be titled [title]; whether it did. */
    private boolean openMenu(String action, String title) {
        try {
            startActivity(new android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            return false;
        }
        if (waitTitle(title)) return true;
        // Sony can reopen on a page under it (Home Theatre Control, under Audio output): one Back to its top.
        if (menu() == null) return false;
        performGlobalAction(GLOBAL_ACTION_BACK);
        return waitTitle(title);
    }

    /** Waits (up to 3 s) for Sony's menu in front to be titled [title]. */
    private boolean waitTitle(String title) {
        for (int i = 0; i < 30; i++) {
            if (title.equals(text(menu(), "decor_title"))) return true;
            pause(100);
        }
        return false;
    }

    /**
     * Back out of Sony's menus, to whatever was playing: one Back a page, each waited out (a Back sent while
     * a page is still closing goes on to the app and stops its video).
     */
    private void closeMenu() {
        // A page that's closing can still be read for a moment, and a Back sent to it goes on to the app below
        // (SonyLIV then asks whether to exit; a video stops). So each Back is for a page not backed out of yet,
        // read the same twice running, and the next waits until that page has gone.
        java.util.Set<String> gone = new java.util.HashSet<>();
        for (int i = 0; i < 8; i++) {
            String page = page();
            if (page == null) break;
            if (gone.contains(page)) {
                pause(200); // the one just closed, on its way out
                continue;
            }
            gone.add(page);
            performGlobalAction(GLOBAL_ACTION_BACK);
            for (int k = 0; k < 20 && page.equals(page()); k++) pause(100);
        }
        pause(300);
    }

    /** Sony's menu page (or Quick Settings) showing, read twice 150 ms apart to agree; null when neither is. */
    private String page() {
        String a = pageOnce();
        for (int i = 0; i < 5; i++) {
            pause(150);
            String b = pageOnce();
            if (a == null ? b == null : a.equals(b)) return a;
            a = b;
        }
        return a;
    }

    /** Which page: its title and where its highlight is (a value strip open on a page is a page of its own). */
    private String pageOnce() {
        AccessibilityNodeInfo m = menu();
        if (m != null) {
            AccessibilityNodeInfo f = m.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            android.graphics.Rect b = new android.graphics.Rect();
            if (f != null) f.getBoundsInScreen(b);
            return "menu:" + text(m, "decor_title") + ":" + b.toShortString();
        }
        return quickTile() != null ? "quick" : null;
    }

    /**
     * "Row=Option" in the menu showing: the row highlighted (Down, then Up), OK opens its list of options,
     * Up/Down to the option, OK takes it and Sony goes back to the menu. Whether it took.
     */
    private boolean pickIn(String setting) {
        String row = row(setting), option = setting.substring(setting.indexOf('=') + 1).trim();
        String parent = text(menu(), "decor_title");
        if (!findRow(row)) return false;
        // Greyed out: Sony doesn't use it with the rest as set (Dolby Digital Plus output, with PCM out).
        List<AccessibilityNodeInfo> name = focused() == null ? null
                : focused().findAccessibilityNodeInfosByViewId("android:id/title");
        if (name != null && !name.isEmpty() && !name.get(0).isEnabled()) return true;
        press(CONFIRM);
        if (!waitTitle(row)) return false;
        List<String> options = new ArrayList<>();
        AccessibilityNodeInfo root = menu();
        if (root != null) {
            for (AccessibilityNodeInfo t : root.findAccessibilityNodeInfosByViewId("android:id/title")) {
                if (t.getText() != null) options.add(t.getText().toString());
            }
        }
        int want = options.indexOf(option);
        for (int i = 0; i < 6 && want >= 0; i++) {
            int now = options.indexOf(soundLabel(focused()));
            if (now < 0 || now == want) break;
            press(now < want ? DOWN : UP);
        }
        boolean found = option.equals(soundLabel(focused()));
        if (found) press(CONFIRM);
        else performGlobalAction(GLOBAL_ACTION_BACK);
        // Back on the menu it came from, its row now saying the option.
        return waitTitle(parent) && found;
    }

    /** Moves the highlight to the row titled [title]: Down first, then back Up (at most 15 each way). */
    private boolean findRow(String title) {
        for (String key : new String[]{DOWN, UP}) {
            for (int i = 0; i < 15; i++) {
                if (title.equals(soundLabel(focused()))) return true;
                press(key);
            }
        }
        return title.equals(soundLabel(focused()));
    }

    /** A row's name in Sony's sound menus: most rows are Android's own (android:id/title), a few Sony's. */
    private static String soundLabel(AccessibilityNodeInfo row) {
        String t = text(row, "title", "android");
        return t.isEmpty() ? label(row) : t;
    }

    /** The first node in Sony's menu with view id [id] (and, if given, exactly [text]). */
    private static AccessibilityNodeInfo byId(AccessibilityNodeInfo root, String id, String text) {
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(MENU + ":id/" + id)) {
            if (text == null || (n.getText() != null && text.contentEquals(n.getText()))) return n;
        }
        return null;
    }


    /** "1 Sports · 2 Movies · …" and the picture mode now. */
    private void showKeys() {
        StringBuilder keys = new StringBuilder();
        for (int n = 1; n <= 9; n++) {
            String[] p = profile(n);
            if (p != null) keys.append(keys.length() == 0 ? "" : "   ").append(n).append(' ').append(p[0]);
        }
        String line = keys.length() == 0 ? "No picture keys in config.xml" : keys.toString();
        String sound = soundNow();
        show(line, sound);
        run(() -> {
            String picture = pictureNow();
            String detail = sound.isEmpty() ? picture : picture.isEmpty() ? sound : picture + "   ·   " + sound;
            handler.post(() -> show(line, detail));
        });
    }

    /** [work] on [io], unless the service is stopping (Android turns off a service that keeps crashing). */
    private void run(Runnable work) {
        try {
            io.execute(work);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    // ---- Sony's picture API (blocking: off the main thread)

    /** Sends [settings] ("target=value"); null when the TV took them, else why not, in a few words. */
    private String set(List<String> settings) {
        try {
            JSONArray a = new JSONArray();
            for (String s : settings) {
                int eq = s.indexOf('=');
                a.put(new JSONObject().put("target", s.substring(0, eq).trim()).put("value", s.substring(eq + 1).trim()));
            }
            JSONObject r = call("setPictureQualitySettings", new JSONObject().put("settings", a));
            JSONArray err = r.optJSONArray("error");
            if (err == null) return null;
            int code = err.optInt(0);
            if (code == 403 || code == 401) return BuildConfig.BRAVIA_PSK.isEmpty()
                    ? "Needs the IP control key (see README)" : "The TV turned down the IP control key";
            return "The TV said no (" + err.optString(1, "error " + code) + ")";
        } catch (IOException | JSONException e) {
            return "Couldn't reach the TV's picture settings";
        }
    }

    /** Those of [settings] ("target=value") the TV offers for the input playing now; all of them if it won't say. */
    private static List<String> available(List<String> settings) {
        try {
            JSONArray r = call("getPictureQualitySettings", new JSONObject()).optJSONArray("result");
            JSONArray all = r == null ? null : r.optJSONArray(0);
            if (all == null) return settings;
            java.util.Set<String> on = new java.util.HashSet<>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject t = all.optJSONObject(i);
                if (t != null && t.optBoolean("isAvailable", true)) on.add(t.optString("target"));
            }
            List<String> out = new ArrayList<>();
            for (String s : settings) if (on.contains(s.substring(0, s.indexOf('=')).trim())) out.add(s);
            return out;
        } catch (IOException | JSONException e) {
            return settings;
        }
    }

    /**
     * "Picture now: Everyday (5)": the picture key whose settings all match the TV's now (Movies and Night
     * share Sony's Cinema mode, so it takes them all, not the mode alone); "Everyday (5), adjusted" when the
     * nearest one in the same mode differs in something; Sony's own mode name when no key uses that mode.
     */
    private String pictureNow() {
        java.util.Map<String, String> tv = new java.util.HashMap<>();
        try {
            JSONArray r = call("getPictureQualitySettings", new JSONObject()).optJSONArray("result");
            JSONArray all = r == null ? null : r.optJSONArray(0);
            for (int i = 0; all != null && i < all.length(); i++) {
                JSONObject t = all.optJSONObject(i);
                if (t != null && t.optBoolean("isAvailable", true)) tv.put(t.optString("target"), t.optString("currentValue"));
            }
        } catch (IOException | JSONException e) {
            return "";
        }
        String mode = tv.get("pictureMode");
        if (mode == null) return "";
        String best = null;
        int bestMiss = Integer.MAX_VALUE, bestSize = -1;
        for (int n = 1; n <= 9; n++) {
            String[] p = profile(n);
            if (p == null || !Arrays.asList(p).contains("pictureMode=" + mode)) continue;
            int miss = 0, size = 0;
            for (String x : Arrays.asList(p).subList(1, p.length)) {
                int eq = x.indexOf('=');
                String have = eq < 0 ? null : tv.get(x.substring(0, eq).trim());
                if (have == null) continue; // motionflow, or a setting this input lacks
                size++;
                if (!have.equalsIgnoreCase(x.substring(eq + 1).trim())) miss++;
            }
            if (miss < bestMiss || miss == bestMiss && size > bestSize) {
                best = p[0] + " (" + n + ")";
                bestMiss = miss;
                bestSize = size;
            }
        }
        if (best == null) return "Picture now: " + title(mode);
        return "Picture now: " + best + (bestMiss == 0 ? "" : ", adjusted");
    }

    private static JSONObject call(String method, JSONObject params) throws IOException, JSONException {
        return call("video", method, new JSONArray().put(params));
    }

    /** [method] of Sony's [service] (video, avContent...) with [params]; its answer. */
    static JSONObject call(String service, String method, JSONArray params) throws IOException, JSONException {
        byte[] body = new JSONObject().put("method", method).put("id", 1).put("version", "1.0")
                .put("params", params).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(API + service).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            if (!BuildConfig.BRAVIA_PSK.isEmpty()) c.setRequestProperty("X-Auth-PSK", BuildConfig.BRAVIA_PSK);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            try (InputStream s = in) {
                byte[] buf = new byte[4096];
                for (int k; (k = s.read(buf)) > 0; ) text.write(buf, 0, k);
            }
            return new JSONObject(text.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    /** "cinema" as the TV's menu says it: "Cinema". */
    private static String title(String value) {
        return value.isEmpty() ? value : value.substring(0, 1).toUpperCase(Locale.UK) + value.substring(1);
    }

    // ---- The card

    private void show(String title, String detail) {
        if (card == null) {
            card = new Card(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Ui.dpi(this, Ui.SAFE_TOP);
            getSystemService(WindowManager.class).addView(card, lp);
        }
        card.set(title, detail);
    }

    /**
     * The card: the key and profile (15, semibold) over what the TV did (13), on a dark rounded plate with
     * the hairline frame the bento uses. Shows at once, holds 2.5 s, fades out over 0.4 s, on its own clock:
     * this TV has animations switched off, which ends Android's animators the moment they start.
     */
    static final class Card extends View {
        private final TextPaint titleP, detailP;
        private final Paint plateP = new Paint(Paint.ANTI_ALIAS_FLAG), lineP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF plate = new RectF();
        private String title = "", detail = "";
        private long fadeAt;

        Card(Context c) {
            super(c);
            titleP = Ui.textPaint(c, Ui.semibold(c), Ui.T_BODY, Ui.TEXT, 0f, false);
            detailP = Ui.textPaint(c, Ui.medium(c), Ui.T_LABEL, Ui.TEXT_2, 0f, false);
            plateP.setColor(0xF2161616);
            lineP.setStyle(Paint.Style.STROKE);
            lineP.setStrokeWidth(Ui.dp(c, 1f));
            lineP.setColor(Ui.FRAME_HAIRLINE);
        }

        void set(String title, String detail) {
            this.title = title;
            this.detail = detail;
            fadeAt = Ui.now() + 2500;
            requestLayout();
            invalidate();
        }

        void remove() {
            try {
                getContext().getSystemService(WindowManager.class).removeView(this);
            } catch (IllegalArgumentException ignored) {
            }
        }

        @Override
        protected void onMeasure(int w, int h) {
            Context c = getContext();
            float text = Math.max(titleP.measureText(title), detail.isEmpty() ? 0 : detailP.measureText(detail));
            float height = Ui.dp(c, detail.isEmpty() ? 44f : 62f);
            setMeasuredDimension(Math.round(text + 2 * Ui.dp(c, Ui.S5)), Math.round(height));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long now = Ui.now();
            float alpha = Math.max(0f, Math.min(1f, 1f - (now - fadeAt) / 400f));
            if (alpha <= 0f) return;
            // Still while it holds (nothing to redraw over the match), every frame once it fades.
            if (now < fadeAt) postInvalidateDelayed(fadeAt - now);
            else postInvalidateOnAnimation();
            int save = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * alpha));
            drawCard(canvas);
            canvas.restoreToCount(save);
        }

        private void drawCard(Canvas canvas) {
            Context c = getContext();
            float r = Ui.dp(c, Ui.R_TILE), half = lineP.getStrokeWidth() / 2f;
            plate.set(half, half, getWidth() - half, getHeight() - half);
            canvas.drawRoundRect(plate, r, r, plateP);
            canvas.drawRoundRect(plate, r, r, lineP);
            float x = Ui.dp(c, Ui.S5);
            if (detail.isEmpty()) {
                canvas.drawText(title, x, Ui.baselineIn(titleP, 0, getHeight()), titleP);
            } else {
                canvas.drawText(title, x, Ui.baselineIn(titleP, Ui.dp(c, 10f), Ui.dp(c, 22f)), titleP);
                canvas.drawText(detail, x, Ui.baselineIn(detailP, Ui.dp(c, 32f), Ui.dp(c, 18f)), detailP);
            }
        }
    }
}
