package org.freewheel.core.domain

/**
 * Shared state machine for the Veteran password-management UX (set / modify /
 * clear / auto-lock). Runs on Compose and SwiftUI:
 *
 *   Idle → Editing → Submitting → PendingAck → (Confirmed | Failed) → Idle
 *
 * Different from [LockPromptState] in two ways:
 *
 *   1. The user-visible form differs per [Operation] (SET asks for newPassword
 *      + confirmation, MODIFY adds an oldPassword field, CLEAR / AUTO_LOCK only
 *      ask for the current password). The model carries enough data to drive
 *      that on both platforms without re-deriving operation semantics.
 *   2. The official app waits ~1 second after dispatch and reads `lockState`
 *      bit 0 (action 11) or bit 5 (action 2/3) to decide success vs. wrong
 *      password — out-of-band acknowledgment, not fire-and-forget. FreeWheel
 *      models this as a [PendingAck] state with a deadline; the ViewModel
 *      pumps successive `lockState` readbacks through [observeLockState] and
 *      surfaces timeout via [timeout].
 *
 * Persistence rules are encoded in [PersistenceAction] and computed once at
 * the Confirmed transition so the ViewModel doesn't have to re-derive them.
 */
sealed class PasswordManagementState {

    /** No password-management UI is currently visible. */
    data object Idle : PasswordManagementState()

    /**
     * The dialog/screen is open. Platform code renders fields per
     * [operation]; [priorStoredPassword] prefills the "current password"
     * field when applicable, and [canPersistPassword] gates the
     * "remember new password" toggle.
     */
    data class Editing(
        val operation: Operation,
        val address: String,
        val priorStoredPassword: String?,
        val canPersistPassword: Boolean,
    ) : PasswordManagementState()

    /**
     * Validation passed; the ViewModel is about to dispatch the wire
     * command. Carries the user's inputs so the next state can finish the
     * persistence reconciliation if confirmation succeeds.
     */
    data class Submitting(
        val operation: Operation,
        val address: String,
        val oldPassword: String,
        val newPassword: String,
        val rememberNewPassword: Boolean,
        val priorStoredPassword: String?,
    ) : PasswordManagementState()

    /**
     * The wire command was dispatched. We're waiting for the wheel to emit
     * a subtype-5 frame whose lockState bits confirm or reject the command.
     * [deadlineEpochMs] is when the ViewModel should give up and report
     * [FailureReason.TIMEOUT].
     */
    data class PendingAck(
        val operation: Operation,
        val address: String,
        val oldPassword: String,
        val newPassword: String,
        val rememberNewPassword: Boolean,
        val priorStoredPassword: String?,
        val deadlineEpochMs: Long,
    ) : PasswordManagementState()

    /**
     * Terminal success state. The ViewModel must apply [persistence] to its
     * [WheelPasswordStore] and then auto-dismiss to [Idle] after the toast.
     */
    data class Confirmed(
        val operation: Operation,
        val address: String,
        val persistence: PersistenceAction,
    ) : PasswordManagementState()

    /** Terminal failure state. UI shows the error and offers retry / close. */
    data class Failed(
        val operation: Operation,
        val address: String,
        val reason: FailureReason,
    ) : PasswordManagementState()

    enum class Operation {
        /** First-time password set. */
        SET,

        /** Change an existing password. */
        MODIFY,

        /** Wipe the wheel-side password. */
        CLEAR,

        /** Toggle auto-lock on. */
        AUTO_LOCK_ON,

        /** Toggle auto-lock off. */
        AUTO_LOCK_OFF,
    }

    enum class FailureReason {
        /** A required password field was blank. */
        EMPTY_PASSWORD,

        /** A password field didn't parse as a non-negative integer ≤ 0xFFFFFF. */
        INVALID_FORMAT,

        /** SET / MODIFY confirmation didn't match the new password. */
        PASSWORDS_DO_NOT_MATCH,

        /** BLE link dropped before dispatch. */
        NOT_CONNECTED,

        /** Wheel responded but rejected the command (action 11: bit 0 clear; action 2/3: bit 5 didn't match). */
        WRONG_PASSWORD,

        /** No lockState readback arrived before [PendingAck.deadlineEpochMs]. */
        TIMEOUT,
    }

    /**
     * What the ViewModel should do with [WheelPasswordStore] when the wheel
     * confirms the command. Computed once at the Confirmed transition so the
     * rules are testable and the ViewModel stays mechanical.
     */
    sealed class PersistenceAction {
        /** Don't touch the store. */
        data object NoOp : PersistenceAction()

        /** Write [password] to the store for this MAC. */
        data class Store(val password: String) : PersistenceAction()

        /** Remove any stored password for this MAC. */
        data object Clear : PersistenceAction()
    }

    companion object {
        /**
         * Default deadline budget for PendingAck. The official app uses a 1s
         * delay then reads lockState; FreeWheel gives BLE / scheduling jitter
         * some slack but still stays close to the app's expected window.
         */
        const val DEFAULT_ACK_TIMEOUT_MS: Long = 2000L

        /**
         * Open the dialog for [operation] against [address]. Prefills the
         * stored password and reports whether the "remember" affordance is
         * available based on the [storeBacking] chosen by the platform.
         */
        fun start(
            operation: Operation,
            address: String,
            store: WheelPasswordStore,
            storeBacking: PasswordStorageBacking,
        ): Editing = Editing(
            operation = operation,
            address = address,
            priorStoredPassword = store.getPassword(address),
            canPersistPassword = storeBacking == PasswordStorageBacking.SECURE,
        )

        /**
         * Validate [input] for [operation]. Returns null on success or the
         * [FailureReason] the prompt should surface otherwise. SET and MODIFY
         * additionally require [PasswordManagementInput.newPassword] to match
         * [PasswordManagementInput.confirmationPassword] after trimming.
         */
        fun validate(operation: Operation, input: PasswordManagementInput): FailureReason? {
            if (operation.requiresOldPassword) {
                LockPromptState.validate(input.oldPassword)?.let { return it.toFailureReason() }
            }
            if (operation.requiresNewPassword) {
                LockPromptState.validate(input.newPassword)?.let { return it.toFailureReason() }
                if (input.newPassword.trim() != input.confirmationPassword.trim()) {
                    return FailureReason.PASSWORDS_DO_NOT_MATCH
                }
            }
            return null
        }

        /**
         * Transition Editing → Submitting on valid input, or Editing → Failed
         * on a validation error. The ViewModel calls this synchronously when
         * the user taps the confirm button.
         */
        fun submit(editing: Editing, input: PasswordManagementInput): PasswordManagementState {
            validate(editing.operation, input)?.let {
                return Failed(editing.operation, editing.address, it)
            }
            return Submitting(
                operation = editing.operation,
                address = editing.address,
                oldPassword = input.oldPassword.trim(),
                newPassword = input.newPassword.trim(),
                rememberNewPassword = input.rememberNewPassword,
                priorStoredPassword = editing.priorStoredPassword,
            )
        }

        /**
         * Transition Submitting → PendingAck after the wire command has been
         * handed to the connection manager. The ViewModel computes
         * [deadlineEpochMs] from its monotonic clock.
         */
        fun dispatched(submitting: Submitting, deadlineEpochMs: Long): PendingAck = PendingAck(
            operation = submitting.operation,
            address = submitting.address,
            oldPassword = submitting.oldPassword,
            newPassword = submitting.newPassword,
            rememberNewPassword = submitting.rememberNewPassword,
            priorStoredPassword = submitting.priorStoredPassword,
            deadlineEpochMs = deadlineEpochMs,
        )

        /**
         * Feed a fresh `lockState` byte from a subtype-5 readback into a
         * PendingAck. Returns:
         *   - [Confirmed] when the relevant bit indicates success
         *   - [Failed] with [FailureReason.WRONG_PASSWORD] when the wheel
         *     reports rejection
         *   - the unchanged [PendingAck] when [newLockState] is -1 (no fresh
         *     readback yet)
         *
         * For SET/MODIFY/CLEAR (action 11) the success bit is `lockState & 0x01`.
         * For AUTO_LOCK_ON/OFF (action 2/3) the wheel reports the current state
         * in `lockState & 0x20`; confirmation requires that bit to match the
         * operation's intent.
         */
        fun observeLockState(pending: PendingAck, newLockState: Int): PasswordManagementState {
            if (newLockState < 0) return pending
            return when (pending.operation) {
                Operation.SET, Operation.MODIFY, Operation.CLEAR -> {
                    val bit0Set = (newLockState and 0x01) != 0
                    if (bit0Set) {
                        Confirmed(pending.operation, pending.address, persistenceFor(pending))
                    } else {
                        Failed(pending.operation, pending.address, FailureReason.WRONG_PASSWORD)
                    }
                }
                Operation.AUTO_LOCK_ON, Operation.AUTO_LOCK_OFF -> {
                    val bit5Set = (newLockState and 0x20) != 0
                    val expected = pending.operation == Operation.AUTO_LOCK_ON
                    if (bit5Set == expected) {
                        Confirmed(pending.operation, pending.address, PersistenceAction.NoOp)
                    } else {
                        Failed(pending.operation, pending.address, FailureReason.WRONG_PASSWORD)
                    }
                }
            }
        }

        /** Transition PendingAck → Failed(TIMEOUT) when the deadline elapses. */
        fun timeout(pending: PendingAck): Failed = Failed(
            operation = pending.operation,
            address = pending.address,
            reason = FailureReason.TIMEOUT,
        )

        private fun persistenceFor(pending: PendingAck): PersistenceAction = when (pending.operation) {
            Operation.SET -> {
                if (pending.rememberNewPassword) PersistenceAction.Store(pending.newPassword)
                else PersistenceAction.NoOp
            }
            Operation.MODIFY -> {
                val wasRemembered = pending.priorStoredPassword != null
                when {
                    pending.rememberNewPassword -> PersistenceAction.Store(pending.newPassword)
                    wasRemembered -> PersistenceAction.Clear
                    else -> PersistenceAction.NoOp
                }
            }
            Operation.CLEAR -> PersistenceAction.Clear
            Operation.AUTO_LOCK_ON, Operation.AUTO_LOCK_OFF -> PersistenceAction.NoOp
        }

        private fun LockPromptState.ErrorReason.toFailureReason(): FailureReason = when (this) {
            LockPromptState.ErrorReason.EMPTY_PASSWORD -> FailureReason.EMPTY_PASSWORD
            LockPromptState.ErrorReason.INVALID_FORMAT -> FailureReason.INVALID_FORMAT
            LockPromptState.ErrorReason.NOT_CONNECTED -> FailureReason.NOT_CONNECTED
        }
    }
}

/**
 * Per-operation form requirements. Drives which password fields the platform
 * UI shows and which fields [PasswordManagementState.validate] checks.
 */
val PasswordManagementState.Operation.requiresOldPassword: Boolean
    get() = when (this) {
        PasswordManagementState.Operation.SET -> false
        PasswordManagementState.Operation.MODIFY,
        PasswordManagementState.Operation.CLEAR,
        PasswordManagementState.Operation.AUTO_LOCK_ON,
        PasswordManagementState.Operation.AUTO_LOCK_OFF -> true
    }

val PasswordManagementState.Operation.requiresNewPassword: Boolean
    get() = when (this) {
        PasswordManagementState.Operation.SET,
        PasswordManagementState.Operation.MODIFY -> true
        PasswordManagementState.Operation.CLEAR,
        PasswordManagementState.Operation.AUTO_LOCK_ON,
        PasswordManagementState.Operation.AUTO_LOCK_OFF -> false
    }

/**
 * Inputs collected by the platform form, passed to [PasswordManagementState.submit].
 * Fields that aren't relevant to the operation are ignored (and may be blank).
 */
data class PasswordManagementInput(
    val oldPassword: String,
    val newPassword: String,
    val confirmationPassword: String,
    val rememberNewPassword: Boolean,
)
