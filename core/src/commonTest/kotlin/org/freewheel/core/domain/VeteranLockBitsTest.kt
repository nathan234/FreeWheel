package org.freewheel.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VeteranLockBitsTest {

    private fun veteran(lockState: Int) = WheelSettings.Veteran(lockState = lockState)

    @Test
    fun `unread lockState yields null for every bit`() {
        val v = veteran(-1)
        assertNull(v.lastPasswordCommandSucceeded)
        assertNull(v.autoLockEnabled)
        assertNull(v.hasPassword)
    }

    @Test
    fun `bit 0 reflects last password command success`() {
        // 0x01 = only bit 0 set
        assertEquals(true, veteran(0x01).lastPasswordCommandSucceeded)
        assertEquals(false, veteran(0x00).lastPasswordCommandSucceeded)
        // Bit 0 set alongside other bits still reads as true
        assertEquals(true, veteran(0x61).lastPasswordCommandSucceeded)
    }

    @Test
    fun `bit 5 reflects auto-lock enabled`() {
        // 0x20 = only bit 5 set
        assertEquals(true, veteran(0x20).autoLockEnabled)
        assertEquals(false, veteran(0x00).autoLockEnabled)
        // Other bits set without bit 5 → false
        assertEquals(false, veteran(0x41).autoLockEnabled)
    }

    @Test
    fun `bit 6 reflects password is set on the wheel`() {
        // 0x40 = only bit 6 set
        assertEquals(true, veteran(0x40).hasPassword)
        assertEquals(false, veteran(0x00).hasPassword)
        // Other bits set without bit 6 → false
        assertEquals(false, veteran(0x21).hasPassword)
    }

    @Test
    fun `all-bits-set lockState decodes to all-true accessors`() {
        // 0x61 = bits 0, 5, 6 all set (the meaningful trio)
        val v = veteran(0x61)
        assertEquals(true, v.lastPasswordCommandSucceeded)
        assertEquals(true, v.autoLockEnabled)
        assertEquals(true, v.hasPassword)
    }
}
