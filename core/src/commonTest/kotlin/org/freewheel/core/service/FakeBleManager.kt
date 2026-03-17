package org.freewheel.core.service

import kotlinx.coroutines.CompletableDeferred
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

    /** When non-null, connect() suspends until this deferred completes. */
    var connectDeferred: CompletableDeferred<Boolean>? = null

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

        // If a deferred is set, suspend until it completes (simulates pending BLE connect)
        val deferred = connectDeferred
        if (deferred != null) {
            val result = deferred.await()
            if (result) {
                isConnected = true
                _connectionState.value = ConnectionState.Connecting(address)
            }
            return result
        }

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

    /** Number of times [startScan] was called. */
    var startScanCallCount = 0
        private set

    override suspend fun startScan(onDeviceFound: (BleDevice) -> Unit) {
        startScanCallCount++
        _connectionState.value = ConnectionState.Scanning
    }

    override suspend fun stopScan() {
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    /** Last configureForWheel call arguments, for test verification. */
    var lastConfigureForWheel: List<String>? = null
        private set

    override fun configureForWheel(
        readServiceUuid: String,
        readCharUuid: String,
        writeServiceUuid: String,
        writeCharUuid: String
    ) {
        lastConfigureForWheel = listOf(readServiceUuid, readCharUuid, writeServiceUuid, writeCharUuid)
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
