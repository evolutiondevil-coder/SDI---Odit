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

    private fun session(scenario: DemoScenario): ObdSession = runBlocking {
        val log = CommLog()
        val s = ObdSession(Elm327(SimulatedElm(scenario), log), log)
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
        assertNull(KLineDirect.keyWords(listOf("?")))
    }

    @Test
    fun kwpUnitIsReadAndKw1281UnitsAreRecognised() = runBlocking {
        val s = session(DemoScenario.OLD_VW)
        val direct = KLineDirect(s)
        val found = direct.scan(listOf(vag(0x01), vag(0x03), vag(0x17), vag(0x25)), { _, _, _ -> }, {})
        // 0x03 does not answer at all.
        assertEquals(listOf(0x01, 0x17, 0x25), found.map { it.tx })

        val engine = found[0]
        assertNull(engine.readError)
        assertTrue(engine.identification!!.contains("MARELLI"))
        assertEquals(listOf("VAG 16684 / P0300", "VAG 17978 / P1570", "VAG 18010 / P1602"), engine.dtcs.map { it.displayCode })

        for (old in found.drop(1)) {
            assertTrue(old.dtcs.isEmpty())
            assertEquals(KLineDirect.KW1281_TEXT, old.readError)
        }

        val results = direct.clear(found)
        assertTrue(results[0].first.message, results[0].first.ok)
        assertTrue(results[0].second!!.dtcs.isEmpty())
        // KW1281 units cannot be cleared from here, and the app says why.
        assertEquals(KLineDirect.KW1281_TEXT, results[1].first.message)
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
