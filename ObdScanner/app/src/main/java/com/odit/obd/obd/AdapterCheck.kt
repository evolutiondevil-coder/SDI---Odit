package com.odit.obd.obd

import com.odit.obd.elm.ElmTimeoutException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** One command of the adapter check and how the adapter took it. */
data class CheckItem(val title: String, val command: String, val answer: String, val ms: Long, val known: Boolean?)

/** What the adapter can do in its ELM327 mode, in plain words. */
enum class KLineSupport { WORKS, LIKELY, PROBABLY_NOT, NO, UNKNOWN }

data class AdapterReport(
    val items: List<CheckItem>,
    val kLine: KLineSupport,
    val verdict: List<String>,
) {
    /** One line for the exported log header. */
    val summary: String
        get() = "K-line=$kLine; " + items.joinToString("; ") { "${it.command}=${it.answer.take(24)} (${it.ms} ms)" }
}

/**
 * Asks the adapter which commands it knows and times the K-line wake-up. The point is to tell,
 * without guessing, whether an old car does not connect because of the car or because the adapter's
 * ELM327 mode has no real K-line. Nothing is written to the car: the wake-up is sent to the
 * standard OBD address 0x33 and the adapter's settings are restored afterwards.
 */
class AdapterCheck(private val s: ObdSession, private val clock: () -> Long = System::currentTimeMillis) {

    private val items = mutableListOf<CheckItem>()

    private suspend fun probe(title: String, command: String, timeoutMs: Long = 2_000): CheckItem {
        val start = clock()
        val lines = try {
            s.elm.send(command, timeoutMs)
        } catch (_: ElmTimeoutException) {
            listOf("(няма отговор)")
        }
        val answer = lines.joinToString(" | ").ifBlank { "(празно)" }
        val known = when {
            lines.any { it.trim() == "?" } -> false
            lines.isEmpty() || answer == "(няма отговор)" -> null
            else -> true
        }
        return CheckItem(title, command, answer, clock() - start, known).also { items += it }
    }

    suspend fun run(progress: (String) -> Unit = {}): AdapterReport {
        items.clear()
        s.adapterDirty = true
        s.kLineDirty = true
        try {
            progress("Проверка на адаптера: версия…")
            probe("Версия", "ATI")
            probe("Описание", "AT@1")
            probe("Чип STN (по-добър от ELM327)", "STI")
            probe("Напрежение", "ATRV")

            progress("Проверка на адаптера: K-line, бързо събуждане…")
            probe("K-line протокол (бърз)", "ATSP5")
            probe("Адрес за K-line", "ATSHC133F1")
            delay(300)
            val fi = probe("K-line бързо събуждане", "ATFI", 8_000)

            progress("Проверка на адаптера: K-line, бавно събуждане (5 бода)…")
            probe("K-line протокол (бавен)", "ATSP4")
            probe("Адрес за бавно събуждане", "ATIIA33")
            probe("Без проверка на ключовите байтове", "ATKW0")
            delay(300)
            val si = probe("K-line бавно събуждане", "ATSI", 12_000)
            probe("Ключови байтове", "ATKW")
            probe("Съобщение за поддържане", "ATWM81F13E")

            progress("Проверка на адаптера: CAN…")
            probe("CAN протокол", "ATSP6")
            val raw = probe("Сурови CAN кадри", "ATCAF0")
            probe("Нормални CAN кадри", "ATCAF1")
            val filter = probe("CAN филтър", "ATCF700")
            val fc = probe("Управление на потока (дълги отговори)", "ATFCSD300000")

            return AdapterReport(items.toList(), kLine(fi, si), verdict(fi, si, raw, filter, fc))
        } finally {
            withContext(NonCancellable) { runCatching { s.restoreObdDefaults() } }
        }
    }

    private fun woke(i: CheckItem) = i.known == true && !i.answer.contains("ERROR", true) && !i.answer.contains("UNABLE", true) &&
        i.answer.contains("OK", true)

    private fun kLine(fi: CheckItem, si: CheckItem): KLineSupport = when {
        woke(fi) || woke(si) -> KLineSupport.WORKS
        fi.known == false && si.known == false -> KLineSupport.NO
        // Sending one address byte at 5 baud alone takes 2 s: a real attempt cannot be faster.
        si.known == true && si.ms >= 1_500 -> KLineSupport.LIKELY
        si.known == true && si.ms < 600 && fi.ms < 600 -> KLineSupport.PROBABLY_NOT
        else -> KLineSupport.UNKNOWN
    }

    private fun verdict(fi: CheckItem, si: CheckItem, raw: CheckItem, filter: CheckItem, fc: CheckItem): List<String> {
        val out = mutableListOf<String>()
        out += when (kLine(fi, si)) {
            KLineSupport.WORKS ->
                "K-line работи: модул на колата отговори на събуждането."
            KLineSupport.LIKELY ->
                "K-line изглежда реално поддържан (бавното събуждане отне ${"%.1f".format(si.ms / 1000.0)} с, колкото трябва). " +
                    "Ако стара кола не се свързва, причината по-скоро е в протокола на колата, а не в адаптера."
            KLineSupport.PROBABLY_NOT ->
                "Адаптерът приема командите за K-line, но отговаря мигновено с грешка (за ${si.ms} мс вместо поне 2 с). " +
                    "Изглежда в режим ELM327 той няма реален K-line — коли отпреди около 2005–2008 г. няма да се свързват " +
                    "с това приложение, а само със собственото приложение на MUCAR."
            KLineSupport.NO ->
                "Адаптерът не познава командите за K-line в режим ELM327 — коли отпреди около 2005–2008 г. няма да се " +
                    "свързват с това приложение."
            KLineSupport.UNKNOWN ->
                "За K-line не може да се каже със сигурност — пратете лога."
        }
        if (filter.known == true && fc.known == true) {
            out += "CAN: сканирането на модули се поддържа напълно."
        } else {
            out += "CAN: адаптерът не поддържа някои команди за модули — сканирането ще намира по-малко модули."
        }
        if (raw.known == false) out += "Сурови CAN кадри не се поддържат (нужни за някои марки, напр. по-старите VW с TP2.0)."
        return out
    }
}
