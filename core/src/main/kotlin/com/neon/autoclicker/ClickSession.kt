package com.neon.autoclicker

/**
 * The timing of one clicking session: running / paused / finished, and the auto-stop timer.
 * Plain Kotlin with a replaceable clock, so it can be unit-tested without a phone.
 *
 * Times are in milliseconds of "session time" (time spent running; pauses don't count).
 */
class ClickSession(private val clock: () -> Long) {

    enum class State { IDLE, RUNNING, PAUSED, DONE }

    var state = State.IDLE
        private set
    val running: Boolean get() = state == State.RUNNING

    /** Session time at which the timer runs out; 0 = no timer this session. */
    var totalMs = 0L
        private set
    private var accumMs = 0L     // session time before the current running stretch
    private var runStart = 0L    // clock time when the current stretch began
    /** Session time the timer length counts from (0, or when it was switched on mid-session). */
    private var timerBaseMs = 0L

    fun elapsed(): Long = accumMs + if (running) clock() - runStart else 0L

    val hasTimer: Boolean get() = totalMs > 0
    fun remainingMs(): Long = if (hasTimer) maxOf(0L, totalMs - elapsed()) else 0L
    fun remainingSec(): Long = (remainingMs() + 999) / 1000

    /** The Airbus lights: from the threshold on, also while paused or finished, until Reset or more time. */
    fun warningOn(warnSec: Int): Boolean = state != State.IDLE && hasTimer && remainingSec() <= warnSec

    /** Running with a timer that has run out. */
    fun timeUp(): Boolean = running && hasTimer && elapsed() >= totalMs

    /** Starts a new session (when idle or finished) or resumes a paused one. */
    fun start(timerMs: Long) {
        if (running) return
        if (state == State.IDLE || state == State.DONE) {
            reset()
            totalMs = timerMs
        }
        state = State.RUNNING
        runStart = clock()
    }

    /** Freezes the time. */
    fun pause() {
        if (!running) return
        accumMs = elapsed()
        state = State.PAUSED
    }

    fun finish() {
        accumMs = totalMs
        state = State.DONE
    }

    fun reset() {
        state = State.IDLE
        totalMs = 0L
        accumMs = 0L
        timerBaseMs = 0L
    }

    /** Timer switched off (no limit) or on (its length counts from now). Returns true if it finished. */
    fun setTimerOn(on: Boolean, durationMs: Long): Boolean {
        if (state == State.IDLE) return false
        if (!on) {
            totalMs = 0L
        } else if (!hasTimer) {
            timerBaseMs = elapsed()
            totalMs = timerBaseMs + durationMs
        }
        return settle()
    }

    /** Timer length changed (keeps counting from where it started). Returns true if it finished. */
    fun setDuration(durationMs: Long): Boolean {
        if (state == State.IDLE || !hasTimer) return false
        totalMs = timerBaseMs + durationMs
        return settle()
    }

    /** From the timer slider: set the time left directly. Returns true if it finished. */
    fun setTimeLeft(leftMs: Long, durationMs: Long): Boolean {
        if (state == State.IDLE) return false
        totalMs = elapsed() + leftMs
        timerBaseMs = totalMs - durationMs
        return settle()
    }

    /** After the time changed: a finished session with time again can be resumed; no time left finishes it. */
    private fun settle(): Boolean {
        if (state == State.DONE && (!hasTimer || totalMs > accumMs)) state = State.PAUSED
        if (hasTimer && remainingMs() == 0L && (state == State.RUNNING || state == State.PAUSED)) {
            finish()
            return true
        }
        return false
    }
}
