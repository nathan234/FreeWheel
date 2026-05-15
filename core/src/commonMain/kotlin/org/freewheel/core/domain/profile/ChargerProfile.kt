package org.freewheel.core.domain.profile

/**
 * Represents a saved HW charger for the charger profile list.
 * Pure value class — persistence is handled by [ChargerProfileStore].
 */
data class ChargerProfile(
    /** BLE MAC address (Android) or CBPeripheral UUID string (iOS). */
    val address: String,
    /** User-set display name (e.g., "HW Charger"). */
    val displayName: String = "",
    /** BLE connection password. */
    val password: String = "",
    /** Epoch millis of last successful connection, used for sorting. */
    val lastConnectedMs: Long = 0
)
