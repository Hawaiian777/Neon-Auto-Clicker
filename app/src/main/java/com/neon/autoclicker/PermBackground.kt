package com.neon.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.sin
import kotlin.random.Random

/**
 * Background of the Permission card.
 *  OFF: black base with a red diagonal caution grid and a red edge, like a locked-out
 *       electrical panel. Static, so it costs nothing.
 *  ON:  dark electric green with faint circuit traces; small bright pulses of "current"
 *       run along the traces and the edge breathes gently. It only animates while the card
 *       is on screen (about 30 frames a second), and stops when the app goes to the background.
 */
class PermBackground(context: Context) : Drawable(), Animatable, Runnable {

    private val d = context.resources.displayMetrics.density
    private val radius = 16f * d
    private val box = RectF()
    private val edgeBox = RectF()
    private val clip = Path()

    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private class Trace(val path: Path, val measure: PathMeasure, val length: Float, val speed: Float, val offset: Float, val gap: Float)
    private val traces = mutableListOf<Trace>()
    private val seg = Path()
    private val pos = FloatArray(2)

    private var running = false
    private var startedAt = SystemClock.uptimeMillis()

    /** Required permissions all on? */
    var on: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            update()
            invalidateSelf()
        }

    /** False while the card is scrolled out of view: no animation then. */
    var onScreen: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            update()
        }

    private fun update() {
        if (on && isVisible && onScreen) start() else stop()
    }

    // Gradients depend only on the card's size, so they're built once per size.
    private var offShade: Shader? = null
    private var onBase: Shader? = null

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        box.set(bounds)
        clip.reset()
        clip.addRoundRect(box, radius, radius, Path.Direction.CW)
        // a darker band top and bottom keeps text easy to read on the red grid
        offShade = LinearGradient(
            box.left, box.top, box.left, box.bottom,
            intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(60, 0, 0, 0), Color.argb(150, 0, 0, 0)),
            null, Shader.TileMode.CLAMP
        )
        onBase = LinearGradient(
            box.left, box.top, box.right, box.bottom,
            Color.parseColor("#021A0A"), Color.parseColor("#04220E"), Shader.TileMode.CLAMP
        )
        buildTraces()
    }

    /** Circuit-board style lines: straight runs with 45° jogs, left to right. Same layout every time. */
    private fun buildTraces() {
        traces.clear()
        val w = box.width()
        val h = box.height()
        if (w <= 0f || h <= 0f) return
        val rnd = Random(7)
        val count = (h / (34f * d)).toInt().coerceIn(3, 8)
        for (i in 0 until count) {
            val p = Path()
            var x = box.left - 4f * d
            var y = box.top + h * (i + 0.5f) / count + (rnd.nextFloat() - 0.5f) * 10f * d
            p.moveTo(x, y)
            while (x < box.right + 4f * d) {
                x += (28f + rnd.nextFloat() * 70f) * d
                p.lineTo(x, y)
                if (rnd.nextFloat() < 0.65f) {
                    val jog = (8f + rnd.nextFloat() * 14f) * d * if (rnd.nextBoolean()) 1f else -1f
                    val ny = (y + jog).coerceIn(box.top + 8f * d, box.bottom - 8f * d)
                    x += kotlin.math.abs(ny - y)
                    y = ny
                    p.lineTo(x, y)
                }
            }
            val m = PathMeasure(p, false)
            traces += Trace(
                path = p,
                measure = m,
                length = m.length,
                speed = (110f + rnd.nextFloat() * 90f) * d, // px per second
                offset = rnd.nextFloat() * 4000f,
                gap = (120f + rnd.nextFloat() * 260f) * d,
            )
        }
    }

    override fun draw(c: Canvas) {
        if (box.isEmpty) return
        c.save()
        c.clipPath(clip)
        if (on) drawOn(c) else drawOff(c)
        c.restore()
        // edge
        edge.strokeWidth = maxOf(1f, 0.75f * d)
        val inset = edge.strokeWidth / 2f
        edgeBox.set(box.left + inset, box.top + inset, box.right - inset, box.bottom - inset)
        val r = edgeBox
        if (on) {
            val t = (SystemClock.uptimeMillis() - startedAt) / 1000f
            val breathe = 0.65f + 0.35f * (0.5f + 0.5f * sin(t * 2.6f)) // gentle 2.4 s pulse
            edge.color = Color.argb((255 * breathe).toInt(), 57, 255, 20)
        } else {
            edge.color = Color.argb(220, 255, 42, 42)
        }
        c.drawRoundRect(r, radius - inset, radius - inset, edge)
    }

    private fun drawGrid(c: Canvas, color: Int, spacing: Float, width: Float) {
        grid.color = color
        grid.strokeWidth = width
        val w = box.width()
        val h = box.height()
        var k = -h
        while (k < w + h) {
            // "\" and "/" diagonals
            c.drawLine(box.left + k, box.top, box.left + k + h, box.bottom, grid)
            c.drawLine(box.left + k + h, box.top, box.left + k, box.bottom, grid)
            k += spacing
        }
    }

    private fun drawOff(c: Canvas) {
        base.shader = null
        base.color = Color.parseColor("#070101")
        c.drawRect(box, base)
        drawGrid(c, Color.argb(70, 255, 42, 42), 16f * d, 1f * d)
        base.shader = offShade
        c.drawRect(box, base)
        base.shader = null
    }

    private fun drawOn(c: Canvas) {
        base.shader = onBase
        c.drawRect(box, base)
        base.shader = null
        drawGrid(c, Color.argb(22, 57, 255, 20), 16f * d, 1f * d)

        // faint circuit traces
        trace.strokeWidth = 1.2f * d
        trace.color = Color.argb(60, 57, 255, 20)
        traces.forEach { c.drawPath(it.path, trace) }

        // current pulses
        val now = SystemClock.uptimeMillis() / 1000f
        val tail = 46f * d
        traces.forEach { tr ->
            val cycle = tr.length + tail + tr.gap
            val head = ((now * tr.speed + tr.offset * d) % cycle)
            if (head > tr.length + tail) return@forEach
            val from = (head - tail).coerceAtLeast(0f)
            val to = head.coerceAtMost(tr.length)
            if (to <= from) return@forEach
            seg.reset()
            tr.measure.getSegment(from, to, seg, true)
            trace.strokeWidth = 5f * d
            trace.color = Color.argb(45, 57, 255, 20)
            c.drawPath(seg, trace)
            trace.strokeWidth = 1.8f * d
            trace.color = Color.argb(185, 120, 255, 90)
            c.drawPath(seg, trace)
            if (head <= tr.length && tr.measure.getPosTan(head, pos, null)) {
                spark.color = Color.argb(60, 57, 255, 20)
                c.drawCircle(pos[0], pos[1], 5f * d, spark)
                spark.color = Color.argb(230, 220, 255, 210)
                c.drawCircle(pos[0], pos[1], 1.8f * d, spark)
            }
        }
    }

    // ---------------- Animation (only while ON and on screen) ----------------

    override fun run() {
        if (!running) return
        invalidateSelf()
        scheduleSelf(this, SystemClock.uptimeMillis() + 33)
    }

    override fun start() {
        if (running || !on || !onScreen) return
        running = true
        scheduleSelf(this, SystemClock.uptimeMillis() + 33)
    }

    override fun stop() {
        running = false
        unscheduleSelf(this)
    }

    override fun isRunning() = running

    /** The view tells us when it's shown or hidden (screen off, app in the background). */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        update()
        return changed
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
