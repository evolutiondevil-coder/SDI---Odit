package com.odit.obd

import com.odit.obd.data.DirPackSource
import com.odit.obd.garage.CarRecord
import com.odit.obd.garage.Garage
import com.odit.obd.garage.ScanRecord
import com.odit.obd.garage.SavedModule
import com.odit.obd.obd.Brand
import com.odit.obd.uds.DiagType
import com.odit.obd.vehicle.Confidence
import com.odit.obd.vehicle.UserVehicle
import com.odit.obd.vehicle.VehicleDecoder
import com.odit.obd.vehicle.VinRules
import com.odit.obd.vehicle.WmiDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Decoding with the real data packs from odit-data/packs. */
class VehicleTest {

    private val packs = DirPackSource(
        listOf(File("../../odit-data/packs"), File("../odit-data/packs")).first { it.isDirectory },
    )
    private val wmi = WmiDb.parse(packs.read("wmi"))
    private val rules = VinRules.parse(packs.read("vin-rules"))
    private val decoder = VehicleDecoder(wmi, rules)

    @Test
    fun wmiTableCoversCommonMakes() {
        assertTrue(wmi.size > 1000)
        for ((prefix, make) in listOf(
            "WVW" to "Volkswagen", "WAU" to "Audi", "TMB" to "Škoda", "VSS" to "SEAT", "WBA" to "BMW",
            "WDD" to "Mercedes-Benz", "W0L" to "Opel", "VF1" to "Renault", "UU1" to "Dacia", "VF3" to "Peugeot",
            "VF7" to "Citroën", "ZFA" to "Fiat", "WF0" to "Ford", "SB1" to "Toyota", "KMH" to "Hyundai",
            "U5Y" to "Kia", "JN1" to "Nissan", "YV1" to "Volvo", "JHM" to "Honda", "XTA" to "Lada",
        )) {
            assertEquals(prefix, make, wmi.lookup(prefix + "ZZZ00000000000")?.make)
        }
        assertEquals(Brand.VAG, wmi.lookup("TMB")?.group)
        assertEquals(Brand.RENAULT_NISSAN, wmi.lookup("UU1")?.group)
    }

    @Test
    fun countryFallbackForUnknownWmi() {
        assertNull(wmi.lookup("Y3MZZZ15ZKA000777"))
        assertEquals("Беларус", wmi.country("Y3MZZZ15ZKA000777"))
        assertEquals("България", wmi.country("XBA00000000000000"))
        assertEquals("Германия", wmi.country("W9Q00000000000000"))
        assertEquals("Япония", wmi.country("J0000000000000000"))
        val id = decoder.decode("Y3MZZZ15ZKA000777")
        assertNull(id.make)
        assertEquals("Беларус", id.country)
    }

    @Test
    fun vagModelAndReliableYear() {
        val id = decoder.decode("WVWZZZ1KZ8W123456", currentYear = 2026)
        assertEquals("Volkswagen", id.make?.value)
        assertEquals(Confidence.CERTAIN, id.make?.confidence)
        assertEquals("Golf V / Jetta V", id.model?.value)
        assertEquals(Confidence.PROBABLE, id.model?.confidence)
        assertEquals(2008, id.year?.value)
        assertEquals(Confidence.CERTAIN, id.year?.confidence)
        assertEquals(Brand.VAG, id.group)
        assertEquals("Audi A3 (8P)", decoder.decode("WAUZZZ8P0AA000001").let { "${it.make?.value} ${it.model?.value}" })
        assertEquals("Octavia II", decoder.decode("TMBBS21Z962000001").model?.value)
    }

    @Test
    fun mercedesNeverShowsYearFromPosition10() {
        val id = decoder.decode("WDD2050421R123456", currentYear = 2026)
        assertEquals("C-класа (W205)", id.model?.value)
        assertNull(id.year)
        assertNotNull(id.yearNote)
    }

    @Test
    fun europeanFordUsesPosition9And11() {
        // WF0 + body + XX + model line + factory + model letter (pos 9) + body + year (pos 11)
        val id = decoder.decode("WF0AXXWPCA8D12345", currentYear = 2026)
        assertEquals("Ford", id.make?.value)
        assertEquals("Focus / S-Max / Transit Courier", id.model?.value)
        assertEquals(2008, id.year?.value)
        assertEquals(Confidence.PROBABLE, id.year?.confidence)
    }

    @Test
    fun northAmericanYearUsesPosition7() {
        // Honda Accord 2003 (letter at pos 7 would mean 2010+).
        val id = decoder.decode("1HGCM82633A004352", currentYear = 2026)
        assertEquals(2003, id.year?.value)
        assertEquals(Confidence.CERTAIN, id.year?.confidence)
    }

    @Test
    fun unknownRulesGiveProbableYearOnly() {
        val id = decoder.decode("VF1BB0A0526123456", currentYear = 2026)
        assertEquals("Renault", id.make?.value)
        assertNull(id.model)
        assertEquals(Confidence.PROBABLE, id.year?.confidence)
        // Position 10 = 0 is not a year at all.
        assertNull(decoder.decode("ZFA19900000000001").year)
    }

    @Test
    fun userCorrectionsWin() {
        val id = decoder.decode("WVWZZZ1KZ8W123456", UserVehicle(model = "Golf VI", year = 2009, engine = "1.6 TDI"))
        assertEquals("Golf VI", id.model?.value)
        assertEquals(Confidence.USER, id.model?.confidence)
        assertEquals(2009, id.year?.value)
        assertEquals("1.6 TDI", id.engine?.value)
        assertEquals("Volkswagen Golf VI 2009", id.shortName)
        // A make typed by hand also selects the module names of its group.
        assertEquals(Brand.VAG, decoder.decode("Y3MZZZ15ZKA000777", UserVehicle(make = "Škoda")).group)
    }

    @Test
    fun garageRoundTrip() {
        val dir = Files.createTempDirectory("garage").toFile()
        val g = Garage(dir)
        val car = CarRecord(
            id = CarRecord.idForVin("WVWZZZ1KZ8W123456"), vin = "WVWZZZ1KZ8W123456", name = null, adapter = "AA:BB",
            user = UserVehicle(engine = "1.4 TSI"), protocol = '6',
            modules = listOf(SavedModule(0x713, 0x77D, false, "77D", "ABS", "ESP MK100", DiagType.UDS)),
            history = listOf(ScanRecord(1L, ScanRecord.OBD, listOf("P0301"))),
        )
        g.save(car)
        assertEquals(car.copy(lastSeen = car.lastSeen), g.get(car.id))
        assertEquals(car.id, g.lastForAdapter("AA:BB")?.id)
        g.delete(car.id)
        assertTrue(g.all().isEmpty())
    }
}
