package com.cooper.wheellog.core.service

import com.cooper.wheellog.core.utils.Logger
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
    ) {
        stop()

        if (intervalMs <= 0) return

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
     * Stop the keep-alive timer.
     */
    fun stop() {
        timerJob?.cancel()
        timerJob = null
        _isRunning.value = false
        onTick = null
    }

    /**
     * Restart the timer with the same settings.
     * Useful after a reconnection.
     */
    fun restart() {
        val callback = onTick ?: return
        val interval = intervalMs
        if (interval > 0) {
            start(interval, 0, callback)
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
    private var timeoutJob: Job? = null
    private var lastDataTime: Long = 0
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
    ) {
        stop()

        this.timeoutMs = timeoutMs
        lastDataTime = currentTimeMillis()
        _isTimedOut.value = false

        timeoutJob = scope.launch(dispatcher) {
            while (isActive) {
                delay(1000) // Check every second

                val elapsed = currentTimeMillis() - lastDataTime
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
    fun stop() {
        timeoutJob?.cancel()
        timeoutJob = null
        _isTimedOut.value = false
    }

    /**
     * Call this when data is received to reset the timeout.
     */
    fun onDataReceived() {
        lastDataTime = currentTimeMillis()
        _isTimedOut.value = false
    }

    /**
     * Get the time since last data in milliseconds.
     */
    fun timeSinceLastDataMs(): Long {
        return currentTimeMillis() - lastDataTime
    }
}

/**
 * Command scheduler for handling delayed commands.
 *
 * Some protocols require commands to be sent with specific delays
 * (e.g., InMotion V2 init sequence).
 */
class CommandScheduler(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val pendingJobs = mutableListOf<Job>()
    private val lock = com.cooper.wheellog.core.utils.Lock()

    /**
     * Schedule a command to be executed after a delay.
     *
     * @param delayMs Delay before execution
     * @param command The command to execute
     */
    fun schedule(delayMs: Long, command: suspend () -> Unit) {
        val job = scope.launch(dispatcher) {
            delay(delayMs)
            try {
                command()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("CommandScheduler", "Error executing scheduled command", e)
            }
        }

        lock.lock()
        try {
            pendingJobs.add(job)
            // Clean up completed jobs
            pendingJobs.removeAll { !it.isActive }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Schedule a sequence of commands as a single coroutine.
     * Unlike [schedule], this runs the entire block sequentially,
     * so delays within the block are relative to the previous step.
     */
    fun scheduleSequence(block: suspend () -> Unit) {
        val job = scope.launch(dispatcher) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("CommandScheduler", "Error executing scheduled command sequence", e)
            }
        }

        lock.lock()
        try {
            pendingJobs.add(job)
            pendingJobs.removeAll { !it.isActive }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Cancel all pending commands.
     */
    fun cancelAll() {
        lock.lock()
        try {
            pendingJobs.forEach { it.cancel() }
            pendingJobs.clear()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Get the number of pending commands.
     */
    fun pendingCount(): Int {
        lock.lock()
        try {
            return pendingJobs.count { it.isActive }
        } finally {
            lock.unlock()
        }
    }
}

/**
 * Platform-agnostic current time function.
 * Returns current time in milliseconds.
 */
expect fun currentTimeMillis(): Long
