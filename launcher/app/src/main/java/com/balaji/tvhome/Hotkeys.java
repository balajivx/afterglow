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
 * on the TV (http://127.0.0.1/sony/video: IP control, signed with the pre-shared key from the build).
 * 0 shows what each key does. Keys with no profile do what they always did.
 *
 * Motionflow isn't in Sony's API, and on this TV it doesn't follow the picture mode, so a profile's
 * motionflow (auto, custom, off) is set in Sony's own picture menu, the way a person would, with remote
 * presses sent over IP control (IRCC): the gear (Quick Settings), Picture settings (opened like this
 * over a playing app, it's the menu for "Apps"), down to Motion and Motionflow, the value stepped round,
 * the menu closed. Accessibility only reads which row is highlighted. It shows for a few seconds, and
 * only ever touches the Apps settings, never an HDMI input's or the TV's. Smoothness and Clearness stay
 * as set by hand: Sony ignores them set any other way, and they keep their values as Motionflow changes.
 *
 * An accessibility service, as that's the only way Android lets an app see keys meant for other apps and
 * work Sony's menu; it reads no other screen. A small card at the top says what happened, then fades.
 */
public class Hotkeys extends AccessibilityService {
    private static final String API = "http://127.0.0.1/sony/video";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Card card;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
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
        show(n + "  " + name, "Changing the picture…");
        List<String> settings = new ArrayList<>(Arrays.asList(profile).subList(1, profile.length));
        Motion motion = Motion.from(settings); // takes its settings out of the list
        io.execute(() -> {
            // The picture mode first, on its own: the rest are that mode's own settings.
            String mode = null;
            List<String> rest = new ArrayList<>();
            for (String s : settings) {
                if (s.startsWith("pictureMode=")) mode = s;
                else rest.add(s);
            }
            String failed = mode == null ? null : set(Arrays.asList(mode));
            if (failed == null && !rest.isEmpty()) failed = set(rest);
            String detail = failed != null ? failed
                    : mode == null ? "Picture adjusted" : "Picture mode: " + title(mode.substring(mode.indexOf('=') + 1));
            String flow = motion == null ? null : setMotion(motion);
            handler.post(() -> show(n + "  " + name, flow == null ? detail : detail + " · " + flow));
        });
    }

    // ---- Motionflow, through Sony's picture menu

    private static final String MENU = "com.sony.dtv.configsettings";

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
        // Sony's menu is slow to settle now and then; a second go nearly always lands.
        String how = walkOnce(m);
        if (!how.equals(done(m))) {
            android.util.Log.i("Hotkeys", "Motionflow, first go: " + how);
            how = walkOnce(m);
        }
        return how;
    }

    /** What [walkMenu] says when it worked. */
    private static String done(Motion m) {
        return "Motionflow " + title(m.flow);
    }

    private String walkOnce(Motion m) {
        press(GEAR);
        try {
            return walkMenu(m);
        } catch (RuntimeException e) {
            // Sony's screens changed under us; never let that take the home screen down with it.
            return "Motionflow: the menu went its own way";
        } finally {
            // Out of the menu and Quick Settings, back to the picture.
            for (int i = 0; i < 6 && (menu() != null || quickTile() != null); i++) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                pause(250);
            }
            pause(300);
        }
    }

    /** The steps from Quick Settings to Motionflow's card; how it went. */
    private String walkMenu(Motion m) {
        // Quick Settings opens on its Picture settings tile.
        AccessibilityNodeInfo tile = null;
        for (int i = 0; i < 20 && tile == null; i++) {
            tile = quickTile();
            if (tile == null) pause(100);
        }
        if (tile == null || !"Picture settings".equals(text(tile, "quick_settings_tile_header_title", QUICK))) {
            return "Motionflow: Quick Settings didn't open on Picture settings";
        }
        press(CONFIRM);
        // Whose settings the menu shows. It opens with the input it showed last time and corrects itself a
        // moment later, so give it up to 3 s to say Apps before taking its word for another input.
        String input = "";
        for (int i = 0; i < 30 && !input.equals("Apps"); i++) {
            input = text(menu(), "decor_summary");
            if (!input.equals("Apps")) pause(100);
        }
        if (input.isEmpty()) return "Motionflow: the menu didn't open";
        if (!input.equals("Apps")) return "Motionflow not changed (menu was for " + input + ")";
        if (!moveTo("Motion")) return "Motionflow: no Motion in the menu";
        press(CONFIRM); // opens the Motion group
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

    private static final String QUICK = "com.android.systemui";

    /** The highlighted tile of Sony's Quick Settings, when it's showing. */
    private AccessibilityNodeInfo quickTile() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null || r.findAccessibilityNodeInfosByViewId(QUICK + ":id/quick_settings_title_text").isEmpty()) return null;
        return r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
    }

    /** Presses Down until the highlighted row is [title] (at most 15). */
    private boolean moveTo(String title) {
        for (int i = 0; i < 15; i++) {
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
    private static void press(String code) {
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
        show(line, "");
        io.execute(() -> {
            String mode = currentMode();
            if (mode != null) handler.post(() -> show(line, "Picture mode now: " + title(mode)));
        });
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

    private String currentMode() {
        try {
            JSONArray r = call("getPictureQualitySettings", new JSONObject().put("target", "pictureMode")).optJSONArray("result");
            JSONObject s = r == null ? null : r.optJSONArray(0).optJSONObject(0);
            return s == null ? null : s.optString("currentValue", null);
        } catch (IOException | JSONException | NullPointerException e) {
            return null;
        }
    }

    private static JSONObject call(String method, JSONObject params) throws IOException, JSONException {
        byte[] body = new JSONObject().put("method", method).put("id", 1).put("version", "1.0")
                .put("params", new JSONArray().put(params)).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
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
