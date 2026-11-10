package com.odit.obd.data

import com.odit.obd.net.HttpClient
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** One line of manifest.json. */
data class ManifestEntry(
    val id: String,
    val version: Int,
    val file: String,
    val bytes: Int,
    val sha256: String,
    val makes: Set<String>,
)

/**
 * Downloads newer packs listed in the manifest of the data repository. Every file is checked
 * (size, SHA-256, id, version, valid JSON) before it replaces the local copy, and is written
 * atomically, so a broken download can never break the app: the bundled copy stays in use.
 */
class PackUpdater(
    private val http: HttpClient,
    /** App storage root, e.g. filesDir/odit-data (packs go to its "packs" subfolder). */
    private val dir: File,
    private val baseUrl: String = BASE_URL,
) {
    data class Result(val reachable: Boolean, val updated: List<String>, val available: List<ManifestEntry>)

    /**
     * [localVersion] gives the version the app already has (bundled or downloaded);
     * [wanted] decides which packs this phone needs (all general ones, brand ones for its makes).
     */
    suspend fun update(localVersion: (String) -> Int, wanted: (ManifestEntry) -> Boolean): Result {
        val m = http.get(baseUrl + "manifest.json", maxBytes = 512_000)
        val text = m.text()
        if (!m.ok || text == null) return Result(false, emptyList(), emptyList())
        val entries = runCatching { parseManifest(text) }.getOrNull() ?: return Result(false, emptyList(), emptyList())
        val updated = mutableListOf<String>()
        for (e in entries) {
            if (!wanted(e) || e.version <= localVersion(e.id)) continue
            if (download(e)) updated += e.id
        }
        return Result(true, updated, entries)
    }

    private suspend fun download(e: ManifestEntry): Boolean {
        if (!e.file.matches(Regex("packs/[a-z0-9-]+\\.json")) || e.file != "packs/${e.id}.json") return false
        val r = http.get(baseUrl + e.file, maxBytes = maxOf(e.bytes, 1) + 1)
        val body = r.body ?: return false
        if (!r.ok || body.size != e.bytes || sha256(body) != e.sha256.lowercase()) return false
        val ok = runCatching {
            val o = JSONObject(String(body, Charsets.UTF_8))
            o.getString("id") == e.id && o.getInt("version") == e.version && o.optInt("schema", 1) <= PackFormat.SCHEMA && o.has("data")
        }.getOrDefault(false)
        if (!ok) return false
        val packs = File(dir, "packs").apply { mkdirs() }
        val tmp = File(packs, "${e.id}.json.tmp")
        val target = File(packs, "${e.id}.json")
        return runCatching {
            tmp.writeBytes(body)
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target))
            }
            true
        }.getOrElse {
            tmp.delete()
            false
        }
    }

    companion object {
        /** Packs are published from the main branch of the app's repository. */
        const val BASE_URL = "https://raw.githubusercontent.com/evolutiondevil-coder/SDI---Odit/main/odit-data/"

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        fun parseManifest(json: String): List<ManifestEntry> {
            val o = JSONObject(json)
            require(o.optInt("schema", 1) <= PackFormat.SCHEMA)
            val a = o.getJSONArray("packs")
            return (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                val makes = p.optJSONArray("makes")?.let { m -> (0 until m.length()).map { m.getString(it).lowercase() }.toSet() }.orEmpty()
                ManifestEntry(p.getString("id"), p.getInt("version"), p.getString("file"), p.getInt("bytes"), p.getString("sha256"), makes)
            }
        }
    }
}
