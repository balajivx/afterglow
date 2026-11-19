package com.balaji.watchsports;

import org.junit.Test;
import static org.junit.Assert.*;

public class FplTest {

    @Test
    public void testParseNextDeadline() throws Exception {
        String json = "{\"events\":[" +
                "{\"id\":10,\"name\":\"Gameweek 10\",\"deadline_time\":\"2026-11-02T11:00:00Z\",\"is_next\":false}," +
                "{\"id\":11,\"name\":\"Gameweek 11\",\"deadline_time\":\"2026-11-21T11:00:00Z\",\"deadline_time_epoch\":1795258800,\"is_next\":true}," +
                "{\"id\":12,\"name\":\"Gameweek 12\",\"deadline_time\":\"2026-11-28T11:00:00Z\",\"is_next\":false}" +
                "]}";

        Fpl.Deadline d = Fpl.parse(json);
        assertNotNull(d);
        assertEquals(11, d.id);
        assertEquals("Gameweek 11", d.name);
        assertEquals(1795258800000L, d.deadlineTimeMs);
    }

    @Test
    public void testFormatRemainingDaysAndHours() {
        long now = 1000_000_000L;
        // 3 days and 14 hours = 3*86400s + 14*3600s = 259200 + 50400 = 309600s
        long deadline = now + (3L * 24 * 3600_000L) + (14L * 3600_000L);
        assertEquals("3d 14h", Fpl.formatRemaining(deadline, now));
    }

    @Test
    public void testFormatRemainingHoursAndMinutes() {
        long now = 1000_000_000L;
        // 5 hours and 25 minutes
        long deadline = now + (5L * 3600_000L) + (25L * 60_000L);
        assertEquals("5h 25m", Fpl.formatRemaining(deadline, now));
    }

    @Test
    public void testFormatRemainingPast() {
        long now = 1000_000_000L;
        long deadline = now - 5000L;
        assertEquals("LIVE", Fpl.formatRemaining(deadline, now));
    }

    @Test
    public void testProgressCalculation() {
        long now = 1000_000_000L;
        long weekMs = 7L * 24 * 3600_000L;
        long deadline = now + (weekMs / 2); // 3.5 days remaining
        assertEquals(50.0f, Fpl.progress(deadline, now), 0.1f);
    }
}

