# Afterglow: notes for AI assistants and future you

An Android TV home screen (launcher). Plain Java, no AndroidX or other libraries, one Activity, every view
drawn in code. Package `com.balaji.tvhome` (keep it: the TV only accepts updates under the same package
and signing key).

## Build, install, look

    ./build.sh                                  # -> build/tvhome.apk (Gradle 8.7 wrapper, Java 17)
    ./install.sh                                # adb push + pm install -r (plain `adb install` can hang on TVs)
    ./activate.sh                               # make it the default Home
    adb exec-out screencap -p > shot.png        # see the result; always check on the TV
    adb shell dumpsys gfxinfo com.balaji.tvhome # frame stats; reset first with `... gfxinfo <pkg> reset`

Needs `launcher/tmdb.properties` with `tmdb.token=...` (TMDB read access token) for the shelf; git ignores
it. Without it the shelf stays empty and everything else works.

## Where things are (launcher/app/src/main/java/com/balaji/tvhome/)

| File | What it is |
|---|---|
| `MainActivity.java` | Wires everything: slots, focus routing, page lift, data loading and refresh timers, the clock tick |
| `Ui.java` | Design tokens (palette, type sizes, spacing, radii, ease), fonts, text paints, `Tween`, glyph masks |
| `Colors.java` | OKLab/OKLCH maths, `even()` (brand colour at one lightness), `vivid()` (an image's colour) |
| `Backdrop.java` | The scene: aurora ribbons, stars, hero backdrop, glow; `SceneView` (half resolution); `Glass` (frosted, quarter resolution, RenderEffect on Android 12+) |
| `BentoView.java` | Clock card: dial, greeting/time, sky tile, sentence line |
| `Weather.java` | Open-Meteo forecast, WMO codes to words and Phosphor glyphs, cache |
| `Shelf.java` | "New and top rated on your apps": TMDB queries (`LANGUAGES`, `SERVICES`, no animation/kids; two weeks, reaching back through `WINDOWS` to 90 days for a language short of its share, and `APP_WINDOWS` to a year for an app with no titles) building a pool of up to `POOL` titles with the apps taking turns (`spread()`), dealt into pages of 6 by `deal()` (every page 3 Telugu, 2 English, 1 Hindi by `places()`; titles not shown before first; pages kept in `home-shelf-pages.json`); wide title cards (`PosterView`, 16:9, TMDB backdrops with the title lettered on when there is one); `SpotlightView` (the focused title's name, details and story); `ShelfButton` (+/− ‹ › ↻ in the head); shelf layout |
| `Sports.java` | Flip card: Arsenal and India (TheSportsDB, key `123`), F1 (Jolpica); faces `MatchTile`, `RaceTile`; `CardView` flips on Left/Right |
| `DockView.java` | App grid (rows of `columns`), drawn covers (`BRANDS` colours), focus routing inside the grid |
| `ArtView.java` | Base for focusable pictures: fine ring, colour change, nothing grows |
| `FadeFrame.java` | The page: fades children out at the screen's bottom edge |
| `Net.java` | HTTP GET with caps, atomic file writes, picture decode/crop/cache |

Resources: `res/values/config.xml` (dock order, hidden apps, weather place, columns, effects level),
`res/values/strings.xml`, `res/layout/activity_main.xml` (slot positions in dp), `res/font/` (Open Runde),
`res/drawable/ph_*.xml` (Phosphor icons).

## Rules this design keeps

- Inputs and Settings sit over the sports card (`quickSlot`, built in `MainActivity.onCreate`; Inputs
  sends `com.android.tv.action.VIEW_INPUTS`, which opens Sony's Inputs panel). Up from the card reaches them.
- The shelf starts folded: just its head and a + button, on the line over the dock (no posters, story or
  backdrop). + opens it, − folds it again (`MainActivity.foldShelf`, `ShelfView.setCollapsed`).
- ↻ refetches and deals a new page of titles not shown before onto the end; ‹ › page back and forth
  through the last 4 pages (`MainActivity.loadShelf(true)`, `dealSet`, `turnTo`). Up from a poster reaches
  those buttons, Up again the sports card.
- Focus is a colour change plus a 1 dp ring. Nothing grows, lifts or slides (the sports card's flip and
  the page lift for lower dock rows are the only motions, both on purpose).
- The screen is 960 x 540 dp. Positions are in `activity_main.xml`.
- Performance budget: the TV's GPU has ~16 ms a frame. The scene draws at half resolution and the glass at
  a quarter; full resolution dropped most frames. Measure with gfxinfo after visual changes.
- No network or disk on the main thread; every source caches to `cacheDir` and refreshes on a timer only
  while the launcher is in front.
- Comments explain what and why in plain words; match the existing style.

## Data sources

TMDB (token), TheSportsDB (free test key `123`), Jolpica-F1 (no key), Open-Meteo (no key). Credits are in
`NOTICE.md`; keep `licenses/` intact (the licences require it).
