package team.superfun.rerun

import android.app.Activity
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : Activity() {

    private enum class Framing(val label: String) {
        /** Picture fills the whole output. Right when the stick outputs 4:3/480p, or the AV box squeezes 16:9 onto the tube. */
        FULL("FULL SCREEN"),
        /** Picture is a centered 4:3 box. Right when the AV box crops the middle out of a 16:9 signal. */
        CENTER_4X3("CENTER 4:3"),
    }

    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var badge: TextView
    private lateinit var info: TextView
    private lateinit var message: TextView

    private var clips: List<Clip> = emptyList()
    private var shuffle: ShuffleBag? = null
    private var player: ExoPlayer? = null
    private var framing = Framing.FULL
    private val vhs = VhsEffect()
    private var tracking = 0

    private val handler = Handler(Looper.getMainLooper())
    private var badgeText = ""
    private var badgeUntil = 0L
    private var infoUntil = 0L
    private var exitArmedUntil = 0L
    private var runtimeRetries = 0

    private val tick = object : Runnable {
        override fun run() {
            updateOsd()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        stage = findViewById(R.id.stage)
        playerView = findViewById(R.id.player)
        badge = findViewById(R.id.badge)
        info = findViewById(R.id.info)
        message = findViewById(R.id.message)

        val vcr = Typeface.createFromAsset(assets, "fonts/vcr_osd_mono.ttf")
        for (tv in listOf(badge, info, message)) {
            tv.typeface = vcr
            tv.textScaleX = Config.TEXT_SCALE_X
            tv.isAllCaps = Config.UPPERCASE
            tv.setShadowLayer(1f, 3f, 3f, 0xCC000000.toInt())
            tv.includeFontPadding = false
        }

        val prefs = getPreferences(MODE_PRIVATE)
        framing = Framing.entries.getOrElse(prefs.getInt(PREF_FRAMING, 0)) { Framing.FULL }
        tracking = prefs.getInt(PREF_TRACKING, 0).coerceIn(0, Config.TRACKING_STEPS)
        vhs.amount = tracking.toFloat() / Config.TRACKING_STEPS
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) root.post { layoutStage() }
        }

        clips = ClipLibrary.load(assets)
        shuffle = if (clips.isNotEmpty()) ShuffleBag(clips.size) else null
    }

    override fun onStart() {
        super.onStart()
        hideSystemUi()
        if (clips.isEmpty()) {
            showBlueScreen("NO TAPE\n\nADD CLIPS TO\nassets/clips/")
        } else {
            startPlayer()
        }
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        player?.release()
        player = null
        playerView.player = null
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // ---- Playback ----

    @OptIn(UnstableApi::class)
    private fun startPlayer() {
        val bag = shuffle ?: return
        val exo = ExoPlayer.Builder(this).build().apply {
            // Always in the pipeline; at tracking 0 the shader passes frames straight through.
            setVideoEffects(listOf(vhs))
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(playerListener)
            setMediaItems(bag.nextRound().map(::mediaItemFor))
            prepare()
            playWhenReady = true
        }
        player = exo
        playerView.player = exo
        message.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        stage.setBackgroundColor(0xFF000000.toInt())
        onClipStarted()
    }

    private fun mediaItemFor(index: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId(index.toString())
            .setUri(Uri.Builder().scheme("asset").authority("").path("/" + clips[index].assetPath).build())
            .build()

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            runtimeRetries = 0
            topUpPlaylist()
            onClipStarted()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = updateOsd()

        override fun onPlayerError(error: PlaybackException) {
            val exo = player ?: return
            val bag = shuffle ?: return
            // Media3 1.4's effects pipeline can throw a runtime error if a frame has to be dropped. That isn't the
            // clip's fault, so pick up where it left off rather than marking the clip broken.
            if (error.errorCode == PlaybackException.ERROR_CODE_UNSPECIFIED && runtimeRetries < 3) {
                runtimeRetries++
                Log.w(TAG, "Playback hiccup, resuming", error)
                exo.prepare()
                exo.play()
                return
            }
            val bad = exo.currentMediaItem?.mediaId?.toIntOrNull()
            Log.e(TAG, "Can't play ${bad?.let { clips[it].assetPath }}, skipping", error)
            if (bad != null) {
                bag.skip(bad)
                // Drop every queued copy of the broken clip.
                for (i in exo.mediaItemCount - 1 downTo 0) {
                    if (exo.getMediaItemAt(i).mediaId == bad.toString() && i != exo.currentMediaItemIndex) {
                        exo.removeMediaItem(i)
                    }
                }
            }
            if (bag.playableCount == 0) {
                exo.stop()
                showBlueScreen("TRACKING ERROR\n\nNO PLAYABLE CLIPS")
                return
            }
            val broken = exo.currentMediaItemIndex
            topUpPlaylist()
            exo.seekTo(broken + 1, 0)
            exo.removeMediaItem(broken)
            exo.prepare()
            exo.play()
        }
    }

    /** Keeps at least two clips queued ahead, and forgets old history so the playlist never grows forever. */
    private fun topUpPlaylist() {
        val exo = player ?: return
        val bag = shuffle ?: return
        if (exo.mediaItemCount - exo.currentMediaItemIndex <= 2) {
            exo.addMediaItems(bag.nextRound().map(::mediaItemFor))
        }
        val keepHistory = 25
        if (exo.currentMediaItemIndex > keepHistory * 2) {
            exo.removeMediaItems(0, exo.currentMediaItemIndex - keepHistory)
        }
    }

    private fun onClipStarted() {
        val index = player?.currentMediaItem?.mediaId?.toIntOrNull() ?: return
        info.text = clips[index].infoLines().joinToString("\n")
        flashBadge("PLAY ►", Config.PLAY_BADGE_MS)
        infoUntil = 0L
        updateOsd()
    }

    private fun next() {
        val exo = player ?: return
        topUpPlaylist()
        exo.seekToNextMediaItem()
        exo.play()
    }

    /** First press restarts the clip; a quick second press goes back one clip. */
    private fun back() {
        val exo = player ?: return
        if (exo.currentPosition < Config.RESTART_THRESHOLD_MS && exo.hasPreviousMediaItem()) {
            exo.seekToPreviousMediaItem()
        } else {
            exo.seekTo(0)
            flashBadge("◄◄ REW", 1_500L)
        }
        exo.play()
    }

    private fun togglePause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
        showInfoFor(Config.INFO_ON_KEYPRESS_MS)
    }

    // ---- Remote ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val firstPress = event.repeatCount == 0
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (firstPress) next()
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (firstPress) back()
            }
            // Held keys repeat, so holding Down rolls all the way back to a clean picture.
            KeyEvent.KEYCODE_DPAD_UP -> adjustTracking(+1)
            KeyEvent.KEYCODE_DPAD_DOWN -> adjustTracking(-1)
            // OK pauses on release; holding it changes framing instead (see onKeyLongPress).
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (firstPress) event.startTracking()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (firstPress) togglePause()
            }
            KeyEvent.KEYCODE_INFO -> showInfoFor(Config.INFO_ON_KEYPRESS_MS)
            KeyEvent.KEYCODE_BACK -> {
                // Guard against a stray press ending the show: back has to be pressed twice.
                val now = SystemClock.uptimeMillis()
                if (now < exitArmedUntil) finish() else {
                    exitArmedUntil = now + 2_500L
                    flashBadge("BACK AGAIN = EXIT", 2_500L)
                }
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in OK_KEYS) {
            cycleFraming()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in OK_KEYS) {
            if (event.isTracking && !event.isCanceled) togglePause()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Up/Down turn the tape worse or better, one click at a time; 0 is a clean picture. */
    private fun adjustTracking(delta: Int) {
        tracking = (tracking + delta).coerceIn(0, Config.TRACKING_STEPS)
        vhs.amount = tracking.toFloat() / Config.TRACKING_STEPS
        getPreferences(MODE_PRIVATE).edit().putInt(PREF_TRACKING, tracking).apply()
        val bar = "|".repeat(tracking) + "-".repeat(Config.TRACKING_STEPS - tracking)
        flashBadge(if (tracking == 0) "AUTO TRACKING" else "TRACKING\n$bar", 2_500L)
    }

    // ---- On-screen display ----

    private fun cycleFraming() {
        framing = Framing.entries[(framing.ordinal + 1) % Framing.entries.size]
        getPreferences(MODE_PRIVATE).edit().putInt(PREF_FRAMING, framing.ordinal).apply()
        layoutStage()
        flashBadge(framing.label, 2_500L)
    }

    /** Sizes the picture area for the framing mode, then scales text and margins to it. */
    private fun layoutStage() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val (sw, sh) = when (framing) {
            Framing.FULL -> w to h
            Framing.CENTER_4X3 -> if (w * 3 > h * 4) (h * 4 / 3) to h else w to (w * 3 / 4)
        }
        stage.layoutParams = (stage.layoutParams as FrameLayout.LayoutParams).apply {
            width = sw; height = sh
        }
        val padX = (sw * Config.SAFE_MARGIN_X).toInt()
        val padY = (sh * Config.SAFE_MARGIN_Y).toInt()
        for (tv in listOf(badge, info, message)) {
            (tv.layoutParams as FrameLayout.LayoutParams).setMargins(padX, padY, padX, padY)
            tv.requestLayout()
        }
        badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * Config.BADGE_TEXT_SIZE)
        info.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * Config.INFO_TEXT_SIZE)
        info.setLineSpacing(sh * Config.INFO_TEXT_SIZE * 0.35f, 1f)
        message.setTextSize(TypedValue.COMPLEX_UNIT_PX, sh * Config.BADGE_TEXT_SIZE)
        stage.requestLayout()
    }

    private fun flashBadge(text: String, durationMs: Long) {
        badgeText = text
        badgeUntil = SystemClock.uptimeMillis() + durationMs
        updateOsd()
    }

    private fun showInfoFor(durationMs: Long) {
        infoUntil = SystemClock.uptimeMillis() + durationMs
        updateOsd()
    }

    private fun showBlueScreen(text: String) {
        stage.setBackgroundColor(getColor(R.color.vcr_blue))
        playerView.visibility = View.INVISIBLE
        message.text = text
        message.visibility = View.VISIBLE
        info.visibility = View.INVISIBLE
    }

    private fun updateOsd() {
        val now = SystemClock.uptimeMillis()
        val exo = player

        val paused = exo != null && !exo.playWhenReady
        when {
            now < badgeUntil -> { badge.text = badgeText; badge.visibility = View.VISIBLE }
            paused -> { badge.text = "PAUSE ||"; badge.visibility = View.VISIBLE }
            else -> badge.visibility = View.INVISIBLE
        }

        if (exo == null) {
            info.visibility = View.INVISIBLE
            return
        }
        val pos = exo.currentPosition
        val dur = exo.duration
        val showInfo = Config.INFO_ALWAYS_ON || paused || now < infoUntil ||
            pos < Config.INFO_SHOW_AT_START_MS ||
            (dur != C.TIME_UNSET && dur - pos < Config.INFO_SHOW_AT_END_MS)
        info.visibility = if (showInfo && info.text.isNotEmpty()) View.VISIBLE else View.INVISIBLE
    }

    companion object {
        private const val TAG = "Rerun"
        private const val PREF_FRAMING = "framing"
        private const val PREF_TRACKING = "tracking"
        private val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    }
}
