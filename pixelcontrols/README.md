# PixelControls 2.7

Adblock (system-wide, with Quick Settings tile) + Pro Enhance for photos + your existing
Low Signal / DSLR daemon controls.

## Install
Phone on USB, then on the Mac: `./install.sh` (add `--check` to run the on-device checks afterwards). It installs the
app, removes the old AA Switch app (its features are now in here), grants the adb-only permissions and starts the
helper daemon. Safe to re-run.
From source: `./build.sh` → `build/pixelcontrols.apk` (Gradle 8.7, AGP 8.5.2, JDK 17, no dependencies). Minimum
Android 10. Package name `com.pixelcontrols`.
targetSdk is 28 (Android 9) on purpose: an app targeting it may still switch Wi-Fi itself, which is what the
Android Auto wired/wireless switch needs without a helper (checked on the Pixel 9a, Android 17).

## Adblock
1. Open the app → turn on "Block ads, pop-ups & trackers" → accept Android's one-time VPN prompt.
2. Add the tile: swipe down twice → pencil → drag "Adblock" into place. Tap = on/off, long-press = settings.
   Also: long-press the app icon → "Toggle Adblock".
3. Optional, survives reboots: Settings → Network → VPN → PixelControls → Always-on VPN.

How it works: a local, DNS-only VPN. Only DNS lookups enter it (app traffic does not), so there is no
speed cost. Ad/tracker domains (HaGeZi Multi PRO, ~252k domains, bundled; "Update blocklist now" fetches the latest)
get 0.0.0.0; everything else resolves via your network's normal DNS.

Not getting blocked by apps/sites:
- Payment/banking apps (GPay, PhonePe, Paytm, BHIM, YONO, iMobile, HDFC, Axis) bypass the filter by default
  and can't see the VPN. Add any app under "Apps that bypass the filter".
- "Pause 10 minutes" (in app or notification) for a site with an ad-blocker wall.
- "Recently blocked" → tap a domain to allow it if something broke.

OTT and music apps:
- Turn on "Maximum OTT ad blocking". This activates per-app ad packs (59 servers) and blocks Google's video-ad servers.
- "OTT ad packs (per app)": switch a pack off if that app stops playing.

| App | What the pack blocks | Expect |
|---|---|---|
| Hotstar / JioHotstar | ads, adsmanager, videoads.hotstar.com, hesads, jioads, JioCinema ad trackers, analytics | Most VOD ads gone; live-sports ads stitched into the stream stay |
| Spotify | ads-fa/ads.spotify.com, AdsWizz, ad event trackers, analytics | Banner/display ads gone; some audio ads may still play (served via core servers) |
| Gaana, JioSaavn, Wynk | Their AdsWizz audio-ad servers, ad config/asset servers, stats | Most audio and banner ads gone |
| MX Player | Ad servers + event tracking | Most ads gone |
| SonyLIV, ZEE5 | Google video-ad servers (IMA/DAI), SonyLIV analytics | Pre-rolls mostly gone; a player may refuse to play: then turn off that pack |
| Prime Video / miniTV | amazon-adsystem, Prime video ad rolls | miniTV/Prime ad breaks mostly gone |
| YouTube, YouTube Music | No pack possible | Ads come from the same servers as the videos/songs; use Firefox + uBlock Origin or Premium |

- Packs were built from domains that HaGeZi Pro++/Ultimate, AdGuard DNS, StevenBlack and GoodbyeAds block, keeping only
  hosts clearly serving ads/analytics. Ambiguous hosts that may be core services (service.hotstar.com, vs.hotstar.com,
  hotstar.pubnub.net) are left out on purpose. Tested: all 59 pack servers blocked; 47 core app servers (Hotstar API,
  Spotify streaming, Gaana, JioSaavn, Wynk, SonyLIV, ZEE5, Prime Video, YouTube) stay reachable.
- Still seeing ads in an app? "Recent lookups by app": open the app, wait for an ad, check which servers it contacted
  and tap to block. Your entries go into "My blocked domains". If you have Afterglow's blocklist, paste it there.

Lite mode (alternative): sets Private DNS to AdGuard DNS (the app does it; no helper needed). No VPN icon, no per-app control.

Private DNS note: a specific Private DNS provider makes Android bypass any local filter. The app switches it to
Automatic itself when needed and restores your original setting afterwards.

## DSLR (photo editor)
The DSLR control now edits photos (it no longer changes display colors).
- Just took a photo: swipe down → tap the **DSLR** tile. It opens your newest camera photo, picks the settings
  for it and saves the edited copy automatically (first use asks for photo access). Add the tile once:
  swipe down twice → pencil → drag "DSLR" up. A tile can't see which photo is open on screen, so it always
  uses the latest one; for any other photo use the gallery options below.
- Any photo, in any gallery (Google Photos, Samsung Gallery, Xiaomi Gallery, Files…): Share → DSLR,
  Edit in → DSLR, or Open with → DSLR. Also: long-press the app icon → "DSLR photo".
It reads the shot the way a photographer would and re-renders it as if taken with the right settings:
- Exposure: corrected only when off by more than 1/3 stop; portraits are metered on the faces.
- Backlit subjects: dark regions are lifted locally (edge-aware, no halo against the sky).
- White balance: three estimators incl. grey pixels (neutral surfaces), corrected along warm↔cool.
- Haze/levels, highlight recovery, contrast, vibrance (skin protected), clarity, sharpening.
- Noise reduction: noise is measured from the flattest parts of the photo; colour speckle and grain are
  removed in proportion, with a second pass for heavily brightened night shots. Clean photos are untouched.
Hold the photo to compare; strength slider; "Save copy" writes Pictures/PixelControls/<name>_pro.jpg with the
original's EXIF. The original is never modified.
Limits: blown-out highlights, motion blur and missed focus can't be recovered; very textured night shots
keep some grain.

## Sound profiles
Tile "Sound Profile" (or the app): Normal (your own volumes) → Silent (vibrate, media muted) → Night (ring 40%,
notifications and media off) → Normal. The tile changes it in place: the Quick Settings panel stays open.
Two one-time grants make that possible, both set by `install.sh` over USB and kept across restarts:
- Do Not Disturb access (`cmd notification allow_dnd`), needed to change the ringer mode.
- The CONTROL_AUDIO app-ops set to allow. Android 16+ otherwise ignores volume changes from an app that isn't on
  screen ("audio hardening"); if they're ever reset, the tile falls back to applying from an invisible screen (the
  panel closes then).
A profile only counts as active once the phone shows it. Switching to Silent makes Android play its usual
vibrate feedback; the next tap can take a second or two while it does.

## Low signal mode
Goal: whatever app you open (OTT, YouTube, music, banking, UPI, anything) gets the weak connection.
- **Focus (no helper, on battery and on the charger):** while Low signal is on, Pixel Controls' DNS filter only lets
  the app on screen (and apps used in the last minute) make new connections; background apps get cached answers or
  are refused, so they can't start downloads that compete with yours. Always let through: the system, Google Play
  services (notifications), the keyboard, Pixel Controls; in the car also Android Auto and its navigation / music
  apps. Connections already open aren't cut. Needs usage access (granted by `install.sh`); without it nothing is
  held back.
- **Battery & Data Saver** on battery: Android's own Data Saver blocks background data entirely (the app on screen
  and apps playing in the foreground keep it). Battery Saver keeps your theme (no dark-mode switch).
- Mobile data kept ready, a weak Wi-Fi hands over sooner, Private DNS = Automatic (never "no Internet"), DNS
  accelerator (cache, serve-stale, raced resolvers).
- OFF restores every setting exactly. Survives a restart.
- With the helper running (optional): the 4G lock (no hunting for weak 5G) and Data Saver while charging too.
Limits: Android lets no app reserve bandwidth per app or change TCP settings; what can't arrive over a very weak
signal still can't. Apps set to bypass the filter (UPI/banking by default) aren't held back.

## Battery watch
Now and then, a notification when an app has been busy in the background, so you can stop it, restrict it or
leave it. About once an hour on battery (never while charging) the last 2 hours are checked: an app that ran 30 min
or more in the background (a background service while none of its screens showed) or used 50 MB or more of data
there is reported: "X is busy in the background · running 42 min, 12 MB of data (last 2 h)". **Stop / restrict**
opens its app page (Force stop, Battery → Restricted); **It's fine** exempts it. **Exempt apps** in the app: tick the
apps never to track (work apps; Lookout for Work is ticked from the start).
At most 3 a day, one per app a day; core system apps aren't reported. The app's Battery watch section shows the
busiest apps and has an on/off switch and Check now.
Android doesn't let apps read other apps' battery use (checked on Android 17: the API is hidden even with
BATTERY_STATS), so background running time and data stand in for it. Uses the usage access install.sh grants.

## What needs nothing, and what the helper daemon adds
No debugging has to stay on. `install.sh` grants, once over USB (kept across restarts): WRITE_SECURE_SETTINGS,
Do Not Disturb access, CONTROL_AUDIO, VPN permission, usage access, Pixel Controls' Data Saver exemption.
With those the app does everything itself: adblock Full and Lite (with its DNS watchdog), Low signal (focus,
Battery & Data Saver, settings, accelerator), sound profiles, brightness, Android Auto wired/wireless, DSLR.

The **helper daemon** only adds what Android reserves for the adb shell: the 4G lock and Data Saver while charging. Start it from the Mac over USB: `./daemon.sh start` (stop: ■ Stop in the app or
`./daemon.sh stop`; `status`, `log`). It runs until the phone restarts; nothing else depends on it.
Optional: `mac/autostart.sh install` starts it whenever the phone is plugged into this Mac.
Commands (127.0.0.1:28888, one per connection): BOOST_ON, OFF, STATUS, SHUTDOWN. No authentication: any app on the phone could send these.

## After a phone restart
Checked on the Pixel 9a (restart 23 Nov 2026, Low signal + extras and Night profile on beforehand): every permission
kept; Sound profile, Wi-Fi handover / DNS settings and Data Saver kept; the VPN (Full adblock / DNS accelerator)
comes back by itself ~2 min after the first unlock (Android queues apps' start-up work); Low signal re-applies Battery
Saver (Android drops it at a restart or once charged past 90%). The helper daemon doesn't survive a restart: Low
signal OFF without it restores everything the app owns and remembers to undo the 4G lock / charging Data Saver, which happens
automatically the next time the helper runs (`./daemon.sh start`, then open the app). Afterwards every setting was
identical to before the test.

## What was tested
On the Pixel 9a (Android 17): `./install.sh --check` → `tests/device_check.sh` (real Quick Settings taps on every tile,
each put back exactly; Low signal focus with an app on screen; Lite watchdog fallback; helper start/stop; simulated
car + cable). The phone must be unlocked for the tile and focus parts. A restart was tested separately.
On the Mac: `bash tests/daemon_lifecycle.sh` (53 checks, helper on a simulated phone under mksh) and
`bash tests/run_java_tests.sh` (blocklist 204, DNS hedging/cache 33, focus decisions 10). Photo: tests/PhotoTest.java
(needs the sample images).

## Android Auto: wired / wireless (2.7, from AA Switch 2.0.2; no helper needed)
Android Auto connects wirelessly and stays wireless even with a cable plugged in. Wireless Android Auto runs over
Wi-Fi, so Pixel Controls moves it to the cable by turning Wi-Fi off:
- In the car (the phone connects to a car's Bluetooth: car-audio or hands-free class, e.g. "My Car"), a small
  "Android Auto · <car>" notification shows and the app waits for the cable. Cable in + the car's Android Auto USB
  handshake (a plain charger never does it) with Wi-Fi on → Wi-Fi off → the car runs Android Auto over the cable.
  Cable out → Wi-Fi back on after 15 s (a quick re-plug stays wired). Car Bluetooth gone → Wi-Fi back on.
  Once per plug-in, so tapping back to wireless sticks.
- The car's two USB ports: a port without a USB data link (charge only) is never switched. The first time you plug
  into a data port Pixel Controls doesn't know, it asks once ("Is this the car's Android Auto port?") and remembers
  (by its data link, max current and voltage); a port where wired Android Auto started is remembered too. If Wi-Fi went
  off but no Android Auto started within 90 s and the cable wasn't replugged, Wi-Fi comes back on. "Forget car ports"
  in the app resets this. Android didn't report the car's USB handshake to the app on the first drive, hence learning.
- Quick Settings tile "Android Auto" (lit = wired): tap switches wired ↔ wireless. In the app: the same, plus the
  "switch automatically" option.
- Works after a phone restart with nothing running; no helper, computer or debugging.
- Car screen brightness is not something a phone app can change (the car controls its own backlight). Use Android
  Auto's own setting (Android Auto settings → Day/night mode for map) and the car's display brightness. The old
  "AA Dim" tile was removed in 2.7.
- Test without the car (adb): `adb shell dumpsys battery set ac 1`, then
  `adb shell am broadcast -n com.pixelcontrols/.auto.SimReceiver --es car "My Car" --ez accessory true --ez connected true`;
  undo with `--ez accessory false --ez connected false` and `adb shell dumpsys battery reset`.
- Not yet tried in the car: that the car starts Android Auto over the cable once Wi-Fi goes off.
