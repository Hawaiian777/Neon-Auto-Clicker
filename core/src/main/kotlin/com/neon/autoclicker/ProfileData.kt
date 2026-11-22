package com.neon.autoclicker

/** A target position as fractions of the screen (0..1). */
data class Pt(val x: Float, val y: Float)

/**
 * A saved, reusable clicking setup. Colors and menu size stay global and are not part of a profile.
 * The timer length is saved, but loading a profile never switches the auto-stop timer on.
 */
data class Profile(
    val name: String,
    val intervalQ: Int,
    val timerSec: Int,
    val targetDp: Int,
    val mode: Int,
    val targets: List<Pt>,
) {
    fun summary(): String {
        val n = targets.size
        val modeText = if (n > 1) {
            if (mode == Config.MODE_ALL) " · all each tap" else " · in turn"
        } else ""
        return "${Config.formatInterval(intervalQ)} · $n ${if (n == 1) "target" else "targets"}$modeText · " +
            "timer length ${Config.durLabel(timerSec)}"
    }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "q" to intervalQ,
        "timerSec" to timerSec,
        "size" to targetDp,
        "mode" to mode,
        "targets" to targets.map { listOf(it.x.toDouble(), it.y.toDouble()) },
    )

    companion object {
        /** Reads a profile, also one saved by older versions. */
        fun fromJson(o: Map<*, *>): Profile = Profile(
            name = o["name"] as? String ?: "Profile",
            intervalQ = Config.clampQuarters(o.int("q") ?: Config.DEFAULT_Q),
            timerSec = timerFrom(o),
            targetDp = Config.clampTargetDp(o.int("size") ?: Config.TARGET_DEFAULT_DP),
            mode = Config.normalizeMode(o.int("mode") ?: Config.MODE_SEQUENCE),
            targets = Targets.fromList(o["targets"]) ?: listOf(Pt(0.5f, 0.5f)),
        )

        private fun Map<*, *>.int(k: String): Int? = (this[k] as? Number)?.toInt()

        private fun timerFrom(o: Map<*, *>): Int {
            o.int("timerSec")?.let { return Config.clampTimer(it) }
            // older versions: index into a list that started with "No timer"
            val old = listOf(0, 60, 300, 600, 900, 1800, 2700, 3600, 5400, 7200, 10800, 14400, 18000, 21600, 25200)
            val i = o.int("timer") ?: 0
            val v = if (i in old.indices) old[i] else (o.int("custom") ?: Config.DEFAULT_TIMER_SEC)
            return if (v <= 0) Config.DEFAULT_TIMER_SEC else Config.clampTimer(v)
        }

        fun listToJson(list: List<Profile>): String = MiniJson.write(list.map { it.toJson() })

        fun listFromJson(json: String?): MutableList<Profile> {
            if (json.isNullOrBlank()) return mutableListOf()
            return try {
                (MiniJson.parse(json) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { fromJson(it) }.toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }
        }
    }
}

/** Target positions stored as JSON: [[x, y], ...]. */
object Targets {
    fun toJson(list: List<Pt>): String = MiniJson.write(list.map { listOf(it.x.toDouble(), it.y.toDouble()) })

    fun fromJson(json: String?): MutableList<Pt>? {
        if (json.isNullOrBlank()) return null
        return try {
            fromList(MiniJson.parse(json))
        } catch (e: Exception) {
            null
        }
    }

    fun fromList(v: Any?): MutableList<Pt>? {
        val out = (v as? List<*>).orEmpty().mapNotNull { p ->
            val xy = p as? List<*> ?: return@mapNotNull null
            val x = (xy.getOrNull(0) as? Number)?.toFloat() ?: return@mapNotNull null
            val y = (xy.getOrNull(1) as? Number)?.toFloat() ?: return@mapNotNull null
            Pt(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }.toMutableList()
        return out.takeIf { it.isNotEmpty() }
    }
}

/**
 * Minimal JSON reader/writer for the app's own small settings values (objects, arrays,
 * strings, numbers, booleans, null). Keeps both apps free of extra libraries.
 */
object MiniJson {
    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> str(v, sb)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Number -> {
                val d = v.toDouble()
                if (d == Math.floor(d) && !d.isInfinite() && kotlin.math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                v.forEach { (k, value) ->
                    if (!first) sb.append(',')
                    first = false
                    str(k.toString(), sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                v.forEach {
                    if (!first) sb.append(',')
                    first = false
                    write(it, sb)
                }
                sb.append(']')
            }
            else -> str(v.toString(), sb)
        }
    }

    private fun str(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }

    fun parse(s: String): Any? {
        val p = Parser(s)
        val v = p.value()
        p.ws()
        require(p.i == s.length) { "Trailing characters at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            ws()
            require(i < s.length) { "Unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("Unexpected '$c' at $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "Bad literal at $i" }
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = string()
                ws()
                require(s[i] == ':') { "Expected ':' at $i" }
                i++
                m[k] = value()
                ws()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> error("Expected ',' or '}' at $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value()
                ws()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> error("Expected ',' or ']' at $i")
                }
            }
        }

        private fun string(): String {
            require(s[i] == '"') { "Expected string at $i" }
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> error("Bad escape at $i")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Number {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLong()
        }
    }
}
