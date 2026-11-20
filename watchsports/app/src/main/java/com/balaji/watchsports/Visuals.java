package com.balaji.watchsports;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.List;
import java.util.Map;

/**
 * The app's drawn pieces. All animation runs only while the view is on screen (stopped when it's detached), so
 * none of it costs anything once the app is closed.
 */
final class Visuals {
    private Visuals() {
    }

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    /**
     * The lap ring round the round screen's edge (inspired by timing loops and Activity rings): a faint track, the
     * fill in the sport's colour, and a glowing "car" at its head. Sweeps in when shown. A Test shows five days.
     */
    static final class LapRing extends View {
        private Ring ring;
        private int accent;
        private float shown; // animated 0 -> ring.fill
        private ValueAnimator sweep;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                glow = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        LapRing(Context c) {
            super(c);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(dp(c, 3));
            track.setColor(0x26FFFFFF);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(dp(c, 4));
            fill.setStrokeCap(Paint.Cap.ROUND);
            dot.setColor(0xFFFFFFFF);
        }

        void set(Ring r, int accentColour) {
            ring = r;
            accent = accentColour;
            fill.setColor(accentColour);
            setContentDescription(r == null ? null : "Progress: " + r.label);
            if (sweep != null) sweep.cancel();
            if (r == null) {
                shown = 0;
                invalidate();
                return;
            }
            sweep = ValueAnimator.ofFloat(0f, r.fill);
            sweep.setDuration(900);
            sweep.setInterpolator(new DecelerateInterpolator());
            sweep.addUpdateListener(a -> {
                shown = (float) a.getAnimatedValue();
                invalidate();
            });
            if (isAttachedToWindow()) sweep.start();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (sweep != null && !sweep.isStarted()) sweep.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (sweep != null) sweep.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (ring == null) return;
            float inset = dp(getContext(), 5);
            box.set(inset, inset, getWidth() - inset, getHeight() - inset);
            float gap = ring.segments > 0 ? 6f : 0f;
            int n = Math.max(1, ring.segments);
            float per = 360f / n;
            for (int k = 0; k < n; k++) c.drawArc(box, -90 + k * per + gap / 2, per - gap, false, track);
            float sweepDeg = 360f * shown;
            if (ring.segments > 0) {
                for (int k = 0; k < n; k++) {
                    float from = k * per, to = Math.min(sweepDeg, (k + 1) * per);
                    if (to > from + gap / 2) c.drawArc(box, -90 + from + gap / 2, to - from - gap, false, fill);
                }
            } else if (sweepDeg > 0.5f) {
                c.drawArc(box, -90, sweepDeg, false, fill);
            }
            // the car at the head, with a soft glow in the sport's colour
            double a = Math.toRadians(-90 + sweepDeg);
            float cx = box.centerX() + (float) Math.cos(a) * box.width() / 2;
            float cy = box.centerY() + (float) Math.sin(a) * box.height() / 2;
            float r = dp(getContext(), 11);
            glow.setShader(new RadialGradient(cx, cy, r, (accent & 0x00FFFFFF) | 0xCC000000, accent & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, glow);
            c.drawCircle(cx, cy, dp(getContext(), 3.2f), dot);
        }
    }

    /** A dot that breathes (for LIVE): only animates while on screen. */
    static View pulse(Context c, int colour) {
        android.widget.TextView t = new android.widget.TextView(c);
        t.setText("●  ");
        t.setTextColor(colour);
        t.setTextSize(11);
        ValueAnimator a = ValueAnimator.ofFloat(1f, 0.25f);
        a.setDuration(800);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.addUpdateListener(v -> t.setAlpha((float) v.getAnimatedValue()));
        t.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                a.start();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                a.cancel();
            }
        });
        return t;
    }

    /**
     * The F1 weekend as stations on a line (after TAG Heuer's race-weekend face): done = filled yellow, on now =
     * filled + glow, next = a ring that breathes, later = dim. Names above; the next one's time below.
     */
    static final class WeekendLine extends View {
        private final List<Weekend.Station> stations;
        private final int current;
        private final boolean onNow;
        private float breath = 1f;
        private final ValueAnimator anim = ValueAnimator.ofFloat(0.55f, 1f);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), node = new Paint(Paint.ANTI_ALIAS_FLAG),
                text = new Paint(Paint.ANTI_ALIAS_FLAG);

        WeekendLine(Context c, List<Weekend.Station> stations, long now) {
            super(c);
            this.stations = stations;
            this.current = Weekend.current(stations, now);
            this.onNow = current < stations.size() && Weekend.onNow(stations.get(current), now);
            line.setStrokeWidth(dp(c, 2));
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(dp(c, 9.5f));
            text.setTypeface(Typeface.DEFAULT_BOLD);
            anim.setDuration(900);
            anim.setRepeatMode(ValueAnimator.REVERSE);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.addUpdateListener(v -> {
                breath = (float) v.getAnimatedValue();
                invalidate();
            });
            setMinimumHeight((int) dp(c, 46));
            StringBuilder d = new StringBuilder("Race weekend: ");
            for (Weekend.Station s : stations) d.append(s.name).append(' ');
            setContentDescription(d.toString());
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            anim.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            anim.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(getContext(), 46));
        }

        @Override
        protected void onDraw(Canvas c) {
            int n = stations.size();
            if (n == 0) return;
            float pad = dp(getContext(), 14), y = dp(getContext(), 22), r = dp(getContext(), 5);
            float step = n == 1 ? 0 : (getWidth() - 2 * pad) / (n - 1);
            for (int i = 0; i < n - 1; i++) {
                line.setColor(i < current ? MainActivity.YELLOW : 0x40FFFFFF);
                c.drawLine(pad + i * step + r, y, pad + (i + 1) * step - r, y, line);
            }
            for (int i = 0; i < n; i++) {
                float x = pad + i * step;
                Weekend.Station s = stations.get(i);
                boolean done = i < current, next = i == current;
                text.setColor(next ? MainActivity.WHITE : done ? MainActivity.YELLOW : MainActivity.DIM);
                c.drawText(s.name, x, y - dp(getContext(), 10), text);
                if (done || (next && onNow)) {
                    node.setStyle(Paint.Style.FILL);
                    node.setColor(MainActivity.YELLOW);
                    if (next) {
                        node.setColor((MainActivity.YELLOW & 0x00FFFFFF) | ((int) (0x55 * breath) << 24));
                        c.drawCircle(x, y, r * 2.2f, node);
                        node.setColor(MainActivity.YELLOW);
                    }
                    c.drawCircle(x, y, r, node);
                } else if (next) {
                    node.setStyle(Paint.Style.STROKE);
                    node.setStrokeWidth(dp(getContext(), 2));
                    node.setColor((MainActivity.YELLOW & 0x00FFFFFF) | ((int) (0xFF * breath) << 24));
                    c.drawCircle(x, y, r * (0.8f + 0.4f * breath), node);
                    text.setColor(MainActivity.YELLOW);
                    c.drawText(Feed.when(s.time).replace("Today ", "").replace("Tomorrow ", "Tmrw "), x, y + dp(getContext(), 19), text);
                } else {
                    node.setStyle(Paint.Style.STROKE);
                    node.setStrokeWidth(dp(getContext(), 1.5f));
                    node.setColor(0x60FFFFFF);
                    c.drawCircle(x, y, r * 0.8f, node);
                }
            }
        }
    }

    /**
     * The finishing order as 20 slim bars (after F1 TV's timing tower), the two Ferraris lit red with their codes
     * and places; the podium a touch brighter.
     */
    static final class GridStrip extends View {
        private final Map<String, Integer> ferrari; // code -> place
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        GridStrip(Context c, Map<String, Integer> ferrari) {
            super(c);
            this.ferrari = ferrari;
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(dp(c, 9.5f));
            text.setTypeface(Typeface.DEFAULT_BOLD);
            StringBuilder d = new StringBuilder("Ferraris: ");
            for (Map.Entry<String, Integer> e : ferrari.entrySet()) d.append(e.getKey()).append(" P").append(e.getValue()).append(' ');
            setContentDescription(d.toString());
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(getContext(), 58));
        }

        @Override
        protected void onDraw(Canvas c) {
            float pad = dp(getContext(), 6), top = dp(getContext(), 18), h = dp(getContext(), 16);
            float w = (getWidth() - 2 * pad) / 20f;
            int k = 0;
            for (int p = 1; p <= 20; p++) {
                String code = null;
                for (Map.Entry<String, Integer> e : ferrari.entrySet()) if (e.getValue() == p) code = e.getKey();
                float x = pad + (p - 1) * w;
                rect.set(x + w * 0.18f, top, x + w * 0.82f, top + h);
                bar.setColor(code != null ? MainActivity.RED : p <= 3 ? 0x80FFFFFF : 0x33FFFFFF);
                c.drawRoundRect(rect, w * 0.3f, w * 0.3f, bar);
                if (code != null) {
                    // "LEC P8" above the bars for the first Ferrari, below for the second: team-mates often finish
                    // side by side (P8, P9) and their labels would run into each other.
                    String label = code + " P" + p;
                    float half = text.measureText(label) / 2, cx = Math.max(half, Math.min(getWidth() - half, x + w / 2));
                    text.setColor(MainActivity.WHITE);
                    c.drawText(label, cx, k % 2 == 0 ? top - dp(getContext(), 6) : top + h + dp(getContext(), 14), text);
                    k++;
                }
            }
            text.setColor(MainActivity.DIM);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText("P1", pad, top + h + dp(getContext(), 14), text);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText("P20", getWidth() - pad, top + h + dp(getContext(), 14), text);
            text.setTextAlign(Paint.Align.CENTER);
        }
    }

    /** "Podium NOR VER HAD  ·  Ferrari LEC P8, HAM P9" -> {LEC: 8, HAM: 9}. */
    static Map<String, Integer> ferraris(String body) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        if (body == null || !body.contains("Ferrari ")) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Z]{3}) P(\\d+)").matcher(body.substring(body.indexOf("Ferrari ")));
        while (m.find()) out.put(m.group(1), Integer.parseInt(m.group(2)));
        return out;
    }
}
