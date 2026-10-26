package com.balaji.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * The clock: a bento card. A rounded frame with a hairline holding four tiles: a
 * cream watch dial with an orange second hand, the time under a greeting, a square the colour of the sky
 * with the temperature, and a line that reads like a sentence ("WED 21 · Drizzle from 10 am"). The frame is
 * a dark tint over frosted glass; each tile is its own view, so the dial's tick redraws only the dial.
 */
final class BentoView extends ViewGroup {
    final Dial dial;
    final TimeTile time;
    final SkyTile sky;
    final LineTile line;

    private final float pad;
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF(), hair = new RectF();

    /** The frame's corner: the tiles' 14 plus the 5 of padding, so the corners nest. */
    static float corner(Context c) {
        return Ui.dp(c, Ui.R_TILE + 5f);
    }

    BentoView(Context c, String place, boolean glass) {
        super(c);
        pad = Ui.dp(c, 5f);
        framePaint.setColor(glass ? Ui.withAlpha(Ui.DOCK_FRAME, 0.62f) : Ui.DOCK_FRAME);
        hairPaint.setStyle(Paint.Style.STROKE);
        hairPaint.setStrokeWidth(Ui.dp(c, 1f));
        hairPaint.setColor(Ui.FRAME_HAIRLINE);
        int tile = glass ? Ui.withAlpha(Ui.TILE, 0.82f) : Ui.TILE;
        dial = new Dial(c, tile);
        time = new TimeTile(c, tile);
        sky = new SkyTile(c, tile);
        line = new LineTile(c, tile, place);
        addView(dial);
        addView(time);
        addView(sky);
        addView(line);
        setWillNotDraw(false);
        setFocusable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
        float square = Ui.dp(getContext(), 46f);
        float inner = getMeasuredWidth() - 2 * pad;
        exactly(dial, square, square);
        exactly(sky, square, square);
        exactly(time, inner - 2 * square - 2 * pad, square);
        exactly(line, inner, getMeasuredHeight() - 3 * pad - square);
    }

    private static void exactly(View v, float w, float h) {
        v.measure(MeasureSpec.makeMeasureSpec(Math.round(w), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(h), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // grid-template-columns: 46px 1fr 46px; rows 46px 28px; gap and padding 5.
        int p = Math.round(pad);
        dial.layout(p, p, p + dial.getMeasuredWidth(), p + dial.getMeasuredHeight());
        int tx = dial.getRight() + p;
        time.layout(tx, p, tx + time.getMeasuredWidth(), p + time.getMeasuredHeight());
        int sx = time.getRight() + p;
        sky.layout(sx, p, sx + sky.getMeasuredWidth(), p + sky.getMeasuredHeight());
        int ly = dial.getBottom() + p;
        line.layout(p, ly, p + line.getMeasuredWidth(), ly + line.getMeasuredHeight());
        frame.set(0, 0, getWidth(), getHeight());
        float half = Ui.dp(getContext(), 0.5f);
        hair.set(frame);
        hair.inset(half, half);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = corner(getContext());
        canvas.drawRoundRect(frame, r, r, framePaint);
        float h = Ui.dp(getContext(), 0.5f);
        canvas.drawRoundRect(hair, r - h, r - h, hairPaint);
    }

    /** A tile's surface: radius 14. */
    abstract static class Tile extends View {
        protected final float tileRadius;
        protected final RectF tile = new RectF();
        protected final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Tile(Context c, int color) {
            super(c);
            tileRadius = Ui.dp(c, Ui.R_TILE);
            tilePaint.setColor(color);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            tile.set(0, 0, w, h);
        }

        protected void drawTile(Canvas canvas, Paint paint) {
            canvas.drawRoundRect(tile, tileRadius, tileRadius, paint);
        }
    }

    /**
     * The watch dial: a cream face, twelve dots (bigger at the quarters), dark hour and minute hands, an
     * orange second hand and cap, drawn on a 100-unit grid at 40 dp. The second hand ticks with a
     * 160 ms ease and keeps counting past 59, so 59 to 0 doesn't spin backwards.
     */
    static final class Dial extends Tile {
        private final float unit;
        private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hourP, minuteP, secondP, cap = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float hourAngle, minuteAngle;
        private final Ui.Tween second = new Ui.Tween(0f);
        private int lastSecond = -1;

        Dial(Context c, int color) {
            super(c, color);
            unit = Ui.dp(c, 40f) / 100f;
            face.setColor(Colors.oklch(0.94, 0.018, 85));
            dot.setColor(Colors.oklch(0.58, 0.01, 85));
            int dark = Colors.oklch(0.19, 0, 0), orange = Colors.oklch(0.72, 0.18, 50);
            hourP = hand(7f, dark);
            minuteP = hand(4.5f, dark);
            secondP = hand(2.2f, orange);
            cap.setColor(orange);
        }

        private Paint hand(float width, int color) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(width * unit);
            p.setColor(color);
            return p;
        }

        void reset() {
            lastSecond = -1;
        }

        void tick(int hour, int minute, int sec) {
            hourAngle = (hour % 12) * 30f + minute * 0.5f;
            minuteAngle = minute * 6f + sec * 0.1f;
            if (lastSecond < 0) {
                second.snap(sec * 6f);
            } else if (sec != lastSecond) {
                float goal = second.target + ((sec - lastSecond + 60) % 60) * 6f;
                if (goal >= 720f) {
                    second.snap(second.value - 360f);
                    goal -= 360f;
                }
                second.to(goal, 160, Ui.TICK);
            }
            lastSecond = sec;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            drawTile(canvas, tilePaint);
            boolean moving = second.step();
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            canvas.drawCircle(cx, cy, 49 * unit, face);
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6;
                canvas.drawCircle(cx + 38 * unit * (float) Math.sin(a), cy - 38 * unit * (float) Math.cos(a),
                        (i % 3 == 0 ? 3.8f : 2.4f) * unit, dot);
            }
            drawHand(canvas, cx, cy, hourAngle, 0f, 23f, hourP);
            drawHand(canvas, cx, cy, minuteAngle, 0f, 35f, minuteP);
            drawHand(canvas, cx, cy, second.value, 11f, 38f, secondP);
            canvas.drawCircle(cx, cy, 4.2f * unit, cap);
            if (moving) postInvalidateOnAnimation();
        }

        private void drawHand(Canvas canvas, float cx, float cy, float angle, float tail, float length, Paint p) {
            canvas.save();
            canvas.rotate(angle, cx, cy);
            canvas.drawLine(cx, cy + tail * unit, cx, cy - length * unit, p);
            canvas.restore();
        }
    }

    /** The greeting (12, secondary) over the time (24 semibold, tabular) and am or pm. */
    static final class TimeTile extends Tile {
        private final TextPaint greetP, timeP, ampmP;
        private int hour = -1;
        private String greeting = "", clock = "", ampm = "";
        private float greetBase, clockBase, ampmX;

        TimeTile(Context c, int color) {
            super(c, color);
            greetP = Ui.textPaint(c, Ui.medium(c), 12f, Ui.TEXT_2, -0.01f, false);
            timeP = Ui.textPaint(c, Ui.semibold(c), 24f, Ui.TEXT, -0.03f, true);
            ampmP = Ui.textPaint(c, Ui.semibold(c), 12f, Ui.TEXT_2, 0f, false);
        }

        void setTime(int hour24, int minute) {
            String text = (hour24 % 12 == 0 ? 12 : hour24 % 12) + ":" + (minute < 10 ? "0" : "") + minute;
            if (text.equals(clock) && hour24 == hour) return;
            hour = hour24;
            clock = text;
            ampm = hour24 < 12 ? "am" : "pm";
            setContentDescription(text + " " + ampm);
            fit();
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            fit();
        }

        private void fit() {
            if (getWidth() == 0 || hour < 0) return;
            Context c = getContext();
            String[] g = greetingFor(hour);
            float room = getWidth() - Ui.dp(c, 12f);
            greeting = greetP.measureText(g[0]) <= room ? g[0] : g[1];
            float greetLine = Ui.dp(c, 12f * 1.15f), timeLine = Ui.dp(c, 24f * 1.05f);
            float top = (getHeight() - greetLine - timeLine) / 2f;
            greetBase = Ui.baselineIn(greetP, top, greetLine);
            clockBase = Ui.baselineIn(timeP, top + greetLine, timeLine);
            ampmX = Ui.dp(c, 8f) + timeP.measureText(clock) + Ui.dp(c, 2f);
        }

        static String[] greetingFor(int hour) {
            if (hour >= 5 && hour <= 11) return new String[]{"Good morning", "Morning"};
            if (hour >= 12 && hour <= 16) return new String[]{"Good afternoon", "Afternoon"};
            if (hour >= 17 && hour <= 21) return new String[]{"Good evening", "Evening"};
            return new String[]{"Good night", "Night"};
        }

        @Override
        protected void onDraw(Canvas canvas) {
            drawTile(canvas, tilePaint);
            float x = Ui.dp(getContext(), 8f);
            canvas.drawText(greeting, x, greetBase, greetP);
            canvas.drawText(clock, x, clockBase, timeP);
            canvas.drawText(ampm, ampmX, clockBase, ampmP);
        }
    }

    /** The sky now: a 150 degree gradient in its colours, the glyph top left, the temperature under it. */
    static final class SkyTile extends Tile {
        private final int glyphSize;
        private final TextPaint tempP;
        private final Paint skyP = new Paint(Paint.ANTI_ALIAS_FLAG), glyphP = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap glyph;
        private String temp = "--°";
        private Weather.Sky sky = Weather.Sky.NONE;
        private float tempBase;

        SkyTile(Context c, int color) {
            super(c, color);
            glyphSize = Ui.dpi(c, 15f);
            tempP = Ui.textPaint(c, Ui.semibold(c), 17f, Ui.TEXT, -0.02f, true);
            glyph = Ui.glyph(c, R.drawable.ph_cloud, glyphSize);
        }

        void setWeather(Weather.Reading r) {
            sky = Weather.sky(r);
            temp = r != null ? r.temp + "°" : "--°";
            glyph = Ui.glyph(getContext(), Weather.glyph(r != null ? r.icon : null), glyphSize);
            setContentDescription(r != null ? r.temp + " degrees, " + r.text : null);
            paintSky();
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            float lineH = Ui.dp(getContext(), 17f);
            tempBase = Ui.baselineIn(tempP, h - Ui.dp(getContext(), 5f) - lineH, lineH);
            paintSky();
        }

        private void paintSky() {
            if (getWidth() == 0) return;
            int[] c = colors(sky);
            float w = getWidth(), h = getHeight();
            double a = Math.toRadians(150);
            float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
            float half = (Math.abs(w * dx) + Math.abs(h * dy)) / 2f;
            skyP.setShader(new LinearGradient(w / 2 - dx * half, h / 2 - dy * half, w / 2 + dx * half, h / 2 + dy * half,
                    c[0], c[1], Shader.TileMode.CLAMP));
            tempP.setColor(c[2]);
            glyphP.setColor(c[2]);
        }

        /** bento.css's --sky-1, --sky-2 and --sky-ink for each sky. */
        private static int[] colors(Weather.Sky s) {
            int white = Colors.oklch(0.98, 0, 0);
            switch (s) {
                case SUN: return new int[]{Colors.oklch(0.89, 0.14, 92), Colors.oklch(0.78, 0.16, 60), Colors.oklch(0.27, 0.06, 55)};
                case PARTLY: return new int[]{Colors.oklch(0.85, 0.06, 225), Colors.oklch(0.70, 0.08, 240), Colors.oklch(0.24, 0.04, 245)};
                case CLOUD: return new int[]{Colors.oklch(0.68, 0.02, 250), Colors.oklch(0.54, 0.025, 255), white};
                case RAIN: return new int[]{Colors.oklch(0.62, 0.09, 228), Colors.oklch(0.46, 0.10, 245), white};
                case STORM: return new int[]{Colors.oklch(0.50, 0.12, 295), Colors.oklch(0.35, 0.10, 285), white};
                case NIGHT: return new int[]{Colors.oklch(0.43, 0.11, 278), Colors.oklch(0.27, 0.08, 272), white};
                default: return new int[]{Colors.oklch(0.36, 0, 0), Colors.oklch(0.26, 0, 0), Ui.TEXT};
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            drawTile(canvas, skyP);
            Context c = getContext();
            canvas.drawBitmap(glyph, Ui.dp(c, 7f), Ui.dp(c, 6f), glyphP);
            canvas.drawText(temp, Ui.dp(c, 7f), tempBase, tempP);
        }
    }

    /**
     * The sentence line: "TUE 20 · (glyph) Drizzle from 3 pm". The weekday in calendar red, the date and
     * the strong words white and semibold, the rest secondary; 5 apart. A long forecast loses its glyph
     * first, then steps down to 11.
     */
    static final class LineTile extends Tile {
        private final TextPaint wdayP;
        private final TextPaint[] strong = new TextPaint[2], plain = new TextPaint[2], sep = new TextPaint[2];
        private final Paint glyphP = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int glyphSize;
        private final String place;

        private static final class Run {
            final String text;
            final TextPaint paint;
            float x, base;

            Run(String text, TextPaint paint) {
                this.text = text;
                this.paint = paint;
            }
        }

        private List<Run> runs = new ArrayList<>();
        private Bitmap glyph;
        private float glyphX, glyphY;
        private String weekday = "";
        private int date;
        private Weather.Reading reading;

        LineTile(Context c, int color, String place) {
            super(c, color);
            this.place = place;
            wdayP = Ui.textPaint(c, Ui.bold(c), 12f, Ui.CALENDAR_RED, 0.04f, false);
            for (int i = 0; i < 2; i++) {
                float size = i == 0 ? 12f : 11f;
                strong[i] = Ui.textPaint(c, Ui.semibold(c), size, Ui.TEXT, 0.012f, false);
                plain[i] = Ui.textPaint(c, Ui.medium(c), size, Ui.TEXT_2, 0.012f, false);
                sep[i] = Ui.textPaint(c, Ui.medium(c), size, Ui.TEXT_2, 0.012f, false);
                sep[i].setAlpha(Math.round(255 * 0.6f));
            }
            glyphP.setColor(Ui.TEXT);
            glyphSize = Ui.dpi(c, 13f);
        }

        void setLine(String weekday, int date, Weather.Reading reading) {
            this.weekday = weekday;
            this.date = date;
            this.reading = reading;
            layoutRuns();
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            layoutRuns();
        }

        private void layoutRuns() {
            if (getWidth() == 0 || weekday.isEmpty()) return;
            Weather.Next next = reading != null ? Weather.next(reading) : null;
            Bitmap icon = next != null ? Ui.glyph(getContext(), Weather.glyph(next.icon), glyphSize) : null;
            // As it reads, then without the glyph, then a size down (clipped if even that's too long).
            int[] tight = {0, 0, 1};
            Bitmap[] glyphs = {icon, null, null};
            for (int k = 0; k < 3; k++) {
                if (k == 1 && icon == null) continue;
                List<Run> built = build(next, tight[k]);
                if (place(built, glyphs[k]) || k == 2) {
                    runs = built;
                    glyph = glyphs[k];
                    StringBuilder sb = new StringBuilder();
                    for (Run r : built) sb.append(r.text).append(' ');
                    setContentDescription(sb.toString().trim());
                    return;
                }
            }
        }

        private List<Run> build(Weather.Next next, int t) {
            List<Run> list = new ArrayList<>();
            list.add(new Run(weekday, wdayP));
            list.add(new Run(String.valueOf(date), strong[t]));
            list.add(new Run("·", sep[t]));
            if (next == null) list.add(new Run(place, plain[t]));
            else for (int i = 0; i < next.words.length; i++) list.add(new Run(next.words[i], next.strong[i] ? strong[t] : plain[t]));
            return list;
        }

        private boolean place(List<Run> list, Bitmap icon) {
            Context c = getContext();
            float gap = Ui.dp(c, 5f), x = Ui.dp(c, 10f);
            for (int i = 0; i < list.size(); i++) {
                Run run = list.get(i);
                float size = run.paint.getTextSize();
                run.x = x;
                run.base = Ui.baselineIn(run.paint, (getHeight() - size) / 2f, size);
                x += run.paint.measureText(run.text) + gap;
                if (i == 2 && icon != null) {
                    glyphX = x;
                    glyphY = (getHeight() - glyphSize) / 2f;
                    x += glyphSize + gap;
                }
            }
            return x - gap <= getWidth() - Ui.dp(c, 10f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            drawTile(canvas, tilePaint);
            canvas.save();
            canvas.clipRect(0, 0, getWidth() - Ui.dp(getContext(), 10f), getHeight());
            for (int i = 0; i < runs.size(); i++) {
                Run r = runs.get(i);
                canvas.drawText(r.text, r.x, r.base, r.paint);
            }
            if (glyph != null) canvas.drawBitmap(glyph, glyphX, glyphY, glyphP);
            canvas.restore();
        }
    }
}
