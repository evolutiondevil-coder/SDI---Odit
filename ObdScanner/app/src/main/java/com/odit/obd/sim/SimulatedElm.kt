package com.odit.obd.sim

import com.odit.obd.bt.ObdTransport
import com.odit.obd.obd.Dtc
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlin.math.sin

/**
 * A fake ELM327 v1.5 plugged into a fake car, used for the demo mode and for tests.
 * The car speaks ISO 15765-4 CAN 11-bit 500k (or ISO 9141 for legacy tests) and has a few
 * control units with trouble codes.
 */
class SimulatedElm(
    private val carProtocol: Char = '6',
    private val chunkSize: Int = 20,
) : ObdTransport {

    override val description = "Демо режим (симулатор)"

    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<ByteArray> = channel

    private val input = StringBuilder()
    private val startMs = System.currentTimeMillis()

    // Adapter state
    private var echo = true
    private var spaces = true
    private var headers = false
    private var selected = '0'
    private var detected = false
    private var header = 0x7DF
    private var session = 1

    // Car state
    val engineStored = mutableListOf("P0301", "P0171")
    val enginePending = mutableListOf("P0420")
    val enginePermanent = mutableListOf("P0301")
    val tcmStored = mutableListOf("P0741")

    private class Module(
        val rx: Int,
        val name: String,
        val dtcs: MutableList<Triple<String, Int, Int>>, // code, ftb, status
        val uds: Boolean = true,
        val kwp: Boolean = false,
        val needsExtendedToClear: Boolean = false,
    )

    private val modules: Map<Int, Module> = mapOf(
        0x7E0 to Module(0x7E8, "ECM 1.4 TSI", mutableListOf(Triple("P0301", 0x00, 0x2F), Triple("P0171", 0x00, 0x28))),
        0x7E1 to Module(0x7E9, "DSG 7", mutableListOf(Triple("P0741", 0x00, 0x08))),
        0x713 to Module(0x77D, "ESP MK100", mutableListOf(Triple("C0035", 0x29, 0x09))),
        0x715 to Module(0x77F, "Airbag VW", mutableListOf(Triple("B1001", 0x13, 0x08)), needsExtendedToClear = true),
        0x714 to Module(0x77E, "KOMBI", mutableListOf(Triple("U0121", 0x00, 0x20), Triple("U0155", 0x00, 0x50))),
        0x70E to Module(0x778, "BCM PQ35", mutableListOf()),
        0x710 to Module(0x77A, "Gateway", mutableListOf(Triple("U0100", 0x00, 0x08)), uds = false, kwp = true),
    )

    val vin = "WVWZZZ1KZ8W123456"

    private val supportedPids = setOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x14, 0x15, 0x1C,
        0x1F, 0x20, 0x21, 0x2F, 0x31, 0x33, 0x40, 0x42, 0x46, 0x5C,
    )

    override suspend fun connect() {}

    override suspend fun write(data: ByteArray) {
        input.append(String(data, Charsets.ISO_8859_1))
        while (true) {
            val idx = input.indexOf("\r")
            if (idx < 0) break
            val cmd = input.substring(0, idx)
            input.delete(0, idx + 1)
            val out = StringBuilder()
            if (echo) out.append(cmd).append('\r')
            for (line in handle(cmd.trim())) out.append(line).append('\r')
            out.append('\r').append('>')
            val bytes = out.toString().toByteArray(Charsets.ISO_8859_1)
            var i = 0
            while (i < bytes.size) {
                val end = minOf(bytes.size, i + chunkSize)
                channel.trySend(bytes.copyOfRange(i, end))
                i = end
            }
        }
    }

    override fun close() {
        channel.close()
    }

    // ---------------------------------------------------------------- adapter

    private fun handle(raw: String): List<String> {
        val c = raw.replace(" ", "").uppercase()
        if (c.isEmpty()) return emptyList()
        if (c.startsWith("AT")) return at(c.substring(2))
        if (!c.all { it in '0'..'9' || it in 'A'..'F' } || c.length % 2 != 0) return listOf("?")
        val req = IntArray(c.length / 2) { c.substring(it * 2, it * 2 + 2).toInt(16) }
        return obd(req)
    }

    private fun at(c: String): List<String> = when {
        c == "Z" -> { reset(); listOf("", "ELM327 v1.5") }
        c == "I" -> listOf("ELM327 v1.5")
        c == "D" -> { reset(); listOf("OK") }
        c == "E0" -> { echo = false; listOf("OK") }
        c == "E1" -> { echo = true; listOf("OK") }
        c == "S0" -> { spaces = false; listOf("OK") }
        c == "S1" -> { spaces = true; listOf("OK") }
        c == "H0" -> { headers = false; listOf("OK") }
        c == "H1" -> { headers = true; listOf("OK") }
        c == "RV" -> listOf("12.6V")
        c == "DPN" -> listOf(if (selected == '0') "A$carProtocol" else "$selected")
        c.startsWith("SPA") -> { selected = '0'; detected = false; listOf("OK") }
        c.startsWith("SP") || c.startsWith("TP") -> {
            selected = c.last(); detected = false; listOf("OK")
        }
        c.startsWith("SH") -> {
            val h = c.substring(2)
            header = if (h.length == 3) h.toInt(16) else h.takeLast(6).toInt(16)
            listOf("OK")
        }
        c.startsWith("CRA") || c.startsWith("CF") || c.startsWith("CM") || c.startsWith("FC") ||
            c.startsWith("ST") || c.startsWith("CP") || c.startsWith("AT") || c.startsWith("L") ||
            c.startsWith("D0") || c.startsWith("D1") || c.startsWith("CAF") -> listOf("OK")
        else -> listOf("?")
    }

    private fun reset() {
        echo = true; spaces = true; headers = false; header = 0x7DF; session = 1
        selected = '0'; detected = false
    }

    // -------------------------------------------------------------------- car

    private fun obd(req: IntArray): List<String> {
        val prefix = mutableListOf<String>()
        if (!detected) {
            if (selected == '0') prefix += "SEARCHING..."
            if (selected != '0' && selected != carProtocol) return listOf("UNABLE TO CONNECT")
            if (carProtocol in "345") prefix += "BUS INIT: ...OK"
            detected = true
        }
        val answers: List<Pair<Int, IntArray>> = when {
            carProtocol == '6' && header == 0x7DF -> obdFunctional(req)
            carProtocol == '6' -> physical(header, req)
            else -> obdFunctional(req)
        }
        if (answers.isEmpty()) return prefix + "NO DATA"
        val lines = mutableListOf<String>()
        for ((rx, payload) in answers) lines += format(rx, payload)
        return prefix + lines
    }

    private fun obdFunctional(req: IntArray): List<Pair<Int, IntArray>> {
        val out = mutableListOf<Pair<Int, IntArray>>()
        engine(req)?.let { out += 0x7E8 to it }
        tcm(req)?.let { out += 0x7E9 to it }
        return out
    }

    private fun dtcPayload(service: Int, codes: List<String>): IntArray {
        val bytes = mutableListOf(service + 0x40)
        if (carProtocol == '6') bytes += codes.size
        for (code in codes) {
            val (a, b) = Dtc.encode(code)
            bytes += a; bytes += b
        }
        if (carProtocol != '6') while ((bytes.size - 1) % 6 != 0 || bytes.size == 1) { bytes += 0; bytes += 0 }
        return bytes.toIntArray()
    }

    private fun engine(req: IntArray): IntArray? {
        val sid = req[0]
        return when (sid) {
            0x01 -> if (req.size >= 2) pid(req[1])?.let { intArrayOf(0x41, req[1]) + it } else null
            0x02 -> if (req.size >= 3) freeze(req[1])?.let { intArrayOf(0x42, req[1], 0x00) + it } else null
            0x03 -> dtcPayload(0x03, engineStored)
            0x07 -> dtcPayload(0x07, enginePending)
            0x0A -> dtcPayload(0x0A, enginePermanent)
            0x04 -> {
                engineStored.clear(); enginePending.clear(); tcmStored.clear()
                intArrayOf(0x44)
            }
            0x09 -> when (req.getOrNull(1)) {
                0x02 -> intArrayOf(0x49, 0x02, 0x01) + vin.map { it.code }.toIntArray()
                0x04 -> intArrayOf(0x49, 0x04, 0x01) + pad("03C906056DT", 16)
                0x0A -> intArrayOf(0x49, 0x0A, 0x01) + pad("ECM-EngineControl", 20)
                else -> null
            }
            else -> intArrayOf(0x7F, sid, 0x11)
        }
    }

    private fun tcm(req: IntArray): IntArray? = when (req[0]) {
        0x03 -> dtcPayload(0x03, tcmStored)
        0x07 -> dtcPayload(0x07, emptyList())
        0x04 -> intArrayOf(0x44)
        0x09 -> if (req.getOrNull(1) == 0x0A) intArrayOf(0x49, 0x0A, 0x01) + pad("TCM-TransmissionCtr", 20) else null
        0x01 -> if (req.getOrNull(1) == 0x00) intArrayOf(0x41, 0x00, 0x80, 0x00, 0x00, 0x00) else null
        else -> null
    }

    private fun pad(s: String, n: Int) = IntArray(n) { if (it < s.length) s[it].code else 0 }

    private fun mask(base: Int, set: Set<Int>): IntArray {
        val out = IntArray(4)
        for (p in set) {
            val off = p - base - 1
            if (off in 0..31) out[off / 8] = out[off / 8] or (0x80 shr (off % 8))
        }
        return out
    }

    private fun pid(p: Int): IntArray? {
        if (p % 0x20 == 0) {
            val m = mask(p, supportedPids)
            return if (p == 0 || p in supportedPids) m else null
        }
        if (p !in supportedPids) return null
        val t = (System.currentTimeMillis() - startMs) / 1000.0
        val rpm = (820 + 60 * sin(t)).toInt() * 4
        return when (p) {
            0x01 -> intArrayOf(
                (if (engineStored.isEmpty()) 0 else 0x80) or engineStored.size,
                0x07, 0x65, if (engineStored.isEmpty()) 0x25 else 0x04,
            )
            0x03 -> intArrayOf(0x02, 0x00)
            0x04 -> intArrayOf(0x55)
            0x05 -> intArrayOf(128)
            0x06 -> intArrayOf(0x82)
            0x07 -> intArrayOf(0x86)
            0x0B -> intArrayOf(33)
            0x0C -> intArrayOf(rpm shr 8, rpm and 0xFF)
            0x0D -> intArrayOf(0)
            0x0E -> intArrayOf(0x90)
            0x0F -> intArrayOf(65)
            0x10 -> intArrayOf(0x01, 0x5E)
            0x11 -> intArrayOf(0x26)
            0x13 -> intArrayOf(0x03)
            0x14 -> intArrayOf((90 + 60 * sin(t * 3)).toInt(), 0x80)
            0x15 -> intArrayOf(0x8C, 0xFF)
            0x1C -> intArrayOf(0x06)
            0x1F -> intArrayOf(0x00, t.toInt() and 0xFF)
            0x21 -> intArrayOf(0x00, 0x2A)
            0x2F -> intArrayOf(0x8F)
            0x31 -> intArrayOf(0x05, 0x10)
            0x33 -> intArrayOf(101)
            0x42 -> intArrayOf(0x37, 0x6E)
            0x46 -> intArrayOf(58)
            0x5C -> intArrayOf(132)
            else -> null
        }
    }

    private fun freeze(p: Int): IntArray? = when (p) {
        0x00 -> mask(0, setOf(0x02, 0x04, 0x05, 0x0C, 0x0D, 0x11))
        0x02 -> if (engineStored.isEmpty()) intArrayOf(0, 0) else Dtc.encode(engineStored.first()).let { intArrayOf(it.first, it.second) }
        0x04 -> intArrayOf(0x66)
        0x05 -> intArrayOf(122)
        0x0C -> intArrayOf(0x0F, 0xA0)
        0x0D -> intArrayOf(62)
        0x11 -> intArrayOf(0x33)
        else -> null
    }

    /** Physical (module) addressing: UDS / KWP requests. */
    private fun physical(tx: Int, req: IntArray): List<Pair<Int, IntArray>> {
        val m = modules[tx] ?: return emptyList()
        val sid = req[0]
        val answer: IntArray = when {
            sid == 0x19 && m.uds && req.size >= 3 && req[1] == 0x02 -> {
                val mask = req[2]
                val body = mutableListOf(0x59, 0x02, 0xFF)
                for ((code, ftb, st) in m.dtcs) {
                    if (st and mask == 0) continue
                    val (a, b) = Dtc.encode(code)
                    body += listOf(a, b, ftb, st)
                }
                body.toIntArray()
            }
            sid == 0x19 -> intArrayOf(0x7F, 0x19, 0x11)
            sid == 0x18 && m.kwp -> {
                val body = mutableListOf(0x58, m.dtcs.size)
                for ((code, _, _) in m.dtcs) {
                    val (a, b) = Dtc.encode(code)
                    body += listOf(a, b, 0xE0)
                }
                body.toIntArray()
            }
            sid == 0x14 -> {
                if (m.needsExtendedToClear && session != 3) {
                    intArrayOf(0x7F, 0x14, 0x22)
                } else {
                    m.dtcs.clear()
                    if (tx == 0x7E0) { engineStored.clear(); enginePending.clear() }
                    intArrayOf(0x54)
                }
            }
            sid == 0x10 && req.size >= 2 -> { session = req[1]; intArrayOf(0x50, req[1], 0x00, 0x32, 0x01, 0xF4) }
            sid == 0x22 && req.size >= 3 && req[1] == 0xF1 && req[2] == 0x97 && m.uds ->
                intArrayOf(0x62, 0xF1, 0x97) + m.name.map { it.code }.toIntArray()
            sid == 0x1A && m.kwp -> intArrayOf(0x5A, 0x9B) + m.name.map { it.code }.toIntArray()
            sid == 0x22 -> intArrayOf(0x7F, 0x22, 0x31)
            tx == 0x7E0 -> engine(req) ?: return emptyList()
            else -> intArrayOf(0x7F, sid, 0x11)
        }
        return listOf(m.rx to answer)
    }

    // ------------------------------------------------------------- formatting

    private fun hex(b: List<Int>) = b.joinToString(if (spaces) " " else "") { "%02X".format(it) }

    private fun format(rx: Int, payload: IntArray): List<String> {
        if (carProtocol != '6') {
            // ISO 9141: one line per 7 data bytes, "48 6B 10 <data> <checksum>"
            val lines = mutableListOf<String>()
            val chunks = if (payload[0] == 0x43 || payload[0] == 0x47) {
                payload.drop(1).chunked(6).map { listOf(payload[0]) + it }
            } else {
                listOf(payload.toList())
            }
            for (c in chunks) {
                val src = if (rx == 0x7E8) 0x10 else 0x18
                val frame = listOf(0x48, 0x6B, src) + c
                val all = frame + (frame.sum() and 0xFF)
                lines += if (headers) hex(all) else hex(c)
            }
            return lines
        }
        val h = "%03X".format(rx)
        val sep = if (spaces) " " else ""
        if (payload.size <= 7) {
            val frame = listOf(payload.size) + payload.toList() + List(7 - payload.size) { 0xAA }
            return listOf(if (headers) h + sep + hex(frame) else hex(payload.toList()))
        }
        val frames = mutableListOf<List<Int>>()
        frames += listOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(6)
        var idx = 6
        var seq = 1
        while (idx < payload.size) {
            val part = payload.drop(idx).take(7)
            frames += listOf(0x20 or (seq and 0x0F)) + part + List(7 - part.size) { 0xAA }
            idx += 7
            seq++
        }
        if (headers) return frames.map { h + sep + hex(it) }
        val lines = mutableListOf("%03X".format(payload.size))
        lines += frames.mapIndexed { i, f -> "${i % 16}:$sep" + hex(if (i == 0) f.drop(2) else f.drop(1)) }
        return lines
    }
}
