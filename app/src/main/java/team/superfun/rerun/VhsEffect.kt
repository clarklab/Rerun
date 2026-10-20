package team.superfun.rerun

import android.content.Context
import android.opengl.GLES20
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Bad-tape simulator: a GPU port of the NTSC/VHS model in ntsc-rs (https://github.com/valadaptive/ntsc-rs,
 * MIT/ISC/Apache-2.0), run live in Media3's effect pipeline.
 *
 * Like ntsc-rs, it doesn't paint artifacts on top of the picture. It encodes the frame into a composite NTSC signal
 * (colour riding on a 3.58 MHz carrier), adds noise, snow and tracking errors to that signal, decodes it again with a
 * notch filter, then runs it through a VCR's luma and colour-under filters. Dot crawl, rainbow fringes, ringing, colour
 * smear and colour shifts on torn lines fall out of that, the way they do on a real deck. ntsc-rs's IIR filters run
 * left to right along each line; here the same filter chains are turned into convolution kernels on the CPU (exactly,
 * up to a truncated tail) and applied on the GPU.
 *
 * On top of ntsc-rs's model it adds what a mistracking deck does over time: a noise bar that drifts up the picture,
 * flagging at the top, vertical bounce, and at the worst setting a rolling picture.
 *
 * One knob: [amount] runs from 0 (clean, frames pass straight through) to 1 in tenths; each tenth is a level in
 * [LEVELS], from a decent SP tape to a rolling picture.
 */
@OptIn(UnstableApi::class)
class VhsEffect : GlEffect {
    @Volatile var amount = 0f

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = VhsShaderProgram(this)
}

/** Everything that changes from one level to the next. Units follow ntsc-rs where it has the setting. */
private class Level(
    val lumaCutHz: Double,        // VHS luma bandwidth (SP 2.4 MHz, LP 1.9, EP 1.4)
    val chromaCutHz: Double,      // VHS colour-under bandwidth
    val chromaDelay: Int,         // colour lag, in samples
    val compositeNoise: Float,
    val snow: Float,              // already x0.01, as ntsc-rs applies it
    val lumaNoise: Float,
    val chromaNoise: Float,
    val chromaPhaseNoise: Float,
    val chromaLoss: Float,        // chance a line loses its colour
    val edgeWave: Float,          // px
    val headSwitchRows: Float,    // field rows
    val trackRows: Float,         // frame rows of tracking noise at the bottom
    val trackWave: Float,
    val trackNoise: Float,
    val trackSnow: Float,
    val barHalf: Float,           // half-height of the drifting noise bar, frame rows (0 = none)
    val barSpeed: Float,          // picture heights per second
    val flag: Float,              // px of skew at the top
    val bounceChance: Float,      // per frame
    val rollSpeed: Float,         // picture heights per second
)

// Level 1 is ntsc-rs's defaults on SP tape; each step after that wears the tape and the tracking down.
private val LEVELS = listOf(
    Level(2.4e6, 320e3, 4, 0.05f, 0f, 0.01f, 0.10f, 0.001f, 0.000025f, 0.5f, 8f, 24f, 15f, 0.25f, 0.025f, 0f, 0f, 1.5f, 0f, 0f),
    Level(2.2e6, 310e3, 4, 0.06f, 0f, 0.012f, 0.12f, 0.002f, 0.0001f, 0.7f, 8f, 28f, 18f, 0.30f, 0.035f, 0f, 0f, 2f, 0f, 0f),
    Level(1.9e6, 300e3, 5, 0.08f, 0.00005f, 0.015f, 0.15f, 0.003f, 0.0003f, 1.0f, 8f, 32f, 22f, 0.35f, 0.05f, 0f, 0f, 3f, 0f, 0f),
    Level(1.7e6, 290e3, 5, 0.10f, 0.0001f, 0.02f, 0.18f, 0.005f, 0.0006f, 1.3f, 8f, 40f, 28f, 0.45f, 0.07f, 0f, 0f, 4f, 0f, 0f),
    Level(1.4e6, 280e3, 6, 0.12f, 0.0002f, 0.025f, 0.22f, 0.008f, 0.001f, 1.7f, 9f, 48f, 35f, 0.55f, 0.10f, 12f, 0.02f, 6f, 0f, 0f),
    Level(1.3e6, 270e3, 6, 0.15f, 0.0004f, 0.03f, 0.26f, 0.012f, 0.002f, 2.2f, 9f, 60f, 45f, 0.65f, 0.14f, 20f, 0.03f, 9f, 0f, 0f),
    Level(1.2e6, 260e3, 7, 0.18f, 0.0007f, 0.035f, 0.30f, 0.018f, 0.004f, 2.8f, 10f, 72f, 55f, 0.75f, 0.18f, 30f, 0.04f, 13f, 0f, 0f),
    Level(1.1e6, 250e3, 7, 0.22f, 0.001f, 0.04f, 0.35f, 0.025f, 0.008f, 3.5f, 10f, 90f, 70f, 0.85f, 0.23f, 40f, 0.05f, 18f, 0.02f, 0f),
    Level(1.0e6, 240e3, 8, 0.26f, 0.0015f, 0.05f, 0.40f, 0.035f, 0.015f, 4.5f, 11f, 110f, 85f, 0.95f, 0.28f, 55f, 0.07f, 24f, 0.05f, 0f),
    Level(0.9e6, 230e3, 8, 0.30f, 0.002f, 0.06f, 0.50f, 0.05f, 0.025f, 5.5f, 12f, 130f, 100f, 1.0f, 0.35f, 70f, 0.09f, 30f, 0.06f, 0.35f),
)

/** ntsc-rs's filter design, used to build the convolution kernels the shaders apply. */
private object Filters {
    const val NTSC_RATE = 315_000_000.0 / 88.0 * 4.0

    /** An IIR transfer function, `den` without its leading 1 (as in ntsc-rs). */
    class Tf(val num: DoubleArray, val den: DoubleArray) {
        /** ntsc-rs `with_scale`: s * filtered + (1 - s) * input. Negative s sharpens. */
        fun scaled(s: Double): Tf {
            val n = den.size + 1
            return Tf(DoubleArray(n) { i ->
                val ni = num.getOrElse(i) { 0.0 }
                if (i == 0) s * ni + (1 - s) else s * ni + (1 - s) * den[i - 1]
            }, den)
        }

        fun impulse(len: Int): DoubleArray {
            val n = den.size + 1
            val z = DoubleArray(n)
            return DoubleArray(len) { i ->
                val x = if (i == 0) 1.0 else 0.0
                val y = z[0] + num.getOrElse(0) { 0.0 } * x
                for (j in 0 until n - 1) z[j] = z[j + 1] + num.getOrElse(j + 1) { 0.0 } * x - den[j] * y
                y
            }
        }

        /** The filter as a kernel. ntsc-rs's `delay` shifts the output left, so taps start at -delay. */
        fun kernel(delay: Int = 0) = Kernel(-delay, impulse(256))
    }

    fun lowpass(cutoff: Double, rate: Double): Tf {
        val dt = 1 / rate
        val tau = 1 / (cutoff * 2 * PI)
        val alpha = dt / (tau + dt)
        return Tf(doubleArrayOf(alpha), doubleArrayOf(-(1 - alpha)))
    }

    fun butterworth(cutoff: Double, rate: Double): Tf {
        val f = min(2 * cutoff, rate) / rate * PI
        val s = sin(f)
        val c = cos(f)
        val alpha = s / (2 * 0.7071067811865476)
        val g = 1 / (1 + alpha)
        return Tf(
            doubleArrayOf((1 - c) * 0.5 * g, (1 - c) * g, (1 - c) * 0.5 * g),
            doubleArrayOf(-2 * c * g, (1 - alpha) * g),
        )
    }

    fun notch(freq: Double, quality: Double): Tf {
        val bw = freq / quality * PI
        val f = freq * PI
        val beta = tan(bw * 0.5)
        val g = 1 / (1 + beta)
        return Tf(doubleArrayOf(g, -2 * cos(f) * g, g), doubleArrayOf(-2 * cos(f) * g, 2 * g - 1))
    }
}

/** Taps for out(x) = sum of w[i] * in(x - (start + i)). */
private class Kernel(val start: Int, val w: DoubleArray) {
    operator fun times(o: Kernel): Kernel {
        val out = DoubleArray(min(w.size + o.w.size - 1, 512))
        for (i in w.indices) {
            if (w[i] == 0.0) continue
            for (j in o.w.indices) if (i + j < out.size) out[i + j] += w[i] * o.w[j]
        }
        return Kernel(start + o.start, out)
    }

    operator fun minus(o: Kernel): Kernel {
        val s = min(start, o.start)
        val end = max(start + w.size, o.start + o.w.size)
        return Kernel(s, DoubleArray(end - s) { i ->
            val x = s + i
            w.getOrElse(x - start) { 0.0 } - o.w.getOrElse(x - o.start) { 0.0 }
        })
    }

    /** Lays the taps out from [from] for [count] taps, rescaling a lowpass so the cut tail doesn't change its gain. */
    fun toUniform(from: Int, count: Int): FloatArray {
        val out = FloatArray(count) { i -> w.getOrElse(from + i - start) { 0.0 }.toFloat() }
        val full = w.sum()
        val kept = out.sum()
        if (abs(full) > 0.5 && abs(kept) > 1e-6) for (i in out.indices) out[i] = (out[i] * full / kept).toFloat()
        return out
    }

    companion object {
        val IDENTITY = Kernel(0, doubleArrayOf(1.0))
    }
}

/** The kernels for one level, ready to upload. */
private class Kernels(level: Level) {
    // Pass 1, before modulation: ntsc-rs's input luma notch and broadcast chroma lowpass (I 1.3 MHz, Q 0.6 MHz).
    val yIn: FloatArray
    val iIn: FloatArray
    val qIn: FloatArray

    // Pass 2, composite in: Y = everything the luma goes through; chroma = the composite minus the notched luma.
    val y: FloatArray
    val c: FloatArray

    // Pass 3, chroma in (demodulated as it's read): the VHS colour-under lowpass, then broadcast chroma lowpass out.
    val i: FloatArray
    val q: FloatArray
    val chromaStart: Int

    init {
        val r = Filters.NTSC_RATE
        val demodNotch = Filters.notch(0.5, 2.0).kernel()
        yIn = demodNotch.toUniform(IN_START, IN_TAPS)
        iIn = Filters.butterworth(1.3e6, r).kernel(2).toUniform(IN_START, IN_TAPS)
        qIn = Filters.butterworth(0.6e6, r).kernel(4).toUniform(IN_START, IN_TAPS)

        val preemphasis = Filters.lowpass(315_000_000.0 / 88.0 / 2.0, r).scaled(-1.0).kernel()
        val luma = preemphasis * demodNotch *
            Filters.lowpass(2.0.pow(-4.0 * 0.5) * 0.25, 1.0).kernel() *        // luma smear 0.5
            Filters.notch(0.45, 4.0).scaled(4.0).kernel(1) *                   // ringing
            Filters.butterworth(level.lumaCutHz, r).kernel() *                 // tape luma bandwidth
            Filters.lowpass(level.lumaCutHz, r).scaled(-1.6).kernel() *        // deck's luma peaking
            Filters.butterworth(level.lumaCutHz, r).scaled(-0.5).kernel()      // VHS sharpen 0.25
        y = luma.toUniform(SPLIT_START, SPLIT_TAPS)
        c = (preemphasis * (Kernel.IDENTITY - demodNotch)).toUniform(SPLIT_START, SPLIT_TAPS)

        val colourUnder = Filters.butterworth(level.chromaCutHz, r).kernel(level.chromaDelay)
        chromaStart = -(level.chromaDelay + 4)
        i = (colourUnder * Filters.butterworth(1.3e6, r).kernel(2)).toUniform(chromaStart, CHROMA_TAPS)
        q = (colourUnder * Filters.butterworth(0.6e6, r).kernel(4)).toUniform(chromaStart, CHROMA_TAPS)
    }

    companion object {
        const val IN_START = -2
        const val IN_TAPS = 6
        const val SPLIT_START = -1
        const val SPLIT_TAPS = 20
        const val CHROMA_TAPS = 80
        /** Colour is filtered at 1/16 width: VHS colour is far softer than that, so nothing is lost. */
        const val CHROMA_DECIMATION = 16
    }
}

/** A fragment shader drawn over a full-screen quad. */
private class Pass(fragment: String) {
    private val id: Int = link(VERTEX_SHADER, PRELUDE + fragment)
    private val aPos = GLES20.glGetAttribLocation(id, "aPos")
    private val locations = HashMap<String, Int>()

    private fun loc(name: String) = locations.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }

    fun use() = GLES20.glUseProgram(id)
    fun set(name: String, v: Float) = GLES20.glUniform1f(loc(name), v)
    fun set(name: String, a: Float, b: Float) = GLES20.glUniform2f(loc(name), a, b)
    fun set(name: String, v: FloatArray) = GLES20.glUniform1fv(loc(name), v.size, v, 0)

    fun texture(name: String, unit: Int, texId: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(loc(name), unit)
    }

    fun draw(fbo: Int, x: Int, y: Int, w: Int, h: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(x, y, w, h)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    fun release() = GLES20.glDeleteProgram(id)

    companion object {
        private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw VideoFrameProcessingException("VHS shader didn't compile: $log")
            }
            return shader
        }

        private fun link(vertex: String, fragment: String): Int {
            val program = GLES20.glCreateProgram()
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw VideoFrameProcessingException("VHS shader didn't link: ${GLES20.glGetProgramInfoLog(program)}")
            return program
        }
    }
}

/** An RGBA8 texture we can render into. Signals are stored as 16-bit fixed point across two channels. */
private class Target(val width: Int, val height: Int) {
    val texture: Int
    val fbo: Int

    init {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
    }

    fun release() {
        GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

@OptIn(UnstableApi::class)
private class VhsShaderProgram(private val effect: VhsEffect) :
    BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {

    private val copy = Pass(COPY)
    private val encode = Pass(ENCODE)
    private val split = Pass(SPLIT)
    private val colourUnder = Pass(COLOUR_UNDER)
    private val output = Pass(OUTPUT)
    private val passes = listOf(copy, encode, split, colourUnder, output)

    private var composite: Target? = null
    private var splitTarget: Target? = null
    private var chroma: Target? = null
    private var width = 0
    private var height = 0

    private var kernelsFor = -1
    private lateinit var kernels: Kernels
    private var frame = 0
    private var bounce = 0f
    private var roll = 0f
    private var lastDrawMs = 0L
    private val saved = IntArray(4)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (inputWidth != width || inputHeight != height) {
            releaseTargets()
            width = inputWidth
            height = inputHeight
            // Like a VCR, work one field (every other line) at a time; the output pass line-doubles it.
            val fieldHeight = max(inputHeight / 2, 1)
            val chromaWidth = (inputWidth + Kernels.CHROMA_DECIMATION - 1) / Kernels.CHROMA_DECIMATION
            composite = Target(inputWidth, fieldHeight)
            splitTarget = Target(inputWidth, fieldHeight)
            chroma = Target(chromaWidth, fieldHeight)
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val levelIndex = (effect.amount.coerceIn(0f, 1f) * LEVELS.size).roundToInt()
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, saved, 0)
            val outFbo = saved[0]
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, saved, 0)
            GLES20.glDisable(GLES20.GL_BLEND)
            if (levelIndex == 0) {
                copy.use()
                copy.texture("uTex", 0, inputTexId)
                copy.draw(outFbo, saved[0], saved[1], saved[2], saved[3])
                GlUtil.checkGlError()
                return
            }
            draw(LEVELS[levelIndex - 1], levelIndex, inputTexId, outFbo)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun draw(level: Level, levelIndex: Int, inputTexId: Int, outFbo: Int) {
        val composite = composite ?: return
        val splitOut = splitTarget ?: return
        val chroma = chroma ?: return
        if (kernelsFor != levelIndex) {
            kernels = Kernels(level)
            kernelsFor = levelIndex
        }
        val k = kernels
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.elapsedRealtime()
        val t = (now % 1_000_000L) / 1000f
        val dt = if (lastDrawMs == 0L) 0f else ((now - lastDrawMs) / 1000f).coerceIn(0f, 0.25f)
        lastDrawMs = now
        frame = (frame + 1) % 4096
        val barCenter = h * (1f - ((t * level.barSpeed + 0.3f) % 1f))
        roll = if (level.rollSpeed > 0f) (roll + dt * level.rollSpeed * (h + VBI_ROWS)) % (h + VBI_ROWS) else 0f
        bounce = if (Math.random() < level.bounceChance) (2 + (Math.random() * 7).toInt()).toFloat() else bounce * 0.5f

        for (p in passes) {
            p.use()
            p.set("uSize", w, h)
            p.set("uFrame", frame.toFloat())
            p.set("uParity", (frame % 2).toFloat())
            p.set("uFieldH", composite.height.toFloat())
            p.set("uTime", t)
            p.set("uTrackRows", level.trackRows)
            p.set("uBarCenter", barCenter)
            p.set("uBarHalf", level.barHalf)
        }

        encode.use()
        encode.texture("uTex", 0, inputTexId)
        encode.set("uKYin", k.yIn)
        encode.set("uKIin", k.iIn)
        encode.set("uKQin", k.qIn)
        encode.set("uInStart", Kernels.IN_START.toFloat())
        encode.set("uCompNoise", level.compositeNoise)
        encode.set("uSnow", level.snow)
        encode.set("uTrackNoise", level.trackNoise)
        encode.set("uTrackSnow", level.trackSnow)
        encode.draw(composite.fbo, 0, 0, composite.width, composite.height)


        split.use()
        split.texture("uComp", 0, composite.texture)
        split.set("uKY", k.y)
        split.set("uKC", k.c)
        split.set("uKStart", Kernels.SPLIT_START.toFloat())
        split.set("uHsRows", level.headSwitchRows)
        split.set("uTrackWave", level.trackWave)
        split.set("uFlag", level.flag)
        split.draw(splitOut.fbo, 0, 0, splitOut.width, splitOut.height)


        colourUnder.use()
        colourUnder.texture("uSplit", 0, splitOut.texture)
        colourUnder.set("uChromaNoise", level.chromaNoise)
        colourUnder.set("uPhaseNoise", level.chromaPhaseNoise)
        colourUnder.set("uKI", k.i)
        colourUnder.set("uKQ", k.q)
        colourUnder.set("uKCStart", k.chromaStart.toFloat())
        colourUnder.draw(chroma.fbo, 0, 0, chroma.width, chroma.height)


        output.use()
        output.texture("uSplit", 0, splitOut.texture)
        output.texture("uChroma", 1, chroma.texture)
        output.set("uEdgeAmp", level.edgeWave)
        output.set("uLumaNoise", level.lumaNoise)
        output.set("uChromaLoss", level.chromaLoss)
        output.set("uRoll", roll)
        output.set("uBounce", bounce)
        output.set("uVbiRows", VBI_ROWS)
        output.draw(outFbo, saved[0], saved[1], saved[2], saved[3])
    }

    private fun releaseTargets() {
        listOfNotNull(composite, splitTarget, chroma).forEach { it.release() }
        composite = null
        splitTarget = null
        chroma = null
    }

    override fun release() {
        super.release()
        releaseTargets()
        passes.forEach { it.release() }
    }

    companion object {
        /** Blank lines between frames, seen as a black bar when the picture rolls. */
        const val VBI_ROWS = 40f
    }
}

private const val VERTEX_SHADER = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
  gl_Position = vec4(aPos, 0.0, 1.0);
  vUv = aPos * 0.5 + 0.5;
}
"""

/** Shared by every pass. Rows count from the top of the picture; x and rows are whole sample indices. */
private const val PRELUDE = """
precision highp float;
varying vec2 vUv;
uniform vec2 uSize;      // full frame
uniform float uFieldH;   // rows in one field (the working textures)
uniform float uParity;   // which field this frame uses
uniform float uFrame;
uniform float uTime;
uniform float uTrackRows;
uniform float uBarCenter;
uniform float uBarHalf;

const mat3 RGB_TO_YIQ = mat3(0.299, 0.596, 0.211, 0.587, -0.274, -0.523, 0.114, -0.322, 0.312);
const mat3 YIQ_TO_RGB = mat3(1.0, 1.0, 1.0, 0.956, -0.272, -1.106, 0.621, -0.647, 1.703);

// Sin-free hash (Dave Hoskins): fract(sin(big)) stops looking random on mobile GPUs.
float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

// 1D gradient noise, about -1..1 (stands in for ntsc-rs's simplex noise).
float gnoise(float x, float seed) {
  float i = floor(x);
  float f = fract(x);
  float g0 = hash(vec2(i, seed)) * 2.0 - 1.0;
  float g1 = hash(vec2(i + 1.0, seed)) * 2.0 - 1.0;
  float u = f * f * (3.0 - 2.0 * f);
  return mix(g0 * f, g1 * (f - 1.0), u) * 2.0;
}

float vnoise2(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  float a = hash(i);
  float b = hash(i + vec2(1.0, 0.0));
  float c = hash(i + vec2(0.0, 1.0));
  float d = hash(i + vec2(1.0, 1.0));
  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y) * 2.0 - 1.0;
}

// Signals live in RGBA8 textures as 16-bit fixed point split over two channels (linear, so filtering still works).
vec2 pack(float v, float lo, float range) {
  float u = clamp((v - lo) / range, 0.0, 1.0) * 255.0;
  return vec2(floor(u) / 255.0, fract(u));
}
float unpack(vec2 e, float lo, float range) { return (e.x + e.y / 255.0) * range + lo; }

// Row within the field (working passes), from the top.
float fieldRow() { return uFieldH - 0.5 - gl_FragCoord.y; }
// The matching row of the full frame.
float frameRow(float fieldRow) { return fieldRow * 2.0 + uParity; }
// Texture v for a field row in the working textures.
float fieldV(float fieldRow) { return (uFieldH - 0.5 - fieldRow) / uFieldH; }

// Colour subcarrier phase at four samples per cycle, alternating by line and field like NTSC (ntsc-rs chroma_phase_shift).
float carrierPhase(float x, float fieldRow) {
  float xi = mod(uFrame * 2.0 + uParity + 2.0 * fieldRow, 4.0) >= 2.0 ? 2.0 : 0.0;
  return mod(x + xi, 4.0);
}
float carrierI(float ph) { return ph < 0.5 ? 1.0 : ((ph > 1.5 && ph < 2.5) ? -1.0 : 0.0); }
float carrierQ(float ph) { return (ph > 0.5 && ph < 1.5) ? 1.0 : (ph > 2.5 ? -1.0 : 0.0); }

// How hard tracking noise hits a row: ntsc-rs's ramp into the bottom of the picture, plus a drifting bar.
float trackScale(float row) {
  float bottom = clamp((row - (uSize.y - uTrackRows)) / max(uTrackRows, 1.0), 0.0, 1.0);
  float d = abs(row - uBarCenter);
  d = min(d, uSize.y - d);
  float bar = uBarHalf > 0.0 ? pow(clamp(1.0 - d / uBarHalf, 0.0, 1.0), 0.6) : 0.0;
  return max(bottom, bar);
}

// ntsc-rs row_speckles: short decaying transients ("snow") along a line, clumped per line by anisotropy.
// Most lines get none, so decide that once per line before looking for transients near this sample.
float speckles(float x, float row, float intensity, float anisotropy, float seed) {
  if (intensity <= 0.0) return 0.0;
  float r = hash(vec2(row, seed));
  float denom = max(intensity * (1.0 - intensity) * (1.0 - anisotropy), 1e-6);
  float logistic = exp(clamp((r - intensity) / denom, -30.0, 30.0));
  float lineIntensity = clamp((anisotropy / (1.0 + logistic) + intensity * (1.0 - anisotropy)) * 0.125, 0.0, 1.0);
  float lineChance = min(1.0, lineIntensity * uSize.x);
  if (hash(vec2(row, seed + 0.37)) >= lineChance) return 0.0;
  // Given the line has snow, each 16-sample cell starts a transient with this chance.
  float cellChance = min(1.0, lineIntensity * 16.0 / lineChance);
  float sum = 0.0;
  float cell0 = floor(x / 16.0);
  for (int c = 0; c < 5; c++) {
    float cell = cell0 - float(c);
    vec2 id = vec2(cell, row * 7.31 + seed);
    if (hash(id) < cellChance) {
      float start = cell * 16.0 + floor(hash(id + 0.5) * 16.0);
      float len = mix(8.0, 64.0, hash(id + 1.7));
      float d = x - start;
      if (d >= 0.0 && d < len) {
        float freq = len * mix(3.0, 5.0, hash(id + 2.9));
        float fall = 1.0 - d / len;
        sum += cos(d * 3.14159265 / freq) * fall * fall * mix(-1.0, 2.0, hash(id + 4.1));
      }
    }
  }
  return sum;
}
"""

private const val COPY = """
uniform sampler2D uTex;
void main() { gl_FragColor = texture2D(uTex, vUv); }
"""

/** Pass 1: RGB to composite NTSC, plus composite noise, snow and tracking noise (all added to the signal itself). */
private const val ENCODE = """
uniform sampler2D uTex;
uniform float uKYin[6];
uniform float uKIin[6];
uniform float uKQin[6];
uniform float uInStart;
uniform float uCompNoise;
uniform float uSnow;
uniform float uTrackNoise;
uniform float uTrackSnow;

void main() {
  float x = gl_FragCoord.x - 0.5;
  float frow = fieldRow();
  float row = frameRow(frow);
  float v = (uSize.y - 0.5 - row) / uSize.y;
  // The filters are linear, so filter RGB per kernel and convert once.
  vec3 ys = vec3(0.0);
  vec3 is = vec3(0.0);
  vec3 qs = vec3(0.0);
  for (int k = 0; k < 6; k++) {
    float sx = clamp(x - (float(k) + uInStart), 0.0, uSize.x - 1.0);
    vec3 rgb = texture2D(uTex, vec2((sx + 0.5) / uSize.x, v)).rgb;
    ys += uKYin[k] * rgb;
    is += uKIin[k] * rgb;
    qs += uKQin[k] * rgb;
  }
  float ph = carrierPhase(x, frow);
  float c = dot(ys, vec3(0.299, 0.587, 0.114))
      + dot(is, vec3(0.596, -0.274, -0.322)) * carrierI(ph)
      + dot(qs, vec3(0.211, -0.523, 0.312)) * carrierQ(ph);

  float seed = hash(vec2(row, uFrame * 1.37 + 3.0)) * 977.0;
  c += gnoise(x * 0.5 + seed, seed) * uCompNoise * 0.25;
  float ts = trackScale(row);
  float trackSnow = uTrackSnow * ts * ts;
  if (ts > 0.0) c += gnoise(x * 0.25 + seed * 1.3, seed + 5.0) * ts * ts * uTrackNoise;
  // Background snow and tracking snow in one pass (ntsc-rs uses anisotropy 0.5 and 0.25 for them).
  float snow = uSnow + trackSnow;
  c += speckles(x, row, snow, mix(0.5, 0.25, trackSnow / max(snow, 1e-6)), 11.0 + uFrame * 3.17);
  gl_FragColor = vec4(pack(c, -2.5, 6.0), 0.0, 1.0);
}
"""

/**
 * Pass 2: shift lines in the composite signal (head switching, tracking, flagging), then split it with the kernels:
 * luma through the notch and the deck's luma chain, chroma as what the notch took out.
 */
private const val SPLIT = """
uniform sampler2D uComp;
uniform float uKY[20];
uniform float uKC[20];
uniform float uKStart;
uniform float uHsRows;
uniform float uTrackWave;
uniform float uFlag;

void main() {
  float x = gl_FragCoord.x - 0.5;
  float frow = fieldRow();
  float row = frameRow(frow);
  float v = fieldV(frow);
  float field = uFrame * 2.0 + uParity;

  // Head switching (ntsc-rs defaults: 8 rows, offset 3, 72 px, mid-line at 95%).
  float shift = 0.0;
  float copyStart = -1.0;
  float transient = 0.0;
  float hsOffset = 3.0;
  float affected = uHsRows - hsOffset;
  float firstRow = uFieldH - affected;
  if (frow >= firstRow) {
    float idx = affected - (frow - firstRow);
    shift += 72.0 * pow((idx + hsOffset) / uHsRows, 1.5) + hash(vec2(idx, field + 41.0)) - 0.5;
    if (idx > affected - 0.5) {
      float jitter = (hash(vec2(field, 7.0)) + hash(vec2(field, 8.0))) * 0.5 - 0.5;
      copyStart = floor(uSize.x * (0.95 + jitter * 0.03));
      transient = (hash(vec2(field, 9.0)) + 0.5) * 0.5;
    }
  }
  // Tracking error wobbles the lines it hits.
  float ts = trackScale(row);
  shift += gnoise(frow * 0.5 + hash(vec2(field, 3.0)) * 400.0, 17.0) * ts * uTrackWave * 0.25;
  // Flagging: tape tension bends the top of the picture.
  float flag = clamp(1.0 - row / (uSize.y * 0.08), 0.0, 1.0);
  shift += flag * flag * uFlag * (0.6 + 0.4 * gnoise(uTime * 1.7, 5.0));

  // Sum the still-packed samples and unpack once at the end (unpacking is linear). Samples off the line are 0.
  vec2 ay = vec2(0.0);
  vec2 ac = vec2(0.0);
  float wy = 0.0;
  float wc = 0.0;
  float extra = 0.0;
  float extraC = 0.0;
  if (copyStart < 0.0) {
    float base = x - uKStart - shift;
    for (int k = 0; k < 20; k++) {
      float sx = base - float(k);
      float inside = step(-0.5, sx) * step(sx, uSize.x - 0.5);
      vec2 e = texture2D(uComp, vec2((sx + 0.5) / uSize.x, v)).rg;
      float ky = uKY[k] * inside;
      float kc = uKC[k] * inside;
      ay += ky * e;
      ac += kc * e;
      wy += ky;
      wc += kc;
    }
  } else {
    // The head-switch line: only the part right of copyStart is shifted, with a spike where it starts.
    for (int k = 0; k < 20; k++) {
      float xd = x - (float(k) + uKStart);
      float sx = xd < copyStart ? xd : xd - shift;
      float inside = step(-0.5, sx) * step(sx, uSize.x - 0.5);
      vec2 e = texture2D(uComp, vec2((sx + 0.5) / uSize.x, v)).rg;
      float ky = uKY[k] * inside;
      float kc = uKC[k] * inside;
      ay += ky * e;
      ac += kc * e;
      wy += ky;
      wc += kc;
      float f = clamp(1.0 - (xd - copyStart) / 16.0, 0.0, 1.0) * step(copyStart, xd);
      extra += uKY[k] * f * f * f * transient;
      extraC += uKC[k] * f * f * f * transient;
    }
  }
  float luma = (ay.x + ay.y / 255.0) * 6.0 - 2.5 * wy + extra;
  float chroma = (ac.x + ac.y / 255.0) * 6.0 - 2.5 * wc + extraC;
  gl_FragColor = vec4(pack(luma, -1.0, 3.0), pack(chroma, -3.0, 6.0));
}
"""

/**
 * Pass 3, at 1/16 width: demodulate the chroma and run it through the VHS colour-under lowpass and broadcast chroma
 * lowpass. Multiplying by the carrier and then lowpassing gives the same result as ntsc-rs's 3-tap demodulator followed
 * by these filters. Colour noise and per-line phase noise come after, already as soft as the filters would leave them.
 */
private const val COLOUR_UNDER = """
uniform sampler2D uSplit;
uniform float uKI[80];
uniform float uKQ[80];
uniform float uKCStart;
uniform float uChromaNoise;
uniform float uPhaseNoise;

void main() {
  float xc = (gl_FragCoord.x - 0.5) * 16.0 + 8.0;
  float frow = fieldRow();
  float row = frameRow(frow);
  float v = fieldV(frow);
  float xi = mod(uFrame * 2.0 + uParity + 2.0 * frow, 4.0) >= 2.0 ? 2.0 : 0.0;
  vec2 iq = vec2(0.0);
  for (int k = 0; k < 80; k++) {
    float sx = xc - (float(k) + uKCStart);
    float inside = step(0.0, sx) * step(sx, uSize.x - 1.0);
    float c = unpack(texture2D(uSplit, vec2((sx + 0.5) / uSize.x, v)).ba, -3.0, 6.0) * inside;
    float ph = mod(sx + xi, 4.0);
    iq += 2.0 * c * vec2(uKI[k] * carrierI(ph), uKQ[k] * carrierQ(ph));
  }
  float si = hash(vec2(row, uFrame + 101.0)) * 977.0;
  float sq = hash(vec2(row, uFrame + 151.0)) * 977.0;
  iq.x += gnoise(xc * 0.02 + si, si) * uChromaNoise * 0.12;
  iq.y += gnoise(xc * 0.02 + sq, sq) * uChromaNoise * 0.12;
  float angle = (hash(vec2(row, uFrame + 211.0)) - 0.5) * 2.0 * uPhaseNoise * 6.2831853;
  float ca = cos(angle);
  float sa = sin(angle);
  iq = vec2(iq.x * ca - iq.y * sa, iq.x * sa + iq.y * ca);
  gl_FragColor = vec4(pack(iq.x, -1.5, 3.0), pack(iq.y, -1.5, 3.0));
}
"""

/** Pass 4: edge wave, colour blended with the line above (as VHS does), luma noise, colour loss, roll, back to RGB. */
private const val OUTPUT = """
uniform sampler2D uSplit;
uniform sampler2D uChroma;
uniform float uEdgeAmp;
uniform float uLumaNoise;
uniform float uChromaLoss;
uniform float uRoll;
uniform float uBounce;
uniform float uVbiRows;

vec2 chromaAt(float sx, float v) {
  vec4 e = texture2D(uChroma, vec2((sx + 0.5) / uSize.x, v));
  return vec2(unpack(e.rg, -1.5, 3.0), unpack(e.ba, -1.5, 3.0));
}

void main() {
  float x = gl_FragCoord.x - 0.5;
  float rolled = mod(uSize.y - 0.5 - gl_FragCoord.y + uRoll + uBounce, uSize.y + uVbiRows);
  if (rolled >= uSize.y) {
    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    return;
  }
  float row = floor(rolled);
  float frow = min(floor(row * 0.5), uFieldH - 1.0);   // line-double the field
  float v = fieldV(frow);

  // ntsc-rs edge wave: smooth noise down the picture that moves every frame.
  vec2 p = vec2(frow * 0.05, uFrame * 0.2);
  float wave = vnoise2(p) + 0.707 * vnoise2(p * 2.0 + 13.0);
  float sx = clamp(x - wave * uEdgeAmp, 0.0, uSize.x - 1.0);

  float luma = unpack(texture2D(uSplit, vec2((sx + 0.5) / uSize.x, v)).rg, -1.0, 3.0);
  vec2 iq = chromaAt(sx, v);
  if (frow >= 1.0) iq = (iq + chromaAt(sx, v + 1.0 / uFieldH)) * 0.5;

  float seed = hash(vec2(frow, uFrame + 307.0)) * 977.0;
  luma += gnoise(x * 0.5 + seed, seed) * uLumaNoise * 0.25;
  if (hash(vec2(frow, uFrame + 401.0)) < uChromaLoss) iq = vec2(0.0);

  gl_FragColor = vec4(clamp(YIQ_TO_RGB * vec3(luma, iq), 0.0, 1.0), 1.0);
}
"""
