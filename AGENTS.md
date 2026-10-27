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
| `Shelf.java` | "New and top rated on your apps": TMDB queries (`LANGUAGES`, `SERVICES`, strictly the last two weeks, no animation/kids) building a pool of up to `POOL` titles, newest first (`NEWEST`, by each title's `released` day), dealt into at most `PAGES` (4) pages of 6 by `pages()` (page 1 the newest; 3 Telugu, 2 English, 1 Hindi by `places()` while a language lasts, the newest of any filling in); wide title cards (`PosterView`, 16:9, TMDB backdrops with the title lettered on when there is one); `SpotlightView` (the focused title's name, details and story); `ShelfButton` (+/− ‹ › ↻ in the head); shelf layout |
| `Sports.java` | Flip card: Arsenal and India (TheSportsDB, key `123`), F1 (Jolpica); faces `MatchTile`, `RaceTile`; `CardView` flips on Left/Right |
| `DockView.java` | App grid (rows of `columns`), drawn covers (`BRANDS` colours), focus routing inside the grid |
| `ArtView.java` | Base for focusable pictures: fine ring, colour change, nothing grows |
| `FadeFrame.java` | The page: fades children out at the screen's bottom edge |
| `Net.java` | HTTP GET with caps, atomic file writes, picture decode/crop/cache |
| `ControlCenter.java` | The Control Center panel (button over the sports card; Back closes): StandBy, Wi-Fi, Bluetooth (opens its settings: the remote uses Bluetooth), Inputs, Settings, round buttons (voice, Sony's Display & Sound, sleep timer `Sleeper`, picture off, Cast, `quick_input` HDMI port, screensaver, restart on a second press), picture profiles (through `Hotkeys.running`), volume (Sony API → Streambar over CEC), recent apps (usage stats) with each one's memory and a × to close it, Close all (running launchable apps minus `close_all_keep`) |
| `Adb.java` | A tiny adb client to the TV's own debugging service (127.0.0.1:5555, RSA auth, `shell:`): the only way an app may `am force-stop` others or read `dumpsys meminfo` here. Its key lives in the app's files; the TV asked once to allow it |
| `Hotkeys.java` | Picture hotkeys: an accessibility service that takes the remote's 0-9 in any app and applies `hotkey_N` profiles from `config.xml` through Sony's picture API at `http://127.0.0.1/sony/video` (IP control, `bravia.psk`); a small fading card says what happened. Turned on by `hotkeys.sh`. A profile's `motionflow` (not in the API, and not kept per picture mode on this TV) is set by working Sony's menu: IRCC button presses (gear, Picture settings, Motion, Motionflow's value strip), with accessibility only reading the highlighted row; it acts only when the menu names the input the TV says is playing (avContent getPlayingContentInfo: an app gives error 7, "Apps"; a CEC device is read by its port, "HDMI 4"). Settings an input lacks (`isAvailable` false, e.g. lightSensor on HDMI) are left out of the batch, since one refusal fails it all. Smoothness/Clearness ignore accessibility and stay set by hand. Its card times its own fade: the TV has animations off (scale 0), which ends `ValueAnimator`s at once |

Resources: `res/values/config.xml` (dock order, hidden apps, weather place, columns, effects level, picture hotkeys),
`res/values/strings.xml`, `res/layout/activity_main.xml` (slot positions in dp), `res/font/` (Open Runde),
`res/drawable/ph_*.xml` (Phosphor icons).

## Rules this design keeps

- The Control Center's button sits over the sports card (`quickSlot`, built in `MainActivity.onCreate`);
  Up from the card reaches it. Inputs and Settings live in the panel.
- The shelf starts folded: just its head and a + button, on the line over the dock (no posters, story or
  backdrop). + opens it, − folds it again (`MainActivity.foldShelf`, `ShelfView.setCollapsed`).
- Only titles out in the last two weeks, never older. Pages run newest first (page 1 the most recent);
  ‹ › page through them. ↻ refetches and goes to page 1 if TMDB has something new, else to the next page
  (`MainActivity.loadShelf(true)`, `usePool`, `showSet`). Up from a poster reaches those buttons, Up again
  the sports card.
- Focus is a colour change plus a 1 dp ring. Nothing grows, lifts or slides (the sports card's flip and
  the page lift for lower dock rows are the only motions, both on purpose).
- The screen is 960 x 540 dp. Positions are in `activity_main.xml`.
- Performance budget: the TV's GPU has ~16 ms a frame. The scene draws at half resolution and the glass at
  a quarter; full resolution dropped most frames. Measure with gfxinfo after visual changes.
- No network or disk on the main thread; every source caches to `cacheDir` and refreshes on a timer only
  while the launcher is in front.
- Comments explain what and why in plain words; match the existing style.
- This TV has system animations switched off: don't rely on `ValueAnimator` for anything that must be seen
  (use `Ui.Tween` / `Ui.now()`). Apps can't close other apps here (`killBackgroundProcesses` is refused);
  `quiet-apps.sh` restricts the streaming apps' background running instead (live cricket dropped frames
  when they idled in the 3 GB of memory).

## Data sources

TMDB (token), TheSportsDB (free test key `123`), Jolpica-F1 (no key), Open-Meteo (no key). Credits are in
`NOTICE.md`; keep `licenses/` intact (the licences require it).
