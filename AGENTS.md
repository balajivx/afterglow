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
| `Shelf.java` | "New and top rated on your apps": TMDB queries (`LANGUAGES`, `SERVICES`, two-week window, no animation/kids), poster views, shelf layout (6 posters) |
| `Sports.java` | Flip card: Arsenal and India (TheSportsDB, key `123`), F1 (Jolpica); faces `MatchTile`, `RaceTile`; `CardView` flips on Left/Right |
| `DockView.java` | App grid (rows of `columns`), drawn covers (`BRANDS` colours), focus routing inside the grid |
| `ArtView.java` | Base for focusable pictures: fine ring, colour change, nothing grows |
| `FadeFrame.java` | The page: fades children out at the screen's bottom edge |
| `Net.java` | HTTP GET with caps, atomic file writes, picture decode/crop/cache |

Resources: `res/values/config.xml` (dock order, hidden apps, weather place, columns, effects level),
`res/values/strings.xml`, `res/layout/activity_main.xml` (slot positions in dp), `res/font/` (Open Runde),
`res/drawable/ph_*.xml` (Phosphor icons).

## Rules this design keeps

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
