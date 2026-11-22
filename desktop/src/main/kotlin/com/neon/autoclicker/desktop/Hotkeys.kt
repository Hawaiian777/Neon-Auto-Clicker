package com.neon.autoclicker.desktop

import com.sun.jna.Pointer
import javax.swing.SwingUtilities

/**
 * The start/pause hotkey: the Windows counterpart of Android's Volume Down option.
 * Registered with Windows (RegisterHotKey) only while the floating controls are showing and
 * the option is on, so the key works in any app and is left alone the rest of the time.
 */
object Hotkeys {
    /** Keys that can be picked: label to Windows virtual-key code. */
    val KEYS: List<Pair<String, Int>> = (1..12).map { "F$it" to (0x6F + it) } + listOf(
        "Pause" to 0x13,
        "Scroll Lock" to 0x91,
        "Insert" to 0x2D,
        "Home" to 0x24,
        "End" to 0x23,
        "Page Up" to 0x21,
        "Page Down" to 0x22,
    )

    fun label(vk: Int) = KEYS.firstOrNull { it.second == vk }?.first ?: "F6"

    enum class Status { OFF, ACTIVE, IN_USE, UNAVAILABLE }

    @Volatile var status = Status.OFF
        private set

    private var thread: Thread? = null
    @Volatile private var threadId = 0
    private var onStatus: (() -> Unit)? = null

    fun setStatusListener(l: () -> Unit) { onStatus = l }

    /** Registers [vk] (stops any previous key). [onPress] runs on the UI thread. */
    fun start(vk: Int, onPress: () -> Unit) {
        stop()
        val u = Win.user32
        val k = Win.kernel32
        if (u == null || k == null) {
            setStatus(Status.UNAVAILABLE)
            return
        }
        val t = Thread({
            try {
                val msg = MSG()
                u.PeekMessageW(msg, null, 0, 0, 0) // creates this thread's message queue
                threadId = k.GetCurrentThreadId()
                if (!u.RegisterHotKey(null, 1, 0x4000 /* MOD_NOREPEAT */, vk)) {
                    setStatus(Status.IN_USE)
                    return@Thread
                }
                setStatus(Status.ACTIVE)
                while (true) {
                    val r = u.GetMessageW(msg, null, 0, 0)
                    if (r <= 0) break // WM_QUIT or error
                    if (msg.message == 0x0312 /* WM_HOTKEY */) SwingUtilities.invokeLater(onPress)
                }
                u.UnregisterHotKey(null, 1)
            } catch (t: Throwable) {
                setStatus(Status.UNAVAILABLE)
            }
        }, "hotkey")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        val t = thread ?: run { setStatus(Status.OFF); return }
        thread = null
        val id = threadId
        try {
            if (id != 0) Win.user32?.PostThreadMessageW(id, 0x0012 /* WM_QUIT */, null, Pointer.NULL)
            t.join(500)
        } catch (e: Throwable) { }
        threadId = 0
        setStatus(Status.OFF)
    }

    private fun setStatus(s: Status) {
        if (status == s) return
        status = s
        onStatus?.let { SwingUtilities.invokeLater(it) }
    }
}
