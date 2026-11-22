package com.neon.autoclicker.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.awt.Robot
import java.awt.Window
import java.awt.event.InputEvent

// ---------------------------------------------------------------------------------------------
// Win32 functions used by the app (JNA, no extra native code). Everything here is optional:
// if Windows can't be reached (other OS, blocked DLL), the app falls back to plain Java.
// ---------------------------------------------------------------------------------------------

@Structure.FieldOrder("x", "y")
class POINT : Structure() {
    @JvmField var x = 0
    @JvmField var y = 0
}

@Structure.FieldOrder("left", "top", "right", "bottom")
class RECT : Structure() {
    @JvmField var left = 0
    @JvmField var top = 0
    @JvmField var right = 0
    @JvmField var bottom = 0
}

@Structure.FieldOrder("hWnd", "message", "wParam", "lParam", "time", "ptX", "ptY", "lPrivate")
class MSG : Structure() {
    @JvmField var hWnd: Pointer? = null
    @JvmField var message = 0
    @JvmField var wParam: Pointer? = null
    @JvmField var lParam: Pointer? = null
    @JvmField var time = 0
    @JvmField var ptX = 0
    @JvmField var ptY = 0
    @JvmField var lPrivate = 0
}

@Suppress("FunctionName")
interface User32 : StdCallLibrary {
    fun GetWindowLongPtrW(hWnd: Pointer, index: Int): Long
    fun SetWindowLongPtrW(hWnd: Pointer, index: Int, value: Long): Long
    fun SetWindowPos(hWnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
    fun GetWindowRect(hWnd: Pointer, rect: RECT): Boolean
    fun SetCursorPos(x: Int, y: Int): Boolean
    fun GetCursorPos(p: POINT): Boolean
    fun mouse_event(flags: Int, dx: Int, dy: Int, data: Int, extra: Pointer?)
    fun GetAsyncKeyState(vk: Int): Short
    fun RegisterHotKey(hWnd: Pointer?, id: Int, mods: Int, vk: Int): Boolean
    fun UnregisterHotKey(hWnd: Pointer?, id: Int): Boolean
    fun GetMessageW(msg: MSG, hWnd: Pointer?, min: Int, max: Int): Int
    fun PeekMessageW(msg: MSG, hWnd: Pointer?, min: Int, max: Int, remove: Int): Boolean
    fun PostThreadMessageW(threadId: Int, msg: Int, w: Pointer?, l: Pointer?): Boolean
    fun SystemParametersInfoW(action: Int, param: Int, out: IntArray, winIni: Int): Boolean
}

@Suppress("FunctionName")
interface Kernel32 : StdCallLibrary {
    fun GetCurrentThreadId(): Int
    fun SetThreadExecutionState(flags: Int): Int
}

@Suppress("FunctionName")
interface Shell32 : StdCallLibrary {
    fun IsUserAnAdmin(): Boolean
    fun ShellExecuteW(hwnd: Pointer?, op: WString?, file: WString, params: WString?, dir: WString?, show: Int): Pointer?
}

@Suppress("FunctionName")
interface Dwmapi : StdCallLibrary {
    fun DwmSetWindowAttribute(hwnd: Pointer, attr: Int, value: IntArray, size: Int): Int
}

@Suppress("FunctionName")
interface Advapi32 : StdCallLibrary {
    fun RegGetValueW(hkey: Pointer, subKey: WString, value: WString, flags: Int, type: IntArray?, data: IntArray?, size: IntArray?): Int
}

/** Access to Windows, or null pieces when unavailable. */
object Win {
    private const val GWL_EXSTYLE = -20
    private const val WS_EX_TRANSPARENT = 0x00000020L
    private const val WS_EX_TOOLWINDOW = 0x00000080L
    private const val WS_EX_LAYERED = 0x00080000L
    private const val WS_EX_NOACTIVATE = 0x08000000L
    private const val WS_EX_APPWINDOW = 0x00040000L
    private const val SWP_NOSIZE = 0x1
    private const val SWP_NOMOVE = 0x2
    private const val SWP_NOZORDER = 0x4
    private const val SWP_NOACTIVATE = 0x10
    private const val SWP_FRAMECHANGED = 0x20

    val isWindows = System.getProperty("os.name", "").lowercase().startsWith("windows")

    val user32: User32? = load("user32", User32::class.java)
    val kernel32: Kernel32? = load("kernel32", Kernel32::class.java)
    private val shell32: Shell32? = load("shell32", Shell32::class.java)
    private val dwm: Dwmapi? = load("dwmapi", Dwmapi::class.java)
    private val advapi: Advapi32? = load("advapi32", Advapi32::class.java)

    /** True when real Windows input (SetCursorPos + mouse_event) is available. */
    val nativeInput: Boolean get() = user32 != null

    private fun <T : com.sun.jna.Library> load(name: String, c: Class<T>): T? {
        if (!isWindows) return null
        return try {
            Native.load(name, c)
        } catch (t: Throwable) {
            null
        }
    }

    fun hwnd(w: Window): Pointer? = if (user32 == null || !w.isDisplayable) null else try {
        Native.getWindowPointer(w)
    } catch (t: Throwable) {
        null
    }

    /**
     * Overlay window styles: never takes focus (clicking it doesn't pull focus from the app being
     * clicked), no taskbar button, and optionally click-through.
     */
    fun styleOverlay(w: Window, clickThrough: Boolean) {
        val u = user32 ?: return
        val h = hwnd(w) ?: return
        try {
            var ex = u.GetWindowLongPtrW(h, GWL_EXSTYLE)
            val before = ex
            ex = ex or WS_EX_NOACTIVATE or WS_EX_TOOLWINDOW
            ex = ex and WS_EX_APPWINDOW.inv()
            ex = if (clickThrough) ex or WS_EX_TRANSPARENT or WS_EX_LAYERED else ex and WS_EX_TRANSPARENT.inv()
            if (ex != before) {
                u.SetWindowLongPtrW(h, GWL_EXSTYLE, ex)
                u.SetWindowPos(h, null, 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
            }
        } catch (t: Throwable) {
            // keep the default style
        }
    }

    /** Window rectangle in physical screen pixels (what the mouse uses), or null. */
    fun physicalRect(w: Window): IntArray? {
        val u = user32 ?: return null
        val h = hwnd(w) ?: return null
        return try {
            val r = RECT()
            if (u.GetWindowRect(h, r)) intArrayOf(r.left, r.top, r.right, r.bottom) else null
        } catch (t: Throwable) {
            null
        }
    }

    // ---- Mouse ----
    private const val MOUSEEVENTF_LEFTDOWN = 0x0002
    private const val MOUSEEVENTF_LEFTUP = 0x0004

    fun cursor(): IntArray? {
        val u = user32 ?: return null
        return try {
            val p = POINT()
            if (u.GetCursorPos(p)) intArrayOf(p.x, p.y) else null
        } catch (t: Throwable) {
            null
        }
    }

    fun moveCursor(x: Int, y: Int) {
        try { user32?.SetCursorPos(x, y) } catch (t: Throwable) { }
    }

    fun leftDown() { try { user32?.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, null) } catch (t: Throwable) { } }
    fun leftUp() { try { user32?.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, null) } catch (t: Throwable) { } }

    /** Is a mouse button held right now (left or right)? */
    fun mouseButtonDown(): Boolean {
        val u = user32 ?: return false
        return try {
            (u.GetAsyncKeyState(0x01).toInt() and 0x8000) != 0 || (u.GetAsyncKeyState(0x02).toInt() and 0x8000) != 0
        } catch (t: Throwable) {
            false
        }
    }

    // ---- Power ----
    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002

    /** Keeps the PC (and screen) awake while clicking. Call from the UI thread, which lives as long as the app. */
    fun keepAwake(on: Boolean) {
        try {
            kernel32?.SetThreadExecutionState(if (on) ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED else ES_CONTINUOUS)
        } catch (t: Throwable) { }
    }

    // ---- Admin ----
    fun isAdmin(): Boolean = try { shell32?.IsUserAnAdmin() == true } catch (t: Throwable) { false }

    /** Starts [exe] elevated (Windows shows its UAC prompt). True if it launched. */
    fun runElevated(exe: String, args: String?): Boolean {
        val s = shell32 ?: return false
        return try {
            val r = s.ShellExecuteW(null, WString("runas"), WString(exe), args?.let { WString(it) }, null, 1)
            Pointer.nativeValue(r) > 32
        } catch (t: Throwable) {
            false
        }
    }

    // ---- Appearance ----
    /** Windows dark mode setting for apps (Settings > Personalization > Colors), or null if unknown. */
    fun systemIsDark(): Boolean? {
        val a = advapi ?: return null
        return try {
            val hkcu = Pointer.createConstant(0x80000001.toInt().toLong())
            val data = IntArray(1)
            val size = intArrayOf(4)
            val rc = a.RegGetValueW(
                hkcu, WString("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"),
                WString("AppsUseLightTheme"), 0x00000010 /* RRF_RT_REG_DWORD */, null, data, size
            )
            if (rc == 0) data[0] == 0 else null
        } catch (t: Throwable) {
            null
        }
    }

    /** Windows "Show animations" setting off = reduced motion (the Airbus lights then glow steadily). */
    fun animationsOff(): Boolean {
        val u = user32 ?: return false
        return try {
            val v = IntArray(1)
            u.SystemParametersInfoW(0x1042 /* SPI_GETCLIENTAREAANIMATION */, 0, v, 0) && v[0] == 0
        } catch (t: Throwable) {
            false
        }
    }

    /** Title bar in the app's colors: dark mode flag, caption and text color (Windows 11; 10 takes the dark flag only). */
    fun styleTitleBar(w: Window, dark: Boolean, caption: Int, text: Int) {
        val d = dwm ?: return
        val h = hwnd(w) ?: return
        fun bgr(c: Int) = ((c and 0xFF) shl 16) or (c and 0xFF00) or ((c shr 16) and 0xFF)
        try {
            d.DwmSetWindowAttribute(h, 20, intArrayOf(if (dark) 1 else 0), 4)
            d.DwmSetWindowAttribute(h, 35, intArrayOf(bgr(caption)), 4)
            d.DwmSetWindowAttribute(h, 36, intArrayOf(bgr(text)), 4)
        } catch (t: Throwable) { }
    }
}

/**
 * Presses the left mouse button at a point and puts the pointer back where it was.
 * Windows: SetCursorPos + mouse_event in physical pixels. Elsewhere: java.awt.Robot.
 */
class Mouse {
    private val robot: Robot? = try { Robot() } catch (t: Throwable) { null }

    /** [phys] = physical pixels (Windows), [logical] = Java screen coordinates (fallback). */
    fun click(phys: IntArray?, logicalX: Int, logicalY: Int, holdMs: Long = 25L) {
        if (Win.nativeInput && phys != null) {
            val back = Win.cursor()
            Win.moveCursor(phys[0], phys[1])
            Win.leftDown()
            sleep(holdMs)
            Win.leftUp()
            if (back != null) Win.moveCursor(back[0], back[1])
            return
        }
        val r = robot ?: return
        val back = try { java.awt.MouseInfo.getPointerInfo()?.location } catch (t: Throwable) { null }
        r.mouseMove(logicalX, logicalY)
        r.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        sleep(holdMs)
        r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        if (back != null) r.mouseMove(back.x, back.y)
    }

    val available: Boolean get() = Win.nativeInput || robot != null

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) { }
}
