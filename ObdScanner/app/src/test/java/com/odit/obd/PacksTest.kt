package com.odit.obd

import com.odit.obd.data.DataPacks
import com.odit.obd.data.DirPackSource
import com.odit.obd.data.PackUpdater
import com.odit.obd.net.HttpClient
import com.odit.obd.obd.Brand
import com.odit.obd.obd.DtcDatabase
import com.odit.obd.uds.ModuleCatalog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.concurrent.thread

class PacksTest {

    private val repo = listOf(File("../../odit-data"), File("../odit-data")).first { File(it, "manifest.json").isFile }
    private val source = DirPackSource(File(repo, "packs"))

    @After
    fun reset() {
        DtcDatabase.genericPack = emptyMap()
        DtcDatabase.brandPacks = emptyList()
        DtcDatabase.currentMake = null
        ModuleCatalog.packGroups = emptyMap()
    }

    @Test
    fun manifestMatchesPacks() {
        val entries = PackUpdater.parseManifest(File(repo, "manifest.json").readText())
        assertEquals(source.ids(), entries.map { it.id }.toSet())
        for (e in entries) {
            val bytes = File(repo, e.file).readBytes()
            assertEquals("${e.id}: run tools/build_manifest.py", e.sha256, PackUpdater.sha256(bytes))
            assertEquals(e.bytes, bytes.size)
            val o = JSONObject(String(bytes))
            assertEquals(e.version, o.getInt("version"))
            assertTrue("${e.id} has no source", o.getJSONArray("sources").length() > 0)
        }
    }

    @Test
    fun genericAndBrandDescriptions() {
        DataPacks.load(source)
        // Generic pack adds codes the built-in table did not have.
        assertTrue(DtcDatabase.describe("B0001").contains("водача"))
        assertTrue(DtcDatabase.describe("P3401").contains("Цилиндър 1"))
        assertTrue(DtcDatabase.describe("U3003").contains("акумулатора"))
        // Built-in descriptions still work.
        assertEquals("Прекъсване (misfire) в цилиндър 1", DtcDatabase.describe("P0301"))
        // Manufacturer code: generic explanation without a known make...
        assertTrue(DtcDatabase.describe("P1450").startsWith("Код на производителя"))
        assertNull(DtcDatabase.lookup("P1450").source)
        // ...and the Ford pack once the make is Ford, with its source shown.
        DtcDatabase.currentMake = "Ford"
        assertTrue(DtcDatabase.describe("P1450").contains("вакуум"))
        assertTrue(DtcDatabase.lookup("P1450").source!!.contains("непроверено"))
        // A brand pack never replaces a generic code.
        assertEquals("Прекъсване (misfire) в цилиндър 1", DtcDatabase.describe("P0301"))
        // Other make: no Ford text.
        DtcDatabase.currentMake = "Toyota"
        assertTrue(DtcDatabase.describe("P1450").startsWith("Код на производителя"))
        assertTrue(DtcDatabase.describe("P1349").contains("VVT"))
    }

    @Test
    fun modulesPackReplacesBuiltInNames() {
        DataPacks.load(source)
        assertEquals("ABS / ESP (спирачки)", ModuleCatalog.nameFor(Brand.VAG, 0x713))
        assertTrue(ModuleCatalog.quickList(Brand.VAG).any { it.tx == 0x713 && it.rx == 0x77D })
    }

    // ------------------------------------------------------------------- updater

    /** Serves files from [files] (path -> bytes); unknown paths get 404. */
    private fun server(files: Map<String, ByteArray>): ServerSocket {
        val ss = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    c.use { sock ->
                        val path = sock.getInputStream().bufferedReader().readLine().orEmpty().split(" ").getOrElse(1) { "/" }
                        val body = files[path.removePrefix("/")]
                        val head = if (body != null) "HTTP/1.1 200 OK" else "HTTP/1.1 404 Not Found"
                        val b = body ?: ByteArray(0)
                        sock.getOutputStream().write("$head\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray() + b)
                    }
                }
            }
        }
        return ss
    }

    private fun pack(id: String, version: Int, makes: List<String>? = null): ByteArray =
        JSONObject().apply {
            put("id", id); put("version", version); put("schema", 1); put("title", id)
            put("sources", org.json.JSONArray().put(JSONObject().put("name", "test")))
            makes?.let { put("makes", org.json.JSONArray(it)) }
            put("data", JSONObject().put("codes", JSONObject().put("P1000", "тест")))
        }.toString().toByteArray()

    private fun manifest(vararg entries: Triple<String, ByteArray, List<String>?>, badSha: String? = null): ByteArray =
        JSONObject().apply {
            put("schema", 1)
            put("packs", org.json.JSONArray().apply {
                for ((id, b, makes) in entries) put(JSONObject().apply {
                    put("id", id); put("version", JSONObject(String(b)).getInt("version")); put("file", "packs/$id.json")
                    put("bytes", b.size); put("sha256", if (id == badSha) "00".repeat(32) else PackUpdater.sha256(b))
                    makes?.let { put("makes", org.json.JSONArray(it)) }
                })
            })
        }.toString().toByteArray()

    @Test
    fun updaterDownloadsOnlyNewerValidWantedPacks() = runBlocking {
        val generic = pack("dtc-generic", 5)
        val ford = pack("dtc-ford", 2, listOf("Ford"))
        val bmw = pack("dtc-bmw", 1, listOf("BMW"))
        val broken = pack("wmi", 9)
        val files = mapOf(
            "odit-data/manifest.json" to manifest(
                Triple("dtc-generic", generic, null), Triple("dtc-ford", ford, listOf("Ford")),
                Triple("dtc-bmw", bmw, listOf("BMW")), Triple("wmi", broken, null), badSha = "wmi",
            ),
            "odit-data/packs/dtc-generic.json" to generic,
            "odit-data/packs/dtc-ford.json" to ford,
            "odit-data/packs/dtc-bmw.json" to bmw,
            "odit-data/packs/wmi.json" to broken,
        )
        val ss = server(files)
        try {
            val dir = Files.createTempDirectory("packs").toFile()
            val updater = PackUpdater(HttpClient(1_000, 2_000), dir, "http://127.0.0.1:${ss.localPort}/odit-data/")
            val local = mapOf("dtc-generic" to 1, "dtc-ford" to 2, "wmi" to 1)
            val make = "ford"
            val r = updater.update(localVersion = { local[it] ?: 0 }, wanted = { e -> e.makes.isEmpty() || make in e.makes })
            assertTrue(r.reachable)
            // generic: newer -> downloaded; ford: same version -> skipped; bmw: other make -> skipped; wmi: bad hash -> rejected.
            assertEquals(listOf("dtc-generic"), r.updated)
            assertTrue(File(dir, "packs/dtc-generic.json").isFile)
            assertFalse(File(dir, "packs/wmi.json").exists())
            assertFalse(File(dir, "packs/dtc-bmw.json").exists())
            // The downloaded pack is now preferred by a source reading that folder.
            assertEquals(5, DirPackSource(File(dir, "packs")).version("dtc-generic"))
        } finally {
            ss.close()
        }
    }

    @Test
    fun updaterOfflineChangesNothing() = runBlocking {
        val dir = Files.createTempDirectory("packs").toFile()
        val r = PackUpdater(HttpClient(300, 300), dir, "http://127.0.0.1:1/odit-data/").update({ 0 }, { true })
        assertFalse(r.reachable)
        assertTrue(r.updated.isEmpty())
        assertTrue(dir.listFiles().isNullOrEmpty())
    }
}
