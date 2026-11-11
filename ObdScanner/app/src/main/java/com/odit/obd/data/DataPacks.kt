package com.odit.obd.data

import com.odit.obd.obd.Brand
import com.odit.obd.obd.DtcDatabase
import com.odit.obd.uds.KCodes
import com.odit.obd.uds.KInit
import com.odit.obd.uds.KLineTarget
import com.odit.obd.uds.ModuleAddress
import com.odit.obd.uds.ModuleCatalog
import com.odit.obd.vehicle.VinRules
import com.odit.obd.vehicle.WmiDb
import org.json.JSONObject

/** Everything loaded from the packs; built on a background thread, then swapped in at once. */
class DataPacks(
    val wmi: WmiDb,
    val vinRules: VinRules,
    val infos: List<PackInfo>,
) {
    companion object {
        val EMPTY = DataPacks(WmiDb.EMPTY, VinRules.EMPTY, emptyList())

        /** Loads all packs and installs the trouble-code and module overlays. */
        fun load(source: PackSource): DataPacks {
            val infos = mutableListOf<PackInfo>()
            fun info(json: String?) = json?.let { runCatching { PackFormat.info(JSONObject(it)) }.getOrNull() }?.let { infos += it }

            val wmiJson = source.read("wmi")
            val rulesJson = source.read("vin-rules")
            info(wmiJson)
            info(rulesJson)

            source.read("dtc-generic")?.let { j ->
                runCatching { DtcDatabase.genericPack = codes(JSONObject(j)) }
                info(j)
            }
            val brands = mutableListOf<DtcDatabase.BrandCodes>()
            for (id in source.ids().filter { it.startsWith("dtc-") && it != "dtc-generic" }.sorted()) {
                val j = source.read(id) ?: continue
                runCatching {
                    val o = JSONObject(j)
                    val makes = o.optJSONArray("makes")?.let { a -> (0 until a.length()).map { a.getString(it).lowercase() }.toSet() }.orEmpty()
                    brands += DtcDatabase.BrandCodes(makes, o.optString("title", id), codes(o))
                }
                info(j)
            }
            DtcDatabase.brandPacks = brands

            source.read("modules")?.let { j ->
                runCatching { ModuleCatalog.packGroups = modules(JSONObject(j)) }
                runCatching { ModuleCatalog.packKLine = kLine(JSONObject(j)) }
                info(j)
            }
            return DataPacks(
                runCatching { WmiDb.parse(wmiJson) }.getOrDefault(WmiDb.EMPTY),
                runCatching { VinRules.parse(rulesJson) }.getOrDefault(VinRules.EMPTY),
                infos,
            )
        }

        private fun codes(o: JSONObject): Map<String, String> {
            val c = o.getJSONObject("data").getJSONObject("codes")
            return c.keys().asSequence().associate { it.uppercase() to c.getString(it) }
        }

        private fun kLine(o: JSONObject): Map<String, List<KLineTarget>> {
            val g = o.getJSONObject("data").optJSONObject("kline") ?: return emptyMap()
            val out = HashMap<String, List<KLineTarget>>()
            for (k in g.keys()) {
                val a = g.getJSONArray(k)
                out[k] = (0 until a.length()).map { i ->
                    val m = a.getJSONObject(i)
                    KLineTarget(
                        m.getString("addr").toInt(16),
                        m.optString("name").ifEmpty { null },
                        if (m.optString("init") == "slow") KInit.SLOW else KInit.FAST_THEN_SLOW,
                        if (m.optString("codes") == "vag") KCodes.VAG else KCodes.RAW,
                    )
                }
            }
            return out
        }

        private fun modules(o: JSONObject): Map<Brand, List<ModuleAddress>> {
            val g = o.getJSONObject("data").getJSONObject("groups")
            val out = HashMap<Brand, List<ModuleAddress>>()
            for (k in g.keys()) {
                val brand = runCatching { Brand.valueOf(k) }.getOrNull() ?: continue
                val a = g.getJSONArray(k)
                out[brand] = (0 until a.length()).map { i ->
                    val m = a.getJSONObject(i)
                    ModuleAddress(m.getString("tx").toInt(16), m.getString("rx").toInt(16), m.optString("name").ifEmpty { null })
                }
            }
            return out
        }
    }
}
