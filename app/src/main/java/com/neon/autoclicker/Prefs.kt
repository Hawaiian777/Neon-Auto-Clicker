package com.neon.autoclicker

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF

/**
 * Android settings storage. Keys, defaults, limits and labels come from the shared [Config]
 * (also used by the Windows app); this file only reads and writes them on the phone.
 */
object Prefs {
    private const val NAME = "neon_clicker"

    // ---- Keys (shared) ----
    const val KEY_INTERVAL_Q = Config.KEY_INTERVAL_Q
    const val KEY_VOLUME_TOGGLE = Config.KEY_VOLUME_TOGGLE
    const val KEY_TIMER_ON = Config.KEY_TIMER_ON
    const val KEY_TIMER_DUR = Config.KEY_TIMER_DUR
    const val KEY_WARN_SEC = Config.KEY_WARN_SEC
    const val KEY_THEME = Config.KEY_THEME
    const val KEY_APP_MODE = Config.KEY_APP_MODE
    const val KEY_MENU_SIZE = Config.KEY_MENU_SIZE
    const val KEY_PANEL_CLEAR = Config.KEY_PANEL_CLEAR
    const val KEY_PANEL_LOCKED = Config.KEY_PANEL_LOCKED
    const val KEY_TARGET_DP = Config.KEY_TARGET_DP
    const val KEY_TARGETS = Config.KEY_TARGETS
    const val KEY_PANEL_FX = Config.KEY_PANEL_FX
    const val KEY_PANEL_FY = Config.KEY_PANEL_FY
    const val KEY_TARGET_HIDDEN = Config.KEY_TARGET_HIDDEN
    const val KEY_COLLAPSED = Config.KEY_COLLAPSED
    const val KEY_MODE = Config.KEY_MODE
    const val KEY_PROFILES = Config.KEY_PROFILES
    const val KEY_RELOAD = Config.KEY_RELOAD

    // ---- Limits and defaults (shared) ----
    const val MIN_Q = Config.MIN_Q
    const val MAX_Q = Config.MAX_Q
    const val SLIDER_MAX_Q = Config.SLIDER_MAX_Q
    const val DEFAULT_Q = Config.DEFAULT_Q
    const val DEFAULT_TIMER_SEC = Config.DEFAULT_TIMER_SEC
    const val TIMER_MAX_SEC = Config.TIMER_MAX_SEC
    const val CUSTOM = Config.CUSTOM
    const val WARN_DEFAULT = Config.WARN_DEFAULT
    const val WARN_MIN = Config.WARN_MIN
    const val WARN_MAX = Config.WARN_MAX
    const val MODE_SEQUENCE = Config.MODE_SEQUENCE
    const val MODE_ALL = Config.MODE_ALL
    const val MAX_TARGETS = Config.MAX_TARGETS
    const val TARGET_MIN_DP = Config.TARGET_MIN_DP
    const val TARGET_MAX_DP = Config.TARGET_MAX_DP
    const val TARGET_STEP_DP = Config.TARGET_STEP_DP
    const val TARGET_DEFAULT_DP = Config.TARGET_DEFAULT_DP
    val WARN_PRESETS get() = Config.WARN_PRESETS
    val MENU_SIZES get() = Config.MENU_SIZES
    val TIMER_PRESETS get() = Config.TIMER_PRESETS
    val TIMER_SLIDER get() = Config.TIMER_SLIDER

    fun get(c: Context): SharedPreferences = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    // ---- Readers ----
    fun quarters(c: Context): Int = Config.clampQuarters(get(c).getInt(KEY_INTERVAL_Q, DEFAULT_Q))
    fun intervalMs(c: Context): Long = quarters(c) * 250L
    fun timerOn(c: Context): Boolean = get(c).getBoolean(KEY_TIMER_ON, false)
    /** Timer length in seconds (whether or not the timer is on). */
    fun timerDuration(c: Context): Int = Config.clampTimer(get(c).getInt(KEY_TIMER_DUR, DEFAULT_TIMER_SEC))
    /** Seconds the auto-stop timer will run for, or 0 when it's off. */
    fun timerSeconds(c: Context): Int = if (timerOn(c)) timerDuration(c) else 0
    fun warnSeconds(c: Context): Int = Config.clampWarn(get(c).getInt(KEY_WARN_SEC, WARN_DEFAULT))
    fun themeIndex(c: Context): Int = Palette.clampIndex(get(c).getInt(KEY_THEME, Config.DEFAULT_THEME))
    fun appMode(c: Context): AppMode = AppMode.values().getOrElse(get(c).getInt(KEY_APP_MODE, 0)) { AppMode.NIGHT }
    fun menuSizeIndex(c: Context): Int = Config.clampMenuSize(get(c).getInt(KEY_MENU_SIZE, Config.DEFAULT_MENU_SIZE))
    fun menuScale(c: Context): Float = MENU_SIZES[menuSizeIndex(c)].second
    fun panelClear(c: Context): Boolean = get(c).getBoolean(KEY_PANEL_CLEAR, false)
    fun panelLocked(c: Context): Boolean = get(c).getBoolean(KEY_PANEL_LOCKED, false)
    fun targetDp(c: Context): Int = Config.clampTargetDp(get(c).getInt(KEY_TARGET_DP, TARGET_DEFAULT_DP))
    fun mode(c: Context): Int = Config.normalizeMode(get(c).getInt(KEY_MODE, MODE_SEQUENCE))

    // ---- Timer ----
    fun setTimerOn(c: Context, on: Boolean) {
        get(c).edit().putBoolean(KEY_TIMER_ON, on).apply()
    }

    fun setTimerDuration(c: Context, sec: Int) {
        get(c).edit().putInt(KEY_TIMER_DUR, Config.clampTimer(sec)).apply()
    }

    /** From the timer slider: 0 turns the timer off; anything else turns it on with that length. */
    fun setTimerSeconds(c: Context, sec: Int) {
        val e = get(c).edit()
        if (sec <= 0) e.putBoolean(KEY_TIMER_ON, false)
        else e.putBoolean(KEY_TIMER_ON, true).putInt(KEY_TIMER_DUR, Config.clampTimer(sec))
        e.apply()
    }

    /** Fresh launch: timer OFF, length 1:00. Everything else stays as saved. */
    fun resetTimer(c: Context) {
        get(c).edit().putBoolean(KEY_TIMER_ON, false).putInt(KEY_TIMER_DUR, DEFAULT_TIMER_SEC).apply()
    }

    fun sliderIndexFor(sec: Long): Int = Config.sliderIndexFor(sec)

    // ---- Steps and labels (shared) ----
    fun stepAbove(q: Int) = Config.stepAbove(q)
    fun stepBelow(q: Int) = Config.stepBelow(q)
    fun warnStepAbove(v: Int) = Config.warnStepAbove(v)
    fun warnStepBelow(v: Int) = Config.warnStepBelow(v)
    fun hms(totalSec: Long) = Config.hms(totalSec)
    fun shortTime(totalSec: Long) = Config.shortTime(totalSec)
    fun durLabel(sec: Int) = Config.durLabel(sec)
    fun warnShort(sec: Int) = Config.warnShort(sec)
    fun formatInterval(q: Int) = Config.formatInterval(q)
    fun trim(d: Double) = Config.trim(d)

    // ---- Targets (stored with the shared format) ----
    fun loadTargets(c: Context): MutableList<PointF>? =
        Targets.fromJson(get(c).getString(KEY_TARGETS, null))?.mapTo(mutableListOf()) { PointF(it.x, it.y) }

    fun saveTargets(c: Context, list: List<PointF>) {
        get(c).edit().putString(KEY_TARGETS, Targets.toJson(list.map { Pt(it.x, it.y) })).apply()
    }

    fun targetCount(c: Context): Int = loadTargets(c)?.size ?: 1

    /**
     * One-time upgrade of settings saved by early versions (pixel positions, the old timer list).
     * Converts what's worth keeping and deletes the old keys, so nothing else has to know about them.
     */
    fun migrateOldSettings(c: Context) {
        val p = get(c)
        val old = listOf("target_cx", "target_cy", "panel_x", "panel_y", "timer_index", "custom_seconds")
        if (old.none { p.contains(it) }) return
        val dm = c.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1).toFloat()
        val h = dm.heightPixels.coerceAtLeast(1).toFloat()
        val e = p.edit()
        val cx = p.getInt("target_cx", -1)
        val cy = p.getInt("target_cy", -1)
        if (!p.contains(KEY_TARGETS) && cx >= 0 && cy >= 0) {
            e.putString(KEY_TARGETS, Targets.toJson(listOf(Pt((cx / w).coerceIn(0f, 1f), (cy / h).coerceIn(0f, 1f)))))
        }
        if (!p.contains(KEY_PANEL_FX) && p.contains("panel_x") && p.contains("panel_y")) {
            e.putFloat(KEY_PANEL_FX, (p.getInt("panel_x", 0) / w).coerceIn(0f, 1f))
            e.putFloat(KEY_PANEL_FY, (p.getInt("panel_y", 0) / h).coerceIn(0f, 1f))
        }
        old.forEach { e.remove(it) }
        e.apply()
    }
}
