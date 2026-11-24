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
 * The three things followed, from free public feeds (no keys of our own):
 * India's cricket (ESPN's
 * scoreboard, the one espn.in uses: properly live), Arsenal (TheSportsDB's free
 * key: fixtures and results,
 * no live score), and Formula 1 (Jolpica-F1: the next race, and the last one's
 * podium and Ferraris).
 * Blocking: call off the main thread. Times are shown in the watch's own time
 * zone.
 */
final class Feed {
    static final int CRICKET = 0, FOOTBALL = 1, F1 = 2, OTHER = 3;

    /** One line of news: a live game, the next fixture, or the last result. */
    static final class Item {
        int sport;
        boolean live, past;
        long time; // when it starts (or started)
        boolean senior; // India's main men's side (not India A, women, under-19s)
        String head; // "NZ v IND", "Liverpool v Arsenal", "Mexico City GP"
        String body; // "IND 117/1 (6 ov) · BAN 116", "Sun 22:00", "Ferrari: LEC P2, HAM P4"
        String competition; // the match format from ESPN's class ("Test", "ODI", "T20"), else the league
                            // name
        long end; // live cricket: when it is surely over (start + its format's length); 0 unknown
        long resume; // live cricket at stumps: about when the next day's play starts; 0 otherwise
        int day; // live cricket: ESPN's day of the match (a Test's 1-5); 0 unknown
        String circuit; // F1: Jolpica's circuitId ("vegas"), for the track's drawing
        String sessions; // F1 next race: the weekend in order, "FP1=ms,FP2=ms,FP3=ms,Quali=ms,Race=ms"
        String shortTitle, shortText; // for a small widget: "IND" / "117/1", "ARS" / "22:00"
        String ref; // for its details page (Details): ESPN "league/event", TheSportsDB event id, F1
                    // "next"/"last"
        String tag; // sport badge: "CRICKET", "FOOTBALL", "TENNIS"
    }

    private static final String ESPN = "https://site.api.espn.com/apis/personalized/v2/scoreboard/header?sport=cricket&region=in&lang=en";
    private static final String ESPN_TENNIS = "https://site.api.espn.com/apis/personalized/v2/scoreboard/header?sport=tennis&region=in&lang=en";
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
     * As [load()], sturdier: each sport is tried twice (a watch reaches the
     * internet through the phone, and a
     * request now and then times out), and when it still fails its last good answer
     * from [cache] (a folder;
     * null for none) stands in, up to 12 hours old, without fixtures that have
     * since started.
     */
    static List<Item> load(java.io.File cache, boolean includeOther) {
        return load(cache, includeOther, null);
    }

    /**
     * As [load(File, boolean)], but only the sports in [only] go online (null:
     * all); the rest come from [cache]
     * while it holds them (a live Test needn't re-fetch Arsenal and F1 every 15
     * minutes).
     */
    /**
     * How many sports the last load() actually reached (0: none; the answers all
     * came from the cache).
     */
    static volatile int lastFetched;

    static List<Item> load(java.io.File cache, boolean includeOther, java.util.Set<Integer> only) {
        List<Item> out = new ArrayList<>();
        int fetched = 0;
        int[] sports = includeOther ? new int[] { CRICKET, FOOTBALL, F1, OTHER } : new int[] { CRICKET, FOOTBALL, F1 };
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
                    if (sport == CRICKET)
                        cricket(l);
                    else if (sport == FOOTBALL)
                        arsenal(l);
                    else if (sport == F1)
                        f1(l);
                    else
                        otherScores(l);
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
            if (got != null)
                fetched++;
            if (got != null && f != null)
                save(f, got);
            if (got == null && f != null)
                got = restore(f);
            if (got != null)
                out.addAll(got);
        }
        lastFetched = fetched;
        return out;
    }

    /**
     * The last good answers kept in [cache] by [load(File)], without going online.
     */
    static List<Item> cached(java.io.File cache) {
        return cached(cache, false);
    }

    static List<Item> cached(java.io.File cache, boolean includeOther) {
        List<Item> out = new ArrayList<>();
        int[] sports = includeOther ? new int[] { CRICKET, FOOTBALL, F1, OTHER } : new int[] { CRICKET, FOOTBALL, F1 };
        for (int sport : sports) {
            List<Item> got = restore(new java.io.File(cache, "sport" + sport + ".json"));
            if (got != null)
                out.addAll(got);
        }
        return out;
    }

    /**
     * When the soonest upcoming event the widget cares about starts (ms), or 0 for
     * none.
     */
    static long nextStart(List<Item> all, long now) {
        Item i = nextItem(all, now);
        return i == null ? 0 : startOf(i);
    }

    /** The item whose start is soonest (see [nextStart]), or null. */
    static Item nextItem(List<Item> all, long now) {
        Item next = null;
        for (Item i : all) {
            if (i.sport == OTHER)
                continue;
            if (i.sport == CRICKET && !i.senior)
                continue;
            // At stumps the next day's start counts as an upcoming start (the widget checks
            // in around it).
            boolean upcoming = (!i.past && !i.live) || (i.live && i.resume > 0);
            if (upcoming && startOf(i) > now - 2 * 3600_000L && (next == null || startOf(i) < startOf(next)))
                next = i;
        }
        return next;
    }

    static long startOf(Item i) {
        return i.live && i.resume > 0 ? i.resume : i.time;
    }

    /**
     * Whether an item is actively in play right now:
     * - Cricket: in play (live and not overnight at stumps, until 20 min before
     * play resumes)
     * - Football/F1: live (during the match/session)
     */
    static boolean isLive(Item i, long now) {
        if (i == null || !i.live)
            return false;
        if (i.sport == CRICKET && i.resume > 0) {
            return now >= i.resume - 20 * 60_000L;
        }
        return true;
    }

    private static void save(java.io.File f, List<Item> items) {
        try {
            JSONArray a = new JSONArray();
            for (Item i : items) {
                a.put(new JSONObject().put("sport", i.sport).put("live", i.live).put("past", i.past).put("time", i.time)
                        .put("senior", i.senior).put("head", i.head).put("body", i.body).put("comp", i.competition)
                        .put("end", i.end).put("res", i.resume).put("day", i.day).put("cir", i.circuit)
                        .put("ses", i.sessions)
                        .put("st", i.shortTitle).put("sx", i.shortText).put("ref", i.ref).put("tag", i.tag));
            }
            java.nio.file.Files.write(f.toPath(), a.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    private static List<Item> restore(java.io.File f) {
        try {
            long now = System.currentTimeMillis();
            if (!f.exists() || now - f.lastModified() > 12 * 3600_000L)
                return null;
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
                if (!i.past && !i.live && i.time < now)
                    continue; // a fixture that has started since: it's stale
                // A live match stays live while it could still be in play (a Test across its
                // five days),
                // so a failed fetch doesn't swap the game in play for the next race; once it is
                // surely over
                // the next fixture or race takes over (19 Nov: a finished Test sat on the
                // widget for a day).
                long end = i.end > 0 ? i.end : i.time + cricketLength(i.competition); // caches from before "end"
                if (i.live && i.sport == CRICKET && end < now)
                    continue;
                out.add(i);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The watch-face widget's pick:
     * 1. Matches actively in play (cycles every 30s if multiple, equal priority for
     * India, Arsenal, F1);
     * 2. Soonest upcoming match in next 24 hours across all favourites (cricket at
     * stumps counts as upcoming starting at resume);
     * 3. Soonest upcoming match further out across all favourites;
     * 4. Fallback to latest result.
     */
    /**
     * What the face's widget takes turns showing (the user's requests, 2026-11-23/24): every favourite in play at
     * the same time; else the soonest upcoming favourite together with any other starting within CLOSE_MS of it
     * (events close together take turns; one further off waits its turn); else the single pick of headline().
     * Never empty unless headline() is null.
     */
    /** Upcoming events this close together take turns on the widget. */
    static final long CLOSE_MS = 3 * 3600_000L;

    static List<Item> headlines(List<Item> all, long now) {
        List<Item> out = new ArrayList<>();
        Item first = headline(all, now);
        if (first == null) return out;
        List<Item> fav = favourites(all);
        List<Item> live = liveMatches(fav, now);
        if (!live.isEmpty()) {
            out.addAll(live);
            return out;
        }
        List<Item> up = new ArrayList<>();
        for (Item i : fav) {
            boolean upcoming = (!i.past && !i.live) || (i.live && i.resume > 0 && !isLive(i, now));
            if (upcoming && startOf(i) > now) up.add(i);
        }
        up.sort((a, b) -> Long.compare(startOf(a), startOf(b)));
        if (up.size() >= 2 && startOf(up.get(1)) - startOf(up.get(0)) <= CLOSE_MS) {
            for (Item i : up) if (startOf(i) - startOf(up.get(0)) <= CLOSE_MS) out.add(i);
            return out;
        }
        out.add(first);
        return out;
    }

    /** The favourites headline() chooses from: senior India (any India side if none), Arsenal, F1. */
    static List<Item> favourites(List<Item> all) {
        List<Item> items = new ArrayList<>();
        for (Item i : all) {
            if (i.sport == OTHER) continue;
            if (i.sport != CRICKET || i.senior) items.add(i);
        }
        boolean hasCricket = false;
        for (Item i : items) if (i.sport == CRICKET) { hasCricket = true; break; }
        if (!hasCricket) for (Item i : all) if (i.sport == CRICKET) items.add(i);
        return items;
    }

    static Item headline(List<Item> all, long now) {
        List<Item> items = new ArrayList<>();
        // Priority: Senior India side, Arsenal, F1 (all equal)
        for (Item i : all) {
            if (i.sport == OTHER)
                continue;
            if (i.sport != CRICKET || i.senior)
                items.add(i);
        }
        // If no senior India match was in the feed, include any India national matches
        // (India A, tour)
        boolean hasCricket = false;
        for (Item i : items) {
            if (i.sport == CRICKET) {
                hasCricket = true;
                break;
            }
        }
        if (!hasCricket) {
            for (Item i : all) {
                if (i.sport == CRICKET)
                    items.add(i);
            }
        }

        // 1. Matches actively in play across favourites (equal priority, cycle every
        // 30s)
        List<Item> live = liveMatches(items, now);
        if (!live.isEmpty()) {
            int index = (int) ((now / 30_000L) % live.size());
            return live.get(index);
        }

        // 2. Upcoming matches in next 24 hours: strictly pick the sooner event (min
        // start time) across ALL favourites
        // At stumps, play resuming counts as an upcoming start (startOf(i) ==
        // i.resume).
        Item best = null;
        for (Item i : items) {
            long start = startOf(i);
            boolean upcoming = (!i.past && !i.live) || (i.live && i.resume > 0 && !isLive(i, now));
            if (upcoming && start > now && start - now < 24 * 3600_000L) {
                if (best == null || start < startOf(best))
                    best = i;
            }
        }
        if (best != null)
            return best;

        // 3. Upcoming matches further out: strictly pick whichever is sooner (min time)
        // across all 3 favourites
        for (Item i : items) {
            long start = startOf(i);
            boolean upcoming = (!i.past && !i.live) || (i.live && i.resume > 0 && !isLive(i, now));
            if (upcoming && start > now) {
                if (best == null || start < startOf(best))
                    best = i;
            }
        }
        if (best != null)
            return best;

        // 4. Fallback to latest result
        for (Item i : items) {
            if (i.past && (best == null || i.time > best.time))
                best = i;
        }
        return best;
    }

    /**
     * Get ALL matches actively in play for cycling display (equal priority for
     * India, Arsenal, F1).
     * Does NOT include cricket at stumps.
     */
    static List<Item> liveMatches(List<Item> all, long now) {
        List<Item> live = new ArrayList<>();
        for (Item i : all) {
            if (i.sport != OTHER && isLive(i, now) && (i.sport != CRICKET || i.senior)) {
                live.add(i);
            }
        }
        return live;
    }

    static List<Item> liveMatches(List<Item> all) {
        return liveMatches(all, System.currentTimeMillis());
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
                if (cs == null)
                    continue;
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
                        if (scores.length() > 0)
                            scores.append("  ·  ");
                        scores.append(c.optString("abbreviation")).append(' ').append(score);
                    }
                }
                if (india == null)
                    continue;
                Item it = new Item();
                it.sport = CRICKET;
                it.senior = senior;
                // The format, from the match itself: league names are series ("India tour of
                // New Zealand 2026/27").
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
                    // Stumps on day N: play resumes about N days after the first day's start (half
                    // an hour's
                    // grace for an early start). Until then the widget needn't check every 15
                    // minutes.
                    JSONObject full = e.optJSONObject("fullStatus");
                    JSONObject type = full == null ? null : full.optJSONObject("type");
                    it.day = full == null ? 0 : full.optInt("dayNumber", 0);
                    if (type != null && ("112".equals(type.optString("id"))
                            || "Stumps".equalsIgnoreCase(type.optString("description")))) {
                        int day = Math.max(1, it.day);
                        it.resume = it.time + day * 24 * 3600_000L - 30 * 60_000L;
                    }
                    out.add(it);
                } else if ("pre".equals(state)) {
                    it.body = when(it.time);
                    it.shortText = clock(it.time);
                    if (next == null || it.time < next.time
                            || (it.senior && !next.senior && it.time - next.time < 6 * 3600_000L))
                        next = it;
                } else {
                    it.past = true;
                    JSONObject full = e.optJSONObject("fullStatus");
                    // "India won by 5 wkts (0b rem)": the bracket is jargon
                    String result = full == null ? ""
                            : full.optString("summary").replaceAll("\\s*\\([^)]*\\)\\s*$", "");
                    it.body = result.isEmpty() ? scores.toString() : result;
                    it.shortText = india.optBoolean("winner") ? "WON"
                            : result.toLowerCase(Locale.UK).contains("won") ? "LOST" : "–";
                    if (last == null || it.time > last.time)
                        last = it;
                }
            }
        }
        if (next != null)
            out.add(next);
        if (last != null)
            out.add(last);
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
            it.body = it.live ? "On now · score at full time"
                    : when(it.time) + "  ·  " + e.optString("strLeague").replace("English ", "");
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
        JSONArray next = new JSONObject(get(JOLPICA + "next.json")).getJSONObject("MRData").getJSONObject("RaceTable")
                .optJSONArray("Races");
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
            String[] sessions = { "FirstPractice", "SecondPractice", "ThirdPractice", "SprintQualifying", "Sprint",
                    "Qualifying" };
            java.util.TreeMap<Long, String> weekend = new java.util.TreeMap<>();
            weekend.put(raceTime, "Race");
            for (String s : sessions) {
                JSONObject sObj = r.optJSONObject(s);
                if (sObj != null) {
                    long t = parseTime(sObj.optString("date") + "T" + sObj.optString("time", "00:00:00Z"));
                    weekend.put(t, Weekend.shortName(s));
                    if (t > now && (earliest == raceTime || t < earliest)) {
                        earliest = t;
                        sessionName = s.equals("FirstPractice") ? "FP1"
                                : s.equals("SecondPractice") ? "FP2"
                                        : s.equals("ThirdPractice") ? "FP3"
                                                : s.equals("SprintQualifying") ? "Sprint Shootout" : s;
                    }
                }
            }
            it.time = earliest;
            StringBuilder ws = new StringBuilder();
            for (java.util.Map.Entry<Long, String> e : weekend.entrySet()) {
                if (ws.length() > 0)
                    ws.append(',');
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
        JSONArray last = new JSONObject(get(JOLPICA + "last/results.json")).getJSONObject("MRData")
                .getJSONObject("RaceTable").optJSONArray("Races");
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
                if (i < 3)
                    podium.append(i == 0 ? "" : " ").append(code);
                if ("ferrari".equals(x.getJSONObject("Constructor").optString("constructorId"))) {
                    if (ferrari.length() > 0)
                        ferrari.append(", ");
                    ferrari.append(code).append(" P").append(pos);
                    if (best == null)
                        best = code + " P" + pos;
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
        // Football: only these leagues, in this order (the user's request, 2026-11-23).
        for (String[] lg : FOOTBALL_LEAGUES) {
            List<String> days = new ArrayList<>();
            try {
                days.add(get(ESPN_LEAGUE + lg[0] + "/scoreboard"));                       // today's round
            } catch (Exception ignored) {
            }
            try {
                days.add(get(ESPN_LEAGUE + lg[0] + "/scoreboard?dates=" + yyyymmdd(System.currentTimeMillis() - 24 * 3600_000L)));
            } catch (Exception ignored) {
            }
            try {
                parseLeagueFootball(days, lg[1], out);
            } catch (Exception ignored) {
            }
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
                if (cs == null || cs.length() < 2)
                    continue;
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
                        if (scores.length() > 0)
                            scores.append("  ·  ");
                        String ab = c.optString("abbreviation");
                        scores.append(ab.isEmpty() ? name : ab).append(' ').append(score);
                    }
                }
                if (hasIndia || scores.length() == 0)
                    continue;
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

    /** The football leagues under "Other scores", in order: ESPN slug, the name shown. */
    static final String[][] FOOTBALL_LEAGUES = {
            {"eng.1", "Premier League"}, {"uefa.champions", "Champions League"}, {"esp.1", "LaLiga"}, {"fra.1", "Ligue 1"}};
    static final String ESPN_LEAGUE = "https://site.api.espn.com/apis/site/v2/sports/soccer/";
    /** Per league: live matches, then the latest results (from today's and yesterday's scoreboards), at most this many. */
    static final int PER_LEAGUE = 3;

    static String yyyymmdd(long t) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(t));
    }

    /**
     * One league's matches from ESPN's site scoreboard (one or more days' answers): in play first, then results,
     * newest first, PER_LEAGUE at most; Arsenal's are left to the favourites.
     */
    static void parseLeagueFootball(List<String> days, String league, List<Item> out) throws Exception {
        List<Item> live = new ArrayList<>(), post = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String json : days) {
            JSONArray events = new JSONObject(json).optJSONArray("events");
            for (int j = 0; events != null && j < events.length(); j++) {
                JSONObject e = events.getJSONObject(j);
                if (!seen.add(e.optString("id", e.optString("date") + j))) continue;
                JSONObject comp = e.optJSONArray("competitions") == null ? null : e.getJSONArray("competitions").optJSONObject(0);
                JSONArray cs = comp == null ? null : comp.optJSONArray("competitors");
                if (cs == null || cs.length() < 2) continue;
                JSONObject home = cs.getJSONObject(0), away = cs.getJSONObject(1);
                if ("away".equals(home.optString("homeAway"))) { JSONObject t = home; home = away; away = t; }
                String n1 = home.optJSONObject("team") == null ? "" : home.getJSONObject("team").optString("displayName");
                String n2 = away.optJSONObject("team") == null ? "" : away.getJSONObject("team").optString("displayName");
                if (n1.contains("Arsenal") || n2.contains("Arsenal")) continue;
                JSONObject type = e.optJSONObject("status") == null ? null : e.getJSONObject("status").optJSONObject("type");
                String state = type == null ? "" : type.optString("state");
                if (!"in".equals(state) && !"post".equals(state)) continue;
                Item it = new Item();
                it.sport = OTHER;
                it.tag = "FOOTBALL";
                it.head = n1 + " v " + n2;
                it.time = parseTime(e.optString("date"));
                String s1 = home.optString("score", ""), s2 = away.optString("score", "");
                StringBuilder body = new StringBuilder();
                if (!s1.isEmpty() && !s2.isEmpty()) body.append(s1).append("–").append(s2);
                String detail = type.optString("shortDetail", "");
                if (!detail.isEmpty()) body.append(body.length() > 0 ? "  ·  " : "").append(detail);
                body.append(body.length() > 0 ? "  ·  " : "").append(league);
                it.body = body.toString();
                if ("in".equals(state)) { it.live = true; live.add(it); } else { it.past = true; post.add(it); }
            }
        }
        post.sort((a, b) -> Long.compare(b.time, a.time));
        List<Item> all = new ArrayList<>(live);
        all.addAll(post);
        for (int i = 0; i < Math.min(PER_LEAGUE, all.size()); i++) out.add(all.get(i));
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
                if (cs == null || cs.length() < 2)
                    continue;
                JSONObject c1 = cs.getJSONObject(0), c2 = cs.getJSONObject(1);
                String n1 = c1.optString("displayName", "");
                String n2 = c2.optString("displayName", "");
                if (n1.isEmpty() || n2.isEmpty() || n1.contains("TBD") || n2.contains("TBD"))
                    continue;

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
            if (d == 0)
                return "Today";
            if (d == 1)
                return "Tomorrow";
            if (d == -1)
                return "Yesterday";
        }
        return new SimpleDateFormat("EEE d MMM", Locale.UK).format(new Date(t));
    }

    /**
     * How long a cricket match runs, from its format: a Test (or first-class) five
     * days, an ODI nine hours, a T20 four.
     */
    static long cricketLength(String competition) {
        String c = competition == null ? "" : competition.toLowerCase(Locale.UK);
        if (c.contains("test") || c.contains("first-class"))
            return 5 * 24 * 3600_000L;
        if (c.contains("one day") || c.contains("odi"))
            return 9 * 3600_000L;
        return 4 * 3600_000L;
    }

    /**
     * ISO times as the feeds write them: "2026-11-01T16:30:00Z",
     * "2026-11-01T07:00Z".
     */
    static long parseTime(String iso) {
        for (String f : new String[] { "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss" }) {
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
            if (c.getResponseCode() != 200)
                throw new IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            // ESPN's league scoreboards answer gzip even unasked; unpack it unless the connection already did.
            InputStream raw = c.getInputStream();
            if (!raw.markSupported()) raw = new java.io.BufferedInputStream(raw);
            raw.mark(2);
            int b0 = raw.read(), b1 = raw.read();
            raw.reset();
            if (b0 == 0x1f && b1 == 0x8b) raw = new java.util.zip.GZIPInputStream(raw);
            try (InputStream in = raw) {
                byte[] buf = new byte[8192];
                for (int k; (k = in.read(buf)) > 0;)
                    text.write(buf, 0, k);
            }
            return text.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
