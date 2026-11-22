package com.neon.autoclicker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The timer behavior the app promises, checked with a fake clock. */
class ClickSessionTest {

    private var now = 0L
    private val s = ClickSession { now }
    private val warn = 60 // warning lights at 1:00 left

    @Test
    fun noTimerRunsUntilStopped() {
        s.start(0L)
        now += 10 * 60_000L
        assertFalse(s.hasTimer)
        assertFalse(s.timeUp())
        assertFalse(s.warningOn(warn))
    }

    @Test
    fun timerCountsDownAndFinishes() {
        s.start(120_000L) // 2 min
        now += 30_000L
        assertEquals(90L, s.remainingSec())
        now += 90_000L
        assertTrue(s.timeUp())
        s.finish()
        assertEquals(ClickSession.State.DONE, s.state)
    }

    @Test
    fun pauseFreezesAndResumeContinuesExactly() {
        s.start(120_000L)
        now += 20_000L
        s.pause()
        now += 5 * 60_000L // a long pause doesn't use up time
        assertEquals(100L, s.remainingSec())
        s.start(0L) // resume keeps the original timer
        now += 10_000L
        assertEquals(90L, s.remainingSec())
        assertEquals(ClickSession.State.RUNNING, s.state)
    }

    @Test
    fun warningLightsStartAtThreshold() {
        s.start(120_000L)
        now += 59_000L
        assertFalse(s.warningOn(warn)) // 61 s left
        now += 1_000L
        assertTrue(s.warningOn(warn)) // 60 s left
    }

    @Test
    fun warningLightsKeepFlashingWhilePaused() {
        s.start(120_000L)
        now += 90_000L
        s.pause()
        assertTrue(s.warningOn(warn))
        now += 10 * 60_000L
        assertTrue(s.warningOn(warn))
    }

    @Test
    fun warningLightsStayOnWhenFinished() {
        s.start(60_000L)
        now += 60_000L
        s.finish()
        assertTrue(s.warningOn(warn))
    }

    @Test
    fun resetTurnsEverythingOff() {
        s.start(120_000L)
        now += 90_000L
        s.pause()
        s.reset()
        assertEquals(ClickSession.State.IDLE, s.state)
        assertFalse(s.warningOn(warn))
        assertFalse(s.hasTimer)
    }

    @Test
    fun switchingTimerOffStopsWarningAndLimit() {
        s.start(120_000L)
        now += 100_000L
        assertTrue(s.warningOn(warn))
        assertFalse(s.setTimerOn(false, 120_000L))
        assertFalse(s.warningOn(warn))
        now += 60 * 60_000L
        assertFalse(s.timeUp())
    }

    @Test
    fun switchingTimerOnMidSessionCountsFromNow() {
        s.start(0L)
        now += 10 * 60_000L
        s.setTimerOn(true, 60_000L)
        assertEquals(60L, s.remainingSec())
    }

    @Test
    fun changingLengthKeepsCountingFromTheStart() {
        s.start(60_000L)
        now += 30_000L
        s.setDuration(300_000L) // 1 min -> 5 min
        assertEquals(270L, s.remainingSec())
        assertFalse(s.warningOn(warn)) // more time clears the warning
    }

    @Test
    fun shorterLengthThanElapsedFinishes() {
        s.start(300_000L)
        now += 120_000L
        assertTrue(s.setDuration(60_000L))
        assertEquals(ClickSession.State.DONE, s.state)
    }

    @Test
    fun addingTimeAfterFinishingAllowsResume() {
        s.start(60_000L)
        now += 60_000L
        s.finish()
        assertFalse(s.setTimeLeft(120_000L, 60_000L))
        assertEquals(ClickSession.State.PAUSED, s.state)
        assertEquals(120L, s.remainingSec())
        assertFalse(s.warningOn(warn))
    }

    @Test
    fun idleIgnoresTimerChanges() {
        assertFalse(s.setTimerOn(true, 60_000L))
        assertFalse(s.setDuration(60_000L))
        assertEquals(ClickSession.State.IDLE, s.state)
        assertFalse(s.hasTimer)
    }
}
