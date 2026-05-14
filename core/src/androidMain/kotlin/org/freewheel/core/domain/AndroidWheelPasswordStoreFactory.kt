package org.freewheel.core.domain

import android.content.SharedPreferences
import android.os.Build

/**
 * Pick the right [WheelPasswordStore] for the runtime Android version.
 *
 * `KeyGenParameterSpec` and the `AndroidKeyStore` AES-GCM provider used by
 * [KeystoreWheelPasswordStore] were introduced in API 23 (Marshmallow). The
 * project's `minSdk` is 21, so on API 21–22 devices the secure backing
 * isn't available and this factory returns [NoOpWheelPasswordStore] —
 * password persistence is silently disabled and the lock UX must prompt for
 * the password every time.
 */
object AndroidWheelPasswordStoreFactory {

    /** Minimum SDK required for the Keystore-backed implementation. */
    const val MIN_SECURE_STORAGE_SDK: Int = Build.VERSION_CODES.M

    /**
     * Returns the secure [KeystoreWheelPasswordStore] on API 23+, or a
     * [NoOpWheelPasswordStore] on older devices. Use [createWithBacking]
     * when the caller needs to render the "remember password" affordance
     * conditionally.
     */
    fun create(prefs: SharedPreferences): WheelPasswordStore =
        createWithBacking(prefs).store

    /**
     * Returns both the chosen [WheelPasswordStore] and the
     * [PasswordStorageBacking] that picked it. The Phase 5 lock prompt uses
     * the backing to decide whether the "remember password" toggle should
     * appear at all (see [LockPromptState.start]).
     */
    fun createWithBacking(prefs: SharedPreferences): WheelPasswordStoreSelection =
        if (Build.VERSION.SDK_INT >= MIN_SECURE_STORAGE_SDK) {
            WheelPasswordStoreSelection(KeystoreWheelPasswordStore(prefs), PasswordStorageBacking.SECURE)
        } else {
            WheelPasswordStoreSelection(NoOpWheelPasswordStore, PasswordStorageBacking.NONE)
        }
}

/**
 * [WheelPasswordStore] that refuses to persist anything. Used as the API <23
 * fallback because no secure on-device storage is available there.
 *
 * Calling [setPassword] is a silent no-op — passwords stored here vanish
 * immediately. [getPassword] always returns null and [hasPassword] always
 * returns false. Lock UX layered on top must treat this as "user must
 * re-enter the password each time" rather than as an error.
 */
object NoOpWheelPasswordStore : WheelPasswordStore {
    override fun getPassword(address: String): String? = null
    override fun hasPassword(address: String): Boolean = false
    override fun setPassword(address: String, password: String) = Unit
    override fun clearPassword(address: String) = Unit
    override fun clearAll() = Unit
}
