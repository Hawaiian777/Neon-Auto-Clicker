package com.neon.autoclicker

/**
 * Timing of the Airbus-style live lights, ported from Sports Tracker v34 "LIVE LIGHTS CSS"
 * with the same values. Both apps draw the lights from these numbers, so they flash identically.
 *
 *   - white wing-tip strobes left/right, 10px: double flash per 1.2 s
 *     (on 0–5%, off 6–13%, on 14–19%, off 20–100%, linear), base opacity .08,
 *     glow 0 0 10px 4px #fff, 0 0 26px 10px rgba(255,255,255,.75)
 *   - centre strobe 6px behind the content: single flash 0–2.5%, off from 3%,
 *     base opacity .06, glow 0 0 6px 2.5px #fff, 0 0 17px 7px rgba(255,255,255,.7)
 *   - red #ff2a2a beacons top/bottom centre, 12px, delay -0.6 s, ease-out:
 *     0% opacity 1 + glow (10px 4px, 28px 10px rgba(.7)), 18% opacity .35 + glow 6px 2px rgba(.5),
 *     35–100% opacity .12, no glow
 *
 * Sizes are CSS px (drawn as dp on Android, as scaled pixels on Windows).
 * A shadow array holds two CSS box-shadows: (blur, spread, alpha) x 2; alpha 0 = none.
 */
object AirbusLights {
    const val CYCLE_MS = 1200L
    val RED = hex("#ff2a2a")

    // Geometry (CSS px), relative to the box the lights sit on.
    const val BORDER = 2f          // positions are measured inside a 2px border
    const val STROBE_R = 5f        // 10px strobes
    const val STROBE_OUT = 6f      // left / right: -6px
    const val BEACON_R = 6f        // 12px beacons
    const val BEACON_OUT = 7f      // top / bottom: -7px
    const val CENTER_R = 3f        // 6px centre strobe

    // The small lamp beside "Warning": lens colors.
    val LENS_DIM_EDGE = hex("#3A0505")
    val LENS_DIM_CORE = hex("#5C0A0A")
    val LENS_HOT = hex("#FFE2DC")
    val BEZEL = argb(200, 30, 2, 2)
    val LENS_STOPS = floatArrayOf(0f, 0.45f, 1f)

    /** Steady opacity when the system asks for reduced motion. */
    const val STEADY = 0.9f

    fun newShadows() = FloatArray(6)

    private fun shadows(into: FloatArray, b1: Float, s1: Float, a1: Float, b2: Float = 0f, s2: Float = 0f, a2: Float = 0f): FloatArray {
        into[0] = b1; into[1] = s1; into[2] = a1; into[3] = b2; into[4] = s2; into[5] = a2
        return into
    }

    private fun pct(now: Long, offsetMs: Long): Float = ((now + offsetMs) % CYCLE_MS) / 12f

    /** Wing-tip strobes: returns their opacity, glow into [sh]. */
    fun strobe(now: Long, sh: FloatArray): Float {
        val p = pct(now, 0L)
        val k = when {
            p <= 5f -> 1f
            p < 6f -> 1f - (p - 5f)
            p <= 13f -> 0f
            p < 14f -> p - 13f
            p <= 19f -> 1f
            p < 20f -> 1f - (p - 19f)
            else -> 0f
        }
        shadows(sh, 10f, 4f, k, 26f, 10f, 0.75f * k)
        return lerp(0.08f, 1f, k)
    }

    /** Centre strobe: returns its opacity, glow into [sh]. */
    fun center(now: Long, sh: FloatArray): Float {
        val p = pct(now, 0L)
        val k = when {
            p <= 2.5f -> 1f
            p < 3f -> 1f - (p - 2.5f) / 0.5f
            else -> 0f
        }
        shadows(sh, 6f, 2.5f, k, 17f, 7f, 0.7f * k)
        return lerp(0.06f, 1f, k)
    }

    /** Red beacons (.beaconFlash, ease-out, delay -0.6 s): returns opacity, glow into [sh]. */
    fun beacon(now: Long, sh: FloatArray): Float {
        val p = pct(now, 600L)
        return when {
            p < 18f -> {
                val e = easeOut(p / 18f)
                shadows(sh, lerp(10f, 6f, e), lerp(4f, 2f, e), lerp(1f, 0.5f, e), lerp(28f, 0f, e), lerp(10f, 0f, e), lerp(0.7f, 0f, e))
                lerp(1f, 0.35f, e)
            }
            p < 35f -> {
                val e = easeOut((p - 18f) / 17f)
                shadows(sh, lerp(6f, 0f, e), lerp(2f, 0f, e), lerp(0.5f, 0f, e))
                lerp(0.35f, 0.12f, e)
            }
            else -> {
                shadows(sh, 0f, 0f, 0f)
                0.12f
            }
        }
    }

    /** The small lamp: lit intensity 0 (dim) .. 1 (flash peak), glow into [sh]. */
    fun lamp(now: Long, reduceMotion: Boolean, sh: FloatArray): Float {
        if (reduceMotion) {
            shadows(sh, 10f, 4f, 0.8f)
            return 0.85f
        }
        return ((beacon(now, sh) - 0.12f) / 0.88f).coerceIn(0f, 1f)
    }

    /** Lens colors for a lamp intensity: (core, middle, edge). */
    fun lensColors(k: Float): IntArray {
        val edge = mix(LENS_DIM_EDGE, RED, k)
        val core = mix(LENS_DIM_CORE, LENS_HOT, k)
        return intArrayOf(core, mix(edge, core, 0.35f), edge)
    }

    fun highlightAlpha(k: Float): Int = (60 + 70 * k).toInt()

    /**
     * Battery saver: ms until the next frame worth drawing (0 = next screen refresh, -1 = none).
     * The lights only change during two windows of each 1.2 s cycle (strobes 0–240 ms, beacon
     * 600–1020 ms); in between nothing moves.
     */
    fun nextFrameDelay(now: Long, reduceMotion: Boolean = false): Long {
        if (reduceMotion) return -1
        val t = now % CYCLE_MS
        return when {
            t < 240L -> 0L
            t < 600L -> 600L - t
            t < 1020L -> 0L
            else -> CYCLE_MS - t
        }
    }

    /** Same for a single beacon: it only moves from 600 to 1020 ms of each cycle. */
    fun beaconFrameDelay(now: Long, reduceMotion: Boolean = false): Long {
        if (reduceMotion) return -1
        val t = now % CYCLE_MS
        return when {
            t < 600L -> 600L - t
            t < 1020L -> 0L
            else -> CYCLE_MS - t + 600L
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** CSS ease-out = cubic-bezier(0, 0, 0.58, 1). */
    fun easeOut(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var lo = 0f
        var hi = 1f
        var t = x
        repeat(22) {
            t = (lo + hi) / 2f
            val bx = 3f * (1f - t) * t * t * 0.58f + t * t * t
            if (bx < x) lo = t else hi = t
        }
        return 3f * (1f - t) * t * t + t * t * t
    }
}
