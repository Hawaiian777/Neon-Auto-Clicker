package com.neon.autoclicker

import android.content.Context

/** Saved profiles on the phone. The [Profile] model and its storage format are shared with Windows. */
object Profiles {
    fun load(c: Context): MutableList<Profile> = Profile.listFromJson(Prefs.get(c).getString(Prefs.KEY_PROFILES, null))

    private fun saveAll(c: Context, list: List<Profile>) {
        Prefs.get(c).edit().putString(Prefs.KEY_PROFILES, Profile.listToJson(list)).apply()
    }

    /** Snapshot of the current setup. */
    fun current(c: Context, name: String): Profile = Profile(
        name = name,
        intervalQ = Prefs.quarters(c),
        timerSec = Prefs.timerDuration(c),
        targetDp = Prefs.targetDp(c),
        mode = Prefs.mode(c),
        targets = Prefs.loadTargets(c)?.map { Pt(it.x, it.y) } ?: listOf(Pt(0.5f, 0.5f)),
    )

    /** Saves, replacing any profile with the same name. */
    fun saveOrReplace(c: Context, profile: Profile) {
        val list = load(c)
        val i = list.indexOfFirst { it.name.equals(profile.name, ignoreCase = true) }
        if (i >= 0) list[i] = profile else list += profile
        saveAll(c, list)
    }

    fun delete(c: Context, name: String) {
        saveAll(c, load(c).filterNot { it.name == name })
    }

    /** Makes the profile the current setup. Never starts clicking. */
    fun apply(c: Context, p: Profile) {
        Prefs.get(c).edit()
            .putInt(Prefs.KEY_INTERVAL_Q, p.intervalQ)
            .putInt(Prefs.KEY_TIMER_DUR, p.timerSec) // length only: the timer stays off unless turned on
            .putInt(Prefs.KEY_TARGET_DP, p.targetDp)
            .putInt(Prefs.KEY_MODE, p.mode)
            .putString(Prefs.KEY_TARGETS, Targets.toJson(p.targets))
            .putLong(Prefs.KEY_RELOAD, System.currentTimeMillis())
            .apply()
    }
}
