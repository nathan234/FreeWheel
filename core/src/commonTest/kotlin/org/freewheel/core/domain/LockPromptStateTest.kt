package org.freewheel.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LockPromptStateTest {

    private val mac = "AA:BB:CC:DD:EE:01"

    // ==================== Validation rules ====================

    @Test
    fun `blank password returns EMPTY_PASSWORD`() {
        assertEquals(LockPromptState.ErrorReason.EMPTY_PASSWORD, LockPromptState.validate(""))
        assertEquals(LockPromptState.ErrorReason.EMPTY_PASSWORD, LockPromptState.validate("   "))
        assertEquals(LockPromptState.ErrorReason.EMPTY_PASSWORD, LockPromptState.validate("\t\n"))
    }

    @Test
    fun `non-numeric password returns INVALID_FORMAT`() {
        assertEquals(LockPromptState.ErrorReason.INVALID_FORMAT, LockPromptState.validate("abcdef"))
        assertEquals(LockPromptState.ErrorReason.INVALID_FORMAT, LockPromptState.validate("12a456"))
        assertEquals(LockPromptState.ErrorReason.INVALID_FORMAT, LockPromptState.validate("0x1234"))
    }

    @Test
    fun `negative numeric password returns INVALID_FORMAT`() {
        // The wire format encodes the password as 3-byte BE unsigned, so
        // negative inputs aren't representable.
        assertEquals(LockPromptState.ErrorReason.INVALID_FORMAT, LockPromptState.validate("-1"))
        assertEquals(LockPromptState.ErrorReason.INVALID_FORMAT, LockPromptState.validate("-123456"))
    }

    @Test
    fun `password above 24-bit max returns INVALID_FORMAT`() {
        // Max 3-byte BE = 0xFFFFFF = 16,777,215.
        assertEquals(LockPromptState.ErrorReason.INVALID_FORMAT, LockPromptState.validate("16777216"))
        assertEquals(LockPromptState.ErrorReason.INVALID_FORMAT, LockPromptState.validate("999999999"))
    }

    @Test
    fun `valid passwords return null`() {
        assertNull(LockPromptState.validate("0"))
        assertNull(LockPromptState.validate("000000"), "leading-zero numeric passwords parse")
        assertNull(LockPromptState.validate("123456"))
        assertNull(LockPromptState.validate("16777215"), "exact 24-bit max is allowed")
        assertNull(LockPromptState.validate(" 123 "), "trim leading/trailing whitespace")
    }

    // ==================== start() construction ====================

    @Test
    fun `start with empty store and SECURE backing yields AwaitingPassword with no prefill and persist enabled`() {
        val store = InMemoryWheelPasswordStore()
        val state = LockPromptState.start(
            action = LockPromptState.LockAction.LOCK,
            address = mac,
            store = store,
            storeBacking = PasswordStorageBacking.SECURE,
        )
        assertEquals(LockPromptState.LockAction.LOCK, state.action)
        assertEquals(mac, state.address)
        assertNull(state.prefilledPassword)
        assertEquals(true, state.canPersistPassword)
    }

    @Test
    fun `start with stored password prefills it`() {
        val store = InMemoryWheelPasswordStore().apply { setPassword(mac, "123456") }
        val state = LockPromptState.start(
            action = LockPromptState.LockAction.UNLOCK,
            address = mac,
            store = store,
            storeBacking = PasswordStorageBacking.SECURE,
        )
        assertEquals(LockPromptState.LockAction.UNLOCK, state.action)
        assertEquals("123456", state.prefilledPassword)
        assertEquals(true, state.canPersistPassword)
    }

    @Test
    fun `start with NONE backing reports persist disabled`() {
        // API 21-22 Android falls back to NoOpWheelPasswordStore — the prompt
        // should hide the "remember password" affordance because the wheel
        // password won't survive the next app launch.
        val store = InMemoryWheelPasswordStore() // any store; backing label is the source of truth
        val state = LockPromptState.start(
            action = LockPromptState.LockAction.LOCK,
            address = mac,
            store = store,
            storeBacking = PasswordStorageBacking.NONE,
        )
        assertEquals(false, state.canPersistPassword)
    }

    @Test
    fun `start does not depend on a prior submit`() {
        // Reopening the prompt should always re-read the store so a password
        // saved in a previous session shows up after a fresh prompt.
        val store = InMemoryWheelPasswordStore()
        val first = LockPromptState.start(
            LockPromptState.LockAction.LOCK, mac, store, PasswordStorageBacking.SECURE,
        )
        assertNull(first.prefilledPassword)

        store.setPassword(mac, "999999")

        val second = LockPromptState.start(
            LockPromptState.LockAction.LOCK, mac, store, PasswordStorageBacking.SECURE,
        )
        assertEquals("999999", second.prefilledPassword)
    }
}
