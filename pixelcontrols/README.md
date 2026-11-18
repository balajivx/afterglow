# PixelControls 2.1

Adblock (system-wide, with Quick Settings tile) + Pro Enhance for photos + your existing
Low Signal / DSLR daemon controls.

## Install
Prebuilt: `adb install -r pixelcontrols.apk` (debug-signed; minimum Android 10).
From source on your Mac: `./build.sh` → `build/pixelcontrols.apk` (Gradle 8.7, AGP 8.5.2, JDK 17, no dependencies).
Package name is `com.pixelcontrols`. If your old app used a different one, both will be installed side by side.

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

Lite mode (alternative): sets Private DNS to AdGuard DNS via the helper daemon. No VPN icon, no per-app control.

Private DNS note: a specific Private DNS provider makes Android bypass any local filter. The app switches it to
Automatic through the daemon when needed and restores your original setting afterwards; without the daemon,
set Settings → Network → Private DNS → Automatic.

## Pro Enhance
Google Photos → open a photo → Share → Pro Enhance (or Edit → ⋮ → Edit in… → Pro Enhance).
It reads the scene (portrait via face detection, landscape, golden hour, low light, backlit) and corrects exposure
(only when off by more than 1/3 stop), white balance (warm↔cool axis, gentle on colorful scenes), haze/levels,
shadows and highlights, contrast, vibrance (skin protected), clarity and noise-aware sharpening.
Hold the photo to compare; strength slider; "Save copy" writes Pictures/PixelControls/<name>_pro.jpg with the
original's date, camera and GPS EXIF. The original is never modified.
Limits: blown-out highlights, motion blur and missed focus can't be recovered.

## Low signal mode (fixed in 2.1)
Before: it forced strict Private DNS (one.one.one.one). On weak signal that encrypted DNS connection fails, Android
never falls back, and the phone shows "Mobile network has no Internet access / Private DNS server cannot be accessed".
Now: 4G/3G only, Data Saver + Battery Saver, and Private DNS = Automatic (encrypted when possible, normal DNS on weak
signal). Turning it off restores your own Private DNS setting exactly.
DNS watchdog: if the strict AdGuard DNS used by Lite adblock becomes unreachable while the internet works, the daemon
switches to Automatic within ~1 minute and the app shows a warning. It never changes a strict DNS you set yourself.
Tip: on weak networks prefer the Full filter adblock; it doesn't depend on encrypted DNS.

## Helper daemon (Low signal, DSLR colors, Lite adblock, Private DNS switching)
adb push start_daemon.sh /data/local/tmp/ && adb shell sh /data/local/tmp/start_daemon.sh
Re-run after each reboot. Safe to re-run: it now stops the previous listener first.
New commands: ADBLOCK_DNS_ON/OFF, PRIVDNS_AUTO/RESTORE (backs up and exactly restores your Private DNS).
Note: the daemon still has no authentication; any app on the phone could send it commands.

## What was tested
Blocklist: 77/77 tests on the real list. DNS filter: end-to-end with a real DNS server, every packet independently
verified (checksums, IDs, answers); blocked names never leave the phone. Photo: 81/82 checks; 25/30 deliberately
degraded photos measurably improved (30% mean error reduction), good photos barely changed. Known miss: a blue cast
on a scene full of orange objects is under-corrected. Daemon: all commands + 4 restarts → exactly one listener (mksh).
Not tested here: on a real device (no emulator in the build sandbox).
