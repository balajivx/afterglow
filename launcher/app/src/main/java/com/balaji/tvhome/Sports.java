package com.balaji.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The sports card, top right: one card with three faces, Arsenal's next match, India's next cricket match
 * and the next F1 race. Select it (Up from the shelf) and Left or Right flips it to the next face; three
 * dots say which one is showing. Each face shows when (in this TV's time) and a countdown that turns to
 * "Live" while it's on.
 *
 * Arsenal is a night match under floodlights (near black, a red glow, the pitch's centre circle in faint
 * chalk, the crest in it); India is a night game in blue (a navy sky, a saffron glow, the 22-yard strip
 * with its creases and stumps); F1 is carbon black with a chequered edge and a red speed stripe.
 *
 * Data: TheSportsDB (free test key "123") for Arsenal, India and the crests; Jolpica-F1 (the open Ergast
 * successor, no key) for the race. Cached on disk; refreshed every 3 hours, and as soon as one is over.
 */
final class Sports {
    private Sports() {
    }

    static final String ARSENAL_ID = "133604", INDIA_ID = "137143";
    static final long EVERY_MS = 3 * 3600 * 1000L;
    private static final long HOUR = 3600 * 1000L;

    /** A fixture between two teams (football or cricket). */
    static final class Match {
        String home, away, competition, homeBadge, awayBadge, homeBadgeUrl, awayBadgeUrl;
        boolean ourHome;
        long start;
    }

    static final class Race {
        String name;
        int round;
        long start;
    }

    // ---- Data (blocking: off the main thread)

    static final class Source {
        private final File file, badges;

        Source(File cacheDir) {
            file = new File(cacheDir, "home-sports.json");
            badges = new File(cacheDir, "home-badges");
        }

        long age(long now) {
            if (!file.isFile()) return Long.MAX_VALUE;
            long a = now - file.lastModified();
            return a < 0 ? Long.MAX_VALUE : a;
        }

        JSONObject cached() {
            try {
                return file.isFile() ? new JSONObject(Net.readText(file)) : null;
            } catch (IOException | JSONException e) {
                return null;
            }
        }

        /** Asks each service; keeps whichever answered (the others stay as cached). */
        JSONObject fetch() throws IOException {
            JSONObject old = cached();
            JSONObject out = new JSONObject();
            boolean any = false;
            try {
                for (String[] team : new String[][]{{"match", ARSENAL_ID}, {"cricket", INDIA_ID}}) {
                    try {
                        JSONArray ev = new JSONObject(Net.getString(
                                "https://www.thesportsdb.com/api/v1/json/123/eventsnext.php?id=" + team[1])).optJSONArray("events");
                        if (ev != null && ev.length() > 0) {
                            out.put(team[0], upcoming(ev.getJSONObject(0), team[0], team[1]));
                            any = true;
                        }
                    } catch (IOException | JSONException e) {
                        if (old != null && old.has(team[0])) out.put(team[0], old.get(team[0]));
                    }
                }
                try {
                    JSONObject f1 = new JSONObject(Net.getString("https://api.jolpi.ca/ergast/f1/current/next.json"));
                    JSONArray races = f1.getJSONObject("MRData").getJSONObject("RaceTable").optJSONArray("Races");
                    if (races != null && races.length() > 0) {
                        out.put("race", races.getJSONObject(0));
                        any = true;
                    }
                } catch (IOException | JSONException e) {
                    if (old != null && old.has("race")) out.put("race", old.get("race"));
                }
            } catch (JSONException e) {
                throw new IOException(e);
            }
            if (!any) throw new IOException("No sports service answered");
            Net.writeAtomically(file, out.toString());
            return out;
        }

        /**
         * [e], the team's "next" event, unless it should be over by now: TheSportsDB can keep a finished
         * match as next for hours (no score, no status), and the free key gives only that one. Then the
         * league's next fixture with [teamId] in it, if there is one (e.g. the series' next T20I).
         */
        private static JSONObject upcoming(JSONObject e, String key, String teamId) {
            long length = key.equals("cricket") ? cricketLength(e.optString("strLeague")) : 2 * HOUR;
            if (start(e) + length > System.currentTimeMillis()) return e;
            try {
                JSONArray ev = new JSONObject(Net.getString("https://www.thesportsdb.com/api/v1/json/123/eventsnextleague.php?id="
                        + e.optString("idLeague"))).optJSONArray("events");
                for (int i = 0; ev != null && i < ev.length(); i++) {
                    JSONObject n = ev.getJSONObject(i);
                    boolean ours = teamId.equals(n.optString("idHomeTeam")) || teamId.equals(n.optString("idAwayTeam"));
                    if (ours && start(n) + length > System.currentTimeMillis()) return n;
                }
            } catch (IOException | JSONException ignored) {
                // keep the finished one; the card stops counting down for it
            }
            return e;
        }

        /** A team's crest at [px] wide: from disk, else downloaded (TheSportsDB's small size) and kept. */
        Bitmap badge(String teamId, String url, int px, boolean online) {
            if (teamId == null || teamId.isEmpty()) return null;
            badges.mkdirs();
            File kept = new File(badges, teamId + ".png");
            if (!kept.isFile() && online && url != null && url.startsWith("http")) {
                try {
                    Net.download(url + "/small", kept);
                } catch (IOException e) {
                    try {
                        Net.download(url, kept);
                    } catch (IOException ignored) {
                    }
                }
            }
            if (!kept.isFile()) return null;
            Bitmap b = BitmapFactory.decodeFile(kept.getPath());
            if (b == null) return null;
            Bitmap s = Bitmap.createScaledBitmap(b, px, px * b.getHeight() / Math.max(1, b.getWidth()), true);
            if (s != b) b.recycle();
            return s;
        }
    }

    /** The fixture under [key] ("match" or "cricket"), seen from [ourId]'s side. */
    static Match match(JSONObject data, String key, String ourId) {
        JSONObject e = data == null ? null : data.optJSONObject(key);
        if (e == null) return null;
        Match m = new Match();
        m.home = e.optString("strHomeTeam").replace(" Cricket", "");
        m.away = e.optString("strAwayTeam").replace(" Cricket", "");
        m.ourHome = ourId.equals(e.optString("idHomeTeam"));
        m.competition = e.optString("strLeague");
        m.homeBadge = e.optString("idHomeTeam");
        m.awayBadge = e.optString("idAwayTeam");
        m.homeBadgeUrl = e.optString("strHomeTeamBadge");
        m.awayBadgeUrl = e.optString("strAwayTeamBadge");
        m.start = start(e);
        return m.start > 0 ? m : null;
    }

    /** When TheSportsDB's event [e] starts (0 if it doesn't say). */
    private static long start(JSONObject e) {
        String stamp = e.optString("strTimestamp");
        if (stamp.isEmpty()) stamp = e.optString("dateEvent") + "T" + e.optString("strTime", "00:00:00");
        return parseUtc(stamp.replace("+00:00", "").replace("Z", ""));
    }

    static Race race(JSONObject data) {
        JSONObject r = data == null ? null : data.optJSONObject("race");
        if (r == null) return null;
        Race x = new Race();
        x.name = r.optString("raceName").replace("Grand Prix", "GP");
        x.round = r.optInt("round");
        x.start = parseUtc(r.optString("date") + "T" + r.optString("time", "12:00:00Z").replace("Z", ""));
        return x.start > 0 ? x : null;
    }

    private static long parseUtc(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s).getTime();
        } catch (ParseException | RuntimeException e) {
            return 0;
        }
    }

    /** "Wed 1:30 am" within the week (the countdown gives the rest), "Wed 4 Nov" further out; this TV's time. */
    static String when(long t) {
        boolean soon = t - System.currentTimeMillis() < 6 * 24 * HOUR;
        SimpleDateFormat f = new SimpleDateFormat(soon ? "EEE h:mm a" : "EEE d MMM", Locale.UK);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(t).replace("AM", "am").replace("PM", "pm");
    }

    /** "in 2d 5h", "in 5h 20m", "in 45m", "Live" (for [length] after the start), or "" once it's over. */
    static String countdown(long start, long now, long length) {
        long d = start - now;
        if (d <= 0) return -d < length ? "Live" : "";
        long m = d / 60000, h = m / 60, days = h / 24;
        if (days > 0) return "in " + days + "d " + (h % 24) + "h";
        if (h > 0) return "in " + h + "h " + (m % 60) + "m";
        return "in " + Math.max(1, m) + "m";
    }

    /** How long a cricket match runs, from its format: a Test five days, an ODI nine hours, a T20 four. */
    static long cricketLength(String competition) {
        String c = competition.toLowerCase(Locale.UK);
        if (c.contains("test")) return 5 * 24 * HOUR;
        if (c.contains("one day") || c.contains("odi")) return 9 * HOUR;
        return 4 * HOUR;
    }

    /** "Twenty20 International Series" to "T20I", and so on. */
    static String cricketFormat(String competition) {
        String c = competition.toLowerCase(Locale.UK);
        if (c.contains("test")) return "TEST";
        if (c.contains("one day")) return "ODI";
        if (c.contains("twenty20") || c.contains("t20")) return c.contains("world cup") ? "T20 WORLD CUP" : "T20I";
        return competition.toUpperCase(Locale.UK);
    }

    // ---- The card

    /**
     * One card, three faces. It takes focus like the posters (a fine ring, nothing grows); Left and Right
     * flip it, a half turn out and a half turn in (150 ms each), and Down goes back to the shelf.
     */
    static final class CardView extends ViewGroup {
        final MatchTile arsenal, india;
        final RaceTile f1;
        private final Tile[] faces;
        private int face;
        private final float pad, ringSpace, ringOffset;
        private final Paint frameP = new Paint(Paint.ANTI_ALIAS_FLAG), hairP = new Paint(Paint.ANTI_ALIAS_FLAG),
                ringP = new Paint(Paint.ANTI_ALIAS_FLAG), dotP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF frame = new RectF(), hair = new RectF(), ring = new RectF();
        private final Ui.Tween focus = new Ui.Tween(0f);
        private boolean flipping;
        /** True once the viewer has flipped it; until then the card shows whatever comes first. */
        boolean flipped;
        /** Where Down goes. */
        java.util.function.Supplier<View> below, above;
        /** Told when the face changes while focused, with its colour for the glow. */
        java.util.function.IntConsumer onFace;

        CardView(Context c, boolean glass) {
            super(c);
            pad = Ui.dp(c, 5f);
            ringSpace = Ui.dp(c, 3f);
            ringOffset = Ui.dp(c, 2.5f);
            frameP.setColor(glass ? Ui.withAlpha(Ui.DOCK_FRAME, 0.62f) : Ui.DOCK_FRAME);
            hairP.setStyle(Paint.Style.STROKE);
            hairP.setStrokeWidth(Ui.dp(c, 1f));
            hairP.setColor(Ui.FRAME_HAIRLINE);
            ringP.setStyle(Paint.Style.STROKE);
            ringP.setStrokeWidth(Ui.dp(c, 1f));
            arsenal = new MatchTile(c, false);
            india = new MatchTile(c, true);
            f1 = new RaceTile(c);
            faces = new Tile[]{arsenal, india, f1};
            for (Tile t : faces) addView(t);
            show(0);
            setWillNotDraw(false);
            setFocusable(true);
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            setDefaultFocusHighlightEnabled(false);
            setCameraDistance(Ui.dp(c, 1200f) * c.getResources().getDisplayMetrics().density);
        }

        int ringSpacePx() {
            return Math.round(ringSpace);
        }

        /** The face that's up: 0 Arsenal, 1 India, 2 F1. */
        int face() {
            return face;
        }

        void show(int i) {
            face = (i + faces.length) % faces.length;
            for (int k = 0; k < faces.length; k++) faces[k].setVisibility(k == face ? VISIBLE : INVISIBLE);
            setContentDescription(faces[face].getContentDescription());
            invalidate();
        }

        int faceColor() {
            return faces[face].accent;
        }

        /** Flips to the next face ([dir] +1, Right) or the one before (-1, Left). */
        void flip(int dir) {
            if (flipping) return;
            flipping = true;
            flipped = true;
            animate().rotationY(-90f * dir).setDuration(150).setInterpolator(new AccelerateInterpolator())
                    .withEndAction(() -> {
                        show(face + dir);
                        if (onFace != null && isFocused()) onFace.accept(faceColor());
                        setRotationY(90f * dir);
                        animate().rotationY(0f).setDuration(150).setInterpolator(new DecelerateInterpolator())
                                .withEndAction(() -> flipping = false).start();
                    }).start();
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                flip(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                flip(-1);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        public View focusSearch(View focused, int direction) {
            if (focused == this) {
                if (direction == FOCUS_DOWN && below != null && below.get() != null) return below.get();
                if (direction == FOCUS_UP && above != null && above.get() != null) return above.get();
                return this;
            }
            return super.focusSearch(focused, direction);
        }

        @Override
        protected void onFocusChanged(boolean gain, int direction, Rect previous) {
            super.onFocusChanged(gain, direction, previous);
            focus.to(gain ? 1f : 0f, 200);
            invalidate();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
            int w = Math.round(getMeasuredWidth() - 2 * ringSpace - 2 * pad), h = Math.round(getMeasuredHeight() - 2 * ringSpace - 2 * pad);
            for (Tile t : faces) t.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int p = Math.round(ringSpace + pad);
            for (Tile f : faces) f.layout(p, p, p + f.getMeasuredWidth(), p + f.getMeasuredHeight());
            frame.set(ringSpace, ringSpace, getWidth() - ringSpace, getHeight() - ringSpace);
            float half = Ui.dp(getContext(), 0.5f);
            hair.set(frame);
            hair.inset(half, half);
            ring.set(frame);
            ring.inset(-ringOffset, -ringOffset);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            boolean moving = focus.step();
            float r = BentoView.corner(getContext()), h = Ui.dp(getContext(), 0.5f);
            if (focus.value > 0f) {
                ringP.setColor(Ui.withAlpha(Ui.FOCUS_RING, focus.value));
                canvas.drawRoundRect(ring, r + ringOffset, r + ringOffset, ringP);
            }
            canvas.drawRoundRect(frame, r, r, frameP);
            canvas.drawRoundRect(hair, r - h, r - h, hairP);
            if (moving) postInvalidateOnAnimation();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            // Which face: three dots, top right of the tile.
            Context c = getContext();
            Tile t = faces[face];
            float d = Ui.dp(c, 2.5f), gap = Ui.dp(c, 4f);
            float y = t.getTop() + Ui.dp(c, 12.5f);
            float x = t.getRight() - Ui.dp(c, 9f) - d;
            for (int k = faces.length - 1; k >= 0; k--) {
                dotP.setColor(k == face ? 0xFFFFFFFF : 0x59FFFFFF);
                canvas.drawCircle(x, y, k == face ? d : d * 0.8f, dotP);
                x -= 2 * d + gap;
            }
        }
    }

    /** A face: three lines (a small caps label, the main line, when) and a countdown pill. */
    abstract static class Tile extends View {
        protected final RectF tile = new RectF();
        protected final float radius;
        protected final TextPaint labelP, mainP, whenP, pillP;
        protected final Paint pillBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        protected String label = "", main = "", when = "", pill = "";
        private final Path clip = new Path();
        /** The face's colour, for the glow while the card is focused. */
        int accent = Colors.FALLBACK;

        Tile(Context c) {
            super(c);
            radius = Ui.dp(c, Ui.R_TILE);
            labelP = Ui.textPaint(c, Ui.bold(c), 9.5f, 0xE6FFFFFF, 0.08f, false);
            mainP = Ui.textPaint(c, Ui.semibold(c), 13f, 0xFFFFFFFF, -0.01f, false);
            whenP = Ui.textPaint(c, Ui.medium(c), 11f, 0xD9FFFFFF, 0f, true);
            pillP = Ui.textPaint(c, Ui.bold(c), 10f, 0xFFFFFFFF, 0.02f, true);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            tile.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(tile, radius, radius, Path.Direction.CW);
            onSize(w, h);
        }

        protected void onSize(int w, int h) {
        }

        protected abstract void drawSurface(Canvas canvas);

        protected float mainIndent() {
            return 0;
        }

        protected void drawMainExtras(Canvas canvas, float baseline) {
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            canvas.save();
            canvas.clipPath(clip);
            drawSurface(canvas);
            canvas.restore();
            float x = Ui.dp(c, 9f), w = getWidth() - 2 * x, h = getHeight();
            float dots = Ui.dp(c, 30f); // the card's face dots sit at the label's right
            canvas.drawText(TextUtils.ellipsize(label, labelP, w - dots, TextUtils.TruncateAt.END).toString(), x, Ui.dp(c, 16f), labelP);
            float mainBase = h / 2f + Ui.dp(c, 5f);
            float indent = mainIndent();
            canvas.drawText(TextUtils.ellipsize(main, mainP, w - indent, TextUtils.TruncateAt.END).toString(), x + indent, mainBase, mainP);
            drawMainExtras(canvas, mainBase);
            float bottom = h - Ui.dp(c, 9f);
            float pillW = pill.isEmpty() ? 0 : pillP.measureText(pill) + Ui.dp(c, 12f);
            canvas.drawText(TextUtils.ellipsize(when, whenP, w - pillW - Ui.dp(c, 4f), TextUtils.TruncateAt.END).toString(), x, bottom, whenP);
            if (!pill.isEmpty()) {
                float ph = Ui.dp(c, 16f), right = getWidth() - x;
                RectF pr = new RectF(right - pillW, bottom - ph + Ui.dp(c, 4f), right, bottom + Ui.dp(c, 4f));
                canvas.drawRoundRect(pr, ph / 2, ph / 2, pillBg);
                canvas.drawText(pill, pr.left + Ui.dp(c, 6f), Ui.baselineIn(pillP, pr.top, ph), pillP);
            }
        }
    }

    /**
     * A team's next fixture, as a night game. Arsenal: near black with a deep red tint, a red glow from the
     * bottom left, a floodlight wash, the pitch's halfway line and centre circle in faint chalk off the right
     * edge with the crest in it; the label in the club's gold. India (cricket): a navy night, a saffron glow,
     * a floodlight wash, and the 22-yard strip down the right edge with its creases and stumps; the label in
     * saffron, the countdown in India blue.
     */
    static final class MatchTile extends Tile {
        private final boolean cricket;
        private final Paint surfaceP = new Paint(Paint.DITHER_FLAG), glowP = new Paint(Paint.DITHER_FLAG),
                lightP = new Paint(Paint.DITHER_FLAG), chalkP = new Paint(Paint.ANTI_ALIAS_FLAG),
                stripP = new Paint(Paint.DITHER_FLAG), stumpP = new Paint(Paint.ANTI_ALIAS_FLAG),
                markP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG),
                crestP = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private Bitmap ours, theirs;
        private final int crest;
        private float circleX, circleY, circleR;
        private final RectF strip = new RectF();

        MatchTile(Context c, boolean cricket) {
            super(c);
            this.cricket = cricket;
            crest = Ui.dpi(c, 18f);
            chalkP.setStyle(Paint.Style.STROKE);
            chalkP.setStrokeWidth(Ui.dp(c, 1f));
            chalkP.setColor(0x24FFFFFF);
            stumpP.setColor(0x66FFFFFF);
            markP.setAlpha(Math.round(255 * 0.20f));
            if (cricket) {
                pillBg.setColor(0xFF1F6FEB); // India blue
                labelP.setColor(0xFFFF9933); // saffron
                accent = 0xFF1F6FEB;
                label = "INDIA · CRICKET";
            } else {
                pillBg.setColor(0xFFDB0007);
                labelP.setColor(0xFFC9A85C); // Arsenal gold
                accent = 0xFFEF0107;
                label = "ARSENAL";
            }
            main = "Next match";
        }

        @Override
        protected void onSize(int w, int h) {
            if (cricket) {
                surfaceP.setShader(new LinearGradient(0, 0, w, h, 0xFF12307A, 0xFF070C1E, Shader.TileMode.CLAMP));
                glowP.setShader(new RadialGradient(0, h, w * 0.6f, 0x2EFF8A1F, 0x00FF8A1F, Shader.TileMode.CLAMP));
                stripP.setShader(new LinearGradient(0, 0, 0, h, 0x5CC9A36B, 0x26C9A36B, Shader.TileMode.CLAMP));
                float sw = h * 0.30f, sx = w - sw * 0.9f;
                strip.set(sx, -2, sx + sw, h + 2);
            } else {
                surfaceP.setShader(new LinearGradient(0, 0, w, h, 0xFF2A0B10, 0xFF0B0B0E, Shader.TileMode.CLAMP));
                glowP.setShader(new RadialGradient(0, h, w * 0.85f, 0x66C8000A, 0x00C8000A, Shader.TileMode.CLAMP));
                circleX = w * 0.93f; // half off the edge: the pitch carries on past the tile
                circleY = h * 0.5f;
                circleR = h * 0.36f;
            }
            lightP.setShader(new RadialGradient(w, 0, w * 0.75f, 0x1CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        }

        void set(Match m, Bitmap oursCrest, Bitmap theirsCrest, long now) {
            ours = oursCrest;
            theirs = theirsCrest;
            if (m == null) {
                label = cricket ? "INDIA · CRICKET" : "ARSENAL";
                main = "No match listed";
                when = "";
                pill = "";
            } else {
                String side = m.ourHome ? "HOME" : "AWAY";
                if (cricket) {
                    label = cricketFormat(m.competition) + " · " + side;
                    pill = countdown(m.start, now, cricketLength(m.competition));
                } else {
                    label = m.competition.replace("English ", "").replace("UEFA ", "").toUpperCase(Locale.UK) + " · " + side;
                    pill = countdown(m.start, now, 2 * HOUR);
                }
                main = (m.ourHome ? m.away : m.home).replaceAll(" (Town|FC|AFC)$", "");
                when = when(m.start);
            }
            setContentDescription((cricket ? "India: " : "Arsenal: ") + label + ", " + main + ", " + when + " " + pill);
            invalidate();
        }

        @Override
        protected float mainIndent() {
            return ours == null && theirs == null ? 0 : 2 * crest + Ui.dp(getContext(), 18f);
        }

        @Override
        protected void drawSurface(Canvas canvas) {
            canvas.drawRect(tile, surfaceP);
            canvas.drawRect(tile, glowP);
            canvas.drawRect(tile, lightP);
            if (cricket) {
                // The 22-yard strip from above: creases near each end, three stumps at each.
                canvas.drawRect(strip, stripP);
                float cx = strip.centerX(), h = getHeight();
                for (float y : new float[]{h * 0.16f, h * 0.84f}) {
                    canvas.drawLine(strip.left + 2, y, strip.right - 2, y, chalkP);
                    for (int k = -1; k <= 1; k++) canvas.drawCircle(cx + k * Ui.dp(getContext(), 2.5f), y, Ui.dp(getContext(), 1f), stumpP);
                }
                canvas.drawLine(strip.left + 4, h * 0.26f, strip.right - 4, h * 0.26f, chalkP);
                canvas.drawLine(strip.left + 4, h * 0.74f, strip.right - 4, h * 0.74f, chalkP);
            } else {
                canvas.drawLine(circleX, 0, circleX, getHeight(), chalkP);
                canvas.drawCircle(circleX, circleY, circleR, chalkP);
                if (ours != null) {
                    float s = circleR * 1.15f;
                    canvas.drawBitmap(ours, null, new RectF(circleX - s / 2, circleY - s / 2, circleX + s / 2, circleY + s / 2), markP);
                }
            }
        }

        @Override
        protected void drawMainExtras(Canvas canvas, float baseline) {
            if (ours == null && theirs == null) return;
            Context c = getContext();
            float x = Ui.dp(c, 9f), top = baseline - Ui.dp(c, 4.5f) - crest / 2f;
            if (ours != null) canvas.drawBitmap(ours, null, fit(ours, x, top), crestP);
            float vx = x + crest + Ui.dp(c, 3f);
            whenP.setAlpha(200);
            canvas.drawText("v", vx, baseline - Ui.dp(c, 1f), whenP);
            whenP.setAlpha(217);
            float ox = vx + Ui.dp(c, 9f);
            if (theirs != null) canvas.drawBitmap(theirs, null, fit(theirs, ox, top), crestP);
        }

        /** A crest in a [crest]-sized square at [x], [y], keeping its shape. */
        private RectF fit(Bitmap b, float x, float y) {
            float s = Math.min(crest / (float) b.getWidth(), crest / (float) b.getHeight());
            float w = b.getWidth() * s, h = b.getHeight() * s;
            return new RectF(x + (crest - w) / 2, y + (crest - h) / 2, x + (crest + w) / 2, y + (crest + h) / 2);
        }
    }

    /** F1: carbon black, a chequered edge on the right fading in, and a red speed stripe. */
    static final class RaceTile extends Tile {
        private final Paint surfaceP = new Paint(Paint.DITHER_FLAG), checkP = new Paint(), stripeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path stripe = new Path();

        RaceTile(Context c) {
            super(c);
            pillBg.setColor(0xFFE10600);
            labelP.setColor(0xFFFF2A1F);
            accent = 0xFFE10600;
            label = "FORMULA 1";
            main = "Next race";
        }

        @Override
        protected void onSize(int w, int h) {
            surfaceP.setShader(new LinearGradient(0, 0, w, h, 0xFF26272B, 0xFF0C0C0E, Shader.TileMode.CLAMP));
            stripeP.setShader(new LinearGradient(0, 0, w, 0, 0x00E10600, 0xCCE10600, Shader.TileMode.CLAMP));
            float t = Ui.dp(getContext(), 3f);
            stripe.reset();
            stripe.moveTo(w * 0.25f, h * 0.40f);
            stripe.lineTo(w, h * 0.22f);
            stripe.lineTo(w, h * 0.22f + t);
            stripe.lineTo(w * 0.25f, h * 0.40f + t * 0.4f);
            stripe.close();
        }

        void set(Race r, long now) {
            if (r == null) {
                label = "FORMULA 1";
                main = "No race listed";
                when = "";
                pill = "";
            } else {
                label = "FORMULA 1 · ROUND " + r.round;
                main = r.name;
                when = when(r.start);
                pill = countdown(r.start, now, 2 * HOUR);
            }
            setContentDescription(label + ", " + main + ", " + when + " " + pill);
            invalidate();
        }

        @Override
        protected void drawSurface(Canvas canvas) {
            canvas.drawRect(tile, surfaceP);
            canvas.drawPath(stripe, stripeP);
            float sq = Ui.dp(getContext(), 5f);
            int cols = 3;
            for (int col = 0; col < cols; col++) {
                float x = getWidth() - (cols - col) * sq;
                float a = 0.10f + 0.10f * col;
                for (int row = 0; row * sq < getHeight(); row++) {
                    if ((row + col) % 2 != 0) continue;
                    checkP.setColor(Ui.withAlpha(0xFFFFFFFF, a));
                    canvas.drawRect(x, row * sq, x + sq, row * sq + sq, checkP);
                }
            }
        }
    }
}
