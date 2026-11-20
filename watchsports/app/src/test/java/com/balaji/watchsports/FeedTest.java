package com.balaji.watchsports;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

public class FeedTest {

    @Test
    public void testOtherSportConstantExists() {
        assertEquals(3, Feed.OTHER);
    }

    @Test
    public void testHeadlineIgnoresOtherScores() {
        long now = System.currentTimeMillis();
        List<Feed.Item> items = new ArrayList<>();

        Feed.Item otherLive = new Feed.Item();
        otherLive.sport = Feed.OTHER;
        otherLive.live = true;
        otherLive.head = "Coventry City v Everton";
        otherLive.body = "1-3 · FT";
        items.add(otherLive);

        Feed.Item arsenal = new Feed.Item();
        arsenal.sport = Feed.FOOTBALL;
        arsenal.past = true;
        arsenal.time = now - 3600_000L;
        arsenal.head = "Arsenal 2–0 Chelsea";
        arsenal.body = "Won · Yesterday";
        items.add(arsenal);

        // headline should pick Arsenal, not the "OTHER" score, even if OTHER is live
        Feed.Item picked = Feed.headline(items, now);
        assertNotNull(picked);
        assertEquals(Feed.FOOTBALL, picked.sport);
        assertEquals("Arsenal 2–0 Chelsea", picked.head);
    }

    @Test
    public void testParseOtherCricket() throws Exception {
        String json = "{\"sports\":[{\"leagues\":[{\"name\":\"Bilateral Series\",\"events\":[{" +
                "\"name\":\"Bangladesh v West Indies\",\"shortName\":\"BAN v WI\",\"status\":\"in\"," +
                "\"competitors\":[{\"displayName\":\"Bangladesh\",\"abbreviation\":\"BAN\",\"score\":\"210/7\"}," +
                "{\"displayName\":\"West Indies\",\"abbreviation\":\"WI\",\"score\":\"356\"}]}]}]}]}";

        List<Feed.Item> list = new ArrayList<>();
        Feed.parseOtherCricket(json, list);

        assertFalse(list.isEmpty());
        Feed.Item item = list.get(0);
        assertEquals(Feed.OTHER, item.sport);
        assertEquals("CRICKET", item.tag);
        assertTrue(item.live);
        assertEquals("BAN v WI", item.head);
        assertTrue(item.body.contains("BAN 210/7"));
        assertTrue(item.body.contains("WI 356"));
    }

    @Test
    public void testParseOtherFootball() throws Exception {
        String json = "{\"sports\":[{\"leagues\":[{\"name\":\"English Premier League\",\"events\":[{" +
                "\"name\":\"Coventry City v Everton\",\"shortName\":\"COV v EVE\",\"status\":\"post\",\"summary\":\"FT\"," +
                "\"competitors\":[{\"displayName\":\"Coventry City\",\"score\":\"1\"}," +
                "{\"displayName\":\"Everton\",\"score\":\"3\"}]}]}]}]}";

        List<Feed.Item> list = new ArrayList<>();
        Feed.parseOtherFootball(json, list);

        assertFalse(list.isEmpty());
        Feed.Item item = list.get(0);
        assertEquals(Feed.OTHER, item.sport);
        assertEquals("FOOTBALL", item.tag);
        assertFalse(item.live);
        assertTrue(item.past);
        assertEquals("Coventry City v Everton", item.head);
        assertTrue(item.body.contains("1–3"));
        assertTrue(item.body.contains("FT"));
    }

    @Test
    public void testParseOtherTennis() throws Exception {
        String json = "{\"sports\":[{\"leagues\":[{\"name\":\"ATP\",\"events\":[{" +
                "\"name\":\"Rolex Paris Masters\",\"shortName\":\"Rolex Paris Masters\",\"status\":\"in\"," +
                "\"competitors\":[{\"displayName\":\"Carlos Alcaraz\",\"score\":\"6-4 4-2\"}," +
                "{\"displayName\":\"Casper Ruud\",\"score\":\"4-6 2-4\"}]}]}]}]}";

        List<Feed.Item> list = new ArrayList<>();
        Feed.parseOtherTennis(json, list);

        assertFalse(list.isEmpty());
        Feed.Item item = list.get(0);
        assertEquals(Feed.OTHER, item.sport);
        assertEquals("TENNIS", item.tag);
        assertTrue(item.live);
        assertEquals("Carlos Alcaraz v Casper Ruud", item.head);
        assertTrue(item.body.contains("6-4 4-2"));
    }

    @Test
    public void testOtherCricketFiltersIndia() throws Exception {
        String json = "{\"sports\":[{\"leagues\":[{\"name\":\"India tour of NZ\",\"events\":[{" +
                "\"name\":\"New Zealand v India\",\"shortName\":\"NZ v IND\",\"status\":\"in\"," +
                "\"competitors\":[{\"displayName\":\"New Zealand\",\"abbreviation\":\"NZ\",\"score\":\"240\"}," +
                "{\"displayName\":\"India\",\"abbreviation\":\"IND\",\"score\":\"150/2\"}]}]}]}]}";

        List<Feed.Item> list = new ArrayList<>();
        Feed.parseOtherCricket(json, list);

        // India matches are handled by the main cricket feed, so parseOtherCricket must drop it
        assertTrue(list.isEmpty());
    }

    @Test
    public void testOtherFootballFiltersArsenal() throws Exception {
        String json = "{\"sports\":[{\"leagues\":[{\"name\":\"English Premier League\",\"events\":[{" +
                "\"name\":\"Arsenal v Chelsea\",\"shortName\":\"ARS v CHE\",\"status\":\"post\",\"summary\":\"FT\"," +
                "\"competitors\":[{\"displayName\":\"Arsenal\",\"score\":\"2\"}," +
                "{\"displayName\":\"Chelsea\",\"score\":\"0\"}]}]}]}]}";

        List<Feed.Item> list = new ArrayList<>();
        Feed.parseOtherFootball(json, list);

        // Arsenal matches are handled by the main Arsenal feed, so parseOtherFootball must drop it
        assertTrue(list.isEmpty());
    }

    @Test
    public void testLiveCricketHeadline() {
        long now = System.currentTimeMillis();
        List<Feed.Item> items = Feed.load();
        System.out.println("Total items loaded: " + items.size());
        for (Feed.Item i : items) {
            System.out.println("Item: sport=" + i.sport + " head=" + i.head + " live=" + i.live + " past=" + i.past + " senior=" + i.senior + " body=" + i.body);
        }
        Feed.Item h = Feed.headline(items, now);
        System.out.println("Headline picked: " + (h != null ? (h.head + " | live=" + h.live + " | senior=" + h.senior + " | body=" + h.body) : "null"));
        assertNotNull(h);
    }

    @Test
    public void testCricketLengthFromFormat() {
        assertEquals(5 * 24 * 3600_000L, Feed.cricketLength("Test"));
        assertEquals(5 * 24 * 3600_000L, Feed.cricketLength("First-class"));
        assertEquals(9 * 3600_000L, Feed.cricketLength("ODI"));
        assertEquals(4 * 3600_000L, Feed.cricketLength("T20"));
        // A series name says nothing of the format: the short default (why the format now comes from ESPN's class)
        assertEquals(4 * 3600_000L, Feed.cricketLength("India tour of New Zealand 2026/27"));
    }

    @Test
    public void testStumpsResumeCountsAsNextStart() {
        long now = System.currentTimeMillis();
        Feed.Item test = new Feed.Item();
        test.sport = Feed.CRICKET;
        test.senior = true;
        test.live = true;
        test.time = now - 8 * 3600_000L;
        test.resume = now + 15 * 3600_000L;
        List<Feed.Item> items = new ArrayList<>();
        items.add(test);
        assertEquals(test.resume, Feed.nextStart(items, now));
        test.resume = 0; // in play: no "next start" from it (the live flag drives the checks)
        assertEquals(0, Feed.nextStart(items, now));
    }

    @Test
    public void testCachedLiveTestKeptUntilItsEnd() throws Exception {
        long now = System.currentTimeMillis();
        java.io.File dir = java.nio.file.Files.createTempDirectory("ws").toFile();
        String day2 = "{\"sport\":0,\"live\":true,\"time\":" + (now - 30 * 3600_000L) + ",\"senior\":true,\"comp\":\"Test\","
                + "\"end\":" + (now + 90 * 3600_000L) + ",\"head\":\"NZ v IND\"}";
        String over = "{\"sport\":0,\"live\":true,\"time\":" + (now - 6 * 24 * 3600_000L) + ",\"senior\":true,\"comp\":\"Test\","
                + "\"end\":" + (now - 24 * 3600_000L) + ",\"head\":\"SA v IND\"}";
        java.nio.file.Files.write(new java.io.File(dir, "sport0.json").toPath(), ("[" + day2 + "," + over + "]").getBytes("UTF-8"));
        List<Feed.Item> got = Feed.cached(dir);
        assertEquals(1, got.size());
        assertEquals("NZ v IND", got.get(0).head);
    }

    @Test
    public void testStumpsSnapshot() throws Exception {
        String path = System.getenv("ESPN_SNAPSHOT"); // a saved scoreboard/header response, when checking by hand
        if (path == null) return;
        List<Feed.Item> list = new ArrayList<>();
        Feed.parseCricket(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), "UTF-8"), list);
        for (Feed.Item i : list) System.out.println("SNAP " + i.head + " live=" + i.live + " comp=" + i.competition
                + " end=" + new java.util.Date(i.end) + " resume=" + (i.resume == 0 ? "-" : new java.util.Date(i.resume).toString()));
    }

    @Test
    public void testFaceLines() {
        long now = System.currentTimeMillis(), soon = now + 2 * 3600_000L, nextWeek = now + 3 * 24 * 3600_000L,
                later = now + 20 * 24 * 3600_000L;
        // {label, head, body, shortTitle, shortText, start, day}
        assertEquals("NZ 243/5 · Day 2 in ^1", line(true, now, "STUMPS", "NZ v IND", "NZ 243/5 (82 ov)", "IND", "LIVE", "" + soon, "2"));
        assertEquals("NZ 312 & 140/4 · IND 98 · Stumps", line(true, now, "STUMPS", "NZ v IND", "NZ 312 & 140/4 (50 ov)  ·  IND 98", "IND", "LIVE", "" + soon, "4"));
        assertEquals("NZ 243/5 · Play in ^1", line(true, now, "STUMPS", "NZ v IND", "NZ 243/5 (82 ov)", "IND", "LIVE", "" + soon)); // saved by v10
        assertEquals("LIVE · NZ 243/5 · IND 40/3", line(false, now, "LIVE", "NZ v IND", "NZ 243/5 (82 ov)  ·  IND 40/3 (9 ov)", "IND", "40/3", "0", ""));
        assertEquals("IND 410/8 & 22/1 · NZ 243 & 180", line(false, now, "LIVE", "NZ v IND", "IND 410/8 & 22/1 (6 ov)  ·  NZ 243 & 180", "IND", "22/1", "0", ""));
        assertEquals("Newcastle vs ARS · in ^1", line(true, now, "ARSENAL", "Newcastle United v Arsenal", "Sat 21 Nov 23:00  ·  Premier League", "ARS", "23:00", "" + soon, ""));
        assertEquals("Forest vs ARS · in ^1", line(true, now, "ARSENAL", "Nottingham Forest v Arsenal", "Sat 21 Nov 23:00  ·  Premier League", "ARS", "23:00", "" + soon, ""));
        assertEquals("Newcastle vs ARS · Sat 23:00", line(false, now, "ARSENAL", "Newcastle United v Arsenal", "Sat 21 Nov 23:00  ·  Premier League", "ARS", "23:00", "" + nextWeek, ""));
        assertEquals("Newcastle vs ARS · 5 Dec 23:00", line(false, now, "ARSENAL", "Newcastle United v Arsenal", "Sat 5 Dec 23:00  ·  Premier League", "ARS", "23:00", "" + later, ""));
        assertEquals("Newcastle 1–2 ARS · Won", line(false, now, "ARSENAL", "Newcastle United 1–2 Arsenal", "Won  ·  Yesterday", "ARS", "W 2-1", "0", ""));
        assertEquals("Newcastle vs ARS · LIVE", line(false, now, "LIVE", "Newcastle United v Arsenal", "On now · score at full time", "ARS", "LIVE", "0", ""));
        assertEquals("Las Vegas · FP1 in ^1", line(true, now, "F1", "Las Vegas GP", "Tomorrow 06:00  ·  FP1", "F1", "06:00", "" + soon, ""));
        assertEquals("Las Vegas · Tmrw 06:00 · FP1", line(false, now, "F1", "Las Vegas GP", "Tomorrow 06:00  ·  FP1", "F1", "06:00", "" + nextWeek, ""));
        assertEquals("Abu Dhabi · Quali in ^1", line(true, now, "F1", "Abu Dhabi GP", "Sat 5 Dec 14:00  ·  Qualifying", "F1", "14:00", "" + soon, ""));
        assertEquals("Brazilian · LEC P8 · HAM P9", line(false, now, "F1", "Brazilian GP", "Podium NOR VER HAD  ·  Ferrari LEC P8, HAM P9", "F1", "LEC P8", "0", ""));
        assertEquals("Saudi · LEC P10 · HAM P12", line(false, now, "F1", "Saudi Arabian GP", "Podium VER NOR PIA  ·  Ferrari LEC P10, HAM P12", "F1", "LEC P10", "0", ""));
    }

    private static String line(boolean countdown, long now, String... p) {
        return NextUpComplication.faceLine(p, countdown, now);
    }


    @Test
    public void testRailValues() {
        long now = 1_000_000_000_000L, h = 3600_000L;
        assertEquals(-1f, RailComplication.value(0, false, 0, now), 0.01);             // nothing coming
        assertEquals(-1f, RailComplication.value(now + 30 * h, false, 0, now), 0.01);  // over a day off
        assertEquals(50f, RailComplication.value(now + 12 * h, false, 0, now), 0.01);  // half way through the last day
        assertEquals(100f, RailComplication.value(now - h, false, 0, now), 0.01);      // started; next check catches up
        assertEquals(100f, RailComplication.value(0, true, 0, now), 0.01);             // live (not a Test)
        assertEquals(103f, RailComplication.value(0, true, 3, now), 0.01);             // a Test's third day
        assertEquals(105f, RailComplication.value(0, true, 9, now), 0.01);             // never past five
    }

    @Test
    public void testStumpsGivesWayWhenPlayIsDue() {
        long now = 1_000_000_000_000L, h = 3600_000L;
        Feed.Item test = new Feed.Item();
        test.sport = Feed.CRICKET;
        test.senior = true;
        test.live = true;
        test.time = now - 27 * h;          // day 1 began 27 h ago
        test.resume = now - 3 * h;         // day 2 was due 3 h ago (the 20 Nov case)
        assertEquals("PLAY", NextUpComplication.label(test, now));
        assertTrue(NextUpComplication.liveNow(test, now));           // so the widget checks every 15 minutes
        test.resume = now + 10 * h;        // overnight at stumps
        assertEquals("STUMPS", NextUpComplication.label(test, now));
        assertFalse(NextUpComplication.liveNow(test, now));          // no need to check every 15 minutes
        test.resume = now + 10 * 60_000L;  // ten minutes before the start
        assertTrue(NextUpComplication.liveNow(test, now));
        test.resume = 0;                   // in play
        assertEquals("LIVE", NextUpComplication.label(test, now));
        assertEquals("NZ 243/5 · Day 2 on", line(false, now, "PLAY", "NZ v IND", "NZ 243/5 (82 ov)", "IND", "LIVE", "0", "2"));
    }

    @Test
    public void testWallpaperForDayMatchesFace() {
        // The face: theme = day % 3 (arsenal, ferrari, space), design = (day % 24) / 3; photo n is for design n - 1.
        assertEquals("ferrari-5.jpg", Wallpapers.nameFor(13));   // 13 % 24 = 13: theme 1, design 4
        assertEquals("arsenal-5.jpg", Wallpapers.nameFor(324));  // 20 Nov: 324 % 24 = 12: theme 0, design 4
        assertEquals("space-8.jpg", Wallpapers.nameFor(23));
        assertEquals("arsenal-1.jpg", Wallpapers.nameFor(24));
        java.util.Set<String> all = new java.util.HashSet<>();
        for (int d = 1; d <= 366; d++) all.add(Wallpapers.nameFor(d));
        assertEquals(24, all.size());
    }

    // ---- Moments (the buzz)

    @Test
    public void testWicketsParse() {
        java.util.Map<String, Integer> w = Moments.wickets("NZ 368/9 (113 ov)  ·  IND 45/2");
        assertEquals(Integer.valueOf(9), w.get("NZ"));
        assertEquals(Integer.valueOf(2), w.get("IND"));
        w = Moments.wickets("IND 410/8 & 22/1 (6 ov)  ·  NZ 243 & 180");
        assertEquals(Integer.valueOf(1), w.get("IND"));   // latest innings only
        assertEquals(Integer.valueOf(10), w.get("NZ"));   // all out
        assertTrue(Moments.wickets("India won toss & fielded").isEmpty());
    }

    private static Feed.Item cricket(boolean live, boolean past, String body) {
        Feed.Item i = new Feed.Item();
        i.sport = Feed.CRICKET; i.senior = true; i.ref = "123/456"; i.head = "NZ v IND";
        i.live = live; i.past = past; i.body = body;
        return i;
    }

    @Test
    public void testMomentsWicketsBothWays() {
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        java.util.List<Feed.Item> l = new java.util.ArrayList<>();
        l.add(cricket(true, false, "NZ 368/8 (110 ov)  ·  IND 0/0"));
        assertTrue("first look: nothing to tell", Moments.diff(l, seen).isEmpty());
        l.set(0, cricket(true, false, "NZ 368/9 (112 ov)  ·  IND 0/0"));
        java.util.List<Moments.Event> e = Moments.diff(l, seen);
        assertEquals(1, e.size());
        assertEquals("good", e.get(0).kind);                 // India took one: two quick taps
        assertEquals("NZ 368/9 · IND 0/0", e.get(0).text);
        assertTrue("same score again: quiet", Moments.diff(l, seen).isEmpty());
        l.set(0, cricket(true, false, "NZ 368/9 (112 ov)  ·  IND 12/1 (4 ov)"));
        e = Moments.diff(l, seen);
        assertEquals(1, e.size());
        assertEquals("bad", e.get(0).kind);                  // India lost one: one long buzz
    }

    @Test
    public void testMomentsResultsOnlyOnce() {
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        java.util.List<Feed.Item> l = new java.util.ArrayList<>();
        l.add(cricket(false, true, "India won by 5 wkts"));
        assertTrue("an old result never seen live: quiet", Moments.diff(l, seen).isEmpty());
        seen.clear();
        l.set(0, cricket(true, false, "NZ 200/9  ·  IND 150/5"));
        Moments.diff(l, seen);
        l.set(0, cricket(false, true, "India won by 5 wkts"));
        java.util.List<Moments.Event> e = Moments.diff(l, seen);
        assertEquals(1, e.size());
        assertEquals("result", e.get(0).kind);
        assertTrue("told once", Moments.diff(l, seen).isEmpty());
        // Arsenal: the first result seen is just remembered; a new one is told
        Feed.Item a = new Feed.Item();
        a.sport = Feed.FOOTBALL; a.past = true; a.ref = "e1"; a.head = "Arsenal 1–0 Hull City"; a.body = "Won  ·  Sat 7 Nov";
        java.util.List<Feed.Item> al = new java.util.ArrayList<>();
        al.add(a);
        assertTrue(Moments.diff(al, seen).isEmpty());
        Feed.Item b = new Feed.Item();
        b.sport = Feed.FOOTBALL; b.past = true; b.ref = "e2"; b.head = "Newcastle United 1–2 Arsenal"; b.body = "Won  ·  Today";
        al.set(0, b);
        e = Moments.diff(al, seen);
        assertEquals(1, e.size());
        assertEquals("Won", e.get(0).text);
    }

    // ---- The lap ring

    @Test
    public void testRing() {
        long now = 1_000_000_000_000L, h = 3600_000L;
        Feed.Item up = new Feed.Item();
        up.sport = Feed.FOOTBALL; up.time = now + 12 * h;
        assertEquals(0.5f, Ring.of(up, now).fill, 0.001);
        up.time = now + 30 * h;
        assertNull("more than a day off: no ring", Ring.of(up, now));
        Feed.Item t20 = new Feed.Item();
        t20.sport = Feed.CRICKET; t20.live = true; t20.time = now - h; t20.end = t20.time + 4 * h; t20.competition = "T20";
        t20.body = "IND 120/3 (10 ov)";
        assertEquals(0.5f, Ring.of(t20, now).fill, 0.001);
        t20.competition = "ODI"; t20.end = t20.time + 9 * h; t20.body = "IND 120/3 (25 ov)";
        assertEquals(0.5f, Ring.of(t20, now).fill, 0.001);
        Feed.Item test = new Feed.Item();
        test.sport = Feed.CRICKET; test.live = true; test.time = now - 24 * h; test.end = test.time + 5 * 24 * h; test.day = 2;
        Ring r = Ring.of(test, now);
        assertEquals(5, r.segments);
        assertEquals(0.2f, r.fill, 0.001);                    // day 2 just begun: one day of five done
        Feed.Item ars = new Feed.Item();
        ars.sport = Feed.FOOTBALL; ars.live = true; ars.time = now - (long) (52.5 * 60_000L);
        assertEquals(0.5f, Ring.of(ars, now).fill, 0.001);
        Feed.Item done = new Feed.Item();
        done.past = true;
        assertNull(Ring.of(done, now));
        assertEquals(17f + 4f / 6, Ring.overs("NZ 243  ·  IND 120/3 (17.4 ov)"), 0.001);
    }

    // ---- The F1 weekend and the grid strip

    @Test
    public void testWeekend() {
        long h = 3600_000L, t = 1_000_000_000_000L;
        java.util.List<Weekend.Station> st = Weekend.parse("FP1=" + t + ",FP2=" + (t + 4 * h) + ",Quali=" + (t + 24 * h) + ",Race=" + (t + 48 * h));
        assertEquals(4, st.size());
        assertEquals(0, Weekend.current(st, t - h));                 // before FP1: FP1 is next
        assertTrue(Weekend.onNow(st.get(0), t + h / 2));             // FP1 on now
        assertEquals(1, Weekend.current(st, t + 2 * h));             // after FP1: FP2 next
        assertEquals(3, Weekend.current(st, t + 49 * h));            // the race on now
        assertEquals(4, Weekend.current(st, t + 51 * h));            // weekend over
        assertEquals("Quali", Weekend.shortName("Qualifying"));
        assertTrue(Weekend.parse(null).isEmpty());
        java.util.Map<String, Integer> f = Visuals.ferraris("Podium NOR VER HAD  ·  Ferrari LEC P8, HAM P9");
        assertEquals(Integer.valueOf(8), f.get("LEC"));
        assertEquals(Integer.valueOf(9), f.get("HAM"));
        assertTrue(Visuals.ferraris("Podium NOR VER HAD").isEmpty());
    }

    @Test
    public void testWallpaperSetIsNewer() {
        assertTrue(Wallpapers.isNewer("2026-12", 1, "2026-11", 2));   // a new month
        assertTrue(Wallpapers.isNewer("2026-11", 2, "2026-11", 1));   // the same month, prepared again
        assertFalse(Wallpapers.isNewer("2026-11", 2, "2026-11", 2));  // already have it
        assertFalse(Wallpapers.isNewer("2026-10", 9, "2026-11", 1));  // an older month
        assertTrue(Wallpapers.isNewer("2026-11", 1, "", 1));          // nothing yet
    }
}
