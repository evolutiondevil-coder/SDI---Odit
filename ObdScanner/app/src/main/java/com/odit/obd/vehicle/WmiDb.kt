package com.odit.obd.vehicle

import com.odit.obd.data.PackInfo
import com.odit.obd.data.PackFormat
import com.odit.obd.obd.Brand
import org.json.JSONObject

/** One World Manufacturer Identifier (VIN positions 1–3). */
data class WmiEntry(val maker: String, val make: String?, val country: String?, val group: Brand)

/** Manufacturer by WMI, and the country of manufacture by the first two characters when the WMI is unknown. */
class WmiDb private constructor(
    private val wmi: Map<String, WmiEntry>,
    private val countries: List<Triple<String, String, String>>,
    private val order: String,
    val info: PackInfo?,
) {
    fun lookup(vin: String): WmiEntry? = if (vin.length >= 3) wmi[vin.substring(0, 3).uppercase()] else null

    /** Country of manufacture from VIN positions 1–2 (ISO 3780 ranges). */
    fun country(vin: String): String? {
        if (vin.length < 2) return null
        val c1 = vin[0].uppercaseChar()
        val c2 = vin[1].uppercaseChar()
        for ((from, to, name) in countries) {
            if (from.length == 1) {
                if (from[0] == c1) return name
                continue
            }
            if (from[0] != c1) continue
            val i = order.indexOf(c2)
            if (i >= 0 && i >= order.indexOf(from[1]) && i <= order.indexOf(to.last())) return name
        }
        return null
    }

    val size: Int get() = wmi.size

    private val groups: Map<String, Brand> by lazy {
        wmi.values.filter { it.make != null && it.group != Brand.OTHER }.associate { it.make!!.lowercase() to it.group }
    }

    /** Diagnostic group for a make typed by the user ("volkswagen" -> VAG). */
    fun groupForMake(make: String): Brand = groups[make.trim().lowercase()] ?: Brand.OTHER

    companion object {
        val EMPTY = WmiDb(emptyMap(), emptyList(), "ABCDEFGHJKLMNPRSTUVWXYZ1234567890", null)

        fun parse(json: String?): WmiDb {
            if (json == null) return EMPTY
            val root = JSONObject(json)
            val data = root.getJSONObject("data")
            val map = HashMap<String, WmiEntry>(2048)
            val w = data.getJSONObject("wmi")
            for (key in w.keys()) {
                val e = w.getJSONObject(key)
                val group = e.optString("group").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { Brand.valueOf(it) }.getOrNull() } ?: Brand.OTHER
                map[key] = WmiEntry(
                    maker = e.optString("maker"),
                    make = e.optString("make").takeIf { it.isNotEmpty() },
                    country = e.optString("country").takeIf { it.isNotEmpty() },
                    group = group,
                )
            }
            val c = data.optJSONArray("countries")
            val countries = (0 until (c?.length() ?: 0)).map { i ->
                val o = c!!.getJSONObject(i)
                Triple(o.getString("from"), o.getString("to"), o.getString("country"))
            }
            return WmiDb(map, countries, data.optString("order", "ABCDEFGHJKLMNPRSTUVWXYZ1234567890"), PackFormat.info(root))
        }
    }
}
