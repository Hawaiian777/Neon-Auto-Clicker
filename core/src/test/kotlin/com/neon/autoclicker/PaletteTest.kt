package com.neon.autoclicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {

    @Test
    fun hexAndChannels() {
        val c = hex("#39FF14")
        assertEquals(255, alphaOf(c))
        assertEquals(0x39, redOf(c))
        assertEquals(0xFF, greenOf(c))
        assertEquals(0x14, blueOf(c))
        assertEquals(0x80, alphaOf(hex("#80FFFFFF")))
    }

    @Test
    fun luminanceMatchesAndroidFormula() {
        assertEquals(0f, luminance(BLACK), 1e-6f)
        assertEquals(1f, luminance(WHITE), 1e-4f)
        assertEquals(0.2126f, luminance(hex("#FF0000")), 1e-4f)
    }

    @Test
    fun mixEnds() {
        assertEquals(BLACK, mix(BLACK, WHITE, 0f))
        assertEquals(WHITE, mix(BLACK, WHITE, 1f))
    }

    @Test
    fun textOnButtonsIsReadable() {
        fun on(name: String) = NeonColors(name, Palette.NEONS.first { it.first == name }.second).onMain
        assertEquals(BLACK, on("Neon Yellow"))
        assertEquals(BLACK, on("Neon Green"))
        assertEquals(WHITE, on("Neon Blue"))
    }

    @Test
    fun dayModeDeepensBrightNeons() {
        Palette.NEONS.forEach { (name, neon) ->
            val day = NeonColors(name, neon, Look.DAY)
            assertTrue("$name readable on white", luminance(day.main) <= 0.2f || luminance(neon) <= 0.2f)
        }
    }

    @Test
    fun systemModeFollowsTheOs() {
        assertEquals(Look.NIGHT, Palette.resolve(AppMode.SYSTEM, systemIsDark = true))
        assertEquals(Look.DAY, Palette.resolve(AppMode.SYSTEM, systemIsDark = false))
        assertEquals(Look.SUNSET, Palette.resolve(AppMode.SUNSET, systemIsDark = true))
    }

    @Test
    fun nineColorsGreenDefault() {
        assertEquals(9, Palette.NEONS.size)
        assertEquals("Neon Green", Palette.NEONS[Config.DEFAULT_THEME].first)
    }
}
