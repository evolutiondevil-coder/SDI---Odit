package com.odit.obd

import com.odit.obd.data.DirPackSource
import com.odit.obd.garage.OnlineInfo
import com.odit.obd.net.HttpClient
import com.odit.obd.online.VpicDecoder
import com.odit.obd.vehicle.Confidence
import com.odit.obd.vehicle.UserVehicle
import com.odit.obd.vehicle.VehicleDecoder
import com.odit.obd.vehicle.VinRules
import com.odit.obd.vehicle.WmiDb
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class OnlineTest {

    private fun fixture(name: String) =
        listOf(File("src/test/resources/vpic/$name"), File("app/src/test/resources/vpic/$name")).first { it.isFile }.readText()

    @Test
    fun onlyTheFirst11CharactersAreSent() {
        val url = VpicDecoder.url("WVWZZZ1KZ8W123456")
        assertEquals("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/WVWZZZ1K*8W?format=json", url)
        assertFalse(url.contains("123456"))
    }

    @Test
    fun parsesRealVpicAnswers() {
        val bmw = VpicDecoder.parse(fixture("bmw-WBA3B1C5_EK.json"), 1L)!!
        assertEquals("BMW", bmw.make)
        assertEquals("320i (3-Series)", bmw.model)
        assertEquals(2014, bmw.year)
        assertEquals("2.0 л, бензин", bmw.engine)
        val vw = VpicDecoder.parse(fixture("vw-WVWZZZ1K_8W.json"), 1L)!!
        assertEquals("Volkswagen", vw.make)
        assertNull(vw.model)
        // Not registered in the US: nothing to add.
        assertNull(VpicDecoder.parse(fixture("skoda-unregistered.json"), 1L))
        assertEquals("Mercedes-Benz", VpicDecoder.prettyMake("MERCEDES-BENZ"))
        assertEquals("Kia", VpicDecoder.prettyMake("KIA"))
    }

    private val packs = DirPackSource(listOf(File("../../odit-data/packs"), File("../odit-data/packs")).first { it.isDirectory })
    private val decoder = VehicleDecoder(WmiDb.parse(packs.read("wmi")), VinRules.parse(packs.read("vin-rules")))

    @Test
    fun onlineOnlyFillsGaps() {
        val bmwOnline = OnlineInfo("nhtsa-vpic", 1L, "BMW", "320i (3-Series)", 2014, "2.0 л, бензин", null)
        val bmw = decoder.decode("WBA3B1C50EK000001", online = bmwOnline, currentYear = 2026)
        assertEquals("BMW", bmw.make?.value)
        assertEquals(Confidence.CERTAIN, bmw.make?.confidence)       // the WMI table already knew it
        assertEquals(Confidence.ONLINE, bmw.model?.confidence)       // filled from vPIC
        // vPIC recognised the model, so its year is from a matching US pattern and can be shown (as online).
        assertEquals(2014, bmw.year?.value)
        assertEquals(Confidence.ONLINE, bmw.year?.confidence)
        // A year without a recognised model is just position 10 read blindly (wrong for Renault): ignored.
        assertNull(decoder.decode("VF15R0G0H41234567", online = OnlineInfo("nhtsa-vpic", 1L, "Renault", null, 2004, null, null)).year)
        // VW: the offline model stays, the online one is ignored.
        val vw = decoder.decode("WVWZZZ1KZ8W123456", online = OnlineInfo("x", 1L, "Volkswagen", "Rabbit", 2008, null, null))
        assertEquals("Golf V / Jetta V", vw.model?.value)
        // Mercedes: vPIC's year from position 10 is not used.
        val mb = decoder.decode("WDD2050421R123456", online = OnlineInfo("x", 1L, "Mercedes-Benz", null, 2001, null, null))
        assertNull(mb.year)
        // The user's correction beats everything.
        val corrected = decoder.decode("WBA3B1C50EK000001", UserVehicle(model = "318d"), bmwOnline)
        assertEquals("318d", corrected.model?.value)
        // Unknown WMI: online make fills in.
        val unknown = decoder.decode("Y3MZZZ15ZKA000777", online = OnlineInfo("x", 1L, "MAZ", null, null, null, null))
        assertEquals("MAZ", unknown.make?.value)
        assertTrue(unknown.make!!.probable)
    }

    /** Minimal HTTP server on a plain socket: "/ok" answers at once, anything else never answers. */
    private fun server(): Pair<ServerSocket, Thread> {
        val ss = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val t = thread(isDaemon = true) {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    c.use { sock ->
                        val line = sock.getInputStream().bufferedReader().readLine().orEmpty()
                        if (line.contains("/ok")) {
                            val body = "{\"a\":1}"
                            sock.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(),
                            )
                        } else {
                            Thread.sleep(3_000)
                        }
                    }
                }
            }
        }
        return ss to t
    }

    @Test
    fun httpClientShortTimeoutsAndNoExceptions() = runBlocking {
        val (ss, _) = server()
        try {
            val base = "http://127.0.0.1:${ss.localPort}"
            val http = HttpClient(connectTimeoutMs = 500, readTimeoutMs = 500)
            val ok = http.get("$base/ok")
            assertTrue(ok.ok)
            assertEquals("{\"a\":1}", ok.text())
            val t0 = System.currentTimeMillis()
            val slow = http.get("$base/slow")
            assertFalse(slow.ok)
            assertTrue("timeout took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 2_000)
            assertEquals(-1, http.get("http://127.0.0.1:1/none").code)
        } finally {
            ss.close()
        }
    }
}
