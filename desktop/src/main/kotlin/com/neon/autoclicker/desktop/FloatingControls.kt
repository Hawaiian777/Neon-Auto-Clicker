package com.neon.autoclicker.desktop

import com.neon.autoclicker.BLACK
import com.neon.autoclicker.ClickSession
import com.neon.autoclicker.Config
import com.neon.autoclicker.NeonColors
import com.neon.autoclicker.Palette
import com.neon.autoclicker.Pt
import com.neon.autoclicker.WHITE
import java.awt.AWTEvent
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Area
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.locks.LockSupport
import javax.swing.JScrollPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The floating menu card (port of LivePanel): draws its own Black or Clear card in the accent
 * color, and the Airbus lights when the warning threshold is reached, without changing the
 * card's background or colors. [ROOM] around the card leaves space for the edge lights.
 */
class LivePanel : Lin(false, G_CENTER) {
    companion object { const val ROOM = 9 }

    private val lights = Lights(1f)
    private val anim = Animator(this) { lights.nextFrameDelay(System.currentTimeMillis()) }

    var themeColor = WHITE
    var cornerRadius = 20f
    var clear = false
    var warning = false
        set(value) {
            if (field == value) return
            field = value
            if (value) lights.refreshMotionSetting()
            repaint()
        }

    fun setInnerPadding(l: Int, t: Int, r: Int, b: Int) = pad(l + ROOM, t + ROOM, r + ROOM, b + ROOM)

    private fun card() = RoundRectangle2D.Float(ROOM.toFloat(), ROOM.toFloat(), (width - 2 * ROOM).toFloat(), (height - 2 * ROOM).toFloat(), 0f, 0f)

    override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).smooth()
        val c = card()
        val r = minOf(cornerRadius, c.height / 2f)
        val shape = RoundRectangle2D.Float(c.x, c.y, c.width, c.height, 2 * r, 2 * r)
        if (!clear) {
            g2.color = Color.BLACK
            g2.fill(shape)
            val sw = 1f
            val h = sw / 2f
            g2.stroke = BasicStroke(sw)
            g2.color = col(themeColor)
            g2.draw(RoundRectangle2D.Float(c.x + h, c.y + h, c.width - sw, c.height - sw, 2 * (r - h), 2 * (r - h)))
        } else {
            // invisible, but keeps the whole card clickable / draggable on Windows
            g2.color = Color(0, 0, 0, 1)
            g2.fill(shape)
        }
        if (warning) lights.drawCenter(g2, c.x, c.y, c.width, c.height, System.currentTimeMillis())
        g2.dispose()
    }

    override fun paint(g: Graphics) {
        super.paint(g)
        if (warning) {
            val g2 = (g.create() as Graphics2D).smooth()
            val c = card()
            lights.drawOver(g2, c.x, c.y, c.width, c.height, System.currentTimeMillis())
            g2.dispose()
            anim.scheduleNext()
        }
    }
}

/** One crosshair window. */
private class TargetWindow : OverlayWindow() {
    val view = object : NView() {
        override fun paintView(g: Graphics2D) {
            if (hidden) return
            Crosshair.paint(g, width, height, ringPx, color, active, label, dot = !active || Win.nativeInput)
        }
    }
    var ringPx = 32
    var color = WHITE
    var active = false
        set(value) { if (field != value) { field = value; view.repaint() } }
    var label: String? = null
    var hidden = false
        set(value) { if (field != value) { field = value; view.repaint() } }

    init {
        contentPane = view
    }

    /** Center in Java screen coordinates. */
    fun center() = Point(x + width / 2, y + height / 2)

    /** Center in physical pixels (what Windows' mouse uses), or null off Windows. */
    fun physicalCenter(): IntArray? = Win.physicalRect(this)?.let { r -> intArrayOf((r[0] + r[2]) / 2, (r[1] + r[3]) / 2) }
}

/** A pop-up next to the menu (Settings or the enlarged timer). */
private class Pop(val win: OverlayWindow, val root: Lin)

/** A scroll area that grows with its content up to [maxH] (Android MaxHeightScrollView). */
private class MaxScroll(val view: Lin, var maxH: Int, t: NeonColors) : Lin(true) {
    val scroll: JScrollPane = neonScroll(view, t)

    init {
        view.tracksWidth = true
        add(scroll, LP(MATCH, MATCH))
    }

    override fun getPreferredSize(): Dimension {
        val w = view.lin.prefWidth(view)
        return Dimension(w, minOf(maxH, view.lin.heightFor(view, w)))
    }

    override fun doLayout() {
        scroll.setBounds(0, 0, width, height)
        scroll.doLayout()
    }
}

class FloatingControls(private val app: App) {

    private val targets = mutableListOf<TargetWindow>()
    /** Target centers as fractions of the desktop, so they follow resolution changes. */
    private var targetsN = mutableListOf<Pt>()
    /** Panel top-left as fractions of the desktop. */
    private var panelN = Pt(0f, 0.33f)

    private var panelWin: OverlayWindow? = null
    private var panel: LivePanel? = null
    private var drag: DragSupport? = null
    private var playBtn: NIcon? = null
    private var eyeBtn: NIcon? = null
    private var statusText: NText? = null
    private var timerView: NCountdown? = null
    private var targetCountText: NText? = null

    // Pop-ups
    private var settingsPop: Pop? = null
    private var timerPop: Pop? = null
    private var settingsClosedAt = 0L
    private var timerClosedAt = 0L
    private var popFreqText: NText? = null
    private var popTimerText: NText? = null
    private var popWarnText: NText? = null
    private var settingsScroll: MaxScroll? = null
    private var warnCustomOpen = false
    private var tpBig: NCountdown? = null
    private var tpLabel: NText? = null
    private var tpSeek: NSlider? = null
    private var tpAction: NButton? = null
    private var tpReset: NButton? = null
    private var lockToastAt = 0L

    // Session
    private val session = ClickSession { System.nanoTime() / 1_000_000L }
    private val state get() = session.state
    private val running get() = session.running
    private var held = false
    private var blocked = false
    private var taps = 0
    private var seqIndex = 0

    // Menu options
    private var collapsed = false
    private var targetHidden = false
    private var locked = false
    private var clearBg = false

    val isOverlayShown: Boolean get() = panelWin != null

    // Settings read on every click / tick, kept here instead of re-reading them each time.
    @Volatile private var intervalMs = 1000L
    private var warnSec = Config.WARN_DEFAULT
    private var multiMode = Config.MODE_SEQUENCE

    private val mouse = Mouse()
    val mouseAvailable get() = mouse.available
    @Volatile private var syntheticUntil = 0L
    private var engine: Thread? = null
    private val uiTimer = Timer(1000) { uiLoop() }.apply { isRepeats = false }
    private val outsideTimer = Timer(40) { watchOutside() }
    private var buttonWasDown = false
    private var lastVirtual: Rectangle? = null
    private val screenWatch = Timer(2000) { checkScreens() }

    private fun loadCachedSettings() {
        intervalMs = Prefs.intervalMs()
        warnSec = Prefs.warnSeconds()
        multiMode = Prefs.mode()
    }

    init {
        loadCachedSettings()
        Prefs.store.addListener { key -> onPrefChanged(key) }
        // Presses in any of our own windows close an open pop-up (like Android's outside touch).
        Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
            if (e.id == MouseEvent.MOUSE_PRESSED) {
                val w = SwingUtilities.getWindowAncestor((e as MouseEvent).component) ?: (e.component as? Window)
                closePopsUnless(w)
            }
        }, AWTEvent.MOUSE_EVENT_MASK)
    }

    private fun neon() = Theme.dark()
    private fun later(r: () -> Unit) = SwingUtilities.invokeLater(r)

    // ---------------- Settings changes (from here, the Settings pop-up or the main window) ----------------

    private fun onPrefChanged(key: String) {
        when (key) {
            Config.KEY_INTERVAL_Q, Config.KEY_WARN_SEC, Config.KEY_MODE -> loadCachedSettings()
        }
        if (key == Config.KEY_VOLUME_TOGGLE || key == Prefs.KEY_HOTKEY_VK) updateHotkey()
        if (panelWin == null) return
        when (key) {
            Config.KEY_THEME -> {
                val c = neon().main
                targets.forEach { it.color = c; it.view.repaint() }
                rebuildPanel()
                rebuildPops()
            }
            Config.KEY_MENU_SIZE -> { rebuildPanel(); rebuildPops() }
            Config.KEY_PANEL_CLEAR -> { clearBg = Prefs.panelClear(); rebuildPanel(); rebuildPops() }
            Config.KEY_PANEL_LOCKED -> { locked = Prefs.panelLocked(); rebuildPanel(); rebuildPops() }
            Config.KEY_TARGET_DP -> relayoutTargets()
            Config.KEY_INTERVAL_Q -> { refreshPopValues(); refreshPanel() }
            Config.KEY_TIMER_ON, Config.KEY_TIMER_DUR -> {
                applyTimerToSession(key)
                if (key == Config.KEY_TIMER_ON) settingsPop?.let { fillSettingsPop(it) } else refreshPopValues()
                refreshPanel()
            }
            Config.KEY_WARN_SEC -> {
                val custom = Prefs.warnSeconds() !in Config.WARN_PRESETS
                if (custom) warnCustomOpen = true
                val pop = settingsPop
                if (pop != null && (popWarnText == null || !custom)) fillSettingsPop(pop) else refreshPopValues()
                refreshPanel()
            }
            Config.KEY_RELOAD -> { // a profile was loaded: new setup, fresh session
                reset()
                targetsN = loadTargetsN()
                buildTargets()
                refreshPanel()
            }
        }
    }

    // ---------------- Overlay ----------------

    fun showOverlay() {
        if (panelWin != null) return
        collapsed = Prefs.store.getBoolean(Config.KEY_COLLAPSED, false)
        targetHidden = Prefs.store.getBoolean(Config.KEY_TARGET_HIDDEN, false)
        locked = Prefs.panelLocked()
        clearBg = Prefs.panelClear()
        session.reset()
        resetCounters()

        targetsN = loadTargetsN()
        buildTargets()

        panelN = loadPanelN()
        val w = OverlayWindow()
        val p = LivePanel()
        w.contentPane = p
        panelWin = w
        panel = p
        drag = DragSupport(w, { !locked }, onDragStart = { closePops() }, onDragEnd = { savePanelPosition() },
            onBlockedDrag = { lockedHint() }, clamp = { pt -> clampPoint(pt, w) })
        populatePanel()
        w.pack()
        placePanel()
        w.isVisible = true
        clampPanel()
        lastVirtual = Screens.virtual()
        screenWatch.start()
        updateHotkey()
        app.onOverlayChanged()
    }

    fun hideOverlay() {
        if (panelWin == null) return
        closePops()
        reset()
        targets.forEach { it.dispose() }
        targets.clear()
        panelWin?.dispose()
        panelWin = null
        panel = null
        drag = null
        playBtn = null
        eyeBtn = null
        statusText = null
        timerView = null
        targetCountText = null
        screenWatch.stop()
        updateHotkey()
        app.onOverlayChanged()
    }

    /** Hotkey on only while the controls are showing and the option is on. */
    private fun updateHotkey() {
        if (panelWin != null && Prefs.hotkeyOn()) Hotkeys.start(Prefs.hotkeyVk()) { if (panelWin != null) toggle() }
        else Hotkeys.stop()
    }

    // ---------------- Targets ----------------

    private fun defaultTarget(): Pt {
        val p = Screens.primary()
        return Screens.toFraction(p.x + p.width / 2, p.y + p.height / 2)
    }

    private fun loadTargetsN(): MutableList<Pt> =
        Prefs.loadTargets() ?: mutableListOf(defaultTarget()).also { Prefs.saveTargets(it) }

    private fun buildTargets() {
        closePops()
        targets.forEach { it.dispose() }
        targets.clear()
        val t = neon()
        val ring = Prefs.targetDp()
        val win = Crosshair.windowSizeFor(ring)
        val multi = targetsN.size > 1
        targetsN.forEachIndexed { i, n ->
            val tw = TargetWindow()
            tw.color = t.main
            tw.ringPx = ring
            tw.label = if (multi) "${i + 1}" else null
            tw.setSize(win, win)
            positionTarget(tw, n)
            DragSupport(tw, { !running }, onDragEnd = { onTargetMoved(tw) }).attach(tw.view)
            tw.view.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR)
            tw.isVisible = true
            targets += tw
        }
        applyTargetState()
        panelWin?.toFront() // keep the menu above the crosshairs
        settingsPop?.win?.toFront()
        timerPop?.win?.toFront()
    }

    private fun positionTarget(tw: TargetWindow, n: Pt) {
        val c = Screens.fromFraction(n)
        tw.setLocation(c.x - tw.width / 2, c.y - tw.height / 2)
    }

    private fun onTargetMoved(tw: TargetWindow) {
        val i = targets.indexOf(tw)
        if (i < 0 || i >= targetsN.size) return
        val c = tw.center()
        targetsN[i] = Screens.toFraction(c.x, c.y)
        Prefs.saveTargets(targetsN)
    }

    private fun relayoutTargets() {
        val ring = Prefs.targetDp()
        val win = Crosshair.windowSizeFor(ring)
        targets.forEachIndexed { i, tw ->
            tw.ringPx = ring
            tw.setSize(win, win)
            positionTarget(tw, targetsN.getOrElse(i) { defaultTarget() })
            tw.view.repaint()
        }
        applyTargetState()
    }

    private fun addTarget() {
        if (running) { toast("Pause clicking first"); return }
        if (targetsN.size >= Config.MAX_TARGETS) { toast("Up to ${Config.MAX_TARGETS} targets"); return }
        // next to the last one, on the same screen (10% across, 6% down, wrapping like Android)
        val last = targetsN.lastOrNull() ?: defaultTarget()
        val lp = Screens.fromFraction(last)
        val s = Screens.at(lp)
        var nx = (lp.x - s.x) / s.width.toFloat() + 0.1f
        var ny = (lp.y - s.y) / s.height.toFloat() + 0.06f
        if (nx > 0.9f) nx = 0.15f
        if (ny > 0.9f) ny = 0.15f
        targetsN.add(Screens.toFraction(s.x + (nx * s.width).roundToInt(), s.y + (ny * s.height).roundToInt()))
        Prefs.saveTargets(targetsN)
        if (targetHidden) setTargetsHidden(false) // show them so the new one can be placed
        buildTargets()
        refreshPanel()
    }

    private fun removeTarget() {
        if (running) { toast("Pause clicking first"); return }
        if (targetsN.size <= 1) { toast("At least one target is needed"); return }
        targetsN.removeAt(targetsN.lastIndex)
        Prefs.saveTargets(targetsN)
        buildTargets()
        refreshPanel()
    }

    /** Visible + draggable only when shown and idle. Hidden or running = clicks pass straight through. */
    private fun applyTargetState() {
        val through = running || targetHidden
        targets.forEach { tw ->
            tw.hidden = targetHidden
            tw.active = running
            tw.clickThrough = through
            if (!Win.nativeInput) fallbackShape(tw, through)
        }
    }

    /** Without Windows' click-through style: cut the center out so clicks reach the app below. */
    private fun fallbackShape(tw: TargetWindow, through: Boolean) {
        try {
            if (!through) { tw.shape = null; return }
            val a = Area(Rectangle(0, 0, tw.width, tw.height))
            val hole = 12.0
            if (targetHidden) tw.shape = Rectangle(0, 0, 1, 1)
            else {
                a.subtract(Area(Ellipse2D.Double(tw.width / 2.0 - hole / 2, tw.height / 2.0 - hole / 2, hole, hole)))
                tw.shape = a
            }
        } catch (t: Throwable) { }
    }

    private fun setTargetsHidden(hidden: Boolean) {
        targetHidden = hidden
        Prefs.store.edit().putBoolean(Config.KEY_TARGET_HIDDEN, hidden).apply()
        applyTargetState() // positions and sizes are kept
        refreshPanel()
    }

    // ---------------- Panel ----------------

    /** Saved menu position, or the left edge a third of the way down the main screen the first time. */
    private fun loadPanelN(): Pt {
        val fx = Prefs.store.getFloat(Config.KEY_PANEL_FX, -1f)
        val fy = Prefs.store.getFloat(Config.KEY_PANEL_FY, -1f)
        if (fx >= 0f && fy >= 0f) return Pt(fx, fy)
        val p = Screens.primary()
        return Screens.toFraction(p.x + 8, p.y + p.height / 3)
    }

    private fun placePanel() {
        val w = panelWin ?: return
        w.location = Screens.fromFraction(panelN)
    }

    private fun savePanelPosition() {
        val w = panelWin ?: return
        panelN = Screens.toFraction(w.x, w.y)
        Prefs.store.edit().putFloat(Config.KEY_PANEL_FX, panelN.x).putFloat(Config.KEY_PANEL_FY, panelN.y).apply()
    }

    /** Keeps at least half of the menu on the screen it's on. */
    private fun clampPoint(p: Point, w: Window): Point {
        val s = Screens.at(Point(p.x + w.width / 2, p.y + w.height / 2))
        val x = p.x.coerceIn(s.x - w.width / 2, max(s.x - w.width / 2, s.x + s.width - w.width / 2))
        val y = p.y.coerceIn(s.y - w.height / 2, max(s.y - w.height / 2, s.y + s.height - w.height / 2))
        return Point(x, y)
    }

    private fun clampPanel() {
        val w = panelWin ?: return
        w.location = clampPoint(w.location, w)
    }

    /** Resolution or monitor change: move everything to the same relative spots. */
    private fun checkScreens() {
        val v = Screens.virtual()
        if (v == lastVirtual) return
        lastVirtual = v
        closePops()
        relayoutTargets()
        placePanel()
        clampPanel()
    }

    private fun sdp(v: Float, s: Float) = max(1, (v * s).roundToInt())

    /** Builds the panel (wide/horizontal) for the current color, size, background and collapsed state. */
    private fun populatePanel() {
        val p = panel ?: return
        val t = neon()
        val s = Prefs.menuScale()
        fun sdp(v: Float) = sdp(v, s)
        val thin = maxOf(1f, 0.75f * s)

        p.removeAll()
        playShowsPause = null
        p.themeColor = t.main
        p.clear = clearBg
        p.cornerRadius = sdp(if (collapsed) 28f else 20f).toFloat()

        val play = NIcon(Icon.PLAY, t.onMain).apply { onClick = { toggle() }; pressDim = true }
        playBtn = play

        fun countdown(size: Float) = NCountdown(size * s, t.main).apply {
            onClick = { later { toggleTimerPop() } }
            pressDim = true
        }

        fun lockIcon() = NIcon(if (locked) Icon.LOCK else Icon.GRIP, if (locked) t.main else t.dim).apply {
            halo = clearBg
            padX = sdp(2f); padY = sdp(4f)
            toolTipText = if (locked) "Position locked" else "Drag to move · click to lock"
            onClick = { later { toggleLock() } }
            pressDim = true
        }

        if (collapsed) {
            // Collapsed: Start/Pause, the countdown (during a timed session) and a small expand arrow.
            p.setInnerPadding(sdp(6f), sdp(6f), sdp(4f), sdp(6f))
            play.pad(sdp(11f))
            p.add(play, LP(sdp(44f), sdp(44f)))
            val timer = countdown(11f)
            timerView = timer
            p.add(timer, LP(ml = sdp(5f)))
            if (locked) p.add(lockIcon(), LP(sdp(16f), sdp(24f), ml = sdp(2f)))
            val expand = NIcon(Icon.EXPAND, t.main).apply {
                rotation = -90f // points right: "open"
                halo = clearBg
                pad(sdp(3f))
                onClick = { later { setCollapsed(false) } }
                pressDim = true
            }
            p.add(expand, LP(sdp(26f), sdp(26f), ml = sdp(2f)))
            statusText = null
            eyeBtn = null
            targetCountText = null
        } else {
            p.setInnerPadding(sdp(4f), sdp(8f), sdp(6f), sdp(8f))

            // Drag grip, or a lock when the position is locked (click to switch)
            p.add(lockIcon(), LP(sdp(18f), sdp(30f), mr = sdp(2f)))

            // Start / Pause / Resume
            play.pad(sdp(15f))
            p.add(play, LP(sdp(56f), sdp(56f)))

            // Clicks + countdown (click the countdown to enlarge it)
            val info = vbox(G_CENTER)
            val status = NText("", Fonts.mono(11f * s), t.main).apply {
                align = SwingConstants.CENTER
                minW = sdp(54f)
                shadow = clearBg
            }
            statusText = status
            info.add(status, LP())
            val timer = countdown(12f)
            timerView = timer
            info.add(timer, LP(mt = sdp(2f)))
            p.add(info, LP(ml = sdp(6f)))

            // Small round buttons
            fun small(k: Icon, action: () -> Unit) = NIcon(k, t.main).apply {
                circle(BLACK, t.dim, thin)
                pad(sdp(6.5f))
                onClick = { later(action) }
                pressDim = true
            }
            val m = sdp(2.5f)
            fun cell(w: Float = 28f) = LP(sdp(w), sdp(28f)).margins(m)

            val eye = small(if (targetHidden) Icon.EYE_OFF else Icon.EYE) { setTargetsHidden(!targetHidden) }
            eye.toolTipText = "Show / hide the crosshair"
            eyeBtn = eye
            val row1 = hbox(G_CENTER)
            row1.add(small(Icon.HOME) { openApp(settings = false) }.apply { toolTipText = "Open Neon Auto Clicker" }, cell())
            row1.add(eye, cell())
            row1.add(small(Icon.GEAR) { toggleSettingsPop() }.apply { toolTipText = "Quick settings" }, cell())

            // − count + : one balanced pill, minus on the left, plus on the right
            val pill = hbox(G_CENTER).apply {
                val r = sdp(14f).toFloat()
                bg = { g, w, h ->
                    val sh = RoundRectangle2D.Float(thin / 2f, thin / 2f, w - thin, h - thin, 2 * r, 2 * r)
                    g.color = Color.BLACK; g.fill(sh)
                    g.stroke = BasicStroke(thin); g.color = col(t.dim); g.draw(sh)
                }
            }
            fun pillBtn(k: Icon, desc: String, action: () -> Unit) = NIcon(k, t.main).apply {
                toolTipText = desc
                pad(sdp(7f))
                onClick = { later(action) }
                pressDim = true
            }
            pill.add(pillBtn(Icon.MINUS, "Remove target") { removeTarget() }, LP(sdp(27f), sdp(28f)))
            val count = NText("", Fonts.mono(11f * s, bold = true), t.main).apply {
                align = SwingConstants.CENTER
                minW = sdp(14f)
            }
            targetCountText = count
            pill.add(count, LP(WRAP, WRAP))
            pill.add(pillBtn(Icon.PLUS, "Add target") { addTarget() }, LP(sdp(27f), sdp(28f)))

            val collapse = small(Icon.COLLAPSE) { setCollapsed(true) }.apply {
                rotation = -90f // points left: "fold away"
                toolTipText = "Collapse"
            }
            val row2 = hbox(G_CENTER)
            row2.add(pill, LP(WRAP, sdp(28f)).margins(m))
            row2.add(collapse, cell())
            val grid = vbox(G_CENTER)
            grid.add(row1, LP())
            grid.add(row2, LP())
            p.add(grid, LP(ml = sdp(6f)))

            // Close, top-right
            val close = NIcon(Icon.CLOSE, t.dim).apply {
                halo = clearBg
                pad(sdp(4f))
                toolTipText = "Hide the floating controls"
                onClick = { later { hideOverlay() } }
                pressDim = true
            }
            p.add(close, LP(sdp(22f), sdp(22f), ml = sdp(2f), gravity = G_START))
        }
        drag?.attach(p)
        refreshPanel()
    }

    private fun rebuildPanel() {
        val w = panelWin ?: return
        populatePanel()
        w.pack()
        w.validate()
        clampPanel()
        settingsPop?.let { placePop(it) }
        timerPop?.let { placePop(it) }
        w.repaint()
    }

    private fun setCollapsed(c: Boolean) {
        collapsed = c
        Prefs.store.edit().putBoolean(Config.KEY_COLLAPSED, c).apply()
        closePops()
        rebuildPanel() // the clicking keeps running
    }

    /** The grip turns into a lock icon (and back), so no message is needed. */
    private fun toggleLock() = Prefs.put(Config.KEY_PANEL_LOCKED, !locked)

    private fun lockedHint() {
        val now = System.currentTimeMillis()
        if (now - lockToastAt > 3000) {
            lockToastAt = now
            toast("Menu is locked. Click the lock to unlock.")
        }
    }

    /** Home: open the app. Clicking and the timer carry on; clicks are held while the app is in front. */
    private fun openApp(settings: Boolean) {
        closePops()
        app.showMain(if (settings) MainWindow.Section.APPEARANCE else MainWindow.Section.TOP)
    }

    // ---------------- Pop-ups (shared) ----------------

    private fun popScale() = Prefs.menuScale().coerceIn(0.85f, 1.3f)

    private fun openPop(fill: (Pop) -> Unit): Pop {
        val w = OverlayWindow()
        val root = vbox()
        w.contentPane = root
        val pop = Pop(w, root)
        fill(pop)
        w.pack()
        placePop(pop)
        w.isVisible = true
        outsideTimer.start()
        return pop
    }

    /** Opens below the menu, or above it when there's no room. */
    private fun placePop(pop: Pop) {
        val p = panelWin ?: return
        pop.win.pack()
        val area = Screens.workArea(Point(p.x + p.width / 2, p.y + p.height / 2))
        val w = pop.win.width
        val h = pop.win.height
        val room = LivePanel.ROOM
        val gap = 2
        var y = p.y + p.height - room + gap
        if (y + h > area.y + area.height) y = p.y + room - h - gap
        if (y < area.y) y = max(area.y, area.y + area.height - h)
        val x = (p.x + room).coerceIn(area.x, max(area.x, area.x + area.width - w))
        pop.win.setLocation(x, y)
        pop.win.repaint()
    }

    private fun popBackground(root: Lin, t: NeonColors, s: Float) {
        val r = 16f * s
        root.bg = { g, w, h ->
            val sh = RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 2 * r, 2 * r)
            g.color = Color.BLACK; g.fill(sh)
            g.stroke = BasicStroke(1f); g.color = col(t.main); g.draw(sh)
        }
    }

    fun closePops() {
        closeSettingsPop()
        closeTimerPop()
    }

    private fun closePopsUnless(w: Window?) {
        if (settingsPop != null && settingsPop?.win != w) closeSettingsPop()
        if (timerPop != null && timerPop?.win != w) closeTimerPop()
    }

    /** Presses outside our windows (anywhere on screen) also close the pop-ups. */
    private fun watchOutside() {
        if (settingsPop == null && timerPop == null) { outsideTimer.stop(); return }
        val down = Win.mouseButtonDown()
        if (down && !buttonWasDown && System.currentTimeMillis() > syntheticUntil) {
            val p = java.awt.MouseInfo.getPointerInfo()?.location
            if (p != null) {
                val inOurs = listOfNotNull(settingsPop?.win, timerPop?.win, panelWin, app.mainWindowIfShown())
                    .any { it.isShowing && it.bounds.contains(p) }
                if (!inOurs) closePops()
            }
        }
        buttonWasDown = down
    }

    private fun rebuildPops() {
        settingsPop?.let { fillSettingsPop(it) }
        timerPop?.let { fillTimerPop(it); placePop(it) }
    }

    private fun refreshPopValues() {
        popFreqText?.text = Config.formatInterval(Prefs.quarters())
        popTimerText?.text = Config.durLabel(Prefs.timerDuration())
        popWarnText?.text = Config.durLabel(Prefs.warnSeconds())
    }

    // ---------------- Settings pop-up ----------------

    private fun toggleSettingsPop() {
        if (settingsPop != null) { closeSettingsPop(); return }
        if (System.currentTimeMillis() - settingsClosedAt < 400) return // the click that closed it
        closeTimerPop()
        settingsPop = openPop { fillSettingsPop(it, placeAfter = false) }
    }

    private fun closeSettingsPop() {
        val pop = settingsPop ?: return
        settingsPop = null
        popFreqText = null
        popTimerText = null
        popWarnText = null
        settingsScroll = null
        warnCustomOpen = false
        settingsClosedAt = System.currentTimeMillis()
        pop.win.dispose()
    }

    /** Quick adjustments: color, menu size, click interval, timer, warning time, background and position lock. */
    private fun fillSettingsPop(pop: Pop, placeAfter: Boolean = true) {
        val root = pop.root
        val t = neon()
        val s = popScale()
        fun sdp(v: Float) = sdp(v, s)
        val thin = maxOf(1f, 0.75f * s)
        val keepScroll = settingsScroll?.scroll?.verticalScrollBar?.value ?: 0
        root.removeAll()
        popBackground(root, t, s)
        root.pad(0, sdp(8f), 0, sdp(10f))
        popWarnText = null

        // Header
        val head = hbox(G_CENTER).pad(sdp(12f), 0, sdp(12f), 0)
        head.add(NText("SETTINGS", Fonts.mono(13f * s, bold = true), t.main), LP(0, WRAP, weight = 1f))
        head.add(NIcon(Icon.CLOSE, t.dim).apply { pad(sdp(4f)); onClick = { closeSettingsPop() } }, LP(sdp(26f), sdp(26f)))
        root.add(head, LP(MATCH, WRAP))

        // Everything below the header scrolls when the screen is short.
        val v = vbox().pad(sdp(12f), 0, sdp(12f), 0)
        val area = Screens.workArea(panelWin?.location ?: Point())
        val scroll = MaxScroll(v, (area.height * 0.8f).toInt() - sdp(48f), t)
        settingsScroll = scroll
        root.add(scroll, LP(MATCH, WRAP))

        fun section(label: String) = v.add(NText(label, Fonts.sans(11f * s), t.body).padding(0, sdp(8f), 0, sdp(3f)), LP(MATCH, WRAP))
        fun chipView(label: String, selected: Boolean, icon: Icon? = null, onClick: () -> Unit) =
            NButton(label, t, if (selected) BStyle.FILLED else BStyle.OUTLINE).apply {
                radius = 10f * s
                tfont = Fonts.mono(11.5f * s)
                minH = 0
                padH = sdp(8f)
                this.icon = icon
                iconSize = sdp(16f)
                this.onClick = onClick
            }
        fun chipRow(vararg chips: NButton): Lin {
            val r = hbox()
            chips.forEach { r.add(it, LP(0, sdp(30f), weight = 1f).margins(sdp(2f))) }
            return r
        }
        fun stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit): Pair<Lin, NText> {
            fun btn(k: Icon, a: () -> Unit) = NIcon(k, t.main).apply {
                circle(BLACK, t.dim, thin)
                pad(sdp(7f))
                onClick = a
            }
            val txt = NText(value, Fonts.mono(14f * s, bold = true), t.main).apply { align = SwingConstants.CENTER }
            val r = hbox(G_CENTER)
            r.add(btn(Icon.MINUS, onMinus), LP(sdp(30f), sdp(30f)))
            r.add(txt, LP(0, WRAP, weight = 1f))
            r.add(btn(Icon.PLUS, onPlus), LP(sdp(30f), sdp(30f)))
            return r to txt
        }

        // Color
        section("Color")
        val curTheme = Prefs.themeIndex()
        val dots = hbox(G_CENTER)
        Palette.NEONS.forEachIndexed { i, (name, c) ->
            dots.add(object : NView() {
                init { toolTipText = name; onClick = { Prefs.put(Config.KEY_THEME, i) } }
                override fun paintView(g: Graphics2D) {
                    val d = minOf(width, height) - 1f
                    val e = Ellipse2D.Float(0.5f, 0.5f, d, d)
                    g.color = col(c); g.fill(e)
                    if (i == curTheme) { g.stroke = BasicStroke(1f); g.color = Color.WHITE; g.draw(e) }
                }
            }, LP(sdp(22f), sdp(22f)).margins(sdp(3f), sdp(2f), sdp(3f), sdp(2f)))
        }
        v.add(dots, LP(WRAP, WRAP))

        // Menu size
        section("Menu size")
        val size = Prefs.menuSizeIndex()
        val sizeLabels = listOf("XS", "S", "M", "L", "XL", "XXL")
        v.add(chipRow(*sizeLabels.mapIndexed { i, l -> chipView(l, i == size) { Prefs.put(Config.KEY_MENU_SIZE, i) } }.toTypedArray()), LP(MATCH, WRAP))

        // Click interval
        section("Click every")
        val (freqRow, freqText) = stepper(Config.formatInterval(Prefs.quarters()), {
            val q = Prefs.quarters(); Prefs.setQuarters(q - Config.stepBelow(q))
        }, {
            val q = Prefs.quarters(); Prefs.setQuarters(q + Config.stepAbove(q))
        })
        popFreqText = freqText
        v.add(freqRow, LP(MATCH, WRAP))

        // Auto-stop timer: off / on, and its length
        section("Auto-stop timer")
        val timerOn = Prefs.timerOn()
        v.add(chipRow(
            chipView("Off", !timerOn) { Prefs.setTimerOn(false) },
            chipView("On", timerOn) { Prefs.setTimerOn(true) },
        ), LP(MATCH, WRAP))
        val stops = Config.TIMER_PRESETS.map { it.second }.filter { it > 0 }
        val (timerRow, timerText) = stepper(Config.durLabel(Prefs.timerDuration()), {
            val cur = Prefs.timerDuration(); stops.lastOrNull { it < cur }?.let { Prefs.setTimerDuration(it) }
        }, {
            val cur = Prefs.timerDuration(); stops.firstOrNull { it > cur }?.let { Prefs.setTimerDuration(it) }
        })
        timerRow.alpha = if (timerOn) 1f else 0.55f // the length can be set before switching on
        popTimerText = timerText
        v.add(timerRow, LP(MATCH, WRAP))

        // Warning time, with the small flashing Airbus beacon just left of the "W" (decorative: it
        // always flashes; the menu's own warning lights still follow the timer).
        val warnHead = hbox(G_START)
        val warnLabel = NText("Warning lights at", Fonts.sans(11f * s), t.body).padding(0, sdp(8f), 0, sdp(3f))
        warnHead.add(NBeacon(warnLabel), LP(10, MATCH, mr = 5))
        warnHead.add(warnLabel, LP(0, WRAP, weight = 1f))
        v.add(warnHead, LP(MATCH, WRAP))
        val warn = Prefs.warnSeconds()
        val isPreset = warn in Config.WARN_PRESETS
        val warnChips = Config.WARN_PRESETS.map { sec ->
            chipView(Config.warnShort(sec), sec == warn && !warnCustomOpen) {
                warnCustomOpen = false
                if (sec == Prefs.warnSeconds()) fillSettingsPop(pop) else Prefs.setWarn(sec)
            }
        }
        val customChip = chipView("Custom", !isPreset || warnCustomOpen) {
            warnCustomOpen = true
            fillSettingsPop(pop)
        }
        v.add(chipRow(*warnChips.take(4).toTypedArray()), LP(MATCH, WRAP))
        v.add(chipRow(*(warnChips.drop(4) + customChip).toTypedArray()), LP(MATCH, WRAP))
        if (!isPreset || warnCustomOpen) {
            val (warnRow, warnText) = stepper(Config.durLabel(warn), {
                val cur = Prefs.warnSeconds(); Prefs.setWarn(cur - Config.warnStepBelow(cur))
            }, {
                val cur = Prefs.warnSeconds(); Prefs.setWarn(cur + Config.warnStepAbove(cur))
            })
            popWarnText = warnText
            v.add(warnRow, LP(MATCH, WRAP, mt = sdp(4f)))
        }

        // Background + position lock (independent of each other)
        section("Menu background")
        v.add(chipRow(
            chipView("Black", !clearBg) { Prefs.put(Config.KEY_PANEL_CLEAR, false) },
            chipView("Clear", clearBg) { Prefs.put(Config.KEY_PANEL_CLEAR, true) },
        ), LP(MATCH, WRAP))
        section("Menu position")
        v.add(chipRow(
            chipView("Free", !locked, Icon.UNLOCK) { Prefs.put(Config.KEY_PANEL_LOCKED, false) },
            chipView("Locked", locked, Icon.LOCK) { Prefs.put(Config.KEY_PANEL_LOCKED, true) },
        ), LP(MATCH, WRAP))

        // More
        v.add(NText("All settings ›", Fonts.sans(12f * s), t.main).apply {
            align = SwingConstants.RIGHT
            padding(0, sdp(10f), sdp(2f), 0)
            onClick = { openApp(settings = true) }
        }, LP(MATCH, WRAP))

        root.revalidate()
        if (placeAfter) placePop(pop)
        if (keepScroll > 0) later { scroll.scroll.verticalScrollBar.value = keepScroll }
        root.repaint()
    }

    /**
     * Timer switched on/off or its length changed (here, in Settings, or in the app):
     * apply it to a session in progress without resetting it.
     */
    private fun applyTimerToSession(key: String) {
        val durMs = Prefs.timerDuration() * 1000L
        val finished = when (key) {
            Config.KEY_TIMER_ON -> session.setTimerOn(Prefs.timerOn(), durMs)
            else -> session.setDuration(durMs)
        }
        if (finished) finish()
    }

    // ---------------- Enlarged timer pop-up ----------------

    private val autoCloseTimerPop = Timer(1500) { closeTimerPop() }.apply { isRepeats = false }

    private fun toggleTimerPop() {
        if (timerPop != null) { closeTimerPop(); return }
        if (System.currentTimeMillis() - timerClosedAt < 400) return
        closeSettingsPop()
        timerPop = openPop { fillTimerPop(it) }
        refreshTimerPop()
    }

    private fun closeTimerPop() {
        autoCloseTimerPop.stop()
        val pop = timerPop ?: return
        timerPop = null
        tpBig = null
        tpLabel = null
        tpSeek = null
        tpAction = null
        tpReset = null
        timerClosedAt = System.currentTimeMillis()
        pop.win.dispose()
    }

    private fun fillTimerPop(pop: Pop) {
        val v = pop.root
        val t = neon()
        val s = popScale()
        fun sdp(x: Float) = sdp(x, s)
        v.removeAll()
        popBackground(v, t, s)
        v.pad(sdp(12f), sdp(8f), sdp(12f), sdp(12f))
        v.minimumSize = Dimension(sdp(264f), 0)

        val head = hbox(G_CENTER)
        head.add(NText("TIMER", Fonts.mono(13f * s, bold = true), t.main), LP(0, WRAP, weight = 1f))
        head.add(NIcon(Icon.CLOSE, t.dim).apply { pad(sdp(4f)); onClick = { closeTimerPop() } }, LP(sdp(26f), sdp(26f)))
        v.add(head, LP(sdp(264f) - 2 * sdp(12f), WRAP))

        val big = NCountdown(28f * s, t.main)
        tpBig = big
        v.add(big, LP(gravity = G_CENTER, mt = sdp(2f)))

        val label = NText("", Fonts.sans(11.5f * s), t.body).apply {
            align = SwingConstants.CENTER
            padding(0, sdp(4f), 0, 0)
        }
        tpLabel = label
        v.add(label, LP(MATCH, WRAP))

        val seek = NSlider(t, Config.TIMER_SLIDER.size - 1).apply {
            onUser = { applySliderSeconds(Config.TIMER_SLIDER[it]) }
            onStart = { autoCloseTimerPop.stop() }
            onStop = {
                refreshTimerPop()
                autoCloseTimerPop.initialDelay = 1500
                autoCloseTimerPop.restart()
            }
        }
        tpSeek = seek
        v.add(seek, LP(MATCH, sdp(40f)))

        fun button(k: Icon, text: String) = NButton(text, t, BStyle.OUTLINE).apply {
            radius = 10f * s
            tfont = Fonts.mono(12f * s)
            minH = sdp(34f)
            padH = sdp(9f)
            icon = k
            iconSize = sdp(16f)
        }
        val action = button(Icon.PLAY, "Start")
        tpAction = action
        action.onClick = {
            autoCloseTimerPop.stop()
            if (running) {
                pause()
            } else if (start()) {
                autoCloseTimerPop.initialDelay = 700 // get out of the way once clicking starts
                autoCloseTimerPop.restart()
            }
        }
        // Reset needs a long press (the label says so); a short click does nothing.
        val resetBtn = button(Icon.RESET, "Hold to reset")
        resetBtn.onLongClick = { reset() }
        tpReset = resetBtn
        val r = hbox()
        r.add(action, LP(0, sdp(34f), weight = 1f, mr = sdp(4f)))
        r.add(resetBtn, LP(0, sdp(34f), weight = 1.25f, ml = sdp(4f)))
        v.add(r, LP(MATCH, WRAP, mt = sdp(4f)))
        refreshTimerPop()
    }

    /** Slider: before starting it sets the timer length; during a session it sets the time left. */
    private fun applySliderSeconds(sec: Int) {
        if (state == ClickSession.State.IDLE) {
            Prefs.setTimerSeconds(sec) // the panel refreshes through the settings listener
            return
        }
        if (sec <= 0) { // slid all the way down: timer off for this session
            Prefs.setTimerOn(false)
            return
        }
        if (session.setTimeLeft(maxOf(sec, 10) * 1000L, Prefs.timerDuration() * 1000L)) finish() else refreshPanel()
        if (!Prefs.timerOn()) Prefs.setTimerOn(true)
    }

    private fun refreshTimerPop() {
        val big = tpBig ?: return
        val t = neon()
        val has = hasTimer()
        val left = remainingSec()
        big.text = if (has) Config.shortTime(left) else "--:--"
        big.color = if (state == ClickSession.State.PAUSED || state == ClickSession.State.IDLE) t.body else t.main
        tpLabel?.text = when (state) {
            ClickSession.State.IDLE -> if (has) "Timer length · starts with ▶" else "No timer · slide to set one"
            ClickSession.State.RUNNING -> if (has) "Time left" else "No timer · slide to set one"
            ClickSession.State.PAUSED -> if (has) "Paused · time left" else "Paused"
            ClickSession.State.DONE -> "Time's up · slide to add time"
        }
        val seek = tpSeek
        if (seek != null && !seek.tracking) seek.progress = Config.sliderIndexFor(if (has) left else 0L)
        tpAction?.let { b ->
            when (state) {
                ClickSession.State.RUNNING -> { b.icon = Icon.PAUSE; b.text = "Pause" }
                ClickSession.State.PAUSED -> { b.icon = Icon.PLAY; b.text = "Resume" }
                else -> { b.icon = Icon.PLAY; b.text = "Start" }
            }
            b.repaint()
        }
        tpReset?.alpha = if (state == ClickSession.State.IDLE && taps == 0) 0.45f else 1f
        val pop = timerPop
        if (pop != null && pop.win.size != pop.win.preferredSize) placePop(pop)
    }

    // ---------------- Session ----------------

    private fun hasTimer(): Boolean = if (state == ClickSession.State.IDLE) Prefs.timerSeconds() > 0 else session.hasTimer

    private fun remainingMs(): Long =
        if (state == ClickSession.State.IDLE) Prefs.timerSeconds() * 1000L else session.remainingMs()

    private fun remainingSec(): Long = (remainingMs() + 999) / 1000

    private fun resetCounters() {
        taps = 0
        seqIndex = 0
        held = false
        blocked = false
    }

    fun toggle() {
        if (running) pause() else start()
    }

    /** Starts a new session, or resumes a paused one. Returns false if it couldn't start. */
    fun start(): Boolean {
        if (running || targets.isEmpty()) return false
        val covered = targets.indexOfFirst { overlayCovers(it.center()) }
        if (covered >= 0) {
            toast(if (targets.size > 1) "Move the menu off target ${covered + 1} first" else "Move the menu off the crosshair first")
            return false
        }
        if (state == ClickSession.State.IDLE || state == ClickSession.State.DONE) resetCounters()
        session.start(Prefs.timerSeconds() * 1000L)
        held = false
        blocked = false
        applyTargetState()
        startEngine()
        uiTimer.stop()
        uiLoop()
        Win.keepAwake(true)
        refreshPanel()
        return true
    }

    /** Stops clicking and freezes the time left. The warning lights keep flashing if they were on. */
    fun pause() {
        if (!running) return
        session.pause()
        stopLoops()
        applyTargetState()
        refreshPanel()
    }

    /** Back to the start: clicking stops, the timer goes back to its full length, lights off. */
    fun reset() {
        stopLoops()
        session.reset()
        resetCounters()
        applyTargetState()
        refreshPanel()
    }

    private fun finish() {
        session.finish()
        stopLoops()
        applyTargetState()
        refreshPanel()
    }

    private fun stopLoops() {
        engine?.interrupt()
        engine = null
        uiTimer.stop()
        held = false
        blocked = false
        Win.keepAwake(false)
    }

    /** Updates the countdown. Wakes just after each whole second instead of several times a second. */
    private fun uiLoop() {
        if (!running) return
        if (session.timeUp()) { finish(); return }
        refreshPanel()
        val wait = if (session.hasTimer) session.remainingMs() % 1000L + 15L else 1000L
        uiTimer.initialDelay = wait.coerceIn(50L, 1000L).toInt()
        uiTimer.restart()
    }

    /** What one interval clicks: computed on the UI thread, clicked on the engine thread. */
    private class Plan(val logical: List<Point>, val physical: List<IntArray?>, val gap: Long)

    /**
     * The clicking loop. Runs on its own thread with an absolute schedule (no drift), sleeping
     * between clicks; the interval can change while running and applies from the next click.
     */
    private fun startEngine() {
        engine?.interrupt()
        val th = Thread({ try { engineLoop() } catch (t: Throwable) { System.err.println("clicker stopped: $t"); t.printStackTrace() } }, "clicker")
        th.isDaemon = true
        th.priority = Thread.MAX_PRIORITY
        engine = th
        th.start()
    }

    private fun engineLoop() {
            val me = Thread.currentThread()
            var next = System.nanoTime() + intervalMs * 1_000_000L
            while (!me.isInterrupted) {
                if (!waitUntil(next)) break
                var plan: Plan? = null
                var stop = false
                try {
                    SwingUtilities.invokeAndWait {
                        if (engine !== me || !running) { stop = true; return@invokeAndWait }
                        plan = planTick()
                        if (!running) stop = true
                    }
                } catch (e: Exception) {
                    break
                }
                if (stop) break
                val p = plan
                var count = 0
                if (p != null && p.logical.isNotEmpty()) {
                    syntheticUntil = System.currentTimeMillis() + 150 + p.gap * p.logical.size
                    p.logical.forEachIndexed { i, pt ->
                        if (me.isInterrupted) return@forEachIndexed
                        if (i > 0 && p.gap > 0) try { Thread.sleep(p.gap) } catch (e: InterruptedException) { return }
                        mouse.click(p.physical[i], pt.x, pt.y)
                        count++
                    }
                }
                val done = count
                SwingUtilities.invokeLater {
                    if (engine === me) {
                        taps += done
                        refreshStatus()
                    }
                }
                val iv = intervalMs * 1_000_000L
                next += iv
                val now = System.nanoTime()
                if (next < now) next = now + iv / 4 // fell behind (very short interval): don't bunch clicks up
            }
    }

    /** Sleeps until [deadline] (nanoTime) with sub-millisecond accuracy. False if interrupted. */
    private fun waitUntil(deadline: Long): Boolean {
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return true
            if (Thread.currentThread().isInterrupted) return false
            if (left > 3_000_000L) {
                try { Thread.sleep((left - 2_000_000L) / 1_000_000L) } catch (e: InterruptedException) { return false }
            } else if (left > 200_000L) {
                LockSupport.parkNanos(left / 2)
            } else {
                Thread.onSpinWait()
            }
        }
    }

    /** One interval (UI thread): the next target in turn, or every target in order. */
    private fun planTick(): Plan? {
        if (!running) return null
        if (session.timeUp()) { finish(); return null }
        if (app.mainIsActive()) {
            held = true // our own window is in front: don't press its buttons
            refreshStatus()
            return null
        }
        held = false
        val n = targets.size
        if (n == 0) return null
        val list = if (n == 1 || multiMode == Config.MODE_SEQUENCE) {
            seqIndex %= n
            listOf(targets[seqIndex]).also { seqIndex = (seqIndex + 1) % n }
        } else targets.toList()
        // Clicks within one interval are spaced out so apps see separate clicks.
        val gap = if (list.size > 1) ((intervalMs - 60) / list.size).coerceIn(20L, 80L) else 0L
        val logical = mutableListOf<Point>()
        val physical = mutableListOf<IntArray?>()
        var anyBlocked = false
        list.forEach { tw ->
            val c = tw.center()
            // Never click our own menu, pop-ups or window, even if they're over a crosshair.
            if (overlayCovers(c) || app.mainCovers(c)) { anyBlocked = true; return@forEach }
            logical += c
            physical += tw.physicalCenter()
        }
        blocked = anyBlocked
        return Plan(logical, physical, gap)
    }

    private fun covers(w: Window?, p: Point) = w != null && w.isShowing && w.bounds.contains(p)

    private fun overlayCovers(p: Point): Boolean {
        val pw = panelWin
        val onPanel = pw != null && pw.isShowing && Rectangle(pw.x + LivePanel.ROOM, pw.y + LivePanel.ROOM,
            pw.width - 2 * LivePanel.ROOM, pw.height - 2 * LivePanel.ROOM).contains(p)
        return onPanel || covers(settingsPop?.win, p) || covers(timerPop?.win, p)
    }

    // ---------------- Panel UI ----------------

    /** Play button look last applied (null = not yet), so it's only rebuilt when it changes. */
    private var playShowsPause: Boolean? = null

    private fun refreshPanel() {
        val play = playBtn ?: return
        val t = neon()
        if (playShowsPause != running) {
            playShowsPause = running
            if (running) {
                play.kind = Icon.PAUSE
                play.color = t.main
                play.glow = true
                play.circle(BLACK, t.main)
            } else {
                play.kind = Icon.PLAY
                play.color = t.onMain
                play.glow = false
                play.circle(t.main, t.main)
            }
        }
        play.toolTipText = when (state) {
            ClickSession.State.RUNNING -> "Pause"
            ClickSession.State.PAUSED -> "Resume"
            else -> "Start"
        }
        eyeBtn?.kind = if (targetHidden) Icon.EYE_OFF else Icon.EYE
        targetCountText?.text = "${targets.size}"

        val has = hasTimer()
        timerView?.let { tv ->
            // Expanded: always shown (click it to set a timer). Collapsed: only during a timed session.
            val show = if (collapsed) has && state != ClickSession.State.IDLE else true
            if (tv.isVisible != show) {
                tv.isVisible = show
                panelWin?.let { it.pack(); it.validate() }
            }
            if (show) {
                tv.text = if (has) Config.shortTime(remainingSec()) else "--:--"
                tv.color = if (running) t.main else t.body
            }
        }
        // Warning threshold reached: the lights flash on the menu. Pausing doesn't switch them
        // off; Reset, more time, or switching the timer off does.
        panel?.warning = session.warningOn(warnSec)
        refreshStatus()
        refreshTimerPop()
        // text widths can change (e.g. 9:59 -> 10:00)
        panelWin?.let { w -> if (w.size != w.preferredSize) { w.pack(); w.validate() } }
        app.onSessionChanged()
    }

    /** Click count and state line under the play button. */
    private fun refreshStatus() {
        val status = statusText ?: return
        val line = "$taps ${if (taps == 1) "click" else "clicks"}"
        val second = when {
            running && held -> "HELD"
            running && blocked -> "BLOCKED"
            running -> null
            state == ClickSession.State.PAUSED -> "PAUSED"
            state == ClickSession.State.DONE -> "DONE"
            else -> "READY"
        }
        val text = if (second == null) line else "$line\n$second"
        if (status.text != text) {
            status.text = text
            panelWin?.let { w -> if (w.size != w.preferredSize) { w.pack(); w.validate() } }
        }
    }

    /** For the tray menu and the main window. */
    val stateLabel: String get() = when (state) {
        ClickSession.State.RUNNING -> "Pause clicking"
        ClickSession.State.PAUSED -> "Resume clicking"
        else -> "Start clicking"
    }

    private fun toast(msg: String) {
        val w = panelWin
        Toast.show(msg, near = w?.let { Point(it.x + it.width / 2, it.y + it.height / 2) })
    }

    /** The windows that make up the floating controls (for tests and the main window's checks). */
    internal fun windows(): List<Window> = listOfNotNull(panelWin, settingsPop?.win, timerPop?.win) + targets

    internal fun debugState() = "state=$state taps=$taps held=$held blocked=$blocked targets=${targets.size}"
    internal fun debugTargetsCenters() = targets.map { it.center() }
    internal fun debugPanelWindow() = panelWin
    internal fun debugOpenSettings() = toggleSettingsPop()
    internal fun debugOpenTimer() = toggleTimerPop()
    internal fun debugMoveTarget(i: Int, x: Int, y: Int) {
        val tw = targets[i]
        tw.setLocation(x - tw.width / 2, y - tw.height / 2)
        onTargetMoved(tw)
    }
    internal fun debugAddTarget() = addTarget()
    internal fun debugCollapse(c: Boolean) = setCollapsed(c)
}

/** Shared helper for the panel's NIcon padding. */
fun NIcon.pad(p: Int) = apply { padX = p; padY = p }
