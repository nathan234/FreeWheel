package org.freewheel.core.service

import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.Logger
import org.freewheel.core.utils.currentTimeMillis
import org.freewheel.core.utils.withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keep-alive timer for maintaining wheel connection.
 *
 * Different wheel protocols require periodic commands to:
 * 1. Request telemetry data (InMotion, Ninebot)
 * 2. Maintain the BLE connection
 * 3. Detect disconnections (no data received timeout)
 *
 * Timer intervals by wheel type:
 * - Gotway/Veteran: N/A (wheel-initiated)
 * - Kingsong: N/A (wheel-initiated)
 * - InMotion V1: 250ms
 * - InMotion V2: 25ms
 * - Ninebot: 125ms
 * - Ninebot Z: 25ms
 */
class KeepAliveTimer(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val lock = Lock()
    private var timerJob: Job? = null
    private var intervalMs: Long = 0
    private var onTick: (suspend () -> Unit)? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _tickCount = MutableStateFlow(0L)
    val tickCount: StateFlow<Long> = _tickCount.asStateFlow()

    /**
     * Start the keep-alive timer.
     *
     * @param intervalMs Interval between ticks in milliseconds
     * @param initialDelayMs Optional delay before first tick
     * @param onTick Callback invoked on each timer tick
     */
    fun start(
        intervalMs: Long,
        initialDelayMs: Long = 0,
        onTick: suspend () -> Unit
    ) = lock.withLock {
        stop_internal()

        if (intervalMs <= 0) return@withLock

        start_internal(intervalMs, initialDelayMs, onTick)
    }

    /**
     * Stop the keep-alive timer.
     */
    fun stop() = lock.withLock {
        stop_internal()
    }

    /** Internal stop — caller must hold [lock]. */
    private fun stop_internal() {
        timerJob?.cancel()
        timerJob = null
        _isRunning.value = false
        onTick = null
    }

    /**
     * Restart the timer with the same settings.
     * Uses interval as initial delay to avoid double-tick on restart.
     */
    fun restart() = lock.withLock {
        val callback = onTick ?: return@withLock
        val interval = intervalMs
        if (interval > 0) {
            stop_internal()
            start_internal(interval, interval, callback)
        }
    }

    /** Internal start — caller must hold [lock]. */
    private fun start_internal(intervalMs: Long, initialDelayMs: Long, onTick: suspend () -> Unit) {
        this.intervalMs = intervalMs
        this.onTick = onTick
        _tickCount.value = 0

        timerJob = scope.launch(dispatcher) {
            _isRunning.value = true

            if (initialDelayMs > 0) {
                delay(initialDelayMs)
            }

            while (isActive) {
                try {
                    onTick()
                    _tickCount.value++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e("KeepAliveTimer", "Error in timer tick callback", e)
                }
                delay(intervalMs)
            }
        }
    }

    /**
     * Get the current interval in milliseconds.
     */
    fun getIntervalMs(): Long = intervalMs
}

/**
 * Timeout tracker for detecting connection loss.
 *
 * Monitors the time since last data was received and triggers
 * a timeout callback if no data arrives within the threshold.
 */
class DataTimeoutTracker(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val lock = Lock()
    private var timeoutJob: Job? = null
    // Lock-free timestamp — MutableStateFlow provides thread-safe reads/writes
    // from the BLE callback thread (onDataReceived called 50+ times/sec)
    // without blocking the callback
    private val lastDataTime = MutableStateFlow(0L)
    private var timeoutMs: Long = DEFAULT_TIMEOUT_MS

    private val _isTimedOut = MutableStateFlow(false)
    val isTimedOut: StateFlow<Boolean> = _isTimedOut.asStateFlow()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L // 60 seconds
    }

    /**
     * Start monitoring for data timeout.
     *
     * @param timeoutMs Timeout threshold in milliseconds
     * @param onTimeout Callback invoked when timeout occurs
     */
    fun start(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onTimeout: suspend () -> Unit
    ) = lock.withLock {
        stop_internal()

        this.timeoutMs = timeoutMs
        lastDataTime.value = currentTimeMillis()
        _isTimedOut.value = false

        timeoutJob = scope.launch(dispatcher) {
            while (isActive) {
                delay(1000) // Check every second

                val elapsed = currentTimeMillis() - lastDataTime.value
                if (elapsed > timeoutMs) {
                    if (!_isTimedOut.value) {
                        _isTimedOut.value = true
                        try {
                            onTimeout()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Logger.e("DataTimeoutTracker", "Error in timeout callback", e)
                        }
                    }
                    // Continue monitoring — onDataReceived() resets _isTimedOut
                }
            }
        }
    }

    /**
     * Stop monitoring for timeout.
     */
    fun stop() = lock.withLock {
        stop_internal()
    }

    /** Internal stop — caller must hold [lock]. */
    private fun stop_internal() {
        timeoutJob?.cancel()
        timeoutJob = null
        _isTimedOut.value = false
    }

    /**
     * Call this when data is received to reset the timeout.
     * Lock-free — safe to call from BLE callback thread at high frequency.
     */
    fun onDataReceived() {
        lastDataTime.value = currentTimeMillis()
        _isTimedOut.value = false
    }

    /**
     * Get the time since last data in milliseconds.
     */
    fun timeSinceLastDataMs(): Long = currentTimeMillis() - lastDataTime.value
}

/**
 * Serial command queue for BLE command dispatch.
 *
 * Commands are executed sequentially by a single consumer coroutine,
 * guaranteeing:
 * - **Ordering**: commands reach the wheel in dispatch order
 * - **Atomicity**: multi-step sequences (e.g., Gotway LED: W→M→digit→b
 *   with 100ms delays) complete without interleaving
 * - **Clean cancellation**: [cancelAll] cancels the in-flight command
 *   and discards all pending work
 */
class CommandScheduler(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var channel = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(
        kotlinx.coroutines.channels.Channel.UNLIMITED
    )
    private var consumerJob = launchConsumer()

    /**
     * Enqueue a command sequence for serial execution.
     * Returns immediately — the consumer processes it in order.
     */
    fun scheduleSequence(block: suspend () -> Unit) {
        channel.trySend(block)
    }

    /**
     * Cancel the in-flight command and discard all pending work.
     * A new consumer is started immediately, ready for future commands.
     */
    fun cancelAll() {
        consumerJob.cancel()
        // Drain any buffered blocks (channel is UNLIMITED, so tryReceive is non-blocking)
        while (channel.tryReceive().isSuccess) { /* discard */ }
        // Close the old channel and start fresh
        channel.close()
        channel = kotlinx.coroutines.channels.Channel(
            kotlinx.coroutines.channels.Channel.UNLIMITED
        )
        consumerJob = launchConsumer()
    }

    private fun launchConsumer(): Job = scope.launch(dispatcher) {
        for (block in channel) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("CommandScheduler", "Error executing command", e)
            }
        }
    }
}
