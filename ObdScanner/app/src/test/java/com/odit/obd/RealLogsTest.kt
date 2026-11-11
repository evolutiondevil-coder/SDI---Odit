package com.odit.obd

import com.odit.obd.elm.CommLog
import com.odit.obd.elm.Elm327
import com.odit.obd.elm.ObdProtocol
import com.odit.obd.elm.ResponseParser
import com.odit.obd.obd.AdapterCheck
import com.odit.obd.obd.KLineSupport
import com.odit.obd.obd.ObdSession
import com.odit.obd.obd.VehicleNotRespondingException
import com.odit.obd.sim.ReplayTransport
import com.odit.obd.uds.ModuleScanner
import com.odit.obd.vin.VinParser
import com.odit.obd.vin.VinReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Logs from real cars (src/test/resources/logs), replayed through the app's code. */
class RealLogsTest {

    private fun log(name: String): String =
        listOf(File("src/test/resources/logs/$name"), File("app/src/test/resources/logs/$name")).first { it.isFile }.readText()

    private fun replay(name: String): ObdSession {
        val l = CommLog()
        return ObdSession(Elm327(ReplayTransport(log(name)), l), l).also { it.timeScale = 0.01 }
    }

    /**
     * VW Golf IV (1J), MUCAR BT200 in its ELM327 mode over BLE. The engine answers standard OBD on
     * ISO 9141-2 (K-line), reports the warning lamp on with one stored code, and has no VIN in mode 09.
     */
    @Test
    fun golf4ConnectsOnIso9141() = runBlocking {
        val s = replay("vw-golf4-iso9141.txt")
        s.initAdapter()
        assertEquals("ELM327 v1.4b", s.adapterVersion)
        assertEquals(ObdProtocol.ISO_9141, s.connectVehicle(null) {})
        val r = s.readiness()!!
        assertTrue(r.milOn)
        assertEquals(1, r.dtcCount)
        assertNull(VinReader(s).read().vin)
    }

    /**
     * Same Golf, version 1.4: the 5-baud wake-up at 0x33 worked ("BUS INIT: ...OK" and nothing else),
     * but the check showed "(празно)" and could not say whether the K-line works.
     */
    @Test
    fun golf4AdapterCheckSeesTheWakeUp() = runBlocking {
        val s = replay("vw-golf4-adapter-check.txt")
        s.initAdapter()
        val r = AdapterCheck(s).run()
        assertEquals(KLineSupport.WORKS, r.kLine)
        assertEquals("BUS INIT OK", r.items.first { it.command == "ATSI" }.answer)
        assertTrue(r.verdict.first().startsWith("K-line работи"))
    }

    /**
     * Renault Scénic I: on ISO 9141-2 a unit answers the wake-up but never a standard OBD request;
     * nothing else answers. The app must say so (and point to the direct K-line access), not just
     * "the car does not answer".
     */
    @Test
    fun scenicWakesUpButIgnoresObd() = runBlocking {
        val s = replay("renault-scenic1-connect.txt")
        s.initAdapter()
        try {
            // The phone had last been used on the Golf (ISO 9141-2), as in the real session.
            s.connectVehicle(ObdProtocol.ISO_9141) {}
            fail("must not connect")
        } catch (_: VehicleNotRespondingException) {
        }
        val iso = s.attempts.first { it.protocol == ObdProtocol.ISO_9141 }
        assertTrue(iso.woke)
        assertEquals("BUS INIT OK, NO DATA", iso.answer)
        assertTrue(s.attempts.filter { it.protocol != ObdProtocol.ISO_9141 }.none { it.woke })
        // CAN is checked first now (a fraction of a second), before the slow K-line wake-up.
        assertEquals(ObdProtocol.CAN_11_500, s.attempts.first().protocol)
    }

    /**
     * Honda CR-V 2012 (29-bit CAN): the engine answers 19 02 FF with 79 codes, all with status 0x40
     * ("test not completed since the last clear"). None of them is a fault.
     */
    @Test
    fun hondaCodesWithTestNotCompletedAreNotFaults() {
        val lines = HONDA_1902FF.trimIndent().lines()
        val m = ResponseParser.parse(lines, ObdProtocol.CAN_29_500).single()
        assertTrue(m.complete)
        assertEquals(0x13F, m.data.size)
        assertEquals(listOf(0x59, 0x02, 0xCE), m.data.take(3))
        assertTrue(ModuleScanner.parseUds(m.data).isEmpty())
    }

    /** Honda puts a length byte (0x11) before the VIN in 22 F190 and pads with zeros. */
    @Test
    fun hondaVinFromF190() {
        val vin = "SHSRE6730CU000001" // real one replaced, same layout
        val payload = listOf(0x62, 0xF1, 0x90, 0x11) + vin.map { it.code } + listOf(0, 0, 0)
        val frames = mutableListOf(listOf(0x10, payload.size) + payload.take(6))
        var i = 6
        var seq = 1
        while (i < payload.size) {
            val part = payload.drop(i).take(7)
            frames += listOf(0x20 or (seq and 0x0F)) + part + List(7 - part.size) { 0x55 }
            i += 7
            seq++
        }
        val lines = frames.map { f -> "18DAF110 " + f.joinToString(" ") { "%02X".format(it) } }
        val m = ResponseParser.parse(lines, ObdProtocol.CAN_29_500).single()
        assertEquals(vin, VinParser.parseUdsF190(m.data))
    }

    private companion object {
        /** The second answer of the real log (20:13:46), byte for byte. */
        const val HONDA_1902FF = """
            18DAF110 11 3F 59 02 CE 01 03 01
            18DAF110 21 40 01 02 01 40 11 02
            18DAF110 22 01 40 11 03 01 40 06
            18DAF110 23 45 01 40 16 B1 01 40
            18DAF110 24 06 47 01 40 06 46 01
            18DAF110 25 40 14 01 01 40 05 04
            18DAF110 26 01 40 24 5A 01 40 24
            18DAF110 27 5C 01 40 12 98 01 40
            18DAF110 28 12 97 01 40 02 19 01
            18DAF110 29 40 12 6D 01 40 10 30
            18DAF110 2A 01 40 10 35 01 40 10
            18DAF110 2B 34 01 40 22 51 01 40
            18DAF110 2C 22 37 01 40 01 35 01
            18DAF110 2D 40 10 36 01 40 10 37
            18DAF110 2E 01 40 2A 00 01 40 06
            18DAF110 2F 4D 01 40 01 32 01 40
            18DAF110 20 01 31 01 40 00 53 01
            18DAF110 21 40 12 37 01 40 12 36
            18DAF110 22 01 40 00 87 01 40 00
            18DAF110 23 88 01 40 10 65 01 40
            18DAF110 24 05 45 01 40 25 52 01
            18DAF110 25 40 00 AF 01 40 10 27
            18DAF110 26 01 40 00 48 01 40 05
            18DAF110 27 02 01 40 21 46 01 40
            18DAF110 28 06 2B 01 40 02 01 01
            18DAF110 29 40 02 03 01 40 02 04
            18DAF110 2A 01 40 02 02 01 40 10
            18DAF110 2B 41 01 40 10 43 01 40
            18DAF110 2C 10 44 01 40 10 42 01
            18DAF110 2D 40 06 1B 01 40 21 76
            18DAF110 2E 01 40 10 4A 01 40 10
            18DAF110 2F 4C 01 40 10 4D 01 40
            18DAF110 20 10 4B 01 40 06 0B 01
            18DAF110 21 40 01 81 01 40 22 8F
            18DAF110 22 01 40 22 8E 01 40 21
            18DAF110 23 99 01 40 01 26 01 40
            18DAF110 24 01 16 01 40 06 2F 01
            18DAF110 25 40 03 44 01 40 03 40
            18DAF110 26 01 40 03 39 01 40 03
            18DAF110 27 35 01 40 03 01 01 40
            18DAF110 28 03 03 01 40 03 04 01
            18DAF110 29 40 03 02 01 40 03 00
            18DAF110 2A 01 40 06 01 01 40 10
            18DAF110 2B 8F 01 40 10 8E 01 40
            18DAF110 2C 26 10 01 40 21 11 01
            18DAF110 2D 40 21 12 01 40 55 55
        """
    }
}
