package com.neon.autoclicker

import java.util.Locale

/**
 * Settings shared by Android and Windows: storage keys, defaults, limits, presets, step sizes
 * and the labels shown for them. Both apps store the same keys with the same meaning.
 */
object Config {
    // ---- Keys ----
    const val KEY_INTERVAL_Q = "interval_quarters"   // tap interval in 0.25 s steps
    const val KEY_VOLUME_TOGGLE = "volume_toggle"    // Android: Volume Down · Windows: the hotkey
    // Auto-stop timer. Unlike everything else, NOT kept between launches (both apps reset it).
    const val KEY_TIMER_ON = "timer_on"
    const val KEY_TIMER_DUR = "timer_duration_sec"
    const val KEY_WARN_SEC = "warn_seconds"          // warning lights threshold
    // Appearance
    const val KEY_THEME = "theme_index"
    const val KEY_APP_MODE = "app_mode"              // AppMode ordinal
    const val KEY_MENU_SIZE = "menu_size_index"
    const val KEY_PANEL_CLEAR = "panel_clear"        // floating menu background: false = Black
    const val KEY_PANEL_LOCKED = "panel_locked"      // floating menu position lock
    const val KEY_TARGET_DP = "target_size_dp"
    // Floating controls. Positions are fractions of the screen (0..1) so they follow rotation / resolution.
    const val KEY_TARGETS = "targets_json"
    const val KEY_PANEL_FX = "panel_fx"
    const val KEY_PANEL_FY = "panel_fy"
    const val KEY_TARGET_HIDDEN = "target_hidden"
    const val KEY_COLLAPSED = "menu_collapsed"
    const val KEY_MODE = "multi_mode"
    const val KEY_PROFILES = "profiles_json"
    /** Written when a profile is loaded so the floating controls reload their targets. */
    const val KEY_RELOAD = "reload_stamp"

    // ---- Limits and defaults ----
    const val MIN_Q = 1           // 0.25 s
    const val MAX_Q = 14400       // 60 min (custom intervals)
    const val SLIDER_MAX_Q = 480  // the slider covers 0.25 s – 2 min
    const val DEFAULT_Q = 4       // 1 s

    const val DEFAULT_TIMER_SEC = 60
    const val TIMER_MAX_SEC = 86399 // 23:59:59
    const val CUSTOM = -1

    const val WARN_DEFAULT = 60
    const val WARN_MIN = 5
    const val WARN_MAX = 3599     // 59:59
    val WARN_PRESETS = listOf(10, 30, 60, 120, 180, 300)

    const val MODE_SEQUENCE = 0   // one target per tap, in order 1, 2, 3...
    const val MODE_ALL = 1        // every target on each tap, in order
    const val MAX_TARGETS = 10

    const val DEFAULT_THEME = 3     // Neon Green
    const val DEFAULT_MENU_SIZE = 2 // Normal

    const val TARGET_MIN_DP = 16
    const val TARGET_MAX_DP = 96
    const val TARGET_STEP_DP = 2
    const val TARGET_DEFAULT_DP = 32

    val MENU_SIZES: List<Pair<String, Float>> = listOf(
        "Extra Small" to 0.7f,
        "Small" to 0.85f,
        "Normal" to 1.0f,
        "Large" to 1.2f,
        "Extra Large" to 1.4f,
        "XXL" to 1.65f,
    )

    /** Quick interval buttons: label to number of 0.25 s steps. */
    val QUICK_INTERVALS: List<Pair<String, Int>> = listOf(
        "0.25s" to 1, "0.5s" to 2, "1s" to 4, "2s" to 8, "3s" to 12,
        "4s" to 16, "5s" to 20, "6s" to 24, "7s" to 28, "8s" to 32,
        "9s" to 36, "10s" to 40, "15s" to 60, "30s" to 120, "1m" to 240,
        "2m" to 480, "5m" to 1200, "10m" to 2400,
    )

    /** Timer lengths offered in lists (Custom = any length). */
    val TIMER_PRESETS: List<Pair<String, Int>> = listOf(
        "1 minute" to 60,
        "5 minutes" to 300,
        "10 minutes" to 600,
        "15 minutes" to 900,
        "30 minutes" to 1800,
        "45 minutes" to 2700,
        "1 hour" to 3600,
        "1.5 hours" to 5400,
        "2 hours" to 7200,
        "3 hours" to 10800,
        "4 hours" to 14400,
        "5 hours" to 18000,
        "6 hours" to 21600,
        "7 hours" to 25200,
        "Custom…" to CUSTOM,
    )

    /** Timer slider stops: off, 10 s steps to 10 min, 1 min steps to 2 h, 5 min steps to 7 h. */
    val TIMER_SLIDER: IntArray = buildList {
        add(0)
        for (v in 10..600 step 10) add(v)
        for (v in 660..7200 step 60) add(v)
        for (v in 7500..25200 step 300) add(v)
    }.toIntArray()

    // ---- Clamps (what a stored value means) ----
    fun clampQuarters(q: Int) = q.coerceIn(MIN_Q, MAX_Q)
    fun clampTimer(sec: Int) = sec.coerceIn(1, TIMER_MAX_SEC)
    fun clampWarn(sec: Int) = sec.coerceIn(1, WARN_MAX)
    fun clampTargetDp(dp: Int) = dp.coerceIn(TARGET_MIN_DP, TARGET_MAX_DP)
    fun clampMenuSize(i: Int) = i.coerceIn(0, MENU_SIZES.lastIndex)
    fun normalizeMode(m: Int) = if (m == MODE_ALL) MODE_ALL else MODE_SEQUENCE

    /** Slider position closest to [sec] (never above it unless it's the first stop). */
    fun sliderIndexFor(sec: Long): Int {
        var best = 0
        for (i in TIMER_SLIDER.indices) if (TIMER_SLIDER[i] <= sec) best = i
        return best
    }

    // ---- Steps ----
    /** +/- interval steps: 0.25 s up to 2 min, then 1 s up to 10 min, then 5 s. */
    fun stepAbove(q: Int) = when {
        q < 480 -> 1
        q < 2400 -> 4
        else -> 20
    }
    fun stepBelow(q: Int) = stepAbove(q - 1)

    /** Custom warning time steps: 5 s under 1 min, 15 s under 5 min, 30 s under 30 min, then 1 min. */
    fun warnStepAbove(v: Int) = when {
        v < 60 -> 5
        v < 300 -> 15
        v < 1800 -> 30
        else -> 60
    }
    fun warnStepBelow(v: Int) = warnStepAbove(v - 1)

    // ---- Labels ----
    fun hms(totalSec: Long): String {
        val s = if (totalSec < 0) 0 else totalSec
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    /** Compact countdown: M:SS under an hour, H:MM:SS above. */
    fun shortTime(totalSec: Long): String {
        val s = if (totalSec < 0) 0 else totalSec
        return if (s >= 3600) hms(s) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /** "Off", "45 s", "5 min", "1.5 h", "1:02:30" */
    fun durLabel(sec: Int): String = when {
        sec <= 0 -> "Off"
        sec < 60 -> "$sec s"
        sec < 3600 && sec % 60 == 0 -> "${sec / 60} min"
        sec >= 3600 && sec % 1800 == 0 -> "${trim(sec / 3600.0)} h"
        else -> shortTime(sec.toLong())
    }

    /** Short chip label: "10s", "1m", "1:30". */
    fun warnShort(sec: Int): String = when {
        sec < 60 -> "${sec}s"
        sec % 60 == 0 -> "${sec / 60}m"
        else -> String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
    }

    /** "1.00 s", "2 min", "1 min 30.25 s" */
    fun formatInterval(q: Int): String {
        if (q < 240) return String.format(Locale.US, "%.2f s", q * 0.25)
        val m = q / 240
        val rest = q % 240
        return if (rest == 0) "$m min" else "$m min ${trim(rest * 0.25)} s"
    }

    /** "≈ 60 taps per minute" / "1 tap every 2.5 minutes" */
    fun rateHint(q: Int): String {
        val sec = q * 0.25
        val perMin = 60.0 / sec
        return if (perMin >= 1) "≈ ${trim(perMin)} taps per minute" else "1 tap every ${trim(sec / 60)} minutes"
    }

    fun trim(d: Double): String = String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')
}
