package com.odit.obd.elm

import com.odit.obd.bt.ObdTransport
import com.odit.obd.bt.TransportException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class ElmTimeoutException(val command: String) : IOException("Адаптерът не отговори на „$command“")

/**
 * An answer with what the adapter said about waking the K-line up on the way:
 * [busInit] true for "BUS INIT: ...OK", false for "BUS INIT: ...ERROR", null when it did not try.
 */
class ElmAnswer(val lines: List<String>, val busInit: Boolean?)

/**
 * Command/response layer for ELM327 compatible adapters: writes a command, then
 * collects text until the ">" prompt.
 */
class Elm327(private val transport: ObdTransport, private val log: CommLog) {

    private val mutex = Mutex()

    /** Sends one command and returns the cleaned answer lines. */
    suspend fun send(command: String, timeoutMs: Long = 4_000): List<String> = sendDetailed(command, timeoutMs).lines

    /** Like [send], and also tells whether the adapter woke the K-line up first (and how it went). */
    suspend fun sendDetailed(command: String, timeoutMs: Long = 4_000): ElmAnswer = mutex.withLock {
        var raw = exchange(command, timeoutMs)
        var lines = clean(raw, command)
        // A command sent while the adapter was still busy only interrupts it.
        if (lines.size == 1 && lines[0].equals("STOPPED", ignoreCase = true)) {
            raw = exchange(command, timeoutMs)
            lines = clean(raw, command)
        }
        ElmAnswer(lines, busInit(raw))
    }

    private suspend fun exchange(command: String, timeoutMs: Long): String {
        drain()
        log.tx(command)
        transport.write((command + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        val finished = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val chunk = try {
                    transport.incoming.receive()
                } catch (e: ClosedReceiveChannelException) {
                    throw TransportException("Връзката с адаптера прекъсна")
                }
                sb.append(String(chunk, Charsets.ISO_8859_1))
                if (sb.indexOf(">") >= 0) break
            }
        }
        if (sb.isNotEmpty()) log.rx(sb.toString())
        if (finished == null) {
            log.note("няма отговор след $timeoutMs ms")
            throw ElmTimeoutException(command)
        }
        return sb.toString()
    }

    private fun drain() {
        while (transport.incoming.tryReceive().isSuccess) { /* discard stale bytes */ }
    }

    companion object {
        /** true / false when the raw answer reports a K-line wake-up ("BUS INIT: ...OK" / "...ERROR"). */
        fun busInit(raw: String): Boolean? {
            val line = raw.substringBefore('>').replace('\n', '\r').split('\r')
                .firstOrNull { it.trim().startsWith("BUS INIT", ignoreCase = true) } ?: return null
            return !line.contains("ERROR", ignoreCase = true)
        }

        /** Splits the raw answer into lines, removing echo, prompt and progress messages. */
        fun clean(raw: String, command: String): List<String> {
            val body = raw.substringBefore('>')
            val cmd = command.replace(" ", "")
            return body.replace('\n', '\r').split('\r')
                .asSequence()
                .map { it.replace("\u0000", "").trim() }
                .filter { it.isNotEmpty() }
                .filterNot { it.replace(" ", "").equals(cmd, ignoreCase = true) }
                .map { it.replace("SEARCHING...", "", ignoreCase = true).trim() }
                .map { line ->
                    if (line.startsWith("BUS INIT", ignoreCase = true)) {
                        if (line.contains("ERROR", ignoreCase = true)) "BUS INIT ERROR"
                        else line.substringAfter("OK", "").trim()
                    } else {
                        line
                    }
                }
                .filter { it.isNotEmpty() }
                .toList()
        }
    }
}
