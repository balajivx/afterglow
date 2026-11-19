package com.balaji.watchsports;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * More about one match, fetched only when its card is tapped (the widget never calls this): for cricket
 * ESPN's match summary (toss or result, venue, each innings' batting and bowling), for Arsenal the event and
 * the Premier League table, for F1 the weekend's sessions or the full result, and the championship.
 * Blocking: call off the main thread.
 */
final class Details {
    static final int HEAD = 0, ROW = 1, TEXT = 2, NOTE = 3;

    /** A line on the page: a heading, a row (left and right), plain text, or a highlighted note. */
    static final class Line {
        final int style;
        final String left, right;

        Line(int style, String left, String right) {
            this.style = style;
            this.left = left;
            this.right = right;
        }
    }

    private Details() {
    }

    static List<Line> load(int sport, String ref) throws Exception {
        List<Line> out = new ArrayList<>();
        if (sport == Feed.CRICKET) cricket(ref, out);
        else if (sport == Feed.FOOTBALL) football(ref, out);
        else f1(ref, out);
        return out;
    }

    // ---- Cricket (ESPN match summary)

    private static void cricket(String ref, List<Line> out) throws Exception {
        String[] p = ref.split("/");
        JSONObject j = new JSONObject(Feed.get("https://site.web.api.espn.com/apis/site/v2/sports/cricket/" + p[0]
                + "/summary?event=" + p[1]));
        JSONObject comp = j.getJSONObject("header").getJSONArray("competitions").getJSONObject(0);
        JSONObject venue = j.optJSONObject("gameInfo") == null ? null : j.getJSONObject("gameInfo").optJSONObject("venue");
        String where = comp.optString("description") + (venue != null ? "  ·  " + venue.optString("fullName") : "");
        out.add(new Line(TEXT, where, null));
        String status = comp.optJSONObject("status") == null ? "" : comp.getJSONObject("status").optString("summary");
        if (!status.isEmpty()) out.add(new Line(NOTE, status, null));

        JSONArray cs = comp.optJSONArray("competitors");
        for (int k = 0; cs != null && k < cs.length(); k++) {
            JSONObject c = cs.getJSONObject(k);
            String score = c.optString("score");
            out.add(new Line(ROW, c.getJSONObject("team").optString("abbreviation"), score.isEmpty() ? "Yet to bat" : score));
        }

        // Each innings, the latest first: who batted (from the batting side) and who bowled (from the other).
        JSONArray rosters = j.optJSONArray("rosters");
        if (rosters == null) return;
        int innings = 0;
        for (int r = 0; r < rosters.length(); r++) {
            for (JSONObject s : players(rosters.getJSONObject(r))) {
                JSONArray ls = s.optJSONArray("linescores");
                for (int k = 0; ls != null && k < ls.length(); k++) innings = Math.max(innings, ls.getJSONObject(k).optInt("period"));
            }
        }
        for (int inn = innings; inn >= 1; inn--) {
            for (int r = 0; r < rosters.length(); r++) {
                JSONObject team = rosters.getJSONObject(r);
                List<Line> bat = new ArrayList<>();
                for (JSONObject pl : players(team)) {
                    JSONObject st = stats(pl, inn);
                    if (st == null || !"1".equals(st.optString("batted"))) continue;
                    boolean in = "0".equals(st.optString("outs"));
                    bat.add(new Line(ROW, name(pl) + (in ? " *" : ""), st.optString("runs") + " (" + st.optString("ballsFaced") + ")"));
                }
                if (bat.isEmpty()) continue;
                out.add(new Line(HEAD, team.getJSONObject("team").optString("abbreviation") + " batting"
                        + (innings > 2 ? "  ·  innings " + inn : ""), null));
                out.addAll(bat);
                List<Line> bowl = new ArrayList<>();
                for (int o = 0; o < rosters.length(); o++) {
                    if (o == r) continue;
                    for (JSONObject pl : players(rosters.getJSONObject(o))) {
                        JSONObject st = stats(pl, inn);
                        String overs = st == null ? "" : st.optString("overs");
                        if (overs.isEmpty() || overs.equals("0")) continue;
                        bowl.add(new Line(ROW, name(pl), overs + "-" + st.optString("maidens", "0") + "-"
                                + st.optString("conceded") + "-" + st.optString("wickets")));
                    }
                }
                if (!bowl.isEmpty()) {
                    out.add(new Line(HEAD, "Bowling  ·  O-M-R-W", null));
                    out.addAll(bowl);
                }
            }
        }
        out.add(new Line(TEXT, "* not out", null));
    }

    private static List<JSONObject> players(JSONObject team) throws Exception {
        List<JSONObject> l = new ArrayList<>();
        JSONArray a = team.optJSONArray("roster");
        for (int i = 0; a != null && i < a.length(); i++) l.add(a.getJSONObject(i));
        return l;
    }

    /** A player's figures for one innings, as name -> display value, or null. */
    private static JSONObject stats(JSONObject player, int innings) throws Exception {
        JSONArray ls = player.optJSONArray("linescores");
        for (int k = 0; ls != null && k < ls.length(); k++) {
            JSONObject period = ls.getJSONObject(k);
            if (period.optInt("period") != innings) continue;
            JSONArray inner = period.optJSONArray("linescores");
            if (inner == null || inner.length() == 0) return null;
            JSONObject cat = inner.getJSONObject(0).getJSONObject("statistics").getJSONArray("categories").getJSONObject(0);
            JSONArray st = cat.getJSONArray("stats");
            JSONObject out = new JSONObject();
            for (int i = 0; i < st.length(); i++) out.put(st.getJSONObject(i).optString("name"), st.getJSONObject(i).optString("displayValue"));
            return out;
        }
        return null;
    }

    /** "Daryl Mitchell" -> "D Mitchell", to fit the round screen. */
    private static String name(JSONObject player) {
        JSONObject a = player.optJSONObject("athlete");
        String n = a == null ? "?" : a.optString("displayName");
        int sp = n.indexOf(' ');
        return sp > 0 ? n.charAt(0) + n.substring(sp) : n;
    }

    // ---- Arsenal (TheSportsDB)

    private static void football(String id, List<Line> out) throws Exception {
        JSONObject e = new JSONObject(Feed.get("https://www.thesportsdb.com/api/v1/json/123/lookupevent.php?id=" + id))
                .getJSONArray("events").getJSONObject(0);
        String league = e.optString("strLeague").replace("English ", "");
        String round = e.optString("intRound");
        out.add(new Line(TEXT, league + (round.isEmpty() || round.equals("null") ? "" : "  ·  round " + round), null));
        out.add(new Line(TEXT, e.optString("strVenue"), null));
        long t = Feed.parseTime(e.optString("dateEvent") + "T" + e.optString("strTime", "00:00:00") + "Z");
        out.add(new Line(TEXT, Feed.when(t), null));
        String hs = e.optString("intHomeScore"), as = e.optString("intAwayScore");
        if (!hs.isEmpty() && !hs.equals("null")) {
            out.add(new Line(ROW, e.optString("strHomeTeam"), hs));
            out.add(new Line(ROW, e.optString("strAwayTeam"), as));
            String st = e.optString("strStatus");
            if (!st.isEmpty() && !st.equals("null")) out.add(new Line(NOTE, st.equals("FT") ? "Full time" : st, null));
        }

        // The Premier League table: the top four, and Arsenal if lower.
        JSONArray table = new JSONObject(Feed.get("https://www.thesportsdb.com/api/v1/json/123/lookuptable.php?l=4328&s="
                + e.optString("strSeason"))).optJSONArray("table");
        if (table == null || table.length() == 0) return;
        out.add(new Line(HEAD, "Premier League", null));
        for (int i = 0; i < table.length(); i++) {
            JSONObject r = table.getJSONObject(i);
            String team = r.optString("strTeam");
            if (i >= 4 && !team.equals("Arsenal")) continue;
            out.add(new Line(ROW, r.optString("intRank") + "  " + team.replace(" and Hove Albion", "").replace("Manchester", "Man"),
                    "P" + r.optString("intPlayed") + " · " + r.optString("intPoints") + " pts"));
        }
    }

    // ---- Formula 1 (Jolpica)

    private static final String[][] SESSIONS = {{"FirstPractice", "Practice 1"}, {"SecondPractice", "Practice 2"},
            {"ThirdPractice", "Practice 3"}, {"SprintQualifying", "Sprint quali"}, {"Sprint", "Sprint"},
            {"Qualifying", "Qualifying"}};

    private static void f1(String which, List<Line> out) throws Exception {
        String base = "https://api.jolpi.ca/ergast/f1/current/";
        if ("next".equals(which)) {
            JSONObject r = new JSONObject(Feed.get(base + "next.json")).getJSONObject("MRData").getJSONObject("RaceTable")
                    .getJSONArray("Races").getJSONObject(0);
            JSONObject c = r.getJSONObject("Circuit");
            out.add(new Line(TEXT, "Round " + r.optString("round") + "  ·  " + c.optString("circuitName"), null));
            out.add(new Line(TEXT, c.getJSONObject("Location").optString("locality") + ", "
                    + c.getJSONObject("Location").optString("country"), null));
            out.add(new Line(HEAD, "Weekend (your time)", null));
            for (String[] s : SESSIONS) {
                JSONObject x = r.optJSONObject(s[0]);
                if (x != null) out.add(new Line(ROW, s[1], Feed.when(Feed.parseTime(x.optString("date") + "T" + x.optString("time")))));
            }
            out.add(new Line(ROW, "Race", Feed.when(Feed.parseTime(r.optString("date") + "T" + r.optString("time")))));
        } else {
            JSONObject r = new JSONObject(Feed.get(base + "last/results.json")).getJSONObject("MRData")
                    .getJSONObject("RaceTable").getJSONArray("Races").getJSONObject(0);
            out.add(new Line(TEXT, "Round " + r.optString("round") + "  ·  " + Feed.day(Feed.parseTime(r.optString("date")
                    + "T" + r.optString("time", "00:00:00Z"))), null));
            JSONArray res = r.getJSONArray("Results");
            out.add(new Line(HEAD, "Result", null));
            String fastest = null;
            for (int i = 0; i < res.length(); i++) {
                JSONObject x = res.getJSONObject(i);
                String code = x.getJSONObject("Driver").optString("code");
                boolean ferrari = "ferrari".equals(x.getJSONObject("Constructor").optString("constructorId"));
                JSONObject fl = x.optJSONObject("FastestLap");
                if (fl != null && "1".equals(fl.optString("rank"))) fastest = code + "  " + fl.getJSONObject("Time").optString("time");
                if (i >= 10 && !ferrari) continue;
                JSONObject time = x.optJSONObject("Time");
                String gap = time != null ? time.optString("time") : x.optString("status");
                out.add(new Line(ROW, x.optString("positionText") + "  " + code + (ferrari ? "  SF" : ""), gap));
            }
            if (fastest != null) out.add(new Line(NOTE, "Fastest lap  " + fastest, null));
        }
        JSONArray ds = new JSONObject(Feed.get(base + "driverStandings.json")).getJSONObject("MRData")
                .getJSONObject("StandingsTable").getJSONArray("StandingsLists").getJSONObject(0).getJSONArray("DriverStandings");
        out.add(new Line(HEAD, "Championship", null));
        for (int i = 0; i < ds.length(); i++) {
            JSONObject d = ds.getJSONObject(i);
            JSONArray teams = d.optJSONArray("Constructors");
            boolean ferrari = teams != null && teams.length() > 0 && "ferrari".equals(teams.getJSONObject(0).optString("constructorId"));
            if (i >= 3 && !ferrari) continue;
            out.add(new Line(ROW, d.optString("position") + "  " + d.getJSONObject("Driver").optString("code") + (ferrari ? "  SF" : ""),
                    d.optString("points") + " pts"));
        }
    }
}
