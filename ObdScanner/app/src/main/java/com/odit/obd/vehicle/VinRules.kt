package com.odit.obd.vehicle

import com.odit.obd.data.PackFormat
import com.odit.obd.data.PackInfo
import org.json.JSONObject

/** How much the model year in the VIN can be trusted for a make. */
enum class YearPolicy {
    /** The position is the model year (VAG, North American VINs). */
    RELIABLE,

    /** The position is usually a year, but not for every model or period: shown as "вероятно". */
    PROBABLE,

    /** The position is not a year for this make (e.g. European Mercedes): never shown. */
    NONE,
}

/** A text pattern on VIN positions, e.g. positions 4–6 equal "ZZZ". */
data class VinCondition(val from: Int, val to: Int, val equals: String) {
    fun matches(vin: String) = vin.length >= to && vin.substring(from - 1, to) == equals
}

data class YearRule(val position: Int, val policy: YearPolicy, val condition: VinCondition?, val note: String?)

data class ModelRule(
    val condition: VinCondition?,
    val from: Int,
    val to: Int,
    val label: String?,
    val map: Map<String, String>,
)

data class MakeRules(val years: List<YearRule>, val models: List<ModelRule>)

/**
 * Per-make VIN rules (model code positions, model year position and reliability), loaded from the
 * "vin-rules" pack so they can be extended without a new APK.
 */
class VinRules private constructor(
    private val makes: Map<String, MakeRules>,
    private val defaultYear: YearRule,
    private val northAmericaYear: YearRule,
    val yearCodes: String,
    val info: PackInfo?,
) {
    fun forMake(make: String?): MakeRules? = make?.let { makes[it] }

    /** The year rule that applies to this VIN (first matching rule of the make, else the defaults). */
    fun yearRule(vin: String, make: String?): YearRule {
        if (vin.isNotEmpty() && vin[0] in "12345") return northAmericaYear
        val rules = forMake(make)?.years.orEmpty()
        return rules.firstOrNull { it.condition == null || it.condition.matches(vin) } ?: defaultYear
    }

    companion object {
        private const val DEFAULT_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"

        val EMPTY = VinRules(
            emptyMap(),
            YearRule(10, YearPolicy.PROBABLE, null, null),
            YearRule(10, YearPolicy.RELIABLE, null, null),
            DEFAULT_CODES,
            null,
        )

        private fun range(s: String): Pair<Int, Int> {
            val p = s.split("-")
            return p[0].trim().toInt() to (p.getOrNull(1) ?: p[0]).trim().toInt()
        }

        private fun condition(o: JSONObject?): VinCondition? {
            o ?: return null
            val (a, b) = range(o.getString("pos"))
            return VinCondition(a, b, o.getString("is"))
        }

        private fun year(o: JSONObject): YearRule = YearRule(
            position = o.optInt("pos", 10),
            policy = runCatching { YearPolicy.valueOf(o.optString("policy", "PROBABLE").uppercase()) }.getOrDefault(YearPolicy.PROBABLE),
            condition = condition(o.optJSONObject("if")),
            note = o.optString("note").takeIf { it.isNotEmpty() },
        )

        fun parse(json: String?): VinRules {
            if (json == null) return EMPTY
            val root = JSONObject(json)
            val data = root.getJSONObject("data")
            val maps = HashMap<String, Map<String, String>>()
            data.optJSONObject("maps")?.let { m ->
                for (k in m.keys()) {
                    val o = m.getJSONObject(k)
                    maps[k] = o.keys().asSequence().associateWith { o.getString(it) }
                }
            }
            val makes = HashMap<String, MakeRules>()
            val mk = data.getJSONObject("makes")
            for (name in mk.keys()) {
                val o = mk.getJSONObject(name)
                val years = o.optJSONArray("years")?.let { a -> (0 until a.length()).map { year(a.getJSONObject(it)) } }.orEmpty()
                val models = o.optJSONArray("models")?.let { a ->
                    (0 until a.length()).map { i ->
                        val r = a.getJSONObject(i)
                        val (from, to) = range(r.getString("code"))
                        val map = when (val m = r.opt("map")) {
                            is String -> maps[m].orEmpty()
                            is JSONObject -> m.keys().asSequence().associateWith { m.getString(it) }
                            else -> emptyMap()
                        }
                        ModelRule(condition(r.optJSONObject("if")), from, to, r.optString("label").takeIf { it.isNotEmpty() }, map)
                    }
                }.orEmpty()
                makes[name] = MakeRules(years, models)
            }
            return VinRules(
                makes,
                data.optJSONObject("default")?.let { year(it) } ?: EMPTY.defaultYear,
                data.optJSONObject("northAmerica")?.let { year(it) } ?: EMPTY.northAmericaYear,
                data.optString("yearCodes", DEFAULT_CODES),
                PackFormat.info(root),
            )
        }
    }
}
