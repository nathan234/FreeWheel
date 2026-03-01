package com.cooper.wheellog.core.service

import com.cooper.wheellog.core.utils.Logger
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
    private val connect: suspend (String) -> Unit,
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

    private var observerJob: Job? = null

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
     */
    fun attemptStartupConnect(address: String, timeoutMs: Long = 10_000) {
        if (address.isBlank()) return

        _isAutoConnecting.value = true
        startupJob?.cancel()
        startupJob = scope.launch(dispatcher) {
            try {
                connect(address)
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
        backoffMs: List<Long> = DEFAULT_BACKOFF
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

                _reconnectState.value = ReconnectState.Attempting(attempt)

                try {
                    connect(address)
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

    companion object {
        private const val TAG = "AutoConnectManager"

        /** Default exponential backoff delays matching iOS behavior: 2s, 4s, 8s, 16s, 30s */
        val DEFAULT_BACKOFF = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

        /** Time to wait after a reconnect attempt to see if connection succeeds */
        private const val RECONNECT_SETTLE_MS = 10_000L
    }
}
