package org.freewheel.core.domain.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VeteranModelCatalogTest {

    @Test
    fun `Nosfet manufacturer IDs normalize with honest fallback provenance`() {
        val apex = VeteranModelCatalog.matchManufacturerVersion(501)
        val xeno = VeteranModelCatalog.matchManufacturerVersion(504)

        assertEquals(42, apex?.modelVersion)
        assertEquals("Nosfet Apex", apex?.displayName)
        assertEquals(151.2, apex?.fullVoltageV)
        assertEquals(36, apex?.seriesCellCount)
        assertEquals(WheelSocSource.MODEL_CLASS_FALLBACK, apex?.socSource)

        assertEquals(45, xeno?.modelVersion)
        assertEquals("Nosfet Xeno", xeno?.displayName)
        assertEquals(126.0, xeno?.fullVoltageV)
        assertEquals(30, xeno?.seriesCellCount)
        assertEquals(WheelSocSource.MODEL_CLASS_FALLBACK, xeno?.socSource)
    }

    @Test
    fun `Oryx is identified as a model-class SOC fallback`() {
        val profile = VeteranModelCatalog.matchModelVersion(8)

        assertEquals("Leaperkim Oryx", profile?.displayName)
        assertEquals(176.4, profile?.fullVoltageV)
        assertEquals(42, profile?.seriesCellCount)
        assertEquals(WheelSocSource.MODEL_CLASS_FALLBACK, profile?.socSource)
    }

    @Test
    fun `identity names resolve case insensitively`() {
        assertEquals(
            7,
            VeteranModelCatalog.matchName("LEAPERKIM PATTON S")?.modelVersion,
        )
    }

    @Test
    fun `unknown model does not invent a profile`() {
        assertNull(VeteranModelCatalog.matchModelVersion(99))
        assertNull(VeteranModelCatalog.matchName("Unknown veteran wheel"))
    }
}
