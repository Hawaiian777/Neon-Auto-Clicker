package com.neon.autoclicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Sports Tracker keyframes, checked at their key moments. */
class AirbusLightsTest {
    private val sh = AirbusLights.newShadows()

    @Test
    fun strobesDoubleFlash() {
        assertEquals(1f, AirbusLights.strobe(0, sh), 1e-4f)     // 1st flash
        assertEquals(0.08f, AirbusLights.strobe(100, sh), 1e-4f) // gap (8%)
        assertEquals(1f, AirbusLights.strobe(200, sh), 1e-4f)   // 2nd flash (16.7%)
        assertEquals(0.08f, AirbusLights.strobe(600, sh), 1e-4f) // off
        assertEquals(0f, sh[2], 1e-4f)
    }

    @Test
    fun centerSingleFlash() {
        assertEquals(1f, AirbusLights.center(10, sh), 1e-4f)
        assertEquals(0.06f, AirbusLights.center(200, sh), 1e-4f)
    }

    @Test
    fun beaconHalfACycleLater() {
        assertEquals(1f, AirbusLights.beacon(600, sh), 1e-3f)          // flash at 600 ms
        assertEquals(0.7f, sh[5], 1e-3f)                                  // big halo
        assertEquals(0.12f, AirbusLights.beacon(0, sh), 1e-4f)          // dim at the strobes
        assertEquals(0.12f, AirbusLights.beacon(600 + 450, sh), 1e-4f)  // after 35%
    }

    @Test
    fun lampGoesFromDimToPeak() {
        assertEquals(1f, AirbusLights.lamp(600, false, sh), 1e-3f)
        assertEquals(0f, AirbusLights.lamp(0, false, sh), 1e-4f)
        assertTrue(AirbusLights.lamp(0, true, sh) > 0.5f) // reduce motion: steady and lit
    }

    @Test
    fun frameSkippingOnlyDrawsWhileLightsMove() {
        assertEquals(0L, AirbusLights.nextFrameDelay(100))
        assertEquals(300L, AirbusLights.nextFrameDelay(300))
        assertEquals(0L, AirbusLights.nextFrameDelay(700))
        assertEquals(100L, AirbusLights.nextFrameDelay(1100))
        assertEquals(500L, AirbusLights.beaconFrameDelay(100))
        assertEquals(700L, AirbusLights.beaconFrameDelay(1100))
        assertEquals(-1L, AirbusLights.nextFrameDelay(100, reduceMotion = true))
    }
}
