package com.odit.obd

import com.odit.obd.elm.CommLog
import com.odit.obd.elm.Elm327
import com.odit.obd.elm.ObdProtocol
import com.odit.obd.elm.ResponseParser
import com.odit.obd.obd.ObdSession
import com.odit.obd.obd.VehicleNotRespondingException
import com.odit.obd.sim.DemoScenario
import com.odit.obd.sim.SimulatedElm
import com.odit.obd.ui.clearedText
import com.odit.obd.uds.DiagType
import com.odit.obd.uds.KLineDirect
import com.odit.obd.uds.KLineTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A car from before OBD2: no answer to standard requests, units reachable one by one on the K-line. */
class OldCarTest {

    private val log = CommLog()

    /** The simulated adapter behaves like the MUCAR clone of the real logs; time runs 5x faster. */
    private fun session(): ObdSession = runBlocking {
        val s = ObdSession(Elm327(SimulatedElm(DemoScenario.OLD_RENAULT, cloneQuirks = true, timeScale = 0.2), log), log)
        s.timeScale = 0.2
        s.initAdapter()
        s
    }

    // 0x7A is reached like the Scénic I engine: wake-up at 0x33 on ISO 9141 ("ISO8"), then 81 7A F1.
    private val targets = listOf(KLineTarget(0x7A, null, iso8 = true)) + listOf(0x01, 0x2C, 0x26).map { KLineTarget(it, null) }

    @Test
    fun standardObdFailsAndSaysWhy() = runBlocking {
        val s = session()
        try {
            s.connectVehicle(null) {}
            fail("must not connect")
        } catch (_: VehicleNotRespondingException) {
        }
        assertFalse(s.vehicleConnected)
        val kwp = s.attempts.first { it.protocol == ObdProtocol.KWP_FAST }
        assertEquals("BUS INIT ERROR", kwp.answer)
        // The engine wakes at 0x33 on ISO 9141 but ignores OBD requests, like the real Scénic.
        val iso = s.attempts.first { it.protocol == ObdProtocol.ISO_9141 }
        assertTrue(iso.woke)
        assertEquals("BUS INIT OK, NO DATA", iso.answer)
        assertTrue(s.attempts.map { it.protocol }.containsAll(listOf(ObdProtocol.AUTO, ObdProtocol.CAN_11_500, ObdProtocol.ISO_9141)))
    }

    @Test
    fun directKLineFindsUnitsReadsAndClears() = runBlocking {
        val s = session()
        val direct = KLineDirect(s)
        val found = direct.scan(targets, { _, _, _ -> }, {})
        assertEquals(listOf(0x7A, 0x2C, 0x26), found.map { it.tx })

        val engine = found[0]
        assertEquals(DiagType.KLINE, engine.type)
        assertEquals("K-line 0x7A", engine.addressLabel)
        assertEquals("VF1JA0B0521234567", engine.vin)
        assertTrue(engine.identification!!.contains("SIRIUS32"))
        // Manufacturer numbering: shown as raw bytes, never as a standard P-code with a made-up meaning.
        assertEquals(listOf("DTC 0120", "DTC 0335"), engine.dtcs.map { it.displayCode })
        assertTrue(engine.dtcs.all { it.raw })
        assertTrue(engine.dtcs[0].description.startsWith("Код на производителя"))
        assertNull(engine.dtcs[0].descriptionSource)
        assertEquals(1, found[1].dtcs.size)
        assertTrue(found[2].dtcs.isEmpty())

        // Clearing goes the way that worked (ISO8) straight away.
        val before = log.entries().size
        val (result, again) = direct.clear(listOf(engine)).single()
        assertTrue(result.message, result.ok)
        assertTrue(again!!.dtcs.isEmpty())
        val sent = log.entries().drop(before).filter { it.dir == CommLog.Dir.TX }.map { it.text }
        assertTrue(sent.none { it == "ATSP5" })
        assertTrue(sent.contains("ATSP3"))
        assertTrue(sent.contains("14FF00"))
    }

    @Test
    fun recordLengthIsWorkedOutFromTheAnswer() {
        // 3-byte records
        assertEquals(listOf("0120", "0335"), KLineDirect.parseRecords(intArrayOf(0x57, 2, 0x01, 0x20, 0xA1, 0x03, 0x35, 0x21), true).map { it.code })
        // 2-byte records (no status)
        assertEquals(listOf("0120", "0335"), KLineDirect.parseRecords(intArrayOf(0x57, 2, 0x01, 0x20, 0x03, 0x35), true).map { it.code })
        // 4-byte records
        assertEquals(listOf("0120", "0335"), KLineDirect.parseRecords(intArrayOf(0x57, 2, 0x01, 0x20, 0xA1, 0x00, 0x03, 0x35, 0x21, 0x00), true).map { it.code })
        // Standard KWP2000 answer keeps the SAE reading.
        assertEquals(listOf("P0120"), KLineDirect.parseRecords(intArrayOf(0x58, 1, 0x01, 0x20, 0xE0), false).map { it.code })
        assertTrue(KLineDirect.parseRecords(intArrayOf(0x57, 0), true).isEmpty())
    }

    @Test
    fun clearMessageIsSingularOrPlural() {
        assertEquals("Грешката е изтрита.", clearedText(1))
        assertEquals("Грешките са изтрити (3).", clearedText(3))
        assertEquals("Няма грешки за изтриване.", clearedText(0))
    }

    @Test
    fun kwpFrameWithSeparateLengthByte() {
        // 80 F1 7A <len> <data...> <checksum>
        val line = "80 F1 7A 03 61 80 41 99"
        val m = ResponseParser.parse(listOf(line), ObdProtocol.KWP_FAST).single()
        assertEquals("7A", m.header)
        assertEquals(listOf(0x61, 0x80, 0x41), m.data.toList())
        // Usual form with the length in the first byte.
        val short = ResponseParser.parse(listOf("82 F1 7A 50 C0 FD"), ObdProtocol.KWP_FAST).single()
        assertEquals(listOf(0x50, 0xC0), short.data.toList())
    }
}
