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
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF000000);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int side = dp(22);
        list.setPadding(side, dp(34), side, dp(48)); // a round screen: room at the top and bottom
        scroll.addView(list);
        setContentView(scroll);
        list.addView(text("Loading…", 15, DIM, false, Gravity.CENTER));
    }

    @Override
    protected void onResume() {
        super.onResume();
        new Thread(() -> {
            List<Feed.Item> items = Feed.load(getCacheDir(), true);
            runOnUiThread(() -> show(items));
            // Trigger complication refresh with fresh data
            getSharedPreferences("fetch", MODE_PRIVATE).edit().putLong("at", 0).apply();
            ComplicationDataSourceUpdateRequester.create(this, new ComponentName(this, NextUpComplication.class)).requestUpdateAll();
        }).start();
    }

    private void show(List<Feed.Item> items) {
        list.removeAllViews();
        if (items.isEmpty()) {
            list.addView(text("Couldn't reach the scores. Is the phone or Wi-Fi connected?", 14, DIM, false, Gravity.CENTER));
            return;
        }

        // Section 1: My Favorites
        mainHeader("★  MY FAVOURITES", RED);
        section("India cricket", Feed.CRICKET, RED, items);
        section("Arsenal", Feed.FOOTBALL, RED, items);
        section("Formula 1", Feed.F1, YELLOW, items);

        // Section 2: Other Scores
        mainHeader("OTHER SCORES", WHITE);
        otherSection(items);
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
