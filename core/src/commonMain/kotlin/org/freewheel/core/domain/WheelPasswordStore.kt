package org.freewheel.core.domain

/**
 * Per-wheel password storage for Veteran lock/unlock.
 *
 * The official Leaperkim app's lock flow uses a numeric password (3-byte BE
 * integer) embedded in the [WheelCommand.SetVeteranLock] payload. To match
 * the app's UX of "remembered" wheels, FreeWheel needs to store these
 * passwords per-MAC across launches.
 *
 * **Security boundary**: passwords here protect a Bluetooth lock that
 * gates motor power on a personally-owned vehicle. They are not used as
 * authentication for any FreeWheel service or remote system, but losing a
 * password locks the user out of their wheel until factory reset. Treat the
 * store as security-sensitive: production implementations MUST use
 * platform-secure storage (Android Keystore-derived key + AES-GCM, or iOS
 * Keychain). A plain-prefs implementation would leak passwords to any
 * process that can read app private storage.
 *
 * The companion [InMemoryWheelPasswordStore] is for tests only.
 */
interface WheelPasswordStore {

    /** Returns the stored password for [address], or null if none is stored. */
    fun getPassword(address: String): String?

    /** True if a non-blank password is stored for [address]. */
    fun hasPassword(address: String): Boolean

    /**
     * Store [password] for [address]. Passing a blank string is equivalent to
     * [clearPassword] — the store does not retain empty entries.
     */
    fun setPassword(address: String, password: String)

    /** Remove any stored password for [address]. No-op if none was stored. */
    fun clearPassword(address: String)

    /** Drop every stored password. Used by "forget all wheels" / debug flows. */
    fun clearAll()
}

/**
 * In-memory [WheelPasswordStore] for unit tests. NEVER use in production —
 * passwords vanish on process death and live unencrypted in the heap.
 */
class InMemoryWheelPasswordStore : WheelPasswordStore {
    private val storage = mutableMapOf<String, String>()

    override fun getPassword(address: String): String? = storage[address]

    override fun hasPassword(address: String): Boolean =
        storage[address]?.isNotBlank() == true

    override fun setPassword(address: String, password: String) {
        if (password.isBlank()) {
            storage.remove(address)
        } else {
            storage[address] = password
        }
    }

    override fun clearPassword(address: String) {
        storage.remove(address)
    }

    override fun clearAll() {
        storage.clear()
    }
}
