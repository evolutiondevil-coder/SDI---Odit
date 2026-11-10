package com.odit.obd.obd

data class Monitor(val name: String, val complete: Boolean)

/** Decoded mode 01 PID 01: MIL, number of codes and readiness monitors. */
data class Readiness(
    val milOn: Boolean,
    val dtcCount: Int,
    val diesel: Boolean,
    val monitors: List<Monitor>,
) {
    val incomplete: List<Monitor> get() = monitors.filter { !it.complete }

    companion object {
        fun decode(a: Int, b: Int, c: Int, d: Int): Readiness {
            val diesel = b and 0x08 != 0
            val list = mutableListOf<Monitor>()
            // Continuous monitors: supported bits 0-2 of B, incomplete bits 4-6.
            val continuous = listOf("Прекъсвания (misfire)", "Горивна система", "Компоненти")
            for (i in 0..2) {
                if (b and (1 shl i) != 0) list += Monitor(continuous[i], b and (1 shl (i + 4)) == 0)
            }
            val names = if (diesel) {
                listOf(
                    "NMHC катализатор", "NOx / SCR (AdBlue)", "", "Налягане на турбото",
                    "", "Сензор за отработени газове", "Филтър за твърди частици (DPF)", "EGR / VVT",
                )
            } else {
                listOf(
                    "Катализатор", "Подгряван катализатор", "Изпарения (EVAP)", "Вторичен въздух",
                    "", "Ламбда сонди", "Нагреватели на ламбда сондите", "EGR / VVT",
                )
            }
            for (i in 0..7) {
                if (names[i].isEmpty()) continue
                if (c and (1 shl i) != 0) list += Monitor(names[i], d and (1 shl i) == 0)
            }
            return Readiness(a and 0x80 != 0, a and 0x7F, diesel, list)
        }
    }
}
