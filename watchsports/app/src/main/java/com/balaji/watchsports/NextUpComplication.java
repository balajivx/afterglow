package com.balaji.watchsports;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationText;
import androidx.wear.watchface.complications.data.CountDownTimeReference;
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText;
import androidx.wear.watchface.complications.data.TimeDifferenceStyle;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.LongTextComplicationData;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The watch-face widget: the one thing that matters now (India live, else whatever's next of India, Arsenal
 * and F1), as long text ("SPORTS · LIV v ARS · Today 22:00") or short ("ARS" / "22:00"). Tapping opens the
 * app. Wear OS asks every 15 minutes, but it only goes online when it matters: India (or anything) live, an
 * event starting within 20 minutes or since started (a Test at stumps: its next day's start), or 3 hours since the last look; otherwise the kept answers
 * are shown again (3 Nov: going online every 15 minutes, ~400 requests a day, drained the watch). When the
 * feeds can't be reached, the last answer is shown again.
 */
public class NextUpComplication extends ComplicationDataSourceService {
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    @Override
    public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        ComplicationType type = request.getComplicationType();
        net.execute(() -> {
            SharedPreferences cache = getSharedPreferences("headline2", MODE_PRIVATE); // "headline" held lines from before India A was dropped
            long now = System.currentTimeMillis();
            SharedPreferences when = getSharedPreferences("fetch", MODE_PRIVATE);
            long next = when.getLong("next", 0);
            boolean due = now - when.getLong("at", 0) > 3 * 3600_000L;
            boolean live = when.getBoolean("live", false);
            boolean near = next > 0 && now > next - 20 * 60_000L;
            // The app has just loaded everything (and asked for this update): its answers are in the cache.
            boolean fresh = now - when.getLong("appAt", 0) < 5 * 60_000L;
            boolean online = !fresh && (due || live || near);
            // Between the 3-hourly full checks, only the sport that is live or about to start goes online.
            java.util.Set<Integer> only = null;
            if (online && !due) {
                only = new java.util.HashSet<>();
                if (live) only.add(when.getInt("liveSport", Feed.CRICKET));
                if (near) only.add(when.getInt("nextSport", Feed.CRICKET));
            }
            List<Feed.Item> all = online ? Feed.load(getCacheDir(), false, only) : Feed.cached(getCacheDir());
            Feed.Item h = Feed.headline(all, now);
            if (online || h != null) {
                Feed.Item n = Feed.nextItem(all, now);
                when.edit().putLong("at", online && only == null ? now : when.getLong("at", 0))
                        .putLong("next", Feed.nextStart(all, now)).putInt("nextSport", n == null ? Feed.CRICKET : n.sport)
                        .putBoolean("live", h != null && h.live && h.resume == 0) // stumps: see Feed.nextStart
                        .putInt("liveSport", h == null ? Feed.CRICKET : h.sport).apply();
            }
            String[] parts;
            if (h != null) {
                // The last part: when it starts, for a countdown the watch keeps ticking by itself (0: none).
                long start = h.past ? 0 : h.live ? h.resume : h.time;
                parts = new String[]{label(h), h.head, h.body, h.shortTitle, h.shortText, String.valueOf(start)};
                cache.edit().putString("t", String.join("\n", parts)).apply();
            } else {
                String saved = cache.getString("t", null);
                parts = saved == null ? new String[]{"SPORTS", "No connection", "", "SPT", "--"} : saved.split("\n", -1);
            }
            try {
                listener.onComplicationData(data(type, parts));
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public ComplicationData getPreviewData(ComplicationType type) {
        return data(type, new String[]{"ARSENAL", "Arsenal v Hull City", "Today 20:30  ·  Premier League", "ARS", "20:30"});
    }

    /** "LIVE", or the sport ("CRICKET", "ARSENAL", "F1") for what's next or just finished. */
    private static String label(Feed.Item h) {
        if (h.live) return h.resume > 0 ? "STUMPS" : "LIVE";
        return h.sport == Feed.CRICKET ? "CRICKET" : h.sport == Feed.FOOTBALL ? "ARSENAL" : "F1";
    }

    /** {label, head, body, shortTitle, shortText} as the type asked for. */
    private ComplicationData data(ComplicationType type, String[] p) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        PlainComplicationText desc = text(p[1] + ", " + p[2]);
        long start = p.length > 5 ? parseLong(p[5]) : 0;
        long now = System.currentTimeMillis();
        boolean countdown = start > now && start - now < 24 * 3600_000L; // further off, the day and time read better
        if (type == ComplicationType.SHORT_TEXT) {
            ComplicationText t = countdown ? countdown(start, null) : text(p[4]);
            return new ShortTextComplicationData.Builder(t, desc).setTitle(text(p[3])).setTapAction(open).build();
        }
        if (type == ComplicationType.LONG_TEXT) {
            // One line on the face: "ARS vs Hull · 20:30" (today goes without saying), "Tmrw 01:30".
            String when = p[2].replace("Today ", "").replace("Tomorrow ", "Tmrw ").replaceAll("  ·  Premier League$", "");
            String head = p[1].replace("Arsenal", "ARS"); // the other team keeps its full name
            if (p[1].contains("Arsenal")) {
                head = head.replace("Hull City", "Hull").replace(" v ", " vs ");
            }
            String body = when.isEmpty() ? head : head + "  ·  " + when;
            ComplicationText line = null;
            // Coming up today: "ARS vs Newcastle United  ·  in 2h 14m", counted down by the watch every minute
            // without waking this app (no battery cost). At stumps: "STUMPS  ·  NZ 243/5  ·  play in 5h 10m".
            if (countdown && !"STUMPS".equals(p[0])) line = countdown(start, head + "  ·  in ^1");
            // Live cricket: just the scores, without the overs ("LIVE · NZ 134 · IND 40/3"), so it fits one line.
            if (("LIVE".equals(p[0]) || "STUMPS".equals(p[0])) && p[2].matches(".*\\d.*")) {
                body = p[0] + "  ·  " + p[2].replaceAll("\\s*\\([^)]*\\)", "");
                if (countdown && "STUMPS".equals(p[0])) line = countdown(start, body + "  ·  play in ^1");
            } else if ("LIVE".equals(p[0]) && p[1].contains("Arsenal")) {
                body = head + "  ·  LIVE";
            }
            return new LongTextComplicationData.Builder(line != null ? line : text(body), desc).setTitle(text(p[0])).setTapAction(open).build();
        }
        return null;
    }

    /** "2h 14m" (or [template] with ^1 in its place) to [start], kept current by the watch itself. */
    private static ComplicationText countdown(long start, String template) {
        TimeDifferenceComplicationText.Builder b = new TimeDifferenceComplicationText.Builder(
                TimeDifferenceStyle.SHORT_DUAL_UNIT, new CountDownTimeReference(java.time.Instant.ofEpochMilli(start)))
                .setMinimumTimeUnit(java.util.concurrent.TimeUnit.MINUTES);
        if (template != null) b.setText(template);
        return b.build();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static PlainComplicationText text(String s) {
        return new PlainComplicationText.Builder(s).build();
    }

    @Override
    public void onDestroy() {
        net.shutdownNow();
        super.onDestroy();
    }
}
