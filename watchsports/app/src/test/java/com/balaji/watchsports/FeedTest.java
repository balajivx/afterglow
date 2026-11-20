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
}
