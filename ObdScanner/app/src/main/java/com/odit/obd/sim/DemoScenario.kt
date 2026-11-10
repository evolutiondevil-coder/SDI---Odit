package com.odit.obd.sim

/** Cars available in the demo mode, each showing one situation the app has to handle. */
enum class DemoScenario(val title: String, val description: String) {
    NORMAL("Обикновен автомобил", "VW Golf с CAN, VIN по OBD (09 02)."),
    VIN_UDS_ENGINE("VIN само през UDS", "Компютърът не отговаря на 09 02, но дава VIN с UDS 22 F190."),
    VIN_OTHER_MODULE("VIN само в друг модул", "Двигателят не дава VIN — намира се при сканиране на модулите."),
    VIN_CONFLICT("Различни VIN в модулите", "ABS модулът е от друга кола (сменен модул)."),
    UNKNOWN_WMI("Непозната марка", "VIN с производител, който не е в таблицата — показва се само държавата."),
    KLINE("Стара кола (K-line)", "ISO 14230 KWP без 09 02; VIN с KWP 1A 90."),
}
