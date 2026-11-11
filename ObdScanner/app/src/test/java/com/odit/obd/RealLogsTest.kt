package com.odit.obd

import com.odit.obd.elm.CommLog
import com.odit.obd.elm.Elm327
import com.odit.obd.elm.ObdProtocol
import com.odit.obd.obd.ObdSession
import com.odit.obd.sim.DemoScenario
import com.odit.obd.sim.ReplayTransport
import com.odit.obd.sim.SimulatedElm
import com.odit.obd.uds.KCodes
import com.odit.obd.uds.KInit
import com.odit.obd.uds.KLineDirect
import com.odit.obd.uds.KLineTarget
import com.odit.obd.vin.VinReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Logs from real cars (src/test/resources/logs), replayed through the app's code. */
class RealLogsTest {

    private fun log(name: String): String =
        listOf(File("src/test/resources/logs/$name"), File("app/src/test/resources/logs/$name")).first { it.isFile }.readText()

    /**
     * VW Golf IV (1J), MUCAR BT200 in its ELM327 mode over BLE. The engine answers standard OBD on
     * ISO 9141-2 (K-line), reports the warning lamp on with one stored code, and has no VIN in mode 09.
     */
    @Test
    fun golf4ConnectsOnIso9141() = runBlocking {
        val l = CommLog()
        val s = ObdSession(Elm327(ReplayTransport(log("vw-golf4-iso9141.txt")), l), l)
        s.initAdapter()
        assertEquals("ELM327 v1.4b", s.adapterVersion)
        assertEquals(ObdProtocol.ISO_9141, s.connectVehicle(null) {})
        val r = s.readiness()!!
        assertTrue(r.milOn)
        assertEquals(1, r.dtcCount)
        assertNull(VinReader(s).read().vin)
    }

    /** The demo VW also has a radio that only wakes at 9600 baud: it is found on the second try. */
    @Test
    fun vwUnitAt9600BaudIsFound() = runBlocking {
        val l = CommLog()
        val s = ObdSession(Elm327(SimulatedElm(DemoScenario.OLD_VW), l), l)
        s.initAdapter()
        val radio = KLineTarget(0x56, "56 Радио", KInit.SLOW, KCodes.VAG)
        val found = KLineDirect(s).scan(listOf(radio), { _, _, _ -> }, {})
        assertEquals(listOf(0x56), found.map { it.tx })
        assertEquals(KLineDirect.KW1281_TEXT, found.single().readError)
        val sent = l.entries().filter { it.dir == CommLog.Dir.TX }.map { it.text }
        assertTrue(sent.contains("ATIB96"))
        // Back to the standard speed afterwards.
        assertEquals("ATIB10", sent.last { it.startsWith("ATIB") })
    }
}
