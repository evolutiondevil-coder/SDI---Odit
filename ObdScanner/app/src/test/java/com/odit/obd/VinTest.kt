package com.odit.obd

import com.odit.obd.elm.CommLog
import com.odit.obd.elm.Elm327
import com.odit.obd.elm.ObdProtocol
import com.odit.obd.obd.Brand
import com.odit.obd.obd.ObdSession
import com.odit.obd.sim.DemoScenario
import com.odit.obd.sim.ReplayTransport
import com.odit.obd.sim.SimulatedElm
import com.odit.obd.uds.ModuleScanner
import com.odit.obd.vin.VinMethod
import com.odit.obd.vin.VinParser
import com.odit.obd.vin.VinReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * VIN parsing. The raw answers below follow the ELM327 output formats (headers on/off, spaces
 * on/off, numbered lines) for the protocols OBD-II uses. Logs exported from real cars go into
 * src/test/resources/logs/ and are checked by [logsFromCarsReplay].
 */
class VinTest {

    private val vin = "WVWZZZ1KZ8W123456"
    private fun lines(raw: String) = Elm327.clean(raw, "0902")

    @Test
    fun formatAndCheckDigit() {
        assertTrue(VinParser.isValidFormat(vin))
        assertFalse(VinParser.isValidFormat("WVWZZZ1KZ8W12345"))     // 16 chars
        assertFalse(VinParser.isValidFormat("WVWZZZ1KZ8W12345O"))    // letter O
        assertFalse(VinParser.isValidFormat("IVWZZZ1KZ8W123456"))    // letter I
        assertTrue(VinParser.checkDigitOk("1M8GDM9AXKP042788"))      // worked example on Wikipedia
        assertFalse(VinParser.checkDigitOk("1M8GDM9A1KP042788"))
        assertFalse(VinParser.checkDigitOk(vin))                     // European VIN: no check digit, still valid
    }

    @Test
    fun can11HeadersOnWithAndWithoutSpaces() {
        val spaced = "0902\r7E8 10 14 49 02 01 57 56 57\r7E8 21 5A 5A 5A 31 4B 5A 38\r7E8 22 57 31 32 33 34 35 36\r\r>"
        val r1 = VinParser.parse0902(lines(spaced), ObdProtocol.CAN_11_500)
        assertEquals(listOf(vin), r1.map { it.vin })
        assertEquals("7E8", r1[0].ecu)
        val compact = "7E81014490201575657\r7E8215A5A5A314B5A38\r7E82257313233343536\r\r>"
        assertEquals(vin, VinParser.parse0902(lines(compact), ObdProtocol.CAN_11_500).single().vin)
    }

    @Test
    fun twoEcusInterleaved() {
        val raw = "7E8 10 14 49 02 01 57 56 57\r7E9 10 14 49 02 01 57 56 57\r" +
            "7E8 21 5A 5A 5A 31 4B 5A 38\r7E9 21 5A 5A 5A 31 4B 5A 38\r" +
            "7E8 22 57 31 32 33 34 35 36\r7E9 22 57 31 32 33 34 35 37\r\r>"
        val r = VinParser.parse0902(lines(raw), ObdProtocol.CAN_11_500)
        assertEquals(listOf("7E8" to vin, "7E9" to "WVWZZZ1KZ8W123457"), r.map { it.ecu to it.vin })
    }

    @Test
    fun can29Bit() {
        val raw = "18 DA F1 10 10 14 49 02 01 57 56 57\r18 DA F1 10 21 5A 5A 5A 31 4B 5A 38\r18 DA F1 10 22 57 31 32 33 34 35 36\r\r>"
        val r = VinParser.parse0902(lines(raw), ObdProtocol.CAN_29_500).single()
        assertEquals(vin, r.vin)
        assertEquals("18DAF110", r.ecu)
    }

    @Test
    fun headersOffNumberedLines() {
        val raw = "014\r0: 49 02 01 57 56 57\r1: 5A 5A 5A 31 4B 5A 38\r2: 57 31 32 33 34 35 36\r\r>"
        assertEquals(vin, VinParser.parse0902(lines(raw), ObdProtocol.CAN_11_500).single().vin)
        val compact = "014\r0:490201575657\r1:5A5A5A314B5A38\r2:57313233343536\r\r>"
        assertEquals(vin, VinParser.parse0902(lines(compact), ObdProtocol.CAN_11_500).single().vin)
        // Clone without the byte-count line.
        val noCount = "0: 49 02 01 57 56 57\r1: 5A 5A 5A 31 4B 5A 38\r2: 57 31 32 33 34 35 36\r\r>"
        assertEquals(vin, VinParser.parse0902(lines(noCount), ObdProtocol.CAN_11_500).single().vin)
    }

    @Test
    fun headersOffTwoEcus() {
        val raw = "014\r0: 49 02 01 57 56 57\r1: 5A 5A 5A 31 4B 5A 38\r2: 57 31 32 33 34 35 36\r" +
            "014\r0: 49 02 01 57 56 57\r1: 5A 5A 5A 31 4B 5A 38\r2: 57 31 32 33 34 35 37\r\r>"
        val r = VinParser.parse0902(lines(raw), ObdProtocol.CAN_11_500)
        assertEquals(listOf(vin, "WVWZZZ1KZ8W123457"), r.map { it.vin })
    }

    @Test
    fun kLineFiveMessages() {
        // ISO 9141 / KWP with headers: 3 header bytes + 4 data bytes + checksum, VIN split over 5 messages.
        val raw = "BUS INIT: ...OK\r" +
            "48 6B 10 49 02 01 00 00 00 57 AA\r48 6B 10 49 02 02 56 57 5A 5A AA\r" +
            "48 6B 10 49 02 03 5A 31 4B 5A AA\r48 6B 10 49 02 04 38 57 31 32 AA\r" +
            "48 6B 10 49 02 05 33 34 35 36 AA\r\r>"
        val r = VinParser.parse0902(lines(raw), ObdProtocol.KWP_FAST).single()
        assertEquals(vin, r.vin)
        assertEquals("10", r.ecu)
        // Same without headers, out of order.
        val off = "49 02 02 56 57 5A 5A\r49 02 01 00 00 00 57\r49 02 03 5A 31 4B 5A\r49 02 04 38 57 31 32\r49 02 05 33 34 35 36\r\r>"
        assertEquals(vin, VinParser.parse0902(lines(off), ObdProtocol.ISO_9141).single().vin)
    }

    @Test
    fun paddingAndBadAnswers() {
        // Padded with zeros before and 0xFF after.
        val padded = listOf(0x01, 0x00, 0x00) + vin.map { it.code } + listOf(0xFF, 0xFF)
        assertEquals(vin, VinParser.vinFromBytes(padded))
        // Lower case letters are accepted.
        assertEquals(vin, VinParser.vinFromBytes(vin.lowercase().map { it.code }))
        // Cut-off multi-frame answer: no VIN instead of a wrong one.
        val cut = "7E8 10 14 49 02 01 57 56 57\r7E8 21 5A 5A 5A 31 4B 5A 38\r\r>"
        assertTrue(VinParser.parse0902(lines(cut), ObdProtocol.CAN_11_500).isEmpty())
        assertTrue(VinParser.parse0902(lines("NO DATA\r\r>"), ObdProtocol.CAN_11_500).isEmpty())
        // Letter O is not allowed.
        assertNull(VinParser.vinFromBytes("WVWZZZ1KZ8W12345O".map { it.code }))
    }

    @Test
    fun udsAndKwpAnswers() {
        assertEquals(vin, VinParser.parseUdsF190(intArrayOf(0x62, 0xF1, 0x90) + vin.map { it.code }.toIntArray()))
        assertEquals(vin, VinParser.parseKwp1A90(intArrayOf(0x5A, 0x90) + vin.map { it.code }.toIntArray() + intArrayOf(0x20)))
        assertNull(VinParser.parseUdsF190(intArrayOf(0x7F, 0x22, 0x31)))
    }

    // ---------------------------------------------------------------- demo cars, full chain

    private fun session(scenario: DemoScenario, log: CommLog = CommLog()): ObdSession = runBlocking {
        val s = ObdSession(Elm327(SimulatedElm(scenario), log), log)
        s.initAdapter()
        s.connectVehicle(null) {}
        s
    }

    @Test
    fun chainObd() = runBlocking {
        val r = VinReader(session(DemoScenario.NORMAL)).read()
        assertEquals(vin, r.vin)
        assertEquals(VinMethod.OBD_0902, r.found.first().method)
    }

    @Test
    fun chainUdsWhenNo0902() = runBlocking {
        val s = session(DemoScenario.VIN_UDS_ENGINE)
        val r = VinReader(s).read()
        assertEquals(vin, r.vin)
        assertEquals(VinMethod.UDS_F190, r.found.single().method)
        assertEquals("7E8", r.found.single().ecu)
        assertEquals(3, s.readDtcs(0x03).size) // normal OBD still works afterwards
    }

    @Test
    fun chainVinOnlyInOtherModule() = runBlocking {
        val s = session(DemoScenario.VIN_OTHER_MODULE)
        assertNull(VinReader(s).read().vin)
        val modules = ModuleScanner(s).scan(false, Brand.OTHER, { _, _, _ -> }, {})
        val r = VinReader(s).read(modules)
        assertEquals(vin, r.vin)
        assertEquals("77E", r.found.first().ecu)
    }

    @Test
    fun chainConflict() = runBlocking {
        val s = session(DemoScenario.VIN_CONFLICT)
        val first = VinReader(s).read()
        assertFalse(first.conflict)
        val modules = ModuleScanner(s).scan(false, Brand.VAG, { _, _, _ -> }, {})
        val r = VinReader.withModules(first, modules)
        assertTrue(r.conflict)
        assertEquals(vin, r.vin) // the engine's OBD answer wins
        assertEquals(setOf(vin, "WVWZZZ1KZ6W654321"), r.distinct.toSet())
        assertEquals("77D", r.found.first { it.vin != vin }.ecu)
    }

    @Test
    fun chainKLine() = runBlocking {
        val s = session(DemoScenario.KLINE)
        assertEquals(ObdProtocol.KWP_FAST, s.protocol)
        val r = VinReader(s).read()
        assertEquals("VF1BB0A0526123456", r.vin)
        assertEquals(VinMethod.KWP_1A90, r.found.single().method)
        assertEquals(3, s.readDtcs(0x03).size) // header restored for OBD
    }

    @Test
    fun legacy0902MultiMessage() = runBlocking {
        val log = CommLog()
        val s = ObdSession(Elm327(SimulatedElm('3'), log), log)
        s.initAdapter()
        s.connectVehicle(null) {}
        assertEquals(vin, VinReader(s).read().vin)
    }

    // ----------------------------------------------------------------- export / replay

    @Test
    fun exportedLogReplays() = runBlocking {
        val log = CommLog()
        val s = session(DemoScenario.VIN_UDS_ENGINE, log)
        VinReader(s).read()
        val text = log.export(mapOf("expect-vin" to vin))
        assertTrue(text.contains("> 22F190"))
        val replay = ReplayTransport(text)
        assertEquals(vin, replay.header["expect-vin"])
        val log2 = CommLog()
        val s2 = ObdSession(Elm327(replay, log2), log2)
        s2.initAdapter()
        s2.connectVehicle(null) {}
        assertEquals(vin, VinReader(s2).read().vin)
    }

    @Test
    fun escapeRoundTrip() {
        val raw = "7E8 10 14\r\n\u0000x\\y>"
        assertEquals(raw, CommLog.unescape(CommLog.escape(raw)))
    }

    /** Every log in src/test/resources/logs with an "expect-vin" header must give that VIN. */
    @Test
    fun logsFromCarsReplay() = runBlocking {
        val dir = listOf(File("src/test/resources/logs"), File("app/src/test/resources/logs")).firstOrNull { it.isDirectory }
            ?: return@runBlocking
        for (f in dir.listFiles().orEmpty().filter { it.name.endsWith(".txt") }.sorted()) {
            val replay = ReplayTransport(f.readText())
            val expected = replay.header["expect-vin"] ?: continue
            val log = CommLog()
            val s = ObdSession(Elm327(replay, log), log)
            s.initAdapter()
            s.connectVehicle(null) {}
            assertEquals("log ${f.name}", expected, VinReader(s).read().vin)
        }
    }
}
