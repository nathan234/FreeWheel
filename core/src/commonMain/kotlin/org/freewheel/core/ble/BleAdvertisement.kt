package org.freewheel.core.ble

/**
 * Snapshot of a BLE advertisement captured during scanning.
 *
 * Carries enough evidence for downstream wheel-type fingerprinting (manufacturer
 * data, advertised services, advertised name) without forcing the BLE callback
 * path to know about wheel detection. Cached per-address by [BleAdvertisementCache]
 * and consumed when [org.freewheel.core.service.WheelConnectionManager.connect]
 * fires — see the Pass 1.5 plan for the topology-fingerprint matcher that reads it.
 *
 * UUID strings are canonicalized via [BleUuids.canonicalize] (lowercase, 128-bit form)
 * so platform-specific short forms ("FFE0" on iOS, full UUIDs on Android) compare
 * equal across platforms.
 */
class BleAdvertisement(
    val address: String,
    val advertisedName: String?,
    val peripheralName: String?,
    val rssi: Int,
    val advertisedServiceUuids: Set<String>,
    val manufacturerData: Map<Int, ByteArray>,
    val serviceData: Map<String, ByteArray>,
    val connectable: Boolean,
    val lastSeenMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleAdvertisement) return false
        return address == other.address
            && advertisedName == other.advertisedName
            && peripheralName == other.peripheralName
            && rssi == other.rssi
            && advertisedServiceUuids == other.advertisedServiceUuids
            && manufacturerData.byteArrayMapEquals(other.manufacturerData)
            && serviceData.byteArrayMapEquals(other.serviceData)
            && connectable == other.connectable
            && lastSeenMs == other.lastSeenMs
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + (advertisedName?.hashCode() ?: 0)
        result = 31 * result + (peripheralName?.hashCode() ?: 0)
        result = 31 * result + rssi
        result = 31 * result + advertisedServiceUuids.hashCode()
        result = 31 * result + manufacturerData.byteArrayMapHashCode()
        result = 31 * result + serviceData.byteArrayMapHashCode()
        result = 31 * result + connectable.hashCode()
        result = 31 * result + lastSeenMs.hashCode()
        return result
    }
}

private fun <K> Map<K, ByteArray>.byteArrayMapEquals(other: Map<K, ByteArray>): Boolean {
    if (size != other.size) return false
    for ((k, v) in this) {
        val ov = other[k] ?: return false
        if (!v.contentEquals(ov)) return false
    }
    return true
}

private fun <K> Map<K, ByteArray>.byteArrayMapHashCode(): Int {
    var result = 0
    for ((k, v) in this) {
        result += (k?.hashCode() ?: 0) xor v.contentHashCode()
    }
    return result
}
