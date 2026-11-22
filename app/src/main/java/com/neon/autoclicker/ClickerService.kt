package com.neon.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

class ClickerService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickerService? = null
            private set

        /**
         * True while the app's own screen is in front. Clicking and the timer keep going,
         * but taps are held back so they never press the app's own buttons.
         */
        @Volatile
        var appInForeground = false
    }

    /** One crosshair window. */
    private class Tgt(val view: TargetView, val lp: WindowManager.LayoutParams)

    /** A small pop-up window next to the menu (Settings or the enlarged timer). */
    private class Pop(val view: LinearLayout, val lp: WindowManager.LayoutParams)

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private val targets = mutableListOf<Tgt>()
    /** Target centers as fractions of the screen, so they follow rotation and unfolding. */
    private var targetsN = mutableListOf<PointF>()
    /** Panel top-left as fractions of the screen. */
    private var panelN = PointF(0f, 0.33f)

    private var panel: LivePanel? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var playBtn: IconView? = null
    private var eyeBtn: IconView? = null
    private var statusText: TextView? = null
    private var timerView: AnnunciatorView? = null
    private var targetCountText: TextView? = null

    // Pop-ups
    private var settingsPop: Pop? = null
    private var timerPop: Pop? = null
    private var settingsClosedAt = 0L
    private var timerClosedAt = 0L
    private var popFreqText: TextView? = null
    private var popTimerText: TextView? = null
    private var popWarnText: TextView? = null
    private var settingsScroll: MaxHeightScrollView? = null
    private var warnCustomOpen = false
    private var tpBig: AnnunciatorView? = null
    private var tpLabel: TextView? = null
    private var tpSeek: SeekBar? = null
    private var tpActionIcon: IconView? = null
    private var tpActionText: TextView? = null
    private var tpReset: View? = null
    private var seekTracking = false
    private var lockToastAt = 0L

    // Session
    /** Running / paused / done and the auto-stop timer (see ClickSession). */
    private val session = ClickSession { SystemClock.elapsedRealtime() }
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

    val isOverlayShown: Boolean get() = panel != null

    // Settings read on every tap / tick, kept here instead of re-reading them each time.
    private var intervalMs = 1000L
    private var warnSec = Prefs.WARN_DEFAULT
    private var multiMode = Prefs.MODE_SEQUENCE
    private var volumeToggle = false

    private fun loadCachedSettings() {
        intervalMs = Prefs.intervalMs(this)
        warnSec = Prefs.warnSeconds(this)
        multiMode = Prefs.mode(this)
        volumeToggle = prefs.getBoolean(Prefs.KEY_VOLUME_TOGGLE, false)
    }

    // Kept as a field: SharedPreferences only holds listeners weakly.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        handler.post { onPrefChanged(key) }
    }

    // ---------------- Service lifecycle ----------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs.get(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        loadCachedSettings()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        pause()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        hideOverlay()
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        instance = null
    }

    /** Rotation or folding/unfolding: move everything to the same relative spots. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        closePops()
        handler.postDelayed({ relayoutAll() }, 300)
    }

    /** Optional: Volume Down starts / pauses while the controls are showing. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (panel == null) return false
        if (!volumeToggle) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggle()
        return true
    }

    /** Live updates from the app screen and from the Settings pop-up. */
    private fun onPrefChanged(key: String?) {
        when (key) {
            Prefs.KEY_INTERVAL_Q, Prefs.KEY_WARN_SEC, Prefs.KEY_MODE, Prefs.KEY_VOLUME_TOGGLE -> loadCachedSettings()
        }
        if (panel == null) return
        when (key) {
            Prefs.KEY_THEME -> {
                val c = neon().main
                targets.forEach { it.view.color = c }
                rebuildPanel()
                rebuildPops()
            }
            Prefs.KEY_MENU_SIZE -> {
                rebuildPanel()
                rebuildPops()
            }
            Prefs.KEY_PANEL_CLEAR -> {
                clearBg = Prefs.panelClear(this)
                rebuildPanel()
                rebuildPops()
            }
            Prefs.KEY_PANEL_LOCKED -> {
                locked = Prefs.panelLocked(this)
                rebuildPanel()
                rebuildPops()
            }
            Prefs.KEY_TARGET_DP -> relayoutTargets()
            Prefs.KEY_INTERVAL_Q -> {
                refreshPopValues()
                refreshPanel()
            }
            Prefs.KEY_TIMER_ON, Prefs.KEY_TIMER_DUR -> {
                applyTimerToSession(key)
                if (key == Prefs.KEY_TIMER_ON) {
                    settingsPop?.let { pop ->
                        fillSettingsPop(pop.view)
                        pop.view.post { placePop(pop) }
                    }
                } else {
                    refreshPopValues()
                }
                refreshPanel()
            }
            Prefs.KEY_WARN_SEC -> {
                // New threshold: lights switch on/off right away; chips show the new choice.
                val custom = Prefs.warnSeconds(this) !in Prefs.WARN_PRESETS
                if (custom) warnCustomOpen = true
                val pop = settingsPop
                if (pop != null && (popWarnText == null || !custom)) {
                    fillSettingsPop(pop.view)
                    pop.view.post { placePop(pop) }
                } else {
                    refreshPopValues()
                }
                refreshPanel()
            }
            Prefs.KEY_RELOAD -> { // a profile was loaded: new setup, fresh session
                reset()
                targetsN = loadTargetsN()
                buildTargets()
                refreshPanel()
            }
        }
    }

    private fun neon() = Themes.current(this)

    // ---------------- Overlay ----------------

    fun showOverlay() {
        if (panel != null) return
        collapsed = prefs.getBoolean(Prefs.KEY_COLLAPSED, false)
        targetHidden = prefs.getBoolean(Prefs.KEY_TARGET_HIDDEN, false)
        locked = Prefs.panelLocked(this)
        clearBg = Prefs.panelClear(this)
        session.reset()
        resetCounters()

        targetsN = loadTargetsN()
        buildTargets()

        panelN = loadPanelN(screenSize().first)
        val p = LivePanel(this)
        val plp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT
        )
        panel = p
        panelLp = plp
        populatePanel()
        placePanel()
        wm.addView(p, plp)
        p.post { clampPanel() }
    }

    fun hideOverlay() {
        closePops()
        reset()
        targets.forEach { runCatching { wm.removeView(it.view) } }
        targets.clear()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelLp = null
        playBtn = null
        eyeBtn = null
        statusText = null
        timerView = null
        targetCountText = null
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // ---------------- Targets ----------------

    /** Saved targets, or one in the middle of the screen the first time. */
    private fun loadTargetsN(): MutableList<PointF> =
        Prefs.loadTargets(this) ?: mutableListOf(PointF(0.5f, 0.5f)).also { Prefs.saveTargets(this, it) }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildTargets() {
        closePops()
        targets.forEach { runCatching { wm.removeView(it.view) } }
        targets.clear()
        val t = neon()
        val ring = dp(Prefs.targetDp(this).toFloat())
        val win = TargetView.windowSizeFor(ring, resources.displayMetrics.density)
        val multi = targetsN.size > 1
        targetsN.forEachIndexed { i, n ->
            val v = TargetView(this)
            v.color = t.main
            v.ringPx = ring
            v.label = if (multi) "${i + 1}" else null
            val tg = Tgt(v, overlayParams(win, win))
            positionTarget(tg, n)
            v.setOnTouchListener(Dragger(v, tg.lp, { !running }, onDragEnd = { onTargetMoved(tg) }))
            wm.addView(v, tg.lp)
            targets += tg
        }
        applyTargetState()
        // Keep the menu above the crosshairs.
        val p = panel
        val plp = panelLp
        if (p != null && plp != null && p.isAttachedToWindow) {
            runCatching { wm.removeView(p) }
            runCatching { wm.addView(p, plp) }
        }
    }

    private fun positionTarget(tg: Tgt, n: PointF) {
        val (sw, sh) = screenSize()
        tg.lp.x = (n.x * sw).roundToInt() - tg.lp.width / 2
        tg.lp.y = (n.y * sh).roundToInt() - tg.lp.height / 2
    }

    private fun onTargetMoved(tg: Tgt) {
        val i = targets.indexOf(tg)
        if (i < 0 || i >= targetsN.size) return
        val (sw, sh) = screenSize()
        targetsN[i] = PointF(
            ((tg.lp.x + tg.lp.width / 2f) / sw).coerceIn(0f, 1f),
            ((tg.lp.y + tg.lp.height / 2f) / sh).coerceIn(0f, 1f)
        )
        Prefs.saveTargets(this, targetsN)
    }

    /** Size change or rotation: same relative centers, new pixel positions. */
    private fun relayoutTargets() {
        val ring = dp(Prefs.targetDp(this).toFloat())
        val win = TargetView.windowSizeFor(ring, resources.displayMetrics.density)
        targets.forEachIndexed { i, tg ->
            tg.view.ringPx = ring
            tg.lp.width = win
            tg.lp.height = win
            positionTarget(tg, targetsN.getOrElse(i) { PointF(0.5f, 0.5f) })
            runCatching { wm.updateViewLayout(tg.view, tg.lp) }
        }
    }

    private fun addTarget() {
        if (running) { toast("Pause clicking first"); return }
        if (targetsN.size >= Prefs.MAX_TARGETS) { toast("Up to ${Prefs.MAX_TARGETS} targets"); return }
        val last = targetsN.lastOrNull() ?: PointF(0.5f, 0.5f)
        var nx = last.x + 0.1f
        var ny = last.y + 0.06f
        if (nx > 0.9f) nx = 0.15f
        if (ny > 0.9f) ny = 0.15f
        targetsN.add(PointF(nx, ny))
        Prefs.saveTargets(this, targetsN)
        if (targetHidden) setTargetsHidden(false) // show them so the new one can be placed
        buildTargets()
        refreshPanel()
    }

    private fun removeTarget() {
        if (running) { toast("Pause clicking first"); return }
        if (targetsN.size <= 1) { toast("At least one target is needed"); return }
        targetsN.removeAt(targetsN.lastIndex)
        Prefs.saveTargets(this, targetsN)
        buildTargets()
        refreshPanel()
    }

    /** Visible + touchable only when shown and idle. Hidden or running = touches pass through. */
    private fun applyTargetState() {
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val touchable = !running && !targetHidden
        targets.forEach { tg ->
            tg.view.visibility = if (targetHidden) View.INVISIBLE else View.VISIBLE
            tg.view.active = running
            tg.lp.flags = if (touchable) tg.lp.flags and flag.inv() else tg.lp.flags or flag
            runCatching { wm.updateViewLayout(tg.view, tg.lp) }
        }
    }

    private fun setTargetsHidden(hidden: Boolean) {
        targetHidden = hidden
        prefs.edit().putBoolean(Prefs.KEY_TARGET_HIDDEN, hidden).apply()
        applyTargetState() // positions and sizes are kept
        refreshPanel()
    }

    // ---------------- Panel ----------------

    /** Saved menu position, or the left edge a third of the way down the first time. */
    private fun loadPanelN(sw: Int): PointF {
        val fx = prefs.getFloat(Prefs.KEY_PANEL_FX, -1f)
        val fy = prefs.getFloat(Prefs.KEY_PANEL_FY, -1f)
        return if (fx >= 0f && fy >= 0f) PointF(fx, fy) else PointF(dp(8f).toFloat() / sw, 1f / 3f)
    }

    private fun placePanel() {
        val lp = panelLp ?: return
        val (sw, sh) = screenSize()
        lp.x = (panelN.x * sw).roundToInt()
        lp.y = (panelN.y * sh).roundToInt()
    }

    private fun savePanelPosition() {
        val lp = panelLp ?: return
        val (sw, sh) = screenSize()
        panelN = PointF((lp.x.toFloat() / sw).coerceIn(0f, 1f), (lp.y.toFloat() / sh).coerceIn(0f, 1f))
        prefs.edit().putFloat(Prefs.KEY_PANEL_FX, panelN.x).putFloat(Prefs.KEY_PANEL_FY, panelN.y).apply()
    }

    private fun relayoutAll() {
        if (panel == null) return
        relayoutTargets()
        placePanel()
        val p = panel
        val plp = panelLp
        if (p != null && plp != null) runCatching { wm.updateViewLayout(p, plp) }
        p?.post { clampPanel() }
    }

    /** Builds the panel (wide/horizontal) for the current color, size, background and collapsed state. */
    @SuppressLint("ClickableViewAccessibility")
    private fun populatePanel() {
        val p = panel ?: return
        val lp = panelLp ?: return
        val t = neon()
        val s = Prefs.menuScale(this)
        val density = resources.displayMetrics.density
        fun sdp(v: Float) = maxOf(1, (v * s * density).roundToInt())
        val thin = hairline(0.75f * s, density)
        fun wrap() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )

        p.removeAllViews()
        playShowsPause = null
        p.orientation = LinearLayout.HORIZONTAL
        p.gravity = Gravity.CENTER_VERTICAL
        p.background = null
        p.themeColor = t.main
        p.clear = clearBg
        p.cornerRadius = sdp(if (collapsed) 28f else 20f).toFloat()

        // One dragger for the whole panel: drag from anywhere (unless locked), a plain tap still presses buttons.
        val drag = Dragger(p, lp, { !locked }, onDragStart = { closePops() }, onDragEnd = { savePanelPosition() },
            onBlockedDrag = { lockedHint() })
        p.setOnTouchListener(drag)

        val play = IconView(this).apply {
            setOnClickListener { toggle() }
            setOnTouchListener(drag)
        }
        playBtn = play

        fun countdown(sizeSp: Float) = AnnunciatorView(this).apply {
            textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp * s, resources.displayMetrics)
            color = t.main
            setOnClickListener { toggleTimerPop() }
            setOnTouchListener(drag)
        }

        fun lockIcon(sizeW: Float, sizeH: Float) = IconView(this).apply {
            kind = if (locked) IconView.Kind.LOCK else IconView.Kind.GRIP
            color = if (locked) t.main else t.dim
            halo = clearBg
            setPadding(sdp(2f), sdp(4f), sdp(2f), sdp(4f))
            contentDescription = if (locked) "Position locked" else "Drag to move"
            setOnClickListener { handler.post { toggleLock() } }
            setOnTouchListener(drag)
        }.also { it.layoutParams = LinearLayout.LayoutParams(sdp(sizeW), sdp(sizeH)) }

        if (collapsed) {
            // Collapsed: Start/Pause, the countdown (during a timed session) and a small expand arrow.
            p.setInnerPadding(sdp(6f), sdp(6f), sdp(4f), sdp(6f))
            play.setPadding(sdp(11f), sdp(11f), sdp(11f), sdp(11f))
            p.addView(play, LinearLayout.LayoutParams(sdp(44f), sdp(44f)))
            val timer = countdown(11f)
            timerView = timer
            p.addView(timer, wrap().apply { marginStart = sdp(5f) })
            if (locked) p.addView(lockIcon(16f, 24f).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = sdp(2f)
            })
            val expand = IconView(this).apply {
                kind = IconView.Kind.EXPAND
                rotation = -90f // points right: "open"
                color = t.main
                halo = clearBg
                setPadding(sdp(3f), sdp(3f), sdp(3f), sdp(3f))
                setOnClickListener { handler.post { setCollapsed(false) } }
                setOnTouchListener(drag)
            }
            p.addView(expand, LinearLayout.LayoutParams(sdp(26f), sdp(26f)).apply { marginStart = sdp(2f) })
            statusText = null
            eyeBtn = null
            targetCountText = null
        } else {
            p.setInnerPadding(sdp(4f), sdp(8f), sdp(6f), sdp(8f))

            // Drag grip, or a lock when the position is locked (tap to switch)
            p.addView(lockIcon(18f, 30f).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = sdp(2f)
            })

            // Start / Pause / Resume
            play.setPadding(sdp(15f), sdp(15f), sdp(15f), sdp(15f))
            p.addView(play, LinearLayout.LayoutParams(sdp(56f), sdp(56f)))

            // Taps + countdown (tap the countdown to enlarge it)
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnTouchListener(drag)
            }
            val status = TextView(this).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f * s
                setTextColor(t.main)
                gravity = Gravity.CENTER
                minWidth = sdp(54f)
                if (clearBg) setShadowLayer(4f * density, 0f, 0f, Color.BLACK)
                setOnTouchListener(drag)
            }
            statusText = status
            info.addView(status, wrap())
            val timer = countdown(12f)
            timerView = timer
            info.addView(timer, wrap().apply { topMargin = sdp(2f) })
            p.addView(info, wrap().apply { marginStart = sdp(6f) })

            // Small round buttons
            fun small(k: IconView.Kind, action: () -> Unit) = IconView(this).apply {
                kind = k
                color = t.main
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.BLACK)
                    setStroke(thin, t.dim)
                }
                setPadding(sdp(6.5f), sdp(6.5f), sdp(6.5f), sdp(6.5f))
                setOnClickListener { handler.post { action() } }
                setOnTouchListener(drag)
            }
            val m = sdp(2.5f)
            fun cell(w: Float = 28f) = LinearLayout.LayoutParams(sdp(w), sdp(28f)).apply { setMargins(m, m, m, m) }

            val eye = small(if (targetHidden) IconView.Kind.EYE_OFF else IconView.Kind.EYE) {
                setTargetsHidden(!targetHidden)
            }
            eyeBtn = eye
            val row1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(small(IconView.Kind.HOME) { openApp(settings = false) }, cell())
                addView(eye, cell())
                addView(small(IconView.Kind.GEAR) { toggleSettingsPop() }, cell())
            }

            // − count + : one balanced pill, minus on the left, plus on the right
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.BLACK)
                    cornerRadius = sdp(14f).toFloat()
                    setStroke(thin, t.dim)
                }
                setOnTouchListener(drag)
            }
            fun pillBtn(k: IconView.Kind, desc: String, action: () -> Unit) = IconView(this).apply {
                kind = k
                color = t.main
                contentDescription = desc
                setPadding(sdp(7f), sdp(7f), sdp(7f), sdp(7f))
                setOnClickListener { handler.post { action() } }
                setOnTouchListener(drag)
            }
            pill.addView(pillBtn(IconView.Kind.MINUS, "Remove target") { removeTarget() },
                LinearLayout.LayoutParams(sdp(27f), sdp(28f)))
            val count = TextView(this).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textSize = 11f * s
                setTextColor(t.main)
                gravity = Gravity.CENTER
                minWidth = sdp(14f)
                setOnTouchListener(drag)
            }
            targetCountText = count
            pill.addView(count, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, sdp(28f)))
            pill.addView(pillBtn(IconView.Kind.PLUS, "Add target") { addTarget() },
                LinearLayout.LayoutParams(sdp(27f), sdp(28f)))

            val collapse = small(IconView.Kind.COLLAPSE) { setCollapsed(true) }.apply {
                rotation = -90f // points left: "fold away"
            }
            val row2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(pill, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, sdp(28f)
                ).apply { setMargins(m, m, m, m) })
                addView(collapse, cell())
            }
            val grid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(row1)
                addView(row2)
            }
            p.addView(grid, wrap().apply { marginStart = sdp(6f) })

            // Close, top-right
            val close = IconView(this).apply {
                kind = IconView.Kind.CLOSE
                color = t.dim
                halo = clearBg
                setPadding(sdp(4f), sdp(4f), sdp(4f), sdp(4f))
                setOnClickListener { handler.post { hideOverlay() } }
                setOnTouchListener(drag)
            }
            p.addView(close, LinearLayout.LayoutParams(sdp(22f), sdp(22f)).apply {
                gravity = Gravity.TOP
                marginStart = sdp(2f)
            })
        }
        refreshPanel()
    }

    private fun rebuildPanel() {
        val p = panel ?: return
        populatePanel()
        panelLp?.let { runCatching { wm.updateViewLayout(p, it) } }
        p.post {
            clampPanel()
            settingsPop?.let { placePop(it) }
            timerPop?.let { placePop(it) }
        }
    }

    private fun setCollapsed(c: Boolean) {
        collapsed = c
        prefs.edit().putBoolean(Prefs.KEY_COLLAPSED, c).apply()
        closePops()
        rebuildPanel() // the clicking keeps running
    }

    /** The grip turns into a lock icon (and back), so no message is needed. */
    private fun toggleLock() {
        prefs.edit().putBoolean(Prefs.KEY_PANEL_LOCKED, !locked).apply()
    }

    private fun lockedHint() {
        val now = SystemClock.uptimeMillis()
        if (now - lockToastAt > 3000) {
            lockToastAt = now
            toast("Menu is locked. Tap the lock to unlock.")
        }
    }

    /** Home: open the app. Clicking and the timer carry on; taps are held while the app is in front. */
    private fun openApp(settings: Boolean) {
        closePops()
        val i = Intent(this, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            .putExtra(
                MainActivity.EXTRA_SECTION,
                if (settings) MainActivity.SECTION_APPEARANCE else MainActivity.SECTION_TOP
            )
        runCatching { startActivity(i) }
    }

    // ---------------- Pop-ups (shared) ----------------

    private fun popScale() = Prefs.menuScale(this).coerceIn(0.85f, 1.3f)

    @SuppressLint("ClickableViewAccessibility")
    private fun openPop(onOutside: () -> Unit, fill: (LinearLayout) -> Unit): Pop {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT
        ).apply { flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH }
        val pop = Pop(v, lp)
        fill(v)
        v.alpha = 0f
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutside()
            false
        }
        wm.addView(v, lp)
        v.post {
            placePop(pop)
            v.alpha = 1f
        }
        return pop
    }

    /** Opens below the menu, or above it when there's no room. */
    private fun placePop(pop: Pop) {
        val p = panel ?: return
        val plp = panelLp ?: return
        val (sw, sh) = screenSize()
        val w = pop.view.width
        val h = pop.view.height
        val gap = dp(2f)
        var y = plp.y + p.height - p.room + gap
        if (y + h > sh) y = plp.y + p.room - h - gap
        if (y < 0) y = maxOf(0, sh - h)
        pop.lp.x = (plp.x + p.room).coerceIn(0, maxOf(0, sw - w))
        pop.lp.y = y
        runCatching { wm.updateViewLayout(pop.view, pop.lp) }
    }

    private fun popBackground(t: NeonTheme, s: Float) = GradientDrawable().apply {
        setColor(Color.BLACK)
        cornerRadius = 16f * s * resources.displayMetrics.density
        setStroke(hairline(1f, resources.displayMetrics.density), t.main)
    }

    private fun closePops() {
        closeSettingsPop()
        closeTimerPop()
    }

    private fun rebuildPops() {
        settingsPop?.let { pop ->
            fillSettingsPop(pop.view)
            pop.view.post { placePop(pop) }
        }
        timerPop?.let { pop ->
            fillTimerPop(pop.view)
            pop.view.post { placePop(pop) }
        }
    }

    private fun refreshPopValues() {
        popFreqText?.text = Prefs.formatInterval(Prefs.quarters(this))
        popTimerText?.text = Prefs.durLabel(Prefs.timerDuration(this))
        popWarnText?.text = Prefs.durLabel(Prefs.warnSeconds(this))
    }

    // ---------------- Settings pop-up ----------------

    private fun toggleSettingsPop() {
        if (settingsPop != null) { closeSettingsPop(); return }
        if (SystemClock.uptimeMillis() - settingsClosedAt < 400) return // the tap that closed it
        closeTimerPop()
        settingsPop = openPop({ closeSettingsPop() }) { fillSettingsPop(it) }
    }

    private fun closeSettingsPop() {
        val pop = settingsPop ?: return
        settingsPop = null
        popFreqText = null
        popTimerText = null
        popWarnText = null
        settingsScroll = null
        warnCustomOpen = false
        settingsClosedAt = SystemClock.uptimeMillis()
        runCatching { wm.removeView(pop.view) }
    }

    /** Quick adjustments: color, menu size, tap interval, timer, warning time, background and position lock. */
    private fun fillSettingsPop(root: LinearLayout) {
        val t = neon()
        val s = popScale()
        val density = resources.displayMetrics.density
        fun sdp(x: Float) = maxOf(1, (x * s * density).roundToInt())
        val thin = hairline(0.75f * s, density)
        val keepScroll = settingsScroll?.scrollY ?: 0
        root.removeAllViews()
        root.background = popBackground(t, s)
        root.setPadding(0, sdp(8f), 0, sdp(10f))
        popWarnText = null

        // Header
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "SETTINGS"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 13f * s
            setTextColor(t.main)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(IconView(this).apply {
            kind = IconView.Kind.CLOSE
            color = t.dim
            setPadding(sdp(4f), sdp(4f), sdp(4f), sdp(4f))
            setOnClickListener { closeSettingsPop() }
        }, LinearLayout.LayoutParams(sdp(26f), sdp(26f)))
        head.setPadding(sdp(12f), 0, sdp(12f), 0)
        root.addView(head)

        // Everything below the header scrolls when the screen is short (landscape, big sizes).
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(sdp(12f), 0, sdp(12f), 0)
            clipChildren = false
            clipToPadding = false
        }
        val scroll = MaxHeightScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            maxHeightPx = (screenSize().second * 0.8f).toInt() - sdp(48f)
            addView(v)
        }
        settingsScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        if (keepScroll > 0) scroll.post { scroll.scrollTo(0, keepScroll) }

        fun section(label: String) = v.addView(TextView(this).apply {
            text = label
            textSize = 11f * s
            setTextColor(t.body)
            setPadding(0, sdp(8f), 0, sdp(3f))
        })
        fun chip(label: String, selected: Boolean, icon: IconView.Kind? = null, onClick: () -> Unit): View {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = if (selected) NeonUi.filled(t, density, 10f * s) else NeonUi.outline(t, density, 10f * s)
                setPadding(sdp(8f), 0, sdp(8f), 0)
                setOnClickListener { onClick() }
            }
            val fg = if (selected) t.onMain else t.main
            if (icon != null) c.addView(IconView(this).apply {
                kind = icon
                color = fg
                setPadding(sdp(1f), sdp(1f), sdp(1f), sdp(1f))
            }, LinearLayout.LayoutParams(sdp(16f), sdp(16f)).apply { marginEnd = sdp(4f) })
            c.addView(TextView(this).apply {
                text = label
                typeface = Typeface.MONOSPACE
                textSize = 11.5f * s
                setTextColor(fg)
            })
            return c
        }
        fun chipRow(vararg chips: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            chips.forEach {
                addView(it, LinearLayout.LayoutParams(0, sdp(30f), 1f).apply { setMargins(sdp(2f), sdp(2f), sdp(2f), sdp(2f)) })
            }
        }
        fun stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit): Pair<View, TextView> {
            fun btn(k: IconView.Kind, a: () -> Unit) = IconView(this).apply {
                kind = k
                color = t.main
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.BLACK)
                    setStroke(thin, t.dim)
                }
                setPadding(sdp(7f), sdp(7f), sdp(7f), sdp(7f))
                setOnClickListener { a() }
            }
            val txt = TextView(this).apply {
                text = value
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textSize = 14f * s
                setTextColor(t.main)
                gravity = Gravity.CENTER
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(btn(IconView.Kind.MINUS, onMinus), LinearLayout.LayoutParams(sdp(30f), sdp(30f)))
                addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(btn(IconView.Kind.PLUS, onPlus), LinearLayout.LayoutParams(sdp(30f), sdp(30f)))
            }
            return row to txt
        }

        // Color
        section("Color")
        val curTheme = Prefs.themeIndex(this)
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Themes.ALL.forEachIndexed { i, th ->
            dots.addView(View(this).apply {
                contentDescription = th.name
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(th.neon)
                    if (i == curTheme) setStroke(hairline(1f, resources.displayMetrics.density), Color.WHITE)
                }
                setOnClickListener { prefs.edit().putInt(Prefs.KEY_THEME, i).apply() }
            }, LinearLayout.LayoutParams(sdp(22f), sdp(22f)).apply { setMargins(sdp(3f), sdp(2f), sdp(3f), sdp(2f)) })
        }
        v.addView(dots)

        // Menu size
        section("Menu size")
        val size = Prefs.menuSizeIndex(this)
        val sizeLabels = listOf("XS", "S", "M", "L", "XL", "XXL")
        v.addView(chipRow(*sizeLabels.mapIndexed { i, l ->
            chip(l, i == size) { prefs.edit().putInt(Prefs.KEY_MENU_SIZE, i).apply() }
        }.toTypedArray()))

        // Tap interval
        section("Tap every")
        val (freqRow, freqText) = stepper(Prefs.formatInterval(Prefs.quarters(this)), {
            val q = Prefs.quarters(this)
            prefs.edit().putInt(Prefs.KEY_INTERVAL_Q, (q - Prefs.stepBelow(q)).coerceIn(Prefs.MIN_Q, Prefs.MAX_Q)).apply()
        }, {
            val q = Prefs.quarters(this)
            prefs.edit().putInt(Prefs.KEY_INTERVAL_Q, (q + Prefs.stepAbove(q)).coerceIn(Prefs.MIN_Q, Prefs.MAX_Q)).apply()
        })
        popFreqText = freqText
        v.addView(freqRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Auto-stop timer: off / on, and its length
        section("Auto-stop timer")
        val timerOn = Prefs.timerOn(this)
        v.addView(chipRow(
            chip("Off", !timerOn) { Prefs.setTimerOn(this, false) },
            chip("On", timerOn) { Prefs.setTimerOn(this, true) },
        ))
        val stops = Prefs.TIMER_PRESETS.map { it.second }.filter { it > 0 }
        val (timerRow, timerText) = stepper(Prefs.durLabel(Prefs.timerDuration(this)), {
            val cur = Prefs.timerDuration(this)
            stops.lastOrNull { it < cur }?.let { setTimerLength(it) }
        }, {
            val cur = Prefs.timerDuration(this)
            stops.firstOrNull { it > cur }?.let { setTimerLength(it) }
        })
        timerRow.alpha = if (timerOn) 1f else 0.55f // the length can be set before switching on
        popTimerText = timerText
        v.addView(timerRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Warning time: when the Airbus lights start (same setting as the app's section 3)
        // Heading with the small flashing Airbus beacon just left of the "W" (decorative: it
        // always flashes; the menu's own warning lights still follow the timer).
        val warnHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
        }
        val warnLabel = TextView(this).apply {
            text = "Warning lights at"
            textSize = 11f * s
            setTextColor(t.body)
            setPadding(0, sdp(8f), 0, sdp(3f))
        }
        warnHead.addView(BeaconView(this).apply { alignTo = warnLabel },
            LinearLayout.LayoutParams(dp(10f), LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(5f) })
        warnHead.addView(warnLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        v.addView(warnHead, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val warn = Prefs.warnSeconds(this)
        val isPreset = warn in Prefs.WARN_PRESETS
        fun setWarn(sec: Int) = prefs.edit().putInt(Prefs.KEY_WARN_SEC, sec.coerceIn(Prefs.WARN_MIN, Prefs.WARN_MAX)).apply()
        val warnChips = Prefs.WARN_PRESETS.map { sec ->
            chip(Prefs.warnShort(sec), sec == warn && !warnCustomOpen) {
                warnCustomOpen = false
                if (sec == Prefs.warnSeconds(this)) {
                    fillSettingsPop(root) // same value: just close the custom stepper
                    settingsPop?.let { pop -> pop.view.post { placePop(pop) } }
                } else {
                    setWarn(sec)
                }
            }
        }
        val customChip = chip("Custom", !isPreset || warnCustomOpen) {
            warnCustomOpen = true
            fillSettingsPop(root)
            settingsPop?.let { pop -> pop.view.post { placePop(pop) } }
        }
        v.addView(chipRow(*warnChips.take(4).toTypedArray()))
        val secondRow: List<View> = warnChips.drop(4) + listOf(customChip)
        v.addView(chipRow(*secondRow.toTypedArray()))
        if (!isPreset || warnCustomOpen) {
            val (warnRow, warnText) = stepper(Prefs.durLabel(warn), {
                val cur = Prefs.warnSeconds(this)
                setWarn(cur - Prefs.warnStepBelow(cur))
            }, {
                val cur = Prefs.warnSeconds(this)
                setWarn(cur + Prefs.warnStepAbove(cur))
            })
            popWarnText = warnText
            v.addView(warnRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = sdp(4f) })
        }

        // Background + position lock (independent of each other)
        section("Menu background")
        v.addView(chipRow(
            chip("Black", !clearBg) { prefs.edit().putBoolean(Prefs.KEY_PANEL_CLEAR, false).apply() },
            chip("Clear", clearBg) { prefs.edit().putBoolean(Prefs.KEY_PANEL_CLEAR, true).apply() },
        ))
        section("Menu position")
        v.addView(chipRow(
            chip("Free", !locked, IconView.Kind.UNLOCK) { prefs.edit().putBoolean(Prefs.KEY_PANEL_LOCKED, false).apply() },
            chip("Locked", locked, IconView.Kind.LOCK) { prefs.edit().putBoolean(Prefs.KEY_PANEL_LOCKED, true).apply() },
        ))

        // More
        v.addView(TextView(this).apply {
            text = "All settings ›"
            textSize = 12f * s
            setTextColor(t.main)
            gravity = Gravity.END
            setPadding(0, sdp(10f), sdp(2f), 0)
            setOnClickListener { openApp(settings = true) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** Timer length from Settings (the session picks it up through the settings listener). */
    private fun setTimerLength(sec: Int) {
        Prefs.setTimerDuration(this, sec)
    }

    /**
     * Timer switched on/off or its length changed (here, in Settings, or in the app):
     * apply it to a session in progress without resetting it.
     * Off: no time limit, warning lights stop. On mid-session: the length counts from now.
     */
    private fun applyTimerToSession(key: String?) {
        val durMs = Prefs.timerDuration(this) * 1000L
        val finished = when (key) {
            Prefs.KEY_TIMER_ON -> session.setTimerOn(Prefs.timerOn(this), durMs)
            else -> session.setDuration(durMs)
        }
        if (finished) finish()
    }

    // ---------------- Enlarged timer pop-up ----------------

    private fun toggleTimerPop() {
        if (timerPop != null) { closeTimerPop(); return }
        if (SystemClock.uptimeMillis() - timerClosedAt < 400) return
        closeSettingsPop()
        timerPop = openPop({ closeTimerPop() }) { fillTimerPop(it) }
        refreshTimerPop()
    }

    private fun closeTimerPop() {
        handler.removeCallbacks(autoCloseTimerPop)
        val pop = timerPop ?: return
        timerPop = null
        tpBig = null
        tpLabel = null
        tpSeek = null
        tpActionIcon = null
        tpActionText = null
        tpReset = null
        seekTracking = false
        timerClosedAt = SystemClock.uptimeMillis()
        runCatching { wm.removeView(pop.view) }
    }

    /** Returns the timer to its compact look after an adjustment. */
    private val autoCloseTimerPop = Runnable { closeTimerPop() }

    private fun fillTimerPop(v: LinearLayout) {
        val t = neon()
        val s = popScale()
        val density = resources.displayMetrics.density
        fun sdp(x: Float) = maxOf(1, (x * s * density).roundToInt())
        v.removeAllViews()
        v.background = popBackground(t, s)
        v.setPadding(sdp(12f), sdp(8f), sdp(12f), sdp(12f))
        v.minimumWidth = sdp(240f)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "TIMER"
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 13f * s
            setTextColor(t.main)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(IconView(this).apply {
            kind = IconView.Kind.CLOSE
            color = t.dim
            setPadding(sdp(4f), sdp(4f), sdp(4f), sdp(4f))
            setOnClickListener { closeTimerPop() }
        }, LinearLayout.LayoutParams(sdp(26f), sdp(26f)))
        v.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val big = AnnunciatorView(this).apply {
            textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 28f * s, resources.displayMetrics)
            color = t.main
        }
        tpBig = big
        v.addView(big, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = sdp(2f) })

        val label = TextView(this).apply {
            textSize = 11.5f * s
            setTextColor(t.body)
            gravity = Gravity.CENTER
            setPadding(0, sdp(4f), 0, 0)
        }
        tpLabel = label
        v.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val seek = SeekBar(this).apply {
            max = Prefs.TIMER_SLIDER.size - 1
            progressTintList = ColorStateList.valueOf(t.main)
            thumbTintList = ColorStateList.valueOf(t.main)
            progressBackgroundTintList = ColorStateList.valueOf(t.faint)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) applySliderSeconds(Prefs.TIMER_SLIDER[progress])
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    seekTracking = true
                    handler.removeCallbacks(autoCloseTimerPop)
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seekTracking = false
                    refreshTimerPop()
                    handler.postDelayed(autoCloseTimerPop, 1500)
                }
            })
        }
        tpSeek = seek
        v.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, sdp(40f)))

        fun button(k: IconView.Kind, text: String): Triple<LinearLayout, IconView, TextView> {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = NeonUi.outline(t, density, 10f * s)
                setPadding(sdp(8f), 0, sdp(10f), 0)
            }
            val ic = IconView(this).apply {
                kind = k
                color = t.main
                setPadding(sdp(1f), sdp(1f), sdp(1f), sdp(1f))
            }
            val tx = TextView(this).apply {
                this.text = text
                typeface = Typeface.MONOSPACE
                textSize = 12f * s
                setTextColor(t.main)
            }
            b.addView(ic, LinearLayout.LayoutParams(sdp(16f), sdp(16f)).apply { marginEnd = sdp(5f) })
            b.addView(tx)
            return Triple(b, ic, tx)
        }
        val (action, actionIcon, actionText) = button(IconView.Kind.PLAY, "Start")
        tpActionIcon = actionIcon
        tpActionText = actionText
        action.setOnClickListener {
            handler.removeCallbacks(autoCloseTimerPop)
            if (running) {
                pause()
            } else if (start()) {
                handler.postDelayed(autoCloseTimerPop, 700) // get out of the way once clicking starts
            }
        }
        // Reset needs a long press (the label says so); a short tap does nothing.
        val (resetBtn, _, _) = button(IconView.Kind.RESET, "Hold to reset")
        resetBtn.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            reset()
            true
        }
        tpReset = resetBtn
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(action, LinearLayout.LayoutParams(0, sdp(34f), 1f).apply { marginEnd = sdp(4f) })
            addView(resetBtn, LinearLayout.LayoutParams(0, sdp(34f), 1.25f).apply { marginStart = sdp(4f) })
        }
        v.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = sdp(4f) })
        refreshTimerPop()
    }

    /** Slider: before starting it sets the timer length; during a session it sets the time left. */
    private fun applySliderSeconds(sec: Int) {
        if (state == ClickSession.State.IDLE) {
            Prefs.setTimerSeconds(this, sec) // the panel refreshes through the settings listener
            return
        }
        if (sec <= 0) { // slid all the way down: timer off for this session
            Prefs.setTimerOn(this, false)
            return
        }
        if (session.setTimeLeft(maxOf(sec, 10) * 1000L, Prefs.timerDuration(this) * 1000L)) finish() else refreshPanel()
        if (!Prefs.timerOn(this)) Prefs.setTimerOn(this, true)
    }

    private fun refreshTimerPop() {
        val big = tpBig ?: return
        val t = neon()
        val has = hasTimer()
        val left = remainingSec()
        big.text = if (has) Prefs.shortTime(left) else "--:--"
        big.color = if (state == ClickSession.State.PAUSED || state == ClickSession.State.IDLE) t.body else t.main
        val what = when (state) {
            ClickSession.State.IDLE -> if (has) "Timer length · starts with ▶" else "No timer · slide to set one"
            ClickSession.State.RUNNING -> if (has) "Time left" else "No timer · slide to set one"
            ClickSession.State.PAUSED -> if (has) "Paused · time left" else "Paused"
            ClickSession.State.DONE -> "Time's up · slide to add time"
        }
        tpLabel.setIfChanged(what)
        if (!seekTracking) tpSeek?.progress = Prefs.sliderIndexFor(if (has) left else 0L)
        when (state) {
            ClickSession.State.RUNNING -> { tpActionIcon?.kind = IconView.Kind.PAUSE; tpActionText.setIfChanged("Pause") }
            ClickSession.State.PAUSED -> { tpActionIcon?.kind = IconView.Kind.PLAY; tpActionText.setIfChanged("Resume") }
            else -> { tpActionIcon?.kind = IconView.Kind.PLAY; tpActionText.setIfChanged("Start") }
        }
        tpReset?.alpha = if (state == ClickSession.State.IDLE && taps == 0) 0.45f else 1f
    }

    // ---------------- Session ----------------

    /** Before a session starts, the timer shown is the one set in Settings. */
    private fun hasTimer(): Boolean = if (state == ClickSession.State.IDLE) Prefs.timerSeconds(this) > 0 else session.hasTimer

    private fun remainingMs(): Long =
        if (state == ClickSession.State.IDLE) Prefs.timerSeconds(this) * 1000L else session.remainingMs()

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
        val covered = targets.indexOfFirst { tg -> centerOf(tg)?.let { overlayCovers(it.x, it.y) } == true }
        if (covered >= 0) {
            toast(if (targets.size > 1) "Move the menu off target ${covered + 1} first" else "Move the menu off the crosshair first")
            return false
        }
        if (state == ClickSession.State.IDLE || state == ClickSession.State.DONE) resetCounters()
        session.start(Prefs.timerSeconds(this) * 1000L)
        held = false
        blocked = false
        applyTargetState()
        handler.removeCallbacks(tapLoop)
        handler.removeCallbacks(uiLoop)
        handler.postDelayed(tapLoop, intervalMs)
        handler.post(uiLoop)
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
        handler.removeCallbacks(tapLoop)
        handler.removeCallbacks(uiLoop)
        held = false
        blocked = false
    }

    private val tapLoop = object : Runnable {
        override fun run() {
            if (!running) return
            if (session.timeUp()) {
                finish(); return
            }
            if (appInForeground) {
                held = true // the app's own screen is in front: don't press its buttons
            } else {
                held = false
                tick()
            }
            refreshStatus()
            handler.postDelayed(this, intervalMs) // interval changes apply from the next tap
        }
    }

    /** Updates the countdown. Wakes just after each whole second instead of several times a second. */
    private val uiLoop = object : Runnable {
        override fun run() {
            if (!running) return
            if (session.timeUp()) {
                finish(); return
            }
            refreshPanel()
            val wait = if (session.hasTimer) session.remainingMs() % 1000L + 15L else 1000L
            handler.postDelayed(this, wait.coerceIn(50L, 1000L))
        }
    }

    /** One interval: the next target in turn, or every target in order. */
    private fun tick() {
        val n = targets.size
        if (n == 0) return
        if (n == 1 || multiMode == Prefs.MODE_SEQUENCE) {
            seqIndex %= n
            tapTargets(listOf(targets[seqIndex]))
            seqIndex = (seqIndex + 1) % n
        } else {
            tapTargets(targets)
        }
    }

    private fun tapTargets(list: List<Tgt>) {
        // Taps within one interval are spaced out so apps see separate taps.
        val gap = if (list.size > 1) {
            ((intervalMs - 60) / list.size).coerceIn(20L, 80L)
        } else 0L
        val builder = GestureDescription.Builder()
        var count = 0
        var anyBlocked = false
        list.forEach { tg ->
            val c = centerOf(tg) ?: return@forEach
            // Never tap our own menu or pop-ups, even if they're over a crosshair.
            if (overlayCovers(c.x, c.y)) {
                anyBlocked = true
                return@forEach
            }
            val path = Path().apply { moveTo(c.x.coerceAtLeast(0f), c.y.coerceAtLeast(0f)) }
            builder.addStroke(GestureDescription.StrokeDescription(path, count * gap, 40))
            count++
        }
        blocked = anyBlocked
        if (count > 0) {
            runCatching { dispatchGesture(builder.build(), null, null) }
            taps += count
        }
    }

    private fun centerOf(tg: Tgt): PointF? {
        val v = tg.view
        if (v.width == 0) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return PointF(loc[0] + v.width / 2f, loc[1] + v.height / 2f)
    }

    private fun covers(v: View?, x: Float, y: Float): Boolean {
        if (v == null || v.width == 0) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height
    }

    private fun overlayCovers(x: Float, y: Float): Boolean =
        covers(panel, x, y) || covers(settingsPop?.view, x, y) || covers(timerPop?.view, x, y)

    // ---------------- Panel UI ----------------

    /** Play button look last applied (null = not yet), so it's only rebuilt when it changes. */
    private var playShowsPause: Boolean? = null

    private fun refreshPanel() {
        val play = playBtn ?: return
        val t = neon()
        if (playShowsPause != running) {
            playShowsPause = running
            if (running) {
                play.kind = IconView.Kind.PAUSE
                play.color = t.main
                play.glow = true
                play.background = circle(Color.BLACK, t.main)
            } else {
                play.kind = IconView.Kind.PLAY
                play.color = t.onMain
                play.glow = false
                play.background = circle(t.main, t.main)
            }
        }
        play.contentDescription = when (state) {
            ClickSession.State.RUNNING -> "Pause"
            ClickSession.State.PAUSED -> "Resume"
            else -> "Start"
        }
        eyeBtn?.kind = if (targetHidden) IconView.Kind.EYE_OFF else IconView.Kind.EYE
        targetCountText.setIfChanged("${targets.size}")

        val has = hasTimer()
        timerView?.let { tv ->
            // Expanded: always shown (tap it to set a timer). Collapsed: only during a timed session.
            val show = if (collapsed) has && state != ClickSession.State.IDLE else true
            tv.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                tv.text = if (has) Prefs.shortTime(remainingSec()) else "--:--"
                tv.color = if (running) t.main else t.body
            }
        }
        // Warning threshold reached: the lights flash on the menu. Pausing doesn't switch them
        // off; Reset, more time, or switching the timer off does.
        panel?.warning = session.warningOn(warnSec)
        refreshStatus()
        refreshTimerPop()
    }

    /** Tap count and state line under the play button. */
    private fun refreshStatus() {
        val status = statusText ?: return
        val tapsLine = "$taps ${if (taps == 1) "tap" else "taps"}"
        // (the target count is on the − n + pill, the time on the countdown box)
        val secondLine = when {
            running && held -> "HELD"
            running && blocked -> "BLOCKED"
            running -> null
            state == ClickSession.State.PAUSED -> "PAUSED"
            state == ClickSession.State.DONE -> "DONE"
            else -> "READY"
        }
        status.setIfChanged(if (secondLine == null) tapsLine else "$tapsLine\n$secondLine")
    }

    /** Sets text only when it differs, so the floating window isn't re-laid-out for nothing. */
    private fun TextView?.setIfChanged(value: String) {
        if (this != null && text.toString() != value) text = value
    }

    private fun circle(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(hairline(1f, resources.displayMetrics.density), stroke)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Float) = (v * resources.displayMetrics.density).roundToInt()

    private fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        val display = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    /** Keeps at least half of the menu on screen. */
    private fun clampPanel() {
        val v = panel ?: return
        val lp = panelLp ?: return
        val (sw, sh) = screenSize()
        val w = v.width
        val h = v.height
        lp.x = lp.x.coerceIn(-w / 2, maxOf(-w / 2, sw - w / 2))
        lp.y = lp.y.coerceIn(-h / 2, maxOf(-h / 2, sh - h / 2))
        runCatching { wm.updateViewLayout(v, lp) }
    }

    /**
     * Drags a floating window. A tap without movement still counts as a button press.
     * When dragging isn't allowed (locked menu), a drag does nothing and doesn't press anything.
     */
    private inner class Dragger(
        private val win: View,
        private val lp: WindowManager.LayoutParams,
        private val canDrag: () -> Boolean,
        private val onDragStart: () -> Unit = {},
        private val onDragEnd: () -> Unit = {},
        private val onBlockedDrag: () -> Unit = {},
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@ClickerService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var dragging = false
        private var sw = 0
        private var sh = 0

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    downX = e.rawX; downY = e.rawY
                    moved = false
                    dragging = false
                    val size = screenSize()
                    sw = size.first; sh = size.second
                    if (v.hasOnClickListeners()) v.alpha = 0.6f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        v.alpha = 1f
                        dragging = canDrag()
                        if (dragging) onDragStart() else onBlockedDrag()
                    }
                    if (dragging) {
                        val w = win.width
                        val h = win.height
                        lp.x = (startX + dx.toInt()).coerceIn(-w / 2, maxOf(-w / 2, sw - w / 2))
                        lp.y = (startY + dy.toInt()).coerceIn(-h / 2, maxOf(-h / 2, sh - h / 2))
                        runCatching { wm.updateViewLayout(win, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    if (!moved) v.performClick() else if (dragging) onDragEnd()
                }
                MotionEvent.ACTION_CANCEL -> v.alpha = 1f
            }
            return true
        }
    }
}
