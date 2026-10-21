# Handoff notes

Picking this up locally? Here is where things stand. The README has the full docs.

## Status
- **App:** builds with `./gradlew assembleRelease`. It was tested on an Android TV 11 emulator: shuffle
  playback from assets, the VCR info card, Right = next, and the framing toggle all work.
  It has not been tried on the onn. stick or the CRT yet.
- **Clips:** the first 10 are listed in `tools/links.txt`, and their card text is already in
  `app/src/main/assets/clips/clips.csv`. **They haven't been downloaded yet.** YouTube blocks cloud servers, so run
  the fetch step on your own machine.

## Next steps
```sh
git pull
brew install yt-dlp ffmpeg            # or: pip install -U yt-dlp
tools/fetch_clips.sh                  # downloads ≤480p, converts to 640x480 into assets/clips
./gradlew assembleRelease             # needs JDK 17+ and the Android SDK (Android Studio)
adb connect <stick-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell appops set team.superfun.rerun SYSTEM_ALERT_WINDOW allow   # auto-start on power-up
```
If YouTube asks you to sign in, run `YTDLP_ARGS="--cookies-from-browser chrome" tools/fetch_clips.sh`.

## Decisions and assumptions to double-check
- **"Clip at 7min / 5:40 / 5:01"** was read as *keep from 0:00 up to that time*. To change it, edit the
  `START-END` column in `tools/links.txt`, then delete the matching file in `tools/downloads/` and in
  `app/src/main/assets/clips/` before you re-run.
- **Freddy vs Jason** (`XWPq9Hb81aM`) is a 2.37:1 picture in 16:9 with an "FM" logo in the bars.
  It uses `--letterbox --mask-bars 12.5`: the top and bottom 12.5% are painted black (measured from the
  YouTube thumbnails at 90px of 720), and the full frame is kept in 4:3 with nothing cropped. If any of the
  logo still peeks through, raise the number slightly.
- **Every other clip** is center-cropped to fill 4:3, and baked-in black bars are trimmed first.
- **Framing on the CRT:** the default is *Full screen*. If circles look stretched, hold **OK** on the
  remote to switch to *Center 4:3*. The app remembers the choice.
- **Info card** shows during the first and last 8 seconds of each clip, to avoid burning text into the tube.
  Set `INFO_ALWAYS_ON` in `Config.kt` if you want it up the whole time.
- **Size budget:** keep `assets/clips` under ~1.5–1.8 GB, because the onn. stick has 8 GB. Clips come out at
  ~2 MB per minute (H.265 + HE-AAC).

## Files
- `tools/links.txt`: clip list (`URL [START-END] | File Name | converter options`)
- `app/src/main/assets/clips/clips.csv`: card text per file (title, director, year, studio)
- `tools/fetch_clips.sh`: yt-dlp download, then conversion
- `tools/prepare_clips.sh`: any video to a small 640x480 H.265 clip (autocrop, `--letterbox`, `--mask-bars`)
- `app/src/main/java/team/superfun/rerun/Config.kt`: timing, text size, overscan margins
