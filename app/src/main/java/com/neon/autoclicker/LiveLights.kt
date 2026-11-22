package com.neon.autoclicker

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings

/**
 * Draws the Airbus-style live lights on Android. The timing and sizes come from the shared
 * [AirbusLights] (Sports Tracker v34 values), so Windows flashes identically.
 *
 * The Sports Tracker card styling (green border and glow) is deliberately NOT drawn:
 * the floating menu keeps its own background, transparency and accent color while the
 * lights flash on top of it.
 *
 * CSS px are drawn as dp, times [scale] (0.5 = the half-size beacon beside "Warning").
 * Call [drawCenter] before the content and [drawOver] after it.
 */
class LiveLights(private val context: Context, scale: Float = 1f) {

    private val d = context.resources.displayMetrics.density * scale
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val blurCache = HashMap<Int, BlurMaskFilter>()
    private val red = AirbusLights.RED

    // Reused every frame instead of allocating new arrays (the lights redraw up to 60 times a second).
    private val shadow = AirbusLights.newShadows()

    var reduceMotion = false
        private set

    /** Re-check the phone's animation setting (call when the lights switch on). */
    fun refreshMotionSetting() {
        reduceMotion = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
    }

    /** Centre strobe, behind the content. */
    fun drawCenter(c: Canvas, box: RectF) {
        val cx = box.centerX()
        val cy = box.centerY()
        if (reduceMotion) {
            light(c, cx, cy, AirbusLights.CENTER_R, Color.WHITE, AirbusLights.STEADY, null)
            return
        }
        val op = AirbusLights.center(SystemClock.uptimeMillis(), shadow)
        light(c, cx, cy, AirbusLights.CENTER_R, Color.WHITE, op, shadow)
    }

    /** Wing-tip strobes and beacons, on top of the content. */
    fun drawOver(c: Canvas, box: RectF) {
        val now = SystemClock.uptimeMillis()
        // positions are relative to the padding box (inside a 2px border), as in CSS
        val padL = box.left + AirbusLights.BORDER * d
        val padR = box.right - AirbusLights.BORDER * d
        val padT = box.top + AirbusLights.BORDER * d
        val padB = box.bottom - AirbusLights.BORDER * d
        val cx = (padL + padR) / 2f
        val cy = (padT + padB) / 2f

        val sr = AirbusLights.STROBE_R
        val lx = padL - (AirbusLights.STROBE_OUT - sr) * d
        val rx = padR + (AirbusLights.STROBE_OUT - sr) * d
        if (reduceMotion) {
            light(c, lx, cy, sr, Color.WHITE, AirbusLights.STEADY, null)
            light(c, rx, cy, sr, Color.WHITE, AirbusLights.STEADY, null)
        } else {
            val op = AirbusLights.strobe(now, shadow)
            light(c, lx, cy, sr, Color.WHITE, op, shadow)
            light(c, rx, cy, sr, Color.WHITE, op, shadow)
        }

        val br = AirbusLights.BEACON_R
        val ty = padT - (AirbusLights.BEACON_OUT - br) * d
        val by = padB + (AirbusLights.BEACON_OUT - br) * d
        if (reduceMotion) {
            light(c, cx, ty, br, red, AirbusLights.STEADY, null)
            light(c, cx, by, br, red, AirbusLights.STEADY, null)
        } else {
            val op = AirbusLights.beacon(now, shadow)
            light(c, cx, ty, br, red, op, shadow)
            light(c, cx, by, br, red, op, shadow)
        }
    }

    /**
     * The beacon drawn as a real powered lamp (the small beacons beside "Warning"): same flash
     * and halo as the menu's beacons, plus a glass lens that is dark ruby when dimmed and
     * bright red with a hot pinkish-white center when lit.
     */
    fun drawLamp(c: Canvas, x: Float, y: Float, now: Long = SystemClock.uptimeMillis()) {
        val k = AirbusLights.lamp(now, reduceMotion, shadow)
        val r = AirbusLights.BEACON_R * d
        drawShadows(c, x, y, r, red, 1f, shadow)
        fill.maskFilter = null
        fill.shader = RadialGradient(x, y, r, AirbusLights.lensColors(k), AirbusLights.LENS_STOPS, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, fill)
        fill.shader = null
        // thin dark bezel and a small glass highlight
        rim.strokeWidth = maxOf(1f, 0.6f * d)
        rim.color = AirbusLights.BEZEL
        c.drawCircle(x, y, r, rim)
        fill.color = Color.argb(AirbusLights.highlightAlpha(k), 255, 255, 255)
        c.drawCircle(x - r * 0.35f, y - r * 0.38f, r * 0.22f, fill)
    }

    /** Battery saver: ms until the next frame worth drawing (see [AirbusLights.nextFrameDelay]). */
    fun nextFrameDelay(now: Long = SystemClock.uptimeMillis()): Long = AirbusLights.nextFrameDelay(now, reduceMotion)

    /** Same for a single beacon. */
    fun beaconFrameDelay(now: Long = SystemClock.uptimeMillis()): Long = AirbusLights.beaconFrameDelay(now, reduceMotion)

    /** A dot with CSS-like box-shadows: triples of (blur px, spread px, alpha); opacity multiplies all. */
    private fun light(c: Canvas, x: Float, y: Float, radiusDp: Float, col: Int, opacity: Float, shadows: FloatArray?) {
        val r = radiusDp * d
        if (shadows != null) drawShadows(c, x, y, r, col, opacity, shadows)
        fill.maskFilter = null
        fill.color = withAlpha(col, opacity)
        c.drawCircle(x, y, r, fill)
    }

    /** The glow rings around a light; shadows with no alpha are skipped. */
    private fun drawShadows(c: Canvas, x: Float, y: Float, r: Float, col: Int, opacity: Float, sh: FloatArray) {
        var i = 0
        while (i + 2 < sh.size) {
            val a = sh[i + 2] * opacity
            if (a > 0.003f) {
                glow.color = withAlpha(col, a)
                glow.maskFilter = if (sh[i] > 0.2f) blurFor(sh[i]) else null
                c.drawCircle(x, y, r + sh[i + 1] * d, glow)
            }
            i += 3
        }
        glow.maskFilter = null
    }

    /** CSS blur B = Gaussian sigma B/2; Android blur radius r gives sigma 0.57735·r + 0.5. */
    private fun blurFor(cssBlurPx: Float): BlurMaskFilter {
        val key = (cssBlurPx * 4).toInt()
        return blurCache.getOrPut(key) {
            val sigma = (key / 4f) * d / 2f
            BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
        }
    }
}
