package com.neon.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Countdown box on the floating menu: neon digits in an outlined box. */
class AnnunciatorView(context: Context) : View(context) {

    var text: String = ""
        set(value) {
            if (field == value) return
            val relayout = template(value) != template(field)
            field = value
            if (relayout) requestLayout()
            invalidate()
        }
    var color: Int = Color.parseColor("#39FF14")
        set(value) { if (field != value) { field = value; invalidate() } }
    var textSizePx: Float = 30f
        set(value) {
            field = value
            textPaint.textSize = value
            requestLayout()
            invalidate()
        }

    private val d = context.resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 30f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()

    private fun template(s: String) = s.replace(DIGITS, "0")

    private companion object {
        val DIGITS = Regex("[0-9]")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val sample = template(text).ifEmpty { "0:00" }
        val fm = textPaint.fontMetrics
        val w = textPaint.measureText(sample) + textSizePx * 1.1f
        val h = (fm.descent - fm.ascent) + textSizePx * 0.5f
        setMeasuredDimension(
            resolveSize(w.toInt() + 2, widthMeasureSpec),
            resolveSize(h.toInt() + 2, heightMeasureSpec)
        )
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val i = maxOf(1f, 0.75f * d) / 2f // half the line width, so it sits just inside
        box.set(i, i, width - i, height - i)
        val r = minOf(box.height() * 0.3f, 12f * d)
        fill.color = Color.BLACK
        c.drawRoundRect(box, r, r, fill)
        stroke.strokeWidth = maxOf(1f, 0.75f * d)
        stroke.color = mix(color, Color.BLACK, 0.45f)
        c.drawRoundRect(box, r, r, stroke)
        textPaint.color = color
        val fm = textPaint.fontMetrics
        c.drawText(text, box.centerX(), box.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
    }
}
