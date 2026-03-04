package org.freewheel.core.ble

/**
 * BLE Service and Characteristic UUIDs for all supported wheel manufacturers.
 *
 * Each wheel type has specific UUIDs for:
 * - Service discovery (identifying the wheel type)
 * - Read characteristic (receiving telemetry data)
 * - Write characteristic (sending commands)
 * - Descriptor (enabling notifications)
 */
object BleUuids {

    // Standard BLE UUID format suffix
    private const val BLE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    // Standard descriptor for enabling notifications
    const val CLIENT_CHARACTERISTIC_CONFIG = "00002902$BLE_UUID_SUFFIX"

    // ==================== KingSong ====================

    object Kingsong {
        const val SERVICE = "0000ffe0$BLE_UUID_SUFFIX"
        const val READ_CHARACTERISTIC = "0000ffe1$BLE_UUID_SUFFIX"
        const val WRITE_CHARACTERISTIC = "0000ffe1$BLE_UUID_SUFFIX" // Same as read
        const val DESCRIPTOR = CLIENT_CHARACTERISTIC_CONFIG
    }

    // ==================== Gotway/Begode/Veteran ====================

    object Gotway {
        const val SERVICE = "0000ffe0$BLE_UUID_SUFFIX"
        const val READ_CHARACTERISTIC = "0000ffe1$BLE_UUID_SUFFIX"
        const val WRITE_CHARACTERISTIC = "0000ffe1$BLE_UUID_SUFFIX" // Same as read
    }

    // ==================== InMotion V1 ====================

    object InMotion {
        const val READ_SERVICE = "0000ffe0$BLE_UUID_SUFFIX"
        const val READ_CHARACTERISTIC = "0000ffe4$BLE_UUID_SUFFIX"
        const val WRITE_SERVICE = "0000ffe5$BLE_UUID_SUFFIX"
        const val WRITE_CHARACTERISTIC = "0000ffe9$BLE_UUID_SUFFIX"
        const val DESCRIPTOR = CLIENT_CHARACTERISTIC_CONFIG
    }

    // ==================== InMotion V2 (Nordic UART) ====================

    object InMotionV2 {
        const val SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        const val WRITE_CHARACTERISTIC = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        const val READ_CHARACTERISTIC = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
        const val DESCRIPTOR = CLIENT_CHARACTERISTIC_CONFIG
    }

    // ==================== Ninebot ====================

    object Ninebot {
        const val SERVICE = "0000ffe0$BLE_UUID_SUFFIX"
        const val READ_CHARACTERISTIC = "0000ffe1$BLE_UUID_SUFFIX"
        const val WRITE_CHARACTERISTIC = "0000ffe1$BLE_UUID_SUFFIX" // Same as read
        const val DESCRIPTOR = CLIENT_CHARACTERISTIC_CONFIG
    }

    // ==================== Ninebot Z (Nordic UART) ====================

    object NinebotZ {
        const val SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        const val WRITE_CHARACTERISTIC = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        const val READ_CHARACTERISTIC = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
        const val DESCRIPTOR = CLIENT_CHARACTERISTIC_CONFIG
    }

    // ==================== Standard BLE Services ====================

    object StandardServices {
        const val GENERIC_ACCESS = "00001800$BLE_UUID_SUFFIX"
        const val GENERIC_ATTRIBUTE = "00001801$BLE_UUID_SUFFIX"
        const val DEVICE_INFORMATION = "0000180a$BLE_UUID_SUFFIX"
        const val BATTERY_SERVICE = "0000180f$BLE_UUID_SUFFIX"
    }

    // ==================== Helper Functions ====================

    /**
     * Normalize a UUID string to lowercase for comparison.
     */
    fun normalize(uuid: String): String = uuid.lowercase()

    /**
     * Check if two UUIDs match (case-insensitive).
     */
    fun matches(uuid1: String, uuid2: String): Boolean =
        normalize(uuid1) == normalize(uuid2)
}

/**
 * Represents a discovered BLE service with its characteristics.
 */
data class DiscoveredService(
    val uuid: String,
    val characteristics: List<String>
) {
    /**
     * Check if this service has a specific characteristic.
     */
    fun hasCharacteristic(uuid: String): Boolean =
        characteristics.any { BleUuids.matches(it, uuid) }

    /**
     * Check if the service UUID matches.
     */
    fun matchesService(uuid: String): Boolean =
        BleUuids.matches(this.uuid, uuid)
}

/**
 * Represents a complete set of discovered services from a BLE peripheral.
 */
data class DiscoveredServices(
    val services: List<DiscoveredService>
) {
    /**
     * Find a service by UUID.
     */
    fun findService(uuid: String): DiscoveredService? =
        services.find { it.matchesService(uuid) }

    /**
     * Check if a specific service exists.
     */
    fun hasService(uuid: String): Boolean =
        findService(uuid) != null

    /**
     * Check if a service has a specific characteristic.
     */
    fun hasCharacteristic(serviceUuid: String, charUuid: String): Boolean =
        findService(serviceUuid)?.hasCharacteristic(charUuid) == true

    /**
     * Get all service UUIDs.
     */
    fun serviceUuids(): List<String> = services.map { it.uuid }
}
