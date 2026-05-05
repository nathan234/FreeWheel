package org.freewheel.core.ble

import org.freewheel.core.domain.WheelType

/**
 * Detects wheel type based on discovered BLE services and characteristics.
 *
 * The detection logic is based on unique service/characteristic combinations
 * that distinguish each manufacturer's protocol:
 *
 * - **InMotion V1**: Has ffe0/ffe4 (read) AND ffe5/ffe9 (write) - separate services
 * - **InMotion V2**: Has Nordic UART (6e400001) AND ffe0/ffe4 characteristics
 * - **Ninebot Z**: Has Nordic UART (6e400001) only (no ffe0)
 * - **Kingsong**: Has fff0 service OR ffe0/ffe1 with specific characteristics
 * - **Gotway/Veteran**: Has ffe0/ffe1 only (simplest profile)
 * - **Ninebot**: Has ffe0/ffe1 (similar to Gotway, differentiated by name)
 *
 * Note: Some wheel types share similar UUIDs and require additional
 * heuristics like device name patterns for accurate detection.
 */
class WheelTypeDetector {

    /**
     * Result of wheel type detection.
     */
    sealed class DetectionResult {
        /**
         * Successfully detected wheel type.
         */
        data class Detected(
            val wheelType: WheelType,
            val readServiceUuid: String,
            val readCharacteristicUuid: String,
            val writeServiceUuid: String,
            val writeCharacteristicUuid: String,
            val confidence: Confidence = Confidence.HIGH
        ) : DetectionResult()

        /**
         * Could not determine wheel type.
         */
        data class Unknown(val reason: String) : DetectionResult()

        /**
         * Multiple possible wheel types detected (ambiguous).
         */
        data class Ambiguous(
            val possibleTypes: List<WheelType>,
            val reason: String
        ) : DetectionResult()
    }

    /**
     * Confidence level of detection.
     */
    enum class Confidence {
        HIGH,   // Unique service combination, very reliable
        MEDIUM, // Common services but distinguishing characteristics present
        LOW     // May need device name or data to confirm
    }

    /**
     * Detect wheel type from discovered services.
     *
     * @param services The discovered BLE services
     * @param deviceName Optional device name for additional heuristics
     * @return Detection result with wheel type and connection info
     */
    fun detect(services: DiscoveredServices, deviceName: String? = null): DetectionResult {
        // First: try to detect from device name (most reliable when available).
        // Many wheels share the same service UUIDs (e.g. both Gotway and KingSong
        // can have fff0), but device names are unambiguous.
        val nameResult = detectFromName(deviceName)
        if (nameResult != null) return nameResult

        // Check for Nordic UART service (used by InMotion V2 and Ninebot Z)
        val hasNordicUart = services.hasService(BleUuids.InMotionV2.SERVICE)

        // Check for standard wheel service (ffe0)
        val hasStandardService = services.hasService(BleUuids.Gotway.SERVICE)

        // Check for InMotion-specific write service (ffe5)
        val hasInMotionWriteService = services.hasService(BleUuids.InMotion.WRITE_SERVICE)

        // Check for specific characteristics
        val hasInMotionReadChar = services.hasCharacteristic(
            BleUuids.InMotion.READ_SERVICE,
            BleUuids.InMotion.READ_CHARACTERISTIC
        )
        val hasInMotionWriteChar = services.hasCharacteristic(
            BleUuids.InMotion.WRITE_SERVICE,
            BleUuids.InMotion.WRITE_CHARACTERISTIC
        )

        return when {
            // InMotion V2: Has Nordic UART AND standard ffe0 with ffe4 characteristic
            hasNordicUart && hasInMotionReadChar -> {
                DetectionResult.Detected(
                    wheelType = WheelType.INMOTION_V2,
                    readServiceUuid = BleUuids.InMotionV2.SERVICE,
                    readCharacteristicUuid = BleUuids.InMotionV2.READ_CHARACTERISTIC,
                    writeServiceUuid = BleUuids.InMotionV2.SERVICE,
                    writeCharacteristicUuid = BleUuids.InMotionV2.WRITE_CHARACTERISTIC,
                    confidence = Confidence.HIGH
                )
            }

            // Ninebot Z: Has Nordic UART only (no ffe0 with ffe4)
            hasNordicUart && !hasInMotionReadChar -> {
                DetectionResult.Detected(
                    wheelType = WheelType.NINEBOT_Z,
                    readServiceUuid = BleUuids.NinebotZ.SERVICE,
                    readCharacteristicUuid = BleUuids.NinebotZ.READ_CHARACTERISTIC,
                    writeServiceUuid = BleUuids.NinebotZ.SERVICE,
                    writeCharacteristicUuid = BleUuids.NinebotZ.WRITE_CHARACTERISTIC,
                    confidence = Confidence.HIGH
                )
            }

            // InMotion V1: Has separate read (ffe0/ffe4) and write (ffe5/ffe9) services
            hasInMotionWriteService && hasInMotionWriteChar && hasInMotionReadChar -> {
                DetectionResult.Detected(
                    wheelType = WheelType.INMOTION,
                    readServiceUuid = BleUuids.InMotion.READ_SERVICE,
                    readCharacteristicUuid = BleUuids.InMotion.READ_CHARACTERISTIC,
                    writeServiceUuid = BleUuids.InMotion.WRITE_SERVICE,
                    writeCharacteristicUuid = BleUuids.InMotion.WRITE_CHARACTERISTIC,
                    confidence = Confidence.HIGH
                )
            }

            // Standard service (ffe0/ffe1) - Could be Gotway, Veteran, Ninebot, or KingSong
            hasStandardService -> {
                // Ambiguous — fall back to auto-detect decoder with Gotway UUIDs
                DetectionResult.Ambiguous(
                    possibleTypes = listOf(
                        WheelType.GOTWAY,
                        WheelType.KINGSONG,
                        WheelType.NINEBOT
                    ),
                    reason = "Standard BLE profile detected. Device name '$deviceName' " +
                            "does not match known patterns. Will use auto-detection."
                )
            }

            // No recognized services
            else -> {
                DetectionResult.Unknown(
                    "No recognized wheel services found. Services: ${services.serviceUuids()}"
                )
            }
        }
    }

    /**
     * Try to detect wheel type from device name alone.
     * Returns null if name doesn't match any known pattern.
     */
    private fun detectFromName(deviceName: String?): DetectionResult? {
        val type = deriveTypeFromName(deviceName) ?: return null
        val info = WheelConnectionInfo.forType(type) ?: return null
        return DetectionResult.Detected(
            wheelType = info.wheelType,
            readServiceUuid = info.readServiceUuid,
            readCharacteristicUuid = info.readCharacteristicUuid,
            writeServiceUuid = info.writeServiceUuid,
            writeCharacteristicUuid = info.writeCharacteristicUuid,
            confidence = Confidence.HIGH
        )
    }

    companion object {
        /**
         * Derive wheel type from device name patterns alone.
         *
         * Public so the iOS scan-time path can pass a wheel-type hint into
         * `WheelConnectionManager.connect(address, wheelType)` before service
         * discovery completes. Returns null if the name doesn't match any
         * known pattern (caller should fall through to topology-based detection).
         */
        fun deriveTypeFromName(deviceName: String?): WheelType? {
            val name = deviceName?.uppercase() ?: return null
            if (name.isEmpty()) return null

            return when {
                // Veteran/Leaperkim patterns (legacy DC 5A 5C protocol).
                // Official Leaperkim app filters by "LK" prefix; all known LK wheels
                // use the legacy Veteran protocol, not CAN-over-BLE.
                name.contains("LEAPERKIM") ||
                name.contains("LPKIM") ||
                name.startsWith("LK") ||
                name.contains("VETERAN") ||
                name.contains("SHERMAN") ||
                name.contains("LYNX") ||
                name.contains("PATTON") ||
                name.contains("ABRAMS") ||
                name.contains("ORYX") ||
                name.contains("NOSFET") ||
                name.startsWith("NF") -> WheelType.VETERAN

                // InMotion V2 patterns (before Gotway to avoid conflicts with names like "MASTER")
                name.startsWith("V11") || name.startsWith("V12") || name.startsWith("V13") ||
                name.startsWith("V14") || name.startsWith("V9") || name.startsWith("P6") ||
                name.startsWith("E20") || name.startsWith("CLIMBER") || name.startsWith("GLIDE") ||
                name.contains("INMOTION") -> WheelType.INMOTION_V2

                // Gotway/Begode patterns
                name.contains("GOTWAY") ||
                name.startsWith("GW") ||
                name.contains("BEGODE") ||
                name.contains("MCMASTER") ||
                name.contains("NIKOLA") ||
                name.contains("MONSTER") ||
                name.contains("MSP") ||
                name.contains("RSHS") ||
                name.contains("EX.N") ||
                name.contains("HERO") ||
                name.contains("MASTER") -> WheelType.GOTWAY

                // KingSong patterns
                name.contains("KS-") ||
                name.contains("KINGSONG") ||
                name.startsWith("KS") -> WheelType.KINGSONG

                // Ninebot patterns
                name.contains("NINEBOT") ||
                name.contains("NB-") -> WheelType.NINEBOT

                else -> null
            }
        }
    }
}
