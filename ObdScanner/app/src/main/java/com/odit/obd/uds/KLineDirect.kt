package com.odit.obd.uds

import com.odit.obd.elm.EcuMessage
import com.odit.obd.elm.ElmErrors
import com.odit.obd.elm.ElmTimeoutException
import com.odit.obd.elm.ObdProtocol
import com.odit.obd.elm.ResponseParser
import com.odit.obd.obd.ClearResult
import com.odit.obd.obd.Dtc
import com.odit.obd.obd.DtcKind
import com.odit.obd.obd.ObdSession
import com.odit.obd.vin.VinParser
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A control unit to try on the K-line: its KWP2000 address and, when known, what it is. */
/** How a unit is woken up: fast init (ISO 14230) first, or only the 5-baud slow init (VW group). */
enum class KInit { FAST_THEN_SLOW, SLOW }

/** How the unit numbers its trouble codes. */
enum class KCodes {
    /** Manufacturer numbering shown as raw bytes (Renault). */
    RAW,

    /** VW group 5-digit numbers (16684 = P0300). */
    VAG,
}

data class KLineTarget(
    val address: Int,
    val name: String?,
    val init: KInit = KInit.FAST_THEN_SLOW,
    val codes: KCodes = KCodes.RAW,
)

/**
 * EXPERIMENTAL. Talks KWP2000 (ISO 14230) directly to one control unit at a time on the K-line,
 * with physical addressing. This is how cars from before OBD2 (and the non-engine modules of early
 * OBD2 cars) are reached; the standard OBD requests never get an answer from them.
 *
 * Sequence per unit (the one Renault's own tools use): header 81 <addr> F1, wake-up message
 * 81 <addr> F1 3E every 3 s, fast init (slow init as a fallback), start session 10 C0 (or 10 81),
 * identification 21 80, VIN 21 81 (or 1A 90), trouble codes 17 FF 00 (or 18 02 FF 00 / 18 00 FF 00),
 * clear 14 FF 00.
 *
 * VW group units are woken with the 5-baud init only. Key-word checking is turned off (AT KW0) and
 * the key bytes are read back (AT KW): 01 8A means the unit speaks VW's older KW1281 protocol, which
 * needs a byte-by-byte handshake an ELM327 cannot do, so it is reported as found but not readable.
 *
 * Not yet confirmed on a real car with this adapter; every step is in the exported log.
 */
class KLineDirect(private val s: ObdSession) {

    private enum class Woke { NO, KWP, KW1281 }

    /** Targets of the modules found, to talk to them the same way when clearing. */
    private val targetOf = HashMap<Int, KLineTarget>()

    suspend fun scan(
        targets: List<KLineTarget>,
        onProgress: (done: Int, total: Int, label: String) -> Unit,
        onFound: (FoundModule) -> Unit,
    ): List<FoundModule> {
        val found = mutableListOf<FoundModule>()
        try {
            for ((i, t) in targets.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(i, targets.size, "K-line 0x%02X".format(t.address))
                val m = probe(t) ?: continue
                found += m
                onFound(m)
            }
            onProgress(1, 1, "")
        } finally {
            withContext(NonCancellable) { runCatching { s.restoreObdDefaults() } }
        }
        return found
    }

    /** Clears each unit and reads it again. */
    suspend fun clear(modules: List<FoundModule>): List<Pair<ClearResult, FoundModule?>> {
        val out = mutableListOf<Pair<ClearResult, FoundModule?>>()
        try {
            for (m in modules) {
                currentCoroutineContext().ensureActive()
                val target = m.kLine ?: KLineTarget(m.tx, m.catalogName)
                val woke = open(target)
                val result = if (woke == Woke.KW1281) {
                    ClearResult(false, KW1281_TEXT)
                } else if (woke == Woke.NO) {
                    ClearResult(false, "Няма отговор от модула")
                } else {
                    startSession()
                    val r = request("14FF00", 0x14)
                    when {
                        r == null -> ClearResult(false, "Няма отговор от модула")
                        nrc(r) == null -> ClearResult(true, "Изтрито")
                        nrc(r) == 0x22 -> ClearResult(false, "Отказано: условията не са изпълнени (изгасете двигателя)")
                        else -> ClearResult(false, "Отказано: ${ModuleScanner.nrcText(nrc(r)!!)}")
                    }
                }
                delay(500)
                out += result to probe(target)
            }
        } finally {
            withContext(NonCancellable) { runCatching { s.restoreObdDefaults() } }
        }
        return out
    }

    // ---------------------------------------------------------------- internals

    private suspend fun cmd(c: String, timeoutMs: Long = 2_000): List<String> =
        try {
            s.elm.send(c, timeoutMs)
        } catch (_: ElmTimeoutException) {
            emptyList()
        }

    /**
     * Runs an init command. true = the unit woke up, false = nothing answered,
     * null = the adapter does not know the command.
     */
    private suspend fun init(command: String, timeoutMs: Long): Boolean? {
        val lines = try {
            s.elm.send(command, timeoutMs)
        } catch (_: ElmTimeoutException) {
            return false
        }
        return when {
            lines.any { it.contains("ERROR", true) || it.contains("UNABLE", true) } -> false
            lines.any { it.trim() == "?" } -> null
            else -> true // "BUS INIT: OK" (or a plain "OK")
        }
    }

    /** Wakes the unit up and says how it answered. */
    private suspend fun open(t: KLineTarget): Woke {
        s.adapterDirty = true
        s.kLineDirty = true
        val a = "%02X".format(t.address)
        cmd("ATSH81${a}F1")
        cmd("ATSW96")
        cmd("ATWM81${a}F13E")
        cmd("ATSTFF")
        cmd("ATAT0")
        if (t.init == KInit.SLOW) return slowInit(a).also { if (it != Woke.NO) cmd("ATAT1") }
        cmd("ATSP5")
        delay(400) // bus idle time before a new init
        when (init("ATFI", 6_000)) {
            true -> Unit
            null -> {
                // No ATFI on this adapter: the first request makes it initialise the bus by itself.
                val r = requestLines("3E", 8_000)
                if (ElmErrors.find(r) != null && ElmErrors.find(r) != "NO DATA") return Woke.NO
            }
            false -> {
                // Older units only answer the 5-baud wake-up.
                val w = slowInit(a)
                if (w != Woke.NO) cmd("ATAT1")
                return w
            }
        }
        cmd("ATAT1")
        return Woke.KWP
    }

    /** 5-baud wake-up at [a]. Key words are not checked, but read back to recognise KW1281 units. */
    private suspend fun slowInit(a: String): Woke {
        cmd("ATSP4")
        cmd("ATIIA$a")
        cmd("ATKW0")
        delay(600)
        if (init("ATSI", 12_000) != true) return Woke.NO
        val kw = keyWords(cmd("ATKW"))
        return if (kw == listOf(0x01, 0x8A)) Woke.KW1281 else Woke.KWP
    }

    private suspend fun requestLines(hex: String, timeoutMs: Long = 5_000): List<String> = cmd(hex, timeoutMs)

    /** One request to the open unit: its positive or negative answer, or null. */
    private suspend fun request(hex: String, sid: Int): EcuMessage? {
        val lines = requestLines(hex)
        val msgs = ResponseParser.parse(lines, ObdProtocol.KWP_FAST).filter {
            (it.data.isNotEmpty() && it.data[0] == sid + 0x40) || (it.data.size >= 3 && it.data[0] == 0x7F && it.data[1] == sid)
        }
        // 7F xx 78 = "busy, the answer follows": prefer the real answer.
        return msgs.firstOrNull { nrc(it) != 0x78 } ?: msgs.firstOrNull()
    }

    private fun nrc(m: EcuMessage?): Int? = m?.takeIf { it.data.size >= 3 && it.data[0] == 0x7F }?.data?.get(2)

    private suspend fun startSession() {
        for (req in listOf("10C0", "1081", "1089")) {
            val r = request(req, 0x10)
            if (r != null && nrc(r) == null) return
        }
    }

    private fun module(t: KLineTarget, ident: String?, dtcs: List<Dtc>, error: String?, vin: String?): FoundModule {
        targetOf[t.address] = t
        return FoundModule(
            tx = t.address, rx = t.address, is29 = false,
            responseHeader = "K%02X".format(t.address),
            catalogName = t.name, identification = ident, type = DiagType.KLINE,
            dtcs = dtcs, readError = error, vin = vin, kLine = t,
        )
    }

    private suspend fun probe(t: KLineTarget): FoundModule? {
        when (open(t)) {
            Woke.NO -> return null
            Woke.KW1281 -> return module(t, null, emptyList(), KW1281_TEXT, null)
            Woke.KWP -> Unit
        }
        startSession()

        val ident = request("2180", 0x21)?.takeIf { nrc(it) == null }?.let { asciiRuns(it.data.drop(2)) }
            ?: request("1A9B", 0x1A)?.takeIf { nrc(it) == null }?.let { asciiRuns(it.data.drop(2)) }
        val vin = request("2181", 0x21)?.takeIf { nrc(it) == null }?.let { VinParser.vinFromBytes(it.data.drop(2)) }
            ?: request("1A90", 0x1A)?.let { VinParser.parseKwp1A90(it.data) }

        var dtcs: List<Dtc> = emptyList()
        var error: String? = null
        val renault = if (t.codes == KCodes.VAG) null else request("17FF00", 0x17)
        if (renault != null && nrc(renault) == null) {
            dtcs = parseRecords(renault.data, raw = true)
        } else {
            val std = request("1802FF00", 0x18)?.takeIf { nrc(it) == null } ?: request("1800FF00", 0x18)?.takeIf { nrc(it) == null }
            if (std != null) {
                dtcs = if (t.codes == KCodes.VAG) parseVag(std.data) else parseRecords(std.data, raw = false)
            } else {
                error = if (ident == null && vin == null && renault == null) {
                    "Модулът се събуди, но не отговори на заявките (KWP2000). Вероятно използва стар протокол на производителя."
                } else {
                    "Модулът отговаря, но не даде списък с грешки по познатите начини"
                }
            }
        }
        // A unit that woke up is there, even if it gave nothing useful.
        return module(t, ident, dtcs, error, vin)
    }

    companion object {
        const val KW1281_TEXT =
            "Модулът е открит, но говори по стария протокол на VW (KW1281). Адаптерът в режим ELM327 не може да чете " +
                "по него — това става само със собствения режим на MUCAR (приложението DollarFix+) или с VAG кабел."

        /** "1:01 2:8A" (the answer to AT KW) -> [0x01, 0x8A]. */
        fun keyWords(lines: List<String>): List<Int>? {
            val text = lines.joinToString(" ")
            val m = Regex("""1:\s*([0-9A-Fa-f]{2})\s+2:\s*([0-9A-Fa-f]{2})""").find(text) ?: return null
            return listOf(m.groupValues[1].toInt(16), m.groupValues[2].toInt(16))
        }

        /** VW group answer to 18 02 FF 00: 58, count, then (high, low, status) per code. */
        fun parseVag(data: IntArray): List<Dtc> {
            if (data.size < 2) return emptyList()
            val count = data[1]
            val body = data.drop(2)
            val list = mutableListOf<Dtc>()
            var i = 0
            while (i + 1 < body.size && list.size < count) {
                val value = body[i] * 256 + body[i + 1]
                val st = body.getOrNull(i + 2)
                i += 3
                if (value == 0 || value == 0xFFFF) continue
                list += Dtc.fromVag(value, st)
            }
            return list
        }

        /**
         * Answer layout: service, count, then one record per code. The record length differs between
         * units (2 code bytes + 1 or more status bytes), so it is worked out from the length.
         */
        fun parseRecords(data: IntArray, raw: Boolean): List<Dtc> {
            if (data.size < 2) return emptyList()
            val count = data[1]
            val body = data.drop(2)
            if (count == 0 || body.isEmpty()) return emptyList()
            val size = if (body.size % count == 0 && body.size / count in 2..6) body.size / count else 3
            val list = mutableListOf<Dtc>()
            var i = 0
            while (i + 1 < body.size && list.size < count) {
                val hi = body[i]
                val lo = body[i + 1]
                val st = body.getOrNull(i + 2)
                i += size
                if (hi == 0 && lo == 0) continue
                list += if (raw) {
                    Dtc("%02X%02X".format(hi, lo), DtcKind.STORED, status = st, raw = true)
                } else {
                    Dtc(Dtc.decode(hi, lo), DtcKind.STORED, status = st)
                }
            }
            return list
        }

        /** Readable pieces of an identification answer (supplier, part and software numbers). */
        fun asciiRuns(bytes: List<Int>): String? {
            val text = bytes.joinToString("") { if (it in 0x20..0x7E) it.toChar().toString() else "\u0000" }
            return text.split('\u0000').map { it.trim() }.filter { it.length >= 3 }.joinToString(" ").ifBlank { null }
        }
    }
}
