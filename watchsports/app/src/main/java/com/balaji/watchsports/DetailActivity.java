package com.balaji.watchsports;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * One match in full, opened by tapping its card (see Details for what each sport shows). Fetched when opened;
 * a live match reloads every minute while it's on screen, and nothing runs once it's left.
 */
public class DetailActivity extends Activity {
    static final String SPORT = "sport", REF = "ref", HEAD = "head", TAG = "tag", LIVE = "live";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reload = this::load;
    private LinearLayout list;
    private ScrollView scroll;
    private int sport, color;
    private String ref;
    private boolean live;

    static Intent open(Activity from, Feed.Item i) {
        return new Intent(from, DetailActivity.class).putExtra(SPORT, i.sport).putExtra(REF, i.ref).putExtra(HEAD, i.head)
                .putExtra(TAG, i.live ? "LIVE" : i.past ? "LAST" : "NEXT").putExtra(LIVE, i.live);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        sport = in.getIntExtra(SPORT, Feed.CRICKET);
        ref = in.getStringExtra(REF);
        live = in.getBooleanExtra(LIVE, false);
        color = sport == Feed.F1 ? MainActivity.YELLOW : MainActivity.RED;
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF000000);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int side = MainActivity.dp(this, 26);
        list.setPadding(side, MainActivity.dp(this, 34), side, MainActivity.dp(this, 52));
        scroll.addView(list);
        setContentView(scroll);
        header();
        list.addView(MainActivity.text(this, "Loading…", 14, MainActivity.DIM, false, Gravity.CENTER));
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        return MainActivity.crown(this, scroll, e) || super.dispatchGenericMotionEvent(e);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(reload);
    }

    private void header() {
        Intent in = getIntent();
        list.addView(MainActivity.text(this, in.getStringExtra(TAG), 11, live ? color : MainActivity.DIM, true, Gravity.CENTER));
        list.addView(MainActivity.text(this, in.getStringExtra(HEAD), 16, MainActivity.WHITE, true, Gravity.CENTER));
    }

    private void load() {
        main.removeCallbacks(reload);
        new Thread(() -> {
            List<Details.Line> lines;
            try {
                lines = Details.load(sport, ref);
            } catch (Exception e) {
                lines = null;
            }
            List<Details.Line> got = lines;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                show(got);
                if (live) main.postDelayed(reload, 60_000);
            });
        }).start();
    }

    private void show(List<Details.Line> lines) {
        list.removeAllViews();
        header();
        if (lines == null) {
            list.addView(MainActivity.text(this, "Couldn't load the details. Is the phone or Wi-Fi connected?", 13,
                    MainActivity.DIM, false, Gravity.CENTER));
            return;
        }
        for (Details.Line l : lines) {
            switch (l.style) {
                case Details.HEAD: {
                    TextView h = MainActivity.text(this, l.left.toUpperCase(), 11, color, true, Gravity.START);
                    h.setPadding(0, MainActivity.dp(this, 10), 0, MainActivity.dp(this, 2));
                    list.addView(h);
                    break;
                }
                case Details.ROW: {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    TextView a = MainActivity.text(this, l.left, 13, MainActivity.WHITE, false, Gravity.START);
                    TextView b = MainActivity.text(this, l.right, 13, live ? MainActivity.YELLOW : MainActivity.WHITE, true, Gravity.END);
                    row.addView(a, new LinearLayout.LayoutParams(0, -2, 1));
                    row.addView(b, new LinearLayout.LayoutParams(-2, -2));
                    row.setPadding(0, MainActivity.dp(this, 1), 0, MainActivity.dp(this, 1));
                    list.addView(row);
                    break;
                }
                case Details.NOTE:
                    list.addView(MainActivity.text(this, l.left, 13, MainActivity.YELLOW, true, Gravity.CENTER));
                    break;
                default:
                    list.addView(MainActivity.text(this, l.left, 12, MainActivity.DIM, false, Gravity.CENTER));
            }
        }
    }
}
