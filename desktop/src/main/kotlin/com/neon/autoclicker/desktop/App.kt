package com.neon.autoclicker.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Frame
import java.awt.Image
import java.awt.MenuItem
import java.awt.Point
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.util.Properties
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.UIManager
import kotlin.system.exitProcess

/** Ties the main window, the floating controls and the tray icon together. */
class App {
    companion object {
        private val versionProps = Properties().apply {
            try { App::class.java.getResourceAsStream("/neon-version.properties")?.use { load(it) } } catch (e: Exception) { }
        }
        val VERSION: String = versionProps.getProperty("version", "dev")
        val BUILD: String = versionProps.getProperty("build", "0")
    }

    val controls = FloatingControls(this)
    lateinit var main: MainWindow
        private set
    private var tray: TrayIcon? = null
    private var trayToggle: MenuItem? = null
    private var trayOverlay: MenuItem? = null

    fun start(showMain: Boolean = true) {
        main = MainWindow(this)
        setupTray()
        if (showMain) main.isVisible = true
    }

    // ---- Main window ----

    fun showMain(section: MainWindow.Section) = main.showSection(section)

    /** Clicks are held while our own window is in front (Android: while the app is on screen). */
    fun mainIsActive(): Boolean = main.isActiveApp()

    fun mainCovers(p: Point): Boolean = main.covers(p)

    fun mainWindowIfShown(): Window? = if (::main.isInitialized && main.isShowing) main else null

    /**
     * Closing the window: with the floating controls showing, the app keeps running in the
     * notification area (like leaving the app on Android); otherwise it exits.
     */
    fun onMainClosing() {
        if (controls.isOverlayShown) {
            if (tray != null) {
                main.isVisible = false
                tray?.displayMessage("Neon Auto Clicker", "Still running with the floating controls. Close them with ✕ to exit.", TrayIcon.MessageType.NONE)
            } else main.extendedState = main.extendedState or Frame.ICONIFIED
        } else exit()
    }

    fun onOverlayChanged() {
        if (!::main.isInitialized) return
        main.refreshService()
        if (!controls.isOverlayShown && !main.isVisible) showMain(MainWindow.Section.TOP) // never leave the app invisible
        updateTray()
    }

    fun onSessionChanged() = updateTray()

    // ---- Tray ----

    private fun setupTray() {
        if (!SystemTray.isSupported()) return
        try {
            val menu = PopupMenu()
            val open = MenuItem("Open Neon Auto Clicker").apply { addActionListener { SwingUtilities.invokeLater { showMain(MainWindow.Section.TOP) } } }
            val overlay = MenuItem("Show floating controls").apply {
                addActionListener {
                    SwingUtilities.invokeLater { if (controls.isOverlayShown) controls.hideOverlay() else controls.showOverlay() }
                }
            }
            val toggle = MenuItem("Start clicking").apply {
                addActionListener { SwingUtilities.invokeLater { if (controls.isOverlayShown) controls.toggle() } }
            }
            val quit = MenuItem("Exit").apply { addActionListener { SwingUtilities.invokeLater { exit() } } }
            menu.add(open); menu.add(overlay); menu.add(toggle); menu.addSeparator(); menu.add(quit)
            val icon = TrayIcon(AppIcon.image(32), "Neon Auto Clicker", menu).apply {
                isImageAutoSize = true
                addActionListener { SwingUtilities.invokeLater { showMain(MainWindow.Section.TOP) } }
            }
            SystemTray.getSystemTray().add(icon)
            tray = icon
            trayToggle = toggle
            trayOverlay = overlay
            updateTray()
        } catch (e: Exception) {
            tray = null
        }
    }

    private fun updateTray() {
        trayOverlay?.label = if (controls.isOverlayShown) "Hide floating controls" else "Show floating controls"
        trayToggle?.label = controls.stateLabel
        trayToggle?.isEnabled = controls.isOverlayShown
    }

    // ---- Exit / restart ----

    fun exit() {
        controls.hideOverlay()
        Hotkeys.stop()
        Win.keepAwake(false)
        tray?.let { try { SystemTray.getSystemTray().remove(it) } catch (e: Exception) { } }
        try { Prefs.store.flush() } catch (e: Exception) { }
        SingleInstance.release()
        exitProcess(0)
    }

    /** Starts a second copy as administrator (Windows asks first), then this one exits. */
    fun restartElevated() {
        val exe = ProcessHandle.current().info().command().orElse(null)
        if (exe == null) { main.toast("Couldn't find the app to restart"); return }
        val launcherArgs = if (exe.lowercase().endsWith("java.exe") || exe.lowercase().endsWith("javaw.exe")) {
            // started from a plain Java install (development): pass the same class path
            "-cp \"${System.getProperty("java.class.path")}\" com.neon.autoclicker.desktop.MainKt"
        } else null
        controls.hideOverlay()
        try { Prefs.store.flush() } catch (e: Exception) { }
        SingleInstance.release()
        if (Win.runElevated(exe, launcherArgs)) {
            tray?.let { try { SystemTray.getSystemTray().remove(it) } catch (e: Exception) { } }
            exitProcess(0)
        } else {
            SingleInstance.acquire { SwingUtilities.invokeLater { showMain(MainWindow.Section.TOP) } }
            main.toast("Restart cancelled")
        }
    }
}

/** One copy at a time: a second start just brings the running window to the front. */
object SingleInstance {
    private val dir = Store.defaultFile().parentFile
    private val lockFile = File(dir, "instance.lock")
    private val portFile = File(dir, "instance.port")
    private var raf: RandomAccessFile? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /** True if this is the only copy (then [onShow] runs when another copy asks to be shown). */
    fun acquire(onShow: () -> Unit): Boolean {
        dir.mkdirs()
        return try {
            val f = RandomAccessFile(lockFile, "rw")
            val l = f.channel.tryLock()
            if (l == null) {
                f.close()
                return false
            }
            raf = f
            lock = l
            val s = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
            server = s
            portFile.writeText(s.localPort.toString())
            Thread({
                while (!s.isClosed) {
                    try {
                        s.accept().use { c -> if (c.getInputStream().bufferedReader().readLine() == "show") onShow() }
                    } catch (e: Exception) { }
                }
            }, "single-instance").apply { isDaemon = true }.start()
            true
        } catch (e: Exception) {
            true // can't tell: just run
        }
    }

    /** Asks the running copy to show its window. */
    fun signalRunning(): Boolean = try {
        val port = portFile.readText().trim().toInt()
        Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write("show\n".toByteArray()) }
        true
    } catch (e: Exception) {
        false
    }

    fun release() {
        try { server?.close() } catch (e: Exception) { }
        try { lock?.release() } catch (e: Exception) { }
        try { raf?.close() } catch (e: Exception) { }
        server = null; lock = null; raf = null
    }
}

/** The app icon (same drawing as the Android launcher icon: neon crosshair on black). */
object AppIcon {
    fun image(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val u = size / 48f
        val green = Color(0x39, 0xFF, 0x14)
        g.color = Color.BLACK
        g.fill(Ellipse2D.Float(0f, 0f, size.toFloat(), size.toFloat()))
        // soft neon glow under the lines (bigger sizes only)
        if (size >= 32) {
            val sigma = 2.2f * u
            Glow.image(g, "appicon", size, size, sigma, 0xFF39FF14.toInt()) { mg ->
                mg.stroke = BasicStroke(2.5f * u)
                lines(mg, u)
            }?.let { Glow.drawImage(g, it, 0f, 0f, sigma, 0.9f) }
        }
        g.color = green
        g.stroke = BasicStroke(maxOf(1.2f, 2.5f * u))
        lines(g, u)
        g.fill(Ellipse2D.Float(21f * u, 21f * u, 6f * u, 6f * u))
        g.dispose()
        return img
    }

    private fun lines(g: java.awt.Graphics2D, u: Float) {
        g.draw(Ellipse2D.Float(11f * u, 11f * u, 26f * u, 26f * u))
        g.draw(Line2D.Float(24f * u, 7f * u, 24f * u, 17f * u))
        g.draw(Line2D.Float(24f * u, 31f * u, 24f * u, 41f * u))
        g.draw(Line2D.Float(7f * u, 24f * u, 17f * u, 24f * u))
        g.draw(Line2D.Float(31f * u, 24f * u, 41f * u, 24f * u))
    }

    fun images(): List<Image> = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256).map { image(it) }
}

fun main(args: Array<String>) {
    // crisp text and no grey flash while windows appear
    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("sun.awt.noerasebackground", "true")
    val minimized = args.contains("--minimized")

    Prefs.store = Store(Store.defaultFile())
    if (!SingleInstance.acquire { SwingUtilities.invokeLater { appRef?.showMain(MainWindow.Section.TOP) } }) {
        SingleInstance.signalRunning() // already running: bring that window to the front instead
        return
    }
    // Every start: the auto-stop timer is OFF at 1:00 (as when the Android app is opened).
    Prefs.resetTimer()
    Runtime.getRuntime().addShutdownHook(Thread {
        try { Win.keepAwake(false) } catch (e: Throwable) { }
        try { Prefs.store.flush() } catch (e: Throwable) { }
    })

    SwingUtilities.invokeLater {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()) } catch (e: Exception) { }
        ToolTipManager.sharedInstance().initialDelay = 500
        UIManager.put("ToolTip.background", Color(12, 12, 12))
        UIManager.put("ToolTip.foreground", Color(0xE6, 0xF2, 0xE8))
        UIManager.put("ToolTip.border", javax.swing.BorderFactory.createLineBorder(Color(0x39, 0xFF, 0x14, 140)))
        UIManager.put("ToolTip.font", Fonts.sans(12f))
        val app = App()
        appRef = app
        app.start(showMain = !minimized)
    }
}

@Volatile internal var appRef: App? = null
