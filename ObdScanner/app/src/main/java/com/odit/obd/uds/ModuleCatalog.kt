package com.odit.obd.uds

import com.odit.obd.obd.Brand

/** A diagnostic address to try. [rx] is the expected answer id when known (used if the adapter can't filter a range). */
data class ModuleAddress(val tx: Int, val rx: Int, val name: String?)

/**
 * Well-known 11-bit CAN diagnostic addresses. Names are only shown when the make is known from the VIN,
 * because the same address means different modules for different makes.
 */
object ModuleCatalog {

    private fun vag(tx: Int, name: String) = ModuleAddress(tx, tx + 0x6A, name)
    private fun std(tx: Int, name: String) = ModuleAddress(tx, tx + 0x08, name)
    private fun nis(tx: Int, name: String) = ModuleAddress(tx, tx + 0x20, name)

    private val common = listOf(
        std(0x7E0, "Двигател"),
        std(0x7E1, "Скоростна кутия"),
    )

    /** Module lists from the "modules" pack; when present they replace the built-in ones. */
    @Volatile var packGroups: Map<Brand, List<ModuleAddress>> = emptyMap()

    private val byBrand: Map<Brand, List<ModuleAddress>> get() = packGroups.ifEmpty { builtIn }

    private val builtIn: Map<Brand, List<ModuleAddress>> = mapOf(
        Brand.VAG to listOf(
            vag(0x713, "ABS / ESP (спирачки)"), vag(0x715, "Въздушни възглавници"),
            vag(0x714, "Арматурно табло"), vag(0x70E, "Централна електроника (BCM)"),
            vag(0x710, "Гейтуей (Gateway)"), vag(0x712, "Сервоусилвател на волана"),
            vag(0x70C, "Електроника на волана"), vag(0x746, "Климатик"),
            vag(0x74A, "Врата на водача"), vag(0x74B, "Врата на пасажера"),
            vag(0x773, "Мултимедия / радио"), vag(0x70A, "Парктроник"),
            vag(0x757, "Адаптивен круиз контрол"),
        ),
        Brand.FORD to listOf(
            std(0x760, "ABS / ESP (спирачки)"), std(0x737, "Въздушни възглавници"),
            std(0x720, "Арматурно табло"), std(0x726, "Модул на купето (BCM)"),
            std(0x730, "Сервоусилвател на волана"), std(0x724, "Модул на волана"),
            std(0x733, "Климатик"), std(0x727, "Аудио"), std(0x7D0, "SYNC / мултимедия"),
            std(0x706, "Предна камера"), std(0x764, "Радар / круиз контрол"), std(0x716, "Гейтуей (Gateway)"),
            std(0x740, "Врата на водача"), std(0x741, "Врата на пасажера"),
        ),
        Brand.TOYOTA to listOf(
            std(0x7E2, "Хибридна система"), std(0x7B0, "ABS / VSC (спирачки)"),
            std(0x780, "Въздушни възглавници"), std(0x7C0, "Арматурно табло"),
            std(0x7A1, "Сервоусилвател на волана"), std(0x7C4, "Климатик"),
        ),
        Brand.HYUNDAI_KIA to listOf(
            std(0x7D1, "ABS / ESC (спирачки)"), std(0x7D2, "Въздушни възглавници"),
            std(0x7C6, "Арматурно табло"), std(0x770, "Модул на купето (BCM)"),
            std(0x7D4, "Сервоусилвател на волана"), std(0x7B3, "Климатик"),
        ),
        Brand.RENAULT_NISSAN to listOf(
            nis(0x740, "ABS / ESP (спирачки)"), nis(0x743, "Арматурно табло"),
            nis(0x745, "Модул на купето (BCM / UCH)"), nis(0x752, "Въздушни възглавници"),
            nis(0x742, "Сервоусилвател на волана"), nis(0x744, "Климатик"),
        ),
    )

    /** Addresses for the quick scan: everything known for every make, plus the OBD range. */
    fun quickList(brand: Brand): List<ModuleAddress> {
        val result = LinkedHashMap<Int, ModuleAddress>()
        for (a in common) result[a.tx] = a
        byBrand[brand]?.forEach { result[it.tx] = it }
        for ((b, list) in byBrand) if (b != brand) for (a in list) result.putIfAbsent(a.tx, a.copy(name = null))
        for (tx in 0x7E2..0x7E7) result.putIfAbsent(tx, ModuleAddress(tx, tx + 8, null))
        return result.values.toList()
    }

    /** Every 11-bit physical address 0x700..0x7E7 (except the OBD broadcast id 0x7DF). */
    fun fullList(brand: Brand): List<ModuleAddress> {
        val known = quickList(brand).associateBy { it.tx }
        return (0x700..0x7E7).filter { it != 0x7DF }.map { known[it] ?: ModuleAddress(it, it + 8, null) }
    }

    fun nameFor(brand: Brand, tx: Int): String? =
        common.firstOrNull { it.tx == tx }?.name ?: byBrand[brand]?.firstOrNull { it.tx == tx }?.name

    /** 29-bit ISO 15765-4 normal fixed addressing: 18DA<target>F1. */
    fun addresses29(full: Boolean): List<Int> = if (full) (0x00..0xFF).filter { it != 0xF1 } else (0x00..0x7F).toList()

    fun name29(addr: Int): String? = when (addr) {
        0x10, 0x11 -> "Двигател"
        0x18 -> "Скоростна кутия"
        0x28 -> "ABS / спирачки"
        else -> null
    }
}
