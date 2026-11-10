package com.odit.obd.obd

import java.util.Locale

/** A mode 01 / 02 parameter with its SAE J1979 formula. */
class PidDef(
    val pid: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    private val decimals: Int = 0,
    private val formula: (IntArray) -> Double?,
) {
    fun decode(data: IntArray): Double? = if (data.size < bytes) null else formula(data)

    fun format(value: Double?): String {
        if (value == null) return "—"
        val number = if (decimals == 0) "%.0f".format(Locale.US, value) else "%.${decimals}f".format(Locale.US, value)
        return if (unit.isEmpty()) number else "$number $unit"
    }

    fun formatRaw(data: IntArray): String = format(decode(data))
}

object Pids {
    private fun ab(d: IntArray) = d[0] * 256 + d[1]
    private fun pct(a: Int) = a * 100.0 / 255.0
    private fun trim(a: Int) = (a - 128) * 100.0 / 128.0

    val all: Map<Int, PidDef> = listOf(
        PidDef(0x04, "Натоварване на двигателя", "%", 1) { pct(it[0]) },
        PidDef(0x05, "Температура на охладителната течност", "°C", 1) { it[0] - 40.0 },
        PidDef(0x06, "Краткосрочна корекция на горивото (банк 1)", "%", 1, 1) { trim(it[0]) },
        PidDef(0x07, "Дългосрочна корекция на горивото (банк 1)", "%", 1, 1) { trim(it[0]) },
        PidDef(0x08, "Краткосрочна корекция на горивото (банк 2)", "%", 1, 1) { trim(it[0]) },
        PidDef(0x09, "Дългосрочна корекция на горивото (банк 2)", "%", 1, 1) { trim(it[0]) },
        PidDef(0x0A, "Налягане на горивото", "kPa", 1) { it[0] * 3.0 },
        PidDef(0x0B, "Налягане във всмукателния колектор", "kPa", 1) { it[0].toDouble() },
        PidDef(0x0C, "Обороти на двигателя", "об/мин", 2) { ab(it) / 4.0 },
        PidDef(0x0D, "Скорост", "км/ч", 1) { it[0].toDouble() },
        PidDef(0x0E, "Ъгъл на запалване", "°", 1, 1) { it[0] / 2.0 - 64.0 },
        PidDef(0x0F, "Температура на всмукания въздух", "°C", 1) { it[0] - 40.0 },
        PidDef(0x10, "Въздушен поток (MAF)", "g/s", 2, 2) { ab(it) / 100.0 },
        PidDef(0x11, "Положение на дроселовата клапа", "%", 1) { pct(it[0]) },
        PidDef(0x14, "Ламбда сонда банк 1 сонда 1", "V", 2, 3) { it[0] / 200.0 },
        PidDef(0x15, "Ламбда сонда банк 1 сонда 2", "V", 2, 3) { it[0] / 200.0 },
        PidDef(0x16, "Ламбда сонда банк 1 сонда 3", "V", 2, 3) { it[0] / 200.0 },
        PidDef(0x17, "Ламбда сонда банк 1 сонда 4", "V", 2, 3) { it[0] / 200.0 },
        PidDef(0x18, "Ламбда сонда банк 2 сонда 1", "V", 2, 3) { it[0] / 200.0 },
        PidDef(0x19, "Ламбда сонда банк 2 сонда 2", "V", 2, 3) { it[0] / 200.0 },
        PidDef(0x1F, "Време от запалването на двигателя", "s", 2) { ab(it).toDouble() },
        PidDef(0x21, "Изминати км със светнала лампа (MIL)", "км", 2) { ab(it).toDouble() },
        PidDef(0x22, "Налягане в рейката (спрямо колектора)", "kPa", 2, 1) { ab(it) * 0.079 },
        PidDef(0x23, "Налягане в горивната рейка", "bar", 2, 1) { ab(it) * 10.0 / 100.0 },
        PidDef(0x24, "Ламбда (широколентова) сонда 1", "λ", 4, 3) { ab(it) * 2.0 / 65536.0 },
        PidDef(0x2C, "Командван EGR", "%", 1) { pct(it[0]) },
        PidDef(0x2D, "Грешка на EGR", "%", 1, 1) { trim(it[0]) },
        PidDef(0x2E, "Продухване на EVAP", "%", 1) { pct(it[0]) },
        PidDef(0x2F, "Ниво на горивото", "%", 1) { pct(it[0]) },
        PidDef(0x30, "Загрявания след изтриване на грешките", "", 1) { it[0].toDouble() },
        PidDef(0x31, "Изминати км след изтриване на грешките", "км", 2) { ab(it).toDouble() },
        PidDef(0x33, "Барометрично налягане", "kPa", 1) { it[0].toDouble() },
        PidDef(0x3C, "Температура на катализатора (банк 1, сензор 1)", "°C", 2) { ab(it) / 10.0 - 40.0 },
        PidDef(0x3D, "Температура на катализатора (банк 2, сензор 1)", "°C", 2) { ab(it) / 10.0 - 40.0 },
        PidDef(0x42, "Напрежение на управляващия модул", "V", 2, 2) { ab(it) / 1000.0 },
        PidDef(0x43, "Абсолютно натоварване", "%", 2) { ab(it) * 100.0 / 255.0 },
        PidDef(0x44, "Командвана ламбда", "λ", 2, 3) { ab(it) * 2.0 / 65536.0 },
        PidDef(0x45, "Относително положение на дроселовата клапа", "%", 1) { pct(it[0]) },
        PidDef(0x46, "Температура на околния въздух", "°C", 1) { it[0] - 40.0 },
        PidDef(0x47, "Абсолютно положение на дроселовата клапа B", "%", 1) { pct(it[0]) },
        PidDef(0x49, "Положение на педала на газта D", "%", 1) { pct(it[0]) },
        PidDef(0x4A, "Положение на педала на газта E", "%", 1) { pct(it[0]) },
        PidDef(0x4C, "Командвана дроселова клапа", "%", 1) { pct(it[0]) },
        PidDef(0x4D, "Време със светнала лампа (MIL)", "мин", 2) { ab(it).toDouble() },
        PidDef(0x4E, "Време след изтриване на грешките", "мин", 2) { ab(it).toDouble() },
        PidDef(0x52, "Етанол в горивото", "%", 1) { pct(it[0]) },
        PidDef(0x59, "Абсолютно налягане в горивната рейка", "bar", 2, 1) { ab(it) * 10.0 / 100.0 },
        PidDef(0x5A, "Относително положение на педала на газта", "%", 1) { pct(it[0]) },
        PidDef(0x5B, "Заряд на хибридната батерия", "%", 1) { pct(it[0]) },
        PidDef(0x5C, "Температура на маслото в двигателя", "°C", 1) { it[0] - 40.0 },
        PidDef(0x5D, "Момент на впръскване", "°", 2, 1) { ab(it) / 128.0 - 210.0 },
        PidDef(0x5E, "Разход на гориво", "л/ч", 2, 1) { ab(it) / 20.0 },
        PidDef(0x61, "Желан въртящ момент", "%", 1) { it[0] - 125.0 },
        PidDef(0x62, "Действителен въртящ момент", "%", 1) { it[0] - 125.0 },
        PidDef(0x63, "Референтен въртящ момент", "Nm", 2) { ab(it).toDouble() },
        PidDef(0xA6, "Километраж (одометър)", "км", 4, 1) {
            ((it[0].toLong() shl 24) or (it[1].toLong() shl 16) or (it[2].toLong() shl 8) or it[3].toLong()) / 10.0
        },
    ).associateBy { it.pid }

    /** Shown first in live data when the car supports them. */
    val defaultPinned = listOf(0x0C, 0x0D, 0x05, 0x04, 0x11, 0x0F, 0x42, 0x10, 0x0B, 0x5C, 0x2F, 0x06, 0x07)

    /** Mode 01 PIDs that are bitmasks of supported PIDs, never shown as values. */
    fun isSupportMask(pid: Int) = pid % 0x20 == 0
}
