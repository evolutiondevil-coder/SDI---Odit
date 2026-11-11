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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** How a unit is woken up: fast init (ISO 14230) first, or only the 5-baud slow init (VW group). */
enum class KInit { FAST_THEN_SLOW, SLOW }

/** How the unit numbers its trouble codes. */
enum class KCodes {
    /** Manufacturer numbering shown as raw bytes (Renault). */
    RAW,

    /** VW group 5-digit numbers (16684 = P0300). */
    VAG,
}

/** A control unit to try on the K-line: its address and, when known, what it is. */
data class KLineTarget(
    val address: Int,
    val name: String?,
    val init: KInit = KInit.FAST_THEN_SLOW,
    val codes: KCodes = KCodes.RAW,
    /**
     * Also try Renault's "ISO8": the 5-baud wake-up goes to the standard address 0x33 on ISO 9141,
     * then the unit is asked with the physical header 81 <address> F1 (as the open project ddt4all does).
     */
    val iso8: Boolean = false,
    /** How the unit was reached last time ("FAST", "SLOW", "ISO8"): tried first when clearing. */
    val via: String? = null,
)

/**
 * EXPERIMENTAL. Talks KWP2000 (ISO 14230) directly to one control unit at a time on the K-line,
 * with physical addressing. This is how cars from before OBD2 (and the non-engine modules of early
 * OBD2 cars) are reached; the standard OBD requests never get an answer from them.
 *
 * Per unit: header 81 <addr> F1, keep-alive 81 <addr> F1 3E every 3 s, then the FIRST REQUEST
 * ITSELF makes the adapter wake the bus up (fast init, 5-baud init at the unit's address, or
 * "ISO8"). Nothing is sent between the wake-up and the first request: the real log of a Golf IV
 * shows the MUCAR adapter dropping the link after any AT command that follows AT SI, after which
 * every request started a new wake-up that the unit (still in its session) ignored.
 *
 * A unit stays in its session for 5 s after the last message (P3max) and ignores wake-ups meanwhile,
 * so when the link is lost, or the same unit is woken twice in a row, the bus is left quiet first.
 *
 * Key bytes are read back (AT KW): 01 8A means VW's older KW1281 protocol, which needs a
 * byte-by-byte handshake an ELM327 cannot do; xx 8F is KWP2000; 08 08 / 94 94 is ISO 9141.
 */
class KLineDirect(private val s: ObdSession) {

    private enum class Mode(val protocol: Char) { FAST('5'), SLOW('4'), ISO8('3') }

    private enum class Woke { NO, OPEN, KW1281 }

    /** What [open] found: [first] is the answer to the first request (session start), if any. */
    private class Opened(val woke: Woke, val first: EcuMessage? = null, val keyWords: List<Int>? = null, val mode: Mode? = null)

    private class Answer(val busInit: Boolean?, val messages: List<EcuMessage>, val error: String?)

    /** Targets of the modules found, to talk to them the same way when clearing. */
    private val targetOf = HashMap<Int, KLineTarget>()

    /** The mode the adapter's K-line link is up in (as far as we know), or null. */
    private var link: Mode? = null

    /** Wake-up address of the unit on that link. */
    private var linkAddress = ANY

    /** 5-baud/fast-init address of the unit that may still be in a session, and since when. -1 = any unit. */
    private var busyAddress: Int? = null
    private var busyMs = 0L

    /** The unit [open] opened last: its mode and wake-up address (for the requests that follow). */
    private var openMode: Mode? = null
    private var openAddress = ANY

    /** No unit answers a wake-up at 0x33 on this car: do not try "ISO8" again in this run. */
    private var iso8Dead = false

    /** Key bytes the adapter reported last (AT KW keeps the old ones when a wake-up fails). */
    private var lastKeyWords: List<Int>? = null

    suspend fun scan(
        targets: List<KLineTarget>,
        onProgress: (done: Int, total: Int, label: String) -> Unit,
        onFound: (FoundModule) -> Unit,
    ): List<FoundModule> {
        val found = mutableListOf<FoundModule>()
        try {
            begin()
            for ((i, t) in targets.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(i, targets.size, (t.name ?: "K-line 0x%02X".format(t.address)))
                val m = probe(t) ?: continue
                found += m
                onFound(m)
            }
            onProgress(1, 1, "")
        } finally {
            withContext(NonCancellable) { finish() }
        }
        return found
    }

    /** Clears each unit and reads it again. */
    suspend fun clear(modules: List<FoundModule>): List<Pair<ClearResult, FoundModule?>> {
        val out = mutableListOf<Pair<ClearResult, FoundModule?>>()
        try {
            begin()
            for (m in modules) {
                currentCoroutineContext().ensureActive()
                val target = m.kLine ?: targetOf[m.tx] ?: KLineTarget(m.tx, m.catalogName)
                val opened = open(target)
                val result = when (opened.woke) {
                    Woke.KW1281 -> ClearResult(false, KW1281_TEXT)
                    Woke.NO -> ClearResult(false, "Няма отговор от модула")
                    Woke.OPEN -> {
                        val r = request("14FF00", 0x14)
                        when {
                            r == null -> ClearResult(false, "Няма отговор от модула")
                            nrc(r) == null -> ClearResult(true, "Изтрито")
                            nrc(r) == 0x22 -> ClearResult(false, "Отказано: условията не са изпълнени (изгасете двигателя)")
                            else -> ClearResult(false, "Отказано: ${ModuleScanner.nrcText(nrc(r)!!)}")
                        }
                    }
                }
                s.pause(500)
                out += result to probe(target)
            }
        } finally {
            withContext(NonCancellable) { finish() }
        }
        return out
    }

    // ---------------------------------------------------------------- internals

    private suspend fun begin() {
        s.adapterDirty = true
        s.kLineDirty = true
        link = null
        iso8Dead = false
        // The engine may still be in the OBD session the adapter kept alive until now.
        if (s.vehicleConnected && ObdSession.isKLine(s.protocol)) markBusy(ANY)
        s.kLineBusyMs?.let { if (s.elapsed(it) < ObdSession.P3_WAIT_MS) { busyAddress = ANY; busyMs = it } }
        lastKeyWords = keyWords(cmd("ATKW"))
    }

    private suspend fun finish() {
        if (busyAddress != null) s.kLineBusyMs = busyMs
        runCatching { s.restoreObdDefaults() }
    }

    private suspend fun cmd(c: String, timeoutMs: Long = 2_000): List<String> =
        try {
            s.elm.send(c, timeoutMs)
        } catch (_: ElmTimeoutException) {
            emptyList()
        }

    private fun markBusy(address: Int) {
        busyAddress = address
        busyMs = s.now()
    }

    /** Leaves the bus quiet until a unit that may still be in its session (at [address]) has closed it. */
    private suspend fun quiet(address: Int) {
        val busy = busyAddress ?: return
        if (busy != ANY && busy != address) return
        val left = ObdSession.P3_WAIT_MS - s.elapsed(busyMs)
        if (left > 0) {
            s.note("K-line: изчаквам ${"%.1f".format(left / 1000.0)} с модулът да затвори предишната връзка")
            s.pause(left)
        }
        busyAddress = null
    }

    /** A unit at [address] may still be in a session from a moment ago. */
    private fun isBusy(address: Int): Boolean {
        val busy = busyAddress ?: return false
        return (busy == ANY || busy == address) && s.elapsed(busyMs) < ObdSession.P3_WAIT_MS
    }

    /** Address the adapter's wake-up goes to in this mode. */
    private fun initAddress(t: KLineTarget, mode: Mode) = if (mode == Mode.ISO8) 0x33 else t.address

    /** Header, keep-alive, speed and timing for [t] in [mode]; the wake-up itself comes with the first request. */
    private suspend fun configure(t: KLineTarget, mode: Mode) {
        val a = "%02X".format(t.address)
        // The adapter keeps a linked unit's session alive (keep-alive every 3 s) until the link drops,
        // which any of the commands below may do: the unit's 5 s count starts now, not at its last answer.
        if (link != null) markBusy(linkAddress)
        // In ISO8 the link to the unit at 0x33 can be kept while only the header changes.
        if (!(mode == Mode.ISO8 && link == Mode.ISO8)) {
            cmd("ATSP${mode.protocol}")
            link = null
        }
        cmd("ATSH81${a}F1")
        cmd("ATSW96")
        cmd("ATWM81${a}F13E")
        cmd("ATIB10")
        cmd("ATSTFF")
        cmd("ATAT0")
        cmd("ATAL") // identification and code lists are longer than the 7 bytes of OBD messages
        when (mode) {
            Mode.SLOW -> { cmd("ATIIA$a"); cmd("ATKW0") }
            Mode.ISO8 -> { cmd("ATIIA33"); cmd("ATKW0") }
            Mode.FAST -> Unit
        }
    }

    /** Sends one request; the adapter wakes the bus up first if the link is not up. */
    private suspend fun send(hex: String, mode: Mode?, address: Int, timeoutMs: Long = 9_000): Answer {
        val r = try {
            s.elm.sendDetailed(hex, timeoutMs)
        } catch (_: ElmTimeoutException) {
            return Answer(null, emptyList(), "TIMEOUT")
        }
        val msgs = ResponseParser.parse(r.lines, ObdProtocol.KWP_FAST)
        when {
            r.busInit == false -> link = null
            r.busInit == true -> { link = mode; linkAddress = address }
        }
        if (msgs.isNotEmpty() || r.busInit == true) markBusy(address)
        return Answer(r.busInit, msgs, ElmErrors.find(r.lines))
    }

    private fun pick(msgs: List<EcuMessage>, sid: Int): EcuMessage? {
        val relevant = msgs.filter {
            (it.data.isNotEmpty() && it.data[0] == sid + 0x40) || (it.data.size >= 3 && it.data[0] == 0x7F && it.data[1] == sid)
        }
        // 7F xx 78 = "busy, the answer follows": prefer the real answer.
        return relevant.firstOrNull { nrc(it) != 0x78 } ?: relevant.firstOrNull()
    }

    private fun nrc(m: EcuMessage?): Int? = m?.takeIf { it.data.size >= 3 && it.data[0] == 0x7F }?.data?.get(2)

    /** Reads AT KW; [fresh] is false when the adapter only repeats the key bytes it had before. */
    private suspend fun readKeyWords(): Pair<List<Int>?, Boolean> {
        val kw = keyWords(cmd("ATKW"))
        val fresh = kw != null && kw != lastKeyWords
        lastKeyWords = kw
        return kw to fresh
    }

    private fun firstRequests(t: KLineTarget): List<String> =
        if (t.codes == KCodes.VAG) listOf("1089", "1081", "10C0") else listOf("10C0", "1081", "1089")

    /** Wakes the unit up with its first request and says how it went. */
    private suspend fun open(t: KLineTarget): Opened {
        val modes = buildList {
            if (t.init == KInit.FAST_THEN_SLOW) add(Mode.FAST)
            add(Mode.SLOW)
            if (t.iso8 && !iso8Dead) add(Mode.ISO8)
        }.sortedBy { if (it.name == t.via) 0 else 1 }
        val firstReq = firstRequests(t).first()
        for (mode in modes) {
            // The 5-baud wake-up of VW units sometimes fails once and works the next time.
            val attempts = if (mode == Mode.SLOW && t.codes == KCodes.VAG) 2 else 1
            for (attempt in 1..attempts) {
                val initAddr = initAddress(t, mode)
                configure(t, mode)
                if (link == null) {
                    quiet(initAddr)
                    s.pause(if (attempt == 1) 400 else 1_500) // the bus must be idle before a new wake-up
                }
                openMode = mode
                openAddress = initAddr
                var started = s.now()
                var a = send(firstReq, mode, initAddr)
                if (a.busInit == false && isBusy(initAddr)) {
                    // The adapter dropped a link we still needed, and the unit is in its old session.
                    quiet(initAddr)
                    started = s.now()
                    a = send(firstReq, mode, initAddr)
                }
                val ms = s.elapsed(started)
                pick(a.messages, 0x10)?.let { return Opened(Woke.OPEN, it, mode = mode) }

                val awake = a.busInit == true || (a.busInit == null && link == mode && a.error == "NO DATA")
                if (awake) {
                    // Woke up but did not answer the session start: try the other ones before giving up.
                    for (other in firstRequests(t).drop(1)) {
                        request(other, 0x10, mode, initAddr)?.let { return Opened(Woke.OPEN, it, mode = mode) }
                    }
                    val (kw, _) = readKeyWords()
                    if (kw == KW1281_BYTES) return Opened(Woke.KW1281, keyWords = kw)
                    // In ISO8 the wake-up went to 0x33, not to this unit: silence means it is not there.
                    if (mode == Mode.ISO8) {
                        s.note("K-line ISO8: модул на 0x33 се събуди, но 0x%02X не отговаря".format(t.address))
                        return Opened(Woke.NO)
                    }
                    return Opened(Woke.OPEN, null, kw, mode)
                }

                // The wake-up failed. Key bytes 01 8A read back now mean a KW1281 unit answered it.
                val (kw, fresh) = readKeyWords()
                s.note("K-line 0x%02X (%s): няма събуждане след %d ms, ключови байтове %s".format(
                    initAddr, mode.name, ms, kw?.joinToString(" ") { "%02X".format(it) } ?: "—",
                ))
                if (kw == KW1281_BYTES && (fresh || ms < SYNC_FAIL_MS)) return Opened(Woke.KW1281, keyWords = kw)
                if (mode == Mode.ISO8) iso8Dead = true
            }
        }
        return Opened(Woke.NO)
    }

    /**
     * One request to the open unit: its positive or negative answer, or null.
     * If the adapter lost the link and could not wake the unit again, the unit is still in its old
     * session: wait until it closes it and ask once more (the adapter wakes it up again by itself).
     */
    private suspend fun request(hex: String, sid: Int, mode: Mode? = openMode, address: Int = openAddress): EcuMessage? {
        var a = send(hex, mode, address)
        if (a.busInit == false) {
            quiet(address)
            a = send(hex, mode, address)
        }
        var m = pick(a.messages, sid)
        if (m != null && nrc(m) == 0x78) {
            s.pause(2_000)
            m = pick(send(hex, mode, address).messages, sid) ?: m
        }
        return m
    }

    private fun module(target: KLineTarget, ident: String?, dtcs: List<Dtc>, error: String?, vin: String?, mode: Mode?): FoundModule {
        val t = target.copy(via = mode?.name ?: target.via)
        targetOf[t.address] = t
        return FoundModule(
            tx = t.address, rx = t.address, is29 = false,
            responseHeader = "K%02X".format(t.address),
            catalogName = t.name, identification = ident, type = DiagType.KLINE,
            dtcs = dtcs, readError = error, vin = vin, kLine = t,
        )
    }

    private suspend fun probe(t: KLineTarget): FoundModule? {
        val opened = open(t)
        when (opened.woke) {
            Woke.NO -> return null
            Woke.KW1281 -> return module(t, null, emptyList(), KW1281_TEXT, null, null)
            Woke.OPEN -> Unit
        }
        val vag = t.codes == KCodes.VAG
        val identReqs = if (vag) listOf("1A9B", "2180") else listOf("2180", "1A9B")
        var ident: String? = null
        for (r in identReqs) {
            val sid = r.substring(0, 2).toInt(16)
            ident = request(r, sid)?.takeIf { nrc(it) == null }?.let { asciiRuns(it.data.drop(2)) }
            if (ident != null) break
        }
        val vin = (if (vag) null else request("2181", 0x21)?.takeIf { nrc(it) == null }?.let { VinParser.vinFromBytes(it.data.drop(2)) })
            ?: request("1A90", 0x1A)?.let { VinParser.parseKwp1A90(it.data) }

        var dtcs: List<Dtc> = emptyList()
        var error: String? = null
        val renault = if (vag) null else request("17FF00", 0x17)
        if (renault != null && nrc(renault) == null) {
            dtcs = parseRecords(renault.data, raw = true)
        } else {
            val std = request("1802FF00", 0x18)?.takeIf { nrc(it) == null } ?: request("1800FF00", 0x18)?.takeIf { nrc(it) == null }
            if (std != null) {
                dtcs = if (vag) parseVag(std.data) else parseRecords(std.data, raw = false)
            } else {
                error = if (opened.first == null && ident == null && vin == null && renault == null) {
                    silentText(opened.keyWords)
                } else {
                    "Модулът отговаря, но не даде списък с грешки по познатите начини"
                }
            }
        }
        // A unit that woke up is there, even if it gave nothing useful.
        return module(t, ident, dtcs, error, vin, opened.mode)
    }

    companion object {
        private const val ANY = -1

        /**
         * A failed 5-baud wake-up with no answer at all takes ~2.55 s on the MUCAR adapter (real logs);
         * one that got the unit's sync byte and key bytes fails sooner.
         */
        private const val SYNC_FAIL_MS = 2_450L

        private val KW1281_BYTES = listOf(0x01, 0x8A)

        const val KW1281_TEXT =
            "Модулът е открит, но говори по стария протокол на VW (KW1281). Адаптерът в режим ELM327 не може да чете " +
                "по него — това става само със собствения режим на MUCAR (приложението DollarFix+) или с VAG кабел."

        /** Why a unit that woke up gave nothing, by its key bytes. */
        fun silentText(kw: List<Int>?): String {
            val bytes = kw?.joinToString(" ") { "%02X".format(it) }
            return when {
                kw != null && kw.size == 2 && kw[1] == 0x8F ->
                    "Модулът се събуди по KWP2000 (ключови байтове $bytes), но не отговори на заявките. " +
                        "Опитайте търсенето още веднъж; ако пак не стане, пратете лога."
                kw == listOf(0x08, 0x08) || kw == listOf(0x94, 0x94) ->
                    "Модулът се събуди по ISO 9141 (ключови байтове $bytes), но не отговори на заявките на производителя."
                else -> "Модулът се събуди, но не отговори на заявките."
            }
        }

        /** "1:01 2:8A" (the answer to AT KW) -> [0x01, 0x8A]. */
        fun keyWords(lines: List<String>): List<Int>? {
            val text = lines.joinToString(" ")
            val m = Regex("""1:\s*([0-9A-Fa-f]{1,2})\s+2:\s*([0-9A-Fa-f]{1,2})""").find(text) ?: return null
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
