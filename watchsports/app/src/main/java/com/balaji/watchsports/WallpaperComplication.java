package com.balaji.watchsports;

import android.content.ComponentName;
import android.graphics.drawable.Icon;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.NoDataComplicationData;
import androidx.wear.watchface.complications.data.PhotoImageComplicationData;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;
import androidx.wear.watchface.complications.datasource.TimeInterval;
import androidx.wear.watchface.complications.datasource.TimelineEntry;

import java.time.Instant;
import java.util.Calendar;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The face's photo of the day, from this month's downloaded set (Wallpapers), as a full-screen PHOTO_IMAGE the
 * face draws over its own built-in photo. Today's photo comes with tomorrow's, timed from midnight, so the watch
 * swaps them itself; it asks again every 6 hours (no network then, unless a new month's set is due).
 */
public class WallpaperComplication extends ComplicationDataSourceService {
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        ComplicationType type = request.getComplicationType();
        io.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                if (hasNetwork()) Wallpapers.refresh(this, now);
                if (type != ComplicationType.PHOTO_IMAGE) {
                    listener.onComplicationData(null);
                    return;
                }
                Calendar today = Calendar.getInstance();
                today.setTimeInMillis(now);
                ComplicationData now0 = data(today);
                if (now0 == null) {
                    listener.onComplicationData(new NoDataComplicationData());
                    return;
                }
                Calendar midnight = (Calendar) today.clone();
                midnight.add(Calendar.DAY_OF_YEAR, 1);
                midnight.set(Calendar.HOUR_OF_DAY, 0);
                midnight.set(Calendar.MINUTE, 0);
                midnight.set(Calendar.SECOND, 0);
                midnight.set(Calendar.MILLISECOND, 0);
                ComplicationData next = data(midnight);
                if (next == null) {
                    listener.onComplicationData(now0);
                    return;
                }
                Instant from = Instant.ofEpochMilli(midnight.getTimeInMillis());
                TimelineEntry tomorrow = new TimelineEntry(new TimeInterval(from, from.plusSeconds(24 * 3600)), next);
                listener.onComplicationDataTimeline(new ComplicationDataTimeline(now0, Collections.singletonList(tomorrow)));
            } catch (Exception ignored) {
            }
        });
    }

    private ComplicationData data(Calendar day) {
        byte[] jpg = Wallpapers.photo(this, day);
        if (jpg == null) return null;
        return new PhotoImageComplicationData.Builder(Icon.createWithData(jpg, 0, jpg.length),
                new PlainComplicationText.Builder("Photo of the day").build()).build();
    }

    private boolean hasNetwork() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            return cm == null || cm.getActiveNetwork() != null;
        } catch (Exception e) {
            return true;
        }
    }

    /** Asks the face for a fresh photo (after a new month's set arrives). */
    static void redraw(android.content.Context c) {
        ComplicationDataSourceUpdateRequester.create(c, new ComponentName(c, WallpaperComplication.class)).requestUpdateAll();
    }

    @Override
    public ComplicationData getPreviewData(ComplicationType type) {
        return null;
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
