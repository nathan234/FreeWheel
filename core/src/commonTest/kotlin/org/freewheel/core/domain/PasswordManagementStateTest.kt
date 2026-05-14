package org.freewheel.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PasswordManagementStateTest {

    private val mac = "AA:BB:CC:DD:EE:01"

    private fun input(
        old: String = "",
        new: String = "",
        confirm: String = "",
        remember: Boolean = true,
    ) = PasswordManagementInput(old, new, confirm, remember)

    // ==================== Operation field requirements ====================

    @Test
    fun `field requirements per operation match the form rendering rules`() {
        // SET: no old, yes new
        assertEquals(false, PasswordManagementState.Operation.SET.requiresOldPassword)
        assertEquals(true, PasswordManagementState.Operation.SET.requiresNewPassword)
        // MODIFY: both
        assertEquals(true, PasswordManagementState.Operation.MODIFY.requiresOldPassword)
        assertEquals(true, PasswordManagementState.Operation.MODIFY.requiresNewPassword)
        // CLEAR: old only
        assertEquals(true, PasswordManagementState.Operation.CLEAR.requiresOldPassword)
        assertEquals(false, PasswordManagementState.Operation.CLEAR.requiresNewPassword)
        // AUTO_LOCK_*: old only
        assertEquals(true, PasswordManagementState.Operation.AUTO_LOCK_ON.requiresOldPassword)
        assertEquals(false, PasswordManagementState.Operation.AUTO_LOCK_ON.requiresNewPassword)
        assertEquals(true, PasswordManagementState.Operation.AUTO_LOCK_OFF.requiresOldPassword)
        assertEquals(false, PasswordManagementState.Operation.AUTO_LOCK_OFF.requiresNewPassword)
    }

    // ==================== start() ====================

    @Test
    fun `start prefills stored password and reports persistence based on backing`() {
        val store = InMemoryWheelPasswordStore().apply { setPassword(mac, "999999") }
        val state = PasswordManagementState.start(
            operation = PasswordManagementState.Operation.MODIFY,
            address = mac,
            store = store,
            storeBacking = PasswordStorageBacking.SECURE,
        )
        assertEquals(PasswordManagementState.Operation.MODIFY, state.operation)
        assertEquals(mac, state.address)
        assertEquals("999999", state.priorStoredPassword)
        assertEquals(true, state.canPersistPassword)
    }

    @Test
    fun `start with NONE backing hides the remember affordance`() {
        val state = PasswordManagementState.start(
            operation = PasswordManagementState.Operation.SET,
            address = mac,
            store = InMemoryWheelPasswordStore(),
            storeBacking = PasswordStorageBacking.NONE,
        )
        assertEquals(false, state.canPersistPassword)
    }

    // ==================== validate() ====================

    @Test
    fun `validate SET rejects blank or non-matching confirmation`() {
        // Empty new password
        assertEquals(
            PasswordManagementState.FailureReason.EMPTY_PASSWORD,
            PasswordManagementState.validate(PasswordManagementState.Operation.SET, input(new = "", confirm = "")),
        )
        // Confirmation mismatch
        assertEquals(
            PasswordManagementState.FailureReason.PASSWORDS_DO_NOT_MATCH,
            PasswordManagementState.validate(
                PasswordManagementState.Operation.SET,
                input(new = "123456", confirm = "123457"),
            ),
        )
        // Out-of-range new
        assertEquals(
            PasswordManagementState.FailureReason.INVALID_FORMAT,
            PasswordManagementState.validate(
                PasswordManagementState.Operation.SET,
                input(new = "99999999", confirm = "99999999"),
            ),
        )
    }

    @Test
    fun `validate SET ignores old password field`() {
        // SET shouldn't care if the user types garbage in a hidden old-pwd field.
        assertNull(
            PasswordManagementState.validate(
                PasswordManagementState.Operation.SET,
                input(old = "garbage", new = "123456", confirm = "123456"),
            ),
        )
    }

    @Test
    fun `validate MODIFY requires both passwords and matching confirmation`() {
        // Missing old
        assertEquals(
            PasswordManagementState.FailureReason.EMPTY_PASSWORD,
            PasswordManagementState.validate(
                PasswordManagementState.Operation.MODIFY,
                input(old = "", new = "222222", confirm = "222222"),
            ),
        )
        // Mismatch
        assertEquals(
            PasswordManagementState.FailureReason.PASSWORDS_DO_NOT_MATCH,
            PasswordManagementState.validate(
                PasswordManagementState.Operation.MODIFY,
                input(old = "111111", new = "222222", confirm = "222223"),
            ),
        )
        // Valid
        assertNull(
            PasswordManagementState.validate(
                PasswordManagementState.Operation.MODIFY,
                input(old = "111111", new = "222222", confirm = "222222"),
            ),
        )
    }

    @Test
    fun `validate CLEAR only inspects old password`() {
        assertEquals(
            PasswordManagementState.FailureReason.EMPTY_PASSWORD,
            PasswordManagementState.validate(
                PasswordManagementState.Operation.CLEAR,
                input(old = ""),
            ),
        )
        assertNull(
            PasswordManagementState.validate(
                PasswordManagementState.Operation.CLEAR,
                input(old = "111111"),
            ),
        )
    }

    @Test
    fun `validate AUTO_LOCK ops only inspect old password`() {
        for (op in listOf(
            PasswordManagementState.Operation.AUTO_LOCK_ON,
            PasswordManagementState.Operation.AUTO_LOCK_OFF,
        )) {
            assertEquals(
                PasswordManagementState.FailureReason.INVALID_FORMAT,
                PasswordManagementState.validate(op, input(old = "abc")),
            )
            assertNull(PasswordManagementState.validate(op, input(old = "0")))
        }
    }

    // ==================== submit() ====================

    @Test
    fun `submit returns Submitting on valid input and trims fields`() {
        val editing = PasswordManagementState.Editing(
            operation = PasswordManagementState.Operation.MODIFY,
            address = mac,
            priorStoredPassword = "111111",
            canPersistPassword = true,
        )
        val result = PasswordManagementState.submit(
            editing,
            input(old = " 111111 ", new = " 222222 ", confirm = " 222222 ", remember = true),
        )
        val submitting = assertIs<PasswordManagementState.Submitting>(result)
        assertEquals("111111", submitting.oldPassword)
        assertEquals("222222", submitting.newPassword)
        assertEquals(true, submitting.rememberNewPassword)
        assertEquals("111111", submitting.priorStoredPassword)
    }

    @Test
    fun `submit returns Failed on validation error`() {
        val editing = PasswordManagementState.Editing(
            operation = PasswordManagementState.Operation.SET,
            address = mac,
            priorStoredPassword = null,
            canPersistPassword = true,
        )
        val result = PasswordManagementState.submit(editing, input(new = "abc", confirm = "abc"))
        val failed = assertIs<PasswordManagementState.Failed>(result)
        assertEquals(PasswordManagementState.FailureReason.INVALID_FORMAT, failed.reason)
        assertEquals(PasswordManagementState.Operation.SET, failed.operation)
    }

    // ==================== dispatched() and PendingAck ====================

    @Test
    fun `dispatched copies submission data and sets deadline`() {
        val submitting = PasswordManagementState.Submitting(
            operation = PasswordManagementState.Operation.SET,
            address = mac,
            oldPassword = "",
            newPassword = "123456",
            rememberNewPassword = true,
            priorStoredPassword = null,
        )
        val pending = PasswordManagementState.dispatched(submitting, deadlineEpochMs = 10_000L)
        assertEquals("123456", pending.newPassword)
        assertEquals(10_000L, pending.deadlineEpochMs)
        assertEquals(true, pending.rememberNewPassword)
    }

    // ==================== observeLockState() ====================

    private fun pendingFor(
        operation: PasswordManagementState.Operation,
        rememberNew: Boolean = true,
        priorStored: String? = null,
    ) = PasswordManagementState.PendingAck(
        operation = operation,
        address = mac,
        oldPassword = "111111",
        newPassword = "222222",
        rememberNewPassword = rememberNew,
        priorStoredPassword = priorStored,
        deadlineEpochMs = 1_000L,
    )

    @Test
    fun `observeLockState ignores unread lockState`() {
        val pending = pendingFor(PasswordManagementState.Operation.SET)
        val result = PasswordManagementState.observeLockState(pending, newLockState = -1)
        assertEquals(pending, result)
    }

    @Test
    fun `observeLockState confirms action 11 ops on bit 0 set`() {
        for (op in listOf(
            PasswordManagementState.Operation.SET,
            PasswordManagementState.Operation.MODIFY,
            PasswordManagementState.Operation.CLEAR,
        )) {
            val pending = pendingFor(op)
            val result = PasswordManagementState.observeLockState(pending, newLockState = 0x41)
            assertIs<PasswordManagementState.Confirmed>(result)
            assertEquals(op, result.operation)
        }
    }

    @Test
    fun `observeLockState reports wrong-password on action 11 with bit 0 clear`() {
        val pending = pendingFor(PasswordManagementState.Operation.MODIFY)
        val result = PasswordManagementState.observeLockState(pending, newLockState = 0x40)
        val failed = assertIs<PasswordManagementState.Failed>(result)
        assertEquals(PasswordManagementState.FailureReason.WRONG_PASSWORD, failed.reason)
    }

    @Test
    fun `observeLockState confirms AUTO_LOCK_ON when bit 5 becomes set`() {
        val pending = pendingFor(PasswordManagementState.Operation.AUTO_LOCK_ON)
        val result = PasswordManagementState.observeLockState(pending, newLockState = 0x20)
        val confirmed = assertIs<PasswordManagementState.Confirmed>(result)
        assertEquals(PasswordManagementState.PersistenceAction.NoOp, confirmed.persistence)
    }

    @Test
    fun `observeLockState rejects AUTO_LOCK_ON when bit 5 stays clear`() {
        val pending = pendingFor(PasswordManagementState.Operation.AUTO_LOCK_ON)
        val result = PasswordManagementState.observeLockState(pending, newLockState = 0x41)
        val failed = assertIs<PasswordManagementState.Failed>(result)
        assertEquals(PasswordManagementState.FailureReason.WRONG_PASSWORD, failed.reason)
    }

    @Test
    fun `observeLockState confirms AUTO_LOCK_OFF when bit 5 becomes clear`() {
        val pending = pendingFor(PasswordManagementState.Operation.AUTO_LOCK_OFF)
        val result = PasswordManagementState.observeLockState(pending, newLockState = 0x41)
        assertIs<PasswordManagementState.Confirmed>(result)
    }

    @Test
    fun `observeLockState rejects AUTO_LOCK_OFF when bit 5 still set`() {
        val pending = pendingFor(PasswordManagementState.Operation.AUTO_LOCK_OFF)
        val result = PasswordManagementState.observeLockState(pending, newLockState = 0x61)
        val failed = assertIs<PasswordManagementState.Failed>(result)
        assertEquals(PasswordManagementState.FailureReason.WRONG_PASSWORD, failed.reason)
    }

    // ==================== persistence rules at Confirmed ====================

    @Test
    fun `SET success persists new password only when remember is on`() {
        val rememberOn = PasswordManagementState.observeLockState(
            pendingFor(PasswordManagementState.Operation.SET, rememberNew = true),
            newLockState = 0x41,
        )
        val confirmedOn = assertIs<PasswordManagementState.Confirmed>(rememberOn)
        assertEquals(
            PasswordManagementState.PersistenceAction.Store("222222"),
            confirmedOn.persistence,
        )

        val rememberOff = PasswordManagementState.observeLockState(
            pendingFor(PasswordManagementState.Operation.SET, rememberNew = false),
            newLockState = 0x41,
        )
        val confirmedOff = assertIs<PasswordManagementState.Confirmed>(rememberOff)
        assertEquals(PasswordManagementState.PersistenceAction.NoOp, confirmedOff.persistence)
    }

    @Test
    fun `MODIFY success replaces prior stored password when remember is on`() {
        val pending = pendingFor(
            PasswordManagementState.Operation.MODIFY,
            rememberNew = true,
            priorStored = "111111",
        )
        val result = PasswordManagementState.observeLockState(pending, newLockState = 0x41)
        val confirmed = assertIs<PasswordManagementState.Confirmed>(result)
        assertEquals(
            PasswordManagementState.PersistenceAction.Store("222222"),
            confirmed.persistence,
        )
    }

    @Test
    fun `MODIFY success clears prior storage when user opts out`() {
        val pending = pendingFor(
            PasswordManagementState.Operation.MODIFY,
            rememberNew = false,
            priorStored = "111111",
        )
        val result = PasswordManagementState.observeLockState(pending, newLockState = 0x41)
        val confirmed = assertIs<PasswordManagementState.Confirmed>(result)
        assertEquals(PasswordManagementState.PersistenceAction.Clear, confirmed.persistence)
    }

    @Test
    fun `MODIFY success is no-op when nothing was stored and user opts out`() {
        val pending = pendingFor(
            PasswordManagementState.Operation.MODIFY,
            rememberNew = false,
            priorStored = null,
        )
        val result = PasswordManagementState.observeLockState(pending, newLockState = 0x41)
        val confirmed = assertIs<PasswordManagementState.Confirmed>(result)
        assertEquals(PasswordManagementState.PersistenceAction.NoOp, confirmed.persistence)
    }

    @Test
    fun `CLEAR success always clears stored password`() {
        // Stored
        val withStored = PasswordManagementState.observeLockState(
            pendingFor(PasswordManagementState.Operation.CLEAR, priorStored = "111111"),
            newLockState = 0x41,
        )
        assertEquals(
            PasswordManagementState.PersistenceAction.Clear,
            (withStored as PasswordManagementState.Confirmed).persistence,
        )
        // Not stored — still emits Clear (safe; the store treats it as a no-op).
        val withoutStored = PasswordManagementState.observeLockState(
            pendingFor(PasswordManagementState.Operation.CLEAR, priorStored = null),
            newLockState = 0x41,
        )
        assertEquals(
            PasswordManagementState.PersistenceAction.Clear,
            (withoutStored as PasswordManagementState.Confirmed).persistence,
        )
    }

    @Test
    fun `AUTO_LOCK success never touches the store`() {
        for (op in listOf(
            PasswordManagementState.Operation.AUTO_LOCK_ON,
            PasswordManagementState.Operation.AUTO_LOCK_OFF,
        )) {
            val pending = pendingFor(op, priorStored = "111111")
            // Pick a lockState that confirms this op.
            val lockState = if (op == PasswordManagementState.Operation.AUTO_LOCK_ON) 0x21 else 0x01
            val result = PasswordManagementState.observeLockState(pending, lockState)
            val confirmed = assertIs<PasswordManagementState.Confirmed>(result)
            assertEquals(PasswordManagementState.PersistenceAction.NoOp, confirmed.persistence)
        }
    }

    // ==================== timeout() ====================

    @Test
    fun `timeout returns Failed(TIMEOUT) preserving operation and address`() {
        val pending = pendingFor(PasswordManagementState.Operation.MODIFY)
        val failed = PasswordManagementState.timeout(pending)
        assertEquals(PasswordManagementState.FailureReason.TIMEOUT, failed.reason)
        assertEquals(PasswordManagementState.Operation.MODIFY, failed.operation)
        assertEquals(mac, failed.address)
    }

    @Test
    fun `default ack timeout is 2 seconds`() {
        // The official app waits 1s; FreeWheel adds slack for BLE jitter
        // while staying close to the app's expected window.
        assertEquals(2000L, PasswordManagementState.DEFAULT_ACK_TIMEOUT_MS)
    }
}
