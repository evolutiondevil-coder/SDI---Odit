package com.odit.obd.elm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory log of everything sent to / received from the adapter. Answers are kept byte-exact so
 * that an exported log can be replayed in tests (see [com.odit.obd.sim.ReplayTransport]).
 */
class CommLog(private val capacity: Int = 5_000) {

    enum class Dir(val mark: Char) { TX('>'), RX('<'), NOTE('#') }

    class Entry(val timeMs: Long, val dir: Dir, val text: String)

    private val entries = ArrayDeque<Entry>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun tx(text: String) = add(Dir.TX, text)

    /** [raw] is exactly what the adapter sent, including CR/LF and the ">" prompt. */
    fun rx(raw: String) = add(Dir.RX, raw)

    fun note(text: String) = add(Dir.NOTE, text)

    @Synchronized
    private fun add(dir: Dir, text: String) {
        entries.addLast(Entry(System.currentTimeMillis(), dir, text))
        while (entries.size > capacity) entries.removeFirst()
        _version.value = _version.value + 1
    }

    @Synchronized
    fun entries(): List<Entry> = entries.toList()

    /** Human readable lines for the terminal screen. */
    fun snapshot(): List<String> = entries().map { e ->
        val t = fmt.format(Date(e.timeMs))
        when (e.dir) {
            Dir.TX -> "$t  → ${e.text}"
            Dir.RX -> "$t  ← " + e.text.replace("\r\n", "\r").trim().replace("\r", " | ")
            Dir.NOTE -> "$t  • ${e.text}"
        }
    }

    /** Text file for sharing: header lines start with "#", then "time > cmd" / "time < raw answer". */
    fun export(header: Map<String, String>): String = buildString {
        appendLine("# odit-log 1")
        for ((k, v) in header) appendLine("# $k: $v")
        for (e in entries()) {
            append(fmt.format(Date(e.timeMs))).append(' ').append(e.dir.mark).append(' ')
            appendLine(if (e.dir == Dir.RX) escape(e.text) else e.text.replace("\n", " "))
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        _version.value = _version.value + 1
    }

    companion object {
        fun escape(s: String): String = buildString {
            for (c in s) when {
                c == '\\' -> append("\\\\")
                c == '\r' -> append("\\r")
                c == '\n' -> append("\\n")
                c.code < 0x20 || c.code == 0x7F -> append("\\x%02X".format(c.code))
                else -> append(c)
            }
        }

        fun unescape(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'r' -> { sb.append('\r'); i += 2; continue }
                        'n' -> { sb.append('\n'); i += 2; continue }
                        '\\' -> { sb.append('\\'); i += 2; continue }
                        'x' -> if (i + 3 < s.length) {
                            sb.append(s.substring(i + 2, i + 4).toInt(16).toChar()); i += 4; continue
                        }
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }
    }
}
