package org.freewheel.core.protocol

import org.freewheel.core.domain.profile.BegodeModelCatalog
import org.freewheel.core.domain.profile.BegodeVoltageClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BegodeModelCatalogTest {

    @Test
    fun `Commander Max name resolves modern Extreme Bull defaults`() {
        val profile = BegodeModelCatalog.match(model = "MAX", firmware = "JN2026001")

        assertEquals("Commander Max", profile?.displayName)
        assertEquals("Extreme Bull", profile?.brand)
        assertEquals(168.0, profile?.fullVoltageV)
        assertEquals(170.0, profile?.noLoadSpeedKmh)
    }

    @Test
    fun `firmware resolves model when wheel name is generic`() {
        val profile = BegodeModelCatalog.match(model = "", firmware = "GW2035101")

        assertEquals("Blitz", profile?.displayName)
        assertEquals(134.4, profile?.fullVoltageV)
    }

    @Test
    fun `name matching is case insensitive`() {
        assertEquals(
            "ET Max",
            BegodeModelCatalog.match(model = "et max", firmware = "")?.displayName,
        )
    }

    @Test
    fun `unknown wheel does not invent a profile`() {
        assertNull(BegodeModelCatalog.match(model = "UNKNOWN MODEL", firmware = "GW0000000"))
    }

    @Test
    fun `all catalog pack voltages map to an explicit voltage class`() {
        assertEquals(BegodeVoltageClass.V42, BegodeModelCatalog.match("Mten mini", "")?.voltageClass)
        assertEquals(BegodeVoltageClass.V210, BegodeModelCatalog.match("RACE", "")?.voltageClass)
    }

    @Test
    fun `legacy firmware signatures resolve Hero and Msuper X voltage variants`() {
        assertEquals(
            "Hero C30",
            BegodeModelCatalog.match("", "GW2002201")?.displayName,
        )
        assertEquals(
            BegodeVoltageClass.V84,
            BegodeModelCatalog.match("", "CF1931001")?.voltageClass,
        )
        assertEquals(
            BegodeVoltageClass.V100_8,
            BegodeModelCatalog.match("", "GW1932001")?.voltageClass,
        )
    }

    @Test
    fun `legacy named voltage variants do not fall back to 84 volts`() {
        assertEquals(BegodeVoltageClass.V67_2, BegodeModelCatalog.match("Tesla (67 V)", "")?.voltageClass)
        assertEquals(BegodeVoltageClass.V100_8, BegodeModelCatalog.match("Monster V3 (100 V)", "")?.voltageClass)
        assertEquals(BegodeVoltageClass.V84, BegodeModelCatalog.match("Nikola", "")?.voltageClass)
    }
}
