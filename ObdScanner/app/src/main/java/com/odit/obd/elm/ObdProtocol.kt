package com.odit.obd.elm

enum class ObdProtocol(val code: Char, val title: String, val isCan: Boolean, val is29bit: Boolean) {
    AUTO('0', "Автоматичен", false, false),
    J1850_PWM('1', "SAE J1850 PWM (41.6 kbaud)", false, false),
    J1850_VPW('2', "SAE J1850 VPW (10.4 kbaud)", false, false),
    ISO_9141('3', "ISO 9141-2 (K-линия)", false, false),
    KWP_SLOW('4', "ISO 14230-4 KWP (5 baud init)", false, false),
    KWP_FAST('5', "ISO 14230-4 KWP (бърз init)", false, false),
    CAN_11_500('6', "ISO 15765-4 CAN (11 bit, 500 kbaud)", true, false),
    CAN_29_500('7', "ISO 15765-4 CAN (29 bit, 500 kbaud)", true, true),
    CAN_11_250('8', "ISO 15765-4 CAN (11 bit, 250 kbaud)", true, false),
    CAN_29_250('9', "ISO 15765-4 CAN (29 bit, 250 kbaud)", true, true),
    J1939('A', "SAE J1939 CAN", true, true),
    USER1('B', "USER1 CAN", true, false),
    USER2('C', "USER2 CAN", true, false);

    companion object {
        fun fromCode(c: Char): ObdProtocol? = entries.firstOrNull { it.code == c.uppercaseChar() }

        /** Parses the answer of ATDPN, e.g. "A6" (auto, found 6) or "6". */
        fun fromDpn(text: String): ObdProtocol? {
            val t = text.trim().uppercase()
            if (t.isEmpty()) return null
            return fromCode(t.last())?.takeIf { it != AUTO }
        }
    }
}
