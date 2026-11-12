package com.odit.obd

import com.odit.obd.elm.CommLog
import com.odit.obd.elm.Elm327
import com.odit.obd.obd.AdapterCheck
import com.odit.obd.obd.Dtc
import com.odit.obd.obd.KLineSupport
import com.odit.obd.obd.ObdSession
import com.odit.obd.sim.DemoScenario
import com.odit.obd.sim.ReplayTransport
import com.odit.obd.sim.SimulatedElm
import com.odit.obd.uds.KCodes
import com.odit.obd.uds.KInit
import com.odit.obd.uds.KLineDirect
import com.odit.obd.uds.KLineTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** VW group car from before OBD2, and the adapter check. */
class OldVwTest {

    private val log = CommLog()

    /** Old cars run on a simulated MUCAR clone (drops the link after settings commands), 5x faster. */
    private fun session(scenario: DemoScenario): ObdSession = runBlocking {
        val old = scenario == DemoScenario.OLD_VW || scenario == DemoScenario.OLD_RENAULT || scenario == DemoScenario.KLINE
        val scale = if (old) 0.2 else 1.0
        val s = ObdSession(Elm327(SimulatedElm(scenario, cloneQuirks = old, timeScale = scale), log), log)
        s.timeScale = scale
        s.initAdapter()
        s
    }

    private fun vag(addr: Int) = KLineTarget(addr, "%02X".format(addr), KInit.SLOW, KCodes.VAG)

    @Test
    fun vagNumbersMapToSaeWhereTheyCan() {
        // Pairs from VW workshop tables / Ross-Tech: 16684 = P0300, 17978 = P1570.
        val misfire = Dtc.fromVag(16684)
        assertEquals("P0300", misfire.code)
        assertEquals("VAG 16684 / P0300", misfire.displayCode)
        assertEquals("16684 P0300", misfire.searchTerm)
        assertTrue(misfire.description.contains("misfire"))
        assertEquals("P1570", Dtc.fromVag(17978).code)
        val other = Dtc.fromVag(18010 + 1000)
        assertTrue(other.raw)
        assertEquals("VAG 19010", other.displayCode)
        assertTrue(other.description.startsWith("Код на VW групата"))
    }

    @Test
    fun keyWordsAreParsed() {
        assertEquals(listOf(0x01, 0x8A), KLineDirect.keyWords(listOf("1:01 2:8A")))
        assertEquals(listOf(0xEF, 0x8F), KLineDirect.keyWords(listOf("1:EF  2:8F")))
        // The MUCAR adapter prints them without leading zeros (real logs).
        assertEquals(listOf(0x08, 0x08), KLineDirect.keyWords(listOf("1:8 2:8")))
        assertEquals(listOf(0x6B, 0x8F), KLineDirect.keyWords(listOf("1:6B 2:8F")))
        assertNull(KLineDirect.keyWords(listOf("?")))
    }

    @Test
    fun kwpUnitIsReadAndKw1281UnitsAreRecognised() = runBlocking {
        val s = session(DemoScenario.OLD_VW)
        val direct = KLineDirect(s)
        val found = direct.scan(listOf(vag(0x01), vag(0x03), vag(0x17), vag(0x25), vag(0x56)), { _, _, _ -> }, {})
        // 0x03 does not answer at all.
        assertEquals(listOf(0x01, 0x17, 0x25, 0x56), found.map { it.tx })

        val engine = found[0]
        assertNull(engine.readError)
        assertTrue(engine.identification!!.contains("MARELLI"))
        assertEquals(listOf("VAG 16684 / P0300", "VAG 17978 / P1570", "VAG 18010 / P1602"), engine.dtcs.map { it.displayCode })

        // The cluster of the real Golf IV log (KWP2000, key bytes 6B 8F) is read in full, although the
        // adapter drops the link after any settings command.
        val cluster = found[1]
        assertNull(cluster.readError)
        assertTrue(cluster.identification!!.contains("KOMBI"))
        assertEquals(listOf("VAG 18010 / P1602"), cluster.dtcs.map { it.displayCode })

        // Two KW1281 units in a row: the second is recognised although AT KW still shows 01 8A.
        for (old in found.drop(2)) {
            assertTrue(old.dtcs.isEmpty())
            assertEquals(KLineDirect.KW1281_TEXT, old.readError)
        }

        val results = direct.clear(found)
        assertTrue(results[0].first.message, results[0].first.ok)
        assertTrue(results[0].second!!.dtcs.isEmpty())
        assertTrue(results[1].first.message, results[1].first.ok)
        assertTrue(results[1].second!!.dtcs.isEmpty())
        // KW1281 units cannot be cleared from here, and the app says why.
        assertEquals(KLineDirect.KW1281_TEXT, results[2].first.message)
        // The adapter keeps its link when "AT SP 4" is sent on protocol 4 (real Golf IV log: every unit
        // after the cluster was asked over the cluster's link). The link is closed by switching protocols,
        // so no full reset is needed.
        assertTrue(log.entries().none { it.dir == CommLog.Dir.NOTE && it.text.contains("пълно нулиране") })
        // The same unit is never woken twice within its 5 s session: the app waits instead.
        assertTrue(log.entries().any { it.dir == CommLog.Dir.NOTE && it.text.contains("да затвори предишната връзка") })
    }

    /**
     * Real Golf IV log: after the K-line scan the app sent AT D, which on this adapter keeps the last
     * unit's header, so the engine never answered a standard OBD request again. AT Z resets it.
     */
    @Test
    fun obdStillWorksAfterKLineScanOnTheClone() = runBlocking {
        val s = session(DemoScenario.KLINE)
        s.connectVehicle(null) {}
        assertTrue(s.readDtcs(0x03).isNotEmpty())
        KLineDirect(s).scan(listOf(vag(0x56)), { _, _, _ -> }, {})
        val codes = s.readDtcs(0x03).map { it.dtc.code }
        assertTrue(codes.toString(), codes.contains("P0301"))
        assertTrue(log.entries().filter { it.dir == CommLog.Dir.TX }.map { it.text }.none { it == "ATD" })
    }

    @Test
    fun adapterWithRealKLine() = runBlocking {
        val s = session(DemoScenario.NORMAL)
        s.connectVehicle(null) {}
        val r = AdapterCheck(s).run()
        assertEquals(KLineSupport.LIKELY, r.kLine)
        assertTrue(r.verdict.first().startsWith("K-line изглежда реално поддържан"))
        assertTrue(r.verdict.any { it.startsWith("CAN: сканирането на модули се поддържа напълно") })
        // The adapter is set back for normal OBD afterwards.
        assertEquals(3, s.readDtcs(0x03).size)
    }

    private fun replay(fi: String, si: String): ObdSession = runBlocking {
        val log = """
            # odit-log 1
            00:00:00.000 > ATFI
            00:00:00.010 < $fi\r\r>
            00:00:00.020 > ATSI
            00:00:00.030 < $si\r\r>
        """.trimIndent()
        val l = CommLog()
        ObdSession(Elm327(ReplayTransport(log), l), l)
    }

    @Test
    fun adapterAnsweringInstantlyHasNoRealKLine() = runBlocking {
        val r = AdapterCheck(replay("BUS INIT: ...ERROR", "BUS INIT: ...ERROR")).run()
        assertEquals(KLineSupport.PROBABLY_NOT, r.kLine)
        assertTrue(r.verdict.first().contains("няма реален K-line"))
    }

    @Test
    fun adapterWithoutKLineCommands() = runBlocking {
        val r = AdapterCheck(replay("?", "?")).run()
        assertEquals(KLineSupport.NO, r.kLine)
        assertTrue(r.verdict.first().startsWith("Адаптерът не познава командите за K-line"))
    }
}
