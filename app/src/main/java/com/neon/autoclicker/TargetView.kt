package com.neon.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** The crosshair. Taps land on its center. The view is a bit bigger than the ring so it stays easy to grab. */
class TargetView(context: Context) : View(context) {

    companion object {
        /** Window size for a ring of [ringPx]: room for the ticks and glow, and never smaller than a fingertip. */
        fun windowSizeFor(ringPx: Int, density: Float): Int {
            val pad = ringPx * 0.15f + 12f * density
            return max(ringPx + 2 * pad, 56f * density).toInt()
        }
    }

    // Each setter redraws only when the value actually changes.
    var active = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var color: Int = Color.parseColor("#39FF14")
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Diameter of the ring in pixels. 0 = fill the view. */
    var ringPx: Int = 0
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Number badge shown when there is more than one target. */
    var label: String? = null
        set(value) { if (field != value) { field = value; invalidate() } }

    private val d = context.resources.displayMetrics.density
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val idleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(110, 0, 0, 0)
    }
    private val activeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        // The ring's glow needs a software layer only before Android 9.
        if (Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f
        val cy = height / 2f
        val r = if (ringPx > 0) ringPx / 2f else min(cx, cy) - 8 * d
        if (r <= 0f) return

        ring.color = color
        dot.color = color
        activeFill.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
        ring.strokeWidth = (r * 0.08f).coerceIn(1.5f * d, 3f * d)
        ring.setShadowLayer(if (active) 10 * d else 5 * d, 0f, 0f, color)

        c.drawCircle(cx, cy, r, if (active) activeFill else idleFill)
        c.drawCircle(cx, cy, r, ring)
        val gap = r * 0.35f
        val ext = r + max(3 * d, r * 0.12f)
        c.drawLine(cx, cy - ext, cx, cy - gap, ring)
        c.drawLine(cx, cy + gap, cx, cy + ext, ring)
        c.drawLine(cx - ext, cy, cx - gap, cy, ring)
        c.drawLine(cx + gap, cy, cx + ext, cy, ring)
        c.drawCircle(cx, cy, (r * 0.1f).coerceIn(1.5f * d, 3f * d), dot)

        val text = label ?: return
        // Badge at the top-right, outside the ring, so the center stays clear for aiming.
        val br = 7.5f * d
        val off = r * 0.72f + br * 0.55f
        val bx = (cx + off).coerceAtMost(width - br - 1f)
        val by = (cy - off).coerceAtLeast(br + 1f)
        dot.clearShadowLayer()
        c.drawCircle(bx, by, br, dot)
        badgeText.textSize = br * 1.25f
        badgeText.color = if (Color.luminance(color) < 0.2f) Color.WHITE else Color.BLACK
        val fm = badgeText.fontMetrics
        c.drawText(text, bx, by - (fm.ascent + fm.descent) / 2f, badgeText)
    }
}
