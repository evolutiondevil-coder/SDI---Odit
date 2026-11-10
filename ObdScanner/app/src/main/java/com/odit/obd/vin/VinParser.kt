package com.odit.obd.vin

import com.odit.obd.elm.ElmErrors
import com.odit.obd.elm.ObdProtocol
import com.odit.obd.elm.ResponseParser

/** How a VIN was obtained. */
enum class VinMethod(val title: String) {
    OBD_0902("OBD 09 02"),
    UDS_F190("UDS 22 F190"),
    KWP_1A90("KWP 1A 90"),
    KLINE("K-line (KWP)"),
    MANUAL("въведен ръчно"),
}

/** One VIN reported by one control unit. [ecu] is the CAN id / address, or "?" when the adapter hid it. */
data class VinFound(val vin: String, val ecu: String, val method: VinMethod)

/**
 * Pure parsing of VIN answers, independent of the adapter and the UI so it can be unit tested with
 * raw adapter output. Handles CAN 11/29-bit with and without headers, multi-frame ISO-TP, several
 * control units answering at once, K-line answers split over several messages, and the line
 * numbering ("0:", "1:") and byte-count line that ELM327 adapters print with headers off.
 */
object VinParser {

    private const val VIN_CHARS = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"

    /** 17 characters, digits and capital letters except I, O and Q. */
    fun isValidFormat(vin: String): Boolean = vin.length == 17 && vin.all { it in VIN_CHARS }

    /**
     * North American check digit (position 9). Mandatory only in North America and China, so a
     * mismatch is NOT an error for European cars — it is only a hint.
     */
    fun checkDigitOk(vin: String): Boolean {
        if (!isValidFormat(vin)) return false
        val weights = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)
        var sum = 0
        for (i in 0 until 17) sum += transliterate(vin[i]) * weights[i]
        val r = sum % 11
        val expected = if (r == 10) 'X' else ('0' + r)
        return vin[8] == expected
    }

    private fun transliterate(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        'A', 'J' -> 1
        'B', 'K', 'S' -> 2
        'C', 'L', 'T' -> 3
        'D', 'M', 'U' -> 4
        'E', 'N', 'V' -> 5
        'F', 'W' -> 6
        'G', 'P', 'X' -> 7
        'H', 'Y' -> 8
        'R', 'Z' -> 9
        else -> 0
    }

    /**
     * Finds the VIN in the data bytes of an answer (after the service/identifier bytes).
     * Tolerates a leading item-count byte, padding with 00 / FF / spaces and lowercase letters.
     */
    fun vinFromBytes(bytes: List<Int>): String? {
        val text = buildString {
            for (b in bytes) {
                val c = b.toChar().uppercaseChar()
                append(if (b in 0x20..0x7E) c else '\u0000')
            }
        }
        // Runs of VIN characters separated by anything else (padding, count bytes, sequence numbers).
        val runs = text.split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
        runs.firstOrNull { isValidFormat(it) }?.let { return it }
        // Some units pad with ASCII zeros or prefix a stray digit: accept the 17 characters at the end.
        val joined = runs.joinToString("")
        if (joined.length > 17) {
            val tail = joined.takeLast(17)
            if (isValidFormat(tail)) return tail
        }
        return null
    }

    private fun hexBytes(hex: String): List<Int>? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        if (!hex.all { it in '0'..'9' || it in 'A'..'F' }) return null
        return (0 until hex.length / 2).map { hex.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    /**
     * Parses everything the adapter printed for request 0902. Returns one VIN per control unit that
     * answered with a valid one (in answer order).
     */
    fun parse0902(lines: List<String>, protocol: ObdProtocol): List<VinFound> {
        val result = mutableListOf<VinFound>()
        fun add(ecu: String, vin: String?) {
            if (vin != null && result.none { it.ecu == ecu && it.vin == vin }) result += VinFound(vin, ecu, VinMethod.OBD_0902)
        }

        // 1) Headers on: re-assembled messages per sender.
        val msgs = ResponseParser.parse(lines, protocol).filter { it.data.size > 2 && it.data[0] == 0x49 && it.data[1] == 0x02 }
        for ((ecu, list) in msgs.groupBy { it.header }) {
            val bytes = if (list.size == 1) {
                list[0].data.drop(2)
            } else {
                // K-line style: several messages "49 02 <seq> b1 b2 b3 b4", put them in order.
                list.sortedBy { it.data[2] }.flatMap { it.data.drop(3) }
            }
            add(ecu, vinFromBytes(bytes))
        }
        if (result.isNotEmpty()) return result

        // 2) Headers off (or a clone that ignores ATH1).
        for (bytes in headerlessMessages(lines)) {
            if (bytes.size > 2 && bytes[0] == 0x49 && bytes[1] == 0x02) add("?", vinFromBytes(bytes.drop(2)))
        }
        if (result.isNotEmpty()) return result

        // 3) Headers off on K-line: one short line per part, "49 02 01 00 00 00 57", "49 02 02 56 57 5A 5A"...
        val parts = lines.mapNotNull { hexBytes(it.replace(" ", "").uppercase()) }
            .filter { it.size in 4..8 && it[0] == 0x49 && it[1] == 0x02 }
        if (parts.size > 1) add("?", vinFromBytes(parts.sortedBy { it[2] }.flatMap { it.drop(3) }))
        return result
    }

    /**
     * Messages as printed with headers off: a byte-count line ("014") followed by numbered lines
     * ("0: 49 02 01 57 56 57", "1: ..."), or plain single lines. Index wrap-around (F -> 0) is handled.
     */
    internal fun headerlessMessages(lines: List<String>): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        var current: MutableList<Int>? = null
        var expected = -1
        var lastIndex = -1
        fun flush() {
            current?.let { if (it.isNotEmpty()) out += if (expected > 0) it.take(expected) else it }
            current = null
            expected = -1
            lastIndex = -1
        }
        val indexed = Regex("^([0-9A-F]):([0-9A-F]*)$")
        for (raw in lines) {
            val line = raw.replace(" ", "").uppercase()
            if (line.isEmpty() || ElmErrors.find(listOf(line)) != null) continue
            if (line.length == 3 && line.all { it in '0'..'9' || it in 'A'..'F' }) {
                flush()
                current = mutableListOf()
                expected = line.toInt(16)
                continue
            }
            val m = indexed.matchEntire(line)
            if (m != null) {
                val idx = m.groupValues[1].toInt(16)
                val bytes = hexBytes(m.groupValues[2]) ?: continue
                val cur = current
                // A new "0:" without a length line, or one that does not follow the previous index, starts a new message.
                if (cur == null || (idx == 0 && lastIndex != 15 && cur.isNotEmpty())) {
                    if (cur != null && cur.isNotEmpty()) flush()
                    current = mutableListOf()
                }
                current!!.addAll(bytes)
                lastIndex = idx
                continue
            }
            val bytes = hexBytes(line) ?: continue
            flush()
            out += bytes
        }
        flush()
        return out
    }

    /** Positive UDS answer to 22 F1 90: 62 F1 90 + VIN. */
    fun parseUdsF190(data: IntArray): String? =
        if (data.size > 3 && data[0] == 0x62 && data[1] == 0xF1 && data[2] == 0x90) vinFromBytes(data.drop(3)) else null

    /** Positive KWP2000 answer to 1A 90: 5A 90 + VIN. */
    fun parseKwp1A90(data: IntArray): String? =
        if (data.size > 2 && data[0] == 0x5A && data[1] == 0x90) vinFromBytes(data.drop(2)) else null
}
