package org.freewheel.core.service

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic interface for BLE communication.
 * Extracted from [BleManager] to enable testing with fakes in commonTest.
 *
 * Platform implementations ([BleManager]) implement this interface.
 * Test implementations provide controllable behavior for lifecycle tests.
 */
interface BleManagerPort {
    val connectionState: StateFlow<ConnectionState>

    /** Bluetooth adapter state (power, permission). Separate from connection lifecycle. */
    val bluetoothState: StateFlow<BluetoothAdapterState>
        get() = kotlinx.coroutines.flow.MutableStateFlow(BluetoothAdapterState.POWERED_ON)

    /**
     * Connect to a BLE device at the given address.
     * @return true if the connection was established successfully
     */
    suspend fun connect(address: String): Boolean

    /**
     * Disconnect from the current device.
     */
    suspend fun disconnect()

    /**
     * Write data to the connected device.
     * @return true if the write was successful
     */
    suspend fun write(data: ByteArray): Boolean

    /**
     * Start scanning for BLE devices.
     */
    suspend fun startScan(onDeviceFound: (BleDevice) -> Unit)

    /**
     * Stop scanning for BLE devices.
     */
    suspend fun stopScan()

    /**
     * Configure which BLE service/characteristic UUIDs to use for read and write.
     * Called after wheel type detection to set up the correct characteristics
     * and enable notifications.
     *
     * @return true if the read characteristic was bound (notifications enabled).
     *         false if the underlying service or characteristic was missing —
     *         the caller should treat the connection as Failed rather than wait
     *         indefinitely for data that will never arrive.
     */
    fun configureForWheel(
        readServiceUuid: String,
        readCharUuid: String,
        writeServiceUuid: String,
        writeCharUuid: String
    ): Boolean = true

    /**
     * Start scanning for BLE devices advertising a specific service UUID.
     * Default delegates to [startScan] (ignoring the filter).
     */
    suspend fun startScanForService(serviceUuid: String, onDeviceFound: (BleDevice) -> Unit) {
        startScan(onDeviceFound)
    }

    /**
     * Update the adapter-level Bluetooth state (power, permissions).
     * Default is a no-op; platform implementations track this for reconnect logic.
     */
    fun setBluetoothAdapterState(state: BluetoothAdapterState) {}

    /**
     * Release platform resources (threads, broadcast receivers, coroutine scopes).
     * Called once after the event loop has drained. After this call the instance
     * must not be reused.
     */
    fun destroy() {}
}
