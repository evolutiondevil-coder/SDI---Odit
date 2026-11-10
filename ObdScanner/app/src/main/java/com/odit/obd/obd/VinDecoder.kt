package com.odit.obd.obd

import java.util.Calendar

/** Groups of makes that share the same diagnostic addressing. */
enum class Brand { VAG, FORD, TOYOTA, HYUNDAI_KIA, RENAULT_NISSAN, OTHER }

data class VinInfo(val vin: String, val make: String?, val year: Int?, val brand: Brand)

/** Only reads what can be read reliably from any VIN: maker (WMI) and model year. */
object VinDecoder {

    private val WMI: List<Triple<String, String, Brand>> = listOf(
        Triple("WVW", "Volkswagen", Brand.VAG), Triple("WV1", "Volkswagen (бус)", Brand.VAG),
        Triple("WV2", "Volkswagen (бус)", Brand.VAG), Triple("3VW", "Volkswagen", Brand.VAG),
        Triple("1VW", "Volkswagen", Brand.VAG), Triple("WAU", "Audi", Brand.VAG), Triple("WUA", "Audi", Brand.VAG),
        Triple("TRU", "Audi", Brand.VAG), Triple("TMB", "Škoda", Brand.VAG), Triple("VSS", "SEAT / Cupra", Brand.VAG),
        Triple("WP0", "Porsche", Brand.OTHER), Triple("WP1", "Porsche", Brand.OTHER),
        Triple("WF0", "Ford", Brand.FORD), Triple("1FA", "Ford", Brand.FORD), Triple("1FM", "Ford", Brand.FORD),
        Triple("1FT", "Ford", Brand.FORD), Triple("2FM", "Ford", Brand.FORD), Triple("3FA", "Ford", Brand.FORD),
        Triple("NM0", "Ford", Brand.FORD), Triple("JM1", "Mazda", Brand.FORD), Triple("JMZ", "Mazda", Brand.FORD),
        Triple("JM3", "Mazda", Brand.FORD),
        Triple("JTD", "Toyota", Brand.TOYOTA), Triple("JTE", "Toyota", Brand.TOYOTA), Triple("JTN", "Toyota", Brand.TOYOTA),
        Triple("JTM", "Toyota", Brand.TOYOTA), Triple("JTH", "Lexus", Brand.TOYOTA), Triple("JTJ", "Lexus", Brand.TOYOTA),
        Triple("SB1", "Toyota", Brand.TOYOTA), Triple("VNK", "Toyota", Brand.TOYOTA), Triple("NMT", "Toyota", Brand.TOYOTA),
        Triple("4T1", "Toyota", Brand.TOYOTA), Triple("5TD", "Toyota", Brand.TOYOTA), Triple("JT", "Toyota", Brand.TOYOTA),
        Triple("KMH", "Hyundai", Brand.HYUNDAI_KIA), Triple("TMA", "Hyundai", Brand.HYUNDAI_KIA),
        Triple("NLH", "Hyundai", Brand.HYUNDAI_KIA), Triple("5NP", "Hyundai", Brand.HYUNDAI_KIA),
        Triple("KNA", "Kia", Brand.HYUNDAI_KIA), Triple("KND", "Kia", Brand.HYUNDAI_KIA), Triple("KNE", "Kia", Brand.HYUNDAI_KIA),
        Triple("U5Y", "Kia", Brand.HYUNDAI_KIA), Triple("U6Y", "Kia", Brand.HYUNDAI_KIA),
        Triple("VF1", "Renault", Brand.RENAULT_NISSAN), Triple("VF6", "Renault (камион)", Brand.RENAULT_NISSAN),
        Triple("UU1", "Dacia", Brand.RENAULT_NISSAN), Triple("JN1", "Nissan", Brand.RENAULT_NISSAN),
        Triple("JN8", "Nissan", Brand.RENAULT_NISSAN), Triple("SJN", "Nissan", Brand.RENAULT_NISSAN),
        Triple("VSK", "Nissan", Brand.RENAULT_NISSAN), Triple("1N4", "Nissan", Brand.RENAULT_NISSAN),
        Triple("3N1", "Nissan", Brand.RENAULT_NISSAN),
        Triple("WBA", "BMW", Brand.OTHER), Triple("WBS", "BMW M", Brand.OTHER), Triple("WBY", "BMW i", Brand.OTHER),
        Triple("WMW", "MINI", Brand.OTHER), Triple("WDB", "Mercedes-Benz", Brand.OTHER),
        Triple("WDD", "Mercedes-Benz", Brand.OTHER), Triple("WDC", "Mercedes-Benz", Brand.OTHER),
        Triple("WDF", "Mercedes-Benz (бус)", Brand.OTHER), Triple("W1K", "Mercedes-Benz", Brand.OTHER),
        Triple("W1N", "Mercedes-Benz", Brand.OTHER), Triple("W1V", "Mercedes-Benz (бус)", Brand.OTHER),
        Triple("W0L", "Opel", Brand.OTHER), Triple("W0V", "Opel", Brand.OTHER),
        Triple("VF3", "Peugeot", Brand.OTHER), Triple("VR3", "Peugeot", Brand.OTHER), Triple("VF7", "Citroën", Brand.OTHER),
        Triple("VR7", "Citroën", Brand.OTHER), Triple("VR1", "DS", Brand.OTHER), Triple("VXK", "Opel", Brand.OTHER),
        Triple("ZFA", "Fiat", Brand.OTHER), Triple("ZAR", "Alfa Romeo", Brand.OTHER), Triple("ZLA", "Lancia", Brand.OTHER),
        Triple("ZFF", "Ferrari", Brand.OTHER), Triple("JHM", "Honda", Brand.OTHER), Triple("SHH", "Honda", Brand.OTHER),
        Triple("SHS", "Honda", Brand.OTHER), Triple("1HG", "Honda", Brand.OTHER), Triple("2HG", "Honda", Brand.OTHER),
        Triple("YV1", "Volvo", Brand.OTHER), Triple("YV4", "Volvo", Brand.OTHER), Triple("JF1", "Subaru", Brand.OTHER),
        Triple("JF2", "Subaru", Brand.OTHER), Triple("JMB", "Mitsubishi", Brand.OTHER), Triple("JA3", "Mitsubishi", Brand.OTHER),
        Triple("JA4", "Mitsubishi", Brand.OTHER), Triple("MMB", "Mitsubishi", Brand.OTHER), Triple("JS2", "Suzuki", Brand.OTHER),
        Triple("JSA", "Suzuki", Brand.OTHER), Triple("TSM", "Suzuki", Brand.OTHER), Triple("SAL", "Land Rover", Brand.OTHER),
        Triple("SAJ", "Jaguar", Brand.OTHER), Triple("LRW", "Tesla", Brand.OTHER), Triple("5YJ", "Tesla", Brand.OTHER),
        Triple("1G1", "Chevrolet", Brand.OTHER), Triple("KL1", "Chevrolet", Brand.OTHER), Triple("XTA", "Lada", Brand.OTHER),
        Triple("LSJ", "MG", Brand.OTHER), Triple("LVV", "Chery", Brand.OTHER), Triple("LGX", "BYD", Brand.OTHER),
    )

    private const val YEAR_CHARS = "ABCDEFGHJKLMNPRSTVWXY123456789"

    fun isValid(vin: String): Boolean =
        vin.length == 17 && vin.all { it in '0'..'9' || (it in 'A'..'Z' && it != 'I' && it != 'O' && it != 'Q') }

    fun decode(vin: String): VinInfo {
        val v = vin.uppercase()
        val match = WMI.firstOrNull { v.startsWith(it.first) }
        return VinInfo(v, match?.second, modelYear(v), match?.third ?: Brand.OTHER)
    }

    private fun modelYear(vin: String): Int? {
        if (vin.length < 10) return null
        val idx = YEAR_CHARS.indexOf(vin[9])
        if (idx < 0) return null
        val current = Calendar.getInstance().get(Calendar.YEAR)
        // The code repeats every 30 years: pick the newest year that is not in the future.
        var year = 1980 + idx
        while (year + 30 <= current + 1) year += 30
        return year
    }
}
