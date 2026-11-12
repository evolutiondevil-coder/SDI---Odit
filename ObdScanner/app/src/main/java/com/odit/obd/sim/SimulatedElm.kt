package com.odit.obd.sim

import com.odit.obd.bt.ObdTransport
import com.odit.obd.obd.Dtc
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * A fake ELM327 v1.5 plugged into a fake car, used for the demo mode and for tests.
 * The car speaks ISO 15765-4 CAN 11-bit 500k (or ISO 9141 for legacy tests) and has a few
 * control units with trouble codes.
 */
class SimulatedElm(
    private val carProtocol: Char = '6',
    private val chunkSize: Int = 20,
    val scenario: DemoScenario = DemoScenario.NORMAL,
    /**
     * Behave like the MUCAR adapter in the real logs: AT SI wakes a unit up but leaves no link to use
     * (the next request wakes it again, and fails while the unit is still in that session); AT SP to the
     * protocol already selected keeps the K-line link; AT PC is unknown; AT D keeps the custom header
     * and echo (only AT Z resets everything).
     */
    private val cloneQuirks: Boolean = false,
    /** Speed of simulated time: tests run the car faster (and set the same factor on the session). */
    private val timeScale: Double = 1.0,
    /** Clock of the car (the app passes the one it gives the session, so UI tests share virtual time). */
    private val clock: () -> Long = System::currentTimeMillis,
) : ObdTransport {

    /** Car for a demo scenario. */
    constructor(
        scenario: DemoScenario,
        cloneQuirks: Boolean = false,
        timeScale: Double = 1.0,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        when (scenario) {
            DemoScenario.KLINE -> '5'
            DemoScenario.OLD_RENAULT, DemoScenario.OLD_VW -> 'X' // no OBD2 protocol at all
            else -> '6'
        },
        20, scenario, cloneQuirks, timeScale, clock,
    )

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

    val vin = when (scenario) {
        DemoScenario.UNKNOWN_WMI -> "Y3MZZZ15ZKA000777"
        DemoScenario.KLINE -> "VF1BB0A0526123456"
        DemoScenario.OLD_RENAULT -> "VF1JA0B0521234567"
        DemoScenario.OLD_VW -> "WVWZZZ1JZ2W123456"
        else -> "WVWZZZ1KZ8W123456"
    }

    /** VIN stored in another car's module (conflict scenario). */
    val otherVin = "WVWZZZ1KZ6W654321"

    private val obdVinSupported =
        scenario in setOf(DemoScenario.NORMAL, DemoScenario.VIN_CONFLICT, DemoScenario.UNKNOWN_WMI, DemoScenario.ACTIVE_FAULT)

    private class Module(
        val rx: Int,
        val name: String,
        val dtcs: MutableList<Triple<String, Int, Int>>, // code, ftb, status
        val uds: Boolean = true,
        val kwp: Boolean = false,
        val needsExtendedToClear: Boolean = false,
        val vin: String? = null,
    )

    private fun moduleVin(tx: Int): String? = when (scenario) {
        DemoScenario.NORMAL, DemoScenario.UNKNOWN_WMI -> vin
        DemoScenario.VIN_UDS_ENGINE -> if (tx == 0x7E0) vin else null
        DemoScenario.VIN_OTHER_MODULE -> if (tx == 0x714) vin else null
        DemoScenario.VIN_CONFLICT -> if (tx == 0x713) otherVin else vin
        DemoScenario.KLINE, DemoScenario.OLD_RENAULT, DemoScenario.OLD_VW -> null
        DemoScenario.ACTIVE_FAULT -> vin
    }

    // ------------------------------------------------- old car: K-line units with physical addressing
    private class KUnit(
        val ident: String,
        val dtcs: MutableList<Triple<Int, Int, Int>>,
        val vin: String?,
        /** Wakes only with the 5-baud init (VW group). */
        val slowOnly: Boolean = false,
        /** Speaks VW's KW1281: sends sync and key bytes 01 8A, then a handshake an ELM327 cannot do. */
        val kw1281: Boolean = false,
        val vag: Boolean = false,
        /** Key bytes it answers a wake-up with. */
        val keyWords: Pair<Int, Int> = 0xEF to 0x8F,
        /** Renault "ISO8": wakes on the 5-baud address 0x33 on ISO 9141, then answers its own address. */
        val iso8: Boolean = false,
    )

    private val kUnits: Map<Int, KUnit> = when (scenario) {
        DemoScenario.OLD_VW -> mapOf(
            // 16684 = P0300 (0x412C), 17978 = P1570 (0x463A), 18010 has no SAE equivalent (0x465A).
            0x01 to KUnit("06A906032HN MARELLI 4LV", mutableListOf(Triple(0x41, 0x2C, 0x23), Triple(0x46, 0x3A, 0x23), Triple(0x46, 0x5A, 0x23)), null, slowOnly = true, vag = true),
            // The Golf IV cluster of the real log: KWP2000, key bytes 6B 8F. 18010 = immobilizer code (0x465A).
            0x17 to KUnit("1J0920826 KOMBI+WEGFAHRSPERRE", mutableListOf(Triple(0x46, 0x5A, 0x23)), null, slowOnly = true, vag = true, keyWords = 0x6B to 0x8F),
            0x25 to KUnit("6X0953257 IMMO", mutableListOf(), null, slowOnly = true, kw1281 = true),
            0x56 to KUnit("1J0035180B RADIO", mutableListOf(), null, slowOnly = true, kw1281 = true),
        )
        else -> mapOf(
            // Like the Renault Scénic I of the real log: wakes at 0x33 with 08 08, ignores OBD requests.
            0x7A to KUnit("SIEMENS SIRIUS32 7700112233", mutableListOf(Triple(0x01, 0x20, 0xA1), Triple(0x03, 0x35, 0x21)), vin, keyWords = 0x08 to 0x08, iso8 = true),
            0x2C to KUnit("AUTOLIV AB8.0", mutableListOf(Triple(0x00, 0x15, 0x81)), null),
            0x26 to KUnit("UCH N2", mutableListOf(), vin),
        )
    }

    private val isOldCar: Boolean get() = scenario == DemoScenario.OLD_RENAULT || scenario == DemoScenario.OLD_VW

    /** AT KW0 / KW1: key-word checking during the K-line wake-up. */
    private var keyWordCheck = true

    /** Key bytes of the last wake-up that got them (AT KW keeps them when a later one fails). */
    private var lastKeyWords: Pair<Int, Int>? = null

    /** Wake-up address set by AT IIA (slow init). */
    private var initAddress = 0x33

    /** Whether AT AL (long messages) is on. */
    private var allowLong = false

    /** Address of the unit the adapter's K-line link is up with, if any. */
    private var kOpen: Int? = null

    /** Units still in a session after the link to them was dropped: address -> until (ms). They ignore wake-ups. */
    private val busyUntil = HashMap<Int, Long>()

    /** Time the answer being prepared would take on a real adapter. */
    private var pendingDelayMs = 0L

    private fun now() = clock()

    /** The link ends; the unit keeps its session for P3max (5 s) after the last keep-alive. */
    private fun dropLink() {
        kOpen?.let { busyUntil[it] = now() + (5_000 * timeScale).toLong() }
        kOpen = null
    }

    private fun isBusy(addr: Int) = (busyUntil[addr] ?: 0L) > now()

    private fun kAddress(): Int? = if ((header shr 16) == 0x81 && selected in "345") (header shr 8) and 0xFF else null

    /** The ISO8 unit, if this car has one. */
    private fun iso8Unit(): Pair<Int, KUnit>? = kUnits.entries.firstOrNull { it.value.iso8 }?.toPair()

    /**
     * A wake-up in the current protocol to [target] (fast init) or to the AT IIA address (5 baud).
     * Returns the address of the unit that is now linked, or null.
     */
    private fun wake(target: Int?): Int? {
        when (selected) {
            '5' -> {
                pendingDelayMs = 350
                val unit = target?.let { kUnits[it] } ?: return null.also { pendingDelayMs = 2_150 }
                if (unit.slowOnly || unit.iso8 || isBusy(target)) return null.also { pendingDelayMs = 2_150 }
                lastKeyWords = unit.keyWords
                return target
            }
            '3', '4' -> {
                // Sending the address byte at 5 baud alone takes 2 s; no answer at all ends after ~2.55 s.
                pendingDelayMs = 2_550
                val iso8 = iso8Unit()
                val (addr, unit) = when {
                    initAddress == 0x33 && iso8 != null -> iso8
                    else -> initAddress to (kUnits[initAddress] ?: return null)
                }
                // The ISO8 unit wakes only on ISO 9141 (the real Scénic ignored the same address on protocol 4).
                if (unit.iso8 && (initAddress != 0x33 || selected != '3')) return null
                if (isBusy(addr)) return null
                pendingDelayMs = 2_300
                lastKeyWords = unit.keyWords.takeUnless { unit.kw1281 } ?: (0x01 to 0x8A)
                // KW1281 sends a data block where the ELM327 waits for the inverted address; the adapter
                // gives up later than when nobody answers (2.7–3.0 s in the real Golf IV logs).
                if (unit.kw1281) return null.also { pendingDelayMs = 2_900 }
                if (selected == '4' && keyWordCheck && unit.keyWords.second != 0x8F) return null
                if (selected == '3' && keyWordCheck && unit.keyWords != (0x08 to 0x08) && unit.keyWords != (0x94 to 0x94)) return null
                return addr
            }
            else -> return null
        }
    }

    private fun oldCar(req: IntArray): List<String> {
        val target = kAddress()
        val prefix = mutableListOf<String>()
        if (kOpen == null && selected in "345") {
            val linked = wake(target)
            if (linked == null) return listOf(if (selected == '5') "BUS INIT: ERROR" else "BUS INIT: ...ERROR")
            kOpen = linked
            prefix += if (selected == '5') "BUS INIT: OK" else "BUS INIT: ...OK"
        }
        if (target == null) {
            return when (selected) {
                '0' -> { pendingDelayMs = 3_000; listOf("SEARCHING...", "UNABLE TO CONNECT") }
                // A unit woke up, but nothing here answers standard OBD requests.
                '3', '4', '5' -> { pendingDelayMs = 1_000; prefix + "NO DATA" }
                '6', '7', '8', '9' -> listOf("CAN ERROR")
                else -> listOf("NO DATA")
            }
        }
        // Only the linked unit listens (in ISO8 that is the unit that woke at 0x33, at its own address).
        val addr = target.takeIf { it == kOpen }
        val unit = addr?.let { kUnits[it] }
        if (unit == null || unit.kw1281) { pendingDelayMs = 1_000; return prefix + "NO DATA" }
        val sid = req[0]
        val answer: IntArray = when {
            sid == 0x10 && req.getOrNull(1) == 0xC0 && !unit.vag -> intArrayOf(0x50, 0xC0)
            sid == 0x10 && req.getOrNull(1) == 0x89 && unit.vag -> intArrayOf(0x50, 0x89)
            sid == 0x1A && req.getOrNull(1) == 0x9B && unit.vag -> intArrayOf(0x5A, 0x9B) + unit.ident.map { it.code }.toIntArray()
            sid == 0x18 && unit.vag -> intArrayOf(0x58, unit.dtcs.size) + unit.dtcs.flatMap { listOf(it.first, it.second, it.third) }.toIntArray()
            sid == 0x17 && unit.vag -> intArrayOf(0x7F, 0x17, 0x11)
            sid == 0x21 && unit.vag -> intArrayOf(0x7F, 0x21, 0x11)
            sid == 0x10 -> intArrayOf(0x7F, 0x10, 0x12)
            sid == 0x3E -> intArrayOf(0x7E)
            sid == 0x21 && req.getOrNull(1) == 0x80 -> intArrayOf(0x61, 0x80, 0x00) + unit.ident.map { it.code }.toIntArray()
            sid == 0x21 && req.getOrNull(1) == 0x81 ->
                unit.vin?.let { intArrayOf(0x61, 0x81) + it.map { c -> c.code }.toIntArray() } ?: intArrayOf(0x7F, 0x21, 0x12)
            sid == 0x17 -> intArrayOf(0x57, unit.dtcs.size) + unit.dtcs.flatMap { listOf(it.first, it.second, it.third) }.toIntArray()
            sid == 0x14 -> { unit.dtcs.clear(); intArrayOf(0x54, 0xFF, 0x00) }
            else -> intArrayOf(0x7F, sid, 0x11)
        }
        // Without AT AL an ELM327 does not take answers longer than the 7 bytes of OBD messages.
        if (answer.size > 7 && !allowLong) return prefix + "<DATA ERROR"
        pendingDelayMs = 60
        // ISO 14230 frame: format (+length), target, source, data, checksum.
        val head = if (answer.size <= 63) listOf(0x80 or answer.size, 0xF1, addr) else listOf(0x80, 0xF1, addr, answer.size)
        val frame = head + answer.toList()
        val line = if (headers) hex(frame + (frame.sum() and 0xFF)) else hex(answer.toList())
        return prefix + line
    }

    private val modules: Map<Int, Module> = mapOf(
        0x7E0 to Module(0x7E8, "ECM 1.4 TSI", mutableListOf(Triple("P0301", 0x00, 0x2F), Triple("P0171", 0x00, 0x28)), vin = moduleVin(0x7E0)),
        0x7E1 to Module(0x7E9, "DSG 7", mutableListOf(Triple("P0741", 0x00, 0x08)), vin = moduleVin(0x7E1)),
        0x713 to Module(0x77D, "ESP MK100", mutableListOf(Triple("C0035", 0x29, 0x09)), vin = moduleVin(0x713)),
        0x715 to Module(0x77F, "Airbag VW", mutableListOf(Triple("B1001", 0x13, 0x08)), needsExtendedToClear = true, vin = moduleVin(0x715)),
        0x714 to Module(0x77E, "KOMBI", mutableListOf(Triple("U0121", 0x00, 0x20), Triple("U0155", 0x00, 0x50)), vin = moduleVin(0x714)),
        0x70E to Module(0x778, "BCM PQ35", mutableListOf(), vin = moduleVin(0x70E)),
        0x710 to Module(0x77A, "Gateway", mutableListOf(Triple("U0100", 0x00, 0x08)), uds = false, kwp = true, vin = moduleVin(0x710)),
    )

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
            val lines = handle(cmd.trim())
            // A real adapter needs time to try to wake a K-line unit up before it gives up.
            val wait = (pendingDelayMs * timeScale).toLong()
            pendingDelayMs = 0
            if (wait > 0) delay(wait)
            for (line in lines) out.append(line).append('\r')
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

    private fun at(c: String): List<String> = atCommand(c)

    private fun atCommand(c: String): List<String> = when {
        c == "Z" -> { reset(); listOf("", "ELM327 v1.5") }
        c == "I" -> listOf("ELM327 v1.5")
        c == "D" -> { if (cloneQuirks) partialDefaults() else reset(); listOf("OK") }
        c == "AL" -> { allowLong = true; listOf("OK") }
        c == "NL" -> { allowLong = false; listOf("OK") }
        c == "E0" -> { echo = false; listOf("OK") }
        c == "E1" -> { echo = true; listOf("OK") }
        c == "S0" -> { spaces = false; listOf("OK") }
        c == "S1" -> { spaces = true; listOf("OK") }
        c == "H0" -> { headers = false; listOf("OK") }
        c == "H1" -> { headers = true; listOf("OK") }
        c == "RV" -> listOf("12.6V")
        c == "DPN" -> listOf(if (selected == '0') "A$carProtocol" else "$selected")
        c == "@1" -> listOf("OBDII to RS232 Interpreter")
        c == "KW0" -> { keyWordCheck = false; listOf("OK") }
        c == "KW1" -> { keyWordCheck = true; listOf("OK") }
        // The MUCAR clone prints the key bytes without leading zeros: "1:8 2:8".
        c == "KW" -> lastKeyWords?.let { listOf("1:%X 2:%X".format(it.first, it.second)) } ?: listOf("NO DATA")
        c.startsWith("IIA") -> { initAddress = c.substring(3).toInt(16); listOf("OK") }
        c == "IB10" || c == "IB96" -> listOf("OK")
        c == "FI" -> {
            if (isOldCar && selected == '5') {
                dropLink()
                wake(kAddress())?.let { kOpen = it; listOf("BUS INIT: OK") } ?: listOf("BUS INIT: ERROR")
            } else {
                pendingDelayMs = 350
                listOf("BUS INIT: ERROR")
            }
        }
        c == "SI" -> {
            if (isOldCar && selected in "34") {
                dropLink()
                wake(null)?.let {
                    // The clone: the unit is now in a session, but the adapter has no link to it.
                    if (cloneQuirks) busyUntil[it] = now() + (5_000 * timeScale).toLong() else kOpen = it
                    listOf("BUS INIT: ...OK")
                } ?: listOf("BUS INIT: ...ERROR")
            } else {
                // Sending the address byte at 5 baud alone takes 2 s.
                pendingDelayMs = 2_600
                listOf("BUS INIT: ...ERROR")
            }
        }
        c == "STI" -> listOf("?") // not an STN chip
        c.startsWith("SPA") -> { selected = '0'; detected = false; listOf("OK") }
        c == "PC" -> if (cloneQuirks) listOf("?") else { dropLink(); detected = false; listOf("OK") }
        c.startsWith("SP") || c.startsWith("TP") -> {
            // The clone keeps the link when the protocol does not change (real Golf IV log).
            if (!(cloneQuirks && c.last() == selected)) { dropLink(); detected = false }
            selected = c.last()
            listOf("OK")
        }
        c.startsWith("FCSH") -> { fcHeader = c.substring(4).toIntOrNull(16); listOf("OK") }
        c.startsWith("FCSM") -> { fcManual = c.endsWith("1"); listOf("OK") }
        c.startsWith("SH") -> {
            val h = c.substring(2)
            header = if (h.length == 3) h.toInt(16) else h.takeLast(6).toInt(16)
            listOf("OK")
        }
        c.startsWith("CRA") || c.startsWith("CF") || c.startsWith("CM") || c.startsWith("FC") ||
            c.startsWith("ST") || c.startsWith("CP") || c.startsWith("AT") || c.startsWith("L") ||
            c.startsWith("D0") || c.startsWith("D1") || c.startsWith("CAF") || c.startsWith("SW") ||
            c.startsWith("WM") || c.startsWith("IB") -> listOf("OK")
        else -> listOf("?")
    }

    private fun reset() {
        dropLink()
        fcManual = false; fcHeader = null
        echo = true; spaces = true; headers = false; header = 0x7DF; session = 1
        selected = '0'; detected = false
        keyWordCheck = true; initAddress = 0x33; allowLong = false
    }

    /** AT D on the clone: settings back, but the header, echo and init address stay (real logs). */
    private fun partialDefaults() {
        dropLink()
        spaces = true; headers = false; session = 1
        detected = false
        keyWordCheck = true; allowLong = false
    }

    // -------------------------------------------------------------------- car

    private fun obd(req: IntArray): List<String> {
        if (isOldCar) return oldCar(req)
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
            header == 0x8110F1 -> kLinePhysical(req)
            // A request with another unit's physical header never reaches the engine's OBD part.
            carProtocol in "345" && (header shr 16) == 0x81 -> emptyList()
            else -> obdFunctional(req)
        }
        if (answers.isEmpty()) return prefix + "NO DATA"
        val lines = mutableListOf<String>()
        for ((rx, payload) in answers) {
            val frames = format(rx, payload)
            // ISO-TP: after its first frame a module waits for flow control at its own request id. The
            // adapter sends it to "reply id - 8" unless told otherwise (AT FC SH + FC SM 1). Wrong id:
            // the module stops after the first frame and ignores requests until it gives up (~1 s).
            val fcTo = if (fcManual) fcHeader else rx - 8
            if (carProtocol == '6' && header != 0x7DF && payload.size > 7 && headers && fcTo != header) {
                lines += frames.first()
                moduleBusyUntil[header] = now() + (1_000 * timeScale).toLong()
            } else {
                lines += frames
            }
        }
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
                // A fault that is present right now is detected again at once.
                if (scenario == DemoScenario.ACTIVE_FAULT) engineStored += "P0301"
                intArrayOf(0x44)
            }
            0x09 -> when (req.getOrNull(1)) {
                0x02 -> if (obdVinSupported) intArrayOf(0x49, 0x02, 0x01) + vin.map { it.code }.toIntArray() else null
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

    /** K-line KWP2000 physical request to the engine (address 0x10). */
    private fun kLinePhysical(req: IntArray): List<Pair<Int, IntArray>> = when {
        scenario == DemoScenario.KLINE && req.size >= 2 && req[0] == 0x1A && req[1] == 0x90 ->
            listOf(0x7E8 to (intArrayOf(0x5A, 0x90) + vin.map { it.code }.toIntArray()))
        req.isNotEmpty() -> listOf(0x7E8 to intArrayOf(0x7F, req[0], 0x11))
        else -> emptyList()
    }

    /** Flow control settings (AT FC SH / AT FC SM). */
    private var fcManual = false
    private var fcHeader: Int? = null

    /** Modules still waiting for flow control that never came: they ignore requests until then. */
    private val moduleBusyUntil = HashMap<Int, Long>()

    /** Physical (module) addressing: UDS / KWP requests. */
    private fun physical(tx: Int, req: IntArray): List<Pair<Int, IntArray>> {
        val m = modules[tx] ?: return emptyList()
        if ((moduleBusyUntil[tx] ?: 0L) > now()) return emptyList()
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
            sid == 0x22 && req.size >= 3 && req[1] == 0xF1 && req[2] == 0x90 && m.uds ->
                m.vin?.let { intArrayOf(0x62, 0xF1, 0x90) + it.map { c -> c.code }.toIntArray() } ?: intArrayOf(0x7F, 0x22, 0x31)
            sid == 0x1A && m.kwp && req.getOrNull(1) == 0x90 ->
                m.vin?.let { intArrayOf(0x5A, 0x90) + it.map { c -> c.code }.toIntArray() } ?: intArrayOf(0x7F, 0x1A, 0x31)
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
            } else if (payload.size > 3 && payload[0] == 0x49 && payload[1] == 0x02) {
                // SAE J1979 non-CAN: 5 messages "49 02 <n> b b b b", the first padded with three zeros.
                val chars = listOf(0, 0, 0) + payload.drop(3)
                chars.chunked(4).mapIndexed { i, part -> listOf(0x49, 0x02, i + 1) + part }
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
