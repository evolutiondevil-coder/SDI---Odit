package com.odit.obd.garage

import com.odit.obd.uds.DiagType
import com.odit.obd.uds.FoundModule
import com.odit.obd.vehicle.UserVehicle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A module found on this car by a module scan; lets the next scan ask only these addresses. */
data class SavedModule(val tx: Int, val rx: Int, val is29: Boolean, val header: String, val name: String?, val ident: String?, val type: DiagType?)

/** One entry of the scan history. */
data class ScanRecord(val time: Long, val kind: String, val codes: List<String>, val note: String? = null) {
    companion object {
        const val OBD = "obd"
        const val MODULES = "modules"
        const val CLEAR = "clear"
    }
}

/** Online decoding result kept so it is available offline next time. */
data class OnlineInfo(
    val provider: String,
    val time: Long,
    val make: String?,
    val model: String?,
    val year: Int?,
    val engine: String?,
    val raw: String?,
)

/** Everything remembered about one car. */
data class CarRecord(
    val id: String,
    val vin: String?,
    val name: String?,
    val adapter: String?,
    val user: UserVehicle = UserVehicle(),
    val online: OnlineInfo? = null,
    val protocol: Char? = null,
    val modules: List<SavedModule> = emptyList(),
    val history: List<ScanRecord> = emptyList(),
    val lastSeen: Long = System.currentTimeMillis(),
    /** The VIN was typed by the user because the car did not report one. */
    val vinManual: Boolean = false,
) {
    companion object {
        fun idForVin(vin: String) = "vin-$vin"
        fun idForAdapter(address: String) = "adapter-" + address.replace(":", "")
    }
}

/**
 * The garage: one small JSON file per car in the app's private storage. No database library —
 * a handful of cars with a short history each is plenty for files.
 */
class Garage(private val dir: File) {

    init {
        dir.mkdirs()
    }

    private fun file(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    @Synchronized
    fun get(id: String): CarRecord? = file(id).takeIf { it.isFile }?.let { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }

    @Synchronized
    fun all(): List<CarRecord> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { fromJson(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.lastSeen }

    @Synchronized
    fun save(car: CarRecord) {
        val f = file(car.id)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(toJson(car).toString())
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    @Synchronized
    fun delete(id: String) {
        file(id).delete()
    }

    /** The car last seen with this adapter (for its protocol, before the VIN is read). */
    fun lastForAdapter(address: String): CarRecord? = all().firstOrNull { it.adapter == address }

    companion object {
        const val MAX_HISTORY = 50

        fun toJson(c: CarRecord): JSONObject = JSONObject().apply {
            put("id", c.id)
            c.vin?.let { put("vin", it) }
            c.name?.let { put("name", it) }
            c.adapter?.let { put("adapter", it) }
            if (!c.user.isEmpty) {
                put("user", JSONObject().apply {
                    c.user.make?.let { put("make", it) }
                    c.user.model?.let { put("model", it) }
                    c.user.year?.let { put("year", it) }
                    c.user.engine?.let { put("engine", it) }
                })
            }
            c.online?.let { o ->
                put("online", JSONObject().apply {
                    put("provider", o.provider)
                    put("time", o.time)
                    o.make?.let { put("make", it) }
                    o.model?.let { put("model", it) }
                    o.year?.let { put("year", it) }
                    o.engine?.let { put("engine", it) }
                    o.raw?.let { put("raw", it) }
                })
            }
            c.protocol?.let { put("protocol", it.toString()) }
            put("modules", JSONArray().apply {
                for (m in c.modules) put(JSONObject().apply {
                    put("tx", m.tx); put("rx", m.rx); put("is29", m.is29); put("header", m.header)
                    m.name?.let { put("name", it) }
                    m.ident?.let { put("ident", it) }
                    m.type?.let { put("type", it.name) }
                })
            })
            put("history", JSONArray().apply {
                for (h in c.history.takeLast(MAX_HISTORY)) put(JSONObject().apply {
                    put("time", h.time); put("kind", h.kind); put("codes", JSONArray(h.codes))
                    h.note?.let { put("note", it) }
                })
            })
            put("lastSeen", c.lastSeen)
            if (c.vinManual) put("vinManual", true)
        }

        private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

        fun fromJson(o: JSONObject): CarRecord {
            val u = o.optJSONObject("user")
            val on = o.optJSONObject("online")
            val mods = o.optJSONArray("modules") ?: JSONArray()
            val hist = o.optJSONArray("history") ?: JSONArray()
            return CarRecord(
                id = o.getString("id"),
                vin = o.str("vin"),
                name = o.str("name"),
                adapter = o.str("adapter"),
                user = if (u == null) UserVehicle() else UserVehicle(
                    u.str("make"), u.str("model"), if (u.has("year")) u.getInt("year") else null, u.str("engine"),
                ),
                online = on?.let {
                    OnlineInfo(it.getString("provider"), it.optLong("time"), it.str("make"), it.str("model"),
                        if (it.has("year")) it.getInt("year") else null, it.str("engine"), it.str("raw"))
                },
                protocol = o.str("protocol")?.firstOrNull(),
                modules = (0 until mods.length()).map { i ->
                    val m = mods.getJSONObject(i)
                    SavedModule(m.getInt("tx"), m.getInt("rx"), m.optBoolean("is29"), m.getString("header"),
                        m.str("name"), m.str("ident"), m.str("type")?.let { t -> runCatching { DiagType.valueOf(t) }.getOrNull() })
                },
                history = (0 until hist.length()).map { i ->
                    val h = hist.getJSONObject(i)
                    val codes = h.optJSONArray("codes") ?: JSONArray()
                    ScanRecord(h.getLong("time"), h.getString("kind"), (0 until codes.length()).map { codes.getString(it) }, h.str("note"))
                },
                lastSeen = o.optLong("lastSeen"),
                vinManual = o.optBoolean("vinManual"),
            )
        }

        fun fromModules(list: List<FoundModule>): List<SavedModule> = list.map {
            SavedModule(it.tx, it.rx, it.is29, it.responseHeader, it.catalogName, it.identification, it.type)
        }
    }
}
