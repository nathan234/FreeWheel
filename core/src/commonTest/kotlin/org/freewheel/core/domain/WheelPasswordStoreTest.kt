package org.freewheel.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lock the [WheelPasswordStore] contract against the in-memory implementation.
 * Production secure-storage implementations must satisfy the same behavior.
 */
class WheelPasswordStoreTest {

    private val mac1 = "AA:BB:CC:DD:EE:01"
    private val mac2 = "AA:BB:CC:DD:EE:02"

    @Test
    fun `empty store returns null and reports no password`() {
        val store: WheelPasswordStore = InMemoryWheelPasswordStore()
        assertNull(store.getPassword(mac1))
        assertFalse(store.hasPassword(mac1))
    }

    @Test
    fun `setPassword and getPassword round-trip per address`() {
        val store: WheelPasswordStore = InMemoryWheelPasswordStore()
        store.setPassword(mac1, "123456")
        store.setPassword(mac2, "654321")

        assertEquals("123456", store.getPassword(mac1))
        assertEquals("654321", store.getPassword(mac2))
        assertTrue(store.hasPassword(mac1))
        assertTrue(store.hasPassword(mac2))
    }

    @Test
    fun `setPassword overwrites existing entry`() {
        val store: WheelPasswordStore = InMemoryWheelPasswordStore()
        store.setPassword(mac1, "111111")
        store.setPassword(mac1, "222222")
        assertEquals("222222", store.getPassword(mac1))
    }

    @Test
    fun `setPassword with blank string clears the entry`() {
        // Avoids storing empty passwords that would later be confused with
        // "no password stored" via getPassword null checks.
        val store: WheelPasswordStore = InMemoryWheelPasswordStore()
        store.setPassword(mac1, "111111")
        store.setPassword(mac1, "")
        assertNull(store.getPassword(mac1))
        assertFalse(store.hasPassword(mac1))

        store.setPassword(mac1, "222222")
        store.setPassword(mac1, "   ")
        assertNull(store.getPassword(mac1))
    }

    @Test
    fun `hasPassword returns false for blank stored value defensive check`() {
        // Defense-in-depth: even if a future impl bug stores a blank, hasPassword
        // must report no usable password.
        val store: WheelPasswordStore = InMemoryWheelPasswordStore()
        store.setPassword(mac1, "  ")
        assertFalse(store.hasPassword(mac1))
    }

    @Test
    fun `clearPassword removes only the requested address`() {
        val store: WheelPasswordStore = InMemoryWheelPasswordStore()
        store.setPassword(mac1, "111111")
        store.setPassword(mac2, "222222")
        store.clearPassword(mac1)

        assertNull(store.getPassword(mac1))
        assertEquals("222222", store.getPassword(mac2))
    }

    @Test
    fun `clearPassword on empty store is a no-op`() {
        val store: WheelPasswordStore = InMemoryWheelPasswordStore()
        store.clearPassword(mac1) // must not throw
        assertNull(store.getPassword(mac1))
    }

    @Test
    fun `clearAll wipes every stored entry`() {
        val store: WheelPasswordStore = InMemoryWheelPasswordStore()
        store.setPassword(mac1, "111111")
        store.setPassword(mac2, "222222")
        store.clearAll()

        assertNull(store.getPassword(mac1))
        assertNull(store.getPassword(mac2))
        assertFalse(store.hasPassword(mac1))
        assertFalse(store.hasPassword(mac2))
    }
}
