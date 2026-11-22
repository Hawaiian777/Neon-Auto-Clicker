package com.neon.autoclicker.desktop

import com.neon.autoclicker.AirbusLights
import com.neon.autoclicker.BLACK
import com.neon.autoclicker.WHITE
import com.neon.autoclicker.alphaOf
import com.neon.autoclicker.argb
import com.neon.autoclicker.blueOf
import com.neon.autoclicker.greenOf
import com.neon.autoclicker.luminance
import com.neon.autoclicker.mix
import com.neon.autoclicker.redOf
import com.neon.autoclicker.withAlpha
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** ARGB int (shared color math) to a Java color. */
fun col(c: Int): Color = Color(redOf(c), greenOf(c), blueOf(c), alphaOf(c))
fun col(c: Int, a: Float): Color = col(withAlpha(c, a * alphaOf(c) / 255f))

fun Graphics2D.smooth(): Graphics2D {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    return this
}

/** Device pixels per Java pixel on this graphics (1.5 at 150% Windows scaling). */
fun Graphics2D.deviceScale(): Double = max(1.0, abs(transform.scaleX))

// ---------------------------------------------------------------------------------------------
// Fonts and text
// ---------------------------------------------------------------------------------------------

/**
 * Android uses "monospace" (bold for values) and the system sans font. On Windows these are
 * Consolas and Segoe UI. Characters a font lacks (✓ ✗ ↩ ⏸ …) are drawn from fallback fonts.
 */
object Fonts {
    private val families: Set<String> = try {
        GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
    } catch (t: Throwable) {
        emptySet()
    }
    private fun pick(vararg names: String, def: String) = names.firstOrNull { it in families } ?: def

    val MONO = pick("Consolas", "Cascadia Mono", "DejaVu Sans Mono", "Liberation Mono", def = Font.MONOSPACED)
    val SANS = pick("Segoe UI", "Inter", "DejaVu Sans", def = Font.SANS_SERIF)
    private val FALLBACKS = listOf("Segoe UI Symbol", "Segoe UI Emoji", "Segoe UI", "DejaVu Sans", "Noto Sans Symbols2", Font.DIALOG)
        .filter { it in families || it == Font.DIALOG }

    private val cache = HashMap<String, Font>()

    fun mono(size: Float, bold: Boolean = false, tracking: Float = 0f) = get(MONO, size, bold, tracking)
    fun sans(size: Float, bold: Boolean = false, tracking: Float = 0f) = get(SANS, size, bold, tracking)

    private fun get(family: String, size: Float, bold: Boolean, tracking: Float): Font {
        val key = "$family|$size|$bold|$tracking"
        return cache.getOrPut(key) {
            var f = Font(family, if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)
            if (tracking != 0f) f = f.deriveFont(mapOf(TextAttribute.TRACKING to tracking))
            f
        }
    }

    private val fallbackCache = HashMap<Font, List<Font>>()
    fun fallbacks(f: Font): List<Font> = fallbackCache.getOrPut(f) {
        FALLBACKS.map { Font(it, f.style, 1).deriveFont(f.size2D) }
    }
}

/** Text drawing with fallback runs, measuring and word wrapping. */
object Txt {
    val frc = FontRenderContext(null, true, true)

    private class Run(val text: String, val font: Font)

    private fun runs(s: String, f: Font): List<Run> {
        if (f.canDisplayUpTo(s) == -1) return listOf(Run(s, f))
        val out = mutableListOf<Run>()
        val sb = StringBuilder()
        var cur: Font? = null
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            val use = if (f.canDisplay(cp) || cp == ' '.code) f else Fonts.fallbacks(f).firstOrNull { it.canDisplay(cp) } ?: f
            if (use != cur && sb.isNotEmpty()) {
                out += Run(sb.toString(), cur!!)
                sb.setLength(0)
            }
            cur = use
            sb.appendCodePoint(cp)
            i += n
        }
        if (sb.isNotEmpty()) out += Run(sb.toString(), cur!!)
        return out
    }

    private val widths = object : LinkedHashMap<Pair<Font, String>, Float>(512, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Pair<Font, String>, Float>?) = size > 4000
    }

    /** Text width (memoized: the same labels are measured on every repaint). */
    fun width(f: Font, s: String): Float = widths.getOrPut(f to s) {
        runs(s, f).sumOf { it.font.getStringBounds(it.text, frc).width }.toFloat()
    }

    fun ascent(f: Font): Float = f.getLineMetrics("Ag", frc).ascent
    fun descent(f: Font): Float = f.getLineMetrics("Ag", frc).descent
    fun lineHeight(f: Font): Float = f.getLineMetrics("Ag", frc).let { it.ascent + it.descent + it.leading }

    /** Draws [s] with its baseline at [y]. */
    fun draw(g: Graphics2D, s: String, f: Font, x: Float, y: Float) {
        var cx = x
        for (r in runs(s, f)) {
            g.font = r.font
            g.drawString(r.text, cx, y)
            cx += r.font.getStringBounds(r.text, frc).width.toFloat()
        }
    }

    /** Draws [s] centered on ([cx], [cy]) (vertical center of the cap height, like Android's ascent/descent trick). */
    fun drawCentered(g: Graphics2D, s: String, f: Font, cx: Float, cy: Float) {
        val w = width(f, s)
        val lm = f.getLineMetrics("Ag", frc)
        draw(g, s, f, cx - w / 2f, cy + (lm.ascent - lm.descent) / 2f)
    }

    /** Lines of [s] wrapped to [maxW] (paragraphs split on '\n'). */
    fun wrap(s: String, f: Font, maxW: Float): List<String> {
        val out = mutableListOf<String>()
        for (para in s.split('\n')) {
            if (para.isEmpty()) { out += ""; continue }
            val words = para.split(' ')
            val line = StringBuilder()
            for (w in words) {
                val cand = if (line.isEmpty()) w else "$line $w"
                if (line.isNotEmpty() && width(f, cand) > maxW) {
                    out += line.toString()
                    line.setLength(0)
                    line.append(w)
                } else {
                    line.setLength(0)
                    line.append(cand)
                }
            }
            out += line.toString()
        }
        return out
    }
}

// ---------------------------------------------------------------------------------------------
// Glows (Java2D has no blur, so: radial gradients for round lights, blurred masks for the rest)
// ---------------------------------------------------------------------------------------------

object Glow {
    private val cache = object : LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, BufferedImage>?) = size > 96
    }

    /** Normal CDF. */
    private fun phi(x: Double): Double {
        // Abramowitz–Stegun erf approximation, plenty for a glow
        val t = 1.0 / (1.0 + 0.3275911 * abs(x) / sqrt(2.0))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t *
            exp(-x * x / 2.0)
        return if (x >= 0) 0.5 * (1 + y) else 0.5 * (1 - y)
    }

    /**
     * A disc of radius [r] blurred with a gaussian of [sigma] (CSS box-shadow blur B = sigma B/2),
     * in [color] at [alpha]. Matches Android's BlurMaskFilter circles closely.
     */
    fun disc(g: Graphics2D, cx: Float, cy: Float, r: Float, sigma: Float, color: Int, alpha: Float) {
        if (alpha <= 0.003f || r <= 0f) return
        if (sigma < 0.25f) {
            g.color = col(color, alpha)
            g.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
            return
        }
        val outer = r + 3f * sigma
        val n = 14
        val fr = FloatArray(n)
        val cs = arrayOfNulls<Color>(n)
        val peak = 1.0 - exp(-(r * r) / (2.0 * sigma * sigma)) // a small disc never reaches full strength
        for (i in 0 until n) {
            val f = i / (n - 1f)
            val d = f * outer
            val a = min(peak, phi(((r - d) / sigma).toDouble())) * alpha
            fr[i] = f
            cs[i] = col(color, a.toFloat().coerceIn(0f, 1f))
        }
        val p = RadialGradientPaint(Point2D.Float(cx, cy), outer, fr, cs, MultipleGradientPaint.CycleMethod.NO_CYCLE)
        val old = g.paint
        g.paint = p
        g.fill(Ellipse2D.Float(cx - outer, cy - outer, 2 * outer, 2 * outer))
        g.paint = old
    }

    /**
     * A soft glow around whatever [draw] paints (text, icon lines, the crosshair), in [color].
     * Rendered once at device resolution and cached under [key].
     * Draw the result at (x - pad, y - pad) where pad = [padFor] (sigma).
     */
    fun image(g: Graphics2D, key: String, w: Int, h: Int, sigma: Float, color: Int, draw: (Graphics2D) -> Unit): BufferedImage? {
        if (w <= 0 || h <= 0) return null
        val sc = g.deviceScale()
        val k = "$key|$w|$h|$sigma|$color|$sc"
        cache[k]?.let { return it }
        val pad = padFor(sigma)
        val pw = ceil((w + 2 * pad) * sc).toInt()
        val ph = ceil((h + 2 * pad) * sc).toInt()
        if (pw * ph > 4_000_000) return null
        val mask = BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB)
        val mg = mask.createGraphics().smooth()
        mg.scale(sc, sc)
        mg.translate(pad.toDouble(), pad.toDouble())
        mg.color = Color.WHITE
        draw(mg)
        mg.dispose()
        val a = IntArray(pw * ph)
        mask.getRGB(0, 0, pw, ph, a, 0, pw)
        val alpha = FloatArray(pw * ph) { (a[it] ushr 24) / 255f }
        blur(alpha, pw, ph, (sigma * sc).toFloat())
        val r = redOf(color)
        val gr = greenOf(color)
        val b = blueOf(color)
        val ca = alphaOf(color) / 255f
        for (i in a.indices) {
            val v = (alpha[i] * ca * 255f).roundToInt().coerceIn(0, 255)
            a[i] = (v shl 24) or (r shl 16) or (gr shl 8) or b
        }
        val out = BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, pw, ph, a, 0, pw)
        cache[k] = out
        return out
    }

    fun padFor(sigma: Float): Int = ceil(sigma * 3f).toInt() + 1

    /** Draws a cached glow image so it lines up with content drawn at (x, y). */
    fun drawImage(g: Graphics2D, img: BufferedImage, x: Float, y: Float, sigma: Float, alpha: Float = 1f) {
        val pad = padFor(sigma)
        val sc = g.deviceScale()
        val old = g.composite
        if (alpha < 1f) g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
        val at = g.transform
        g.translate((x - pad).toDouble(), (y - pad).toDouble())
        g.scale(1.0 / sc, 1.0 / sc)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(img, 0, 0, null)
        g.transform = at
        g.composite = old
    }

    /** Gaussian blur approximated by three box blurs. */
    private fun blur(a: FloatArray, w: Int, h: Int, sigma: Float) {
        if (sigma < 0.3f) return
        val boxes = boxesFor(sigma, 3)
        val tmp = FloatArray(a.size)
        for (r in boxes) {
            boxH(a, tmp, w, h, r)
            boxV(tmp, a, w, h, r)
        }
    }

    private fun boxesFor(sigma: Float, n: Int): IntArray {
        val wIdeal = sqrt(12.0 * sigma * sigma / n + 1)
        var wl = wIdeal.toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4)
        val m = mIdeal.roundToInt()
        return IntArray(n) { ((if (it < m) wl else wu) - 1) / 2 }
    }

    private fun boxH(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (x in -r..r) acc += src[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = acc * inv
                acc += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
    }

    private fun boxV(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        for (x in 0 until w) {
            var acc = 0f
            for (y in -r..r) acc += src[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = acc * inv
                acc += src[(y + r + 1).coerceAtMost(h - 1) * w + x] - src[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Icons (port of the Android IconView, same 24 x 24 drawings)
// ---------------------------------------------------------------------------------------------

enum class Icon { PLAY, PAUSE, RESET, HOME, EYE, EYE_OFF, GEAR, COLLAPSE, EXPAND, CLOSE, PLUS, MINUS, LOCK, UNLOCK, GRIP }

object Icons {
    /**
     * Draws [kind] fitted in the square of side [s] centered on ([cx], [cy]).
     * [rotation] in degrees (clockwise, as Android's View.rotation).
     */
    fun paint(g0: Graphics2D, kind: Icon, color: Int, cx: Float, cy: Float, s: Float,
              glow: Boolean = false, halo: Boolean = false, rotation: Float = 0f) {
        if (s <= 0f) return
        val g = g0.create() as Graphics2D
        g.smooth()
        if (rotation != 0f) g.rotate(Math.toRadians(rotation.toDouble()), cx.toDouble(), cy.toDouble())
        val u = s / 24f
        if (halo) {
            val h = argb(185, 0, 0, 0)
            render(g, kind, h, cx, cy, u, 4.4f * u, haloFill = 2.4f * u)
        }
        if (glow) {
            val sigma = 2f * u
            val img = Glow.image(g, "icon-$kind", s.roundToInt(), s.roundToInt(), sigma, color) { mg ->
                render(mg, kind, WHITE, s / 2f, s / 2f, u, 2f * u)
            }
            if (img != null) Glow.drawImage(g, img, cx - s / 2f, cy - s / 2f, sigma)
        }
        render(g, kind, color, cx, cy, u, 2f * u)
        g.dispose()
    }

    /** [haloFill] > 0: fills are also outlined by that width (the dark halo under Clear-menu icons). */
    private fun render(g: Graphics2D, kind: Icon, color: Int, cx: Float, cy: Float, u: Float, sw: Float, haloFill: Float = 0f) {
        fun px(v: Float) = cx + (v - 12f) * u
        fun py(v: Float) = cy + (v - 12f) * u
        val c = col(color)
        g.color = c
        val stroke = BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val haloStroke = if (haloFill > 0f) BasicStroke(haloFill, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND) else null
        fun drawS(sh: java.awt.Shape) { g.stroke = stroke; g.draw(sh) }
        fun fillS(sh: java.awt.Shape) {
            g.fill(sh)
            if (haloStroke != null) { g.stroke = haloStroke; g.draw(sh) }
        }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawS(Line2D.Float(x1, y1, x2, y2))
        fun circle(x: Float, y: Float, r: Float) = Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r)
        val path = Path2D.Float()
        when (kind) {
            Icon.PLAY -> {
                path.moveTo(px(8f), py(5f)); path.lineTo(px(19f), py(12f)); path.lineTo(px(8f), py(19f)); path.closePath()
                fillS(path)
            }
            Icon.HOME -> {
                path.moveTo(px(3f), py(11f)); path.lineTo(px(12f), py(3.5f)); path.lineTo(px(21f), py(11f))
                path.moveTo(px(5.5f), py(9.5f)); path.lineTo(px(5.5f), py(20f)); path.lineTo(px(18.5f), py(20f)); path.lineTo(px(18.5f), py(9.5f))
                path.moveTo(px(10f), py(20f)); path.lineTo(px(10f), py(14f)); path.lineTo(px(14f), py(14f)); path.lineTo(px(14f), py(20f))
                drawS(path)
            }
            Icon.EYE, Icon.EYE_OFF -> {
                path.moveTo(px(2f), py(12f)); path.quadTo(px(12f), py(3f), px(22f), py(12f)); path.quadTo(px(12f), py(21f), px(2f), py(12f)); path.closePath()
                drawS(path)
                fillS(circle(px(12f), py(12f), 3f * u))
                if (kind == Icon.EYE_OFF) line(px(4f), py(4f), px(20f), py(20f))
            }
            Icon.GEAR -> {
                drawS(circle(cx, cy, 5.5f * u))
                drawS(circle(cx, cy, 2f * u))
                val thick = BasicStroke(sw * 1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.stroke = thick
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4.0
                    val co = cos(a).toFloat()
                    val sn = sin(a).toFloat()
                    g.draw(Line2D.Float(cx + co * 7f * u, cy + sn * 7f * u, cx + co * 9.5f * u, cy + sn * 9.5f * u))
                }
            }
            Icon.COLLAPSE -> {
                path.moveTo(px(6f), py(15f)); path.lineTo(px(12f), py(9f)); path.lineTo(px(18f), py(15f)); drawS(path)
            }
            Icon.EXPAND -> {
                path.moveTo(px(6f), py(9f)); path.lineTo(px(12f), py(15f)); path.lineTo(px(18f), py(9f)); drawS(path)
            }
            Icon.CLOSE -> {
                line(px(6f), py(6f), px(18f), py(18f)); line(px(18f), py(6f), px(6f), py(18f))
            }
            Icon.PLUS -> {
                line(px(5f), py(12f), px(19f), py(12f)); line(px(12f), py(5f), px(12f), py(19f))
            }
            Icon.MINUS -> line(px(5f), py(12f), px(19f), py(12f))
            Icon.PAUSE -> {
                fillS(RoundRectangle2D.Float(px(6.5f), py(5.5f), 4f * u, 13f * u, 2.4f * u, 2.4f * u))
                fillS(RoundRectangle2D.Float(px(13.5f), py(5.5f), 4f * u, 13f * u, 2.4f * u, 2.4f * u))
            }
            Icon.RESET -> {
                // circular arrow, clockwise, ending top-right with an arrowhead
                drawS(Arc2D.Float(px(5f), py(5f), 14f * u, 14f * u, -40f, -280f, Arc2D.OPEN))
                val a = Math.toRadians(320.0)
                val ex = cx + (7f * cos(a)).toFloat() * u
                val ey = cy + (7f * sin(a)).toFloat() * u
                val dx = (-sin(a)).toFloat()
                val dy = cos(a).toFloat()
                path.moveTo(ex + dx * 3.2f * u, ey + dy * 3.2f * u)
                path.lineTo(ex - dy * 2.8f * u, ey + dx * 2.8f * u)
                path.lineTo(ex + dy * 2.8f * u, ey - dx * 2.8f * u)
                path.closePath()
                fillS(path)
            }
            Icon.LOCK, Icon.UNLOCK -> {
                drawS(RoundRectangle2D.Float(px(5.5f), py(11f), 13f * u, 9.5f * u, 4f * u, 4f * u))
                if (kind == Icon.LOCK) {
                    path.moveTo(px(8.5f), py(11f)); path.lineTo(px(8.5f), py(7.5f))
                    path.append(Arc2D.Float(px(8.5f), py(4f), 7f * u, 7f * u, 180f, -180f, Arc2D.OPEN), true)
                    path.lineTo(px(15.5f), py(11f))
                } else {
                    path.moveTo(px(11.5f), py(11f)); path.lineTo(px(11.5f), py(6f))
                    path.append(Arc2D.Float(px(11.5f), py(2.5f), 7f * u, 7f * u, 180f, -180f, Arc2D.OPEN), true)
                    path.lineTo(px(18.5f), py(7.5f))
                }
                drawS(path)
                fillS(circle(px(12f), py(15.5f), 1.3f * u))
            }
            Icon.GRIP -> {
                for (r in 0 until 3) for (cc in 0 until 2) fillS(circle(px(9.5f + cc * 5f), py(6.5f + r * 5.5f), 1.5f * u))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Crosshair (port of TargetView)
// ---------------------------------------------------------------------------------------------

object Crosshair {
    /** Window size for a ring of [ringPx]: room for the ticks and glow, and never smaller than a fingertip. */
    fun windowSizeFor(ringPx: Int): Int {
        val pad = ringPx * 0.15f + 12f
        return max(ringPx + 2 * pad, 56f).toInt()
    }

    fun paint(g0: Graphics2D, w: Int, h: Int, ringPx: Int, color: Int, active: Boolean, label: String?, dot: Boolean = true) {
        val g = g0.create() as Graphics2D
        g.smooth()
        val cx = w / 2f
        val cy = h / 2f
        val r = if (ringPx > 0) ringPx / 2f else min(cx, cy) - 8f
        if (r <= 0f) return
        val sw = (r * 0.08f).coerceIn(1.5f, 3f)
        val gap = r * 0.35f
        val ext = r + max(3f, r * 0.12f)
        val stroke = BasicStroke(sw)

        fun lines(gg: Graphics2D, ox: Float, oy: Float) {
            gg.stroke = stroke
            gg.draw(Ellipse2D.Float(ox - r, oy - r, 2 * r, 2 * r))
            gg.draw(Line2D.Float(ox, oy - ext, ox, oy - gap))
            gg.draw(Line2D.Float(ox, oy + gap, ox, oy + ext))
            gg.draw(Line2D.Float(ox - ext, oy, ox - gap, oy))
            gg.draw(Line2D.Float(ox + gap, oy, ox + ext, oy))
        }

        // fill (dark when idle, tinted while clicking)
        g.color = if (active) col(argb(70, redOf(color), greenOf(color), blueOf(color))) else col(argb(110, 0, 0, 0))
        g.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
        // glow (Android: shadow 10 dp active, 5 dp idle)
        val sigma = if (active) 5f else 2.5f
        val size = (2 * ext + 2).toInt()
        Glow.image(g, "xhair-$ringPx", size, size, sigma, color) { mg -> lines(mg, size / 2f, size / 2f) }?.let {
            Glow.drawImage(g, it, cx - size / 2f, cy - size / 2f, sigma)
        }
        g.color = col(color)
        lines(g, cx, cy)
        if (dot) {
            val dr = (r * 0.1f).coerceIn(1.5f, 3f)
            g.fill(Ellipse2D.Float(cx - dr, cy - dr, 2 * dr, 2 * dr))
        }

        if (label != null) {
            // badge at the top-right, outside the ring, so the center stays clear for aiming
            val br = 7.5f
            val off = r * 0.72f + br * 0.55f
            val bx = (cx + off).coerceAtMost(w - br - 1f)
            val by = (cy - off).coerceAtLeast(br + 1f)
            g.color = col(color)
            g.fill(Ellipse2D.Float(bx - br, by - br, 2 * br, 2 * br))
            g.color = col(if (luminance(color) < 0.2f) WHITE else BLACK)
            Txt.drawCentered(g, label, Fonts.mono(br * 1.25f, bold = true), bx, by)
        }
        g.dispose()
    }
}

// ---------------------------------------------------------------------------------------------
// Countdown box (port of AnnunciatorView)
// ---------------------------------------------------------------------------------------------

object Annunciator {
    private val DIGITS = Regex("[0-9]")
    fun template(s: String) = s.replace(DIGITS, "0")

    fun size(text: String, textSize: Float): java.awt.Dimension {
        val f = Fonts.mono(textSize, bold = true)
        val sample = template(text).ifEmpty { "0:00" }
        val w = Txt.width(f, sample) + textSize * 1.1f
        val h = Txt.ascent(f) + Txt.descent(f) + textSize * 0.5f
        return java.awt.Dimension(w.toInt() + 2, h.toInt() + 2)
    }

    fun paint(g0: Graphics2D, w: Int, h: Int, text: String, textSize: Float, color: Int) {
        val g = g0.create() as Graphics2D
        g.smooth()
        val sw = 0.75f.coerceAtLeast(1f)
        val i = sw / 2f
        val bw = w - 2 * i
        val bh = h - 2 * i
        val r = min(bh * 0.3f, 12f)
        val box = RoundRectangle2D.Float(i, i, bw, bh, 2 * r, 2 * r)
        g.color = Color.BLACK
        g.fill(box)
        g.stroke = BasicStroke(sw)
        g.color = col(mix(color, BLACK, 0.45f))
        g.draw(box)
        g.color = col(color)
        Txt.drawCentered(g, text, Fonts.mono(textSize, bold = true), w / 2f, h / 2f)
        g.dispose()
    }
}

// ---------------------------------------------------------------------------------------------
// Airbus lights (port of LiveLights; timing and sizes from the shared AirbusLights)
// ---------------------------------------------------------------------------------------------

class Lights(private val scale: Float = 1f) {
    private val shadow = AirbusLights.newShadows()
    var reduceMotion = false

    fun refreshMotionSetting() { reduceMotion = Win.animationsOff() }

    /** Centre strobe, behind the content. [box] = x, y, w, h of the card. */
    fun drawCenter(g: Graphics2D, x: Float, y: Float, w: Float, h: Float, now: Long) {
        val cx = x + w / 2f
        val cy = y + h / 2f
        if (reduceMotion) {
            light(g, cx, cy, AirbusLights.CENTER_R, WHITE, AirbusLights.STEADY, null)
            return
        }
        val op = AirbusLights.center(now, shadow)
        light(g, cx, cy, AirbusLights.CENTER_R, WHITE, op, shadow)
    }

    /** Wing-tip strobes and beacons, on top of the content. */
    fun drawOver(g: Graphics2D, x: Float, y: Float, w: Float, h: Float, now: Long) {
        val d = scale
        val padL = x + AirbusLights.BORDER * d
        val padR = x + w - AirbusLights.BORDER * d
        val padT = y + AirbusLights.BORDER * d
        val padB = y + h - AirbusLights.BORDER * d
        val cx = (padL + padR) / 2f
        val cy = (padT + padB) / 2f
        val sr = AirbusLights.STROBE_R
        val lx = padL - (AirbusLights.STROBE_OUT - sr) * d
        val rx = padR + (AirbusLights.STROBE_OUT - sr) * d
        if (reduceMotion) {
            light(g, lx, cy, sr, WHITE, AirbusLights.STEADY, null)
            light(g, rx, cy, sr, WHITE, AirbusLights.STEADY, null)
        } else {
            val op = AirbusLights.strobe(now, shadow)
            light(g, lx, cy, sr, WHITE, op, shadow)
            light(g, rx, cy, sr, WHITE, op, shadow)
        }
        val br = AirbusLights.BEACON_R
        val ty = padT - (AirbusLights.BEACON_OUT - br) * d
        val by = padB + (AirbusLights.BEACON_OUT - br) * d
        if (reduceMotion) {
            light(g, cx, ty, br, AirbusLights.RED, AirbusLights.STEADY, null)
            light(g, cx, by, br, AirbusLights.RED, AirbusLights.STEADY, null)
        } else {
            val op = AirbusLights.beacon(now, shadow)
            light(g, cx, ty, br, AirbusLights.RED, op, shadow)
            light(g, cx, by, br, AirbusLights.RED, op, shadow)
        }
    }

    /** The beacon drawn as a powered lamp with a glass lens (the small beacons beside "Warning"). */
    fun drawLamp(g: Graphics2D, x: Float, y: Float, now: Long) {
        val k = AirbusLights.lamp(now, reduceMotion, shadow)
        val r = AirbusLights.BEACON_R * scale
        drawShadows(g, x, y, r, AirbusLights.RED, 1f, shadow)
        val lens = AirbusLights.lensColors(k)
        g.paint = RadialGradientPaint(Point2D.Float(x, y), r, AirbusLights.LENS_STOPS, lens.map { col(it) }.toTypedArray())
        g.fill(Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r))
        g.stroke = BasicStroke(max(1f, 0.6f * scale))
        g.color = col(AirbusLights.BEZEL)
        g.draw(Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r))
        g.color = col(argb(AirbusLights.highlightAlpha(k), 255, 255, 255))
        val hr = r * 0.22f
        g.fill(Ellipse2D.Float(x - r * 0.35f - hr, y - r * 0.38f - hr, 2 * hr, 2 * hr))
    }

    fun nextFrameDelay(now: Long) = AirbusLights.nextFrameDelay(now, reduceMotion)
    fun beaconFrameDelay(now: Long) = AirbusLights.beaconFrameDelay(now, reduceMotion)

    private fun light(g: Graphics2D, x: Float, y: Float, radiusPx: Float, c: Int, opacity: Float, sh: FloatArray?) {
        val r = radiusPx * scale
        if (sh != null) drawShadows(g, x, y, r, c, opacity, sh)
        g.color = col(c, opacity)
        g.fill(Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r))
    }

    /** CSS box-shadows as blurred discs: (blur, spread, alpha) x 2. */
    private fun drawShadows(g: Graphics2D, x: Float, y: Float, r: Float, c: Int, opacity: Float, sh: FloatArray) {
        var i = 0
        while (i + 2 < sh.size) {
            val a = sh[i + 2] * opacity
            if (a > 0.003f) Glow.disc(g, x, y, r + sh[i + 1] * scale, sh[i] * scale / 2f, c, a)
            i += 3
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Permission card background (port of PermBackground)
// ---------------------------------------------------------------------------------------------

class PermPainter {
    private class Trace(val pts: FloatArray, val length: Float, val speed: Float, val offset: Float, val gap: Float) {
        val path = Path2D.Float().apply {
            moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
        }
    }

    private val traces = mutableListOf<Trace>()
    private var builtW = -1
    private var builtH = -1
    private val startedAt = System.currentTimeMillis()
    private val radius = 16f

    var on = false

    private fun build(w: Int, h: Int) {
        if (w == builtW && h == builtH) return
        builtW = w; builtH = h
        traces.clear()
        if (w <= 0 || h <= 0) return
        val rnd = Random(7)
        val count = (h / 34f).toInt().coerceIn(3, 8)
        for (i in 0 until count) {
            val pts = ArrayList<Float>()
            var x = -4f
            var y = h * (i + 0.5f) / count + (rnd.nextFloat() - 0.5f) * 10f
            pts += x; pts += y
            while (x < w + 4f) {
                x += 28f + rnd.nextFloat() * 70f
                pts += x; pts += y
                if (rnd.nextFloat() < 0.65f) {
                    val jog = (8f + rnd.nextFloat() * 14f) * if (rnd.nextBoolean()) 1f else -1f
                    val ny = (y + jog).coerceIn(8f, h - 8f)
                    x += abs(ny - y)
                    y = ny
                    pts += x; pts += y
                }
            }
            val arr = pts.toFloatArray()
            var len = 0f
            var k = 2
            while (k < arr.size) { len += hypot(arr[k] - arr[k - 2], arr[k + 1] - arr[k - 1]); k += 2 }
            traces += Trace(arr, len, 110f + rnd.nextFloat() * 90f, rnd.nextFloat() * 4000f, 120f + rnd.nextFloat() * 260f)
        }
    }

    private fun hypot(a: Float, b: Float) = sqrt(a * a + b * b)

    fun paint(g0: Graphics2D, w: Int, h: Int) {
        build(w, h)
        val g = g0.create() as Graphics2D
        g.smooth()
        // the still parts (base, grid, faint traces) are drawn once per size and reused every frame
        val sc = g.deviceScale()
        val key = "$w|$h|$on|$sc"
        if (layerKey != key || layer == null) {
            val img = BufferedImage(maxOf(1, (w * sc).toInt()), maxOf(1, (h * sc).toInt()), BufferedImage.TYPE_INT_ARGB)
            val lg = img.createGraphics().smooth()
            lg.scale(sc, sc)
            lg.clip(RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), 2 * radius, 2 * radius))
            if (on) drawOnStill(lg, w, h) else drawOff(lg, w, h)
            lg.dispose()
            layer = img
            layerKey = key
        }
        val at = g.transform
        g.scale(1 / sc, 1 / sc)
        g.drawImage(layer, 0, 0, null)
        g.transform = at
        if (on) {
            g.clip(RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), 2 * radius, 2 * radius))
            drawPulses(g)
        }
        g.dispose()
        val e = g0.create() as Graphics2D
        e.smooth()
        val sw = 1f
        val inset = sw / 2f
        e.stroke = BasicStroke(sw)
        e.color = if (on) {
            val t = (System.currentTimeMillis() - startedAt) / 1000f
            val breathe = 0.65f + 0.35f * (0.5f + 0.5f * sin(t * 2.6f))
            col(argb((255 * breathe).toInt(), 57, 255, 20))
        } else col(argb(220, 255, 42, 42))
        e.draw(RoundRectangle2D.Float(inset, inset, w - sw, h - sw, 2 * (radius - inset), 2 * (radius - inset)))
        e.dispose()
    }

    private fun grid(g: Graphics2D, w: Int, h: Int, c: Color) {
        g.color = c
        g.stroke = BasicStroke(1f)
        var k = -h.toFloat()
        while (k < w + h) {
            g.draw(Line2D.Float(k, 0f, k + h, h.toFloat()))
            g.draw(Line2D.Float(k + h, 0f, k, h.toFloat()))
            k += 16f
        }
    }

    private fun drawOff(g: Graphics2D, w: Int, h: Int) {
        g.color = col(com.neon.autoclicker.hex("#070101"))
        g.fillRect(0, 0, w, h)
        grid(g, w, h, col(argb(70, 255, 42, 42)))
        g.paint = java.awt.LinearGradientPaint(0f, 0f, 0f, max(1, h).toFloat(), floatArrayOf(0f, 0.5f, 1f),
            arrayOf(col(argb(150, 0, 0, 0)), col(argb(60, 0, 0, 0)), col(argb(150, 0, 0, 0))))
        g.fillRect(0, 0, w, h)
    }

    private var layer: BufferedImage? = null
    private var layerKey = ""

    private fun drawOnStill(g: Graphics2D, w: Int, h: Int) {
        g.paint = java.awt.GradientPaint(0f, 0f, col(com.neon.autoclicker.hex("#021A0A")), w.toFloat(), h.toFloat(), col(com.neon.autoclicker.hex("#04220E")))
        g.fillRect(0, 0, w, h)
        grid(g, w, h, col(argb(22, 57, 255, 20)))
        val round = { width: Float -> BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND) }
        g.stroke = round(1.2f)
        g.color = col(argb(60, 57, 255, 20))
        traces.forEach { g.draw(it.path) }
    }

    private fun drawPulses(g: Graphics2D) {
        val round = { width: Float -> BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND) }
        val now = System.currentTimeMillis() / 1000f
        val tail = 46f
        traces.forEach { tr ->
            val cycle = tr.length + tail + tr.gap
            val head = (now * tr.speed + tr.offset) % cycle
            if (head > tr.length + tail) return@forEach
            val from = (head - tail).coerceAtLeast(0f)
            val to = head.coerceAtMost(tr.length)
            if (to <= from) return@forEach
            val seg = segment(tr, from, to)
            g.stroke = round(5f)
            g.color = col(argb(45, 57, 255, 20))
            g.draw(seg)
            g.stroke = round(1.8f)
            g.color = col(argb(185, 120, 255, 90))
            g.draw(seg)
            if (head <= tr.length) {
                val p = pointAt(tr, head)
                g.color = col(argb(60, 57, 255, 20))
                g.fill(Ellipse2D.Float(p[0] - 5f, p[1] - 5f, 10f, 10f))
                g.color = col(argb(230, 220, 255, 210))
                g.fill(Ellipse2D.Float(p[0] - 1.8f, p[1] - 1.8f, 3.6f, 3.6f))
            }
        }
    }

    private fun pointAt(tr: Trace, dist: Float): FloatArray {
        var acc = 0f
        val p = tr.pts
        var k = 2
        while (k < p.size) {
            val l = hypot(p[k] - p[k - 2], p[k + 1] - p[k - 1])
            if (acc + l >= dist) {
                val t = if (l == 0f) 0f else (dist - acc) / l
                return floatArrayOf(p[k - 2] + (p[k] - p[k - 2]) * t, p[k - 1] + (p[k + 1] - p[k - 1]) * t)
            }
            acc += l
            k += 2
        }
        return floatArrayOf(p[p.size - 2], p[p.size - 1])
    }

    private fun segment(tr: Trace, from: Float, to: Float): Path2D.Float {
        val out = Path2D.Float()
        val a = pointAt(tr, from)
        out.moveTo(a[0], a[1])
        var acc = 0f
        val p = tr.pts
        var k = 2
        while (k < p.size) {
            val l = hypot(p[k] - p[k - 2], p[k + 1] - p[k - 1])
            val end = acc + l
            if (end > from && end < to) out.lineTo(p[k], p[k + 1])
            acc = end
            k += 2
        }
        val b = pointAt(tr, to)
        out.lineTo(b[0], b[1])
        return out
    }
}
