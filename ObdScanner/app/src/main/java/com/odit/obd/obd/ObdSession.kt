package com.odit.obd.obd

import com.odit.obd.elm.CommLog
import com.odit.obd.elm.EcuMessage
import com.odit.obd.elm.Elm327
import com.odit.obd.elm.ElmErrors
import com.odit.obd.elm.ElmTimeoutException
import com.odit.obd.elm.ObdProtocol
import com.odit.obd.elm.ResponseParser
import kotlinx.coroutines.delay
import java.io.IOException

class VehicleNotRespondingException(message: String) : IOException(message)

/** What the adapter answered when one protocol was tried, and how long its first answer took. */
data class ProtocolAttempt(val protocol: ObdProtocol, val answer: String, val ms: Long)

/** The answer to one OBD/UDS request, already split per control unit. */
class ObdReply(val lines: List<String>, val messages: List<EcuMessage>, val error: String?) {
    fun positive(service: Int): List<EcuMessage> =
        messages.filter { it.data.isNotEmpty() && it.data[0] == service + 0x40 }

    fun negative(service: Int): List<EcuMessage> =
        messages.filter { it.data.size >= 3 && it.data[0] == 0x7F && it.data[1] == service }

    val noData: Boolean get() = error == "NO DATA" || (error == null && messages.isEmpty())
}

data class ClearResult(val ok: Boolean, val message: String)

data class FreezeFrame(val dtc: String?, val values: List<Pair<PidDef, String>>)

data class EcuIdentity(val ecu: String, val name: String?, val calibrations: List<String>)

/**
 * Talks standard OBD-II (SAE J1979) through an ELM327 adapter.
 * Nothing here depends on the make or model: the car is asked directly, so no VIN lookup is needed.
 */
class ObdSession(
    val elm: Elm327,
    private val log: CommLog,
    /** Monotonic time in ms (the app passes the system's elapsed-time clock). */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    var protocol: ObdProtocol = ObdProtocol.AUTO
        private set
    var adapterVersion: String = ""
        private set
    var vehicleConnected = false
        private set

    /** Set by the module scanner when it changed headers/filters; restored before the next OBD request. */
    internal var adapterDirty = false

    /** Set when K-line physical addressing changed the header, wake-up message and protocol. */
    internal var kLineDirty = false

    /** Every protocol tried by the last [connectVehicle], for the "why did it not connect" diagnosis. */
    var attempts: List<ProtocolAttempt> = emptyList()
        private set
    private val attemptLog = mutableListOf<ProtocolAttempt>()

    private var supportedCache: Set<Int>? = null

    // ------------------------------------------------------------------ setup

    /** Resets the adapter and applies the settings this app relies on. Throws if the adapter is silent. */
    suspend fun initAdapter() {
        var reset = runCatching { elm.send("ATZ", 5_000) }
        if (reset.isFailure) {
            delay(500)
            reset = runCatching { elm.send("ATZ", 5_000) }
        }
        val lines = reset.getOrElse {
            // Some clones never answer ATZ; check they answer anything at all.
            elm.send("ATI", 3_000)
        }
        delay(300)
        applySettings()
        val ati = runCatching { elm.send("ATI", 2_000) }.getOrNull().orEmpty()
        adapterVersion = (ati + lines).firstOrNull { it.contains("ELM", true) || it.contains("v", true) }?.trim().orEmpty()
    }

    private suspend fun applySettings() {
        for (cmd in listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATD0", "ATAT1")) {
            runCatching { elm.send(cmd, 2_000) }
        }
    }

    /** Finds the protocol of the car. [preferred] is the one that worked last time, tried first. */
    suspend fun connectVehicle(preferred: ObdProtocol?, progress: (String) -> Unit): ObdProtocol {
        vehicleConnected = false
        supportedCache = null
        attemptLog.clear()
        attempts = emptyList()
        val tried = mutableSetOf<ObdProtocol>()
        try {
            if (preferred != null && preferred != ObdProtocol.AUTO) {
                progress("Опит с последния протокол: ${preferred.title}…")
                tried += preferred
                if (tryProtocol(preferred, 12_000)) return finishDetection(preferred)
            }

            progress("Автоматично търсене на протокол…")
            if (tryProtocol(ObdProtocol.AUTO, 25_000)) {
                val dpn = runCatching { elm.send("ATDPN", 2_000) }.getOrNull()?.firstOrNull()
                val p = dpn?.let { ObdProtocol.fromDpn(it) }
                if (p != null) return finishDetection(p)
            }

            // Some adapters' automatic search is unreliable, so walk the list by hand.
            val order = listOf(
                ObdProtocol.CAN_11_500, ObdProtocol.CAN_29_500, ObdProtocol.CAN_11_250, ObdProtocol.CAN_29_250,
                ObdProtocol.KWP_FAST, ObdProtocol.KWP_SLOW, ObdProtocol.ISO_9141,
                ObdProtocol.J1850_VPW, ObdProtocol.J1850_PWM,
            )
            for (p in order) {
                if (p in tried) continue
                progress("Опит: ${p.title}…")
                if (tryProtocol(p, if (p.isCan) 6_000L else 15_000L)) return finishDetection(p)
            }
            throw VehicleNotRespondingException(
                "Автомобилът не отговаря. Завъртете ключа на позиция ON (или запалете двигателя) и опитайте отново.",
            )
        } finally {
            attempts = attemptLog.toList()
        }
    }

    private suspend fun tryProtocol(p: ObdProtocol, timeoutMs: Long): Boolean {
        val kLine = p == ObdProtocol.KWP_FAST || p == ObdProtocol.KWP_SLOW || p == ObdProtocol.ISO_9141
        runCatching { elm.send("ATSP${p.code}", 2_000) }
        if (kLine) {
            // Old control units answer slowly: allow the longest wait, and let the bus rest before a new init.
            runCatching { elm.send("ATSTFF", 2_000) }
            delay(600)
        } else if (p != ObdProtocol.AUTO) {
            runCatching { elm.send("ATST32", 2_000) }
        }
        var firstMs = -1L
        var answer = "няма отговор"
        var ok = false
        for (attempt in 0 until if (kLine) 3 else 2) {
            val started = clock()
            val lines = try {
                elm.send("0100", timeoutMs)
            } catch (e: ElmTimeoutException) {
                answer = "адаптерът не отговори навреме"
                emptyList()
            }
            if (firstMs < 0) firstMs = clock() - started
            if (lines.any { it.replace(" ", "").contains("4100") }) {
                ok = true
                answer = "OK"
                break
            }
            val err = ElmErrors.find(lines)
            if (err != null) answer = err else if (lines.isNotEmpty()) answer = lines.joinToString(" | ").take(60)
            // Hard bus errors mean wrong protocol: do not retry. A failed K-line init is retried once,
            // because the first wake-up after switching the ignition on often fails.
            if (err != null && err != "NO DATA" && !(kLine && attempt == 0 && err == "BUS INIT ERROR")) break
            delay(if (kLine) 1_500 else 300)
        }
        attemptLog += ProtocolAttempt(p, answer, firstMs.coerceAtLeast(0))
        return ok
    }

    private fun finishDetection(p: ObdProtocol): ObdProtocol {
        protocol = p
        vehicleConnected = true
        log.note("протокол: ${p.title}")
        return p
    }

    // --------------------------------------------------------------- requests

    suspend fun request(cmd: String, timeoutMs: Long = 5_000): ObdReply {
        if (adapterDirty) restoreObdDefaults()
        val lines = elm.send(cmd, timeoutMs)
        val error = ElmErrors.find(lines)
        val messages = ResponseParser.parse(lines, protocol)
        return ObdReply(lines, messages, error)
    }

    /** Raw request for the module scanner (no automatic restore). */
    internal suspend fun rawRequest(cmd: String, timeoutMs: Long = 2_000): ObdReply {
        val lines = elm.send(cmd, timeoutMs)
        return ObdReply(lines, ResponseParser.parse(lines, protocol), ElmErrors.find(lines))
    }

    /** Undo header / filter / flow-control changes made for module access. */
    suspend fun restoreObdDefaults() {
        adapterDirty = false
        if (kLineDirty) {
            // Header, wake-up message and protocol were changed: start from the adapter's defaults.
            kLineDirty = false
            runCatching { elm.send("ATD", 3_000) }
            applySettings()
            if (vehicleConnected) runCatching { elm.send("ATSP${protocol.code}", 2_000) }
            runCatching { elm.send(if (protocol.isCan) "ATST32" else "ATSTFF", 2_000) }
            return
        }
        runCatching { elm.send("ATFCSM0", 2_000) }
        val cra = runCatching { elm.send("ATCRA", 2_000) }.getOrNull()
        if (cra == null || ElmErrors.find(cra) == "?") {
            // Old firmware: full reset of settings, then re-apply ours and the protocol.
            runCatching { elm.send("ATD", 3_000) }
            applySettings()
            runCatching { elm.send("ATSP${protocol.code}", 2_000) }
        }
        if (protocol.is29bit) {
            runCatching { elm.send("ATCP18", 2_000) }
            runCatching { elm.send("ATSHDB33F1", 2_000) }
        } else if (protocol.isCan) {
            runCatching { elm.send("ATSH7DF", 2_000) }
        } else if (protocol == ObdProtocol.KWP_FAST || protocol == ObdProtocol.KWP_SLOW) {
            // ELM327 default header for ISO 14230-4 (functional OBD request).
            runCatching { elm.send("ATSHC133F1", 2_000) }
        }
        // K-line control units can be slow; with adaptive timing this is only the upper limit.
        runCatching { elm.send(if (protocol.isCan) "ATST32" else "ATSTFF", 2_000) }
        runCatching { elm.send("ATAT1", 2_000) }
    }

    suspend fun batteryVoltage(): Double? {
        if (adapterDirty) restoreObdDefaults()
        val lines = runCatching { elm.send("ATRV", 2_000) }.getOrNull() ?: return null
        return lines.firstNotNullOfOrNull { l ->
            Regex("""(\d{1,2}[.,]\d)""").find(l)?.value?.replace(',', '.')?.toDoubleOrNull()
        }
    }

    // ------------------------------------------------------------------- DTCs

    /** Mode 03 (stored), 07 (pending) or 0A (permanent) from every OBD control unit. */
    suspend fun readDtcs(mode: Int): List<EcuDtc> {
        val kind = when (mode) {
            0x07 -> DtcKind.PENDING
            0x0A -> DtcKind.PERMANENT
            else -> DtcKind.STORED
        }
        val reply = request("%02X".format(mode), 8_000)
        if (reply.error != null && reply.error != "NO DATA") throw IOException("Грешка от адаптера: ${reply.error}")
        val result = mutableListOf<EcuDtc>()
        for (m in reply.positive(mode)) {
            var payload = m.data.drop(1)
            if (protocol.isCan && payload.size % 2 == 1) payload = payload.drop(1) // count byte
            for (i in 0 until payload.size / 2) {
                val a = payload[i * 2]
                val b = payload[i * 2 + 1]
                if (a == 0 && b == 0) continue
                val d = EcuDtc(m.header, Dtc(Dtc.decode(a, b), kind))
                if (d !in result) result += d
            }
        }
        return result
    }

    /** Mode 04. Also resets readiness monitors and freeze frame data. */
    suspend fun clearDtcs(): ClearResult {
        val reply = request("04", 10_000)
        if (reply.positive(0x04).isNotEmpty()) {
            supportedCache = null
            return ClearResult(true, "Грешките са изтрити.")
        }
        val nrc = reply.negative(0x04).firstOrNull()?.data?.getOrNull(2)
        return when {
            nrc == 0x22 -> ClearResult(false, "Автомобилът отказа: условията не са изпълнени. Изгасете двигателя, оставете запалването включено и опитайте отново.")
            nrc != null -> ClearResult(false, "Автомобилът отказа изтриването (код 0x%02X).".format(nrc))
            reply.error != null && reply.error != "NO DATA" -> ClearResult(false, "Грешка от адаптера: ${reply.error}")
            else -> ClearResult(false, "Няма потвърждение от автомобила. Прочетете грешките отново, за да проверите.")
        }
    }

    // ------------------------------------------------------------- live data

    /** Supported PIDs for mode 01 (live) or 02 (freeze frame 0), union of all control units. */
    suspend fun supportedPids(mode: Int = 1): Set<Int> {
        if (mode == 1) supportedCache?.let { return it }
        val result = sortedSetOf<Int>()
        var base = 0
        while (base <= 0xE0) {
            val cmd = if (mode == 1) "01%02X".format(base) else "02%02X00".format(base)
            val msgs = request(cmd).positive(mode).filter { it.data.size >= 2 && it.data[1] == base }
            if (msgs.isEmpty()) break
            val offset = if (mode == 2) 3 else 2
            for (m in msgs) {
                for (byteIdx in 0 until 4) {
                    val v = m.data.getOrNull(offset + byteIdx) ?: continue
                    for (bit in 0 until 8) {
                        if (v and (0x80 shr bit) != 0) result += base + byteIdx * 8 + bit + 1
                    }
                }
            }
            if (base + 0x20 !in result) break
            base += 0x20
        }
        if (mode == 1) supportedCache = result
        return result
    }

    private fun preferEngine(msgs: List<EcuMessage>): EcuMessage? =
        msgs.firstOrNull { it.header == "7E8" || it.header.endsWith("F110") || it.header == "10" } ?: msgs.firstOrNull()

    suspend fun readPidRaw(pid: Int): IntArray? {
        val msgs = request("01%02X".format(pid), 3_000).positive(0x01).filter { it.data.size >= 2 && it.data[1] == pid }
        val m = preferEngine(msgs) ?: return null
        return m.data.copyOfRange(2, m.data.size)
    }

    suspend fun readPid(pid: Int): Double? {
        val def = Pids.all[pid] ?: return null
        return readPidRaw(pid)?.let { def.decode(it) }
    }

    suspend fun readiness(): Readiness? {
        val d = readPidRaw(0x01) ?: return null
        if (d.size < 4) return null
        return Readiness.decode(d[0], d[1], d[2], d[3])
    }

    suspend fun freezeFrame(): FreezeFrame? {
        val supported = supportedPids(2)
        if (supported.isEmpty()) return null
        var dtc: String? = null
        if (0x02 in supported) {
            val m = preferEngine(request("020200").positive(0x02).filter { it.data.size >= 5 && it.data[1] == 0x02 })
            if (m != null && (m.data[3] != 0 || m.data[4] != 0)) dtc = Dtc.decode(m.data[3], m.data[4])
        }
        val values = mutableListOf<Pair<PidDef, String>>()
        for (pid in supported) {
            if (Pids.isSupportMask(pid)) continue
            val def = Pids.all[pid] ?: continue
            val m = preferEngine(request("02%02X00".format(pid), 3_000).positive(0x02).filter { it.data.size >= 3 && it.data[1] == pid })
                ?: continue
            values += def to def.formatRaw(m.data.copyOfRange(3, m.data.size))
        }
        return FreezeFrame(dtc, values)
    }

    // ---------------------------------------------------------- vehicle info

    /** VIN from OBD mode 09 PID 02 only (see [com.odit.obd.vin.VinReader] for the full chain). */
    suspend fun readVin(): String? {
        val found = com.odit.obd.vin.VinParser.parse0902(request("0902", 6_000).lines, protocol)
        return (found.firstOrNull { isEngine(it.ecu) } ?: found.firstOrNull())?.vin
    }

    /** ECU names (mode 09 PID 0A) and calibration ids (PID 04), per control unit. */
    suspend fun identities(): List<EcuIdentity> {
        val names = mutableMapOf<String, String>()
        runCatching {
            for ((ecu, list) in request("090A").positive(0x09).filter { it.data.size > 3 && it.data[1] == 0x0A }.groupBy { it.header }) {
                names[ecu] = ascii(list.flatMap { it.data.drop(3) })
            }
        }
        val cals = mutableMapOf<String, List<String>>()
        runCatching {
            for ((ecu, list) in request("0904").positive(0x09).filter { it.data.size > 3 && it.data[1] == 0x04 }.groupBy { it.header }) {
                val bytes = list.flatMap { it.data.drop(3) }
                cals[ecu] = bytes.chunked(16).map { ascii(it) }.filter { it.isNotBlank() }
            }
        }
        return (names.keys + cals.keys).sorted().map { EcuIdentity(it, names[it]?.ifBlank { null }, cals[it].orEmpty()) }
    }

    private fun ascii(bytes: List<Int>) = bytes.filter { it in 0x20..0x7E }.joinToString("") { it.toChar().toString() }.trim()

    companion object {
        fun isEngine(header: String): Boolean = header.uppercase() in setOf("7E8", "18DAF110", "10")

        /** Friendly name for the sender of an OBD answer. */
        fun ecuName(header: String): String = when (header.uppercase()) {
            "7E8", "18DAF110", "10" -> "Двигател"
            "7E9", "18DAF118", "18" -> "Скоростна кутия"
            "7EA", "18DAF128", "28" -> "ABS / хибрид"
            else -> "Модул $header"
        }
    }
}
