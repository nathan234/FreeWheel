package org.freewheel.core.logging

import org.freewheel.core.domain.identity.CapabilitySet
import org.freewheel.core.domain.identity.WheelType

/**
 * Privacy-safe snapshot of wheel identity, capabilities, BLE config, and decoder config.
 * Used as a diagnostic footer in BLE captures and for clipboard sharing.
 *
 * No MAC address or serial number — only the BT name prefix (serial suffix stripped).
 */
data class DiagnosticSnapshot(
    // Identity
    val wheelType: WheelType,
    val detectedModel: String,
    val btNamePrefix: String,
    val firmwareVersion: String,
    val firmwareLevel: Int,

    // Capabilities
    val capabilitiesResolved: Boolean,
    val supportedCommands: List<String>,

    // BLE UUIDs
    val readServiceUuid: String,
    val readCharacteristicUuid: String,
    val writeServiceUuid: String,
    val writeCharacteristicUuid: String,

    // Decoder config (protocol-relevant fields only)
    val gotwayNegative: Int,
    val useRatio: Boolean,
    val gotwayVoltage: Int,
    val hwPwmEnabled: Boolean,
    val autoVoltage: Boolean,

    // Context
    val platform: String,
    val appVersion: String
)
