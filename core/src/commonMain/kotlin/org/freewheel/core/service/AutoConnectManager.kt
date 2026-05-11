package org.freewheel.core.service

import org.freewheel.core.utils.Logger
import kotlin.concurrent.Volatile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages automatic connection to a wheel on app startup and reconnection
 * after connection loss with exponential backoff.
 *
 * Observes [ConnectionState] from [WheelConnectionManager] and reacts automatically:
 * - Clears startup auto-connect flag on Connected or Failed
 * - Stops reconnect loop on Connected
 *
 * Follows the same pattern as [KeepAliveTimer] and [DataTimeoutTracker]:
 * takes a [CoroutineScope] and [CoroutineDispatcher], exposes state via [StateFlow].
 *
 * @param connectionState Observable connection state to react to
 * @param connect Suspend function to initiate a connection to the given address
 * @param scope Coroutine scope for launching background work
 * @param dispatcher Dispatcher for background work (injectable for testing)
 */
class AutoConnectManager(
    private val connectionState: StateFlow<ConnectionState>,
    private val connect: suspend (String, ConnectionHint?) -> Unit,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    // --- Startup auto-connect ---

    private val _isAutoConnecting = MutableStateFlow(false)
    val isAutoConnecting: StateFlow<Boolean> = _isAutoConnecting.asStateFlow()

    private var startupJob: Job? = null

    // --- Reconnect after loss ---

    sealed class ReconnectState {
        data object Idle : ReconnectState()
        data class Waiting(val attempt: Int, val nextRetryMs: Long) : ReconnectState()
        data class Attempting(val attempt: Int) : ReconnectState()
    }

    private val _reconnectState = MutableStateFlow<ReconnectState>(ReconnectState.Idle)
    val reconnectState: StateFlow<ReconnectState> = _reconnectState.asStateFlow()

    private var reconnectJob: Job? = null

    @Volatile private var observerJob: Job? = null

    init {
        // Observe connection state to clear flags on terminal states
        observerJob = scope.launch(dispatcher) {
            connectionState.collect { state ->
                if (state.isConnected) {
                    clearAutoConnect()
                    stopReconnect()
                }
                if (state is ConnectionState.Failed) {
                    clearAutoConnect()
                    if (!state.issue.isRecoverable) {
                        stopReconnect()
                    }
                }
                // Pass 4: WheelTypeRequired is terminal from auto-connect's POV.
                // The BLE attempt finished — services discovered, peripheral
                // bound — but detection couldn't resolve the protocol so the
                // user must pick. Leaving isAutoConnecting=true would keep
                // the overlay covering the picker until the timeout fires;
                // letting reconnect retries continue would compete with the
                // user's pending pick.
                if (state is ConnectionState.WheelTypeRequired) {
                    clearAutoConnect()
                    stopReconnect()
                }
            }
        }
    }

    /**
     * Attempt to connect to the given address on app startup.
     * Sets [isAutoConnecting] to true immediately. If the connection does not
     * succeed within [timeoutMs], the flag is cleared automatically.
     *
     * No-op if [address] is blank.
     *
     * @param hint Optional [ConnectionHint] (typically [HintSource.SAVED_PROFILE]
     *             for a remembered last-MAC) biasing service-discovery's
     *             Ambiguous branch.
     */
    fun attemptStartupConnect(
        address: String,
        timeoutMs: Long = 10_000,
        hint: ConnectionHint? = null,
    ) {
        if (address.isBlank()) return

        _isAutoConnecting.value = true
        startupJob?.cancel()
        startupJob = scope.launch(dispatcher) {
            try {
                connect(address, hint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Startup connect failed: ${e.message}")
            }

            // Timeout: if still auto-connecting after timeoutMs, clear the flag
            delay(timeoutMs)
            if (_isAutoConnecting.value) {
                _isAutoConnecting.value = false
            }
        }
    }

    /**
     * Start reconnecting to [address] with exponential backoff.
     * Each attempt waits for the corresponding delay from [backoffMs],
     * then calls [connect]. If the connection succeeds (observed via
     * [connectionState] becoming Connected), the loop stops automatically.
     *
     * No-op if [address] is blank.
     *
     * @param backoffMs List of delay durations in milliseconds for each attempt.
     *   If more attempts are needed than entries, the last value is reused.
     */
    fun startReconnecting(
        address: String,
        backoffMs: List<Long> = DEFAULT_BACKOFF,
        hint: ConnectionHint? = null,
    ) {
        if (address.isBlank()) return

        stopReconnect()

        // Set initial state synchronously so callers can observe it immediately,
        // matching how attemptStartupConnect sets isAutoConnecting before launch.
        val firstDelay = backoffMs[0]
        _reconnectState.value = ReconnectState.Waiting(1, firstDelay)

        reconnectJob = scope.launch(dispatcher) {
            var attempt = 1
            var delayMs = firstDelay
            while (isActive) {
                delay(delayMs)
                if (!isActive) break

                val currentState = connectionState.value
                if (shouldSkipReconnectAttempt(currentState, address)) {
                    Logger.d(
                        TAG,
                        "Skipping reconnect attempt $attempt for $address; state=${currentState.statusText}"
                    )
                    delay(RECONNECT_SETTLE_MS)
                    attempt++
                    delayMs = backoffMs[minOf(attempt - 1, backoffMs.size - 1)]
                    _reconnectState.value = ReconnectState.Waiting(attempt, delayMs)
                    continue
                }

                _reconnectState.value = ReconnectState.Attempting(attempt)

                try {
                    connect(address, hint)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Reconnect attempt $attempt failed: ${e.message}")
                }

                // Wait a bit to see if connection succeeds (observer will stop us)
                delay(RECONNECT_SETTLE_MS)

                attempt++
                delayMs = backoffMs[minOf(attempt - 1, backoffMs.size - 1)]
                _reconnectState.value = ReconnectState.Waiting(attempt, delayMs)
            }
        }
    }

    /**
     * Stop all auto-connect and reconnect activity.
     * Call this on explicit user disconnect.
     */
    fun stop() {
        clearAutoConnect()
        stopReconnect()
    }

    /**
     * Clean up all coroutines. Call when the manager is no longer needed.
     */
    fun destroy() {
        stop()
        observerJob?.cancel()
        observerJob = null
    }

    private fun clearAutoConnect() {
        _isAutoConnecting.value = false
        startupJob?.cancel()
        startupJob = null
    }

    private fun stopReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _reconnectState.value = ReconnectState.Idle
    }

    private fun shouldSkipReconnectAttempt(state: ConnectionState, address: String): Boolean {
        if (state.isConnected && (state as? ConnectionState.Connected)?.address == address) {
            return true
        }
        return state.connectingAddress == address
    }

    companion object {
        private const val TAG = "AutoConnectManager"

        /**
         * Default reconnect backoff: fast first retry (500ms) for BLE hiccups,
         * then exponential backoff for longer outages.
         */
        val DEFAULT_BACKOFF = listOf(500L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

        /** Time to wait after a reconnect attempt to see if connection succeeds.
         * BLE connections typically resolve within 1-3s on iOS/Android. */
        private const val RECONNECT_SETTLE_MS = 4_000L
    }
}
