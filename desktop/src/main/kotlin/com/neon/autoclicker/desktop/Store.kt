package com.neon.autoclicker.desktop

import com.neon.autoclicker.AppMode
import com.neon.autoclicker.Config
import com.neon.autoclicker.Palette
import com.neon.autoclicker.Pt
import com.neon.autoclicker.Targets
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Windows settings storage: the same keys and meanings as Android's SharedPreferences
 * (see [Config]), saved in %APPDATA%\NeonAutoClicker\settings.properties.
 *
 * Like SharedPreferences, listeners hear about every key whose value really changed, on the
 * UI thread. Writes go to disk shortly after, in the background, replacing the file atomically.
 */
class Store(private val file: File) {

    fun interface Listener {
        fun onChanged(key: String)
    }

    private val values = HashMap<String, String>()
    private val listeners = mutableListOf<Listener>()
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "settings-writer").apply { isDaemon = true } }
    @Volatile private var dirty = false

    init {
        try {
            if (file.isFile) {
                val p = Properties()
                file.inputStream().use { p.load(it) }
                p.stringPropertyNames().forEach { values[it] = p.getProperty(it) }
            }
        } catch (e: Exception) {
            // unreadable file: start from defaults (it's rewritten on the next change)
        }
    }

    // ---- Reading ----
    @Synchronized fun contains(key: String) = values.containsKey(key)
    @Synchronized fun getString(key: String, def: String?): String? = values[key] ?: def
    fun getInt(key: String, def: Int): Int = getString(key, null)?.toIntOrNull() ?: def
    fun getLong(key: String, def: Long): Long = getString(key, null)?.toLongOrNull() ?: def
    fun getFloat(key: String, def: Float): Float = getString(key, null)?.toFloatOrNull() ?: def
    fun getBoolean(key: String, def: Boolean): Boolean = getString(key, null)?.toBooleanStrictOrNull() ?: def

    // ---- Writing ----
    inner class Editor {
        private val puts = LinkedHashMap<String, String?>()
        fun putString(k: String, v: String?) = apply { puts[k] = v }
        fun putInt(k: String, v: Int) = apply { puts[k] = v.toString() }
        fun putLong(k: String, v: Long) = apply { puts[k] = v.toString() }
        fun putFloat(k: String, v: Float) = apply { puts[k] = v.toString() }
        fun putBoolean(k: String, v: Boolean) = apply { puts[k] = v.toString() }
        fun remove(k: String) = apply { puts[k] = null }

        fun apply() {
            val changed = mutableListOf<String>()
            synchronized(this@Store) {
                puts.forEach { (k, v) ->
                    val old = values[k]
                    if (old == v) return@forEach
                    if (v == null) values.remove(k) else values[k] = v
                    changed += k
                }
            }
            if (changed.isEmpty()) return
            scheduleSave()
            val notify = Runnable { changed.forEach { k -> listeners.toList().forEach { it.onChanged(k) } } }
            if (SwingUtilities.isEventDispatchThread()) notify.run() else SwingUtilities.invokeLater(notify)
        }
    }

    fun edit() = Editor()

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    private fun scheduleSave() {
        if (dirty) return
        dirty = true
        io.schedule({ saveNow() }, 250, TimeUnit.MILLISECONDS)
    }

    /** Writes pending changes now (also called on exit). */
    fun flush() {
        if (dirty) io.submit { saveNow() }.get(3, TimeUnit.SECONDS)
    }

    private fun saveNow() {
        if (!dirty) return
        dirty = false
        val snapshot = synchronized(this) { values.toSortedMap() }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("# Neon Auto Clicker settings (same keys as the Android app)\n")
                snapshot.forEach { (k, v) -> w.write("${esc(k, true)}=${esc(v, false)}\n") }
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            dirty = true // try again with the next change
        }
    }

    /** Properties-file escaping (keeps the JSON values readable). */
    private fun esc(s: String, key: Boolean): String {
        val sb = StringBuilder()
        s.forEachIndexed { i, c ->
            when {
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                (key && (c == '=' || c == ':' || c == ' ')) || (i == 0 && c == ' ') -> sb.append('\\').append(c)
                c == '#' || c == '!' -> if (i == 0) sb.append('\\').append(c) else sb.append(c)
                c.code < 0x20 || c.code > 0x7e -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    companion object {
        fun defaultFile(): File {
            val appData = System.getenv("APPDATA")
            val dir = if (!appData.isNullOrBlank()) File(appData, "NeonAutoClicker")
            else File(System.getProperty("user.home"), ".config/NeonAutoClicker")
            return File(dir, "settings.properties")
        }
    }
}

/**
 * The Windows counterpart of the Android `Prefs` object: the same readers and writers over the
 * same keys, using the shared clamps and labels in [Config].
 */
object Prefs {
    lateinit var store: Store

    // Windows-only extras (Android ignores them)
    const val KEY_HOTKEY_VK = "hotkey_vk"
    const val KEY_WIN_BOUNDS = "main_window_bounds"
    const val DEFAULT_HOTKEY = 0x75 // F6, the key the earlier Windows clicker used

    fun quarters(): Int = Config.clampQuarters(store.getInt(Config.KEY_INTERVAL_Q, Config.DEFAULT_Q))
    fun intervalMs(): Long = quarters() * 250L
    fun timerOn(): Boolean = store.getBoolean(Config.KEY_TIMER_ON, false)
    fun timerDuration(): Int = Config.clampTimer(store.getInt(Config.KEY_TIMER_DUR, Config.DEFAULT_TIMER_SEC))
    fun timerSeconds(): Int = if (timerOn()) timerDuration() else 0
    fun warnSeconds(): Int = Config.clampWarn(store.getInt(Config.KEY_WARN_SEC, Config.WARN_DEFAULT))
    fun themeIndex(): Int = Palette.clampIndex(store.getInt(Config.KEY_THEME, Config.DEFAULT_THEME))
    fun appMode(): AppMode = AppMode.values().getOrElse(store.getInt(Config.KEY_APP_MODE, 0)) { AppMode.NIGHT }
    fun menuSizeIndex(): Int = Config.clampMenuSize(store.getInt(Config.KEY_MENU_SIZE, Config.DEFAULT_MENU_SIZE))
    fun menuScale(): Float = Config.MENU_SIZES[menuSizeIndex()].second
    fun panelClear(): Boolean = store.getBoolean(Config.KEY_PANEL_CLEAR, false)
    fun panelLocked(): Boolean = store.getBoolean(Config.KEY_PANEL_LOCKED, false)
    fun targetDp(): Int = Config.clampTargetDp(store.getInt(Config.KEY_TARGET_DP, Config.TARGET_DEFAULT_DP))
    fun mode(): Int = Config.normalizeMode(store.getInt(Config.KEY_MODE, Config.MODE_SEQUENCE))
    /** Windows: the start/pause hotkey switch (Android: Volume Down). On by default on a PC. */
    fun hotkeyOn(): Boolean = store.getBoolean(Config.KEY_VOLUME_TOGGLE, true)
    fun hotkeyVk(): Int = store.getInt(KEY_HOTKEY_VK, DEFAULT_HOTKEY).let { vk -> if (Hotkeys.KEYS.any { it.second == vk }) vk else DEFAULT_HOTKEY }

    fun put(key: String, v: Int) = store.edit().putInt(key, v).apply()
    fun put(key: String, v: Boolean) = store.edit().putBoolean(key, v).apply()

    fun setQuarters(q: Int) = put(Config.KEY_INTERVAL_Q, q.coerceIn(Config.MIN_Q, Config.MAX_Q))
    fun setWarn(sec: Int) = put(Config.KEY_WARN_SEC, sec.coerceIn(Config.WARN_MIN, Config.WARN_MAX))
    fun setTimerOn(on: Boolean) = put(Config.KEY_TIMER_ON, on)
    fun setTimerDuration(sec: Int) = put(Config.KEY_TIMER_DUR, Config.clampTimer(sec))

    /** From the timer slider: 0 turns the timer off; anything else turns it on with that length. */
    fun setTimerSeconds(sec: Int) {
        val e = store.edit()
        if (sec <= 0) e.putBoolean(Config.KEY_TIMER_ON, false)
        else e.putBoolean(Config.KEY_TIMER_ON, true).putInt(Config.KEY_TIMER_DUR, Config.clampTimer(sec))
        e.apply()
    }

    /** Every start of the app: timer OFF, length 1:00 (deliberately not remembered, as on Android). */
    fun resetTimer() {
        store.edit().putBoolean(Config.KEY_TIMER_ON, false).putInt(Config.KEY_TIMER_DUR, Config.DEFAULT_TIMER_SEC).apply()
    }

    fun loadTargets(): MutableList<Pt>? = Targets.fromJson(store.getString(Config.KEY_TARGETS, null))
    fun saveTargets(list: List<Pt>) = store.edit().putString(Config.KEY_TARGETS, Targets.toJson(list)).apply()
    fun targetCount(): Int = loadTargets()?.size ?: 1
}
