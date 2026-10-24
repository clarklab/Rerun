package team.superfun.rerun

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One movie on the guide, with the clips that belong to it. */
data class Channel(val number: Int, val title: String, val year: String, val clips: List<Int>)

/**
 * An old cable-channel program guide, in the style of the late-80s Prevue grid: the live picture in a window at
 * the top left, a header with the clock beside it, and a grid of channels underneath, one per movie.
 * The picture itself is the PlayerView moved into [videoWindow]; this view leaves that rectangle clear.
 */
class GuideView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var channels: List<Channel> = emptyList()
        set(value) {
            field = value
            selected = selected.coerceIn(0, (value.size - 1).coerceAtLeast(0))
            invalidate()
        }

    var selected = 0
        private set

    var typeface: Typeface = Typeface.MONOSPACE

    private var firstRow = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()
    private val clock = SimpleDateFormat("h:mm a", Locale.US)
    private val date = SimpleDateFormat("EEE MMM d", Locale.US)
    private val slot = SimpleDateFormat("h:mma", Locale.US)

    fun select(index: Int) {
        if (channels.isEmpty()) return
        selected = index.coerceIn(0, channels.size - 1)
        invalidate()
    }

    fun move(delta: Int) = select(selected + delta)

    /** Rows that fit on screen, for paging with Left/Right. */
    val pageSize: Int get() = layout(width.toFloat(), height.toFloat()).rows.coerceAtLeast(1)

    /** Where the live picture goes, in this view's coordinates (the guide fills the stage). */
    fun videoWindow(w: Int, h: Int): Rect {
        val l = layout(w.toFloat(), h.toFloat())
        return Rect(l.window.left.toInt(), l.window.top.toInt(), l.window.right.toInt(), l.window.bottom.toInt())
    }

    private class Layout(
        val left: Float, val right: Float, val window: RectF, val gridTop: Float,
        val rowH: Float, val gap: Float, val rows: Int, val footerTop: Float,
    )

    private fun layout(w: Float, h: Float): Layout {
        val padX = w * Config.SAFE_MARGIN_X
        val padY = h * Config.SAFE_MARGIN_Y
        val innerW = w - 2 * padX
        val winW = innerW * 0.36f
        // Same shape as the screen, so the picture in it keeps its proportions.
        val window = RectF(padX, padY, padX + winW, padY + winW * h / w)
        val gap = h * 0.006f
        val rowH = h * 0.058f
        val gridTop = window.bottom + h * 0.025f
        val footerTop = h - padY - rowH * 0.8f
        val rows = ((footerTop - gap - (gridTop + rowH + gap)) / (rowH + gap)).toInt()
        return Layout(padX, w - padX, window, gridTop, rowH, gap, rows, footerTop)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val l = layout(w, h)
        paint.typeface = typeface
        paint.textScaleX = Config.TEXT_SCALE_X

        // Navy everywhere except the picture window.
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        path.addRect(l.window, Path.Direction.CW)
        paint.color = NAVY
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.005f
        paint.color = FRAME
        canvas.drawRect(l.window, paint)
        paint.style = Paint.Style.FILL

        // Header beside the picture.
        val hx = l.window.right + w * 0.04f
        val now = Date()
        text(canvas, "RERUN", hx, l.window.top + l.rowH * 1.15f, l.rowH * 1.15f, YELLOW)
        text(canvas, "CABLE GUIDE", hx, l.window.top + l.rowH * 2.0f, l.rowH * 0.5f, CYAN)
        text(canvas, clock.format(now).uppercase(), hx, l.window.top + l.rowH * 3.4f, l.rowH * 0.8f, WHITE)
        text(canvas, date.format(now).uppercase(), hx, l.window.top + l.rowH * 4.2f, l.rowH * 0.5f, WHITE)
        text(canvas, "${channels.size} CHANNELS", hx, l.window.bottom - l.rowH * 0.15f, l.rowH * 0.45f, CYAN)

        // Column widths.
        val innerW = l.right - l.left
        val chW = innerW * 0.12f
        val countW = innerW * 0.17f
        val titleL = l.left + chW + l.gap
        val countL = l.right - countW
        val titleR = countL - l.gap

        // Time-slot header, like the grid's half-hour columns.
        var y = l.gridTop
        cell(canvas, l.left, y, l.left + chW, y + l.rowH, HEADER)
        cell(canvas, titleL, y, l.right, y + l.rowH, HEADER)
        text(canvas, "CH", l.left + chW * 0.22f, baseline(y, l.rowH), l.rowH * 0.5f, CYAN)
        val cal = Calendar.getInstance().apply {
            set(Calendar.MINUTE, if (get(Calendar.MINUTE) < 30) 0 else 30)
        }
        val slots = (0 until 3).map { i ->
            slot.format(cal.time).uppercase().removeSuffix("M").also { cal.add(Calendar.MINUTE, 30) }
        }
        val slotW = (l.right - titleL) / 3f
        slots.forEachIndexed { i, s -> text(canvas, s, titleL + slotW * i + l.rowH * 0.3f, baseline(y, l.rowH), l.rowH * 0.5f, CYAN) }
        y += l.rowH + l.gap

        // Channel rows, scrolled so the selection stays in view.
        val rows = l.rows.coerceAtLeast(1)
        if (selected < firstRow) firstRow = selected
        if (selected >= firstRow + rows) firstRow = selected - rows + 1
        firstRow = firstRow.coerceIn(0, (channels.size - rows).coerceAtLeast(0))
        for (i in firstRow until minOf(channels.size, firstRow + rows)) {
            val ch = channels[i]
            val on = i == selected
            cell(canvas, l.left, y, l.left + chW, y + l.rowH, if (on) YELLOW else CHANNEL)
            cell(canvas, titleL, y, titleR, y + l.rowH, if (on) YELLOW else PROGRAM)
            cell(canvas, countL, y, l.right, y + l.rowH, if (on) YELLOW else CHANNEL)
            val size = l.rowH * 0.5f
            val base = baseline(y, l.rowH)
            text(canvas, "%02d".format(ch.number), l.left + chW * 0.22f, base, size, if (on) NAVY else YELLOW)
            val title = listOf(ch.title.uppercase(), ch.year).filter { it.isNotBlank() }.joinToString(" ")
            text(canvas, fit(title, titleR - titleL - l.rowH * 0.6f, size), titleL + l.rowH * 0.3f, base, size, if (on) NAVY else WHITE)
            val count = if (ch.clips.size == 1) "1 CLIP" else "${ch.clips.size} CLIPS"
            text(canvas, count, countL + l.rowH * 0.3f, base, size * 0.85f, if (on) NAVY else CYAN)
            y += l.rowH + l.gap
        }

        // "More" arrows when the list runs past the screen: up in the time header, down by the footer.
        val ax = l.right - l.rowH * 0.45f
        if (firstRow > 0) arrow(canvas, ax, l.gridTop + l.rowH * 0.3f, l.rowH * 0.25f, up = true)
        if (firstRow + rows < channels.size) arrow(canvas, ax, l.footerTop + l.rowH * 0.6f, l.rowH * 0.25f, up = false)

        text(canvas, "UP/DOWN CHANNEL   OK WATCH   BACK CLOSE", l.left, l.footerTop + l.rowH * 0.55f, l.rowH * 0.42f, CYAN)
    }

    private fun baseline(top: Float, rowH: Float) = top + rowH * 0.68f

    private fun cell(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        paint.color = color
        rect.set(l, t, r, b)
        canvas.drawRect(rect, paint)
    }

    private fun text(canvas: Canvas, s: String, x: Float, y: Float, size: Float, color: Int) {
        paint.textSize = size
        paint.color = color
        canvas.drawText(s, x, y, paint)
    }

    /** Trims a title to fit its cell, VCR-style with "...". */
    private fun fit(s: String, maxW: Float, size: Float): String {
        paint.textSize = size
        if (paint.measureText(s) <= maxW) return s
        var n = s.length
        while (n > 1 && paint.measureText(s.substring(0, n) + "...") > maxW) n--
        return s.substring(0, n).trimEnd() + "..."
    }

    private fun arrow(canvas: Canvas, x: Float, y: Float, size: Float, up: Boolean) {
        path.reset()
        path.fillType = Path.FillType.WINDING
        if (up) {
            path.moveTo(x - size, y + size); path.lineTo(x + size, y + size); path.lineTo(x, y)
        } else {
            path.moveTo(x - size, y - size); path.lineTo(x + size, y - size); path.lineTo(x, y)
        }
        path.close()
        paint.color = YELLOW
        canvas.drawPath(path, paint)
    }

    companion object {
        private const val NAVY = 0xFF0A1050.toInt()
        private const val HEADER = 0xFF101C78.toInt()
        private const val CHANNEL = 0xFF16248C.toInt()
        private const val PROGRAM = 0xFF2A46C6.toInt()
        private const val FRAME = 0xFF8FD8FF.toInt()
        private const val YELLOW = 0xFFF4D61E.toInt()
        private const val CYAN = 0xFF8FD8FF.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()

        /** One channel per movie, numbered from 2 like a cable box, in title order ("The" and "A" ignored). */
        fun channelsFor(clips: List<Clip>): List<Channel> {
            fun sortKey(title: String) = title.lowercase().removePrefix("the ").removePrefix("a ").removePrefix("an ")
            return clips.indices.groupBy { clips[it].movie }.values
                .sortedWith(compareBy({ sortKey(clips[it.first()].channelTitle) }, { clips[it.first()].year }))
                .mapIndexed { i, members ->
                    Channel(i + 2, clips[members.first()].channelTitle, clips[members.first()].year, members)
                }
        }
    }
}
