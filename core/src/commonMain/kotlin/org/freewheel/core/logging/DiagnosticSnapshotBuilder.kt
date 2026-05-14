package org.freewheel.core.logging

import org.freewheel.core.ble.WheelConnectionInfo
import org.freewheel.core.domain.identity.CapabilitySet
import org.freewheel.core.domain.identity.WheelIdentity
import org.freewheel.core.protocol.DecoderConfig

/**
 * Builds [DiagnosticSnapshot] from runtime state and formats it for output.
 *
 * Accessible from Swift as `DiagnosticSnapshotBuilder.shared`.
 */
object DiagnosticSnapshotBuilder {

    /**
     * Build a diagnostic snapshot from wheel identity and config.
     *
     * @param identity Wheel identity (type, model, BT name, version).
     * @param capabilities Current resolved capabilities.
     * @param connectionInfo BLE connection info, or null if not connected.
     * @param decoderConfig Current decoder configuration.
     * @param platform "android" or "ios".
     * @param appVersion App version string.
     */
    fun buildSnapshot(
        identity: WheelIdentity,
        capabilities: CapabilitySet,
        connectionInfo: WheelConnectionInfo?,
        decoderConfig: DecoderConfig,
        platform: String,
        appVersion: String
    ): DiagnosticSnapshot = DiagnosticSnapshot(
        wheelType = identity.wheelType,
        detectedModel = capabilities.detectedModel.ifEmpty { identity.model },
        btNamePrefix = sanitizeBtName(identity.btName),
        firmwareVersion = capabilities.firmwareVersion.ifEmpty { identity.version },
        firmwareLevel = capabilities.firmwareLevel,
        capabilitiesResolved = capabilities.isResolved,
        supportedCommands = capabilities.supportedCommands.map { it.name }.sorted(),
        readServiceUuid = connectionInfo?.readServiceUuid ?: "",
        readCharacteristicUuid = connectionInfo?.readCharacteristicUuid ?: "",
        writeServiceUuid = connectionInfo?.writeServiceUuid ?: "",
        writeCharacteristicUuid = connectionInfo?.writeCharacteristicUuid ?: "",
        gotwayNegative = decoderConfig.gotwayNegative,
        useRatio = decoderConfig.useRatio,
        gotwayVoltage = decoderConfig.gotwayVoltage,
        hwPwmEnabled = decoderConfig.hwPwmEnabled,
        autoVoltage = decoderConfig.autoVoltage,
        platform = platform,
        appVersion = appVersion
    )

    /**
     * Format snapshot as CSV comment lines for appending to a capture file.
     * Each line starts with `#` so CSV parsers skip them.
     */
    fun formatAsCommentBlock(snapshot: DiagnosticSnapshot): String = buildString {
        appendLine("# --- Diagnostic Info ---")
        appendLine("# wheel_type: ${snapshot.wheelType.name}")
        appendLine("# detected_model: ${snapshot.detectedModel}")
        appendLine("# bt_name_prefix: ${snapshot.btNamePrefix}")
        appendLine("# firmware_version: ${snapshot.firmwareVersion}")
        appendLine("# firmware_level: ${snapshot.firmwareLevel}")
        appendLine("# capabilities_resolved: ${snapshot.capabilitiesResolved}")
        appendLine("# supported_commands: ${snapshot.supportedCommands.joinToString(", ")}")
        appendLine("# read_service_uuid: ${snapshot.readServiceUuid}")
        appendLine("# read_characteristic_uuid: ${snapshot.readCharacteristicUuid}")
        appendLine("# write_service_uuid: ${snapshot.writeServiceUuid}")
        appendLine("# write_characteristic_uuid: ${snapshot.writeCharacteristicUuid}")
        appendLine("# gotway_negative: ${snapshot.gotwayNegative}")
        appendLine("# use_ratio: ${snapshot.useRatio}")
        appendLine("# gotway_voltage: ${snapshot.gotwayVoltage}")
        appendLine("# hw_pwm_enabled: ${snapshot.hwPwmEnabled}")
        appendLine("# auto_voltage: ${snapshot.autoVoltage}")
        appendLine("# platform: ${snapshot.platform}")
        append("# app_version: ${snapshot.appVersion}")
    }

    /**
     * Format snapshot as human-readable plain text for clipboard sharing.
     */
    fun formatAsText(snapshot: DiagnosticSnapshot): String = buildString {
        appendLine("FreeWheel Diagnostic Info")
        appendLine()
        appendLine("Wheel Type: ${snapshot.wheelType.name}")
        appendLine("Detected Model: ${snapshot.detectedModel}")
        appendLine("BT Name: ${snapshot.btNamePrefix}")
        appendLine("Firmware: ${snapshot.firmwareVersion}")
        appendLine("Firmware Level: ${snapshot.firmwareLevel}")
        appendLine()
        appendLine("Capabilities Resolved: ${snapshot.capabilitiesResolved}")
        appendLine("Supported Commands: ${snapshot.supportedCommands.joinToString(", ")}")
        appendLine()
        appendLine("BLE Read Service: ${snapshot.readServiceUuid}")
        appendLine("BLE Read Characteristic: ${snapshot.readCharacteristicUuid}")
        appendLine("BLE Write Service: ${snapshot.writeServiceUuid}")
        appendLine("BLE Write Characteristic: ${snapshot.writeCharacteristicUuid}")
        appendLine()
        appendLine("Decoder Config:")
        appendLine("  Gotway Negative: ${snapshot.gotwayNegative}")
        appendLine("  Use Ratio: ${snapshot.useRatio}")
        appendLine("  Gotway Voltage: ${snapshot.gotwayVoltage}")
        appendLine("  HW PWM Enabled: ${snapshot.hwPwmEnabled}")
        appendLine("  Auto Voltage: ${snapshot.autoVoltage}")
        appendLine()
        appendLine("Platform: ${snapshot.platform}")
        append("App Version: ${snapshot.appVersion}")
    }

    /**
     * Strip serial-number-like suffixes from BT names.
     * Strips after `-` or `_` if the suffix has 4+ digits.
     * Examples: "P6-50002437" → "P6", "KS-14D" → "KS-14D", "LPKIM1234567" → "LPKIM1234567"
     */
    internal fun sanitizeBtName(btName: String): String {
        if (btName.isEmpty()) return ""
        // Look for separator followed by a suffix with 4+ digits
        val separatorIndex = btName.lastIndexOfAny(charArrayOf('-', '_'))
        if (separatorIndex > 0) {
            val suffix = btName.substring(separatorIndex + 1)
            val digitCount = suffix.count { it.isDigit() }
            if (digitCount >= 4) {
                return btName.substring(0, separatorIndex)
            }
        }
        return btName
    }
}
