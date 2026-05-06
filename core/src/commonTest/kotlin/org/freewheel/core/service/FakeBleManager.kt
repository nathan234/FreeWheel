package org.freewheel.core.service

import org.freewheel.core.ble.BleAdvertisement
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

    /** Number of times [destroy] was called. */
    var destroyCallCount = 0
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

    /**
     * When true, [write] returns false until [configureForWheel] has been called
     * with a successful result — mirroring the real Android/iOS BleManager, where
     * `write()` requires the write characteristic to be bound. Off by default so
     * legacy tests that exercise the SendBytes path without service discovery
     * stay passing; opt in for tests that need to prove init dispatch waits for
     * BLE configuration.
     */
    var requireConfigureBeforeWrite: Boolean = false

    /** Number of write() calls that returned false because configure hadn't run yet. */
    var writesDroppedBeforeConfigure: Int = 0
        private set

    override suspend fun write(data: ByteArray): Boolean {
        if (!isConnected) return false
        if (requireConfigureBeforeWrite &&
            (lastConfigureForWheel == null || !configureForWheelResult)) {
            writesDroppedBeforeConfigure += 1
            return false
        }
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

    /** Return value for configureForWheel. Default true (happy path). */
    var configureForWheelResult: Boolean = true

    override fun configureForWheel(
        readServiceUuid: String,
        readCharUuid: String,
        writeServiceUuid: String,
        writeCharUuid: String
    ): Boolean {
        lastConfigureForWheel = listOf(readServiceUuid, readCharUuid, writeServiceUuid, writeCharUuid)
        return configureForWheelResult
    }

    /** Simulate connection state changes for testing. */
    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    /** Per-address advertisement evidence to be returned by [getAdvertisement]. */
    val advertisements: MutableMap<String, BleAdvertisement> = mutableMapOf()

    override fun getAdvertisement(address: String): BleAdvertisement? =
        advertisements[address]

    override fun destroy() {
        destroyCallCount++
    }

    /** Clear recorded data. */
    fun clearWrittenData() {
        writtenData.clear()
    }
}
