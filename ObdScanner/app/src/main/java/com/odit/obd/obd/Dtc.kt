package com.odit.obd.obd

/** Where a trouble code came from / in which state it is. */
enum class DtcKind(val title: String) {
    STORED("Запаметена"),
    PENDING("Изчакваща"),
    PERMANENT("Постоянна"),
    ACTIVE("Активна"),
    HISTORY("В историята"),
}

/**
 * A diagnostic trouble code, e.g. P0301.
 * [ftb] is the UDS "failure type byte" (3-byte DTCs from module scans), [status] the UDS/KWP status byte.
 */
data class Dtc(
    val code: String,
    val kind: DtcKind = DtcKind.STORED,
    val ftb: Int? = null,
    val status: Int? = null,
    /**
     * The manufacturer's own numbering, read from an old K-line unit: [code] is the bytes in hex and
     * must not be read as a standard P/C/B/U code.
     */
    val raw: Boolean = false,
    /** VW group 5-digit fault number (old K-line modules), e.g. 16684. */
    val vagNumber: Int? = null,
) {
    val displayCode: String
        get() = when {
            vagNumber != null && !raw -> "VAG %05d / %s".format(vagNumber, code)
            vagNumber != null -> "VAG %05d".format(vagNumber)
            raw -> "DTC $code"
            ftb != null && ftb != 0 -> "%s-%02X".format(code, ftb)
            else -> code
        }

    /** What to type in a web search for this code. */
    val searchTerm: String
        get() = when {
            vagNumber != null && !raw -> "%05d %s".format(vagNumber, code)
            vagNumber != null -> "VAG %05d".format(vagNumber)
            raw -> "DTC $code"
            else -> code
        }

    val description: String
        get() = when {
            vagNumber != null && raw ->
                "Код на VW групата от стар модул. Значението зависи от модула — потърсете го с бутона по-долу."
            raw -> "Код на производителя от стар модул (K-line). Значението зависи от марката и модула — потърсете го с бутона по-долу."
            else -> DtcDatabase.describe(code)
        }

    /** Set when the description comes from a manufacturer pack (shown under the text). */
    val descriptionSource: String? get() = if (raw) null else DtcDatabase.lookup(code).source

    val ftbDescription: String? get() = ftb?.takeIf { it != 0 }?.let { DtcDatabase.failureType(it) }

    companion object {
        private val LETTERS = charArrayOf('P', 'C', 'B', 'U')

        /** Decodes the standard two-byte SAE J2012 form. */
        fun decode(a: Int, b: Int): String {
            val letter = LETTERS[(a shr 6) and 0x03]
            val d1 = (a shr 4) and 0x03
            val d2 = a and 0x0F
            return "%c%d%X%02X".format(letter, d1, d2, b)
        }

        /**
         * VW group 5-digit fault numbers of engine units map onto SAE codes:
         * 16384 + n = P0nnn (16684 = P0300) and 17408 + n = P1nnn (17978 = P1570).
         * Other numbers have no SAE equivalent and are shown as they are.
         */
        fun fromVag(value: Int, status: Int? = null): Dtc {
            val sae = when (value) {
                in 16384..17383 -> "P0%03d".format(value - 16384)
                in 17408..18407 -> "P1%03d".format(value - 17408)
                else -> null
            }
            return Dtc(sae ?: "%05d".format(value), DtcKind.STORED, status = status, raw = sae == null, vagNumber = value)
        }

        /** Inverse of [decode]; used by the simulator and tests. */
        fun encode(code: String): Pair<Int, Int> {
            val letter = LETTERS.indexOf(code[0].uppercaseChar())
            require(letter >= 0 && code.length == 5) { "bad DTC $code" }
            val d1 = code[1].digitToInt(16)
            val d2 = code[2].digitToInt(16)
            val a = (letter shl 6) or (d1 shl 4) or d2
            val b = code.substring(3).toInt(16)
            return a to b
        }
    }
}

/** A trouble code together with the control unit that reported it. */
data class EcuDtc(val ecu: String, val dtc: Dtc)
