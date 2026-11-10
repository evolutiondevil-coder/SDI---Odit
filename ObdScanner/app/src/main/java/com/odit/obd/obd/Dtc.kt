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
) {
    val displayCode: String get() = if (ftb != null && ftb != 0) "%s-%02X".format(code, ftb) else code

    val description: String get() = DtcDatabase.describe(code)

    /** Set when the description comes from a manufacturer pack (shown under the text). */
    val descriptionSource: String? get() = DtcDatabase.lookup(code).source

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
