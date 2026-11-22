package com.neon.autoclicker

import kotlin.math.pow
import kotlin.math.roundToInt

// Colors are ARGB ints (0xAARRGGBB), the same format Android uses, so both apps share the math.

fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)
fun alphaOf(c: Int) = (c ushr 24) and 0xFF
fun redOf(c: Int) = (c shr 16) and 0xFF
fun greenOf(c: Int) = (c shr 8) and 0xFF
fun blueOf(c: Int) = c and 0xFF

/** "#RRGGBB" or "#AARRGGBB". */
fun hex(s: String): Int {
    val v = s.removePrefix("#").toLong(16)
    return if (s.length <= 7) (0xFF000000L or v).toInt() else v.toInt()
}

fun withAlpha(c: Int, a: Float): Int = argb((a.coerceIn(0f, 1f) * 255).toInt(), redOf(c), greenOf(c), blueOf(c))

/** Blend [a] toward [b] by [t] (0 = a, 1 = b), fully opaque. */
fun mix(a: Int, b: Int, t: Float): Int {
    val u = 1f - t
    return rgb(
        (redOf(a) * u + redOf(b) * t).roundToInt(),
        (greenOf(a) * u + greenOf(b) * t).roundToInt(),
        (blueOf(a) * u + blueOf(b) * t).roundToInt(),
    )
}

/** Relative luminance (0 = black, 1 = white), same formula as Android's Color.luminance. */
fun luminance(c: Int): Float {
    fun lin(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return (0.2126 * lin(redOf(c)) + 0.7152 * lin(greenOf(c)) + 0.0722 * lin(blueOf(c))).toFloat()
}

const val BLACK: Int = -0x1000000 // 0xFF000000
const val WHITE: Int = -0x1       // 0xFFFFFFFF

/** How the main screen looks. The floating controls always use NIGHT. */
enum class Look { NIGHT, DAY, SUNSET }

/** App appearance setting (System follows the phone's / PC's dark-mode setting). */
enum class AppMode(val label: String) { NIGHT("Night"), DAY("Day"), SUNSET("Sunset"), SYSTEM("System") }

/** The nine neon colors. */
object Palette {
    val NEONS: List<Pair<String, Int>> = listOf(
        "Neon Red" to hex("#FF2A2A"),
        "Neon Orange" to hex("#FF7B00"),
        "Neon Yellow" to hex("#FFF01F"),
        "Neon Green" to hex("#39FF14"),
        "Neon Cyan" to hex("#00F5FF"),
        "Neon Blue" to hex("#2E6BFF"),
        "Neon Purple" to hex("#B026FF"),
        "Neon Magenta" to hex("#FF00FF"),
        "Neon Pink" to hex("#FF3EA5"),
    )

    fun clampIndex(i: Int) = i.coerceIn(0, NEONS.lastIndex)

    /** Sunset window background, top to bottom. */
    val SUNSET_GRADIENT = intArrayOf(hex("#2A0D2A"), hex("#170A12"), hex("#2E1206"))

    fun resolve(mode: AppMode, systemIsDark: Boolean): Look = when (mode) {
        AppMode.NIGHT -> Look.NIGHT
        AppMode.DAY -> Look.DAY
        AppMode.SUNSET -> Look.SUNSET
        AppMode.SYSTEM -> if (systemIsDark) Look.NIGHT else Look.DAY
    }
}

/**
 * One neon color plus the shades derived from it for a [look].
 * [neon] is the pure neon color; [main] is the accent used for text and outlines
 * (deepened on the light Day look so it stays readable on white).
 */
class NeonColors(val name: String, val neon: Int, val look: Look = Look.NIGHT) {

    /** Screen background. */
    val bg: Int = when (look) {
        Look.NIGHT -> BLACK
        Look.DAY -> hex("#F3F5F7")
        Look.SUNSET -> hex("#170A12")
    }

    /** Accent for text, outlines and highlights. */
    val main: Int = if (look == Look.DAY) readableOnLight(neon) else neon

    /** Solid fill for the main buttons. */
    val fill: Int = main

    val dim: Int = mix(main, bg, 0.45f)
    val faint: Int = when (look) {
        Look.NIGHT -> mix(neon, BLACK, 0.78f)
        Look.DAY -> mix(neon, WHITE, 0.62f)
        Look.SUNSET -> mix(mix(neon, hex("#FF6A3D"), 0.4f), bg, 0.62f)
    }
    val surface: Int = when (look) {
        Look.NIGHT -> mix(BLACK, neon, 0.025f)
        Look.DAY -> WHITE
        Look.SUNSET -> mix(hex("#26101D"), neon, 0.03f)
    }
    val body: Int = when (look) {
        Look.NIGHT -> mix(neon, rgb(200, 200, 200), 0.45f)
        Look.DAY -> mix(hex("#3C434B"), main, 0.15f)
        Look.SUNSET -> mix(hex("#FFB58A"), neon, 0.15f)
    }
    /** Inside of outlined buttons. */
    val field: Int = when (look) {
        Look.NIGHT -> BLACK
        Look.DAY -> WHITE
        Look.SUNSET -> hex("#1D0C17")
    }
    val isLight: Boolean = look == Look.DAY

    /** Section card outline: a little stronger than [faint] so the thin line still shows in every mode. */
    val cardEdge: Int = if (look == Look.DAY) mix(main, WHITE, 0.45f) else mix(faint, main, 0.3f)

    /** Text/icon color to use on top of a solid [fill]. */
    val onMain: Int = if (luminance(fill) < 0.2f) WHITE else BLACK

    /** Status/title bar colors (Sunset uses the ends of its gradient). */
    val topBar: Int = if (look == Look.SUNSET) Palette.SUNSET_GRADIENT[0] else bg
    val bottomBar: Int = if (look == Look.SUNSET) Palette.SUNSET_GRADIENT[2] else bg

    fun alpha(a: Int): Int = argb(a, redOf(main), greenOf(main), blueOf(main))

    fun withLook(l: Look) = NeonColors(name, neon, l)

    private companion object {
        /** Darkens a bright neon until it reads well on a white background. */
        fun readableOnLight(c: Int): Int {
            var t = 0f
            var out = c
            while (luminance(out) > 0.2f && t < 0.85f) {
                t += 0.05f
                out = mix(c, BLACK, t)
            }
            return out
        }
    }
}

/** Fixed colors used by the Permission card on both platforms. */
object StatusColors {
    val ON = hex("#8CFF6A")
    val OFF = hex("#FF6B6B")
    val WARN = hex("#FFC24A")
    val TEXT = hex("#E6F2E8")
}
