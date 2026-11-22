package com.neon.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.widget.LinearLayout

/**
 * The floating menu. Draws its own card (Black or Clear, in the accent color). When the
 * warning threshold is reached, the Sports Tracker lights flash on it: centre strobe behind
 * the buttons, wing-tip strobes on the left/right edges and red beacons top/bottom,
 * without changing the menu's background or colors.
 * A small transparent margin ([room]) around the card leaves space for the lights.
 */
class LivePanel(context: Context) : LinearLayout(context) {

    private val d = context.resources.displayMetrics.density
    private val lights = LiveLights(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val card = RectF()
    private val outline = RectF()

    /** Space around the card for the edge lights. */
    val room: Int = (9 * d).toInt()

    var themeColor: Int = Color.parseColor("#39FF14")
        set(value) { field = value; invalidate() }
    var cornerRadius: Float = 20 * d
        set(value) { field = value; invalidate() }
    /** Clear = no background or outline; the controls float on their own. */
    var clear: Boolean = false
        set(value) { field = value; invalidate() }
    var warning: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) lights.refreshMotionSetting()
            invalidate()
        }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        // Blur glows need software drawing before Android 9.
        if (Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** Padding inside the card; the light margin is added automatically. */
    fun setInnerPadding(l: Int, t: Int, r: Int, b: Int) = setPadding(l + room, t + room, r + room, b + room)

    override fun onDraw(c: Canvas) {
        card.set(room.toFloat(), room.toFloat(), (width - room).toFloat(), (height - room).toFloat())
        val r = minOf(cornerRadius, card.height() / 2f)
        // The menu keeps its own look (Black or Clear, in the chosen accent) at all times;
        // the warning lights only add the strobes and beacons on top.
        if (!clear) {
            fill.color = Color.BLACK
            c.drawRoundRect(card, r, r, fill)
            stroke.strokeWidth = maxOf(1f, 1f * d)
            stroke.color = themeColor
            val h = stroke.strokeWidth / 2f // keep the 1dp line just inside the edge
            outline.set(card.left + h, card.top + h, card.right - h, card.bottom - h)
            c.drawRoundRect(outline, r - h, r - h, stroke)
        }
        if (warning) lights.drawCenter(c, card)
        super.onDraw(c)
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (warning) {
            lights.drawOver(c, card)
            // Only redraw while a light is actually changing (saves battery).
            val wait = lights.nextFrameDelay()
            when {
                wait == 0L -> postInvalidateOnAnimation()
                wait > 0L -> postInvalidateDelayed(wait)
            }
        }
    }
}
