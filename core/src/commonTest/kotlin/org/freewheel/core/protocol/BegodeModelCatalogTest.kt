package org.freewheel.core.protocol

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
}
