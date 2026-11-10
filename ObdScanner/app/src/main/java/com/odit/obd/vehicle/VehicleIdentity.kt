package com.odit.obd.vehicle

import com.odit.obd.obd.Brand
import java.util.Calendar

/** How sure the app is about a value; anything but CERTAIN and USER is shown with "вероятно" or its source. */
enum class Confidence { CERTAIN, PROBABLE, ONLINE, USER }

data class Field<T>(val value: T, val confidence: Confidence, val source: String) {
    val probable: Boolean get() = confidence == Confidence.PROBABLE || confidence == Confidence.ONLINE
}

/** What the app knows about the car: from the VIN rules, an online decoder and the user's corrections. */
data class VehicleIdentity(
    val vin: String?,
    val make: Field<String>? = null,
    val maker: String? = null,
    val country: String? = null,
    val model: Field<String>? = null,
    val year: Field<Int>? = null,
    val engine: Field<String>? = null,
    val group: Brand = Brand.OTHER,
    /** Why the year is not shown (e.g. the make does not encode it). */
    val yearNote: String? = null,
) {
    /** "Volkswagen Golf V 2008" for searches and titles; only values that are known. */
    val shortName: String
        get() = listOfNotNull(make?.value, model?.value, year?.value?.toString()).joinToString(" ")

    companion object {
        val UNKNOWN = VehicleIdentity(null)
    }
}

/** Corrections typed by the user; they always win and are remembered per car. */
data class UserVehicle(
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val engine: String? = null,
) {
    val isEmpty: Boolean get() = make == null && model == null && year == null && engine == null
}

/** Decodes a VIN offline with the WMI table and the per-make rules. Never guesses beyond the rules. */
class VehicleDecoder(private val wmi: WmiDb, private val rules: VinRules) {

    fun decode(vin: String?, user: UserVehicle? = null, currentYear: Int = Calendar.getInstance().get(Calendar.YEAR)): VehicleIdentity {
        var id = if (vin == null) VehicleIdentity.UNKNOWN else decodeVin(vin.uppercase(), currentYear)
        if (user != null) {
            id = applyUser(id, user)
            if (user.make != null) id = id.copy(group = wmi.groupForMake(user.make))
        }
        return id
    }

    private fun decodeVin(vin: String, currentYear: Int): VehicleIdentity {
        val entry = wmi.lookup(vin)
        val makeName = entry?.make
        val make = makeName?.let { Field(it, Confidence.CERTAIN, "WMI ${vin.take(3)}") }
        val country = entry?.country ?: wmi.country(vin)

        var model: Field<String>? = null
        for (rule in rules.forMake(makeName)?.models.orEmpty()) {
            if (rule.condition != null && !rule.condition.matches(vin)) continue
            if (vin.length < rule.to) continue
            val code = vin.substring(rule.from - 1, rule.to)
            val name = rule.map[code] ?: continue
            val label = rule.label?.let { " ($it $code)" }.orEmpty()
            model = Field(name, Confidence.PROBABLE, "VIN позиции ${rule.from}–${rule.to}$label")
            break
        }

        val yr = rules.yearRule(vin, makeName)
        var year: Field<Int>? = null
        var note: String? = yr.note
        if (yr.policy != YearPolicy.NONE && vin.length >= yr.position) {
            val y = modelYear(vin, yr.position, currentYear)
            if (y != null) {
                val conf = if (yr.policy == YearPolicy.RELIABLE) Confidence.CERTAIN else Confidence.PROBABLE
                year = Field(y, conf, "VIN позиция ${yr.position}")
                note = if (conf == Confidence.PROBABLE) yr.note else null
            }
        }
        return VehicleIdentity(
            vin = vin,
            make = make,
            maker = entry?.maker,
            country = country,
            model = model,
            year = year,
            group = entry?.group ?: com.odit.obd.obd.Brand.OTHER,
            yearNote = note,
        )
    }

    /** Model year from the 30-year cycle code; for North American VINs position 7 picks the cycle. */
    fun modelYear(vin: String, position: Int, currentYear: Int): Int? {
        val idx = rules.yearCodes.indexOf(vin[position - 1])
        if (idx < 0) return null
        val base = 1980 + idx
        if (vin[0] in "12345" && position == 10 && vin.length >= 7) {
            // 49 CFR 565: numeric position 7 means 1980–2009, a letter means 2010–2039.
            return if (vin[6].isDigit()) base else base + 30
        }
        var year = base
        while (year + 30 <= currentYear + 1) year += 30
        return year
    }

    companion object {
        fun applyUser(id: VehicleIdentity, user: UserVehicle): VehicleIdentity = id.copy(
            make = user.make?.let { Field(it, Confidence.USER, "въведено ръчно") } ?: id.make,
            model = user.model?.let { Field(it, Confidence.USER, "въведено ръчно") } ?: id.model,
            year = user.year?.let { Field(it, Confidence.USER, "въведено ръчно") } ?: id.year,
            engine = user.engine?.let { Field(it, Confidence.USER, "въведено ръчно") } ?: id.engine,
            yearNote = if (user.year != null) null else id.yearNote,
        )
    }
}
