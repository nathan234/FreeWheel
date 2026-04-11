package org.freewheel.core.telemetry

import kotlin.math.max
import kotlin.math.min

/**
 * Pure state machine for ride-replay playback, shared between Android and iOS.
 *
 * The controller on each platform (Android's [org.freewheel.compose.components.CsvReplayController],
 * iOS's `RideReplayController`) holds an instance of [ReplayPlaybackState] in its native observable
 * container and delegates every user action to [ReplayPlaybackReducer], which returns a new state.
 * The playback clock (Kotlin coroutine / Swift Task) is still driven by the platform, but the
 * index-advancing decision comes from [ReplayPlaybackReducer.advanceOne] so both platforms agree
 * on edge behaviour.
 */
data class ReplayPlaybackState(
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val speedMultiplier: Float = 4f,
    val isFinished: Boolean = false
) {
    companion object {
        /** Swift-callable factory — Kotlin default-parameter constructors aren't visible from ObjC/Swift. */
        fun initial(): ReplayPlaybackState = ReplayPlaybackState()
    }
}

/**
 * Outcome of an [ReplayPlaybackReducer.advanceOne] tick: the new state plus the millisecond delay
 * the platform clock should wait before ticking again. [delayMs] is already divided by
 * [ReplayPlaybackState.speedMultiplier] and floored at [MIN_TICK_MS].
 */
data class ReplayTick(
    val state: ReplayPlaybackState,
    val delayMs: Long
)

object ReplayPlaybackReducer {

    /** Minimum wall-clock delay per tick, so 8× playback doesn't starve the UI thread. */
    const val MIN_TICK_MS = 10L

    /** Default step for skipForward / skipBackward. Legacy value from both platforms. */
    const val SKIP_STEP_MS = 30_000L

    // ==================== Lifecycle actions ====================

    /**
     * Transition to playing. If the state is at the end ([ReplayPlaybackState.isFinished]), the
     * index is reset to 0 so the user can replay from the start with a single Play tap.
     */
    fun play(state: ReplayPlaybackState): ReplayPlaybackState {
        if (state.isPlaying) return state
        return if (state.isFinished) {
            state.copy(currentIndex = 0, isPlaying = true, isFinished = false)
        } else {
            state.copy(isPlaying = true)
        }
    }

    fun pause(state: ReplayPlaybackState): ReplayPlaybackState =
        if (state.isPlaying) state.copy(isPlaying = false) else state

    fun togglePlayPause(state: ReplayPlaybackState): ReplayPlaybackState =
        if (state.isPlaying) pause(state) else play(state)

    /** Resets to the beginning and stops playback. */
    fun stop(state: ReplayPlaybackState): ReplayPlaybackState =
        state.copy(currentIndex = 0, isPlaying = false, isFinished = false)

    // ==================== Seeking ====================

    /**
     * Jump to a normalized position in [0.0, 1.0]. Pauses playback (seeking while playing is a
     * reset operation in both original controllers). The caller is expected to resume if needed.
     */
    fun seekTo(
        state: ReplayPlaybackState,
        progress: Float,
        samples: List<TelemetrySample>
    ): ReplayPlaybackState {
        if (samples.size < 2) return state.copy(isPlaying = false, isFinished = false)
        val lastIndex = samples.size - 1
        val rawIdx = (progress * lastIndex).toInt()
        val idx = rawIdx.coerceIn(0, lastIndex)
        return state.copy(currentIndex = idx, isPlaying = false, isFinished = false)
    }

    /**
     * Skip forward by [SKIP_STEP_MS] of wheel-clock time. Preserves the play state — if the user
     * was playing, the returned state is still `isPlaying = true`, so the platform caller should
     * keep its clock running. Returns the state unchanged when there are no samples.
     */
    fun skipForward(state: ReplayPlaybackState, samples: List<TelemetrySample>): ReplayPlaybackState =
        seekByMs(state, SKIP_STEP_MS, samples)

    fun skipBackward(state: ReplayPlaybackState, samples: List<TelemetrySample>): ReplayPlaybackState =
        seekByMs(state, -SKIP_STEP_MS, samples)

    private fun seekByMs(
        state: ReplayPlaybackState,
        deltaMs: Long,
        samples: List<TelemetrySample>
    ): ReplayPlaybackState {
        if (samples.isEmpty()) return state
        val current = samples.getOrNull(state.currentIndex) ?: return state
        val target = current.timestampMs + deltaMs
        val newIdx = if (deltaMs > 0) {
            val first = samples.indexOfFirst { it.timestampMs >= target }
            if (first < 0) samples.size - 1 else first
        } else {
            val last = samples.indexOfLast { it.timestampMs <= target }
            if (last < 0) 0 else last
        }
        return state.copy(currentIndex = newIdx, isFinished = false)
    }

    // ==================== Speed ====================

    fun setSpeed(state: ReplayPlaybackState, multiplier: Float): ReplayPlaybackState =
        state.copy(speedMultiplier = multiplier)

    // ==================== Tick loop ====================

    /**
     * Advance one sample index forward, used by the platform playback loop. Returns the new state
     * plus the millisecond delay the platform clock should wait before calling this again.
     *
     * When the next advance would push past the last sample, the state transitions to
     * `!isPlaying && isFinished`. The platform loop should stop ticking when it sees this.
     */
    fun advanceOne(state: ReplayPlaybackState, samples: List<TelemetrySample>): ReplayTick {
        if (samples.size < 2 || !state.isPlaying) {
            return ReplayTick(state, MIN_TICK_MS)
        }
        val nextIdx = state.currentIndex + 1
        if (nextIdx >= samples.size) {
            return ReplayTick(state.copy(isPlaying = false, isFinished = true), MIN_TICK_MS)
        }
        val gapMs = samples[nextIdx].timestampMs - samples[state.currentIndex].timestampMs
        val adjustedDelay = max(MIN_TICK_MS, (gapMs / state.speedMultiplier).toLong())
        val advancedState = state.copy(currentIndex = nextIdx)
        val isLast = nextIdx >= samples.size - 1
        return ReplayTick(
            state = if (isLast) advancedState.copy(isPlaying = false, isFinished = true)
            else advancedState,
            delayMs = adjustedDelay
        )
    }

    // ==================== Derived helpers ====================

    fun currentSample(state: ReplayPlaybackState, samples: List<TelemetrySample>): TelemetrySample? =
        samples.getOrNull(state.currentIndex)

    fun progress(state: ReplayPlaybackState, samples: List<TelemetrySample>): Float {
        if (samples.size <= 1) return 0f
        return state.currentIndex.toFloat() / (samples.size - 1)
    }

    fun totalDurationMs(samples: List<TelemetrySample>): Long {
        if (samples.size < 2) return 0L
        return samples.last().timestampMs - samples.first().timestampMs
    }

    fun elapsedMs(state: ReplayPlaybackState, samples: List<TelemetrySample>): Long {
        val first = samples.firstOrNull()?.timestampMs ?: return 0L
        val current = currentSample(state, samples)?.timestampMs ?: return 0L
        return current - first
    }
}
