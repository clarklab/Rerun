# Rerun
The best TV ever.

An Android TV app that turns a cheap streaming stick (built for the Walmart **onn.** stick) into an
offline, always-on CRT channel. Every clip lives inside the APK. Power it on, and it shuffles
movie and TV clips forever, like a music video channel, with VCR-style on-screen text. It needs no internet.

- Plays 4:3 / 480p clips bundled in the APK, on shuffle. Every clip plays once before any repeats.
- Shows title, director, and year + studio in the bottom-left corner, in the **VCR OSD Mono** font.
  The card appears for the first and last 8 seconds of each clip, like MTV, so the text doesn't burn into the tube.
- Shows a `PLAY ►` badge in the top-left at the start of each clip, and `PAUSE ||` while paused.
- Can start on its own when the stick powers on (optional, see below).
- Bad-tape mode for fun: Up on the remote wears the tape down a click at a time, and Down brings it back.
  Level 1 looks like a decent SP tape. Each click goes softer, smearier and noisier, then tracking bars come in
  and grow, the picture starts to bounce, and level 10 rolls. It's a live GPU port of
  [ntsc-rs](https://github.com/valadaptive/ntsc-rs)'s NTSC/VHS signal model (`VhsEffect.kt`), so it works on any clip.
- A late-80s cable guide on **Back**, with the live picture in a window and one channel per movie.
- The shuffle never plays two clips from the same movie back to back.
- Skips a broken clip and moves on. Shows a blue `NO TAPE` screen when there are no clips.

## Remote

| Button | Does |
| --- | --- |
| **Right** / Next / FF / Ch+ | Next clip |
| **Left** / Prev / Rew / Ch− | Restart the clip. Press again within 4 s to go to the previous clip |
| **OK** / Play-Pause | Pause or resume (also shows the info card) |
| **Hold OK** | Change framing: *Full screen* or *Center 4:3* (remembered between runs) |
| **Up** / **Down** | Make the VHS tracking worse / better, in 10 clicks (remembered between runs). Hold **Down** to get a clean picture back (*AUTO TRACKING*) |
| **Back** | Open the cable guide: one channel per movie. **Up/Down** pick, **Left/Right** page, **OK** plays all of that movie's clips in shuffled order, then back to the full shuffle. **Back** closes it (it also closes itself after 30 s) |
| **Home** | Leave Rerun |

## Adding clips

1. Install `ffmpeg`. Name your source files like `Back to the Future (1985).mp4` (any format works).
2. Run:
   ```sh
   tools/prepare_clips.sh ~/Downloads/my-clips/
   ```
   Each file becomes a small 640×480 H.265 MP4 in `app/src/main/assets/clips/`, with the audio level
   evened out. Widescreen sources are center-cropped to fill the 4:3 tube. Use `--letterbox`
   to keep the full width with black bars instead, and `--max-seconds 90` to trim long clips.
3. Fill in `app/src/main/assets/clips/clips.csv`. The script has already added a row for each new clip:
   ```csv
   file,title,director,year,studio,movie
   Back to the Future (1985).mp4,Back to the Future,Robert Zemeckis,1985,Universal Pictures
   "Crouching Tiger (2000).mp4","Crouching Tiger, Hidden Dragon",Ang Lee,2000,Sony Pictures Classics
   Cheers S05E03.mp4,Cheers,"S5 E3 - Abby",1986,NBC
   ```
   For TV clips, put the episode in the director column and the network in the studio column.
   For films, the studio column is the label it was released under in US theaters, named as it was
   credited at the time (e.g. MGM/UA Entertainment Co. for Poltergeist).
   A music video from a film's soundtrack is filed under that film like any other clip: its title is
   "Band - Song", the director, year and studio are the film's, and an extra `movie` column names the film,
   so it shares the film's guide channel and the shuffle keeps it apart from the film's clips:
   ```csv
   Pet Sematary (1989) Ramones.mp4,Ramones - Pet Sematary,Mary Lambert,1989,Paramount Pictures,Pet Sematary
   ```
   Empty cells are skipped. A clip with no row gets its title and year from the filename.

### From YouTube links

Paste links into `tools/links.txt`, one per line. You can add a time range to grab
just a section, and a `| Title (Year)` to name it:

```
https://youtu.be/XXXXXXXXXXX
https://youtu.be/XXXXXXXXXXX   1:05-2:30
https://youtu.be/XXXXXXXXXXX   0:40-1:55   | Back to the Future (1985)
```

Then run:

```sh
pip install -U yt-dlp        # or: brew install yt-dlp ffmpeg
tools/fetch_clips.sh
```

The script downloads each link at 480p or below (only the section you asked for), then runs it
through `prepare_clips.sh`. Clips that are already downloaded or converted get skipped, so it's
safe to add more links and run it again. Originals are kept in `tools/downloads/`.

**Run this on your own computer.** YouTube blocks downloads from most cloud servers. If it ever
asks you to sign in, run `YTDLP_ARGS="--cookies-from-browser chrome" tools/fetch_clips.sh`.

A line can also carry converter options after a second `|`. For example, this one blacks out a
channel logo sitting in a baked-in letterbox and keeps the whole frame instead of cropping it:

```
https://youtu.be/XXXXXXXXXXX   | Freddy vs Jason (2003) | --letterbox --mask-bars 12.5
```

Both scripts automatically trim black bars baked into a video. That covers a 4:3 TV show
uploaded with side bars, and a widescreen movie with top and bottom bars. Pass
`--no-autocrop` to `prepare_clips.sh` to turn this off.

Clips are kept out of git on purpose (`.gitignore`), because GitHub can't store files that large.

### Size budget
The onn. 4K stick has about 8 GB of storage. Installing needs room for the APK and an installed
copy of it, so **keep the clips folder under ~1.5–1.8 GB**. At the script's settings, a clip runs
about 2 MB per minute (H.265 + HE-AAC), so the budget holds several hours of clips.
`prepare_clips.sh` prints the folder's total size when it finishes.

## Building

You need JDK 17+ and the Android SDK. Either open the project in Android Studio, or run:

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # wherever your SDK lives
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

The release APK is signed with the standard debug key, so it sideloads with no keystore setup.
Videos are stored uncompressed inside the APK, so seeking stays instant.

## Installing on the onn. stick

Do this at home, while the stick and your computer are on the same Wi-Fi:

1. On the stick, go to **Settings → System → About** and click **Android TV OS build** 7 times
   to unlock developer options.
2. In **Settings → System → Developer options**, turn on **USB debugging**. This also allows
   network debugging. Note the stick's IP address, found under **Settings → Network**.
3. From your computer, run:
   ```sh
   adb connect 192.168.1.50:5555          # accept the prompt on the TV
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
4. **Rerun** now shows up in the app row. After that, it needs no network at all.

### Start automatically on power-up (recommended for the swap meet)
Android 10 and newer only lets an app start itself at boot if it has the "display over other apps"
permission. Grant it once:
```sh
adb shell appops set team.superfun.rerun SYSTEM_ALERT_WINDOW allow
```
After that, plug the stick in and Rerun starts playing on its own.

Also turn the screensaver off, or set it to *Never*, under **Settings → System → Ambient mode**
or **Screen saver**.

## Framing: getting the picture right on the CRT

The app has two framing modes. Hold **OK** on the remote to switch between them:

- **Full screen** (default): the clip fills the whole HDMI output. This is correct when the stick
  outputs a 4:3 or 480p signal, or when your HDMI→AV converter squeezes the full 16:9 frame onto the tube.
- **Center 4:3**: the clip sits in a 4:3 box in the middle of a 16:9 output. This is correct when
  the converter crops the middle out of a 16:9 signal.

Pick whichever mode gets circles looking round and keeps the text on the glass. If text is cut off by
the bezel, raise `SAFE_MARGIN_X/Y` in `Config.kt`. If the text looks skinny because the converter
squeezes the picture, set `TEXT_SCALE_X = 1.33f`. Card timing, text size and caps are set in the same file.

## Project layout

```
app/src/main/
  assets/clips/            your .mp4 clips + clips.csv
  assets/fonts/            VCR OSD Mono
  java/team/superfun/rerun/
    MainActivity.kt        player, remote, on-screen display
    ClipLibrary.kt         finds clips, reads clips.csv
    ShuffleBag.kt          no-repeat shuffle that keeps one movie's clips apart
    GuideView.kt           the cable guide
    VhsEffect.kt           bad-tape effect (Up/Down), a GPU port of ntsc-rs
    BootReceiver.kt        auto-start on power-up
    Config.kt              tweakables
tools/prepare_clips.sh     converts any video into a CRT-ready clip
```

## Credits

**VCR OSD Mono** by Riciery Leal (mrmanet), released as 100% free on
[dafont](https://www.dafont.com/vcr-osd-mono.font). Playback uses [AndroidX Media3](https://developer.android.com/media/media3) ExoPlayer.
The VHS effect's filters, noise and tracking model are adapted from [ntsc-rs](https://github.com/valadaptive/ntsc-rs)
by valadaptive (MIT / ISC / Apache-2.0).
