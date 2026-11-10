package com.odit.obd.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Where pack JSON comes from: the copy bundled in the APK, a downloaded update, or test files. */
fun interface PackSource {
    /** Full text of pack [id] (e.g. "wmi"), or null if there is none. */
    fun read(id: String): String?
}

/** Metadata common to all packs. */
data class PackInfo(val id: String, val version: Int, val title: String, val sources: List<String>)

object PackFormat {
    const val SCHEMA = 1

    fun info(root: JSONObject): PackInfo {
        val src = root.optJSONArray("sources")
        val list = (0 until (src?.length() ?: 0)).map { i ->
            val s = src!!.getJSONObject(i)
            listOf(s.optString("name"), s.optString("license")).filter { it.isNotBlank() }.joinToString(" · ")
        }
        return PackInfo(root.optString("id"), root.optInt("version"), root.optString("title"), list)
    }
}

/** Packs bundled in the APK under assets/odit-data/packs, overridden by newer downloaded ones. */
class AppPackSource(private val context: Context) : PackSource {
    private val downloaded = File(context.filesDir, "odit-data/packs")

    override fun read(id: String): String? {
        val bundled = runCatching {
            context.assets.open("odit-data/packs/$id.json").bufferedReader().use { it.readText() }
        }.getOrNull()
        val local = File(downloaded, "$id.json").takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        if (local == null) return bundled
        if (bundled == null) return local
        // Use whichever has the higher version: an app update may bundle a newer pack than the download.
        val vb = runCatching { JSONObject(bundled).optInt("version") }.getOrDefault(0)
        val vl = runCatching { JSONObject(local).optInt("version") }.getOrDefault(0)
        return if (vl > vb) local else bundled
    }
}

/** Reads packs from a directory (tests, tools). */
class DirPackSource(private val dir: File) : PackSource {
    override fun read(id: String): String? = File(dir, "$id.json").takeIf { it.isFile }?.readText()
}
