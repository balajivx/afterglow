package com.balaji.watchsports;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fantasy Premier League next gameweek deadline fetcher, parser, and countdown formatter.
 */
public final class Fpl {

    public static final class Deadline {
        public final int id;
        public final String name;
        public final long deadlineTimeMs;

        public Deadline(int id, String name, long deadlineTimeMs) {
            this.id = id;
            this.name = name;
            this.deadlineTimeMs = deadlineTimeMs;
        }
    }

    private static final String FPL_URL = "https://fantasy.premierleague.com/api/bootstrap-static/";

    /** Parse next event from JSON (supports full JSON or truncated events slice). */
    public static Deadline parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray events = root.getJSONArray("events");
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.getJSONObject(i);
            if (ev.optBoolean("is_next", false)) {
                int id = ev.getInt("id");
                String name = ev.optString("name", "GW" + id);
                long epoch = ev.optLong("deadline_time_epoch", 0);
                long deadlineMs = epoch > 0 ? epoch * 1000L : Feed.parseTime(ev.optString("deadline_time"));
                return new Deadline(id, name, deadlineMs);
            }
        }
        return null;
    }

    /** Stream only the first ~45KB of bootstrap-static where events live. */
    public static Deadline fetch() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(FPL_URL).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) WatchSports/1");
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int k; (k = in.read(buf)) > 0; ) {
                    out.write(buf, 0, k);
                    if (out.size() > 40000 && out.toString("UTF-8").contains("\"game_settings\"")) {
                        break;
                    }
                }
            }
            String raw = out.toString("UTF-8");
            int evIdx = raw.indexOf("\"events\":");
            int endEvIdx = raw.indexOf(",\"game_settings\"", evIdx);
            if (evIdx != -1 && endEvIdx != -1) {
                String eventsJson = "{" + raw.substring(evIdx, endEvIdx) + "}";
                return parse(eventsJson);
            }
            return parse(raw);
        } catch (Exception e) {
            throw new IOException("Failed to load FPL deadline: " + e.getMessage(), e);
        } finally {
            c.disconnect();
        }
    }

    /** Formats remaining time in days and hours: "10d 1h", "4h 25m", "35m", or "LIVE". */
    public static String formatRemaining(long deadlineMs, long now) {
        long remaining = deadlineMs - now;
        if (remaining <= 0) return "LIVE";
        long days = remaining / (24 * 3600_000L);
        long hours = (remaining % (24 * 3600_000L)) / 3600_000L;
        long minutes = (remaining % 3600_000L) / 60_000L;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    /** Progress % towards deadline across a 7-day window (0 to 100). */
    public static float progress(long deadlineMs, long now) {
        long remaining = deadlineMs - now;
        if (remaining <= 0) return 0f;
        long weekMs = 7 * 24 * 3600_000L;
        return Math.min(100f, Math.max(0f, (float) remaining / weekMs * 100f));
    }
}

