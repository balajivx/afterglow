# Afterglow

*A calm home screen for Android TV.*

**Your TV's home screen, minus the noise.** No ads, no "sponsored" rows, no clutter. Just the time, what's
new on the apps you pay for, the next big match, and your apps, laid out on a quiet night sky.

![Afterglow on a Sony Bravia](docs/home.jpg)

## What you see

**🕐 The clock card** (top left)
A little watch face, a "Good evening", the time, and today's weather in a square that's the colour of the
sky outside. One line underneath reads like a sentence: *"MON 26 · Clouds from 12 am."*

**⚽ The sports card** (top right)
One card with three sides: **Arsenal's next match**, **India's next cricket match** and the **next F1
race**. Select it and press left or right to flip it over. Each side has its own look: a floodlit pitch,
a blue night with the cricket strip, a carbon-black race car finish. It tells you when it starts in your
time, counts down, and says **Live** while it's on.

**⚙️ Inputs and Settings** (top right, above the sports card)
Two small buttons: one opens the TV's list of inputs (HDMI boxes, streamers), the other its Settings.

**🎬 New and top rated on your apps** (middle)
Shows and movies that came out in the last two weeks and are already streaming on the apps on your TV, six
at a time, newest first: page 1 holds the most recent, up to 4 pages. Each page is 3 Telugu, 2 English and
1 Hindi while there are enough of each; the newest of the others fill in when a language runs out. It starts folded to one line over your apps: press **+** to open it and **−** to fold it again. ‹ ›
page through; ↻ checks TMDB again and shows the next page (or page 1, when there's something new). The best rated lead; brand-new ones still waiting for ratings say
**New**. No cartoons or anime. One slim row of wide title cards; move onto one and its story appears above the row while its artwork softly fills the sky behind it.

**📺 Your apps** (bottom)
Every app, six to a row, each on a card in its own brand colour, all equally bright, so nothing shouts.
Press down for more rows; the page lifts gently to show them.

**✨ The feel**
A slow aurora and a few twinkling stars behind frosted-glass cards. A soft light under the apps (the
afterglow) shines in the colour of whatever you're on. Moving around is calm: a thin ring and a colour change, nothing jumps
or grows.



## Control Center

The button over the sports card (sliders icon) opens it; Back closes it. Left: **StandBy** (the screensaver
now), **Wi-Fi** and **Bluetooth** (their state; OK opens their settings), **Inputs**, **Settings**, and round
buttons: **Voice** (Google Assistant), **Sound** (Sony's Display & Sound, where the Streambar's settings are),
**Sleep** (each press: 30, 60, 90 min, off; the TV goes to standby when it runs out), **Picture off** (dark
screen, sound on; any key brings it back), **Cast** (with the TV's cast name), **Fire TV** (straight to HDMI 4,
set in `config.xml`), **Saver settings** (which screensaver, and when; StandBy starts it now), and
**Restart** (press twice). Right: the five picture modes,
volume (it drives the Streambar), and **Recent apps** with the memory each one holds now: OK opens one, its
**×** closes it at once, and **Close all** closes every running app except those on `close_all_keep` in
`config.xml` (the screensaver, Sony's TV app, the Play Store). **Free storage** beside it clears every app's
cache and the leftovers of apps that are switched off or unused (`storage_clear`, `storage_unupdate`): the TV
has only 4 GB for apps, and your own apps live on the USB drive. The **6** key is Picture off from anywhere.

Closing apps and reading their memory use the TV's own debugging service (the same one the Mac's adb uses):
Android lets no app do either otherwise. The TV asked once to allow it ("Always allow" was ticked). If closing
ever says it needs network debugging, turn it back on in Developer options; after a factory reset the TV
asks again.

## Picture keys (the remote's number keys)

In any app, the number keys switch the picture: **1** Sports (cricket, football, F1), **2** Movies (and
Netflix or Prime series), **3** Gaming, **4** Night, **5** Everyday (YouTube, TV), **6** Picture off (sound
on), and **0** shows what each key does. A small card at the top says what changed. The profiles are in
`launcher/app/src/main/res/values/config.xml` (`hotkey_1`…), so you can change them or add 5 to 9.

They use Sony's IP control on the TV itself, which needs a key, once:

1. On the TV: **Settings → Network & Internet → Home network → IP control**. Set **Authentication** to
   **Normal and Pre-Shared Key**, then set a **Pre-Shared Key** (any word).
2. Put the same word in `launcher/tmdb.properties` as `bravia.psk=yourword`, then **Build and install**.
3. Run `./hotkeys.sh` once (turns the keys on; or TV Settings → Accessibility → Afterglow picture keys).

The keys also set **Motionflow** (how smooth fast motion looks): 1 Sports **Custom**, 2 Movies and 5 Everyday
**Auto**, 4 Night **Off**. 3 Gaming uses Sony's Game mode, which turns motion processing off itself to keep
input lag low. For a console on HDMI, also set that input's **HDMI signal format** to **Enhanced** (TV
Settings, External inputs) for 4K at 120 Hz on HDMI 3 or 4. Sony's IP control can't reach Motionflow, so the key works Sony's own picture
menu for you: it runs along the bottom of the screen for a few seconds, then closes. It works in apps and on
HDMI inputs alike (a Fire TV Stick on HDMI 4, say): each input keeps its own picture settings, and the key
changes those of the input that's playing (it checks Sony's menu names that input before touching anything,
and settings an input doesn't have, like the light sensor on HDMI, are skipped). Set **Smoothness 2** and
**Clearness Min** once by hand for each input you watch sport on (gear, Picture settings, Motion,
Motionflow, with Motionflow on Custom); they stay put as Motionflow changes, and only key 1 (the only one on
Custom) uses them. Max looked smoother on pans but made a hit ball break up and jump.

**Fire TV Stick (or any streaming box) on HDMI:** turn on its **Match Original Frame Rate** (Fire TV:
Settings, Display & Sounds, Display). Without it the stick sends 60 Hz whatever it plays, so 25 and 50 fps
cricket judders before it reaches the TV; with it, it switches to 50 Hz for them.

**For smooth live cricket**, as measured on this TV during a match:

- **Set SonyLIV's quality to 1080p, not Auto.** On Auto it fell to 360p at 25 fps; on 1080p it held 50 fps.
- **Keep streaming apps from idling in the background**: run `./quiet-apps.sh` once (undo: `./quiet-apps.sh
  undo`). The TV has 3 GB of memory; with Netflix, Prime, JioHotstar and Cricfy idling, SonyLIV lost 8 frames
  in 95 s of play, and none in 2 minutes once they were restricted. The apps still open and play as before.
- **Key 1**, which puts Motionflow on Custom with your Smoothness 2 (above).
- **JioHotstar** sends live cricket at 25 fps (1080p), even on its highest quality, and its player aims each
  frame about 4 ms off the TV's refresh, so about once a second a frame stays up 67 ms instead of 33-50 ms.
  The TV shows every frame exactly when asked; neither more memory nor Smoothness Max changed it. It's the
  app: where a match is on SonyLIV too, SonyLIV at 1080p (50 fps, evenly paced) looks clearly smoother.

Why it can stutter at all: the TV's apps always draw at 60 Hz, while cricket
streams are 50 or 25 frames a second, so frames can't be shown for equal times (50 fps: every 5th frame is
held twice; 25 fps: frames alternate 33 ms and 50 ms). SonyLIV sends 50 fps at its best quality but drops to
25 fps at 360p when its stream falls back; Cricfy is 25 fps throughout. Motionflow's Smoothness evens it out best,
and a 50 fps stream (SonyLIV or JioHotstar on its highest quality, not Auto) gives it twice the motion.

## Set it up

You need a Mac, an Android TV on the same Wi-Fi, and about ten minutes.

1. **On the TV**: Settings → Device Preferences → About → click *Build* seven times. Then in Developer
   options turn on **USB debugging** (and network debugging, if your TV shows it).
2. **On the Mac**, install the tools once:

       brew install openjdk@17 gradle
       brew install --cask android-platform-tools android-commandlinetools
       sdkmanager "platforms;android-34" "build-tools;34.0.0"

3. **Connect** to the TV (allow the prompt on screen):

       adb connect <your-tv-ip>:5555

4. **Add your free TMDB token** (for the shows and movies): create an account at themoviedb.org, go to
   Settings → API, copy the *API Read Access Token*, and save it in `launcher/tmdb.properties`:

       tmdb.token=eyJ...

   This file never leaves your Mac (git ignores it). Without it, everything works except that shelf.

5. **Build, install and switch to it**:

       ./build.sh
       ./install.sh
       ./activate.sh

## Make it yours

Open `launcher/app/src/main/res/values/config.xml`:

| Setting | What it does |
|---|---|
| `dock_apps`, `grid_apps` | The apps that come first, in your order. Everything else follows A to Z. |
| `hidden_apps` | Apps to keep off the screen (they stay installed). |
| `weather_lat`, `weather_lon`, `weather_place` | Where your weather is from. |
| `columns` | Apps per row (6). |
| `effects` | 2 = full animation, 1 = lighter (good for TVs with little memory), 0 = still. |

Want different teams, languages or streaming apps? They're near the top of `Sports.java` (team ids from
TheSportsDB) and `Shelf.java` (`LANGUAGES`, `SERVICES`). Brand colours are in `DockView.java`. Run
`./build.sh` and `./install.sh` again after any change.

**Changed your mind?** `./restore-launcher.sh` brings back the TV's original home screen.

## Good to know

- **Light on your TV.** Under 1 MB, no extra libraries. On a Sony Bravia with about 1.2 GB of free
  memory it stays smooth (under 1% dropped frames).
- **Light on your network.** Weather every 30 minutes, shows every 4 hours, sports every 3 hours, and only
  while the home screen is showing. Everything is saved, so it appears instantly after a restart.
- **Nothing is removed from your TV.** It's just another app set as the home screen.

## Credits and licence

MIT licensed, see [LICENSE](LICENSE). Fonts (Open Runde), icons (Phosphor) and data (TMDB, TheSportsDB,
Jolpica-F1, Open-Meteo) are listed in [NOTICE.md](NOTICE.md), with their licences in `licenses/`. App names,
logos and posters belong to their owners.
