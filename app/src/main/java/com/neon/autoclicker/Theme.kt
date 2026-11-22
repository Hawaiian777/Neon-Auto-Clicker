package com.neon.autoclicker

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import kotlin.math.roundToInt

/**
 * One neon color plus its shades for a [look]. The colors themselves come from the shared
 * [NeonColors] (also used by the Windows app); this adds the Android drawables.
 */
class NeonTheme(val name: String, val neon: Int, val look: Look = Look.NIGHT) {
    private val c = NeonColors(name, neon, look)

    val bg: Int = c.bg
    val main: Int = c.main
    val fill: Int = c.fill
    val dim: Int = c.dim
    val faint: Int = c.faint
    val surface: Int = c.surface
    val body: Int = c.body
    val field: Int = c.field
    val isLight: Boolean = c.isLight
    val cardEdge: Int = c.cardEdge
    val onMain: Int = c.onMain

    fun alpha(a: Int): Int = c.alpha(a)

    /** Window background: solid, or a dusk-to-ember gradient for Sunset. */
    fun background(): Drawable = when (look) {
        Look.SUNSET -> GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, Palette.SUNSET_GRADIENT)
        else -> GradientDrawable().apply { setColor(bg) }
    }

    fun withLook(l: Look) = NeonTheme(name, neon, l)
}

object Themes {
    val ALL: List<NeonTheme> = Palette.NEONS.map { (name, neon) -> NeonTheme(name, neon) }

    /** Floating controls: always the dark neon look. */
    fun current(c: Context): NeonTheme = ALL[Prefs.themeIndex(c)]

    /** Main app screen: the chosen color in the chosen appearance mode. */
    fun app(c: Context): NeonTheme = current(c).withLook(resolveLook(c))

    fun resolveLook(c: Context): Look {
        val night = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return Palette.resolve(Prefs.appMode(c), systemIsDark = night == Configuration.UI_MODE_NIGHT_YES)
    }
}

/** A border width in pixels: [dp] converted, never thinner than 1 pixel so it always shows. */
fun hairline(dp: Float, density: Float): Int = maxOf(1, (dp * density).roundToInt())

/** Drawables shared by the app screen, dialogs and the floating pop-ups. */
object NeonUi {
    fun outline(t: NeonTheme, density: Float, radiusDp: Float = 12f): Drawable = RippleDrawable(
        ColorStateList.valueOf(t.alpha(0x66)),
        GradientDrawable().apply {
            setColor(t.field)
            setStroke(hairline(1f, density), t.main)
            cornerRadius = radiusDp * density
        },
        null
    )

    fun filled(t: NeonTheme, density: Float, radiusDp: Float = 14f): Drawable = RippleDrawable(
        ColorStateList.valueOf(Color.argb(0x66, 0, 0, 0)),
        GradientDrawable().apply {
            setColor(t.fill)
            cornerRadius = radiusDp * density
        },
        null
    )

    fun card(t: NeonTheme, density: Float): Drawable = GradientDrawable().apply {
        setColor(t.surface)
        setStroke(hairline(0.5f, density), t.cardEdge)
        cornerRadius = 16 * density
    }
}
