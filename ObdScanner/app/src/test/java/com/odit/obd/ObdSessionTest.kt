package com.odit.obd

import com.odit.obd.elm.CommLog
import com.odit.obd.elm.Elm327
import com.odit.obd.elm.ObdProtocol
import com.odit.obd.elm.ResponseParser
import com.odit.obd.obd.Brand
import com.odit.obd.obd.Dtc
import com.odit.obd.obd.DtcDatabase
import com.odit.obd.obd.DtcKind
import com.odit.obd.obd.ObdSession
import com.odit.obd.obd.Pids
import com.odit.obd.obd.Readiness
import com.odit.obd.obd.VinDecoder
import com.odit.obd.sim.SimulatedElm
import com.odit.obd.uds.DiagType
import com.odit.obd.uds.ModuleScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdSessionTest {

    private fun session(protocol: Char = '6'): Pair<ObdSession, SimulatedElm> {
        val sim = SimulatedElm(protocol)
        val log = CommLog()
        return ObdSession(Elm327(sim, log), log) to sim
    }

    private fun connected(protocol: Char = '6'): Pair<ObdSession, SimulatedElm> = runBlocking {
        val (s, sim) = session(protocol)
        s.initAdapter()
        s.connectVehicle(null) {}
        s to sim
    }

    @Test
    fun dtcEncodingRoundTrip() {
        for (code in listOf("P0301", "C0035", "B1001", "U0121", "P2BAD", "P0000")) {
            val (a, b) = Dtc.encode(code)
            assertEquals(code, Dtc.decode(a, b))
        }
        assertEquals("P0133", Dtc.decode(0x01, 0x33))
        assertEquals("U0100", Dtc.decode(0xC1, 0x00))
    }

    @Test
    fun cleanRemovesEchoPromptAndSearching() {
        val raw = "0100\rSEARCHING...\r7E8 06 41 00 BE 3F A8 13\r\r>"
        assertEquals(listOf("7E8 06 41 00 BE 3F A8 13"), Elm327.clean(raw, "0100"))
        val kline = "BUS INIT: ...OK\r48 6B 10 41 00 BE 3F A8 13 C4\r\r>"
        assertEquals(listOf("48 6B 10 41 00 BE 3F A8 13 C4"), Elm327.clean(kline, "0100"))
    }

    @Test
    fun parsesMultiFrameCanPerEcu() {
        val lines = listOf(
            "7E8 10 14 49 02 01 57 56 57",
            "7E9 03 7F 09 11 AA AA AA AA",
            "7E8 21 5A 5A 5A 31 4B 5A 38",
            "7E8 22 57 31 32 33 34 35 36",
        )
        val msgs = ResponseParser.parse(lines, ObdProtocol.CAN_11_500)
        val vinMsg = msgs.first { it.header == "7E8" }
        assertTrue(vinMsg.complete)
        assertEquals(20, vinMsg.data.size)
        assertEquals("WVWZZZ1KZ8W123456", vinMsg.data.drop(3).joinToString("") { it.toChar().toString() })
        val neg = msgs.first { it.header == "7E9" }
        assertEquals(listOf(0x7F, 0x09, 0x11), neg.data.toList())
    }

    @Test
    fun parsesWithoutSpacesAnd29Bit() {
        val msgs = ResponseParser.parse(listOf("18DAF110" + "0641000000000000"), ObdProtocol.CAN_29_500)
        assertEquals(1, msgs.size)
        assertEquals("18DAF110", msgs[0].header)
        assertEquals(listOf(0x41, 0x00, 0x00, 0x00, 0x00, 0x00), msgs[0].data.toList())
    }

    @Test
    fun connectsAndDetectsCan() {
        val (s, _) = connected()
        assertEquals(ObdProtocol.CAN_11_500, s.protocol)
        assertTrue(s.vehicleConnected)
        assertTrue(s.adapterVersion.contains("ELM327"))
    }

    @Test
    fun preferredProtocolFallsBackToAuto() = runBlocking {
        val (s, _) = session()
        s.initAdapter()
        val p = s.connectVehicle(ObdProtocol.KWP_FAST) {}
        assertEquals(ObdProtocol.CAN_11_500, p)
    }

    @Test
    fun readsStoredPendingPermanentFromAllEcus() = runBlocking {
        val (s, _) = connected()
        val stored = s.readDtcs(0x03)
        assertEquals(
            setOf("7E8" to "P0301", "7E8" to "P0171", "7E9" to "P0741"),
            stored.map { it.ecu to it.dtc.code }.toSet(),
        )
        assertEquals(listOf("P0420"), s.readDtcs(0x07).map { it.dtc.code })
        val perm = s.readDtcs(0x0A)
        assertEquals(listOf("P0301"), perm.map { it.dtc.code })
        assertEquals(DtcKind.PERMANENT, perm[0].dtc.kind)
    }

    @Test
    fun clearsCodes() = runBlocking {
        val (s, _) = connected()
        assertTrue(s.clearDtcs().ok)
        assertTrue(s.readDtcs(0x03).isEmpty())
        val r = s.readiness()!!
        assertFalse(r.milOn)
        assertEquals(0, r.dtcCount)
    }

    @Test
    fun legacyKLineDtcs() = runBlocking {
        val (s, _) = connected('3')
        assertEquals(ObdProtocol.ISO_9141, s.protocol)
        val stored = s.readDtcs(0x03)
        assertEquals(setOf("P0301", "P0171", "P0741"), stored.map { it.dtc.code }.toSet())
        assertEquals(setOf("10", "18"), stored.map { it.ecu }.toSet())
    }

    @Test
    fun readsVinAndIdentity() = runBlocking {
        val (s, sim) = connected()
        assertEquals(sim.vin, s.readVin())
        val info = VinDecoder.decode(sim.vin)
        assertEquals("Volkswagen", info.make)
        assertEquals(2008, info.year)
        assertEquals(Brand.VAG, info.brand)
        val ids = s.identities()
        assertEquals("ECM-EngineControl", ids.first { it.ecu == "7E8" }.name)
    }

    @Test
    fun liveDataAndReadiness() = runBlocking {
        val (s, _) = connected()
        val supported = s.supportedPids()
        assertTrue(0x0C in supported && 0x42 in supported && 0x5C in supported)
        val rpm = s.readPid(0x0C)!!
        assertTrue(rpm in 700.0..900.0)
        assertEquals(88.0, s.readPid(0x05)!!, 0.01)
        assertEquals(14.19, s.readPid(0x42)!!, 0.01)
        val r = s.readiness()!!
        assertTrue(r.milOn)
        assertEquals(2, r.dtcCount)
        assertFalse(r.diesel)
        assertTrue(r.monitors.any { it.name.contains("Катализатор") })
        assertEquals(listOf("Изпарения (EVAP)"), r.incomplete.map { it.name })
        assertNotNull(s.batteryVoltage())
    }

    @Test
    fun freezeFrame() = runBlocking {
        val (s, _) = connected()
        val ff = s.freezeFrame()!!
        assertEquals("P0301", ff.dtc)
        assertTrue(ff.values.any { it.first.pid == 0x0C && it.second == "1000 об/мин" })
    }

    @Test
    fun moduleScanFindsAllModules() = runBlocking {
        val (s, _) = connected()
        s.readVin()
        val found = ModuleScanner(s).scan(full = false, brand = Brand.VAG, onProgress = { _, _, _ -> }, onFound = {})
        val byTx = found.associateBy { it.tx }
        assertEquals(setOf(0x7E0, 0x7E1, 0x713, 0x715, 0x714, 0x70E, 0x710), byTx.keys)
        assertEquals("ABS / ESP (спирачки)", byTx[0x713]!!.title)
        assertEquals("C0035-29", byTx[0x713]!!.dtcs.single().displayCode)
        assertEquals("невалиден сигнал", byTx[0x713]!!.dtcs.single().ftbDescription)
        // U0155 has only "not completed" bits and must not be reported.
        assertEquals(listOf("U0121"), byTx[0x714]!!.dtcs.map { it.code })
        assertEquals(DtcKind.HISTORY, byTx[0x714]!!.dtcs.single().kind)
        assertEquals(DiagType.KWP, byTx[0x710]!!.type)
        assertEquals("ESP MK100", byTx[0x713]!!.identification)
        assertTrue(byTx[0x70E]!!.dtcs.isEmpty())
        // Back to normal OBD afterwards.
        assertEquals(3, s.readDtcs(0x03).size)
    }

    @Test
    fun moduleClearUsesExtendedSessionWhenNeeded() = runBlocking {
        val (s, _) = connected()
        val scanner = ModuleScanner(s)
        val found = scanner.scan(false, Brand.VAG, { _, _, _ -> }, {})
        val airbag = found.first { it.tx == 0x715 }
        val (result, again) = scanner.clear(listOf(airbag), Brand.VAG).single()
        assertTrue(result.message, result.ok)
        assertTrue(again!!.dtcs.isEmpty())
    }

    @Test
    fun fullScanIgnoresEmptyAddresses() = runBlocking {
        val (s, _) = connected()
        val found = ModuleScanner(s).scan(full = true, brand = Brand.OTHER, onProgress = { _, _, _ -> }, onFound = {})
        assertEquals(7, found.size)
        assertNull(found.first { it.tx == 0x713 }.catalogName)
    }

    @Test
    fun descriptions() {
        assertEquals("Прекъсване (misfire) в цилиндър 1", DtcDatabase.describe("P0301"))
        assertTrue(DtcDatabase.describe("P0135").contains("банк 1, сонда 1"))
        assertTrue(DtcDatabase.describe("P1234").startsWith("Код на производителя"))
        assertTrue(DtcDatabase.describe("U0121").contains("ABS"))
        assertEquals("1000 об/мин", Pids.all.getValue(0x0C).formatRaw(intArrayOf(0x0F, 0xA0)))
    }

    @Test
    fun readinessDiesel() {
        val r = Readiness.decode(0x00, 0x0F, 0x48, 0x40)
        assertTrue(r.diesel)
        assertEquals(listOf("Филтър за твърди частици (DPF)"), r.incomplete.map { it.name })
    }
}
