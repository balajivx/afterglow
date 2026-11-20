package com.balaji.watchsports;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Moments": a buzz you can tell apart without looking, for what changed since the last check (no checks of its
 * own: it rides on the widget's and the app's existing ones, so no extra battery).
 *   India take a wicket: two quick taps.  India lose one: one long buzz.  A result (India, Arsenal, the Ferraris):
 *   three taps. Never for old news: an event is only told once, and only when it was seen as live/upcoming before.
 */
final class Moments {
    static final class Event {
        final String kind, title, text; // kind: good / bad / result
        Event(String kind, String title, String text) {
            this.kind = kind;
            this.title = title;
            this.text = text;
        }
    }

    private Moments() {
    }

    private static final Pattern TEAM = Pattern.compile("^([A-Z][A-Z-]{1,5})\\s+(.+?)\\s*(\\(.*\\))?$");

    /** "NZ 368/9 (113 ov)  ·  IND 45/2" -> {NZ: 9, IND: 2}: wickets in each side's latest innings (all out = 10). */
    static Map<String, Integer> wickets(String body) {
        Map<String, Integer> out = new HashMap<>();
        if (body == null) return out;
        for (String part : body.split("  ·  ")) {
            Matcher m = TEAM.matcher(part.trim());
            if (!m.find()) continue;
            String score = m.group(2);
            if (score.contains("&")) score = score.substring(score.lastIndexOf('&') + 1).trim();
            Matcher w = Pattern.compile("^(\\d+)(?:/(\\d+))?").matcher(score);
            if (!w.find()) continue;
            out.put(m.group(1), w.group(2) == null ? 10 : Integer.parseInt(w.group(2)));
        }
        return out;
    }

    /**
     * What's new in [all] against what [seen] remembered (and [seen] updated). Pure: the caller notifies.
     * Keys: "w|ref|TEAM" wickets, "live|ref" an India match seen live/upcoming, "told|ref" told already,
     * "ars" / "f1" the last result seen.
     */
    static List<Event> diff(List<Feed.Item> all, Map<String, String> seen) {
        List<Event> out = new ArrayList<>();
        for (Feed.Item i : all) {
            if (i.sport == Feed.CRICKET && i.senior && i.ref != null) {
                if (i.live || !i.past) seen.put("live|" + i.ref, "1");
                if (i.live) {
                    for (Map.Entry<String, Integer> e : wickets(i.body).entrySet()) {
                        String k = "w|" + i.ref + "|" + e.getKey();
                        String before = seen.put(k, String.valueOf(e.getValue()));
                        if (before == null || e.getValue() <= Integer.parseInt(before)) continue;
                        String line = i.body.replaceAll("\\s*\\([^)]*\\)", "").replace("  ·  ", " · ");
                        if (e.getKey().equals("IND")) out.add(new Event("bad", "India lose a wicket", line));
                        else out.add(new Event("good", "WICKET!", line));
                    }
                } else if (i.past && seen.containsKey("live|" + i.ref) && !seen.containsKey("told|" + i.ref)) {
                    seen.put("told|" + i.ref, "1");
                    out.add(new Event("result", i.head, i.body));
                }
            } else if (i.sport == Feed.FOOTBALL && i.past && i.ref != null) {
                String before = seen.put("ars", i.ref);
                if (before != null && !before.equals(i.ref)) out.add(new Event("result", "Full time: " + i.head, i.body.split("  ·  ")[0]));
            } else if (i.sport == Feed.F1 && i.past && i.head != null) {
                String before = seen.put("f1", i.head);
                if (before != null && !before.equals(i.head)) {
                    int fe = i.body.indexOf("Ferrari ");
                    out.add(new Event("result", i.head, fe < 0 ? i.body : i.body.substring(fe)));
                }
            }
        }
        return out;
    }

    static boolean on(Context c) {
        return c.getSharedPreferences("moments", Context.MODE_PRIVATE).getBoolean("on", true);
    }

    /** Checks [all] and buzzes for what's new. Called after each fetch that reached the feeds. */
    static synchronized void check(Context c, List<Feed.Item> all) {
        SharedPreferences p = c.getSharedPreferences("moments", Context.MODE_PRIVATE);
        Map<String, String> seen = new HashMap<>();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) if (e.getValue() instanceof String) seen.put(e.getKey(), (String) e.getValue());
        List<Event> events = diff(all, seen);
        SharedPreferences.Editor ed = p.edit();
        for (Map.Entry<String, String> e : seen.entrySet()) ed.putString(e.getKey(), e.getValue());
        ed.apply();
        if (!p.getBoolean("on", true)) return;
        for (Event e : events) notify(c, e);
    }

    private static void notify(Context c, Event e) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        String ch = "moment_" + e.kind;
        NotificationChannel nc = new NotificationChannel(ch, e.kind.equals("good") ? "India take a wicket"
                : e.kind.equals("bad") ? "India lose a wicket" : "Results", NotificationManager.IMPORTANCE_HIGH);
        nc.enableVibration(true);
        nc.setVibrationPattern(e.kind.equals("good") ? new long[]{0, 70, 90, 70}        // two quick taps
                : e.kind.equals("bad") ? new long[]{0, 450}                             // one long buzz
                : new long[]{0, 90, 110, 90, 110, 90});                                 // three taps
        nm.createNotificationChannel(nc);
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        nm.notify(e.kind.hashCode() ^ e.text.hashCode(), new Notification.Builder(c, ch).setSmallIcon(R.drawable.icon)
                .setContentTitle(e.title).setContentText(e.text).setContentIntent(open).setAutoCancel(true)
                .setCategory(Notification.CATEGORY_EVENT).build());
    }
}
