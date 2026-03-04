package org.freewheel.core.domain

/**
 * Represents a saved/known wheel for the "My Wheels" garage.
 * Pure value class — persistence is handled by platform-specific stores.
 */
data class WheelProfile(
    /** BLE MAC address (Android) or CBPeripheral UUID string (iOS). */
    val address: String,
    /** User-set or auto-detected model name (e.g., "KS-S18", "V12 HT"). */
    val displayName: String,
    /** WheelType enum name for icon/hint (e.g., "KINGSONG"), empty if unknown. */
    val wheelTypeName: String,
    /** Epoch millis of last successful connection, used for sorting. */
    val lastConnectedMs: Long
)
