package com.neon.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Small line icons drawn in code so they take any neon color and scale cleanly. */
class IconView(context: Context) : View(context) {

    enum class Kind { PLAY, PAUSE, RESET, HOME, EYE, EYE_OFF, GEAR, COLLAPSE, EXPAND, CLOSE, PLUS, MINUS, LOCK, UNLOCK, GRIP }

    // Each setter redraws only when the value actually changes.
    var kind: Kind = Kind.PLAY
        set(value) { if (field != value) { field = value; invalidate() } }
    var color: Int = Color.WHITE
        set(value) { if (field != value) { field = value; invalidate() } }
    var glow: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // Android 9+ draws glows with the GPU; older versions need a (costlier) software
            // layer, and only while the glow is on.
            if (Build.VERSION.SDK_INT < 28) setLayerType(if (value) LAYER_TYPE_SOFTWARE else LAYER_TYPE_NONE, null)
            invalidate()
        }
    /** Dark outline under the icon so it stays visible on any background (Clear menu). */
    var halo: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val haloFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()
    private val arcRect = RectF()


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val aw = width - paddingLeft - paddingRight
        val ah = height - paddingTop - paddingBottom
        val s = min(aw, ah).toFloat()
        if (s <= 0f) return
        val cx = paddingLeft + aw / 2f
        val cy = paddingTop + ah / 2f
        val u = s / 24f // icons are designed on a 24 x 24 grid

        if (halo) {
            val h = Color.argb(185, 0, 0, 0)
            haloStroke.color = h
            haloStroke.strokeWidth = 4.4f * u
            haloFill.color = h
            haloFill.strokeWidth = 2.4f * u
            render(canvas, haloStroke, haloFill, cx, cy, u)
        }
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = 2f * u
        if (glow) {
            stroke.setShadowLayer(4f * u, 0f, 0f, color)
            fill.setShadowLayer(4f * u, 0f, 0f, color)
        } else {
            stroke.clearShadowLayer()
            fill.clearShadowLayer()
        }
        render(canvas, stroke, fill, cx, cy, u)
    }

    private fun render(canvas: Canvas, stroke: Paint, fill: Paint, cx: Float, cy: Float, u: Float) {
        fun px(v: Float) = cx + (v - 12f) * u
        fun py(v: Float) = cy + (v - 12f) * u
        path.reset()

        when (kind) {
            Kind.PLAY -> {
                path.moveTo(px(8f), py(5f))
                path.lineTo(px(19f), py(12f))
                path.lineTo(px(8f), py(19f))
                path.close()
                canvas.drawPath(path, fill)
            }
            Kind.HOME -> {
                path.moveTo(px(3f), py(11f))
                path.lineTo(px(12f), py(3.5f))
                path.lineTo(px(21f), py(11f))
                path.moveTo(px(5.5f), py(9.5f))
                path.lineTo(px(5.5f), py(20f))
                path.lineTo(px(18.5f), py(20f))
                path.lineTo(px(18.5f), py(9.5f))
                path.moveTo(px(10f), py(20f))
                path.lineTo(px(10f), py(14f))
                path.lineTo(px(14f), py(14f))
                path.lineTo(px(14f), py(20f))
                canvas.drawPath(path, stroke)
            }
            Kind.EYE, Kind.EYE_OFF -> {
                path.moveTo(px(2f), py(12f))
                path.quadTo(px(12f), py(3f), px(22f), py(12f))
                path.quadTo(px(12f), py(21f), px(2f), py(12f))
                path.close()
                canvas.drawPath(path, stroke)
                canvas.drawCircle(px(12f), py(12f), 3f * u, fill)
                if (kind == Kind.EYE_OFF) {
                    canvas.drawLine(px(4f), py(4f), px(20f), py(20f), stroke)
                }
            }
            Kind.GEAR -> {
                canvas.drawCircle(cx, cy, 5.5f * u, stroke)
                canvas.drawCircle(cx, cy, 2f * u, stroke)
                val w = stroke.strokeWidth
                stroke.strokeWidth = w * 1.4f
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4.0
                    val c = cos(a).toFloat()
                    val sn = sin(a).toFloat()
                    canvas.drawLine(cx + c * 7f * u, cy + sn * 7f * u, cx + c * 9.5f * u, cy + sn * 9.5f * u, stroke)
                }
                stroke.strokeWidth = w
            }
            Kind.COLLAPSE -> {
                path.moveTo(px(6f), py(15f))
                path.lineTo(px(12f), py(9f))
                path.lineTo(px(18f), py(15f))
                canvas.drawPath(path, stroke)
            }
            Kind.EXPAND -> {
                path.moveTo(px(6f), py(9f))
                path.lineTo(px(12f), py(15f))
                path.lineTo(px(18f), py(9f))
                canvas.drawPath(path, stroke)
            }
            Kind.CLOSE -> {
                canvas.drawLine(px(6f), py(6f), px(18f), py(18f), stroke)
                canvas.drawLine(px(18f), py(6f), px(6f), py(18f), stroke)
            }
            Kind.PLUS -> {
                canvas.drawLine(px(5f), py(12f), px(19f), py(12f), stroke)
                canvas.drawLine(px(12f), py(5f), px(12f), py(19f), stroke)
            }
            Kind.MINUS -> {
                canvas.drawLine(px(5f), py(12f), px(19f), py(12f), stroke)
            }
            Kind.PAUSE -> {
                rect.set(px(6.5f), py(5.5f), px(10.5f), py(18.5f))
                canvas.drawRoundRect(rect, 1.2f * u, 1.2f * u, fill)
                rect.set(px(13.5f), py(5.5f), px(17.5f), py(18.5f))
                canvas.drawRoundRect(rect, 1.2f * u, 1.2f * u, fill)
            }
            Kind.RESET -> {
                // circular arrow, clockwise, ending top-right with an arrowhead
                arcRect.set(px(5f), py(5f), px(19f), py(19f))
                canvas.drawArc(arcRect, 40f, 280f, false, stroke)
                val a = Math.toRadians(320.0)
                val ex = cx + (7f * cos(a)).toFloat() * u
                val ey = cy + (7f * sin(a)).toFloat() * u
                val dx = (-sin(a)).toFloat()
                val dy = cos(a).toFloat()
                path.moveTo(ex + dx * 3.2f * u, ey + dy * 3.2f * u)
                path.lineTo(ex - dy * 2.8f * u, ey + dx * 2.8f * u)
                path.lineTo(ex + dy * 2.8f * u, ey - dx * 2.8f * u)
                path.close()
                canvas.drawPath(path, fill)
            }
            Kind.LOCK, Kind.UNLOCK -> {
                rect.set(px(5.5f), py(11f), px(18.5f), py(20.5f))
                canvas.drawRoundRect(rect, 2f * u, 2f * u, stroke)
                if (kind == Kind.LOCK) {
                    arcRect.set(px(8.5f), py(4f), px(15.5f), py(11f))
                    path.moveTo(px(8.5f), py(11f))
                    path.lineTo(px(8.5f), py(7.5f))
                    path.arcTo(arcRect, 180f, 180f)
                    path.lineTo(px(15.5f), py(11f))
                } else {
                    // shackle lifted and swung open
                    arcRect.set(px(11.5f), py(2.5f), px(18.5f), py(9.5f))
                    path.moveTo(px(11.5f), py(11f))
                    path.lineTo(px(11.5f), py(6f))
                    path.arcTo(arcRect, 180f, 180f)
                    path.lineTo(px(18.5f), py(7.5f))
                }
                canvas.drawPath(path, stroke)
                canvas.drawCircle(px(12f), py(15.5f), 1.3f * u, fill)
            }
            Kind.GRIP -> {
                for (r in 0 until 3) for (col in 0 until 2) {
                    canvas.drawCircle(px(9.5f + col * 5f), py(6.5f + r * 5.5f), 1.5f * u, fill)
                }
            }
        }
    }
}
