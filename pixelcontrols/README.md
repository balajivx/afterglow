# PixelControls 2.5

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

## Low signal mode (2.5)
Goal: the app on screen (YouTube, OTT, UPI, anything) keeps working smoothly on a weak network.
- Background apps are paused from using data (Data Saver), so the app on screen gets the whole weak link.
- The app on screen is kept online even in the background (music, downloads, picture-in-picture); so are
  installed UPI/banking apps (payment confirmations) and PixelControls itself.
- DNS accelerator (every connection starts with a DNS lookup, and lookups fail first on a lossy link):
  - repeat lookups answered instantly on the phone (cache that respects each answer's lifetime);
  - if the network can't answer in time, the last known good answer is used, so a working app keeps
    connecting through short dropouts (RFC 8767 "serve-stale");
  - carrier DNS, Cloudflare and Google are raced (backup after 0.6 s, resend lost queries after 1.5 s).
    Measured on a simulated 30%-loss link: 54/60 lookups answered in 0.85 s on average, vs 46/60 in 2.9 s before.
  - ads and trackers blocked so they don't compete for the weak link. Your previous adblock state is
    restored on OFF. UPI/banking apps bypass the filter (they can't see the VPN).
- LTE/3G/2G instead of hunting for weak 5G; mobile data stays ready; a weak Wi-Fi hands over sooner.
- Private DNS = Automatic (encrypted when possible, normal DNS on weak signal; never "no Internet").
- OFF restores every changed setting exactly. Network doctor shows "Fix now" if Private DNS ever cuts you off.
Limits: without root no app can reserve bandwidth for one app or change the phone's TCP settings; what
can't arrive over a very weak signal still can't. The DNS accelerator works even without the daemon;
everything else needs it. **After updating, re-run start_daemon.sh from your Mac.**

## Helper daemon (Low signal, Lite adblock, Private DNS switching)
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
