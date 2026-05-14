package org.freewheel.core.domain

/**
 * Pick the [WheelPasswordStore] for iOS. Keychain Services is available on
 * every iOS version this project deploys to, so the backing is always
 * [PasswordStorageBacking.SECURE]; the factory exists to keep that
 * decision out of the Swift bridge layer (mirrors the Android factory).
 */
object IosWheelPasswordStoreFactory {

    /**
     * Returns a Keychain-backed [WheelPasswordStore] paired with its
     * [PasswordStorageBacking] label so the lock prompt can decide whether
     * to render the "remember password" affordance without inspecting the
     * store's identity.
     */
    fun createWithBacking(
        serviceName: String = KeychainWheelPasswordStore.DEFAULT_SERVICE,
    ): WheelPasswordStoreSelection = WheelPasswordStoreSelection(
        store = KeychainWheelPasswordStore(serviceName),
        backing = PasswordStorageBacking.SECURE,
    )
}
