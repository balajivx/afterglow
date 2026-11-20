package com.balaji.watchsports;

import android.content.SharedPreferences;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.NoDataComplicationData;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.RangedValueComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

/**
 * The rail along the top of the face's sports pill (a RANGED_VALUE the face draws as lit dashes):
 *   0-100   how close the next favourite is to starting, over its last 24 hours (100: under way, or live)
 *   101-105 a live Test: 100 + the day being played (the face lights that many of five)
 * Nothing at all when nothing starts within a day. It never goes online and has no schedule of its own
 * (UPDATE_PERIOD 0): NextUpComplication writes what it found and asks for a redraw only when that changes, and
 * the face may ask again when it's shown; the value is then worked out from the clock.
 */
public class RailComplication extends ComplicationDataSourceService {

    @Override
    public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        try {
            ComplicationData d = data(request.getComplicationType());
            android.util.Log.i("WatchSports", "rail request " + request.getComplicationType() + " -> " + (d == null ? "null" : d.getType()));
            listener.onComplicationData(d);
        } catch (Exception ignored) {
        }
    }

    private ComplicationData data(ComplicationType type) {
        if (type != ComplicationType.RANGED_VALUE) return null;
        SharedPreferences r = getSharedPreferences("rail", MODE_PRIVATE);
        float v = value(r.getLong("start", 0), r.getBoolean("live", false), r.getInt("day", 0), System.currentTimeMillis());
        if (v < 0) return new NoDataComplicationData();
        return ranged(v);
    }

    /** The rail's value for these facts (see the class comment), or -1 for none. */
    static float value(long start, boolean live, int day, long now) {
        if (live) return day > 0 ? 100 + Math.min(5, day) : 100;
        if (start <= 0) return -1;
        long left = start - now;
        if (left >= 24 * 3600_000L) return -1;
        return left <= 0 ? 100 : 100f * (1 - left / (24f * 3600_000L));
    }

    private RangedValueComplicationData ranged(float v) {
        // Text, title and a tap action as well, like the FPL dial's data (20 Nov: a bare value never reached the face).
        String text = v > 100 ? "Day " + Math.round(v - 100) : v >= 100 ? "LIVE" : Math.round(v) + "%";
        android.app.PendingIntent open = android.app.PendingIntent.getActivity(this, 1,
                new android.content.Intent(this, MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE);
        return new RangedValueComplicationData.Builder(v, 0f, 105f, new PlainComplicationText.Builder("Sports " + text).build())
                .setText(new PlainComplicationText.Builder(text).build())
                .setTitle(new PlainComplicationText.Builder("SPORTS").build())
                .setTapAction(open).build();
    }

    @Override
    public ComplicationData getPreviewData(ComplicationType type) {
        return type == ComplicationType.RANGED_VALUE ? ranged(70f) : null;
    }
}
