package com.balaji.watchsports;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationText;
import androidx.wear.watchface.complications.data.CountDownTimeReference;
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText;
import androidx.wear.watchface.complications.data.TimeDifferenceStyle;
import androidx.wear.watchface.complications.datasource.TimelineEntry;
import androidx.wear.watchface.complications.datasource.TimeInterval;
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.LongTextComplicationData;
import androidx.wear.watchface.complications.data.MonochromaticImage;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

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
            // No network (the watch asleep, or away from the phone): don't try, and don't count it as a check.
            boolean online = !fresh && (due || live || near) && hasNetwork();
            // Between the 3-hourly full checks, only the sport that is live or about to start goes online.
            java.util.Set<Integer> only = null;
            if (online && !due) {
                only = new java.util.HashSet<>();
                if (live) only.add(when.getInt("liveSport", Feed.CRICKET));
                if (near) only.add(when.getInt("nextSport", Feed.CRICKET));
            }
            List<Feed.Item> all = online ? Feed.load(getCacheDir(), false, only) : Feed.cached(getCacheDir());
            // 20 Nov: a check that reached nothing used to count as done, so a Test sat on "STUMPS" for hours
            // after play resumed. Only a check that got an answer counts; a failed one is tried again next time.
            boolean reached = online && Feed.lastFetched > 0;
            if (reached) Moments.check(this, all); // a wicket or a result since the last check: buzz (no extra fetches)

            Feed.Item h = Feed.headline(all, now);

            if (online || h != null) {
                Feed.Item n = Feed.nextItem(all, now);
                when.edit().putLong("at", reached && only == null ? now : when.getLong("at", 0))
                        .putLong("next", Feed.nextStart(all, now)).putInt("nextSport", n == null ? Feed.CRICKET : n.sport)
                        .putBoolean("live", h != null && liveNow(h, now))
                        .putInt("liveSport", h == null ? Feed.CRICKET : h.sport).apply();
            }
            String[] parts;
            List<String[]> turns = new java.util.ArrayList<>();   // more than one: the face takes turns
            if (h != null) {
                List<Feed.Item> shown = Feed.headlines(all, now);
                if (shown.isEmpty()) shown = java.util.Collections.singletonList(h);
                for (Feed.Item t : shown) turns.add(parts(t, now));
                parts = turns.get(0);
                rail(shown.get(0), now);   // the rail follows the soonest
                cache.edit().putString("t", String.join("\n", parts)).apply();
            } else {
                String saved = cache.getString("t", null);
                parts = saved == null ? new String[]{"SPORTS", "No connection", "", "SPT", "--"} : saved.split("\n", -1);
            }
            try {
                ComplicationData first = data(type, parts);
                if (turns.size() < 2 || first == null) {
                    listener.onComplicationData(first);
                } else {
                    // Two (or more) events: alternate every TURN seconds for the next half hour. The face switches by
                    // itself (no wake-ups, nothing fetched); each countdown keeps ticking on its own.
                    List<ComplicationData> each = new java.util.ArrayList<>();
                    for (String[] t : turns) each.add(data(type, t));
                    List<TimelineEntry> entries = new java.util.ArrayList<>();
                    java.time.Instant t0 = java.time.Instant.ofEpochMilli(now);
                    for (int k = 1; k < TURNS; k++) {
                        java.time.Instant a = t0.plusSeconds((long) k * TURN), b = a.plusSeconds(TURN);
                        entries.add(new TimelineEntry(new TimeInterval(a, b), each.get(k % each.size())));
                    }
                    listener.onComplicationDataTimeline(new ComplicationDataTimeline(first, entries));
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** Seconds each event stays on the face, and how many turns (half an hour) one answer covers. */
    static final int TURN = 15, TURNS = 120;

    /** {label, head, body, shortTitle, shortText, start, day, track} for one event. */
    private static String[] parts(Feed.Item h, long now) {
        // The last part: when it starts, for a countdown the watch keeps ticking by itself (0: none).
        long start = h.past ? 0 : h.live ? h.resume : h.time;
        // At stumps, which day's play is next ("Day 2"): resume = first day's start + N days - 30 min.
        String day = h.live && h.resume > 0 ? String.valueOf((h.resume - h.time + 30 * 60_000L) / (24 * 3600_000L) + 1) : "";
        // Race week: the circuit's own outline stands in for the flag (a track drawing per circuit).
        String track = h.sport == Feed.F1 && h.circuit != null && !h.past && start - now < 7 * 24 * 3600_000L ? h.circuit : "";
        if (h.live && h.resume > 0 && now >= h.resume) start = 0; // play is due: no countdown (see label)
        return new String[]{label(h, now), h.head, h.body, h.shortTitle, h.shortText, String.valueOf(start), day, track};
    }

    @Override
    public ComplicationData getPreviewData(ComplicationType type) {
        return data(type, new String[]{"ARSENAL", "Arsenal v Hull City", "Today 20:30  ·  Premier League", "ARS", "20:30"});
    }

    /**
     * A live match the widget should check every 15 minutes: in play, or a Test at stumps once the next day's
     * start is near or past (until the feed says it's under way, or stumps keeps being reported).
     * Works for all sports (cricket, football, F1).
     */
    static boolean liveNow(Feed.Item h, long now) {
        return Feed.isLive(h, now);
    }

    private boolean hasNetwork() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            return cm == null || cm.getActiveNetwork() != null;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * "LIVE"; "STUMPS" until the next day's start; "PLAY" once it's due but the feed hasn't been reached since
     * (the last score, "Day 2 on"); else the sport ("CRICKET", "ARSENAL", "F1") for what's next or just finished.
     */
    static String label(Feed.Item h, long now) {
        if (h.live) return h.resume == 0 ? "LIVE" : now < h.resume ? "STUMPS" : "PLAY";
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
            ComplicationText t = countdown ? countdown(start, null, false) : text(p[4]);
            return new ShortTextComplicationData.Builder(t, desc).setTitle(text(p[3])).setTapAction(open)
                    .setMonochromaticImage(icon(p)).build();
        }
        if (type == ComplicationType.LONG_TEXT) {
            String line = faceLine(p, countdown, now);
            // Stumps: whole hours ("Day 3 in 16h") leave room for both scores; minutes in the last hour.
            ComplicationText t = line.contains("^1") ? countdown(start, line, "STUMPS".equals(p[0])) : text(line);
            return new LongTextComplicationData.Builder(t, desc).setTitle(text(p[0])).setTapAction(open)
                    .setMonochromaticImage(icon(p)).build();
        }
        return null;
    }

    /**
     * The face's one line, for {label, head, body, shortTitle, shortText, start, day}: the sport's icon sits in
     * front of it, so the line needn't name the sport, and ~245 px at 17 px bold must hold it (in the face's
     * font: about 31 characters). "^1" marks a countdown the watch keeps current itself.
     *   live cricket  "LIVE · NZ 243/5 · IND 40/3"       stumps   "NZ 243/5 · Day 2 in ^1"
     *   Arsenal soon  "Newcastle vs ARS · in ^1"          F1 soon  "Las Vegas · FP1 in ^1"
     *   this week     "Newcastle vs ARS · Sat 23:00"      later    "Newcastle vs ARS · 5 Dec 23:00"
     *   results       "Newcastle 1–2 ARS · Won"           F1       "Brazilian · LEC P8 · HAM P9"
     */
    static String faceLine(String[] p, boolean countdown, long now) {
        return faceLine0(p, countdown, now).replace("  ·  ", " · ");
    }

    private static String faceLine0(String[] p, boolean countdown, long now) {
        boolean arsenal = p[1].contains("Arsenal"), f1 = "F1".equals(p[3]);
        long start = p.length > 5 ? parseLong(p[5]) : 0;
        String head = p[1].replace("Arsenal", "ARS");
        // 2026-11-23: club names pushed the kick-off time off the face: both sides as 3-letter codes ("NEW vs ARS").
        if (arsenal) head = teamCodes(p[1]);
        if (f1) head = shortRace(head);
        if ("STUMPS".equals(p[0]) && p[2].matches(".*\\d.*")) {
            String score = p[2].replaceAll("\\s*\\([^)]*\\)", "");
            String day = p.length > 6 && !p[6].isEmpty() ? "Day " + p[6] : "Play";
            String line = score + " · " + day + " in ^1";
            // With "^1" as "16h"; a Test's later innings leave no room for the countdown.
            return countdown && line.replace("  ·  ", " · ").length() + 1 <= 33 ? line : score + "  ·  Stumps";
        }
        if ("PLAY".equals(p[0]) && p[2].matches(".*\\d.*")) {
            // Play is due again; the score is the last one we had until the feed is reached.
            String score = p[2].replaceAll("\\s*\\([^)]*\\)", "");
            return score + "  ·  " + (p.length > 6 && !p[6].isEmpty() ? "Day " + p[6] + " on" : "play on");
        }
        if ("LIVE".equals(p[0]) && p[2].matches(".*\\d.*") && !arsenal) {
            // Live cricket: just the scores, without the overs; a Test's second innings needs LIVE's room.
            String scores = p[2].replaceAll("\\s*\\([^)]*\\)", "");
            return scores.contains("&") ? scores : "LIVE  ·  " + scores;
        }
        if ("LIVE".equals(p[0])) return head + "  ·  LIVE";
        if (f1 && p[2].startsWith("Podium")) {
            // The last race: where the Ferraris finished ("Brazilian · LEC P8 · HAM P9"), else the winner.
            int fe = p[2].indexOf("Ferrari ");
            String ferrari = fe < 0 ? "P1 " + p[2].substring(7).split(" ")[0] : p[2].substring(fe + 8).replace(", ", "  ·  ");
            return head + "  ·  " + ferrari;
        }
        if (start == 0 && arsenal) return head + "  ·  " + p[2].split("  ·  ")[0]; // "Won": the day isn't needed
        String session = f1 && p[2].contains("  ·  ") ? p[2].substring(p[2].lastIndexOf("  ·  ") + 5) : "";
        session = session.replace("Sprint Shootout", "Sprint Q").replace("Qualifying", "Quali");
        if (countdown) return head + "  ·  " + (session.isEmpty() ? "" : session + " ") + "in ^1";
        // "Sat 21 Nov 23:00": this week the day says it ("Sat 23:00"); further off, the date ("21 Nov 23:00").
        String when = p[2].replace("Today ", "").replace("Tomorrow ", "Tmrw ").replaceAll("  ·  Premier League$", "");
        when = start > 0 && start - now < 6 * 24 * 3600_000L
                ? when.replaceAll("^([A-Z][a-z]{2}) \\d{1,2} [A-Z][a-z]{2} ", "$1 ")
                : when.replaceAll("^[A-Z][a-z]{2} (\\d{1,2} [A-Z][a-z]{2}) ", "$1 ");
        if (f1) when = when.replace("Qualifying", "Quali").replace("Sprint Shootout", "Sprint Q");
        return when.isEmpty() ? head : head + "  ·  " + when;
    }

    /** "Saudi Arabian GP" -> "Saudi": the flag says it's F1, and the race's name has to share the line. */
    static String shortRace(String s) {
        return s.replace(" GP", "").replace("Saudi Arabian", "Saudi").replace("Emilia Romagna", "Imola")
                .replace("United States", "USA").replace("Mexico City", "Mexico").replace("São Paulo", "Brazil")
                .replace("Sao Paulo", "Brazil");
    }

    /** "Newcastle United v Arsenal" -> "NEW vs ARS", "Newcastle United 1–2 Arsenal" -> "NEW 1–2 ARS". */
    static String teamCodes(String head) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.+?) (v|\\d+\\s*[–-]\\s*\\d+) (.+)$").matcher(head.trim());
        if (!m.matches()) return head.replace("Arsenal", "ARS");
        String mid = m.group(2).equals("v") ? "vs" : m.group(2).replace("-", "–").replace(" ", "");
        return teamCode(m.group(1)) + " " + mid + " " + teamCode(m.group(3));
    }

    private static final java.util.Map<String, String> CODES = new java.util.HashMap<>();
    static {
        String[] pairs = {
            // Premier League (and recent members)
            "Arsenal=ARS", "Aston Villa=AVL", "Bournemouth=BOU", "AFC Bournemouth=BOU", "Brentford=BRE", "Brighton & Hove Albion=BHA",
            "Brighton=BHA", "Burnley=BUR", "Chelsea=CHE", "Crystal Palace=CRY", "Everton=EVE", "Fulham=FUL", "Leeds United=LEE",
            "Liverpool=LIV", "Manchester City=MCI", "Manchester United=MUN", "Newcastle United=NEW", "Nottingham Forest=NFO",
            "Sunderland=SUN", "Tottenham Hotspur=TOT", "West Ham United=WHU", "Wolverhampton Wanderers=WOL", "Wolves=WOL",
            "Leicester City=LEI", "Southampton=SOU", "Ipswich Town=IPS", "Hull City=HUL", "Coventry City=COV", "Sheffield United=SHU",
            "Luton Town=LUT", "Watford=WAT", "Norwich City=NOR", "Middlesbrough=MID", "Stoke City=STK", "West Bromwich Albion=WBA",
            // Europe
            "Borussia Dortmund=BVB", "Bayern Munich=BAY", "FC Bayern München=BAY", "Bayer Leverkusen=LEV", "RB Leipzig=RBL",
            "Eintracht Frankfurt=SGE", "VfB Stuttgart=VFB", "Real Madrid=RMA", "Barcelona=BAR", "FC Barcelona=BAR",
            "Atlético Madrid=ATM", "Atletico Madrid=ATM", "Athletic Club=ATH", "Villarreal=VIL", "Real Sociedad=RSO", "Sevilla=SEV",
            "Paris Saint-Germain=PSG", "Marseille=OM", "Olympique de Marseille=OM", "Monaco=MON", "AS Monaco=MON", "Lille=LIL", "Lyon=OL",
            "Inter Milan=INT", "Internazionale=INT", "Inter=INT", "AC Milan=MIL", "Juventus=JUV", "Napoli=NAP", "Atalanta=ATA",
            "Roma=ROM", "AS Roma=ROM", "Lazio=LAZ", "Benfica=BEN", "FC Porto=POR", "Porto=POR", "Sporting CP=SCP", "Sporting Lisbon=SCP",
            "Ajax=AJA", "PSV Eindhoven=PSV", "PSV=PSV", "Feyenoord=FEY", "Club Brugge=BRU", "Union St.-Gilloise=USG", "Galatasaray=GAL",
            "Fenerbahce=FEN", "Celtic=CEL", "Rangers=RAN", "Olympiacos=OLY", "Slavia Prague=SLA", "Bodo/Glimt=BOD", "Bodø/Glimt=BOD",
            "FC Copenhagen=FCK", "Copenhagen=FCK", "Qarabag=QAR", "Kairat Almaty=KAI", "Pafos=PAF", "Shakhtar Donetsk=SHA",
            "Red Bull Salzburg=SAL", "Young Boys=YB", "Dinamo Zagreb=DZG", "Sparta Prague=SPA", "Sturm Graz=STU"};
        for (String p : pairs) { int e = p.indexOf('='); CODES.put(p.substring(0, e), p.substring(e + 1)); }
    }

    /** A club's 3-letter code; unknown: the first 3 letters of its main word ("Hellas Verona" -> "VER"). */
    static String teamCode(String name) {
        String n = name.trim();
        String c = CODES.get(n);
        if (c != null) return c;
        String core = n.replaceAll("\\b(FC|AFC|CF|SC|AC|AS|SS|SSC|SL|CD|RC|UD|SD|Club|Real|Borussia|Olympique|Sporting|Hellas|VfL|TSG|1\\.)\\b", " ")
                .replaceAll("\\b(United|City|Town|Hotspur|Wanderers|Albion|Athletic|Rovers)\\b", " ").trim();
        if (core.isEmpty()) core = n;
        String word = core.split("\\s+")[0].replaceAll("[^\\p{L}]", "");
        if (word.length() < 3) word = core.replaceAll("[^\\p{L}]", "");
        return word.length() >= 3 ? word.substring(0, 3).toUpperCase(java.util.Locale.ROOT) : n.toUpperCase(java.util.Locale.ROOT);
    }

    /** "Newcastle United" -> "Newcastle", "Brighton & Hove Albion" -> "Brighton": room for the time. */
    static String shortTeams(String s) {
        return s.replace("Brighton & Hove Albion", "Brighton").replace("Wolverhampton Wanderers", "Wolves")
                .replace("Hull City", "Hull").replace("Tottenham Hotspur", "Spurs")
                .replace("Nottingham Forest", "Forest")
                .replaceAll(" (United|City|Albion|Wanderers|Hotspur|FC)\\b", "");
    }

    /**
     * The sport's icon (white; the face tints it): from the short title, "F1", "ARS" or a cricket side; in race
     * week, the Grand Prix's circuit (ic_track_<circuitId>) when it's one we have.
     */
    private MonochromaticImage icon(String[] p) {
        String shortTitle = p[3];
        int res = "F1".equals(shortTitle) ? R.drawable.ic_f1 : "ARS".equals(shortTitle) ? R.drawable.ic_football : R.drawable.ic_cricket;
        int track = trackIcon(this, p.length > 7 ? p[7] : "", "ic_track_");
        if (track != 0) res = track;
        return new MonochromaticImage.Builder(android.graphics.drawable.Icon.createWithResource(this, res)).build();
    }

    /** The drawable "<prefix><circuitId>" (ic_track_vegas, track_vegas), or 0 when there's none. */
    static int trackIcon(android.content.Context c, String circuit, String prefix) {
        if (circuit == null || circuit.isEmpty() || !circuit.matches("[a-z_]+")) return 0;
        return c.getResources().getIdentifier(prefix + circuit, "drawable", c.getPackageName());
    }

    /**
     * What the face's rail under the pill shows (RailComplication reads it; no going online): how close the next
     * start is, a live match, or a Test's days. It's asked to redraw only when that changes.
     */
    private void rail(Feed.Item h, long now) {
        boolean test = h.sport == Feed.CRICKET && h.end - h.time > 24 * 3600_000L;
        long start = h.live || h.past ? 0 : h.time;
        int day = h.live && test ? Math.max(1, Math.min(5, h.day > 0 ? h.day : 1)) : 0;
        SharedPreferences r = getSharedPreferences("rail", MODE_PRIVATE);
        if (r.getLong("start", -1) == start && r.getBoolean("live", false) == h.live && r.getInt("day", -1) == day) return;
        r.edit().putLong("start", start).putBoolean("live", h.live).putInt("day", day).apply();
        ComplicationDataSourceUpdateRequester.create(this, new android.content.ComponentName(this, RailComplication.class)).requestUpdateAll();
    }

    /** "2h 14m" (or [template] with ^1 in its place) to [start], kept current by the watch itself. */
    private static ComplicationText countdown(long start, String template, boolean wholeUnits) {
        TimeDifferenceComplicationText.Builder b = new TimeDifferenceComplicationText.Builder(
                wholeUnits ? TimeDifferenceStyle.SHORT_SINGLE_UNIT : TimeDifferenceStyle.SHORT_DUAL_UNIT, new CountDownTimeReference(java.time.Instant.ofEpochMilli(start)))
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
