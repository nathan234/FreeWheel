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
}
