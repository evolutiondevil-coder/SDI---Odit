package com.odit.obd.sim

import com.odit.obd.bt.ObdTransport
import com.odit.obd.elm.CommLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Plays back a log exported from the app ("Експорт на лога"): every command gets the answer the
 * real adapter gave to the same command, in recorded order. Lets a log from a real car become a test.
 * Requests to the car are matched within the protocol that was selected (AT SP) when they were
 * recorded, so a newer app may try the protocols in another order. Commands that are not in the log
 * get "OK" (AT commands), "CAN ERROR" (requests on a CAN protocol) or "NO DATA".
 */
class ReplayTransport(log: String) : ObdTransport {

    override val description = "Запис (replay)"

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> = channel

    /** [protocol] = the AT SP value in force when the command was recorded. */
    private class Exchange(val command: String, val answer: String, val protocol: Char, var used: Boolean = false)

    private val exchanges: List<Exchange> = parse(log)
    private val input = StringBuilder()
    private var protocol = '0'

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
            val isAt = cmd.startsWith("AT")
            if (isAt) spOf(cmd)?.let { protocol = it }
            val ex = if (isAt) {
                exchanges.firstOrNull { !it.used && it.command == cmd } ?: exchanges.lastOrNull { it.command == cmd }
            } else {
                exchanges.firstOrNull { !it.used && it.command == cmd && it.protocol == protocol }
            }
            val answer = when {
                ex != null -> { ex.used = true; ex.answer }
                isAt -> "OK\r\r>"
                protocol in "6789" -> "CAN ERROR\r\r>"
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

        /** "ATSP3" / "ATTP3" -> '3'; AT Z and AT D keep the protocol the adapter stored. */
        private fun spOf(cmd: String): Char? =
            if ((cmd.startsWith("ATSP") || cmd.startsWith("ATTP")) && cmd.length >= 5) cmd.last() else null

        private val LINE = Regex("""^\S+ ([<>#]) ?(.*)$""")

        private fun parse(log: String): List<Exchange> {
            val out = mutableListOf<Exchange>()
            var cmd: String? = null
            var protocol = '0'
            val answer = StringBuilder()
            fun flush() {
                cmd?.let {
                    val c = norm(it)
                    out += Exchange(c, answer.toString().ifEmpty { "\r>" }, protocol)
                    spOf(c)?.let { p -> protocol = p }
                }
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
