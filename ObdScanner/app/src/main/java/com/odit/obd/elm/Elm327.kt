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
 * Command/response layer for ELM327 compatible adapters: writes a command, then
 * collects text until the ">" prompt.
 */
class Elm327(private val transport: ObdTransport, private val log: CommLog) {

    private val mutex = Mutex()

    /** Sends one command and returns the cleaned answer lines. */
    suspend fun send(command: String, timeoutMs: Long = 4_000): List<String> = mutex.withLock {
        var lines = exchange(command, timeoutMs)
        // A command sent while the adapter was still busy only interrupts it.
        if (lines.size == 1 && lines[0].equals("STOPPED", ignoreCase = true)) {
            lines = exchange(command, timeoutMs)
        }
        lines
    }

    private suspend fun exchange(command: String, timeoutMs: Long): List<String> {
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
        return clean(sb.toString(), command)
    }

    private fun drain() {
        while (transport.incoming.tryReceive().isSuccess) { /* discard stale bytes */ }
    }

    companion object {
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
