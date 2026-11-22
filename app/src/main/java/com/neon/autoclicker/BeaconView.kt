package com.neon.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.TextView

/**
 * The small (half-size) red Airbus beacon beside "Warning" in section 3.
 * A decorative attention light: it flashes all the time while it's on screen, whether or not
 * a timer is running (the floating menu's warning lights are separate and follow the timer).
 * Drawn as a powered lamp with a glowing lens and red halo. No drawing at all while the
 * app is in the background.
 */
class BeaconView(context: Context) : View(context) {

    private val lights = LiveLights(context, scale = 0.5f)
    private val d = context.resources.displayMetrics.density
    private var watching = false
    private val bounds = Rect()

    /** Text whose first letter the beacon lines up with (centered on the "W"). */
    var alignTo: TextView? = null
        set(value) {
            field?.removeOnLayoutChangeListener(relayout)
            field = value
            value?.addOnLayoutChangeListener(relayout)
            anchorValid = false
        }

    /** Where to draw, worked out once per layout instead of on every frame. */
    private var anchor = 0f
    private var anchorValid = false
    private val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        anchorValid = false
        invalidate()
    }

    init {
        contentDescription = "Warning beacon"
        // Blur glows need software drawing before Android 9.
        if (Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (10 * d).toInt()
        val h = (20 * d).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    /** Vertical center of the first letter of [alignTo], in this view's coordinates. */
    private fun anchorY(): Float {
        val tv = alignTo ?: return height / 2f
        val layout = tv.layout ?: return height / 2f
        val first = tv.text?.take(1)?.toString().orEmpty().ifEmpty { "W" }
        tv.paint.getTextBounds(first, 0, first.length, bounds)
        val inText = tv.totalPaddingTop + layout.getLineBaseline(0) + bounds.exactCenterY()
        val myLoc = IntArray(2)
        val tvLoc = IntArray(2)
        getLocationInWindow(myLoc)
        tv.getLocationInWindow(tvLoc)
        return inText + (tvLoc[1] - myLoc[1])
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        anchorValid = false
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (!anchorValid) {
            anchor = anchorY()
            anchorValid = true
        }
        val now = SystemClock.uptimeMillis()
        lights.drawLamp(c, width / 2f, anchor, now)
        if (watching) {
            val wait = lights.beaconFrameDelay(now)
            when {
                wait == 0L -> postInvalidateOnAnimation()
                wait > 0L -> postInvalidateDelayed(wait)
            }
        }
    }

    private fun setWatching(on: Boolean) {
        if (on == watching) return
        watching = on
        if (on) {
            lights.refreshMotionSetting()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setWatching(windowVisibility == VISIBLE)
    }

    override fun onDetachedFromWindow() {
        setWatching(false)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        setWatching(visibility == VISIBLE && isAttachedToWindow)
    }
}
