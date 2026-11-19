package com.balaji.watchsports;

import android.app.Activity;
import android.content.ComponentName;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

import java.util.List;

/**
 * The full picture on the wrist: India cricket, Arsenal, Formula 1, each with what's live, next and last, in
 * the watch face's colours. Built in code; it reloads every time it's opened, and nudges the watch-face
 * widget to show the same. Tapping a card opens its details (DetailActivity).
 */
public class MainActivity extends Activity {
    static final int RED = 0xFFDB0007, YELLOW = 0xFFFFD600, WHITE = 0xFFFFFFFF, DIM = 0xFFA8A8A8, CARD = 0xFF1C1C1E;
    static final int INDIA_BLUE = 0xFF2F6FEB;
    private LinearLayout list;
    private ScrollView scroll;
    private final android.os.Handler tick = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable ticker;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF000000);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int side = dp(22);
        list.setPadding(side, dp(34), side, dp(48)); // a round screen: room at the top and bottom
        scroll.addView(list);
        setContentView(scroll);
        list.addView(text("Loading…", 15, DIM, false, Gravity.CENTER));
    }

    /** The crown scrolls the list, as in the watch's own apps. */
    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        return crown(this, scroll, e) || super.dispatchGenericMotionEvent(e);
    }

    static boolean crown(Activity a, ScrollView scroll, android.view.MotionEvent e) {
        if (scroll == null || e.getAction() != android.view.MotionEvent.ACTION_SCROLL
                || !e.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER)) return false;
        float d = -e.getAxisValue(android.view.MotionEvent.AXIS_SCROLL)
                * android.view.ViewConfiguration.get(a).getScaledVerticalScrollFactor();
        scroll.smoothScrollBy(0, Math.round(d));
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ticker != null) tick.removeCallbacks(ticker); // nothing runs once the app is left
    }

    /** The time, small, at the top of the round screen (it scrolls away with the list). */
    static TextView clock(android.content.Context c) {
        android.widget.TextClock t = new android.widget.TextClock(c);
        t.setFormat24Hour("HH:mm");
        t.setFormat12Hour("h:mm");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(DIM);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, dp(c, 6));
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        new Thread(() -> {
            List<Feed.Item> items = Feed.load(getCacheDir(), true);
            runOnUiThread(() -> show(items));
            // Trigger complication refresh with fresh data
            // Everything was just fetched and cached: the widget shows it without going online again.
            long now = System.currentTimeMillis();
            getSharedPreferences("fetch", MODE_PRIVATE).edit().putLong("appAt", now).putLong("at", now).apply();
            ComplicationDataSourceUpdateRequester.create(this, new ComponentName(this, NextUpComplication.class)).requestUpdateAll();
        }).start();
    }

    private void show(List<Feed.Item> items) {
        list.removeAllViews();
        if (ticker != null) tick.removeCallbacks(ticker);
        list.addView(clock(this));
        if (items.isEmpty()) {
            list.addView(text("Couldn't reach the scores. Is the phone or Wi-Fi connected?", 14, DIM, false, Gravity.CENTER));
            return;
        }

        Feed.Item h = Feed.headline(items, System.currentTimeMillis());
        if (h != null) hero(h);

        // Section 1: My Favorites
        mainHeader("★  MY FAVOURITES", RED);
        section("India cricket", Feed.CRICKET, RED, items);
        section("Arsenal", Feed.FOOTBALL, RED, items);
        section("Formula 1", Feed.F1, YELLOW, items);

        // Section 2: Other Scores
        mainHeader("OTHER SCORES", WHITE);
        otherSection(items);
    }

    // ---- The hero: the one thing that matters now, big, in its team's colours

    /** India blue for cricket, Arsenal red, Ferrari yellow for F1. */
    static int accent(int sport) {
        return sport == Feed.CRICKET ? INDIA_BLUE : sport == Feed.F1 ? YELLOW : RED;
    }

    /** A team's colour for its badge: the ones followed in theirs, everyone else in grey. */
    private static int teamColour(String abbr) {
        switch (abbr) {
            case "IND": return INDIA_BLUE;
            case "ARS": return RED;
            case "NZ": case "NEW": return 0xFF3A3A3C;
            default: return 0xFF48484A;
        }
    }

    /** "Newcastle United" -> "NEW", "Arsenal" -> "ARS", "NZ" stays. */
    private static String abbr(String team) {
        String t = team.trim();
        if (t.contains("Arsenal")) return "ARS";
        if (t.length() <= 4 && t.equals(t.toUpperCase(java.util.Locale.UK))) return t;
        String letters = t.replaceAll("[^A-Za-z]", "");
        return letters.substring(0, Math.min(3, letters.length())).toUpperCase(java.util.Locale.UK);
    }

    private void hero(Feed.Item h) {
        int accent = accent(h.sport);
        long now = System.currentTimeMillis();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(accent & 0x00FFFFFF) | 0x66000000, CARD});
        bg.setCornerRadius(dp(22));
        if (h.live) bg.setStroke(dp(2), accent);
        card.setBackground(bg);

        String sport = h.sport == Feed.CRICKET ? "INDIA CRICKET" : h.sport == Feed.FOOTBALL ? "ARSENAL" : "FORMULA 1";
        String state = h.live ? (h.resume > 0 ? "STUMPS" : "●  LIVE") : h.past ? "RESULT" : "NEXT UP";
        card.addView(text(state + "  ·  " + sport, 11, h.live && h.resume == 0 ? accent : DIM, true, Gravity.CENTER));

        // Badges: "NZ  v  IND" as two coloured discs (F1: the race name only)
        String[] sides = h.head.split(" v | [0-9]+–[0-9]+ ");
        if (h.sport != Feed.F1 && sides.length == 2) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            row.setPadding(0, dp(8), 0, dp(4));
            row.addView(badge(abbr(sides[0])));
            row.addView(text("  v  ", 13, DIM, true, Gravity.CENTER));
            row.addView(badge(abbr(sides[1])));
            card.addView(row);
        } else {
            TextView t = text(h.head, 15, WHITE, true, Gravity.CENTER);
            t.setPadding(0, dp(6), 0, dp(2));
            card.addView(t);
        }

        // The big line: the score in play, the countdown to what's next, or the result
        String big, small;
        if (h.live && h.sport == Feed.CRICKET && h.body != null && h.body.matches(".*\\d.*")) {
            String first = h.body.split("  ·  ")[0];                 // "NZ 243/5 (82 ov)"
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\S+)\\s+([0-9/&\\s]+?)\\s*(\\((.*)\\))?$").matcher(first);
            if (m.find()) {
                big = m.group(2).trim();
                small = m.group(1) + (m.group(4) != null ? "  ·  " + m.group(4) : "");
            } else {
                big = h.shortText;
                small = first;
            }
            String rest = h.body.contains("  ·  ") ? h.body.substring(h.body.indexOf("  ·  ") + 5) : "";
            if (!rest.isEmpty()) small += "\n" + rest;
            if (h.resume > now) small += "\nPlay resumes in " + until(h.resume, now);
        } else if (!h.live && !h.past && h.time > now) {
            big = until(h.time, now);
            small = h.body;
        } else {
            big = h.shortText;
            small = h.body;
        }
        TextView bigView = text(big, h.sport == Feed.CRICKET && h.live ? 38 : 30, WHITE, true, Gravity.CENTER);
        bigView.setIncludeFontPadding(false);
        card.addView(bigView);
        TextView smallView = text(small, 12, h.live ? YELLOW : DIM, false, Gravity.CENTER);
        smallView.setPadding(0, dp(2), 0, 0);
        card.addView(smallView);

        // A countdown keeps itself current while it's on screen (and only then)
        if (!h.live && !h.past && h.time > now) {
            ticker = () -> {
                long t = System.currentTimeMillis();
                bigView.setText(h.time > t ? until(h.time, t) : "Starting");
                tick.postDelayed(ticker, 30_000);
            };
            tick.postDelayed(ticker, 30_000);
        }
        if (h.ref != null && !h.ref.isEmpty()) {
            card.setClickable(true);
            card.setOnClickListener(v -> startActivity(DetailActivity.open(this, h)));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(4);
        list.addView(card, lp);
    }

    private TextView badge(String abbr) {
        TextView b = text(abbr, 12, WHITE, true, Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(teamColour(abbr));
        d.setStroke(dp(1), 0x33FFFFFF);
        b.setBackground(d);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return b;
    }

    /** "2h 14m", "45m", "3d 4h". */
    static String until(long t, long now) {
        long m = Math.max(0, (t - now) / 60_000L);
        if (m >= 24 * 60) return (m / (24 * 60)) + "d " + (m / 60 % 24) + "h";
        if (m >= 60) return (m / 60) + "h " + (m % 60) + "m";
        return m + "m";
    }

    private void mainHeader(String title, int color) {
        TextView h = text(title.toUpperCase(), 14, color, true, Gravity.CENTER);
        h.setPadding(0, dp(14), 0, dp(6));
        list.addView(h);
    }

    private void otherSection(List<Feed.Item> items) {
        boolean any = false;
        for (Feed.Item i : items) {
            if (i.sport != Feed.OTHER) continue;
            any = true;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(8), dp(12), dp(9));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            bg.setCornerRadius(dp(14));
            int highlightColor = i.live ? ("TENNIS".equals(i.tag) ? YELLOW : RED) : DIM;
            if (i.live) bg.setStroke(dp(2), highlightColor);
            card.setBackground(bg);

            String statusTag = i.live ? "LIVE" : i.past ? "FT" : "UPCOMING";
            String tag = (i.tag != null ? i.tag + " · " : "") + statusTag;
            card.addView(text(tag, 11, i.live ? highlightColor : DIM, true, Gravity.START));
            card.addView(text(i.head, 15, WHITE, true, Gravity.START));
            card.addView(text(i.body, 13, i.live ? YELLOW : DIM, false, Gravity.START));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(6);
            list.addView(card, lp);
        }
        if (!any) list.addView(text("No other live or recent scores", 13, DIM, false, Gravity.CENTER));
    }

    private void section(String title, int sport, int color, List<Feed.Item> items) {
        TextView h = text(title.toUpperCase(), 13, color, true, Gravity.CENTER);
        h.setPadding(0, dp(10), 0, dp(4));
        list.addView(h);
        boolean any = false;
        for (Feed.Item i : items) {
            if (i.sport != sport) continue;
            any = true;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(8), dp(12), dp(9));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            bg.setCornerRadius(dp(14));
            if (i.live) bg.setStroke(dp(2), color);
            card.setBackground(bg);
            String tag = i.live ? "LIVE" : i.past ? "LAST" : "NEXT";
            card.addView(text(tag, 11, i.live ? color : DIM, true, Gravity.START));
            card.addView(text(i.head, 15, WHITE, true, Gravity.START));
            card.addView(text(i.body, 13, i.live ? YELLOW : DIM, false, Gravity.START));
            if (i.ref != null && !i.ref.isEmpty()) {
                card.setClickable(true);
                card.setOnClickListener(v -> startActivity(DetailActivity.open(this, i)));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(6);
            list.addView(card, lp);
        }
        if (!any) list.addView(text("Nothing scheduled", 13, DIM, false, Gravity.CENTER));
    }

    private TextView text(String s, int sp, int color, boolean bold, int gravity) {
        return text(this, s, sp, color, bold, gravity);
    }

    static TextView text(android.content.Context c, String s, int sp, int color, boolean bold, int gravity) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(gravity);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(float v) {
        return dp(this, v);
    }

    static int dp(android.content.Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
