package com.balaji.watchsports;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the lap ring round the app's edge shows for the hero item. Plain Java (tested).
 *   upcoming within a day -> fills towards the start;  live T20/ODI -> overs bowled of 20/50;  live Test -> five
 *   days, today filling over its ~7.5 h of play;  Arsenal live -> minutes of ~105;  F1 session -> time of 2 h.
 */
final class Ring {
    final int segments;   // 0: one continuous ring; 5: a Test's days
    final float fill;     // 0-1 overall (with segments: days done + today's share, / 5)
    final String label;   // what the fill means, for the content description

    private Ring(int segments, float fill, String label) {
        this.segments = segments;
        this.fill = Math.max(0f, Math.min(1f, fill));
        this.label = label;
    }

    private static final Pattern OVERS = Pattern.compile("\\((\\d+)(?:\\.(\\d))? ov\\)");

    /** The ring for [h] at [now], or null for none (a result, or a start more than a day off). */
    static Ring of(Feed.Item h, long now) {
        if (h == null || h.past) return null;
        if (!h.live) {
            long left = h.time - now;
            if (left <= 0 || left > 24 * 3600_000L) return null;
            return new Ring(0, 1 - left / (24f * 3600_000L), "until the start");
        }
        switch (h.sport) {
            case Feed.CRICKET: {
                boolean test = h.end - h.time > 24 * 3600_000L || "test".equalsIgnoreCase(h.competition);
                if (test) {
                    int day = Math.max(1, Math.min(5, h.day > 0 ? h.day : 1));
                    long dayStart = h.time + (day - 1) * 24 * 3600_000L;
                    float today = h.resume > 0 ? 1f : Math.max(0f, Math.min(1f, (now - dayStart) / (7.5f * 3600_000L)));
                    return new Ring(5, (day - 1 + today) / 5f, "day " + day + " of 5");
                }
                float overs = overs(h.body);
                String f = h.competition == null ? "" : h.competition.toLowerCase(Locale.UK);
                int of = f.contains("odi") || f.contains("one day") ? 50 : 20;
                return new Ring(0, overs / of, "overs of " + of);
            }
            case Feed.FOOTBALL:
                return new Ring(0, (now - h.time) / (105f * 60_000L), "minutes played");
            default:
                return new Ring(0, (now - h.time) / (2f * 3600_000L), "session time");
        }
    }

    /** The overs of the innings in play: the last "(N.b ov)" in the score line ("IND 120/3 (17.4 ov)" -> 17.67). */
    static float overs(String body) {
        if (body == null) return 0;
        Matcher m = OVERS.matcher(body);
        float last = 0;
        while (m.find()) last = Integer.parseInt(m.group(1)) + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2)) / 6f);
        return last;
    }
}
