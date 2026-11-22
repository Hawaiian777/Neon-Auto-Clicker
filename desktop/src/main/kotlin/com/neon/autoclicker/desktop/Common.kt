package com.neon.autoclicker.desktop

import com.neon.autoclicker.Config
import com.neon.autoclicker.Look
import com.neon.autoclicker.NeonColors
import com.neon.autoclicker.Palette
import com.neon.autoclicker.Profile
import com.neon.autoclicker.Pt
import com.neon.autoclicker.Targets
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dialog
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JWindow
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.Timer

/** Colors: the floating controls always use the dark neon look; the main window follows the app mode. */
object Theme {
    fun dark(): NeonColors {
        val (name, neon) = Palette.NEONS[Prefs.themeIndex()]
        return NeonColors(name, neon, Look.NIGHT)
    }

    fun resolveLook(): Look = Palette.resolve(Prefs.appMode(), systemIsDark = Win.systemIsDark() ?: true)

    fun app(): NeonColors = dark().withLook(resolveLook())

    /** Can windows have see-through pixels here? (Always on Windows 8+.) */
    val translucent: Boolean by lazy {
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                .isWindowTranslucencySupported(java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
        } catch (t: Throwable) {
            false
        }
    }
}

/** Screens: positions are saved as fractions of the whole desktop (all monitors). */
object Screens {
    fun all(): List<Rectangle> = try {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
    } catch (t: Throwable) {
        listOf(Rectangle(0, 0, 1280, 800))
    }

    fun primary(): Rectangle = try {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
    } catch (t: Throwable) {
        Rectangle(0, 0, 1280, 800)
    }

    fun virtual(): Rectangle = all().reduce { a, b -> a.union(b) }

    /** Screen containing [p], or the nearest one. */
    fun at(p: Point): Rectangle {
        val list = all()
        list.firstOrNull { it.contains(p) }?.let { return it }
        return list.minByOrNull { r ->
            val dx = maxOf(r.x - p.x, 0, p.x - (r.x + r.width))
            val dy = maxOf(r.y - p.y, 0, p.y - (r.y + r.height))
            dx.toLong() * dx + dy.toLong() * dy
        } ?: primary()
    }

    /** Usable area (without the taskbar) of the screen at [p]. */
    fun workArea(p: Point): Rectangle {
        val devs = try { GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.toList() } catch (t: Throwable) { emptyList() }
        val s = at(p)
        val gc = devs.map { it.defaultConfiguration }.firstOrNull { it.bounds == s } ?: return s
        val ins = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
        return Rectangle(s.x + ins.left, s.y + ins.top, s.width - ins.left - ins.right, s.height - ins.top - ins.bottom)
    }

    fun toFraction(x: Int, y: Int): Pt {
        val v = virtual()
        return Pt(((x - v.x).toFloat() / v.width).coerceIn(0f, 1f), ((y - v.y).toFloat() / v.height).coerceIn(0f, 1f))
    }

    fun fromFraction(p: Pt): Point {
        val v = virtual()
        return Point(v.x + Math.round(p.x * v.width), v.y + Math.round(p.y * v.height))
    }
}

/** Saved profiles (the same storage format as Android). */
object Profiles {
    fun load(): MutableList<Profile> = Profile.listFromJson(Prefs.store.getString(Config.KEY_PROFILES, null))

    private fun saveAll(list: List<Profile>) = Prefs.store.edit().putString(Config.KEY_PROFILES, Profile.listToJson(list)).apply()

    fun current(name: String) = Profile(
        name = name,
        intervalQ = Prefs.quarters(),
        timerSec = Prefs.timerDuration(),
        targetDp = Prefs.targetDp(),
        mode = Prefs.mode(),
        targets = Prefs.loadTargets() ?: listOf(Pt(0.5f, 0.5f)),
    )

    fun saveOrReplace(p: Profile) {
        val list = load()
        val i = list.indexOfFirst { it.name.equals(p.name, ignoreCase = true) }
        if (i >= 0) list[i] = p else list += p
        saveAll(list)
    }

    fun delete(name: String) = saveAll(load().filterNot { it.name == name })

    /** Makes the profile the current setup. Never starts clicking. */
    fun apply(p: Profile) {
        Prefs.store.edit()
            .putInt(Config.KEY_INTERVAL_Q, p.intervalQ)
            .putInt(Config.KEY_TIMER_DUR, p.timerSec)
            .putInt(Config.KEY_TARGET_DP, p.targetDp)
            .putInt(Config.KEY_MODE, p.mode)
            .putString(Config.KEY_TARGETS, Targets.toJson(p.targets))
            .putLong(Config.KEY_RELOAD, System.currentTimeMillis())
            .apply()
    }
}

/** A see-through, never-focused, always-on-top window (the floating menu, pop-ups, toasts, crosshairs). */
open class OverlayWindow(clickThrough: Boolean = false) : JWindow() {
    var clickThrough = clickThrough
        set(value) {
            if (field == value) return
            field = value
            if (isDisplayable) Win.styleOverlay(this, value)
        }

    init {
        focusableWindowState = false
        isAlwaysOnTop = true
        type = Window.Type.UTILITY
        background = if (Theme.translucent) Color(0, 0, 0, 0) else Color.BLACK
        (contentPane as JComponent).isOpaque = false
        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) { Win.styleOverlay(this@OverlayWindow, this@OverlayWindow.clickThrough) }
        })
    }

    override fun addNotify() {
        super.addNotify()
        Win.styleOverlay(this, clickThrough)
    }
}

/** Short messages (Android Toast), at the bottom of the screen [near] is on. */
object Toast {
    private var win: OverlayWindow? = null
    private var timer: Timer? = null

    fun show(msg: String, long: Boolean = false, near: Point? = null) {
        win?.dispose()
        timer?.stop()
        val t = Theme.dark()
        val text = NText(msg, Fonts.sans(13.5f), com.neon.autoclicker.hex("#E6F2E8")).apply {
            align = SwingConstants.CENTER
            padding(16, 10, 16, 11)
        }
        val box = vbox(G_CENTER).apply {
            bg = { g, w, h ->
                val r = RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 22f, 22f)
                g.color = Color(12, 12, 12, 238)
                g.fill(r)
                g.stroke = BasicStroke(1f)
                g.color = col(t.dim)
                g.draw(r)
            }
        }
        val maxW = 360
        val tw = minOf(maxW, text.prefWidth())
        box.add(text, LP(tw, WRAP))
        val w = OverlayWindow(clickThrough = true)
        w.contentPane = box
        w.pack()
        val p = near ?: java.awt.MouseInfo.getPointerInfo()?.location ?: Point(0, 0)
        val area = Screens.workArea(p)
        w.setLocation(area.x + (area.width - w.width) / 2, area.y + area.height - w.height - 64)
        w.isVisible = true
        win = w
        val started = System.currentTimeMillis()
        val life = if (long) 3500L else 2000L
        timer = Timer(40) {
            val age = System.currentTimeMillis() - started
            when {
                age >= life + 250 -> { w.dispose(); timer?.stop(); if (win == w) win = null }
                age >= life -> { box.alpha = 1f - (age - life) / 250f }
            }
        }.apply { start() }
    }
}

/** Dialogs in the app colors (Android's neonDialog): themed box, thin neon outline, text buttons. */
object Dialogs {
    fun neon(owner: Window?, t: NeonColors, title: String, content: JComponent, positive: String?, onPositive: (() -> Unit)?): JDialog {
        val d = JDialog(owner, title, Dialog.ModalityType.DOCUMENT_MODAL)
        d.isUndecorated = true
        if (Theme.translucent) d.background = Color(0, 0, 0, 0)
        val root = vbox().apply {
            pad(1, 1, 1, 1)
            bg = { g, w, h ->
                val r = RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 36f, 36f)
                g.color = col(t.surface)
                g.fill(r)
                g.stroke = BasicStroke(1f)
                g.color = col(t.main)
                g.draw(r)
            }
        }
        root.add(NText(title, Fonts.mono(18f, bold = true), t.main).padding(22, 20, 22, 8), LP(MATCH, WRAP))
        root.add(content, LP(MATCH, WRAP))
        val buttons = hbox().pad(12, 8, 12, 12)
        buttons.add(vbox(), LP(0, 1, weight = 1f))
        fun textBtn(label: String, color: Int, action: () -> Unit) = NButton(label, t, BStyle.TEXT).apply {
            tfont = Fonts.sans(14f, bold = true)
            textColor = color
            minH = 40
            padH = 14
            onClick = action
        }
        buttons.add(textBtn(if (positive == null) "Close" else "Cancel", t.body) { d.dispose() }, LP())
        if (positive != null) buttons.add(textBtn(positive.uppercase(), t.main) { d.dispose(); onPositive?.invoke() }, LP(ml = 4))
        root.add(buttons, LP(MATCH, WRAP))
        d.contentPane = root
        root.registerKeyboardAction({ d.dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        if (positive != null) root.registerKeyboardAction({ d.dispose(); onPositive?.invoke() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        val w = maxOf(320, minOf(420, root.lin.prefWidth(root)))
        d.setSize(w, root.lin.heightFor(root, w))
        val maxH = (Screens.workArea(owner?.location ?: Point()).height * 0.85).toInt()
        if (d.height > maxH) d.setSize(w, maxH)
        d.setLocationRelativeTo(owner)
        return d
    }

    /** A dialog whose positive action runs [onPositive]; shows it (blocks until closed). */
    fun show(owner: Window?, t: NeonColors, title: String, content: JComponent, positive: String?, onPositive: (() -> Unit)?) {
        neon(owner, t, title, content, positive, onPositive).isVisible = true
    }

    /** Number pickers side by side, each followed by its unit (Android pickerRow). */
    fun pickerRow(t: NeonColors, vararg parts: Pair<NPicker, String>): Lin {
        val r = hbox(G_CENTER).pad(12, 8, 12, 0)
        r.add(vbox(), LP(0, 1, weight = 1f))
        parts.forEach { (p, unit) ->
            r.add(p, LP(64, 132))
            if (unit.isNotEmpty()) r.add(NText(unit, Fonts.sans(18f), t.main).padding(4, 0, 10, 0), LP())
        }
        r.add(vbox(), LP(0, 1, weight = 1f))
        return r
    }

    /** Fades [c] (used for windows we can't give real alpha). */
    fun alphaComposite(a: Float) = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a)
}
