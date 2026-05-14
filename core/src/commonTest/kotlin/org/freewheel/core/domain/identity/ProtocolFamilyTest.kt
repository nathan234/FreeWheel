package org.freewheel.core.domain.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolFamilyTest {

    @Test
    fun `every family round-trips through WheelType`() {
        for (family in ProtocolFamily.entries) {
            val viaWheelType = ProtocolFamily.fromWheelType(family.toWheelType())
            assertEquals(family, viaWheelType, "Round-trip failed for $family")
        }
    }

    @Test
    fun `fromWheelType maps every protocol-bearing WheelType to a family`() {
        // Every WheelType except Unknown and GOTWAY_VIRTUAL must have a family.
        // If we ever add a new real protocol to WheelType without a matching
        // ProtocolFamily entry, this test fails.
        val expectedNullCases = setOf(WheelType.Unknown, WheelType.GOTWAY_VIRTUAL)
        for (t in WheelType.entries) {
            val family = ProtocolFamily.fromWheelType(t)
            if (t in expectedNullCases) {
                assertNull(family, "$t should not map to a ProtocolFamily")
            } else {
                assertEquals(t, family?.toWheelType(), "$t mapped inconsistently")
            }
        }
    }

    @Test
    fun `fromWheelType GOTWAY_VIRTUAL is null`() {
        assertNull(ProtocolFamily.fromWheelType(WheelType.GOTWAY_VIRTUAL))
    }

    @Test
    fun `fromWheelType Unknown is null`() {
        assertNull(ProtocolFamily.fromWheelType(WheelType.Unknown))
    }
}
