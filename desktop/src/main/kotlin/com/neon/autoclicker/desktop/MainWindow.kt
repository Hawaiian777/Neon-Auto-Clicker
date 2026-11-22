package com.neon.autoclicker.desktop

import com.neon.autoclicker.AppMode
import com.neon.autoclicker.Config
import com.neon.autoclicker.Look
import com.neon.autoclicker.NeonColors
import com.neon.autoclicker.Palette
import com.neon.autoclicker.Profile
import com.neon.autoclicker.StatusColors
import com.neon.autoclicker.hex
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.LinearGradientPaint
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants

/**
 * The app window (port of MainActivity): the same seven sections, in the same order, with the
 * same controls. Every setting refreshes live, whether it was changed here or on the floating menu.
 */
class MainWindow(private val app: App) : JFrame("Neon Auto Clicker") {

    enum class Section { TOP, APPEARANCE }

    private lateinit var t: NeonColors
    private val root = object : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            if (t.look == Look.SUNSET) {
                g2.paint = LinearGradientPaint(0f, 0f, 0f, maxOf(1, height).toFloat(), floatArrayOf(0f, 0.5f, 1f),
                    Palette.SUNSET_GRADIENT.map { col(it) }.toTypedArray())
            } else g2.color = col(t.bg)
            g2.fillRect(0, 0, width, height)
        }
    }
    private var scroll: JScrollPane? = null

    // Views refreshed in place
    private lateinit var permCard: Lin
    private val perm = PermPainter()
    private var permAnim: Animator? = null
    private lateinit var permStatus: NText
    private lateinit var permList: Lin
    private lateinit var permTip: NText
    private lateinit var btnPermission: NButton
    private lateinit var intervalValue: NText
    private lateinit var intervalHint: NText
    private lateinit var seek: NSlider
    private lateinit var switchTimer: NSwitch
    private lateinit var btnTimer: NButton
    private lateinit var switchHotkey: NSwitch
    private lateinit var btnHotkey: NButton
    private lateinit var targetsInfo: NText
    private lateinit var profileList: Lin
    private lateinit var appearanceCard: Lin
    private lateinit var themeName: NText
    private lateinit var switchLock: NSwitch
    private lateinit var targetSeek: NSlider
    private lateinit var targetSizeValue: NText
    private lateinit var preview: NCrosshair
    private lateinit var btnLaunch: NButton
    private lateinit var btnCloseOverlay: NButton
    private lateinit var btnReturnTop: NButton

    private val swatches = mutableListOf<NSwatch>()
    private val intervalChips = mutableListOf<Pair<NButton, Int>>()
    private val warnChips = mutableListOf<Pair<NButton, Int>>()
    private val modeChips = mutableListOf<Pair<NButton, Int>>()
    private val appModeChips = mutableListOf<Pair<NButton, AppMode>>()
    private val menuChips = mutableListOf<Pair<NButton, Int>>()
    private val bgChips = mutableListOf<Pair<NButton, Boolean>>()

    /** Color + appearance the window was last built with; rebuild only when it changes. */
    private var styledWith: String? = null
    private val restyle = Timer(50) { applyTheme() }.apply { isRepeats = false }
    private val systemModeWatch = Timer(4000) { if (Prefs.appMode() == AppMode.SYSTEM && styledWith != styleKey()) applyTheme() }

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        iconImages = AppIcon.images()
        contentPane = root
        minimumSize = Dimension(400, 520)
        restoreBounds()
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = app.onMainClosing()
            override fun windowActivated(e: WindowEvent) {
                if (styledWith != styleKey()) applyTheme() else refreshAll()
            }
        })
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentMoved(e: java.awt.event.ComponentEvent) = saveBounds()
            override fun componentResized(e: java.awt.event.ComponentEvent) = saveBounds()
        })
        Prefs.store.addListener { key -> onPrefChanged(key) }
        Hotkeys.setStatusListener { refreshService(); refreshHotkey() }
        applyTheme()
        systemModeWatch.start()
    }

    // ---------------- Window position ----------------

    private fun restoreBounds() {
        val saved = Prefs.store.getString(Prefs.KEY_WIN_BOUNDS, null)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
        if (saved != null && saved.size == 4) {
            val r = Rectangle(saved[0], saved[1], saved[2].coerceAtLeast(400), saved[3].coerceAtLeast(520))
            if (Screens.all().any { it.intersects(r) && it.contains(Point(r.x + 40, r.y + 10)) }) { bounds = r; return }
        }
        val a = Screens.workArea(Screens.primary().location)
        val h = minOf(900, a.height - 40)
        setBounds(a.x + (a.width - 470) / 2, a.y + (a.height - h) / 2, 470, h)
    }

    private val boundsSaver = Timer(400) {
        if (isShowing && extendedState == Frame.NORMAL) {
            Prefs.store.edit().putString(Prefs.KEY_WIN_BOUNDS, "$x,$y,$width,$height").apply()
        }
    }.apply { isRepeats = false }

    private fun saveBounds() = boundsSaver.restart()

    // ---------------- Theme ----------------

    private fun styleKey() = "${Prefs.themeIndex()}|${Theme.resolveLook()}"

    /** Re-colors the whole window with the chosen color and appearance (keeps the scroll position). */
    fun applyTheme() {
        t = Theme.app()
        val keep = scroll?.verticalScrollBar?.value ?: 0
        permAnim?.stop()
        root.removeAll()
        val content = build()
        val sp = neonScroll(content, t)
        scroll = sp
        root.add(sp, BorderLayout.CENTER)
        root.revalidate()
        root.repaint()
        styledWith = styleKey()
        refreshAll()
        SwingUtilities.invokeLater { sp.verticalScrollBar.value = keep }
        Win.styleTitleBar(this, !t.isLight, t.topBar, t.main)
    }

    private fun onPrefChanged(key: String) {
        when (key) {
            Config.KEY_THEME, Config.KEY_APP_MODE -> restyle.restart()
            Config.KEY_MENU_SIZE, Config.KEY_PANEL_CLEAR, Config.KEY_PANEL_LOCKED -> refreshAppearance()
            Config.KEY_TARGET_DP -> refreshTargetSize()
            Config.KEY_INTERVAL_Q -> refreshInterval()
            Config.KEY_TIMER_ON, Config.KEY_TIMER_DUR -> refreshTimer()
            Config.KEY_WARN_SEC -> refreshWarn()
            Config.KEY_MODE, Config.KEY_TARGETS -> refreshTargets()
            Config.KEY_PROFILES -> refreshProfiles()
            Config.KEY_VOLUME_TOGGLE, Prefs.KEY_HOTKEY_VK -> { refreshHotkey(); refreshService() }
        }
    }

    fun refreshAll() {
        refreshService()
        refreshInterval()
        refreshTimer()
        refreshWarn()
        refreshHotkey()
        refreshTargets()
        refreshProfiles()
        refreshAppearance()
    }

    // ---------------- Building blocks ----------------

    private fun body(text: String, size: Float = 14f) = NText(text, Fonts.sans(size), t.body).apply { lineExtra = 3f }

    private fun card(title: String, perm: Boolean = false): Lin {
        val c = vbox().pad(16)
        if (!perm) {
            c.bg = { g, w, h ->
                val r = RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 32f, 32f)
                g.color = col(t.surface); g.fill(r)
                g.stroke = BasicStroke(1f); g.color = col(t.cardEdge); g.draw(r)
            }
        }
        val tc = if (perm) Theme.dark() else t
        c.add(NText(title, Fonts.mono(15f, bold = true, tracking = 0.05f), tc.main).apply { shadow = perm }, LP(mb = 10))
        return c
    }

    private fun outline(text: String, action: () -> Unit, tt: NeonColors = t) = NButton(text, tt, BStyle.OUTLINE).apply {
        onClick = action
    }

    private fun filled(text: String, action: () -> Unit) = NButton(text, t, BStyle.FILLED).apply {
        tfont = Fonts.mono(16f, bold = true)
        minH = 60
        onClick = action
    }

    private fun chipLp(h: Int = 40) = LP(0, h, weight = 1f).margins(3)

    private fun <T> chipRow(items: List<Pair<String, T>>, into: MutableList<Pair<NButton, T>>, h: Int = 40, onPick: (T) -> Unit): Lin {
        val r = hbox()
        items.forEach { (label, value) ->
            val b = chip(label, t, false, 12f) { onPick(value) }
            r.add(b, chipLp(h))
            into += b to value
        }
        return r
    }

    private fun chipGrid(perRow: Int, items: List<Pair<String, Int>>, into: MutableList<Pair<NButton, Int>>, onPick: (Int) -> Unit): Lin {
        val grid = vbox()
        var r: Lin? = null
        items.forEachIndexed { i, (label, value) ->
            if (i % perRow == 0) r = hbox().also { grid.add(it, LP(MATCH, WRAP)) }
            val b = chip(label, t, false, 12f) { onPick(value) }
            r?.add(b, chipLp())
            into += b to value
        }
        repeat((perRow - items.size % perRow) % perRow) { r?.add(vbox(), chipLp()) }
        return grid
    }

    // ---------------- The window ----------------

    private fun build(): Lin {
        swatches.clear(); intervalChips.clear(); warnChips.clear(); modeChips.clear()
        appModeChips.clear(); menuChips.clear(); bgChips.clear()
        val v = vbox().pad(20)
        v.tracksWidth = true

        v.add(NText("AUTO CLICKER", Fonts.mono(30f, bold = true, tracking = 0.12f), t.main).apply {
            if (!t.isLight) { glowSigma = 5f; glowColor = t.main }
            padding(16, 16, 16, 16) // room for the glow (drawn inside the view, like Android)
        }, LP(gravity = G_CENTER, mt = 8 - 16, mb = -16))
        v.add(body("Clicks wherever you place the crosshair"), LP(gravity = G_CENTER, mt = 4))
        btnReturnTop = filled("↩  RETURN TO FLOATING CONTROLS") { onLaunchButton() }
        v.add(btnReturnTop, LP(MATCH, WRAP, mt = 14))

        // 1. Permission (Windows: what the clicker can do on this PC)
        permCard = card("1 · PERMISSION", perm = true)
        permCard.bg = { g, w, h -> perm.paint(g, w, h); permAnim?.scheduleNext() }
        permAnim = Animator(permCard) { if (perm.on) 33L else -1L }
        val dark = Theme.dark()
        permStatus = NText("", Fonts.mono(15f, bold = true), StatusColors.ON).apply { shadow = true }
        permCard.add(permStatus, LP(MATCH, WRAP))
        permList = vbox()
        permCard.add(permList, LP(MATCH, WRAP, mt = 8, mb = 12))
        btnPermission = outline("", { onPermissionButton() }, dark)
        permCard.add(btnPermission, LP(MATCH, WRAP))
        permTip = NText("", Fonts.sans(12f), dark.body).apply { lineExtra = 3f; shadow = true }
        permCard.add(permTip, LP(MATCH, WRAP, mt = 10))
        v.add(permCard, LP(MATCH, WRAP, mt = 14))

        // 2. Interval
        val c2 = card("2 · CLICK INTERVAL")
        val r2 = hbox(G_CENTER)
        r2.add(outline("−", { val q = Prefs.quarters(); Prefs.setQuarters(q - Config.stepBelow(q)) }).apply {
            tfont = Fonts.mono(24f); padH = 0; minH = 56
        }, LP(56, 56))
        intervalValue = NText("", Fonts.mono(34f, bold = true), t.main).apply {
            align = SwingConstants.CENTER
            wrap = false
            if (!t.isLight) { glowSigma = 4f; glowColor = t.main }
            padding(0, 13, 0, 13) // room for the glow
        }
        r2.add(intervalValue, LP(0, WRAP, weight = 1f, mt = -13, mb = -13))
        r2.add(outline("+", { val q = Prefs.quarters(); Prefs.setQuarters(q + Config.stepAbove(q)) }).apply {
            tfont = Fonts.mono(24f); padH = 0; minH = 56
        }, LP(56, 56))
        c2.add(r2, LP(MATCH, WRAP))
        intervalHint = body("").apply { align = SwingConstants.CENTER }
        c2.add(intervalHint, LP(MATCH, WRAP, mt = 6))
        seek = NSlider(t, Config.SLIDER_MAX_Q - Config.MIN_Q).apply { onUser = { Prefs.setQuarters(it + Config.MIN_Q) } }
        c2.add(seek, LP(MATCH, 32, mt = 10))
        c2.add(body("Slider: 0.25 s – 2 min in 0.25 s steps\nPresets and Custom go up to 60 min", 12f).apply {
            align = SwingConstants.CENTER
        }, LP(MATCH, WRAP, mt = 4))
        c2.add(chipGrid(5, Config.QUICK_INTERVALS + ("Custom" to Config.CUSTOM), intervalChips) { q ->
            if (q == Config.CUSTOM) showCustomIntervalDialog() else Prefs.setQuarters(q)
        }, LP(MATCH, WRAP, mt = 10))
        v.add(c2, LP(MATCH, WRAP, mt = 14))

        // 3. Timer and warning lights
        val c3 = card("3 · AUTO-STOP TIMER")
        switchTimer = NSwitch("Auto-stop timer", t) { Prefs.setTimerOn(it) }
        c3.add(switchTimer, LP(MATCH, WRAP))
        c3.add(body("Starts OFF at 1 min each time the app is opened.", 12f), LP(MATCH, WRAP, mb = 8))
        btnTimer = outline("", { showTimerDialog() })
        c3.add(btnTimer, LP(MATCH, WRAP))
        val warnRow = hbox(G_START)
        val warnLabel = body("Warning lights start flashing with this much time left (they keep flashing while paused):")
        warnRow.add(NBeacon(warnLabel), LP(10, MATCH, mr = 5))
        warnRow.add(warnLabel, LP(0, WRAP, weight = 1f))
        c3.add(warnRow, LP(MATCH, WRAP, mt = 12))
        c3.add(chipGrid(4, Config.WARN_PRESETS.map { Config.durLabel(it) to it } + ("Custom" to Config.CUSTOM), warnChips) { sec ->
            if (sec == Config.CUSTOM) showCustomWarnDialog() else Prefs.put(Config.KEY_WARN_SEC, sec)
        }, LP(MATCH, WRAP, mt = 6))
        v.add(c3, LP(MATCH, WRAP, mt = 14))

        // 4. Start / stop (Windows: a hotkey instead of Volume Down)
        val c4 = card("4 · START / STOP")
        c4.add(body("A hotkey can also start and pause clicking while the floating controls are showing, " +
            "even when another app is in front (the key won't reach other apps then)."), LP(MATCH, WRAP))
        switchHotkey = NSwitch("", t) { Prefs.put(Config.KEY_VOLUME_TOGGLE, it) }.apply { tfont = Fonts.mono(14f) }
        c4.add(switchHotkey, LP(MATCH, WRAP, mt = 10))
        btnHotkey = outline("", { showHotkeyDialog() })
        c4.add(btnHotkey, LP(MATCH, WRAP, mt = 4))
        v.add(c4, LP(MATCH, WRAP, mt = 14))

        // 5. Targets
        val c5 = card("5 · TARGETS")
        targetsInfo = body("")
        c5.add(targetsInfo, LP(MATCH, WRAP))
        c5.add(body("With more than one target, each click goes:"), LP(mt = 12))
        c5.add(chipRow(listOf("In turn: 1, 2, 3…" to Config.MODE_SEQUENCE, "All, in order" to Config.MODE_ALL), modeChips, 44) {
            Prefs.put(Config.KEY_MODE, it)
        }, LP(MATCH, WRAP, mt = 6))
        v.add(c5, LP(MATCH, WRAP, mt = 14))

        // 6. Profiles
        val c6 = card("6 · SAVED PROFILES")
        c6.add(body("Saves the interval, timer length, crosshair size and targets. Loading never starts clicking."), LP(MATCH, WRAP))
        profileList = vbox()
        c6.add(profileList, LP(MATCH, WRAP, mt = 8))
        c6.add(outline("＋ Save current setup as profile", { showSaveProfileDialog() }), LP(MATCH, WRAP, mt = 8))
        v.add(c6, LP(MATCH, WRAP, mt = 14))

        // 7. Appearance
        appearanceCard = card("7 · APPEARANCE")
        val c7 = appearanceCard
        c7.add(body("App appearance"), LP())
        c7.add(chipRow(AppMode.values().map { it.label to it }, appModeChips) { Prefs.put(Config.KEY_APP_MODE, it.ordinal) },
            LP(MATCH, WRAP, mt = 6, mb = 14))
        c7.add(body("Color theme"), LP())
        val sw1 = hbox(G_CENTER)
        val sw2 = hbox(G_CENTER)
        Palette.NEONS.forEachIndexed { i, (name, c) ->
            val s = NSwatch(c).apply { toolTipText = name; onClick = { Prefs.put(Config.KEY_THEME, i) } }
            (if (i < 5) sw1 else sw2).add(s, LP(46, 46).margins(3))
            swatches += s
        }
        c7.add(sw1, LP(gravity = G_CENTER, mt = 6))
        c7.add(sw2, LP(gravity = G_CENTER))
        themeName = NText("", Fonts.mono(14f, bold = true), t.main).apply { align = SwingConstants.CENTER }
        c7.add(themeName, LP(MATCH, WRAP, mt = 4))
        c7.add(body("Floating menu size"), LP(mt = 18))
        val sizes = Config.MENU_SIZES.mapIndexed { i, (label, _) -> label to i }
        c7.add(chipRow(sizes.take(3), menuChips) { Prefs.put(Config.KEY_MENU_SIZE, it) }, LP(MATCH, WRAP, mt = 6))
        c7.add(chipRow(sizes.drop(3), menuChips) { Prefs.put(Config.KEY_MENU_SIZE, it) }, LP(MATCH, WRAP))
        c7.add(body("Floating menu background"), LP(mt = 18))
        c7.add(chipRow(listOf("Black" to false, "Clear" to true), bgChips) { Prefs.put(Config.KEY_PANEL_CLEAR, it) }, LP(MATCH, WRAP, mt = 6))
        switchLock = NSwitch("🔒 Lock floating menu position", t) { Prefs.put(Config.KEY_PANEL_LOCKED, it) }.apply { tfont = Fonts.mono(14f) }
        c7.add(switchLock, LP(MATCH, WRAP, mt = 10))
        val sizeRow = hbox(G_CENTER)
        sizeRow.add(body("Crosshair size"), LP(0, WRAP, weight = 1f))
        targetSizeValue = NText("", Fonts.mono(14f), t.main)
        sizeRow.add(targetSizeValue, LP())
        c7.add(sizeRow, LP(MATCH, WRAP, mt = 18))
        targetSeek = NSlider(t, (Config.TARGET_MAX_DP - Config.TARGET_MIN_DP) / Config.TARGET_STEP_DP).apply {
            onUser = { Prefs.put(Config.KEY_TARGET_DP, Config.TARGET_MIN_DP + it * Config.TARGET_STEP_DP) }
        }
        c7.add(targetSeek, LP(MATCH, 32, mt = 8))
        preview = NCrosshair()
        c7.add(preview, LP(MATCH, 160))
        c7.add(outline("Reset appearance", {
            Prefs.store.edit()
                .remove(Config.KEY_THEME)
                .remove(Config.KEY_MENU_SIZE)
                .remove(Config.KEY_TARGET_DP)
                .remove(Config.KEY_APP_MODE)
                .remove(Config.KEY_PANEL_CLEAR)
                .apply()
        }), LP(MATCH, WRAP))
        v.add(c7, LP(MATCH, WRAP, mt = 14))

        // Floating controls
        btnLaunch = filled("SHOW FLOATING CONTROLS") { onLaunchButton() }
        v.add(btnLaunch, LP(MATCH, WRAP, mt = 20))
        btnCloseOverlay = outline("Close floating controls", { app.controls.hideOverlay(); refreshService() })
        v.add(btnCloseOverlay, LP(MATCH, WRAP, mt = 8))

        // How to use (the floating menu's buttons drawn as the real icons)
        v.add(body("HOW TO USE\n1. Check step 1 (ready on most PCs).\n2. Click SHOW FLOATING CONTROLS.\n" +
            "3. Drag the crosshair to the spot to click.\n4. ▶ starts, ⏸ pauses, ▶ resumes.\n\nFLOATING MENU"), LP(MATCH, WRAP, mt = 18))
        fun help(icon: Icon?, text: String, rotation: Float = 0f, glyph: String? = null) {
            val r = hbox(G_CENTER)
            if (icon != null) r.add(NIcon(icon, t.main).apply { this.rotation = rotation; pad(2) }, LP(22, 22, mr = 8))
            else r.add(NText(glyph ?: "", Fonts.mono(13f, bold = true), t.main).apply { align = SwingConstants.CENTER; wrap = false }, LP(30, WRAP))
            r.add(body(text), LP(0, WRAP, weight = 1f))
            v.add(r, LP(MATCH, WRAP, mt = 2))
        }
        help(Icon.GRIP, "Drag to move · click to lock (🔒)")
        help(Icon.HOME, "Back to this window (clicking carries on)")
        help(Icon.EYE, "Show / hide the crosshair")
        help(Icon.GEAR, "Quick settings")
        help(null, "Remove / add a target (up to 10)", glyph = "− n +")
        help(Icon.COLLAPSE, "Collapse (› expands)", rotation = -90f)
        help(Icon.CLOSE, "Hide the floating controls")
        v.add(body("Click the countdown to change the time, pause or resume. Hold Reset to reset.\n\n" +
            "Crosshairs can only be moved while paused. Everything is saved, except the timer.\n\n" +
            "Clicks are held while this window is in front, and never land on Neon Auto Clicker's own windows. " +
            "The mouse pointer jumps to each crosshair for a moment and comes straight back."), LP(MATCH, WRAP, mt = 10, mb = 12))
        v.add(NText("Version ${App.VERSION}", Fonts.mono(13f), t.body).apply { align = SwingConstants.CENTER }, LP(MATCH, WRAP, mt = 8))
        v.add(NText("Build ${App.BUILD} · Windows", Fonts.mono(11f), t.body).apply { align = SwingConstants.CENTER; alpha = 0.7f },
            LP(MATCH, WRAP, mb = 16))
        return v
    }

    // ---------------- 1. Permission ----------------

    private fun permRow(mark: String, markColor: Int, title: String, detail: String, onClick: (() -> Unit)? = null) {
        val r = hbox(G_START).pad(0, 3, 0, 3)
        r.add(NText(mark, Fonts.mono(15f, bold = true), markColor).apply { minW = 22; shadow = true; wrap = false }, LP(22, WRAP))
        r.add(NText("$title\n$detail", Fonts.sans(13f), StatusColors.TEXT).apply {
            lineExtra = 1.5f
            shadow = true
            this.onClick = onClick
        }, LP(0, WRAP, weight = 1f))
        permList.add(r, LP(MATCH, WRAP))
    }

    /** Permission card and floating-controls buttons, from the real state of this PC. */
    fun refreshService() {
        if (!::permList.isInitialized) return
        val native = Win.nativeInput
        val allOn = app.controls.mouseAvailable
        val admin = Win.isAdmin()
        val on = StatusColors.ON
        val off = StatusColors.OFF
        val warn = StatusColors.WARN

        perm.on = allOn
        permCard.repaint()
        permStatus.text = if (allOn) "✓ ALL SET · READY TO CLICK" else "✗ MOUSE CONTROL UNAVAILABLE"
        permStatus.color = if (allOn) on else off

        permList.removeAll()
        when {
            native -> permRow("✓", on, "Mouse control · required", "ON · Windows mouse input")
            allOn -> permRow("!", warn, "Mouse control · required", "Basic mode (Windows input couldn't be loaded). Clicks still work.")
            else -> permRow("✗", off, "Mouse control · required", "OFF · this PC doesn't allow apps to move the mouse")
        }
        if (admin) {
            permRow("✓", on, "Administrator · optional", "Running as administrator · can click into any app")
        } else {
            permRow("•", warn, "Administrator · optional",
                "Not running as administrator, so apps that run as administrator ignore the clicks. Click to restart as administrator ›") {
                restartAsAdmin()
            }
        }
        val key = Hotkeys.label(Prefs.hotkeyVk())
        when {
            !Prefs.hotkeyOn() -> permRow("•", warn, "Start/pause hotkey · optional", "Off · turn it on in section 4")
            Hotkeys.status == Hotkeys.Status.IN_USE -> permRow("!", warn, "Start/pause hotkey · optional",
                "$key is already used by another app · pick another key in section 4")
            Hotkeys.status == Hotkeys.Status.UNAVAILABLE || !Win.isWindows -> permRow("✗", off, "Start/pause hotkey · optional", "Not available on this PC")
            Hotkeys.status == Hotkeys.Status.ACTIVE -> permRow("✓", on, "Start/pause hotkey · optional", "$key · active now")
            else -> permRow("✓", on, "Start/pause hotkey · optional", "$key · works while the floating controls are showing")
        }
        permRow("✓", on, "Sleep · automatic", "The PC stays awake while clicking")
        permList.revalidate()

        btnPermission.text = if (admin) "Windows mouse settings" else "Restart as administrator"
        permTip.text = when {
            !allOn -> "Windows normally lets apps click without a special permission. Try restarting Neon Auto Clicker, or restart the PC."
            admin -> ""
            else -> "Windows doesn't need a permission for clicking. Only programs started as administrator (some games, " +
                "Task Manager, installers) need Neon Auto Clicker to run as administrator too."
        }
        permTip.isVisible = permTip.text.isNotEmpty()

        val shown = app.controls.isOverlayShown
        btnLaunch.text = if (shown) "RETURN TO FLOATING CONTROLS" else "SHOW FLOATING CONTROLS"
        btnCloseOverlay.isVisible = shown
        btnReturnTop.isVisible = shown
        permCard.revalidate()
        root.revalidate()
        root.repaint()
    }

    private fun onPermissionButton() {
        if (Win.isAdmin()) {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI("ms-settings:mousetouchpad")) } catch (e: Exception) {
                toast("Couldn't open Windows settings")
            }
        } else restartAsAdmin()
    }

    private fun restartAsAdmin() {
        if (!Win.isWindows) { toast("Only available on Windows"); return }
        if (app.controls.isOverlayShown && app.controls.stateLabel != "Start clicking") {
            // a session is going on: say what will happen first
            Dialogs.show(this, t, "Restart as administrator", body("Clicking stops while the app restarts. Your settings are kept.").padding(22, 8, 22, 8),
                "Restart") { app.restartElevated() }
        } else app.restartElevated()
    }

    // ---------------- Floating controls ----------------

    /**
     * Controls already showing: step back to them exactly as they are (nothing is hidden, reset or
     * rebuilt). Not showing: show them, restored from the saved settings. Then this window steps aside.
     */
    private fun onLaunchButton() {
        if (!app.controls.mouseAvailable) {
            toast("Mouse control isn't available on this PC", long = true)
            return
        }
        if (!app.controls.isOverlayShown) app.controls.showOverlay()
        extendedState = extendedState or Frame.ICONIFIED
        refreshService()
    }

    fun showSection(s: Section) {
        if (extendedState and Frame.ICONIFIED != 0) extendedState = extendedState and Frame.ICONIFIED.inv()
        isVisible = true
        toFront()
        requestFocus()
        SwingUtilities.invokeLater {
            val sb = scroll?.verticalScrollBar ?: return@invokeLater
            sb.value = when (s) {
                Section.APPEARANCE -> SwingUtilities.convertPoint(appearanceCard, 0, 0, scroll?.viewport?.view).y - 8
                Section.TOP -> 0
            }
        }
    }

    // ---------------- 2. Interval ----------------

    private fun refreshInterval() {
        if (!::intervalValue.isInitialized) return
        val q = Prefs.quarters()
        val label = Config.formatInterval(q)
        intervalValue.text = label
        intervalValue.tfont = Fonts.mono(if (label.length > 9) 22f else 34f, bold = true)
        seek.progress = minOf(q, Config.SLIDER_MAX_Q) - Config.MIN_Q
        val isPreset = Config.QUICK_INTERVALS.any { it.second == q }
        intervalChips.forEach { (b, cq) -> b.select(if (cq == Config.CUSTOM) !isPreset else cq == q) }
        intervalHint.text = Config.rateHint(q).replace("taps", "clicks").replace("1 tap ", "1 click ")
    }

    private fun showCustomIntervalDialog() {
        val q = Prefs.quarters()
        val m = NPicker(59, q / 240, t)
        val s = NPicker(59, (q % 240) / 4, t)
        val f = NPicker(3, q % 4, t).apply { displayed = arrayOf(".00", ".25", ".50", ".75") }
        Dialogs.show(this, t, "Custom interval", Dialogs.pickerRow(t, m to "min", s to "", f to "s"), "Set") {
            val total = m.value * 240 + s.value * 4 + f.value
            if (total <= 0) toast("Pick an interval longer than 0") else Prefs.setQuarters(total)
        }
    }

    // ---------------- 3. Timer and warning lights ----------------

    private fun refreshTimer() {
        if (!::switchTimer.isInitialized) return
        val on = Prefs.timerOn()
        switchTimer.checked = on
        switchTimer.text = if (on) "Auto-stop timer · ON" else "Auto-stop timer · OFF"
        btnTimer.text = "Length: ${Config.durLabel(Prefs.timerDuration())}"
        btnTimer.alpha = if (on) 1f else 0.6f // can be set before switching on
    }

    private fun listDialog(title: String, items: List<Pair<String, Int>>, selected: (Int) -> Boolean, onPick: (Int) -> Unit) {
        val list = vbox().pad(8, 0, 8, 8)
        var dialog: javax.swing.JDialog? = null
        items.forEach { (label, value) ->
            val sel = selected(value)
            list.add(object : NText((if (sel) "●  " else "○  ") + label, Fonts.sans(16f, bold = sel), if (sel) t.main else t.body) {
                override fun paintView(g: Graphics2D) {
                    if (hover) { g.color = col(t.alpha(0x22)); g.fillRoundRect(0, 0, width, height, 12, 12) }
                    super.paintView(g)
                }
            }.apply {
                padding(14, 11, 14, 11)
                onClick = { dialog?.dispose(); onPick(value) }
            }, LP(MATCH, WRAP))
        }
        val sp = neonScroll(list, t).apply { preferredSize = Dimension(340, minOf(460, list.lin.heightFor(list, 340))) }
        list.tracksWidth = true
        val holder = vbox().apply { add(sp, LP(MATCH, sp.preferredSize.height)) }
        val d = Dialogs.neon(this, t, title, holder, null, null)
        dialog = d
        d.isVisible = true
    }

    private fun showTimerDialog() {
        val dur = Prefs.timerDuration()
        val isPreset = Config.TIMER_PRESETS.any { it.second == dur }
        listDialog("Timer length", Config.TIMER_PRESETS, { v -> if (v == Config.CUSTOM) !isPreset else v == dur }) { v ->
            if (v == Config.CUSTOM) showCustomTimerDialog() else Prefs.setTimerDuration(v)
        }
    }

    private fun showCustomTimerDialog() {
        val cur = Prefs.timerDuration()
        val h = NPicker(23, cur / 3600, t)
        val m = NPicker(59, (cur % 3600) / 60, t)
        val s = NPicker(59, cur % 60, t)
        Dialogs.show(this, t, "Custom timer", Dialogs.pickerRow(t, h to "h", m to "m", s to "s"), "Set") {
            val total = h.value * 3600 + m.value * 60 + s.value
            if (total <= 0) toast("Pick a time longer than 0") else Prefs.setTimerDuration(total)
        }
    }

    private fun refreshWarn() {
        if (warnChips.isEmpty()) return
        val cur = Prefs.warnSeconds()
        val isPreset = cur in Config.WARN_PRESETS
        warnChips.forEach { (b, sec) ->
            b.select(if (sec == Config.CUSTOM) !isPreset else sec == cur)
            if (sec == Config.CUSTOM) b.text = if (isPreset) "Custom" else Config.durLabel(cur)
        }
    }

    private fun showCustomWarnDialog() {
        val cur = Prefs.warnSeconds()
        val m = NPicker(59, cur / 60, t)
        val s = NPicker(59, cur % 60, t)
        Dialogs.show(this, t, "Warning lights from", Dialogs.pickerRow(t, m to "min", s to "s"), "Set") {
            val total = m.value * 60 + s.value
            if (total <= 0) toast("Pick a time longer than 0") else Prefs.put(Config.KEY_WARN_SEC, total)
        }
    }

    // ---------------- 4. Hotkey ----------------

    private fun refreshHotkey() {
        if (!::switchHotkey.isInitialized) return
        val key = Hotkeys.label(Prefs.hotkeyVk())
        switchHotkey.checked = Prefs.hotkeyOn()
        switchHotkey.text = "$key = start/pause"
        btnHotkey.text = "Hotkey: $key · change"
        btnHotkey.alpha = if (Prefs.hotkeyOn()) 1f else 0.6f
    }

    private fun showHotkeyDialog() {
        val cur = Prefs.hotkeyVk()
        listDialog("Start/pause hotkey", Hotkeys.KEYS, { it == cur }) { vk -> Prefs.put(Prefs.KEY_HOTKEY_VK, vk) }
    }

    // ---------------- 5. Targets ----------------

    private fun refreshTargets() {
        if (!::targetsInfo.isInitialized) return
        val n = Prefs.targetCount()
        targetsInfo.text = if (n == 1) "1 target placed." else "$n targets placed, numbered on screen."
        val mode = Prefs.mode()
        modeChips.forEach { (b, m) -> b.select(m == mode) }
    }

    // ---------------- 6. Profiles ----------------

    private fun refreshProfiles() {
        if (!::profileList.isInitialized) return
        profileList.removeAll()
        val list = Profiles.load()
        if (list.isEmpty()) {
            profileList.add(NText("No profiles yet.", Fonts.sans(13f), t.dim).padding(0, 4, 0, 4), LP(MATCH, WRAP))
        }
        list.forEach { p ->
            val r = hbox(G_CENTER).pad(12, 10, 8, 10)
            r.bg = { g, w, h ->
                val rr = RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 32f, 32f)
                g.color = col(t.surface); g.fill(rr)
                g.stroke = BasicStroke(1f); g.color = col(t.cardEdge); g.draw(rr)
            }
            val texts = vbox()
            texts.add(NText(p.name, Fonts.mono(15f, bold = true), t.main), LP(MATCH, WRAP))
            texts.add(NText(p.summary(), Fonts.sans(12f), t.body), LP(MATCH, WRAP))
            r.add(texts, LP(0, WRAP, weight = 1f))
            r.add(chip("Load", t, true, 13f) { loadProfile(p) }.apply { radius = 10f }, LP(64, 38, ml = 6))
            r.add(chip("✕", t, false, 13f) { confirmDeleteProfile(p) }.apply { radius = 10f }, LP(38, 38, ml = 6))
            profileList.add(r, LP(MATCH, WRAP, mt = 6))
        }
        profileList.revalidate()
        profileList.repaint()
    }

    private fun loadProfile(p: Profile) {
        Profiles.apply(p)
        toast("Loaded “${p.name}”")
    }

    private fun confirmDeleteProfile(p: Profile) {
        Dialogs.show(this, t, "Delete profile", NText("Delete “${p.name}”?", Fonts.sans(15f), t.body).padding(22, 8, 22, 8),
            "Delete") { Profiles.delete(p.name) }
    }

    private fun showSaveProfileDialog() {
        val existing = Profiles.load()
        val input = NField(t, "Profile ${existing.size + 1}")
        val box = vbox().pad(22, 4, 22, 0).apply { add(input, LP(MATCH, 42)) }
        val d = Dialogs.neon(this, t, "Save profile", box, "Save") {
            val name = input.text.trim().ifEmpty { "Profile ${existing.size + 1}" }
            val replacing = existing.any { it.name.equals(name, ignoreCase = true) }
            Profiles.saveOrReplace(Profiles.current(name))
            if (replacing) toast("Updated “$name”") // a new one simply appears in the list
        }
        d.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) { input.requestFocusInWindow(); input.selectAll() }
        })
        d.isVisible = true
    }

    // ---------------- 7. Appearance ----------------

    private fun refreshAppearance() {
        if (!::themeName.isInitialized) return
        val cur = Prefs.themeIndex()
        val ring = if (t.isLight) hex("#22272C") else com.neon.autoclicker.WHITE
        swatches.forEachIndexed { i, s ->
            s.ring = ring
            s.selected = i == cur
        }
        themeName.text = t.name
        val mode = Prefs.appMode()
        appModeChips.forEach { (b, m) -> b.select(m == mode) }
        val size = Prefs.menuSizeIndex()
        menuChips.forEach { (b, i) -> b.select(i == size) }
        val clear = Prefs.panelClear()
        bgChips.forEach { (b, c) -> b.select(c == clear) }
        switchLock.checked = Prefs.panelLocked()
        refreshTargetSize()
    }

    private fun refreshTargetSize() {
        if (!::targetSeek.isInitialized) return
        val d = Prefs.targetDp()
        targetSeek.progress = (d - Config.TARGET_MIN_DP) / Config.TARGET_STEP_DP
        targetSizeValue.text = if (d == Config.TARGET_DEFAULT_DP) "$d px · default" else "$d px"
        preview.color = Theme.dark().neon
        preview.ringPx = d
    }

    // ---------------- Helpers ----------------

    fun isActiveApp(): Boolean {
        val aw = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow ?: return false
        var w: Window? = aw
        while (w != null) {
            if (w === this) return true
            w = w.owner
        }
        return false
    }

    /** Is [p] on this window or one of its dialogs (while showing)? */
    fun covers(p: Point): Boolean {
        if (!isShowing || extendedState and Frame.ICONIFIED != 0) return false
        if (bounds.contains(p)) return true
        return ownedWindows.any { it.isShowing && it.bounds.contains(p) }
    }

    fun toast(msg: String, long: Boolean = false) = Toast.show(msg, long, near = Point(x + width / 2, y + height / 2))
}
