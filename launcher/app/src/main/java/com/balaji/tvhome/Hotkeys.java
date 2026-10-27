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
 * An accessibility service, as that's the only way Android lets an app see keys meant for other apps; it
 * reads no screen content. A small card at the top says what happened, then fades.
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
            handler.post(() -> show(n + "  " + name, detail));
        });
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
