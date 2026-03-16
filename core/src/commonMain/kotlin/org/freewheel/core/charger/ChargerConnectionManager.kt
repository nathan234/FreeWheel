package org.freewheel.core.charger

import org.freewheel.core.service.BleManagerPort
import org.freewheel.core.service.ConnectionState
import org.freewheel.core.utils.Logger
import org.freewheel.core.utils.md5
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Connection manager for HW Charger (Roger/Pidzoom) BLE devices.
 *
 * Uses the same reducer + scan MVI pattern as [WheelConnectionManager]:
 * all events flow through a single `scan` pipeline where a pure reducer
 * computes `(State, Event) → (NewState, Effects)`. Side effects (BLE I/O,
 * auth, command dispatch) are executed after each state transition.
 *
 * Independent from WheelConnectionManager — uses its own BleManager instance.
 */
class ChargerConnectionManager(
    private val bleManager: BleManagerPort,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val decoder: HwChargerDecoder = HwChargerDecoder()
) {

    // ── MVI pipeline ───────────────────────────────────────────────

    private val _state = MutableStateFlow(CcmState())

    private val events = Channel<ChargerEvent>(Channel.UNLIMITED)

    private val eventLoopJob: Job = scope.launch(dispatcher) {
        events.receiveAsFlow()
            .scan(CcmTransition(CcmState())) { prev, event ->
                reduce(prev.state, event)
            }
            .collect { transition ->
                _state.value = transition.state
                executeEffects(transition.effects)
            }
    }

    private var bleConnectJob: Job? = null

    // ── Derived public flows ───────────────────────────────────────

    private val derivedScope = scope + dispatcher

    val chargerState: StateFlow<ChargerState> = _state
        .map { it.chargerState }
        .distinctUntilChanged()
        .stateIn(derivedScope, SharingStarted.Eagerly, ChargerState())

    val connectionState: StateFlow<ConnectionState> = _state
        .map { it.connectionState }
        .distinctUntilChanged()
        .stateIn(derivedScope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    // ── Public methods (emit events) ───────────────────────────────

    fun connect(address: String, password: String) {
        events.trySend(ChargerEvent.ConnectRequested(address, password))
    }

    fun disconnect() {
        events.trySend(ChargerEvent.DisconnectRequested)
    }

    suspend fun shutdown() {
        events.send(ChargerEvent.DisconnectRequested)
        events.close()
        eventLoopJob.join()
    }

    fun onDataReceived(data: ByteArray) {
        events.trySend(ChargerEvent.DataReceived(data))
    }

    fun onServicesDiscovered() {
        events.trySend(ChargerEvent.ServicesDiscovered)
    }

    fun onBleError() {
        events.trySend(ChargerEvent.BleError)
    }

    // Charger commands
    fun setOutputVoltage(voltage: Float) {
        events.trySend(ChargerEvent.SendBytes(
            HwChargerProtocol.buildFloatCommand(HwChargerProtocol.CMD_SET_VOLTAGE, voltage)
        ))
    }

    fun setOutputCurrent(current: Float) {
        events.trySend(ChargerEvent.SendBytes(
            HwChargerProtocol.buildFloatCommand(HwChargerProtocol.CMD_SET_CURRENT, current)
        ))
    }

    fun toggleOutput(enable: Boolean) {
        events.trySend(ChargerEvent.SendBytes(
            HwChargerProtocol.buildOutputToggle(enable)
        ))
    }

    fun setPowerLimit(watts: Int) {
        events.trySend(ChargerEvent.SendBytes(
            HwChargerProtocol.buildPowerLimitCommand(watts)
        ))
    }

    fun setTwoStageCharging(enabled: Boolean) {
        val payload = byteArrayOf(if (enabled) 1 else 0)
        events.trySend(ChargerEvent.SendBytes(
            HwChargerProtocol.buildFrame(HwChargerProtocol.CMD_TWO_STAGE, payload)
        ))
    }

    fun setEndOfChargeCurrent(current: Float) {
        events.trySend(ChargerEvent.SendBytes(
            HwChargerProtocol.buildFloatCommand(HwChargerProtocol.CMD_END_CHARGE_CUR, current)
        ))
    }

    fun setPowerOnOutput(enabled: Boolean) {
        val payload = byteArrayOf(if (enabled) 1 else 0)
        events.trySend(ChargerEvent.SendBytes(
            HwChargerProtocol.buildFrame(HwChargerProtocol.CMD_POWER_ON_OUT, payload)
        ))
    }

    // ── Reducer (pure) ─────────────────────────────────────────────

    private fun reduce(state: CcmState, event: ChargerEvent): CcmTransition {
        return when (event) {
            is ChargerEvent.ConnectRequested -> reduceConnect(state, event)
            is ChargerEvent.DisconnectRequested -> reduceDisconnect(state)
            is ChargerEvent.BleConnectResult -> reduceBleResult(state, event)
            is ChargerEvent.ServicesDiscovered -> reduceServicesDiscovered(state)
            is ChargerEvent.DataReceived -> reduceDataReceived(state, event)
            is ChargerEvent.BleError -> reduceBleError(state)
            is ChargerEvent.SendBytes -> reduceSendBytes(state, event)
        }
    }

    private fun reduceConnect(state: CcmState, event: ChargerEvent.ConnectRequested): CcmTransition {
        val newState = CcmState(
            connectionState = ConnectionState.Connecting(event.address),
            password = event.password,
            address = event.address
        )
        val effects = buildList {
            add(CcmEffect.CancelBleConnect)
            add(CcmEffect.ResetDecoder)
            add(CcmEffect.BleConnect(event.address))
        }
        return CcmTransition(newState, effects)
    }

    private fun reduceDisconnect(state: CcmState): CcmTransition {
        val effects = listOf(
            CcmEffect.CancelBleConnect,
            CcmEffect.ResetDecoder,
            CcmEffect.BleDisconnect
        )
        return CcmTransition(CcmState(), effects)
    }

    private fun reduceBleResult(state: CcmState, event: ChargerEvent.BleConnectResult): CcmTransition {
        if (state.connectionState !is ConnectionState.Connecting) {
            return CcmTransition(state)
        }
        return if (event.success) {
            CcmTransition(
                state.copy(connectionState = ConnectionState.DiscoveringServices(state.address))
            )
        } else {
            CcmTransition(
                state.copy(connectionState = ConnectionState.Failed(
                    error = event.error ?: "Connection failed",
                    address = state.address
                ))
            )
        }
    }

    private fun reduceServicesDiscovered(state: CcmState): CcmTransition {
        Logger.d(TAG, "Services discovered, configuring BLE and sending auth")
        val effects = buildList {
            add(CcmEffect.ConfigureBle)
            if (state.password.isNotEmpty()) {
                add(CcmEffect.SendAuth(state.password))
            }
        }
        return CcmTransition(state, effects)
    }

    private fun reduceDataReceived(state: CcmState, event: ChargerEvent.DataReceived): CcmTransition {
        val newChargerState = decoder.decode(event.data, state.chargerState)
            ?: return CcmTransition(state)

        val newConnectionState = if (
            newChargerState.isAuthenticated &&
            state.connectionState !is ConnectionState.Connected
        ) {
            Logger.d(TAG, "Authenticated, transitioning to Connected")
            ConnectionState.Connected(address = state.address, wheelName = "HW Charger")
        } else {
            state.connectionState
        }

        return CcmTransition(
            state.copy(
                chargerState = newChargerState,
                connectionState = newConnectionState,
                consecutiveBleErrors = 0
            )
        )
    }

    private fun reduceBleError(state: CcmState): CcmTransition {
        val newCount = state.consecutiveBleErrors + 1
        Logger.w(TAG, "BLE error #$newCount")
        if (newCount >= MAX_BLE_ERRORS) {
            return CcmTransition(
                state.copy(
                    consecutiveBleErrors = newCount,
                    connectionState = ConnectionState.ConnectionLost(
                        address = state.address,
                        reason = "Too many BLE errors"
                    )
                )
            )
        }
        return CcmTransition(state.copy(consecutiveBleErrors = newCount))
    }

    private fun reduceSendBytes(state: CcmState, event: ChargerEvent.SendBytes): CcmTransition {
        return CcmTransition(state, listOf(CcmEffect.SendData(event.data)))
    }

    // ── Effect executor ────────────────────────────────────────────

    private suspend fun executeEffects(effects: List<CcmEffect>) {
        for (effect in effects) {
            when (effect) {
                is CcmEffect.BleConnect -> launchBleConnect(effect.address)
                is CcmEffect.BleDisconnect -> bleManager.disconnect()
                is CcmEffect.ConfigureBle -> {
                    bleManager.configureForWheel(
                        HwChargerProtocol.SERVICE_UUID,
                        HwChargerProtocol.CHARACTERISTIC_UUID,
                        HwChargerProtocol.SERVICE_UUID,
                        HwChargerProtocol.CHARACTERISTIC_UUID
                    )
                }
                is CcmEffect.SendAuth -> launchWrite { sendAuth(effect.password) }
                is CcmEffect.SendData -> launchWrite { sendWithChunking(effect.data) }
                is CcmEffect.CancelBleConnect -> {
                    bleConnectJob?.cancel()
                    bleConnectJob = null
                }
                is CcmEffect.ResetDecoder -> decoder.reset()
            }
        }
    }

    /**
     * Launch a write operation in a separate coroutine so inter-chunk delays
     * don't block the event loop.
     */
    private fun launchWrite(block: suspend () -> Unit) {
        scope.launch(dispatcher) { block() }
    }

    private fun launchBleConnect(address: String) {
        bleConnectJob = scope.launch(dispatcher) {
            try {
                val success = bleManager.connect(address)
                events.send(ChargerEvent.BleConnectResult(success, address))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled by disconnect
            } catch (e: Exception) {
                events.send(ChargerEvent.BleConnectResult(
                    success = false,
                    address = address,
                    error = e.message ?: "Connection failed"
                ))
            }
        }
    }

    private suspend fun sendAuth(password: String) {
        val hash = md5(password.encodeToByteArray())
        val frame = HwChargerProtocol.buildAuthFrame(hash)
        sendWithChunking(frame)
    }

    private suspend fun sendWithChunking(data: ByteArray) {
        val chunks = HwChargerProtocol.chunkForMtu(data)
        for ((index, chunk) in chunks.withIndex()) {
            if (index > 0) delay(CHUNK_DELAY_MS)
            bleManager.write(chunk)
        }
    }

    companion object {
        private const val TAG = "ChargerConnectionManager"
        internal const val MAX_BLE_ERRORS = 50
        internal const val CHUNK_DELAY_MS = 25L
    }
}

// ── Internal state ─────────────────────────────────────────────────

internal data class CcmState(
    val chargerState: ChargerState = ChargerState(),
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val consecutiveBleErrors: Int = 0,
    val password: String = "",
    val address: String = ""
)

internal sealed class CcmEffect {
    class BleConnect(val address: String) : CcmEffect()
    data object BleDisconnect : CcmEffect()
    data object ConfigureBle : CcmEffect()
    class SendAuth(val password: String) : CcmEffect()
    class SendData(val data: ByteArray) : CcmEffect()
    data object CancelBleConnect : CcmEffect()
    data object ResetDecoder : CcmEffect()
}

internal data class CcmTransition(
    val state: CcmState,
    val effects: List<CcmEffect> = emptyList()
)

// ── Events ─────────────────────────────────────────────────────────

internal sealed class ChargerEvent {
    class ConnectRequested(val address: String, val password: String) : ChargerEvent()
    data object DisconnectRequested : ChargerEvent()
    class BleConnectResult(val success: Boolean, val address: String, val error: String? = null) : ChargerEvent()
    data object ServicesDiscovered : ChargerEvent()
    class DataReceived(val data: ByteArray) : ChargerEvent()
    data object BleError : ChargerEvent()
    class SendBytes(val data: ByteArray) : ChargerEvent()
}
