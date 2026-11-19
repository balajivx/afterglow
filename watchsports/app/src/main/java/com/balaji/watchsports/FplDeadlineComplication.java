package com.balaji.watchsports;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.RangedValueComplicationData;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FPL Deadline Complication: displays time remaining until the next Fantasy Premier League deadline
 * in days and hours (e.g., "10d 1h", "4h 30m").
 *
 * Cache Strategy:
 * Because FPL deadlines change only once a week, the deadline timestamp is cached in SharedPreferences.
 * While deadline > now, calculations are purely local (zero network requests, zero battery drain).
 * The network is only queried when the deadline passes or no cached deadline exists.
 */
public class FplDeadlineComplication extends ComplicationDataSourceService {
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    // Reliable seed for GW11 deadline: Saturday, 21 Nov 2026 11:00:00 UTC
    private static final long SEED_DEADLINE = 1795258800000L;
    private static final int SEED_GW_ID = 11;
    private static final String SEED_GW_NAME = "GW11";

    @Override
    public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        ComplicationType type = request.getComplicationType();
        net.execute(() -> {
            SharedPreferences prefs = getSharedPreferences("fpl_cache", MODE_PRIVATE);
            long now = System.currentTimeMillis();
            long deadlineMs = prefs.getLong("deadline", 0);
            int gwId = prefs.getInt("gw_id", 0);
            String gwName = prefs.getString("gw_name", SEED_GW_NAME);

            // Seed fallback if uninitialized
            if (deadlineMs == 0 && now < SEED_DEADLINE) {
                deadlineMs = SEED_DEADLINE;
                gwId = SEED_GW_ID;
            }

            // Network fetch policy:
            // FPL gameweek deadlines are strictly weekly.
            // While a valid deadline is in the future, NEVER query the network (0 network calls, 0 battery drain).
            // Only query the API when the current deadline has passed (deadlineMs <= now) or no data is cached yet.
            boolean needFetch = (deadlineMs <= now) || (gwId == 0);
            if (needFetch) {
                try {
                    Fpl.Deadline d = Fpl.fetch();
                    if (d != null) {
                        deadlineMs = d.deadlineTimeMs;
                        gwId = d.id;
                        gwName = d.name;
                        prefs.edit()
                                .putLong("deadline", deadlineMs)
                                .putInt("gw_id", gwId)
                                .putString("gw_name", gwName)
                                .putLong("fetched_at", now)
                                .apply();
                    }
                } catch (Exception ignored) {
                }
            }

            // Ensure fallback if fetch failed while offline
            if (deadlineMs == 0 && now < SEED_DEADLINE) {
                deadlineMs = SEED_DEADLINE;
                gwId = SEED_GW_ID;
            }

            String remainingStr = deadlineMs > 0 ? Fpl.formatRemaining(deadlineMs, now) : "--";
            float progressVal = deadlineMs > 0 ? Fpl.progress(deadlineMs, now) : 0f;
            String titleStr = gwId > 0 ? "GW" + gwId : "FPL";

            try {
                listener.onComplicationData(data(type, remainingStr, titleStr, progressVal));
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public ComplicationData getPreviewData(ComplicationType type) {
        return data(type, "10d 1h", "GW11", 75f);
    }

    private ComplicationData data(ComplicationType type, String remaining, String title, float progress) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        PlainComplicationText text = new PlainComplicationText.Builder(remaining).build();
        PlainComplicationText titleText = new PlainComplicationText.Builder(title).build();
        PlainComplicationText desc = new PlainComplicationText.Builder("FPL Deadline: " + remaining).build();

        if (type == ComplicationType.RANGED_VALUE) {
            return new RangedValueComplicationData.Builder(progress, 0f, 100f, desc)
                    .setText(text)
                    .setTitle(titleText)
                    .setTapAction(open)
                    .build();
        }
        if (type == ComplicationType.SHORT_TEXT) {
            return new ShortTextComplicationData.Builder(text, desc)
                    .setTitle(titleText)
                    .setTapAction(open)
                    .build();
        }
        return null;
    }
}

