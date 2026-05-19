package org.freewheel.core.service

import org.freewheel.core.ble.BleAdvertisement
import org.freewheel.core.ble.WheelConnectionInfo
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

    /** Last attemptId stamped at [connect]. */
    var lastConnectAttemptId: Long = 0L
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

    override suspend fun connect(address: String, attemptId: Long): Boolean {
        lastConnectAddress = address
        lastConnectAttemptId = attemptId
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

    /** All [BleWriteRequest]s seen by [write] — preserves [BleWriteType] for assertions. */
    val writeRequests = mutableListOf<BleWriteRequest>()

    /**
     * Optional override hook. Tests opt in to control the [BleWriteResult]
     * returned by each call (e.g., to inject [BleWriteResult.Failed] for
     * retry assertions). When null, the fake returns
     * [BleWriteResult.Submitted] for WITHOUT_RESPONSE and
     * [BleWriteResult.Completed] for WITH_RESPONSE — matching today's
     * happy-path behavior.
     */
    var writeBehavior: ((BleWriteRequest) -> BleWriteResult)? = null

    override suspend fun write(request: BleWriteRequest): BleWriteResult {
        writeRequests.add(request)
        if (!isConnected) {
            return BleWriteResult.Failed("Not connected", latencyMs = 0)
        }
        if (requireConfigureBeforeWrite &&
            (lastConfigureForWheel == null || !configureForWheelResult)) {
            writesDroppedBeforeConfigure += 1
            return BleWriteResult.Failed("Not configured", latencyMs = 0)
        }
        writtenData.add(request.data.copyOf())
        writeBehavior?.let { return it(request) }
        return when (request.writeType) {
            BleWriteType.WITHOUT_RESPONSE -> BleWriteResult.Submitted(latencyMs = 0)
            BleWriteType.WITH_RESPONSE -> BleWriteResult.Completed(
                ack = BleWriteAck(
                    attemptId = lastConnectAttemptId,
                    success = true,
                    data = request.data.copyOf(),
                ),
                latencyMs = 0,
            )
        }
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

    /** Last configureForWheel call arguments (UUIDs), for test verification. */
    var lastConfigureForWheel: List<String>? = null
        private set

    /** Last full [WheelConnectionInfo] passed to [configureForWheel]. */
    var lastConfigureConnectionInfo: WheelConnectionInfo? = null
        private set

    /** Return value for configureForWheel. Default true (happy path). */
    var configureForWheelResult: Boolean = true

    override fun configureForWheel(connectionInfo: WheelConnectionInfo): Boolean {
        lastConfigureConnectionInfo = connectionInfo
        lastConfigureForWheel = listOf(
            connectionInfo.readServiceUuid,
            connectionInfo.readCharacteristicUuid,
            connectionInfo.writeServiceUuid,
            connectionInfo.writeCharacteristicUuid,
        )
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
