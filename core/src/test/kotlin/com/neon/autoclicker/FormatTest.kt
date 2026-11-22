package com.neon.autoclicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Labels, steps and limits shown in the app. */
class FormatTest {

    @Test
    fun timerDefaultsAreOneMinuteAndOff() {
        assertEquals(60, Config.DEFAULT_TIMER_SEC)
        assertEquals("1 min", Config.durLabel(Config.DEFAULT_TIMER_SEC))
        assertEquals(60, Config.WARN_DEFAULT)
    }

    @Test
    fun durationLabels() {
        assertEquals("Off", Config.durLabel(0))
        assertEquals("45 s", Config.durLabel(45))
        assertEquals("5 min", Config.durLabel(300))
        assertEquals("1.5 h", Config.durLabel(5400))
        assertEquals("1:30", Config.durLabel(90))
        assertEquals("1:02:30", Config.durLabel(3750))
    }

    @Test
    fun countdownFormat() {
        assertEquals("1:00", Config.shortTime(60))
        assertEquals("0:05", Config.shortTime(5))
        assertEquals("1:00:00", Config.shortTime(3600))
        assertEquals("0:00", Config.shortTime(-3))
    }

    @Test
    fun intervalLabels() {
        assertEquals("0.25 s", Config.formatInterval(1))
        assertEquals("1.00 s", Config.formatInterval(Config.DEFAULT_Q))
        assertEquals("2 min", Config.formatInterval(480))
        assertEquals("1 min 30.25 s", Config.formatInterval(240 + 121))
    }

    @Test
    fun intervalSteps() {
        assertEquals(1, Config.stepAbove(4))      // 0.25 s steps
        assertEquals(1, Config.stepBelow(480))    // stepping down from 2 min
        assertEquals(4, Config.stepAbove(480))    // then 1 s
        assertEquals(20, Config.stepAbove(2400))  // then 5 s
    }

    @Test
    fun warningChipLabels() {
        assertEquals(listOf("10s", "30s", "1m", "2m", "3m", "5m"), Config.WARN_PRESETS.map { Config.warnShort(it) })
        assertEquals("1:30", Config.warnShort(90))
    }

    @Test
    fun timerSliderStops() {
        assertEquals(0, Config.TIMER_SLIDER.first())
        assertEquals(25200, Config.TIMER_SLIDER.last()) // 7 h
        assertEquals(0, Config.sliderIndexFor(0))
        assertEquals(60, Config.TIMER_SLIDER[Config.sliderIndexFor(65)])
        assertTrue(Config.TIMER_SLIDER.toList().zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun timerPresetsAreAscendingWithCustomLast() {
        val values = Config.TIMER_PRESETS.map { it.second }
        assertEquals(Config.CUSTOM, values.last())
        val real = values.dropLast(1)
        assertEquals(60, real.first())
        assertTrue(real.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun rateHints() {
        assertEquals("≈ 60 taps per minute", Config.rateHint(4))
        assertEquals("≈ 240 taps per minute", Config.rateHint(1))
        assertEquals("1 tap every 2 minutes", Config.rateHint(480))
    }

    @Test
    fun clampsMatchLimits() {
        assertEquals(Config.MIN_Q, Config.clampQuarters(0))
        assertEquals(Config.MAX_Q, Config.clampQuarters(99999))
        assertEquals(Config.WARN_MAX, Config.clampWarn(99999))
        assertEquals(Config.TARGET_MIN_DP, Config.clampTargetDp(1))
        assertEquals(Config.MODE_SEQUENCE, Config.normalizeMode(7))
    }
}
