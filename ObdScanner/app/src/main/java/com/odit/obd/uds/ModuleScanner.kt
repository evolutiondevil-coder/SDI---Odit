package com.odit.obd.uds

import com.odit.obd.elm.EcuMessage
import com.odit.obd.elm.ElmTimeoutException
import com.odit.obd.obd.Brand
import com.odit.obd.obd.ClearResult
import com.odit.obd.obd.Dtc
import com.odit.obd.obd.DtcKind
import com.odit.obd.obd.ObdSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

enum class DiagType { UDS, KWP }

data class FoundModule(
    /** 11-bit request id, or the target address byte for 29-bit CAN. */
    val tx: Int,
    val rx: Int,
    val is29: Boolean,
    val responseHeader: String,
    val catalogName: String?,
    val identification: String?,
    val type: DiagType?,
    val dtcs: List<Dtc>,
    val readError: String?,
) {
    val addressLabel: String get() = if (is29) "18DA%02XF1".format(tx) else "0x%03X".format(tx)

    val title: String get() = catalogName ?: identification ?: "Модул $addressLabel"
}

/**
 * Finds control units on the CAN bus by asking every diagnostic address for its trouble codes
 * (UDS service 0x19, or KWP2000 service 0x18 for older units). Works without knowing the car model.
 */
class ModuleScanner(private val s: ObdSession) {

    private var filterAll = false
    private var busErrors = 0

    suspend fun scan(
        full: Boolean,
        brand: Brand,
        onProgress: (done: Int, total: Int, label: String) -> Unit,
        onFound: (FoundModule) -> Unit,
    ): List<FoundModule> {
        if (!s.protocol.isCan) {
            throw IOException(
                "Сканирането на всички модули работи само с CAN автомобили (обикновено от 2008 г. насам). " +
                    "Този автомобил използва ${s.protocol.title} — достъпни са грешките на двигателя.",
            )
        }
        val found = mutableListOf<FoundModule>()
        try {
            prepare()
            if (s.protocol.is29bit) {
                val list = ModuleCatalog.addresses29(full)
                for ((i, addr) in list.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    onProgress(i, list.size, "18DA%02XF1".format(addr))
                    val m = probe(addr, 0, null, brand) ?: continue
                    if (found.none { it.responseHeader == m.responseHeader }) {
                        found += m
                        onFound(m)
                    }
                }
            } else {
                val list = if (full) ModuleCatalog.fullList(brand) else ModuleCatalog.quickList(brand)
                for ((i, a) in list.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    onProgress(i, list.size, "0x%03X".format(a.tx))
                    val m = probe(a.tx, a.rx, a.name, brand) ?: continue
                    if (found.none { it.responseHeader == m.responseHeader }) {
                        found += m
                        onFound(m)
                    }
                }
            }
            onProgress(1, 1, "")
        } finally {
            withContext(NonCancellable) { runCatching { s.restoreObdDefaults() } }
        }
        return found
    }

    /** Clears one module and reads it again. */
    suspend fun clear(modules: List<FoundModule>, brand: Brand): List<Pair<ClearResult, FoundModule?>> {
        val out = mutableListOf<Pair<ClearResult, FoundModule?>>()
        try {
            prepare()
            for (m in modules) {
                currentCoroutineContext().ensureActive()
                val result = clearOne(m)
                val again = probe(m.tx, m.rx, m.catalogName, brand)
                out += result to again
            }
        } finally {
            withContext(NonCancellable) { runCatching { s.restoreObdDefaults() } }
        }
        return out
    }

    // ---------------------------------------------------------------- internals

    private suspend fun cmd(c: String): List<String>? = runCatching { s.elm.send(c, 2_000) }.getOrNull()

    private fun ok(lines: List<String>?) = lines != null && lines.any { it.contains("OK", ignoreCase = true) }

    private suspend fun prepare() {
        s.adapterDirty = true
        busErrors = 0
        cmd("ATAT0")
        cmd("ATST20") // ~130 ms per address
        filterAll = if (s.protocol.is29bit) {
            cmd("ATCP18")
            ok(cmd("ATCF18DAF100")) && ok(cmd("ATCM1FFFFF00"))
        } else {
            ok(cmd("ATCF700")) && ok(cmd("ATCM700"))
        }
    }

    private suspend fun select(tx: Int, rx: Int) {
        if (s.protocol.is29bit) {
            cmd("ATSHDA%02XF1".format(tx))
            if (!filterAll) cmd("ATCRA18DAF1%02X".format(tx))
        } else {
            cmd("ATSH%03X".format(tx))
            if (!filterAll) cmd("ATCRA%03X".format(rx))
        }
    }

    /** Sends one request to the selected module; returns its answer (positive or negative) or null if silent. */
    private suspend fun exchange(tx: Int, hex: String, sid: Int): EcuMessage? =
        try {
            exchangeOrThrow(tx, hex, sid)
        } catch (e: ElmTimeoutException) {
            null // a silent address is not an error
        }

    private suspend fun exchangeOrThrow(tx: Int, hex: String, sid: Int): EcuMessage? {
        var reply = s.rawRequest(hex, 2_500)
        countBusErrors(reply.error)
        var msgs = relevant(reply.messages, sid)

        // 0x78 = "response pending": the module needs more time.
        if (msgs.isNotEmpty() && msgs.all { isNrc(it, 0x78) }) {
            cmd("ATSTFF")
            reply = s.rawRequest(hex, 5_000)
            cmd("ATST20")
            msgs = relevant(reply.messages, sid).filterNot { isNrc(it, 0x78) }
        }

        // Long answer cut short: the adapter sent flow control to the wrong id. Point it at the module.
        if (msgs.isNotEmpty() && msgs.none { it.complete }) {
            val fcId = if (s.protocol.is29bit) "18DA%02XF1".format(tx) else "%03X".format(tx)
            if (ok(cmd("ATFCSH$fcId")) && ok(cmd("ATFCSD300000")) && ok(cmd("ATFCSM1"))) {
                reply = s.rawRequest(hex, 3_000)
                cmd("ATFCSM0")
                msgs = relevant(reply.messages, sid)
            }
        }
        return msgs.firstOrNull { it.complete } ?: msgs.firstOrNull()
    }

    private fun countBusErrors(error: String?) {
        if (error == "CAN ERROR" || error == "BUS ERROR" || error == "BUS BUSY") {
            busErrors++
            if (busErrors >= 3) throw IOException("Грешка в CAN шината ($error). Проверете връзката на адаптера.")
        }
    }

    private fun relevant(msgs: List<EcuMessage>, sid: Int) = msgs.filter {
        (it.data.isNotEmpty() && it.data[0] == sid + 0x40) || (it.data.size >= 3 && it.data[0] == 0x7F && it.data[1] == sid)
    }

    private fun isNrc(m: EcuMessage, code: Int) = m.data.size >= 3 && m.data[0] == 0x7F && m.data[2] == code

    private fun nrcOf(m: EcuMessage?): Int? = m?.takeIf { it.data.size >= 3 && it.data[0] == 0x7F }?.data?.get(2)

    private suspend fun probe(tx: Int, rx: Int, catalogName: String?, brand: Brand): FoundModule? {
        select(tx, rx)
        val first = exchange(tx, "1902FF", 0x19) ?: return null
        val header = first.header
        var type: DiagType? = null
        var dtcs: List<Dtc> = emptyList()
        var error: String? = null

        var answer: EcuMessage? = first
        if (nrcOf(answer) == 0x31) answer = exchange(tx, "19020D", 0x19) ?: answer
        val nrc = nrcOf(answer)
        if (answer != null && nrc == null) {
            type = DiagType.UDS
            dtcs = parseUds(answer.data)
            if (!answer.complete) error = "Непълен отговор — част от грешките може да липсват"
        } else if (nrc == 0x11 || nrc == 0x7F || nrc == 0x12) {
            val kwp = exchange(tx, "1802FF00", 0x18)
            if (kwp != null && nrcOf(kwp) == null) {
                type = DiagType.KWP
                dtcs = parseKwp(kwp.data)
            } else {
                error = "Модулът отговаря, но не поддържа четене на грешки по стандартния начин"
            }
        } else if (nrc != null) {
            error = "Модулът отказа четене на грешки: ${nrcText(nrc)}"
        }

        val ident = when (type) {
            DiagType.UDS -> readUdsIdent(tx)
            DiagType.KWP -> exchange(tx, "1A9B", 0x1A)?.takeIf { nrcOf(it) == null }?.let { ascii(it.data.drop(2)) }
            null -> null
        }?.takeIf { it.isNotBlank() }

        val name = catalogName
            ?: (if (s.protocol.is29bit) ModuleCatalog.name29(tx) else ModuleCatalog.nameFor(brand, tx))
        return FoundModule(tx, rx, s.protocol.is29bit, header, name, ident, type, dtcs, error)
    }

    private suspend fun readUdsIdent(tx: Int): String? {
        val name = exchange(tx, "22F197", 0x22)?.takeIf { nrcOf(it) == null && it.data.size > 3 }?.let { ascii(it.data.drop(3)) }
        val part = exchange(tx, "22F187", 0x22)?.takeIf { nrcOf(it) == null && it.data.size > 3 }?.let { ascii(it.data.drop(3)) }
        return listOfNotNull(name?.ifBlank { null }, part?.ifBlank { null }).joinToString(" · ").ifBlank { null }
    }

    private suspend fun clearOne(m: FoundModule): ClearResult {
        select(m.tx, m.rx)
        if (m.type == DiagType.KWP) {
            val r = exchange(m.tx, "14FF00", 0x14)
            return clearResult(r)
        }
        var r = exchange(m.tx, "14FFFFFF", 0x14)
        val nrc = nrcOf(r)
        if (nrc == 0x22 || nrc == 0x7E || nrc == 0x7F) {
            // Some modules only accept clearing in the extended diagnostic session.
            exchange(m.tx, "1003", 0x10)
            r = exchange(m.tx, "14FFFFFF", 0x14)
            exchange(m.tx, "1001", 0x10)
        }
        return clearResult(r)
    }

    private fun clearResult(r: EcuMessage?): ClearResult {
        val nrc = nrcOf(r)
        return when {
            r == null -> ClearResult(false, "Няма отговор от модула")
            nrc == null -> ClearResult(true, "Изтрито")
            nrc == 0x22 -> ClearResult(false, "Отказано: условията не са изпълнени (изгасете двигателя или грешката още е активна)")
            nrc == 0x33 -> ClearResult(false, "Отказано: модулът изисква защитен достъп")
            else -> ClearResult(false, "Отказано: ${nrcText(nrc)}")
        }
    }

    companion object {
        fun parseUds(data: IntArray): List<Dtc> {
            val list = mutableListOf<Dtc>()
            var i = 3
            while (i + 3 < data.size) {
                val hi = data[i]
                val mid = data[i + 1]
                val low = data[i + 2]
                val st = data[i + 3]
                i += 4
                if (hi == 0 && mid == 0 && low == 0) continue
                val kind = when {
                    st and 0x01 != 0 -> DtcKind.ACTIVE
                    st and 0x08 != 0 -> DtcKind.STORED
                    st and 0x04 != 0 -> DtcKind.PENDING
                    st and 0x80 != 0 -> DtcKind.STORED
                    st and 0x20 != 0 -> DtcKind.HISTORY
                    else -> null
                } ?: continue
                list += Dtc(Dtc.decode(hi, mid), kind, ftb = low, status = st)
            }
            return list
        }

        fun parseKwp(data: IntArray): List<Dtc> {
            val list = mutableListOf<Dtc>()
            var i = 2
            while (i + 2 < data.size) {
                val hi = data[i]
                val lo = data[i + 1]
                val st = data[i + 2]
                i += 3
                if (hi == 0 && lo == 0) continue
                val kind = when ((st shr 5) and 0x03) {
                    3 -> DtcKind.ACTIVE
                    2 -> DtcKind.PENDING
                    1 -> DtcKind.HISTORY
                    else -> DtcKind.STORED
                }
                list += Dtc(Dtc.decode(hi, lo), kind, status = st)
            }
            return list
        }

        fun nrcText(nrc: Int): String = when (nrc) {
            0x10 -> "общ отказ"
            0x11 -> "услугата не се поддържа"
            0x12 -> "подфункцията не се поддържа"
            0x13 -> "грешна дължина на заявката"
            0x21 -> "модулът е зает"
            0x22 -> "условията не са изпълнени"
            0x31 -> "заявката е извън обхват"
            0x33 -> "необходим е защитен достъп"
            0x7E, 0x7F -> "не се поддържа в текущата сесия"
            else -> "код 0x%02X".format(nrc)
        }

        private fun ascii(bytes: List<Int>) =
            bytes.filter { it in 0x20..0x7E }.joinToString("") { it.toChar().toString() }.trim()
    }
}
