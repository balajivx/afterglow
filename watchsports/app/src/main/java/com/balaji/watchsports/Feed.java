package com.balaji.watchsports;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The three things followed, from free public feeds (no keys of our own): India's cricket (ESPN's
 * scoreboard, the one espn.in uses: properly live), Arsenal (TheSportsDB's free key: fixtures and results,
 * no live score), and Formula 1 (Jolpica-F1: the next race, and the last one's podium and Ferraris).
 * Blocking: call off the main thread. Times are shown in the watch's own time zone.
 */
final class Feed {
    static final int CRICKET = 0, FOOTBALL = 1, F1 = 2, OTHER = 3;

    /** One line of news: a live game, the next fixture, or the last result. */
    static final class Item {
        int sport;
        boolean live, past;
        long time;       // when it starts (or started)
        boolean senior;  // India's main men's side (not India A, women, under-19s)
        String head;     // "NZ v IND", "Liverpool v Arsenal", "Mexico City GP"
        String body;     // "IND 117/1 (6 ov) · BAN 116", "Sun 22:00", "Ferrari: LEC P2, HAM P4"
        String competition; // the match format from ESPN's class ("Test", "ODI", "T20"), else the league name
        long end;        // live cricket: when it is surely over (start + its format's length); 0 unknown
        long resume;     // live cricket at stumps: about when the next day's play starts; 0 otherwise
        int day;         // live cricket: ESPN's day of the match (a Test's 1-5); 0 unknown
        String circuit;  // F1: Jolpica's circuitId ("vegas"), for the track's drawing
        String sessions; // F1 next race: the weekend in order, "FP1=ms,FP2=ms,FP3=ms,Quali=ms,Race=ms"
        String shortTitle, shortText; // for a small widget: "IND" / "117/1", "ARS" / "22:00"
        String ref;      // for its details page (Details): ESPN "league/event", TheSportsDB event id, F1 "next"/"last"
        String tag;      // sport badge: "CRICKET", "FOOTBALL", "TENNIS"
    }

    private static final String ESPN =
            "https://site.api.espn.com/apis/personalized/v2/scoreboard/header?sport=cricket&region=in&lang=en";
    private static final String ESPN_SOCCER =
            "https://site.api.espn.com/apis/personalized/v2/scoreboard/header?sport=soccer&region=in&lang=en";
    private static final String ESPN_TENNIS =
            "https://site.api.espn.com/apis/personalized/v2/scoreboard/header?sport=tennis&region=in&lang=en";
    private static final String SPORTSDB = "https://www.thesportsdb.com/api/v1/json/123/";
    private static final String ARSENAL = "133604";
    private static final String JOLPICA = "https://api.jolpi.ca/ergast/f1/current/";

    /** Everything, each sport on its own: one failing doesn't stop the others. */
    static List<Item> load() {
        return load(null, false);
    }

    static List<Item> load(java.io.File cache) {
        return load(cache, false);
    }

    /**
     * As [load()], sturdier: each sport is tried twice (a watch reaches the internet through the phone, and a
     * request now and then times out), and when it still fails its last good answer from [cache] (a folder;
     * null for none) stands in, up to 12 hours old, without fixtures that have since started.
     */
    static List<Item> load(java.io.File cache, boolean includeOther) {
        return load(cache, includeOther, null);
    }

    /**
     * As [load(File, boolean)], but only the sports in [only] go online (null: all); the rest come from [cache]
     * while it holds them (a live Test needn't re-fetch Arsenal and F1 every 15 minutes).
     */
    /** How many sports the last load() actually reached (0: none; the answers all came from the cache). */
    static volatile int lastFetched;

    static List<Item> load(java.io.File cache, boolean includeOther, java.util.Set<Integer> only) {
        List<Item> out = new ArrayList<>();
        int fetched = 0;
        int[] sports = includeOther ? new int[]{CRICKET, FOOTBALL, F1, OTHER} : new int[]{CRICKET, FOOTBALL, F1};
        for (int sport : sports) {
            if (only != null && !only.contains(sport) && cache != null) {
                List<Item> kept = restore(new java.io.File(cache, "sport" + sport + ".json"));
                if (kept != null) {
                    out.addAll(kept);
                    continue;
                }
            }
            List<Item> got = null;
            for (int attempt = 0; attempt < 2 && got == null; attempt++) {
                try {
                    List<Item> l = new ArrayList<>();
                    if (sport == CRICKET) cricket(l);
                    else if (sport == FOOTBALL) arsenal(l);
                    else if (sport == F1) f1(l);
                    else otherScores(l);
                    got = l;
                } catch (Exception e) {
                    if (attempt == 0) {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
            java.io.File f = cache == null ? null : new java.io.File(cache, "sport" + sport + ".json");
            if (got != null) fetched++;
            if (got != null && f != null) save(f, got);
            if (got == null && f != null) got = restore(f);
            if (got != null) out.addAll(got);
        }
        lastFetched = fetched;
        return out;
    }

    /** The last good answers kept in [cache] by [load(File)], without going online. */
    static List<Item> cached(java.io.File cache) {
        return cached(cache, false);
    }

    static List<Item> cached(java.io.File cache, boolean includeOther) {
        List<Item> out = new ArrayList<>();
        int[] sports = includeOther ? new int[]{CRICKET, FOOTBALL, F1, OTHER} : new int[]{CRICKET, FOOTBALL, F1};
        for (int sport : sports) {
            List<Item> got = restore(new java.io.File(cache, "sport" + sport + ".json"));
            if (got != null) out.addAll(got);
        }
        return out;
    }

    /** When the soonest upcoming event the widget cares about starts (ms), or 0 for none. */
    static long nextStart(List<Item> all, long now) {
        Item i = nextItem(all, now);
        return i == null ? 0 : startOf(i);
    }

    /** The item whose start is soonest (see [nextStart]), or null. */
    static Item nextItem(List<Item> all, long now) {
        Item next = null;
        for (Item i : all) {
            if (i.sport == OTHER) continue;
            if (i.sport == CRICKET && !i.senior) continue;
            // At stumps the next day's start counts as an upcoming start (the widget checks in around it).
            boolean upcoming = (!i.past && !i.live) || (i.live && i.resume > 0);
            if (upcoming && startOf(i) > now - 2 * 3600_000L && (next == null || startOf(i) < startOf(next))) next = i;
        }
        return next;
    }

    private static long startOf(Item i) {
        return i.live ? i.resume : i.time;
    }

    private static void save(java.io.File f, List<Item> items) {
        try {
            JSONArray a = new JSONArray();
            for (Item i : items) {
                a.put(new JSONObject().put("sport", i.sport).put("live", i.live).put("past", i.past).put("time", i.time)
                        .put("senior", i.senior).put("head", i.head).put("body", i.body).put("comp", i.competition)
                        .put("end", i.end).put("res", i.resume).put("day", i.day).put("cir", i.circuit).put("ses", i.sessions)
                        .put("st", i.shortTitle).put("sx", i.shortText).put("ref", i.ref).put("tag", i.tag));
            }
            java.nio.file.Files.write(f.toPath(), a.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    private static List<Item> restore(java.io.File f) {
        try {
            long now = System.currentTimeMillis();
            if (!f.exists() || now - f.lastModified() > 12 * 3600_000L) return null;
            JSONArray a = new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
            List<Item> out = new ArrayList<>();
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.getJSONObject(k);
                Item i = new Item();
                i.sport = o.getInt("sport");
                i.live = o.optBoolean("live");
                i.past = o.optBoolean("past");
                i.time = o.optLong("time");
                i.senior = o.optBoolean("senior");
                i.competition = o.optString("comp");
                i.end = o.optLong("end");
                i.resume = o.optLong("res");
                i.day = o.optInt("day");
                i.circuit = o.optString("cir", null);
                i.sessions = o.optString("ses", null);
                i.head = o.optString("head");
                i.body = o.optString("body");
                i.shortTitle = o.optString("st");
                i.shortText = o.optString("sx");
                i.ref = o.optString("ref", null);
                i.tag = o.optString("tag", null);
                if (!i.past && !i.live && i.time < now) continue; // a fixture that has started since: it's stale
                // A live match stays live while it could still be in play (a Test across its five days),
                // so a failed fetch doesn't swap the game in play for the next race; once it is surely over
                // the next fixture or race takes over (19 Nov: a finished Test sat on the widget for a day).
                long end = i.end > 0 ? i.end : i.time + cricketLength(i.competition); // caches from before "end"
                if (i.live && i.sport == CRICKET && end < now) continue;
                out.add(i);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The watch-face widget's pick: India live; else whichever favourite (India cricket, F1, Arsenal)
     * has an upcoming event SOONEST; else anything else live; else the latest result.
     */
    static Item headline(List<Item> all, long now) {
        List<Item> items = new ArrayList<>();
        // Priority 1: Senior India side, Arsenal, F1
        for (Item i : all) {
            if (i.sport == OTHER) continue;
            if (i.sport != CRICKET || i.senior) items.add(i);
        }
        // If no senior India match was in the feed, include any India national matches (India A, tour)
        boolean hasCricket = false;
        for (Item i : items) {
            if (i.sport == CRICKET) {
                hasCricket = true;
                break;
            }
        }
        if (!hasCricket) {
            for (Item i : all) {
                if (i.sport == CRICKET) items.add(i);
            }
        }

        // 1. Live match in favourites (India senior live first, then any live)
        for (Item i : items) if (i.live && i.sport == CRICKET && i.senior) return i;
        for (Item i : items) if (i.live) return i;

        // 2. Upcoming matches in next 24 hours: strictly pick the sooner event (min time)
        Item best = null;
        for (Item i : items) {
            if (!i.past && !i.live && i.time > now && i.time - now < 24 * 3600_000L) {
                if (best == null || i.time < best.time) best = i;
            }
        }
        if (best != null) return best;

        // 3. Upcoming matches further out: strictly pick whichever is sooner (min time) across all 3 favourites
        for (Item i : items) {
            if (!i.past && !i.live && i.time > now) {
                if (best == null || i.time < best.time) best = i;
            }
        }
        if (best != null) return best;

        // 4. Fallback to latest result
        for (Item i : items) {
            if (i.past && (best == null || i.time > best.time)) best = i;
        }
        return best;
    }

    // ---- India cricket (ESPN)

    private static void cricket(List<Item> out) throws Exception {
        parseCricket(get(ESPN), out);
    }

    static void parseCricket(String json, List<Item> out) throws Exception {
        JSONArray leagues = new JSONObject(json).getJSONArray("sports").getJSONObject(0).getJSONArray("leagues");
        Item next = null, last = null;
        for (int i = 0; i < leagues.length(); i++) {
            String league = leagues.getJSONObject(i).optString("id");
            JSONArray events = leagues.getJSONObject(i).optJSONArray("events");
            for (int j = 0; events != null && j < events.length(); j++) {
                JSONObject e = events.getJSONObject(j);
                JSONArray cs = e.optJSONArray("competitors");
                if (cs == null) continue;
                JSONObject india = null;
                boolean senior = false;
                StringBuilder scores = new StringBuilder();
                for (int k = 0; k < cs.length(); k++) {
                    JSONObject c = cs.getJSONObject(k);
                    String name = c.optString("displayName");
                    if (name.equals("India") || name.startsWith("India ")) {
                        india = c;
                        senior = name.equals("India");
                    }
                    String score = c.optString("score");
                    if (!score.isEmpty()) {
                        if (scores.length() > 0) scores.append("  ·  ");
                        scores.append(c.optString("abbreviation")).append(' ').append(score);
                    }
                }
                if (india == null) continue;
                Item it = new Item();
                it.sport = CRICKET;
                it.senior = senior;
                // The format, from the match itself: league names are series ("India tour of New Zealand 2026/27").
                JSONObject cls = e.optJSONObject("class");
                String format = cls == null ? "" : cls.optString("eventType");
                it.competition = format.isEmpty() ? leagues.optJSONObject(i).optString("name") : format;
                it.ref = league + "/" + e.optString("id");
                it.time = parseTime(e.optString("date"));
                it.head = e.optString("shortName", e.optString("name"));
                it.shortTitle = india.optString("abbreviation", "IND");
                String state = e.optString("status");
                if ("in".equals(state)) {
                    it.live = true;
                    it.body = scores.length() > 0 ? scores.toString() : e.optString("summary");
                    String s = india.optString("score");
                    it.shortText = s.isEmpty() ? "LIVE" : s.replaceAll("\\s*\\(.*", "");
                    it.end = it.time + cricketLength(it.competition);
                    // Stumps on day N: play resumes about N days after the first day's start (half an hour's
                    // grace for an early start). Until then the widget needn't check every 15 minutes.
                    JSONObject full = e.optJSONObject("fullStatus");
                    JSONObject type = full == null ? null : full.optJSONObject("type");
                    it.day = full == null ? 0 : full.optInt("dayNumber", 0);
                    if (type != null && ("112".equals(type.optString("id")) || "Stumps".equalsIgnoreCase(type.optString("description")))) {
                        int day = Math.max(1, it.day);
                        it.resume = it.time + day * 24 * 3600_000L - 30 * 60_000L;
                    }
                    out.add(it);
                } else if ("pre".equals(state)) {
                    it.body = when(it.time);
                    it.shortText = clock(it.time);
                    if (next == null || it.time < next.time || (it.senior && !next.senior && it.time - next.time < 6 * 3600_000L)) next = it;
                } else {
                    it.past = true;
                    JSONObject full = e.optJSONObject("fullStatus");
                    // "India won by 5 wkts (0b rem)": the bracket is jargon
                    String result = full == null ? "" : full.optString("summary").replaceAll("\\s*\\([^)]*\\)\\s*$", "");
                    it.body = result.isEmpty() ? scores.toString() : result;
                    it.shortText = india.optBoolean("winner") ? "WON" : result.toLowerCase(Locale.UK).contains("won") ? "LOST" : "–";
                    if (last == null || it.time > last.time) last = it;
                }
            }
        }
        if (next != null) out.add(next);
        if (last != null) out.add(last);
    }

    // ---- Arsenal (TheSportsDB)

    private static void arsenal(List<Item> out) throws Exception {
        long now = System.currentTimeMillis();
        JSONArray next = new JSONObject(get(SPORTSDB + "eventsnext.php?id=" + ARSENAL)).optJSONArray("events");
        if (next != null && next.length() > 0) {
            JSONObject e = next.getJSONObject(0);
            Item it = new Item();
            it.sport = FOOTBALL;
            it.ref = e.optString("idEvent");
            it.time = parseTime(e.optString("dateEvent") + "T" + e.optString("strTime", "00:00:00") + "Z");
            it.head = e.optString("strHomeTeam") + " v " + e.optString("strAwayTeam");
            it.shortTitle = "ARS";
            // The free feed has no live score: from kick-off to two hours on, it's on now.
            it.live = now >= it.time && now < it.time + 2 * 3600_000L;
            it.body = it.live ? "On now · score at full time" : when(it.time) + "  ·  " + e.optString("strLeague").replace("English ", "");
            it.shortText = it.live ? "LIVE" : clock(it.time);
            out.add(it);
        }
        JSONArray last = new JSONObject(get(SPORTSDB + "eventslast.php?id=" + ARSENAL)).optJSONArray("results");
        if (last != null && last.length() > 0) {
            JSONObject e = last.getJSONObject(0);
            Item it = new Item();
            it.sport = FOOTBALL;
            it.past = true;
            it.ref = e.optString("idEvent");
            it.time = parseTime(e.optString("dateEvent") + "T" + e.optString("strTime", "00:00:00") + "Z");
            String home = e.optString("strHomeTeam"), away = e.optString("strAwayTeam");
            int hs = e.optInt("intHomeScore"), as = e.optInt("intAwayScore");
            it.head = home + " " + hs + "–" + as + " " + away;
            boolean arsenalHome = home.contains("Arsenal");
            int us = arsenalHome ? hs : as, them = arsenalHome ? as : hs;
            String wdl = us > them ? "W" : us < them ? "L" : "D";
            it.body = (wdl.equals("W") ? "Won" : wdl.equals("L") ? "Lost" : "Drew") + "  ·  " + day(it.time);
            it.shortTitle = "ARS";
            it.shortText = wdl + " " + us + "-" + them;
            out.add(it);
        }
    }

    // ---- Formula 1 (Jolpica), Ferrari first

    private static void f1(List<Item> out) throws Exception {
        long now = System.currentTimeMillis();
        JSONArray next = new JSONObject(get(JOLPICA + "next.json")).getJSONObject("MRData").getJSONObject("RaceTable").optJSONArray("Races");
        if (next != null && next.length() > 0) {
            JSONObject r = next.getJSONObject(0);
            Item it = new Item();
            it.sport = F1;
            it.ref = "next";
            JSONObject circuit = r.optJSONObject("Circuit");
            it.circuit = circuit == null ? null : circuit.optString("circuitId", null);
            long raceTime = parseTime(r.optString("date") + "T" + r.optString("time", "00:00:00Z"));
            long earliest = raceTime;
            String sessionName = "Race";
            String[] sessions = {"FirstPractice", "SecondPractice", "ThirdPractice", "SprintQualifying", "Sprint", "Qualifying"};
            java.util.TreeMap<Long, String> weekend = new java.util.TreeMap<>();
            weekend.put(raceTime, "Race");
            for (String s : sessions) {
                JSONObject sObj = r.optJSONObject(s);
                if (sObj != null) {
                    long t = parseTime(sObj.optString("date") + "T" + sObj.optString("time", "00:00:00Z"));
                    weekend.put(t, Weekend.shortName(s));
                    if (t > now && (earliest == raceTime || t < earliest)) {
                        earliest = t;
                        sessionName = s.equals("FirstPractice") ? "FP1" : s.equals("SecondPractice") ? "FP2" : s.equals("ThirdPractice") ? "FP3" : s.equals("SprintQualifying") ? "Sprint Shootout" : s;
                    }
                }
            }
            it.time = earliest;
            StringBuilder ws = new StringBuilder();
            for (java.util.Map.Entry<Long, String> e : weekend.entrySet()) {
                if (ws.length() > 0) ws.append(',');
                ws.append(e.getValue()).append('=').append(e.getKey());
            }
            it.sessions = ws.toString();
            it.head = r.optString("raceName").replace("Grand Prix", "GP");
            it.shortTitle = "F1";
            it.live = now >= it.time && now < it.time + 2 * 3600_000L;
            it.body = it.live ? sessionName + " on now" : when(it.time) + "  ·  " + sessionName;
            it.shortText = it.live ? "LIVE" : clock(it.time);
            out.add(it);
        }
        JSONArray last = new JSONObject(get(JOLPICA + "last/results.json")).getJSONObject("MRData").getJSONObject("RaceTable").optJSONArray("Races");
        if (last != null && last.length() > 0) {
            JSONObject r = last.getJSONObject(0);
            JSONArray res = r.optJSONArray("Results");
            Item it = new Item();
            it.sport = F1;
            it.past = true;
            it.ref = "last";
            JSONObject lastCircuit = r.optJSONObject("Circuit");
            it.circuit = lastCircuit == null ? null : lastCircuit.optString("circuitId", null);
            it.time = parseTime(r.optString("date") + "T" + r.optString("time", "00:00:00Z"));
            it.head = r.optString("raceName").replace("Grand Prix", "GP");
            StringBuilder podium = new StringBuilder(), ferrari = new StringBuilder();
            String best = null;
            for (int i = 0; res != null && i < res.length(); i++) {
                JSONObject x = res.getJSONObject(i);
                String code = x.getJSONObject("Driver").optString("code"), pos = x.optString("position");
                if (i < 3) podium.append(i == 0 ? "" : " ").append(code);
                if ("ferrari".equals(x.getJSONObject("Constructor").optString("constructorId"))) {
                    if (ferrari.length() > 0) ferrari.append(", ");
                    ferrari.append(code).append(" P").append(pos);
                    if (best == null) best = code + " P" + pos;
                }
            }
            it.body = "Podium " + podium + (ferrari.length() > 0 ? "  ·  Ferrari " + ferrari : "");
            it.shortTitle = "F1";
            it.shortText = best != null ? best : "P1 " + podium.toString().split(" ")[0];
            out.add(it);
        }
    }

    // ---- Other scores: cricket, football, tennis

    private static void otherScores(List<Item> out) {
        try {
            parseOtherCricket(get(ESPN), out);
        } catch (Exception ignored) {
        }
        try {
            parseOtherFootball(get(ESPN_SOCCER), out);
        } catch (Exception ignored) {
        }
        try {
            parseOtherTennis(get(ESPN_TENNIS), out);
        } catch (Exception ignored) {
        }
    }

    static void parseOtherCricket(String json, List<Item> out) throws Exception {
        JSONArray leagues = new JSONObject(json).getJSONArray("sports").getJSONObject(0).getJSONArray("leagues");
        List<Item> candidates = new ArrayList<>();
        for (int i = 0; i < leagues.length(); i++) {
            JSONArray events = leagues.getJSONObject(i).optJSONArray("events");
            for (int j = 0; events != null && j < events.length(); j++) {
                JSONObject e = events.getJSONObject(j);
                JSONArray cs = e.optJSONArray("competitors");
                if (cs == null || cs.length() < 2) continue;
                boolean hasIndia = false;
                StringBuilder scores = new StringBuilder();
                for (int k = 0; k < cs.length(); k++) {
                    JSONObject c = cs.getJSONObject(k);
                    String name = c.optString("displayName");
                    if (name.equals("India") || name.startsWith("India ")) {
                        hasIndia = true;
                        break;
                    }
                    String score = c.optString("score");
                    if (!score.isEmpty()) {
                        if (scores.length() > 0) scores.append("  ·  ");
                        String ab = c.optString("abbreviation");
                        scores.append(ab.isEmpty() ? name : ab).append(' ').append(score);
                    }
                }
                if (hasIndia || scores.length() == 0) continue;
                String state = e.optString("status");
                Item it = new Item();
                it.sport = OTHER;
                it.tag = "CRICKET";
                it.head = e.optString("shortName", e.optString("name"));
                it.time = parseTime(e.optString("date"));
                if ("in".equals(state)) {
                    it.live = true;
                    it.body = scores.toString();
                    candidates.add(0, it);
                } else if ("post".equals(state)) {
                    it.past = true;
                    JSONObject full = e.optJSONObject("fullStatus");
                    String res = full == null ? "" : full.optString("summary").replaceAll("\\s*\\([^)]*\\)\\s*$", "");
                    it.body = res.isEmpty() ? scores.toString() : scores.toString() + "  ·  " + res;
                    candidates.add(it);
                }
            }
        }
        for (int i = 0; i < Math.min(2, candidates.size()); i++) {
            out.add(candidates.get(i));
        }
    }

    static void parseOtherFootball(String json, List<Item> out) throws Exception {
        JSONArray leagues = new JSONObject(json).getJSONArray("sports").getJSONObject(0).getJSONArray("leagues");
        List<Item> live = new ArrayList<>(), post = new ArrayList<>();
        for (int i = 0; i < leagues.length(); i++) {
            JSONObject lg = leagues.getJSONObject(i);
            String lgName = lg.optString("name", "").replace("English ", "");
            JSONArray events = lg.optJSONArray("events");
            for (int j = 0; events != null && j < events.length(); j++) {
                JSONObject e = events.getJSONObject(j);
                JSONArray cs = e.optJSONArray("competitors");
                if (cs == null || cs.length() < 2) continue;
                JSONObject c1 = cs.getJSONObject(0), c2 = cs.getJSONObject(1);
                String n1 = c1.optString("displayName", c1.optString("name"));
                String n2 = c2.optString("displayName", c2.optString("name"));
                if (n1.contains("Arsenal") || n2.contains("Arsenal")) continue;
                String s1 = c1.optString("score", "");
                String s2 = c2.optString("score", "");
                String state = e.optString("status");
                if (!"in".equals(state) && !"post".equals(state)) continue;

                Item it = new Item();
                it.sport = OTHER;
                it.tag = "FOOTBALL";
                it.head = n1 + " v " + n2;
                it.time = parseTime(e.optString("date"));

                String scoreStr = (!s1.isEmpty() && !s2.isEmpty()) ? s1 + "–" + s2 : "";
                String sm = e.optString("summary", "");
                StringBuilder body = new StringBuilder();
                if (!scoreStr.isEmpty()) body.append(scoreStr);
                if (!sm.isEmpty()) {
                    if (body.length() > 0) body.append("  ·  ");
                    body.append(sm);
                }
                if (!lgName.isEmpty()) {
                    if (body.length() > 0) body.append("  ·  ");
                    body.append(lgName);
                }
                it.body = body.toString();

                if ("in".equals(state)) {
                    it.live = true;
                    live.add(it);
                } else {
                    it.past = true;
                    boolean isTop = lgName.contains("Premier League") || lgName.contains("Champions League")
                            || lgName.contains("LALIGA") || lgName.contains("Serie A") || lgName.contains("Bundesliga");
                    if (isTop) post.add(0, it);
                    else post.add(it);
                }
            }
        }
        List<Item> candidates = new ArrayList<>(live);
        candidates.addAll(post);
        for (int i = 0; i < Math.min(3, candidates.size()); i++) {
            out.add(candidates.get(i));
        }
    }

    static void parseOtherTennis(String json, List<Item> out) throws Exception {
        JSONArray leagues = new JSONObject(json).getJSONArray("sports").getJSONObject(0).getJSONArray("leagues");
        List<Item> candidates = new ArrayList<>();
        for (int i = 0; i < leagues.length(); i++) {
            JSONObject lg = leagues.getJSONObject(i);
            String tour = lg.optString("name", "");
            JSONArray events = lg.optJSONArray("events");
            for (int j = 0; events != null && j < events.length(); j++) {
                JSONObject e = events.getJSONObject(j);
                JSONArray cs = e.optJSONArray("competitors");
                if (cs == null || cs.length() < 2) continue;
                JSONObject c1 = cs.getJSONObject(0), c2 = cs.getJSONObject(1);
                String n1 = c1.optString("displayName", "");
                String n2 = c2.optString("displayName", "");
                if (n1.isEmpty() || n2.isEmpty() || n1.contains("TBD") || n2.contains("TBD")) continue;

                Item it = new Item();
                it.sport = OTHER;
                it.tag = "TENNIS";
                it.head = n1 + " v " + n2;
                it.time = parseTime(e.optString("date"));

                String s1 = c1.optString("score", "");
                String s2 = c2.optString("score", "");
                String state = e.optString("status");

                if (!s1.isEmpty() || !s2.isEmpty()) {
                    it.body = (!s1.isEmpty() && !s2.isEmpty()) ? s1 + "  |  " + s2 : (!s1.isEmpty() ? s1 : s2);
                } else {
                    it.body = tour.isEmpty() ? e.optString("name", "") : tour + "  ·  " + e.optString("name", "");
                }

                if ("in".equals(state)) {
                    it.live = true;
                    candidates.add(0, it);
                } else if ("post".equals(state)) {
                    it.past = true;
                    candidates.add(it);
                } else if ("pre".equals(state)) {
                    candidates.add(it);
                }
            }
        }
        for (int i = 0; i < Math.min(2, candidates.size()); i++) {
            out.add(candidates.get(i));
        }
    }

    // ---- Times, in the watch's time zone

    /** "Today 22:00", "Tomorrow 12:30", "Tue 4 Nov 19:00". */
    static String when(long t) {
        return day(t) + " " + clock(t);
    }

    static String clock(long t) {
        return new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(t));
    }

    static String day(long t) {
        Calendar c = Calendar.getInstance(), n = Calendar.getInstance();
        c.setTimeInMillis(t);
        int d = c.get(Calendar.DAY_OF_YEAR) - n.get(Calendar.DAY_OF_YEAR);
        if (c.get(Calendar.YEAR) == n.get(Calendar.YEAR)) {
            if (d == 0) return "Today";
            if (d == 1) return "Tomorrow";
            if (d == -1) return "Yesterday";
        }
        return new SimpleDateFormat("EEE d MMM", Locale.UK).format(new Date(t));
    }

    /** How long a cricket match runs, from its format: a Test (or first-class) five days, an ODI nine hours, a T20 four. */
    static long cricketLength(String competition) {
        String c = competition == null ? "" : competition.toLowerCase(Locale.UK);
        if (c.contains("test") || c.contains("first-class")) return 5 * 24 * 3600_000L;
        if (c.contains("one day") || c.contains("odi")) return 9 * 3600_000L;
        return 4 * 3600_000L;
    }

    /** ISO times as the feeds write them: "2026-11-01T16:30:00Z", "2026-11-01T07:00Z". */
    static long parseTime(String iso) {
        for (String f : new String[]{"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss"}) {
            try {
                SimpleDateFormat p = new SimpleDateFormat(f, Locale.UK);
                p.setTimeZone(TimeZone.getTimeZone("UTC"));
                return p.parse(iso).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "WatchSports/1 (Wear OS)");
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int k; (k = in.read(buf)) > 0; ) text.write(buf, 0, k);
            }
            return text.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
