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
