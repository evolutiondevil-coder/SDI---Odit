package com.odit.obd.elm

/**
 * One complete answer from one control unit.
 * [header] is the CAN id ("7E8", "18DAF110") or, for older protocols, the source address ("10").
 * [data] starts with the service byte (e.g. 0x43 for a mode 03 answer).
 */
class EcuMessage(val header: String, val data: IntArray, val complete: Boolean = true) {
    val service: Int get() = if (data.isNotEmpty()) data[0] else -1

    override fun toString(): String =
        header + ": " + data.joinToString(" ") { "%02X".format(it) } + if (complete) "" else " (непълно)"
}

object ElmErrors {
    private val KNOWN = listOf(
        "NO DATA", "UNABLE TO CONNECT", "BUS INIT", "CAN ERROR", "BUS ERROR", "BUS BUSY",
        "BUFFER FULL", "DATA ERROR", "<DATA ERROR", "<RX ERROR", "FB ERROR", "LV RESET",
        "STOPPED", "ERROR", "ACT ALERT", "?",
    )

    /** Returns the first adapter error found in the lines, if any. */
    fun find(lines: List<String>): String? {
        for (l in lines) {
            val u = l.trim().uppercase()
            if (u == "?") return "?"
            for (k in KNOWN) if (k != "?" && u.contains(k)) return if (k == "BUS INIT") "BUS INIT ERROR" else k
        }
        return null
    }
}

object ResponseParser {

    private fun isHex(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }

    private fun bytes(hex: String): IntArray = IntArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16) }

    /**
     * Parses lines printed by the ELM327 with headers ON (ATH1) into messages.
     * CAN answers are re-assembled from ISO-TP frames per sender.
     */
    fun parse(lines: List<String>, protocol: ObdProtocol): List<EcuMessage> =
        if (protocol.isCan) parseCan(lines, protocol.is29bit) else parseLegacy(lines)

    private fun parseCan(lines: List<String>, is29: Boolean): List<EcuMessage> {
        val headerLen = if (is29) 8 else 3
        val frames = mutableListOf<Pair<String, IntArray>>()
        for (raw in lines) {
            val line = raw.replace(" ", "")
            if (!isHex(line) || line.length <= headerLen) continue
            if ((line.length - headerLen) % 2 != 0) continue
            frames += line.substring(0, headerLen).uppercase() to bytes(line.substring(headerLen))
        }

        val result = mutableListOf<EcuMessage>()
        class Partial(val header: String, val total: Int, val buf: MutableList<Int>, var nextSeq: Int)
        val partials = LinkedHashMap<String, Partial>()

        for ((header, f) in frames) {
            if (f.isEmpty()) continue
            when (f[0] shr 4) {
                0 -> { // single frame
                    var len = f[0] and 0x0F
                    var start = 1
                    if (len == 0 && f.size > 1) { len = f[1]; start = 2 } // CAN FD style escape
                    val end = minOf(f.size, start + len)
                    if (end > start) result += EcuMessage(header, f.copyOfRange(start, end))
                }
                1 -> { // first frame
                    if (f.size < 2) continue
                    partials[header]?.let { result += EcuMessage(header, it.buf.toIntArray(), complete = false) }
                    val total = ((f[0] and 0x0F) shl 8) or f[1]
                    val buf = f.drop(2).take(total).toMutableList()
                    partials[header] = Partial(header, total, buf, 1)
                }
                2 -> { // consecutive frame
                    val p = partials[header] ?: continue
                    val remaining = p.total - p.buf.size
                    p.buf.addAll(f.drop(1).take(remaining))
                    p.nextSeq = (p.nextSeq + 1) and 0x0F
                    if (p.buf.size >= p.total) {
                        result += EcuMessage(header, p.buf.toIntArray())
                        partials.remove(header)
                    }
                }
                else -> Unit // flow control frames are not answers
            }
        }
        for (p in partials.values) result += EcuMessage(p.header, p.buf.toIntArray(), complete = false)
        return result
    }

    /** J1850 / ISO 9141 / KWP: "48 6B 10 41 00 BE 3F A8 13 C4" = 3 header bytes, data, checksum. */
    private fun parseLegacy(lines: List<String>): List<EcuMessage> {
        val result = mutableListOf<EcuMessage>()
        for (raw in lines) {
            val line = raw.replace(" ", "")
            if (!isHex(line) || line.length % 2 != 0) continue
            val b = bytes(line)
            if (b.size < 5) continue
            val source = "%02X".format(b[2])
            result += EcuMessage(source, b.copyOfRange(3, b.size - 1))
        }
        return result
    }
}
