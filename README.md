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
- Skips a broken clip and moves on. Shows a blue `NO TAPE` screen when there are no clips.

## Remote

| Button | Does |
| --- | --- |
| **Right** / Next / FF / Ch+ | Next clip |
| **Left** / Prev / Rew / Ch− | Restart the clip. Press again within 4 s to go to the previous clip |
| **OK** / Play-Pause | Pause or resume |
| **Up** | Show the info card |
| **Down** | Change framing: *Full screen* or *Center 4:3* (remembered between runs) |
| **Back** ×2 | Exit (one stray press won't stop the show) |

## Adding clips

1. Install `ffmpeg`. Name your source files like `Back to the Future (1985).mp4` (any format works).
2. Run:
   ```sh
   tools/prepare_clips.sh ~/Downloads/my-clips/
   ```
   Each file becomes a 640×480 H.264 MP4 in `app/src/main/assets/clips/`, with the audio level
   evened out. Widescreen sources are center-cropped to fill the 4:3 tube. Use `--letterbox`
   to keep the full width with black bars instead, and `--max-seconds 90` to trim long clips.
3. Fill in `app/src/main/assets/clips/clips.csv`. The script has already added a row for each new clip:
   ```csv
   file,title,director,year,studio
   Back to the Future (1985).mp4,Back to the Future,Robert Zemeckis,1985,Universal Pictures
   "Crouching Tiger (2000).mp4","Crouching Tiger, Hidden Dragon",Ang Lee,2000,Sony Pictures Classics
   Cheers S05E03.mp4,Cheers,"S5 E3 - Abby",1986,NBC
   ```
   For TV clips, put the episode in the director column and the network in the studio column.
   Empty cells are skipped. A clip with no row gets its title and year from the filename.

Clips are kept out of git on purpose (`.gitignore`), because GitHub can't store files that large.

### Size budget
The onn. 4K stick has about 8 GB of storage. Installing needs room for the APK and an installed
copy of it, so **keep the clips folder under ~1.5–1.8 GB**. At the script's settings, a clip runs
about 10–12 MB per minute, which gives you roughly 100 clips of about 1.5 minutes each.
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

The app has two framing modes. Press **Down** on the remote to switch between them:

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
    ShuffleBag.kt          no-repeat shuffle
    BootReceiver.kt        auto-start on power-up
    Config.kt              tweakables
tools/prepare_clips.sh     converts any video into a CRT-ready clip
```

## Credits

**VCR OSD Mono** by Riciery Leal (mrmanet), released as 100% free on
[dafont](https://www.dafont.com/vcr-osd-mono.font). Playback uses [AndroidX Media3](https://developer.android.com/media/media3) ExoPlayer.
