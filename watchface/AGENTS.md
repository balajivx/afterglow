# Scuderia Telemetry (package gunnersscuderia): a watch face for the user's OnePlus Watch 3 (Wear OS 5, OPWWE251)

Watch Face Format v1 (no code). `res/raw/watchface.xml` is GENERATED: edit `art/make_face.py` (layout) and
`art/themes.py` (the three themes' colours), then run `python3 art/make_bg.py app/src/main/res/drawable-nodpi`
(overlay_<theme>.png and bg.png), `python3 art/make_walls.py app/src/main/res/drawable-nodpi` (24 wallpapers),
and `python3 art/make_face.py app/src/main/res/raw/watchface.xml`.
A theme a day matching the photo: photo = `[DAY_OF_YEAR] % 24` (24 in `art/source`, `art/make_walls.py`, credits
`art/CREDITS.tsv`), theme = `[DAY_OF_YEAR] % 3` (arsenal, ferrari, space; 8 wallpapers each), so they always agree.

Design and Layout:
- **24 daily designs**: eight distinct time treatments and bezel styles per theme, matched in order to each theme's eight wallpapers. `floor(([DAY_OF_YEAR] % 24) / 3)` chooses the design; `[DAY_OF_YEAR] % 3` chooses its theme.
- **Full edge-to-edge layout**: photos fill the complete circular display, with theme-specific bezel art and strong contrast behind the time.
- **Date hierarchy**: full weekday above a larger day-and-month line.
- **Health dials**: battery, steps, and calorie gauges use 90px plates and 28px bold values.
- **Heart rate**: OnePlus Health complication includes BPM units.
- **Sports widget**: slot 2 (`~/Afterglow/watch-sports`) stays at the bottom and presents the match plus its local kick-off time.
- **Ambient mode**: time and date only; no seconds.
- `art/make_preview.py` renders eight designs per theme into `art/design-previews/` for visual review.

What this watch does differently: an alpha `Transform` driven by [SECOND] is applied once, not every second, so
the shift lights are a `Condition` each (re-evaluated every second); curved text inside a ComplicationSlot is
not drawn; `TimeText` needs its own x/y/width/height; a face update keeps each slot's earlier data source and
can mix them up, so after changing slots uninstall and reinstall the face. Fonts: Outfit (OFL), static bold and
semibold instances (the variable file defaults to Thin). Moving parts tick at most once a second, only with the
screen on; ambient shows time and date only.

    ./build.sh                                   # -> build/watchface.apk
    adb pair <ip:port> <code>; adb connect <ip:port>   # watch: Developer options -> Wireless debugging
    adb install -r build/watchface.apk
    adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation set-watchface --es watchFaceId com.balaji.watchface.gunnersscuderia

The watch can't be woken over adb: screenshots need the user to tap it (and HOME opens the app list).
Battery changes made on the watch, with undo: ~/Afterglow/backups/oneplus-watch3/changes.txt

Graphics (cheap: at most once a second, only while the screen is on, hidden in ambient): a race-lap seconds ring
(yellow arc to [SECOND]*6 with a red "car" dot on a rotating Group), and battery and step-goal bars under the values.

Testing every theme on the watch in one go: copy the project, give it applicationId ...gunnersscuderia.test, replace
`[DAY_OF_YEAR]` with `floor([SECOND] / 2.5)` in the generated XML (all 24 photos and 4 themes in a minute), install
with `adb push` + `pm install` (streamed installs fail over the watch's Wi-Fi), set it, screenshot while sending
KEYCODE_WAKEUP, then set the real face back and uninstall the test copy. Battery % verified on all 24 (2026-11-03).

**Rings follow OnePlus Health (2026-11-04).** The face's circles use OnePlus Health's Daily activity goals, which
differ from Wear OS's. The step lights and steps dial are drawn inside slot 3 and the calories dial inside slot 0,
both RANGED_VALUE from OHealth's Step/CaloriesComplicationService; progress =
`clamp(VALUE / MAX * 100, 0, 100)`. On this watch: **no `max()`** (the whole expression fails silently); **a
Condition can't read [COMPLICATION.*]** (lights use an alpha Transform instead); **a widget is only drawn inside
its BoundingBox**. A changed slot needs uninstall + reinstall, including the new 90px calorie dial and relocated
heart-rate and sports slots.

**Sports pill v15 (2026-11-19, built, not yet installed).** Slot 2 draws the widget's monochrome icon
(`[COMPLICATION.MONOCHROMATIC_IMAGE]`, PartImage x 38 in the slot = screen x 93-113, tinted the day's `major`)
and the text in x 63-311 at 17 px; the plate's border is the day's `major` too. The pill runs past the round
screen (visible from x ~78 at its middle, ~90 at its foot), so nothing goes left of x ~90. `art/make_preview.py`
draws the same (icons from `~/Afterglow/watch-sports/art/icons.py`); `art/make_showcase.py art/showcase` renders
six designs with real widget lines and flags any line the slot would cut. v14 backup:
`~/Afterglow/backups/watchface-v14-2026-11-19/` (make_face.py, make_preview.py, watchface.xml, watchface.apk).

**Rail v16 (2026-11-20, built, not yet installed).** Slot 5 (not customizable, x 120-330, y 381, after slot 2 so
the pill's plate doesn't cover it): RailComplication's RANGED_VALUE as lit dashes along the pill's top, ten for
"how near the next start is" (0-100), five for a live Test's day (101-105); lit/dim and the two modes switch by
alpha arithmetic, as the step lights do. Icon box now 22 px (x 37, y 5). `art/make_concepts.py` keeps the
concept sheet (circuit, rhythm, fuse); user does not use always-on display, so no ambient work.

**v19 (2026-11-20, installed and checked).** Slot 6: full-screen PHOTO_IMAGE from watch-sports
WallpaperComplication (24 new photos a month, downloaded on the watch), drawn over the built-in photo; slot 5
(the rail) now x 120, y 378, 210 x 10. Both are customizable: the watch's debug tool can't set fixed slots, and a
reinstall keeps old slot choices. If a new slot stays empty after an update: switch to a stock face, uninstall
this one, install, set it (a fresh favourite applies the defaults). Taps on the pill and rings still reach their
own slots over the photo slot. Backup of v14: ~/Afterglow/backups/watchface-v14-2026-11-19/.

**Clean Feed (v20, 2026-11-20): the face now.** Chosen by the user after three rounds of redesigns (gallery:
https://claude.ai/artifact/1Nuxg7dLVjfANqS5DEUmqb; mock-ups art/redesign/make_redesign.py, make_broadcast2.py,
make_broadcast3.py). Generator: `art/make_face_cf.py` (build.sh calls it; the classic generator art/make_face.py and
art/make_walls_classic.py stay for rollback, v14 APK in ~/Afterglow/backups/watchface-v14-2026-11-19/). Layout: photo of
the day (built-in, or monthly via slot 6) under `cf_scrim` (art/make_cleanfeed_art.py); "FRIDAY  20 NOV" (weekday
right-aligned to x 218 and "[DAY] MON" left-aligned from 232, upper-case by [DAY_OF_WEEK]/[MONTH] conditions, 1 =
Sunday: verify on the watch); time in Barlow Condensed SemiBold 150 (hour END to x 212, colon centred, minutes in the
day's colour START at 238); one row of readings at y 282: steps (slot 3), kcal (0), BPM (1), battery, FPL (4), labels
under; the TV caption bar (round 1): black bar + `cf_tab` tinted the day's colour, sport icon, slot 2 text; the rail
(slot 5) under it. User's preference learned: simple over gimmicky. Photos are no longer smoked or ink-banded
(make_walls.py, and watchsports make_month.py REV 2).

**v23 (2026-11-20): bigger text, user said v20 fonts were too small.** Date 34 px (weekday END box x 20 w 196, "[DAY] MON"
from x 234, y 76); readings values 34 px, labels 15 px, columns at x 69/147/225/303/381 (W 78, values y 266, labels y 311).
Sports caption stays 23 px (user likes it).
**v25 (2026-11-20): date plate + time shadow.** The date sits on a black plate (x 50-394, y 84-128) with a slanted
day's-colour tab (`cf_tab_d`, 32 x 44), mirroring the caption bar; weekday white 32 px, "[DAY] MON" in the day's colour.
The time has a hard drop shadow (#b4000000, +3/+4 px, hidden in ambient) so red minutes read on red photos.
**v26 (2026-11-20): time outline.** Under the time, eight #6e000000 copies 2 px around each part (a thin outline) plus the
drop shadow; fixes red minutes on red photos and yellow on DHL yellow. `art/check_all_photos.py MONTHLY_DIR OUT.png`
(set OUTLINE="[(-2,0),(2,0),(0,-2),(0,2),(-2,-2),(2,2),(-2,2),(2,-2)]" SA=110) draws the face over all 24 built-in +
24 monthly photos (monthly ones copied off the watch: `run-as com.balaji.watchsports tar cf - -C files walls`).

**Git (2026-11-20).** This folder is a git repo; generated images, the face XML and renders are ignored (build.sh
regenerates them). `./sync_to_afterglow.sh "message"` publishes HEAD to github.com/balajivx/afterglow under watchface/
(public repo; the photos are CC-licensed, credited in art/CREDITS.tsv; fonts in art/FONTS.md).
