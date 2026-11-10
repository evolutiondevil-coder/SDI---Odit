package com.odit.obd.elm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory log of everything sent to / received from the adapter. */
class CommLog(private val capacity: Int = 600) {
    private val lines = ArrayDeque<String>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun tx(text: String) = add("→ $text")

    fun rx(text: String) = add("← " + text.replace("\r\n", "\r").trim().replace("\r", " | "))

    fun note(text: String) = add("• $text")

    @Synchronized
    private fun add(text: String) {
        lines.addLast(fmt.format(Date()) + "  " + text)
        while (lines.size > capacity) lines.removeFirst()
        _version.value = _version.value + 1
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        _version.value = _version.value + 1
    }
}
