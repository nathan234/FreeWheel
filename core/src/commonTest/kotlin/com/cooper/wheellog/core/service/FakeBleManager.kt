package com.cooper.wheellog.core.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake BLE manager for testing [WheelConnectionManager] lifecycle.
 * Records written data and allows configuring connection outcomes.
 */
class FakeBleManager : BleManagerPort {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** All data written via [write]. Each entry is a copy of the written bytes. */
    val writtenData = mutableListOf<ByteArray>()

    /** Whether [connect] should return true (success). */
    var connectResult = true

    /** Whether the fake is currently "connected". */
    var isConnected = false
        private set

    /** Last address passed to [connect]. */
    var lastConnectAddress: String? = null
        private set

    /** Number of times [connect] was called. */
    var connectCallCount = 0
        private set

    /** Number of times [disconnect] was called. */
    var disconnectCallCount = 0
        private set

    override suspend fun connect(address: String): Boolean {
        lastConnectAddress = address
        connectCallCount++
        if (connectResult) {
            isConnected = true
            _connectionState.value = ConnectionState.Connecting(address)
        }
        return connectResult
    }

    override suspend fun disconnect() {
        disconnectCallCount++
        isConnected = false
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun write(data: ByteArray): Boolean {
        if (!isConnected) return false
        writtenData.add(data.copyOf())
        return true
    }

    override suspend fun startScan(onDeviceFound: (BleDevice) -> Unit) {
        _connectionState.value = ConnectionState.Scanning
    }

    override suspend fun stopScan() {
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /** Simulate connection state changes for testing. */
    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    /** Clear recorded data. */
    fun clearWrittenData() {
        writtenData.clear()
    }
}
