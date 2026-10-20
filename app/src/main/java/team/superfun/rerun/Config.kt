package team.superfun.rerun

/** Knobs worth tweaking before a build. */
object Config {
    /** Folder inside the APK's assets that holds the clips and clips.csv. */
    const val CLIPS_DIR = "clips"
    const val METADATA_FILE = "clips.csv"
    val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "3gp")

    /**
     * Info card timing, MTV style: shown when a clip starts and again before it ends.
     * Hiding it in between keeps static text from burning into the CRT over a long day.
     */
    const val INFO_SHOW_AT_START_MS = 8_000L
    const val INFO_SHOW_AT_END_MS = 8_000L
    const val INFO_ALWAYS_ON = false
    /** How long the card stays up after pressing a button. */
    const val INFO_ON_KEYPRESS_MS = 5_000L

    /** How long "PLAY ►" stays up in the top-left after a clip starts. */
    const val PLAY_BADGE_MS = 3_000L

    /** Pressing back this far into a clip restarts it; pressing it sooner goes to the previous clip. */
    const val RESTART_THRESHOLD_MS = 4_000L

    /** OSD text in caps, like a real VCR. */
    const val UPPERCASE = true

    /** Text sizes as a fraction of the picture height. */
    const val INFO_TEXT_SIZE = 0.050f
    const val BADGE_TEXT_SIZE = 0.065f

    /**
     * Safe margin as a fraction of the picture, so text isn't lost under the CRT bezel.
     * CRTs overscan a lot; raise these if text gets clipped on your set.
     */
    const val SAFE_MARGIN_X = 0.08f
    const val SAFE_MARGIN_Y = 0.08f

    /**
     * Horizontal text squash/stretch. If your HDMI-to-AV box squeezes a 16:9 signal onto the
     * 4:3 tube, text looks skinny; set this to ~1.33 to compensate.
     */
    const val TEXT_SCALE_X = 1.0f
}
