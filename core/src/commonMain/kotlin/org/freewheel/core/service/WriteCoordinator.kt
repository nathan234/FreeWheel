package org.freewheel.core.service

import kotlin.concurrent.Volatile
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.freewheel.core.utils.currentTimeMillis

/**
 * BLE transport-policy enforcer.
 *
 * `CommandScheduler` is the semantic queue: it serializes ordered command
 * *sequences* (Gotway LED multi-step, decoder follow-ups, init blocks) so
 * those sequences never interleave.
 *
 * `WriteCoordinator` is the layer underneath. It enforces *transport* rules
 * for every actual packet write — write mode, inter-write spacing, retry
 * policy — based on the wheel-family [WheelTransportProfile]. The semantic
 * queue stays stable while the transport policy varies per wheel family.
 *
 * Commit 2 of the Kingsong BLE parity plan introduces the coordinator and
 * pipes every write through it. Every wheel still uses
 * [WheelTransportProfile.Default], so the default-profile path is
 * byte-equivalent to pre-Commit-2 behavior: WITHOUT_RESPONSE, no spacing,
 * no retries, exactly one transport attempt.
 *
 * Stateful but small — a mutex, a generation counter, and the most recent
 * session-stamped write marker. Not a queue: `CommandScheduler` already
 * serializes sequences, and the mutex here only exists to keep spacing and
 * retry honest across concurrent writers.
 *
 * ## Reset race
 *
 * [reset] is called from the WCM event loop via [WcmEffect.StopTimers], which
 * fires *before* [WcmEffect.CancelCommands] cancels the consumer. A command
 * block already inside [write] holds the mutex; acquiring the mutex in
 * [reset] would block the event loop, but skipping it lets an in-flight
 * write outlive the reset and pollute the next session's cadence.
 *
 * The fix is **session-stamping**: every recorded write carries the
 * generation it observed at entry, and the spacing decision only honors a
 * stamp whose generation still matches. [reset] just bumps the generation;
 * any stamp written by a write that started before the reset will have a
 * stale generation and be ignored. The atomic unit is the
 * [SessionStamp] object reference — a single volatile write publishes both
 * fields together, so a check-then-assign race in the old `Long` field
 * design (where reset could interleave between the equality check and the
 * `lastWriteAt = currentTimeMillis()` assignment) is impossible.
 */
open class WriteCoordinator {

    private val mutex = Mutex()

    /**
     * Session generation counter. Incremented by [reset]; captured by every
     * [write] at entry. Stamps with a stale generation are ignored by
     * subsequent writes, so an in-flight write that straddles a session
     * teardown cannot carry the prior session's cadence into the next one.
     */
    @Volatile
    private var generation: Long = 0L

    /**
     * Most recent recorded write, or null if no write has been observed
     * since construction. The stamp's [SessionStamp.generation] tells the
     * next [write] whether the timestamp is "in this session" — it only
     * applies spacing when the generations match.
     *
     * Why an immutable holder instead of a bare `Long`: assigning a
     * volatile reference is a single atomic publish, so a reader always
     * sees a coherent (generation, timeMillis) pair. With a bare
     * `lastWriteAt: Long` field plus a separate `generation` check, [reset]
     * could fire between the check and the assignment — leaving a fresh
     * timestamp tagged with the new generation, exactly what we want to
     * avoid. The stamp design makes that race impossible.
     */
    @Volatile
    private var lastWrite: SessionStamp? = null

    /**
     * (generation, timeMillis) pair recorded for the most recent write.
     * Immutable so the volatile reference write is a single atomic publish.
     */
    private class SessionStamp(val generation: Long, val timeMillis: Long)

    /**
     * Reset session-scoped timing state. Called by [WheelConnectionManager]
     * at every session boundary (via [WcmEffect.StopTimers]) so a fresh
     * connect starts with clean cadence. Safe to call any number of times.
     *
     * Non-blocking and race-free: bumping the generation invalidates every
     * existing or in-flight stamp without touching the [mutex]. An in-flight
     * write that started before this reset will publish a stamp tagged with
     * the *old* generation; the next write captures the *new* generation
     * and ignores the mismatched stamp.
     *
     * Open so tests can subclass and observe reset call counts; production
     * use should treat the coordinator as final.
     */
    open fun reset() {
        generation += 1
    }

    /**
     * Submit a single transport write under [profile].
     *
     * The mutex serializes overlapping callers so spacing and retry honor
     * each write completion before the next attempt begins. [transportWrite]
     * is the actual platform call — usually `bleManager::write` from the
     * effect executor; tests inject a controllable lambda.
     *
     * Failure handling: [WheelTransportProfile.retryPolicy] dictates how many
     * additional attempts to make on [BleWriteResult.Failed]. Spacing only
     * applies between distinct writes, not between retries of the same
     * write — retries use [RetryPolicy.retryBackoffMs] instead.
     */
    suspend fun write(
        profile: WheelTransportProfile,
        data: ByteArray,
        annotation: String = "",
        transportWrite: suspend (BleWriteRequest) -> BleWriteResult,
    ): BleWriteResult = mutex.withLock {
        // Capture the session generation at entry. Spacing only applies to
        // a prior stamp whose generation still matches; the stamp this call
        // publishes is tagged with this same value, so a [reset] that races
        // with us (any time before or after this write completes) cannot
        // make our stamp "look fresh" to a future write.
        val sessionGeneration = generation
        val previousWrite = lastWrite

        if (profile.interWriteSpacingMs > 0 &&
            previousWrite != null &&
            previousWrite.generation == sessionGeneration
        ) {
            val elapsed = currentTimeMillis() - previousWrite.timeMillis
            val wait = profile.interWriteSpacingMs - elapsed
            if (wait > 0) delay(wait)
        }

        val request = BleWriteRequest(
            data = data,
            writeType = profile.writeType,
            annotation = annotation,
        )

        var result: BleWriteResult = transportWrite(request)
        var attemptsLeft = profile.retryPolicy.maxRetries
        while (result is BleWriteResult.Failed && attemptsLeft > 0) {
            if (profile.retryPolicy.retryBackoffMs > 0) {
                delay(profile.retryPolicy.retryBackoffMs)
            }
            result = transportWrite(request)
            attemptsLeft--
        }

        // Always publish the stamp tagged with the entry-time generation —
        // never re-read [generation]. If [reset] ran during our suspension,
        // our stamp carries the now-stale generation and the next write
        // (which captures the post-reset generation) will see the mismatch
        // and ignore it. The single volatile reference write is atomic, so
        // no reader can observe a half-written stamp.
        lastWrite = SessionStamp(sessionGeneration, currentTimeMillis())
        result
    }
}
