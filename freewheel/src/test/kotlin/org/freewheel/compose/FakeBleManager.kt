package org.freewheel.compose

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.freewheel.core.service.BleDevice
import org.freewheel.core.service.BleManagerPort
import org.freewheel.core.service.BleWriteRequest
import org.freewheel.core.service.BleWriteResult
import org.freewheel.core.service.BleWriteType
import org.freewheel.core.service.BleWriteAck
import org.freewheel.core.service.BluetoothAdapterState
import org.freewheel.core.service.ConnectionState

/**
 * Fake BLE manager for ViewModel integration tests.
 * Records interactions so tests can assert on them without mocking.
 */
class FakeBleManager : BleManagerPort {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    var connectCallCount = 0
        private set
    var lastConnectAddress: String? = null
        private set
    var connectResult = true

    var startScanCallCount = 0
        private set
    var disconnectCallCount = 0
        private set

    var lastConnectAttemptId: Long = 0L
        private set

    override suspend fun connect(address: String, attemptId: Long): Boolean {
        connectCallCount++
        lastConnectAddress = address
        lastConnectAttemptId = attemptId
        if (connectResult) {
            _connectionState.value = ConnectionState.Connecting(address)
        }
        return connectResult
    }

    override suspend fun disconnect() {
        disconnectCallCount++
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun write(request: BleWriteRequest): BleWriteResult = when (request.writeType) {
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

    override suspend fun startScan(onDeviceFound: (BleDevice) -> Unit) {
        startScanCallCount++
    }

    override suspend fun stopScan() {}

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }
}
