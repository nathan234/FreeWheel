package org.freewheel.core.service

import org.freewheel.core.domain.identity.ProtocolFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConnectionHintTest {

    @Test
    fun `default rawName is null`() {
        val hint = ConnectionHint(ProtocolFamily.KINGSONG, HintSource.SCAN_NAME)
        assertNull(hint.rawName)
    }

    @Test
    fun `data class equality respects all fields`() {
        val a = ConnectionHint(ProtocolFamily.KINGSONG, HintSource.SCAN_NAME, "S22 PRO")
        val b = ConnectionHint(ProtocolFamily.KINGSONG, HintSource.SCAN_NAME, "S22 PRO")
        val c = ConnectionHint(ProtocolFamily.KINGSONG, HintSource.SAVED_PROFILE, "S22 PRO")
        assertEquals(a, b)
        assertEquals(false, a == c)
    }

    @Test
    fun `HintSource has the expected variants`() {
        // Lock the enum surface — adding a variant should be a deliberate edit
        // that updates this test, not a silent expansion picked up by the reducer.
        assertEquals(
            setOf(
                HintSource.SCAN_NAME,
                HintSource.SAVED_PROFILE,
                HintSource.EXPLICIT_API,
                HintSource.AUTO_RECONNECT,
            ),
            HintSource.entries.toSet()
        )
    }
}
