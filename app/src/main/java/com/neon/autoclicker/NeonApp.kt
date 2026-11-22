package com.neon.autoclicker

import android.app.Application

/**
 * Runs once each time Android starts the app (after it was fully closed, after a phone
 * restart, and so on). The auto-stop timer is deliberately not remembered: it always starts
 * OFF with a length of 1:00. All other settings are kept.
 */
class NeonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.migrateOldSettings(this)
        Prefs.resetTimer(this)
    }
}
