# Sports: India cricket, Arsenal and Formula 1 on the user's OnePlus Watch 3 (Wear OS 5)

An app (`MainActivity`: live, next and last for each, in the watch face's colours) and a watch-face widget
(`NextUpComplication`, LONG_TEXT and SHORT_TEXT, every 15 minutes, tap opens the app). Data in `Feed.java`:
ESPN's public cricket scoreboard (India sides; properly live), TheSportsDB free key 123 (Arsenal 133604:
next and last; no live score on the free tier, so "On now" from kick-off for two hours), Jolpica-F1 (next
race; last race's podium and Ferraris). The widget's pick (`Feed.headline`, main India side only): India
live; else the soonest event in 24 hours; else anything else live; else the next further on; else the latest result. The Gunners x Scuderia face (`~/Afterglow/watchface`) uses it in its
bottom slot by default; a face keeps a slot's earlier choice across updates, so reinstall it to reset.

    ./build.sh                              # -> build/watchsports.apk
    adb -s <watch> push build/watchsports.apk /data/local/tmp/ws.apk   # streamed installs fail over the watch's Wi-Fi
    adb -s <watch> shell pm install -r /data/local/tmp/ws.apk && adb -s <watch> shell rm /data/local/tmp/ws.apk

Each sport is tried twice, and on failure its last good answer (cache dir, 12 h, minus fixtures since started
and old live scores) stands in: the watch reaches the internet through the phone and a request can time out.
On the face's line Arsenal is "ARS"; Arsenal fixtures use "vs", and Hull City shortens to "Hull" so the team and
local kick-off time fit cleanly together.

Plain Java; the only library is androidx.wear.watchface:watchface-complications-data-source. Feed.java runs on
a computer too (org.json jar) to check the feeds against live data.


The watch-face widget shows only India's main men's side (ESPN displayName exactly "India"): `Feed.headline` drops
India A, women and under-19s (the user's request, 2026-11-03). The app itself still lists every India side.

Two sections (2026-11-07): `MainActivity` organizes content into "MY FAVOURITES" (India cricket, Arsenal, Formula 1)
and "OTHER SCORES" (capturing live and top recent matches across international cricket, football/soccer, and tennis
from ESPN scoreboard feeds). The watch-face complication continues to strictly feature the user's favourites.

Match details (2026-11-04, v1.1): tapping a card opens `DetailActivity`, which fetches `Details.load(sport, ref)` only
then (the widget never does): cricket from ESPN's match summary (`site.web.api.espn.com/.../cricket/<league>/summary?event=<id>`:
toss/result, venue, each innings' batters (runs, balls, * not out) and bowlers O-M-R-W from `rosters[].roster[].linescores`
by period), Arsenal from `lookupevent.php` + the Premier League table (top 4 + Arsenal), F1 the weekend's sessions
(next) or the top 10 + Ferraris and fastest lap (last), plus the championship top 3 + Ferraris. `Feed.Item.ref`
carries "league/event", the TheSportsDB event id, or "next"/"last". A live match reloads every minute while on
screen only. Checked on a computer against live feeds (org.json jar) before installing.
The widget (3 Nov) only goes online when live, within 20 min of a start, or every 3 h (battery).
v9 (2026-11-19): live India cricket carries `end` (start + its format's length; the format from ESPN's
`class.eventType`, since league names are series like "India tour of New Zealand 2026/27") so a cached live
match is dropped only once surely over. At stumps (`fullStatus.type` id 112) it carries `resume` (first day's
start + N days − 30 min): the widget shows "STUMPS" and checks every 3 h until near `resume` instead of every
15 min all night (battery). `Feed.parseCricket(json, out)` is testable; `ESPN_SNAPSHOT=<file>` runs
`testStumpsSnapshot` against a saved scoreboard response.
Installed on the watch 2026-11-19 22:14 (it had been on the 12 Nov v8 until then). Pairing: Wireless debugging →
Pair new device, `adb pair <ip:pairport> <code>`, connect port from `adb mdns services`. Turn debugging off after.
v10 (2026-11-19, 1.2): the app opens on a hero card (the widget's pick: team badges, the score or a live
countdown big, "Play resumes in" at stumps); the crown scrolls; the time sits at the top. The widget's
upcoming line is a TimeDifferenceComplicationText ("in 2h 14m", "play in 3h 51m" at stumps) that the watch
counts down itself (no wake-ups). Battery: opening the app marks the cache fresh (`appAt`), so the widget no
longer re-fetches everything straight after; between the 3-hourly full checks only the live/next sport goes
online (`Feed.load(cache, false, only)`), the rest from cache. Installed 23:07 and checked on the watch.
v11 (2026-11-19, 1.3; built, not yet installed): the widget sends a sport icon (`art/icons.py` writes
`res/drawable/ic_{cricket,football,f1}.xml`; the face tints it) and `NextUpComplication.faceLine()` writes a
line made for the face's 248 px box at 17 px after the icon (~31 characters): single-space " · ", F1 without
"GP" (`shortRace`), results without the day, "Sat 23:00" this week / "5 Dec 23:00" later, stumps "Day N in 16h"
(whole hours; "· Stumps" when both innings leave no room). Tested in `FeedTest.testFaceLines`. Needs face v15.
v12 (2026-11-20, 1.4; built, not yet installed): race week (next GP within 7 days) the widget's icon is the
circuit (`res/drawable/ic_track_<circuitId>.xml`; the app's F1 hero card draws `track_<circuitId>` large);
`art/icons.py` makes both from `art/circuits/*.geojson` (bacinger/f1-circuits, MIT; Jolpica circuitId ->
file in `icons.CIRCUITS`; add a new venue there). `RailComplication` (RANGED_VALUE, never online, UPDATE_PERIOD 0)
feeds the face's rail: 0-100 = how near the next start is over its last day, 100 = live, 100 + day = a live
Test; NextUpComplication writes prefs "rail" and asks it to redraw only when that changes. Needs face v16.
v13 (2026-11-20, 1.5): (1) stumps no longer sticks: a check that reached no feed doesn't count as done
(`Feed.lastFetched`), no network means no attempt (`hasNetwork`), and a Test at stumps counts as live from 20 min
before the next day's start (`liveNow`); once that start has passed without news the line reads "NZ 243/5 ·
Day 2 on" (label PLAY) instead of STUMPS. (2) Monthly face photos: `wallpapers/make_month.py` picks 8 photos per
theme from Commons (sources + filters at its top), processes them like the face's walls (date band per design
ink: DARK_INK must match watchface date_contrast), and publishes `wallpapers/<YYYY-MM>/` + `current.json`;
in the afterglow repo a GitHub Action (`wallpapers/workflow/watch-wallpapers.yml`) runs it on the 1st. On the
watch, `Wallpapers.refresh` downloads a newer set (<= every 6 h until it arrives; also when the app opens) and
`WallpaperComplication` (PHOTO_IMAGE, today + tomorrow-from-midnight timeline, every 6 h) feeds face slot 6.
Publish with `./sync_to_afterglow.sh "msg"`: it keeps the Action's photo sets on GitHub as they are.
v15 (2026-11-20, installed and checked on the watch 07:42): the photo widget's manifest type must be the old
name LARGE_IMAGE (PHOTO_IMAGE in SUPPORTED_TYPES matches nothing: the face's default never bound and the picker
showed only "Empty"); the rail's data carries text, title and a tap action like the FPL dial's (a bare value
never drew). Both log "WatchSports" lines per request. Seen on the watch: today's downloaded photo, the rail's
Test-day dashes (2 of 5), "LIVE · NZ 368 · IND 0/0"; tapping the pill still opens the app.

