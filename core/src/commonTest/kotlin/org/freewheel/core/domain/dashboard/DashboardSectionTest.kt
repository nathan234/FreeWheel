package org.freewheel.core.domain.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DashboardSectionTest {

    @Test
    fun `there are 3 sections`() {
        assertEquals(3, DashboardSection.entries.size)
    }

    @Test
    fun `WHEEL_SETTINGS has correct label`() {
        assertEquals("Wheel Settings", DashboardSection.WHEEL_SETTINGS.label)
    }

    @Test
    fun `WHEEL_INFO has correct label`() {
        assertEquals("Wheel Info", DashboardSection.WHEEL_INFO.label)
    }

    @Test
    fun `BMS_SUMMARY has correct label`() {
        assertEquals("BMS Summary", DashboardSection.BMS_SUMMARY.label)
    }

    @Test
    fun `all sections have non-empty labels`() {
        for (section in DashboardSection.entries) {
            assertTrue(section.label.isNotBlank(), "${section.name} should have a non-blank label")
        }
    }
}
