package com.odit.obd.sim

import com.odit.obd.bt.ObdTransport
import com.odit.obd.elm.CommLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Plays back a log exported from the app ("Експорт на лога"): every command gets the answer the
 * real adapter gave to the same command, in recorded order. Lets a log from a real car become a test.
 * Commands that are not in the log get "OK" (AT commands) or "NO DATA".
 */
class ReplayTransport(log: String) : ObdTransport {

    override val description = "Запис (replay)"

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> = channel

    private class Exchange(val command: String, val answer: String, var used: Boolean = false)

    private val exchanges: List<Exchange> = parse(log)
    private val input = StringBuilder()

    /** Header lines of the log ("# key: value"). */
    val header: Map<String, String> = log.lineSequence()
        .filter { it.startsWith("# ") && it.contains(": ") }
        .associate { it.removePrefix("# ").substringBefore(": ") to it.substringAfter(": ") }

    override suspend fun connect() {}

    override suspend fun write(data: ByteArray) {
        input.append(String(data, Charsets.ISO_8859_1))
        while (true) {
            val idx = input.indexOf("\r")
            if (idx < 0) break
            val cmd = norm(input.substring(0, idx))
            input.delete(0, idx + 1)
            val ex = exchanges.firstOrNull { !it.used && it.command == cmd }
                ?: exchanges.lastOrNull { it.command == cmd }
            val answer = when {
                ex != null -> { ex.used = true; ex.answer }
                cmd.startsWith("AT") -> "OK\r\r>"
                else -> "NO DATA\r\r>"
            }
            channel.trySend(answer.toByteArray(Charsets.ISO_8859_1))
        }
    }

    override fun close() {
        channel.close()
    }

    companion object {
        private fun norm(s: String) = s.replace(" ", "").trim().uppercase()

        private val LINE = Regex("""^\S+ ([<>#]) ?(.*)$""")

        private fun parse(log: String): List<Exchange> {
            val out = mutableListOf<Exchange>()
            var cmd: String? = null
            val answer = StringBuilder()
            fun flush() {
                cmd?.let { out += Exchange(norm(it), answer.toString().ifEmpty { "\r>" }) }
                cmd = null
                answer.clear()
            }
            for (line in log.lineSequence()) {
                if (line.startsWith("#")) continue
                val m = LINE.matchEntire(line) ?: continue
                when (m.groupValues[1]) {
                    ">" -> { flush(); cmd = m.groupValues[2] }
                    "<" -> answer.append(CommLog.unescape(m.groupValues[2]))
                }
            }
            flush()
            return out
        }
    }
}
