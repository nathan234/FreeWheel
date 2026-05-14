package org.freewheel.core.domain

/**
 * Shared state machine for the Veteran lock/unlock prompt UX.
 *
 * The prompt is a small dialog flow that runs the same way on Compose and
 * SwiftUI:
 *   Idle → AwaitingPassword → Sending → (Sent | Error)
 *
 * Platform code owns the rendering (dialog vs sheet, button labels,
 * localized error strings). This file owns the transitions, the validation
 * rules, and the persistence-capability signal so neither platform has to
 * peek at the [WheelPasswordStore] backing or re-derive the rules.
 *
 * Validation matches the wire format in [org.freewheel.core.protocol.VeteranDecoder.buildLockCommand]:
 * the password is encoded as a 3-byte big-endian integer, so any non-negative
 * value 0..[MAX_PASSWORD_VALUE] fits. UI may layer stricter format rules
 * (e.g. fixed-length 6 digits) on top.
 */
sealed class LockPromptState {

    /** No prompt is currently visible. */
    data object Idle : LockPromptState()

    /**
     * Prompt is open and waiting for the user to confirm the password and
     * submit. [prefilledPassword] comes from the WheelPasswordStore for this
     * MAC; [canPersistPassword] tells the UI whether the "remember password"
     * toggle is meaningful (false on API 21–22 Android where the
     * NoOpWheelPasswordStore fallback is in use).
     */
    data class AwaitingPassword(
        val action: LockAction,
        val address: String,
        val prefilledPassword: String?,
        val canPersistPassword: Boolean,
    ) : LockPromptState()

    /** The user submitted; the wheel command is in flight. */
    data class Sending(
        val action: LockAction,
        val address: String,
    ) : LockPromptState()

    /** The wheel acknowledged. UI can dismiss the prompt. */
    data object Sent : LockPromptState()

    /**
     * The flow surfaced an error. [reason] is platform-agnostic so the UI
     * can pick the right localized message.
     */
    data class Error(
        val reason: ErrorReason,
        val action: LockAction,
        val address: String,
    ) : LockPromptState()

    enum class LockAction { LOCK, UNLOCK }

    enum class ErrorReason {
        /** Submitted password was blank or whitespace-only. */
        EMPTY_PASSWORD,

        /** Password didn't parse as a non-negative integer or exceeded the wire limit. */
        INVALID_FORMAT,

        /** BLE link dropped before the command could be dispatched. */
        NOT_CONNECTED,
    }

    companion object {
        /** Maximum value the 3-byte-BE wire password field can carry. */
        const val MAX_PASSWORD_VALUE: Int = 0xFFFFFF

        /**
         * Build the initial [AwaitingPassword] state for a lock or unlock
         * action against [address]. Looks up the saved password from [store]
         * (returns null on a no-op store) and uses [storeBacking] to set
         * [AwaitingPassword.canPersistPassword]. The platform's password-store
         * factory selects the backing once at app start; passing it in here
         * keeps the prompt from probing the store on every open.
         */
        fun start(
            action: LockAction,
            address: String,
            store: WheelPasswordStore,
            storeBacking: PasswordStorageBacking,
        ): AwaitingPassword = AwaitingPassword(
            action = action,
            address = address,
            prefilledPassword = store.getPassword(address),
            canPersistPassword = storeBacking == PasswordStorageBacking.SECURE,
        )

        /**
         * Validate [password] against the wire constraints. Returns null on
         * success; otherwise the [ErrorReason] the prompt should surface.
         */
        fun validate(password: String): ErrorReason? {
            if (password.isBlank()) return ErrorReason.EMPTY_PASSWORD
            val numeric = password.trim().toLongOrNull() ?: return ErrorReason.INVALID_FORMAT
            if (numeric < 0 || numeric > MAX_PASSWORD_VALUE.toLong()) return ErrorReason.INVALID_FORMAT
            return null
        }
    }
}

/**
 * Where a [WheelPasswordStore] actually persists the password. Selected by
 * the platform-specific factory (e.g. AndroidWheelPasswordStoreFactory) and
 * passed to [LockPromptState.start] so the prompt knows whether to offer a
 * "remember password" affordance at all.
 *
 * NONE matches the Android NoOpWheelPasswordStore fallback for API 21–22 and
 * the (test-only) InMemoryWheelPasswordStore. SECURE matches Keystore + AES-GCM
 * on Android 23+ and the iOS Keychain.
 */
enum class PasswordStorageBacking { SECURE, NONE }
