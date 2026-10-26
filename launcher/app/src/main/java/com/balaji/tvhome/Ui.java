package com.balaji.tvhome;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.animation.AnimationUtils;
import android.view.animation.PathInterpolator;

import java.util.HashMap;
import java.util.Map;

/**
 * The design tokens (palette, type, spacing, radii, motion), plus the small helpers every part shares.
 * Sizes are dp: the TV lays out at 960 x 540 dp.
 */
final class Ui {
    private Ui() {
    }

    // Palette
    static final int BG = 0xFF000000;
    static final int BAND = 0x13FFFFFF;          // the dock's tray: white at 7.5%
    static final int FILL = 0xFF1A1A1C;          // a poster with no art yet
    static final int TEXT = 0xFFEBEBEB;
    static final int TEXT_2 = 0xFFA1A1A1;
    static final int FOCUS_RING = 0x99FFFFFF;    // 1 dp, 2.5 dp outside the art
    static final int POSTER_FOCUS = 0xFF2B2B2D;  // a focused poster with no art yet
    static final int DOCK_FRAME = 0xFF020202;    // the bento's black frame
    static final int FRAME_HAIRLINE = 0x1CFFFFFF;
    static final int TILE = 0xFF161616;          // bento tiles
    static final int CALENDAR_RED = 0xFFFC5855;  // the weekday

    // Type scale (sp = dp here)
    static final float T_BODY = 15f;
    static final float T_LABEL = 13f;
    static final float T_CAPTION = 12f;

    // Spacing and the safe area
    static final float S1 = 4f, S2 = 8f, S3 = 12f, S4 = 16f, S5 = 20f, S6 = 24f, S8 = 32f;
    static final float SAFE_TOP = 32f, SAFE_SIDE = 56f;

    // Corner radii
    static final float R_TILE = 14f, R_POSTER = 16f, R_TRAY = 26f;

    /** The one ease used everywhere: cubic-bezier(0.23, 1, 0.32, 1). */
    static final TimeInterpolator EASE_OUT = new PathInterpolator(0.23f, 1f, 0.32f, 1f);
    /** The second hand's tick: cubic-bezier(0.2, 0, 0, 1). */
    static final TimeInterpolator TICK = new PathInterpolator(0.2f, 0f, 0f, 1f);

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    static int dpi(Context c, float v) {
        return Math.round(dp(c, v));
    }

    static long now() {
        return AnimationUtils.currentAnimationTimeMillis();
    }

    // ---- Open Runde, the only font, in four weights

    private static Typeface[] fonts;

    private static Typeface font(Context c, int i) {
        if (fonts == null) {
            fonts = new Typeface[]{
                    c.getResources().getFont(R.font.open_runde_regular),
                    c.getResources().getFont(R.font.open_runde_medium),
                    c.getResources().getFont(R.font.open_runde_semibold),
                    c.getResources().getFont(R.font.open_runde_bold),
            };
        }
        return fonts[i];
    }

    static Typeface regular(Context c) {
        return font(c, 0);
    }

    static Typeface medium(Context c) {
        return font(c, 1);
    }

    static Typeface semibold(Context c) {
        return font(c, 2);
    }

    static Typeface bold(Context c) {
        return font(c, 3);
    }

    static TextPaint textPaint(Context c, Typeface face, float sizeDp, int color, float trackingEm, boolean tabular) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(dp(c, sizeDp));
        p.setColor(color);
        p.setLetterSpacing(trackingEm);
        if (tabular) p.setFontFeatureSettings("tnum");
        return p;
    }

    private static final Paint.FontMetrics metrics = new Paint.FontMetrics();

    /** The baseline that centres [paint]'s text in a line box [lineHeight] tall starting at [top]. */
    static float baselineIn(Paint paint, float top, float lineHeight) {
        paint.getFontMetrics(metrics);
        return top + (lineHeight - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent;
    }

    /** Mixes two colours channel by channel in sRGB. */
    static int mix(int from, int to, float t) {
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 0xFF, b = (to >>> shift) & 0xFF;
            int ch = Math.max(0, Math.min(255, Math.round(a + (b - a) * t)));
            out |= ch << shift;
        }
        return out;
    }

    static int withAlpha(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * alpha);
        return (a << 24) | (color & 0xFFFFFF);
    }

    // ---- Phosphor glyphs as alpha masks, drawn in a paint's colour

    private static final Map<Long, Bitmap> masks = new HashMap<>();

    static Bitmap glyph(Context c, int id, int sizePx) {
        long key = ((long) id << 16) | sizePx;
        Bitmap m = masks.get(key);
        if (m == null) {
            Drawable d = c.getDrawable(id).mutate();
            Bitmap argb = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, sizePx, sizePx);
            d.draw(new Canvas(argb));
            m = argb.extractAlpha();
            argb.recycle();
            masks.put(key, m);
        }
        return m;
    }

    /**
     * One value easing from wherever it is to a target, as a CSS transition does. A view calls [step] in
     * onDraw and asks for another frame while it returns true.
     */
    static final class Tween {
        float value, target;
        private float from;
        private long start, duration;
        private TimeInterpolator ease = EASE_OUT;

        Tween(float initial) {
            value = target = from = initial;
        }

        void to(float goal, long durationMs) {
            to(goal, durationMs, EASE_OUT);
        }

        void to(float goal, long durationMs, TimeInterpolator interpolator) {
            if (goal == target && (value != target || value == goal)) return;
            if (durationMs <= 0) {
                snap(goal);
                return;
            }
            from = value;
            target = goal;
            start = now();
            duration = durationMs;
            ease = interpolator;
        }

        void snap(float goal) {
            from = target = value = goal;
        }

        boolean step() {
            if (value == target) return false;
            float t = duration <= 0 ? 1f : Math.max(0f, Math.min(1f, (now() - start) / (float) duration));
            value = t >= 1f ? target : from + (target - from) * ease.getInterpolation(t);
            return value != target;
        }
    }
}
