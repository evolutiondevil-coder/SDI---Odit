package com.odit.obd.online

import com.odit.obd.garage.OnlineInfo
import com.odit.obd.net.HttpClient
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale

/**
 * A service that adds make / model / year / engine for a VIN. Implementations must send as little
 * of the VIN as the service allows and must never throw: no answer is simply null.
 */
interface OnlineVehicleDecoder {
    val id: String
    val title: String

    /** What is sent, in words, for the consent text. */
    val sends: String

    suspend fun decode(vin: String): OnlineInfo?
}

/**
 * NHTSA vPIC (free, no key). Strong for cars sold in North America, incomplete for European ones,
 * so its answers only fill gaps. Only the first 11 characters are sent, with "*" in place of the
 * check digit — the documented partial-VIN form; the serial number (positions 12–17) never leaves the phone.
 */
class VpicDecoder(private val http: HttpClient) : OnlineVehicleDecoder {
    override val id = "nhtsa-vpic"
    override val title = "NHTSA vPIC (САЩ)"
    override val sends = "първите 11 знака на VIN (без серийния номер)"

    override suspend fun decode(vin: String): OnlineInfo? {
        if (vin.length < 11) return null
        val r = http.get(url(vin))
        if (!r.ok) return null
        return runCatching { parse(r.text()!!, System.currentTimeMillis()) }.getOrNull()
    }

    companion object {
        const val BASE = "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/"

        /** "WVWZZZ1KZ8W123456" -> ".../WVWZZZ1K*8W?format=json". */
        fun url(vin: String): String = BASE + partialVin(vin) + "?format=json"

        fun partialVin(vin: String): String = vin.substring(0, 8).uppercase() + "*" + vin.substring(9, 11).uppercase()

        private val UPPER = setOf("BMW", "MINI", "GMC", "MG", "DS", "BYD", "KIA", "SEAT", "DAF", "MAN", "UAZ", "GAZ")

        /** "VOLKSWAGEN" -> "Volkswagen", "MERCEDES-BENZ" -> "Mercedes-Benz"; known acronyms stay upper case. */
        fun prettyMake(s: String): String = s.trim().split(" ").joinToString(" ") { w ->
            if (w.uppercase() in UPPER) {
                if (w.uppercase() == "KIA") "Kia" else w.uppercase()
            } else {
                w.lowercase(Locale.ROOT).split("-").joinToString("-") { p -> p.replaceFirstChar { it.titlecase(Locale.ROOT) } }
            }
        }

        private fun fuel(s: String): String? = when (s.lowercase(Locale.ROOT)) {
            "" -> null
            "gasoline" -> "бензин"
            "diesel" -> "дизел"
            "electric" -> "електрически"
            "flexible fuel vehicle (ffv)" -> "бензин / етанол"
            "compressed natural gas (cng)" -> "метан"
            "liquefied petroleum gas (propane or lpg)" -> "газ (LPG)"
            else -> s
        }

        fun parse(json: String, time: Long): OnlineInfo? {
            val results = JSONObject(json).optJSONArray("Results") ?: return null
            if (results.length() == 0) return null
            val r = results.getJSONObject(0)
            fun v(k: String) = r.optString(k).trim().takeIf { it.isNotEmpty() && it != "Not Applicable" && it != "0" }
            val make = v("Make")?.let { prettyMake(it) }
            val series = v("Series")
            val model = listOfNotNull(v("Model"), series?.let { "($it)" }).joinToString(" ").ifBlank { null }
            val year = v("ModelYear")?.toIntOrNull()?.takeIf { it in 1980..2100 }
            val displacement = v("DisplacementL")?.toDoubleOrNull()?.let { "%.1f л".format(Locale.US, it) }
            val engine = listOfNotNull(displacement, v("FuelTypePrimary")?.let { fuel(it) }, v("EngineModel"))
                .joinToString(", ").ifBlank { null }
            if (make == null && model == null && year == null && engine == null) return null
            val raw = JSONObject().apply {
                for (k in listOf("Make", "Model", "ModelYear", "Series", "Trim", "BodyClass", "DisplacementL", "FuelTypePrimary", "EngineModel", "PlantCountry", "ErrorCode")) {
                    v(k)?.let { put(k, it) }
                }
            }.toString()
            return OnlineInfo("nhtsa-vpic", time, make, model, year, engine, raw)
        }
    }
}

/** Demo stand-in: answers after a delay without any network, or never (no internet). */
class DemoOnlineDecoder(private val online: Boolean, private val delayMs: Long = 1_500) : OnlineVehicleDecoder {
    override val id = "demo"
    override val title = "Демо (симулиран интернет)"
    override val sends = "нищо — всичко е симулирано"

    override suspend fun decode(vin: String): OnlineInfo? {
        if (!online) return null
        delay(delayMs)
        return when {
            vin.startsWith("WVW") -> OnlineInfo(id, System.currentTimeMillis(), "Volkswagen", null, 2008, null, null)
            vin.startsWith("Y3M") -> OnlineInfo(id, System.currentTimeMillis(), "MAZ", "демо модел", null, "4.8 л, дизел", null)
            vin.startsWith("VF1") -> OnlineInfo(id, System.currentTimeMillis(), "Renault", "Clio", 2005, "1.2 л, бензин", null)
            else -> null
        }
    }
}

/**
 * Extension point for explanations of trouble codes (likely causes, what to check first).
 * Not implemented on purpose: it needs an own server, because an API key must never be stored in
 * the app. A future implementation would send only the code and make/model/year.
 */
interface DtcExplainer {
    suspend fun explain(code: String, vehicleName: String?): DtcExplanation?
}

data class DtcExplanation(val text: String, val source: String)

object NoDtcExplainer : DtcExplainer {
    override suspend fun explain(code: String, vehicleName: String?): DtcExplanation? = null
}
