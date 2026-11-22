package com.neon.autoclicker.desktop

import com.neon.autoclicker.BLACK
import com.neon.autoclicker.NeonColors
import com.neon.autoclicker.WHITE
import com.neon.autoclicker.argb
import com.neon.autoclicker.blueOf
import com.neon.autoclicker.greenOf
import com.neon.autoclicker.hex
import com.neon.autoclicker.redOf
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.LayoutManager2
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.EmptyBorder
import javax.swing.plaf.basic.BasicScrollBarUI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// =============================================================================================
// A small LinearLayout: the floating menu, pop-ups and app sections are laid out exactly like
// their Android layouts (sizes in dp = Java pixels; Windows display scaling is applied by Java).
// =============================================================================================

const val MATCH = -1
const val WRAP = -2
const val G_START = 0
const val G_CENTER = 1
const val G_END = 2

class LP(
    var w: Int = WRAP,
    var h: Int = WRAP,
    var weight: Float = 0f,
    var ml: Int = 0, var mt: Int = 0, var mr: Int = 0, var mb: Int = 0,
    /** Cross-axis placement; -1 = the container's default. */
    var gravity: Int = -1,
) {
    fun margins(l: Int, t: Int, r: Int, b: Int) = apply { ml = l; mt = t; mr = r; mb = b }
    fun margins(all: Int) = margins(all, all, all, all)
}

/** Components that know their height for a given width (wrapped text). */
interface HeightForWidth {
    fun prefWidth(): Int
    fun heightFor(w: Int): Int
}

fun lpOf(c: Component): LP = (c as? JComponent)?.getClientProperty("lp") as? LP ?: LP()

fun prefWidthOf(c: Component): Int = when {
    c is HeightForWidth -> c.prefWidth()
    c is Container && c.layout is LinLayout -> (c.layout as LinLayout).prefWidth(c)
    else -> c.preferredSize.width
}

fun heightFor(c: Component, w: Int): Int = when {
    c is HeightForWidth -> c.heightFor(w)
    c is Container && c.layout is LinLayout -> (c.layout as LinLayout).heightFor(c, w)
    else -> c.preferredSize.height
}

class LinLayout(val vertical: Boolean, var gravity: Int = if (vertical) G_START else G_CENTER) : LayoutManager2 {

    private fun kids(p: Container) = p.components.filter { it.isVisible }

    /** Natural width: fixed, or the content's (also for weighted 0-width children, like a wrap-content LinearLayout). */
    private fun childPrefW(c: Component, lp: LP) = when {
        lp.w > 0 -> lp.w
        lp.w == 0 && lp.weight == 0f -> 0
        else -> prefWidthOf(c)
    }

    private fun crossGravity(lp: LP) = if (lp.gravity >= 0) lp.gravity else gravity

    fun prefWidth(p: Container): Int {
        val i = p.insets
        val k = kids(p)
        val inner = if (vertical) {
            k.maxOfOrNull { c -> val lp = lpOf(c); childPrefW(c, lp) + lp.ml + lp.mr } ?: 0
        } else {
            k.sumOf { c -> val lp = lpOf(c); childPrefW(c, lp) + lp.ml + lp.mr }
        }
        return max(inner + i.left + i.right, (p as? JComponent)?.takeIf { it.isMinimumSizeSet }?.minimumSize?.width ?: 0)
    }

    /** Child widths for a horizontal row of total width [w]. */
    private fun rowWidths(p: Container, w: Int): IntArray {
        val i = p.insets
        val k = kids(p)
        val avail = w - i.left - i.right
        val ws = IntArray(k.size)
        var used = 0
        var weights = 0f
        k.forEachIndexed { n, c ->
            val lp = lpOf(c)
            used += lp.ml + lp.mr
            val wt = if (lp.w == MATCH) max(lp.weight, 1f) else lp.weight
            if (wt > 0f) {
                weights += wt
                if (lp.w > 0) { ws[n] = lp.w; used += lp.w }
            } else {
                ws[n] = if (lp.w >= 0) lp.w else prefWidthOf(c)
                used += ws[n]
            }
        }
        val rest = max(0, avail - used)
        if (weights > 0f) {
            var given = 0
            var lastW = -1
            k.forEachIndexed { n, c ->
                val lp = lpOf(c)
                val wt = if (lp.w == MATCH) max(lp.weight, 1f) else lp.weight
                if (wt > 0f) {
                    val add = (rest * wt / weights).toInt()
                    ws[n] += add
                    given += add
                    lastW = n
                }
            }
            if (lastW >= 0) ws[lastW] += rest - given
        } else if (used > avail) {
            // too wide: shrink the wrap-content children that can wrap (text)
            var over = used - avail
            for (n in k.indices.reversed()) {
                if (over <= 0) break
                if (k[n] is HeightForWidth && lpOf(k[n]).w == WRAP) {
                    val take = min(over, ws[n] - 10)
                    if (take > 0) { ws[n] -= take; over -= take }
                }
            }
        }
        return ws
    }

    private fun childH(c: Component, lp: LP, cw: Int): Int = when {
        lp.h >= 0 -> lp.h
        else -> heightFor(c, cw)
    }

    fun heightFor(p: Container, w: Int): Int {
        val i = p.insets
        val k = kids(p)
        val inner = if (vertical) {
            val avail = w - i.left - i.right
            k.sumOf { c ->
                val lp = lpOf(c)
                val cw = vChildW(c, lp, avail)
                childH(c, lp, cw) + lp.mt + lp.mb
            }
        } else {
            val ws = rowWidths(p, w)
            k.withIndex().maxOfOrNull { (n, c) ->
                val lp = lpOf(c)
                if (lp.h == MATCH) 0 else childH(c, lp, ws[n]) + lp.mt + lp.mb
            } ?: 0
        }
        val minH = (p as? JComponent)?.takeIf { it.isMinimumSizeSet }?.minimumSize?.height ?: 0
        return max(inner + i.top + i.bottom, minH)
    }

    private fun vChildW(c: Component, lp: LP, avail: Int) = when (lp.w) {
        MATCH -> avail - lp.ml - lp.mr
        WRAP -> min(prefWidthOf(c), avail - lp.ml - lp.mr)
        else -> lp.w
    }.coerceAtLeast(0)

    override fun layoutContainer(p: Container) {
        val i = p.insets
        val k = kids(p)
        val w = p.width
        val h = p.height
        if (vertical) {
            val avail = w - i.left - i.right
            var y = i.top
            k.forEach { c ->
                val lp = lpOf(c)
                val cw = vChildW(c, lp, avail)
                val ch = childH(c, lp, cw)
                y += lp.mt
                val x = when (crossGravity(lp)) {
                    G_CENTER -> i.left + lp.ml + (avail - lp.ml - lp.mr - cw) / 2
                    G_END -> w - i.right - lp.mr - cw
                    else -> i.left + lp.ml
                }
                c.setBounds(x, y, cw, ch)
                y += ch + lp.mb
            }
        } else {
            val ws = rowWidths(p, w)
            val innerH = h - i.top - i.bottom
            var x = i.left
            k.forEachIndexed { n, c ->
                val lp = lpOf(c)
                val cw = ws[n]
                val ch = if (lp.h == MATCH) innerH - lp.mt - lp.mb else childH(c, lp, cw)
                x += lp.ml
                val y = when (crossGravity(lp)) {
                    G_START -> i.top + lp.mt
                    G_END -> h - i.bottom - lp.mb - ch
                    else -> i.top + lp.mt + (innerH - lp.mt - lp.mb - ch) / 2
                }
                c.setBounds(x, y, cw, max(0, ch))
                x += cw + lp.mr
            }
        }
    }

    override fun preferredLayoutSize(p: Container): Dimension {
        val pw = prefWidth(p)
        val w = if (p.width > 0 && p.parent is JViewport) p.width else pw
        return Dimension(pw, heightFor(p, w))
    }

    override fun minimumLayoutSize(p: Container) = preferredLayoutSize(p)
    override fun maximumLayoutSize(p: Container) = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    override fun addLayoutComponent(c: Component, constraints: Any?) {
        if (constraints is LP) (c as? JComponent)?.putClientProperty("lp", constraints)
    }
    override fun addLayoutComponent(name: String?, c: Component) {}
    override fun removeLayoutComponent(c: Component) {}
    override fun getLayoutAlignmentX(t: Container) = 0f
    override fun getLayoutAlignmentY(t: Container) = 0f
    override fun invalidateLayout(t: Container) {}
}

/** A LinearLayout panel with an optional painted background and alpha. */
open class Lin(vertical: Boolean, gravity: Int = if (vertical) G_START else G_CENTER) : JPanel(), Scrollable {
    var bg: ((Graphics2D, Int, Int) -> Unit)? = null
        set(value) { field = value; repaint() }
    var alpha = 1f
        set(value) { field = value; repaint() }
    /** True for the main screen's scrolling content: follows the viewport width. */
    var tracksWidth = false

    init {
        layout = LinLayout(vertical, gravity)
        isOpaque = false
    }

    val lin get() = layout as LinLayout

    fun pad(l: Int, t: Int, r: Int, b: Int) = apply { border = EmptyBorder(t, l, b, r) }
    fun pad(all: Int) = pad(all, all, all, all)

    fun add(c: Component, lp: LP): Component {
        (c as? JComponent)?.putClientProperty("lp", lp)
        return super.add(c)
    }

    fun minHeight(h: Int) = apply { minimumSize = Dimension(0, h) }

    override fun paint(g: Graphics) {
        if (alpha >= 0.999f) { super.paint(g); return }
        val g2 = g.create() as Graphics2D
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
        super.paint(g2)
        g2.dispose()
    }

    override fun paintComponent(g: Graphics) {
        val b = bg ?: return
        val g2 = (g.create() as Graphics2D).smooth()
        b(g2, width, height)
        g2.dispose()
    }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 24
    override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = max(24, r.height - 48)
    override fun getScrollableTracksViewportWidth() = tracksWidth
    override fun getScrollableTracksViewportHeight() = false
}

fun hbox(gravity: Int = G_CENTER) = Lin(false, gravity)
fun vbox(gravity: Int = G_START) = Lin(true, gravity)

// =============================================================================================
// Clickable views and dragging
// =============================================================================================

/**
 * Drags a floating window (port of the Android Dragger): pressing anywhere and moving more than a
 * few pixels moves the window (if allowed); a press without movement still clicks the button.
 */
class DragSupport(
    private val win: java.awt.Window,
    private val canDrag: () -> Boolean,
    private val onDragStart: () -> Unit = {},
    private val onDragEnd: () -> Unit = {},
    private val onBlockedDrag: () -> Unit = {},
    private val clamp: (Point) -> Point = { it },
) {
    private var down: Point? = null
    private var start = Point()
    var moved = false
        private set
    private var dragging = false
    private val slop = 4

    fun press(e: MouseEvent) {
        down = e.locationOnScreen
        start = win.location
        moved = false
        dragging = false
    }

    /** Returns true once this press has turned into a drag (the click is then cancelled). */
    fun move(e: MouseEvent): Boolean {
        val d = down ?: return false
        val dx = e.xOnScreen - d.x
        val dy = e.yOnScreen - d.y
        if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
            moved = true
            dragging = canDrag()
            if (dragging) onDragStart() else onBlockedDrag()
        }
        if (dragging) win.location = clamp(Point(start.x + dx, start.y + dy))
        return moved
    }

    fun release(): Boolean {
        val was = moved
        if (dragging) onDragEnd()
        down = null
        dragging = false
        moved = false
        return was
    }

    /** Plain components (texts, rows) inside the window forward their presses here too. */
    fun attach(root: Component) {
        if (root !is NView) {
            (root as? JComponent)?.putClientProperty("drag", this)
            val l = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) press(e) }
                override fun mouseDragged(e: MouseEvent) { move(e) }
                override fun mouseReleased(e: MouseEvent) { release() }
            }
            if (root is JComponent && root.getClientProperty("dragL") == null) {
                root.putClientProperty("dragL", l)
                root.addMouseListener(l)
                root.addMouseMotionListener(l)
            }
        } else {
            root.drag = this
        }
        if (root is Container) root.components.forEach { attach(it) }
    }
}

/** Base for every custom-drawn view: optional click / long-press, press feedback, alpha. */
abstract class NView : JComponent() {
    var onClick: (() -> Unit)? = null
        set(value) { field = value; updateCursor() }
    var onLongClick: (() -> Unit)? = null
        set(value) { field = value; updateCursor() }
    var drag: DragSupport? = null
    /** Floating menu style: the view dims to 60% while pressed (Android's Dragger feedback). */
    var pressDim = false
    var alpha = 1f
        set(value) { if (field != value) { field = value; repaint() } }

    var pressed = false
        protected set
    var hover = false
        protected set
    private var longFired = false
    private val longTimer = Timer(500) {
        if (pressed) {
            longFired = true
            pressed = false
            repaint()
            onLongClick?.invoke()
        }
    }.apply { isRepeats = false }

    val clickable get() = onClick != null || onLongClick != null

    private fun updateCursor() {
        cursor = if (clickable) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null
    }

    init {
        isOpaque = false
        val l = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                drag?.press(e)
                longFired = false
                if (clickable) {
                    pressed = true
                    repaint()
                    if (onLongClick != null) longTimer.restart()
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                val d = drag
                if (d != null && d.move(e)) {
                    cancelPress()
                } else if (d == null && pressed && !contains(e.point)) {
                    cancelPress()
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val dragged = drag?.release() ?: false
                longTimer.stop()
                val fire = pressed && !dragged && !longFired && contains(e.point)
                pressed = false
                repaint()
                if (fire) onClick?.invoke()
            }

            override fun mouseEntered(e: MouseEvent) { hover = true; if (clickable) repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; if (clickable) repaint() }
        }
        addMouseListener(l)
        addMouseMotionListener(l)
    }

    private fun cancelPress() {
        if (pressed) {
            pressed = false
            longTimer.stop()
            repaint()
        }
    }

    final override fun paintComponent(g: Graphics) {
        val g2 = (g.create() as Graphics2D).smooth()
        var a = alpha
        if (pressDim && pressed) a *= 0.6f
        if (a < 0.999f) g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a.coerceIn(0f, 1f))
        paintView(g2)
        g2.dispose()
    }

    abstract fun paintView(g: Graphics2D)

    fun lp(lp: LP) = apply { putClientProperty("lp", lp) }
}

// =============================================================================================
// Text
// =============================================================================================

/** Text that wraps to its width (TextView): glow, shadow, alignment, line spacing, padding. */
open class NText(text: String = "", font: Font = Fonts.sans(14f), color: Int = WHITE) : NView(), HeightForWidth {
    var text = text
        set(value) { if (field != value) { field = value; revalidate(); repaint() } }
    var tfont: Font = font
        set(value) { field = value; revalidate(); repaint() }
    var color = color
        set(value) { if (field != value) { field = value; repaint() } }
    var align = SwingConstants.LEFT
    var wrap = true
    var lineExtra = 0f
    /** Neon glow behind the text (Android shadow layer); sigma in px, 0 = none. */
    var glowSigma = 0f
    var glowColor = 0
    /** Dark soft shadow for text on busy backgrounds (Permission card, Clear menu). */
    var shadow = false
    var padL = 0; var padT = 0; var padR = 0; var padB = 0
    var minW = 0

    fun padding(l: Int, t: Int, r: Int, b: Int) = apply { padL = l; padT = t; padR = r; padB = b; revalidate() }

    private fun lineH() = Txt.lineHeight(tfont) + lineExtra

    override fun prefWidth(): Int {
        val w = text.split('\n').maxOfOrNull { Txt.width(tfont, it) } ?: 0f
        return max(minW, kotlin.math.ceil(w).toInt() + padL + padR + 1)
    }

    private var linesKey: Any? = null
    private var linesVal: List<String> = emptyList()

    private fun lines(w: Int): List<String> {
        val key = listOf(text, tfont, w, wrap, padL, padR)
        if (key != linesKey) {
            linesVal = if (!wrap) text.split('\n') else Txt.wrap(text, tfont, (w - padL - padR).toFloat().coerceAtLeast(1f))
            linesKey = key
        }
        return linesVal
    }

    override fun heightFor(w: Int): Int {
        val n = lines(w).size
        return kotlin.math.ceil(n * lineH() - lineExtra).toInt() + padT + padB + 1
    }

    override fun getPreferredSize(): Dimension {
        val pw = prefWidth()
        return Dimension(pw, heightFor(if (width > 0) width else pw))
    }

    /** Vertical center of the first letter, in this view's coordinates (for the warning beacon). */
    fun firstGlyphCenterY(): Float {
        val first = text.take(1).ifEmpty { "W" }
        val gv = tfont.createGlyphVector(Txt.frc, first)
        val b = gv.visualBounds
        val top = topOffset()
        return top + padT + Txt.ascent(tfont) + b.centerY.toFloat()
    }

    private fun topOffset(): Float {
        val content = heightFor(width) - padT - padB
        return max(0f, (height - padT - padB - content) / 2f)
    }

    private var cache: java.awt.image.BufferedImage? = null
    private var cacheKey: Any? = null

    override fun paintView(g: Graphics2D) {
        // Shadowed / glowing text is drawn once into an image and reused (cheap redraws under animations).
        if (!shadow && glowSigma <= 0f) { paintText(g); return }
        val sc = g.deviceScale()
        val key = listOf(text, tfont, color, width, height, sc, glowSigma, glowColor, shadow, align, padL, padT, padR, padB)
        if (key != cacheKey || cache == null) {
            val img = java.awt.image.BufferedImage(max(1, (width * sc).toInt()), max(1, (height * sc).toInt()), java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val ig = img.createGraphics().smooth()
            ig.scale(sc, sc)
            paintText(ig)
            ig.dispose()
            cache = img
            cacheKey = key
        }
        val at = g.transform
        g.scale(1 / sc, 1 / sc)
        g.drawImage(cache, 0, 0, null)
        g.transform = at
    }

    private fun paintText(g: Graphics2D) {
        val ls = lines(width)
        var y = topOffset() + padT + Txt.ascent(tfont)
        val avail = width - padL - padR
        for (l in ls) {
            val lw = Txt.width(tfont, l)
            val x = padL + when (align) {
                SwingConstants.CENTER -> (avail - lw) / 2f
                SwingConstants.RIGHT -> avail - lw
                else -> 0f
            }
            if (glowSigma > 0f) {
                val asc = Txt.ascent(tfont)
                val h = (asc + Txt.descent(tfont)).roundToInt()
                Glow.image(g, "txt-$l-${tfont.hashCode()}", kotlin.math.ceil(lw).toInt() + 1, h, glowSigma, glowColor) { mg ->
                    Txt.draw(mg, l, tfont, 0f, asc)
                }?.let { Glow.drawImage(g, it, x, y - asc, glowSigma) }
            }
            if (shadow) {
                val asc = Txt.ascent(tfont)
                val h = (asc + Txt.descent(tfont)).roundToInt()
                Glow.image(g, "sh-$l-${tfont.hashCode()}", kotlin.math.ceil(lw).toInt() + 1, h, 1.5f, argb(230, 0, 0, 0)) { mg ->
                    Txt.draw(mg, l, tfont, 0f, asc)
                }?.let { Glow.drawImage(g, it, x, y - asc, 1.5f) }
            }
            g.color = col(color)
            Txt.draw(g, l, tfont, x, y)
            y += lineH()
        }
    }
}

// =============================================================================================
// Buttons and chips
// =============================================================================================

enum class BStyle { OUTLINE, FILLED, TEXT }

/** Android Button with the app's outline / filled styles (NeonUi.outline / filled). */
class NButton(text: String, var t: NeonColors, var style: BStyle = BStyle.OUTLINE) : NView(), HeightForWidth {
    var text = text
        set(value) { if (field != value) { field = value; revalidate(); repaint() } }
    var tfont: Font = Fonts.mono(15f)
    var radius = 12f
    var minH = 48
    var padH = 16
    var icon: Icon? = null
    var iconSize = 16
    /** Override text color (TEXT style uses body or main). */
    var textColor: Int? = null
    var wrapText = false

    fun styled(s: BStyle, r: Float = if (s == BStyle.FILLED) 14f else 12f) = apply { style = s; radius = r; repaint() }

    private fun fg() = textColor ?: when (style) {
        BStyle.FILLED -> t.onMain
        else -> t.main
    }

    private fun contentW(): Float = Txt.width(tfont, text) + (if (icon != null) iconSize + 5f else 0f)

    override fun prefWidth() = kotlin.math.ceil(contentW()).toInt() + 2 * padH

    override fun heightFor(w: Int): Int {
        if (!wrapText) return max(minH, (Txt.lineHeight(tfont) + 16).toInt())
        val n = Txt.wrap(text, tfont, (w - 2 * padH).toFloat()).size
        return max(minH, (n * Txt.lineHeight(tfont) + 16).toInt())
    }

    override fun getPreferredSize() = Dimension(prefWidth(), heightFor(prefWidth()))

    override fun paintView(g: Graphics2D) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(radius, h / 2f)
        val shape = RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 2 * r, 2 * r)
        when (style) {
            BStyle.OUTLINE -> {
                g.color = col(t.field); g.fill(shape)
                overlay(g, shape, t.main)
                g.stroke = BasicStroke(1f); g.color = col(t.main); g.draw(shape)
            }
            BStyle.FILLED -> {
                g.color = col(t.fill); g.fill(shape)
                overlay(g, shape, BLACK)
            }
            BStyle.TEXT -> overlay(g, shape, t.main)
        }
        val c = fg()
        val lines = if (wrapText) Txt.wrap(text, tfont, w - 2 * padH) else listOf(text)
        val lh = Txt.lineHeight(tfont)
        var cy = h / 2f - (lines.size - 1) * lh / 2f
        for (l in lines) {
            val tw = Txt.width(tfont, l) + (if (icon != null) iconSize + 5f else 0f)
            var x = (w - tw) / 2f
            icon?.let {
                Icons.paint(g, it, c, x + iconSize / 2f, cy, iconSize - 2f)
                x += iconSize + 5f
            }
            g.color = col(c)
            val lm = tfont.getLineMetrics("Ag", Txt.frc)
            Txt.draw(g, l, tfont, x, cy + (lm.ascent - lm.descent) / 2f)
            cy += lh
        }
    }

    private fun overlay(g: Graphics2D, shape: java.awt.Shape, c: Int) {
        val a = when {
            pressed -> 0x55
            hover && clickable -> 0x22
            else -> 0
        }
        if (a == 0) return
        g.color = col(argb(a, redOf(c), greenOf(c), blueOf(c)))
        g.fill(shape)
    }
}

/** A chip: filled when selected, outlined otherwise (the app's `select`). */
fun chip(label: String, t: NeonColors, selected: Boolean, size: Float = 12f, onClick: () -> Unit): NButton =
    NButton(label, t, if (selected) BStyle.FILLED else BStyle.OUTLINE).apply {
        radius = 12f
        tfont = Fonts.mono(size)
        minH = 0
        padH = 6
        this.onClick = onClick
    }

fun NButton.select(on: Boolean) {
    style = if (on) BStyle.FILLED else BStyle.OUTLINE
    radius = 12f
    repaint()
}

// =============================================================================================
// Icon view, switch, slider, countdown, beacon, swatch, crosshair preview
// =============================================================================================

/** IconView: a line icon with optional round background. */
class NIcon(kind: Icon, color: Int) : NView() {
    var kind = kind
        set(value) { if (field != value) { field = value; repaint() } }
    var color = color
        set(value) { if (field != value) { field = value; repaint() } }
    var glow = false
        set(value) { if (field != value) { field = value; repaint() } }
    var halo = false
    var rotation = 0f
    var padX = 0
    var padY = 0
    /** Round background: fill and outline colors (0 alpha = none), outline width. */
    var circleFill = 0
    var circleStroke = 0
    var circleStrokeW = 1f

    fun circle(fill: Int, stroke: Int, strokeW: Float = 1f) = apply {
        circleFill = fill; circleStroke = stroke; circleStrokeW = strokeW; repaint()
    }

    override fun getPreferredSize() = Dimension(24, 24)

    override fun paintView(g: Graphics2D) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (circleFill != 0 || circleStroke != 0) {
            val sw = circleStrokeW
            val d = min(w, h) - sw
            val e = Ellipse2D.Float((w - d) / 2f, (h - d) / 2f, d, d)
            if (circleFill != 0) { g.color = col(circleFill); g.fill(e) }
            if (circleStroke != 0) { g.stroke = BasicStroke(sw); g.color = col(circleStroke); g.draw(e) }
        }
        val s = min(w - 2 * padX, h - 2 * padY)
        Icons.paint(g, kind, color, w / 2f, h / 2f, s, glow = glow, halo = halo, rotation = rotation)
    }
}

/** Android Switch: label on the left, switch on the right. */
class NSwitch(text: String, var t: NeonColors, var onChange: (Boolean) -> Unit) : NView(), HeightForWidth {
    var text = text
        set(value) { if (field != value) { field = value; revalidate(); repaint() } }
    var checked = false
        set(value) { if (field != value) { field = value; repaint() } }
    var tfont: Font = Fonts.mono(15f)

    init {
        onClick = { checked = !checked; onChange(checked) }
    }

    override fun prefWidth() = kotlin.math.ceil(Txt.width(tfont, text)).toInt() + 60
    override fun heightFor(w: Int) = max(48, (Txt.wrap(text, tfont, (w - 60).toFloat()).size * Txt.lineHeight(tfont) + 12).toInt())
    override fun getPreferredSize() = Dimension(prefWidth(), 48)

    override fun paintView(g: Graphics2D) {
        val h = height.toFloat()
        val lines = Txt.wrap(text, tfont, (width - 60).toFloat())
        val lh = Txt.lineHeight(tfont)
        var y = h / 2f - lines.size * lh / 2f + Txt.ascent(tfont)
        g.color = col(t.main)
        lines.forEach { Txt.draw(g, it, tfont, 0f, y); y += lh }
        val tw = 34f
        val th = 14f
        val tx = width - tw - 4f
        val ty = (h - th) / 2f
        val offThumb = if (t.isLight) hex("#B8BEC4") else hex("#555555")
        val offTrack = if (t.isLight) hex("#DDE1E5") else hex("#222222")
        g.color = col(if (checked) t.dim else offTrack)
        g.fill(RoundRectangle2D.Float(tx, ty, tw, th, th, th))
        val d = 20f
        val cx = if (checked) tx + tw - d / 2f + 2f else tx + d / 2f - 2f
        if (hover) {
            g.color = col(argb(40, redOf(t.main), greenOf(t.main), blueOf(t.main)))
            g.fill(Ellipse2D.Float(cx - 18f, h / 2f - 18f, 36f, 36f))
        }
        g.color = Color(0, 0, 0, 70)
        g.fill(Ellipse2D.Float(cx - d / 2f, h / 2f - d / 2f + 1f, d, d))
        g.color = col(if (checked) t.main else offThumb)
        g.fill(Ellipse2D.Float(cx - d / 2f, h / 2f - d / 2f, d, d))
    }
}

/** Android SeekBar in the theme colors. */
class NSlider(var t: NeonColors, var max: Int) : NView() {
    var progress = 0
        set(value) { val v = value.coerceIn(0, max); if (field != v) { field = v; repaint() } }
    var onUser: (Int) -> Unit = {}
    var onStart: () -> Unit = {}
    var onStop: () -> Unit = {}
    var tracking = false
        private set

    init {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val l = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                tracking = true
                onStart()
                setFrom(e.x)
            }
            override fun mouseDragged(e: MouseEvent) { if (tracking) setFrom(e.x) }
            override fun mouseReleased(e: MouseEvent) {
                if (!tracking) return
                tracking = false
                repaint()
                onStop()
            }
        }
        addMouseListener(l)
        addMouseMotionListener(l)
    }

    private val inset = 12f

    private fun setFrom(x: Int) {
        val f = ((x - inset) / (width - 2 * inset)).coerceIn(0f, 1f)
        val p = (f * max).roundToInt()
        if (p != progress) {
            progress = p
            onUser(p)
        }
        repaint()
    }

    override fun getPreferredSize() = Dimension(200, 32)

    override fun paintView(g: Graphics2D) {
        val cy = height / 2f
        val x0 = inset
        val x1 = width - inset
        val f = if (max <= 0) 0f else progress / max.toFloat()
        val px = x0 + (x1 - x0) * f
        g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = col(t.faint)
        g.draw(java.awt.geom.Line2D.Float(x0, cy, x1, cy))
        g.color = col(t.main)
        g.draw(java.awt.geom.Line2D.Float(x0, cy, px, cy))
        if (tracking || hover) {
            g.color = col(argb(50, redOf(t.main), greenOf(t.main), blueOf(t.main)))
            g.fill(Ellipse2D.Float(px - 14f, cy - 14f, 28f, 28f))
        }
        val r = if (tracking) 9f else 8f
        g.color = col(t.main)
        g.fill(Ellipse2D.Float(px - r, cy - r, 2 * r, 2 * r))
    }
}

/** The countdown box (AnnunciatorView). */
class NCountdown(var textSize: Float, color: Int) : NView() {
    var text = ""
        set(value) {
            if (field == value) return
            val relayout = Annunciator.template(value) != Annunciator.template(field)
            field = value
            if (relayout) revalidate()
            repaint()
        }
    var color = color
        set(value) { if (field != value) { field = value; repaint() } }

    override fun getPreferredSize() = Annunciator.size(text, textSize)
    override fun paintView(g: Graphics2D) = Annunciator.paint(g, width, height, text, textSize, color)
}

/**
 * Something that animates only while on screen: calls [frame] and reschedules itself after the
 * delay it returns (0 = next frame, -1 = stop), like Android's postInvalidateDelayed.
 */
class Animator(private val c: JComponent, private val delay: () -> Long) {
    private val timer = Timer(16) { c.repaint() }.apply { isRepeats = false }

    init {
        c.addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                if (c.isShowing) c.repaint() else timer.stop()
            }
        }
    }

    /** Call at the end of painting. */
    fun scheduleNext() {
        if (!c.isShowing) { timer.stop(); return }
        val w = (SwingUtilities.getWindowAncestor(c) as? java.awt.Frame)
        if (w != null && w.extendedState and java.awt.Frame.ICONIFIED != 0) { timer.stop(); return }
        val d = delay()
        when {
            d == 0L -> { timer.initialDelay = 16; timer.restart() }
            d > 0L -> { timer.initialDelay = d.toInt().coerceAtLeast(1); timer.restart() }
            else -> timer.stop()
        }
    }

    fun stop() = timer.stop()
}

/** The small (half-size) red beacon lamp beside "Warning": always flashing while on screen. */
class NBeacon(private val alignTo: NText?) : NView() {
    private val lights = Lights(0.5f).apply { refreshMotionSetting() }
    private val anim = Animator(this) { lights.beaconFrameDelay(System.currentTimeMillis()) }

    override fun getPreferredSize() = Dimension(10, 20)

    override fun paintView(g: Graphics2D) {
        val y = alignTo?.let { tv ->
            if (tv.parent == null || parent == null) null
            else SwingUtilities.convertPoint(tv, 0, tv.firstGlyphCenterY().roundToInt(), this).y.toFloat()
        } ?: (height / 2f)
        lights.drawLamp(g, width / 2f, y, System.currentTimeMillis())
        anim.scheduleNext()
    }
}

/** Color swatch circle (selected: ring and a little bigger). */
class NSwatch(val color: Int) : NView() {
    var selected = false
        set(value) { if (field != value) { field = value; repaint() } }
    var ring = WHITE

    override fun getPreferredSize() = Dimension(40, 40)

    override fun paintView(g: Graphics2D) {
        val s = if (selected) 1.15f else 1f
        val d = min(width, height) / 1.15f * s
        val x = (width - d) / 2f
        val y = (height - d) / 2f
        g.color = col(color)
        g.fill(Ellipse2D.Float(x, y, d, d))
        if (selected) {
            g.stroke = BasicStroke(1.5f)
            g.color = col(ring)
            g.draw(Ellipse2D.Float(x + 0.75f, y + 0.75f, d - 1.5f, d - 1.5f))
        } else if (hover) {
            g.color = Color(255, 255, 255, 40)
            g.fill(Ellipse2D.Float(x, y, d, d))
        }
    }
}

/** Crosshair preview in Appearance. */
class NCrosshair : NView() {
    var ringPx = 32
        set(value) { if (field != value) { field = value; revalidate(); repaint() } }
    var color = WHITE
        set(value) { if (field != value) { field = value; repaint() } }

    override fun getPreferredSize(): Dimension {
        val s = Crosshair.windowSizeFor(ringPx)
        return Dimension(s, s)
    }

    override fun paintView(g: Graphics2D) {
        val s = Crosshair.windowSizeFor(ringPx)
        val g2 = g.create() as Graphics2D
        g2.translate((width - s) / 2, (height - s) / 2)
        Crosshair.paint(g2, s, s, ringPx, color, false, null)
        g2.dispose()
    }
}

/** NumberPicker: ▲ value ▼, mouse wheel, wraps around. */
class NPicker(val maxValue: Int, start: Int, var t: NeonColors) : NView() {
    var value = start.coerceIn(0, maxValue)
        set(v) { field = v; repaint() }
    var displayed: Array<String>? = null

    init {
        addMouseWheelListener { e -> step(if (e.wheelRotation > 0) 1 else -1) }
        val l = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                when {
                    e.y < height / 3 -> step(-1)
                    e.y > height * 2 / 3 -> step(1)
                }
            }
        }
        addMouseListener(l)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun step(d: Int) {
        value = (value + d + maxValue + 1) % (maxValue + 1)
    }

    private fun label(v: Int) = displayed?.getOrNull(v) ?: String.format("%02d", v)

    override fun getPreferredSize() = Dimension(64, 132)

    override fun paintView(g: Graphics2D) {
        val w = width.toFloat()
        val h = height.toFloat()
        Icons.paint(g, Icon.COLLAPSE, t.dim, w / 2f, h / 6f, 22f)
        Icons.paint(g, Icon.EXPAND, t.dim, w / 2f, h * 5f / 6f, 22f)
        val f = Fonts.mono(24f, bold = true)
        val prev = (value - 1 + maxValue + 1) % (maxValue + 1)
        val next = (value + 1) % (maxValue + 1)
        g.color = col(t.dim)
        Txt.drawCentered(g, label(prev), Fonts.mono(15f), w / 2f, h / 2f - 32f)
        Txt.drawCentered(g, label(next), Fonts.mono(15f), w / 2f, h / 2f + 32f)
        g.stroke = BasicStroke(1f)
        g.color = col(t.main)
        g.drawLine(6, (h / 2f - 20f).toInt(), (w - 6).toInt(), (h / 2f - 20f).toInt())
        g.drawLine(6, (h / 2f + 20f).toInt(), (w - 6).toInt(), (h / 2f + 20f).toInt())
        Txt.drawCentered(g, label(value), f, w / 2f, h / 2f)
    }
}

/** EditText in the theme colors. */
class NField(t: NeonColors, text: String) : JTextField(text) {
    private val line = t.main

    init {
        isOpaque = false
        border = EmptyBorder(8, 2, 10, 2)
        font = Fonts.sans(16f)
        foreground = col(t.main)
        caretColor = col(t.main)
        selectionColor = col(t.main, 0.35f)
        selectedTextColor = col(t.main)
    }

    override fun getPreferredSize() = Dimension(240, 42)

    override fun paintComponent(g: Graphics) {
        val g2 = (g as Graphics2D)
        g2.smooth()
        super.paintComponent(g)
        g2.color = col(line)
        g2.fillRect(0, height - 2, width, 2)
    }
}

/** A scroll pane with a thin neon scrollbar and no background. */
fun neonScroll(view: Component, t: NeonColors): JScrollPane = JScrollPane(view).apply {
    border = null
    viewportBorder = null
    isOpaque = false
    viewport.isOpaque = false
    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    verticalScrollBar.unitIncrement = 24
    verticalScrollBar.isOpaque = false
    verticalScrollBar.preferredSize = Dimension(8, 0)
    verticalScrollBar.setUI(object : BasicScrollBarUI() {
        override fun configureScrollBarColors() {}
        override fun createDecreaseButton(o: Int) = zero()
        override fun createIncreaseButton(o: Int) = zero()
        private fun zero() = javax.swing.JButton().apply { preferredSize = Dimension(0, 0); minimumSize = Dimension(0, 0); maximumSize = Dimension(0, 0) }
        override fun paintTrack(g: Graphics, c: JComponent, r: Rectangle) {}
        override fun paintThumb(g: Graphics, c: JComponent, r: Rectangle) {
            if (r.isEmpty) return
            val g2 = (g.create() as Graphics2D).smooth()
            g2.color = col(t.main, if (isDragging || isThumbRollover) 0.7f else 0.4f)
            g2.fill(RoundRectangle2D.Float(r.x + 2f, r.y + 2f, r.width - 4f, r.height - 4f, 4f, 4f))
            g2.dispose()
        }
    })
}

/** Insets helper. */
fun Insets.h() = left + right
