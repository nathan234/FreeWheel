package org.freewheel.core.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [WriteCoordinator] — Commit 2 of the Kingsong BLE parity
 * plan. The coordinator is the transport-policy enforcement point underneath
 * the command scheduler; these tests prove that the [WheelTransportProfile]
 * fields actually shape behavior, and — critically for Commit 2 — that
 * [WheelTransportProfile.Default] preserves pre-Commit-2 semantics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WriteCoordinatorTest {

    // ==================== Default profile equivalence ====================

    @Test
    fun `default profile uses WITHOUT_RESPONSE`() = runTest(timeout = 0.5.seconds) {
        val coordinator = WriteCoordinator()
        var seenType: BleWriteType? = null
        coordinator.write(WheelTransportProfile.Default, byteArrayOf(0x01)) { req ->
            seenType = req.writeType
            BleWriteResult.Submitted(latencyMs = 0)
        }
        assertEquals(BleWriteType.WITHOUT_RESPONSE, seenType)
    }

    @Test
    fun `default profile performs exactly one transport attempt`() = runTest(timeout = 0.5.seconds) {
        val coordinator = WriteCoordinator()
        var attempts = 0
        coordinator.write(WheelTransportProfile.Default, byteArrayOf(0x01)) {
            attempts++
            BleWriteResult.Submitted(latencyMs = 0)
        }
        assertEquals(1, attempts, "Default profile must not retry")
    }

    @Test
    fun `default profile adds no spacing delay between writes`() = runTest(timeout = 0.5.seconds) {
        val coordinator = WriteCoordinator()
        // Two back-to-back writes — neither should delay in the default profile.
        coordinator.write(WheelTransportProfile.Default, byteArrayOf(0x01)) {
            BleWriteResult.Submitted(latencyMs = 0)
        }
        val before = testScheduler.currentTime
        coordinator.write(WheelTransportProfile.Default, byteArrayOf(0x02)) {
            BleWriteResult.Submitted(latencyMs = 0)
        }
        val after = testScheduler.currentTime
        assertEquals(0L, after - before, "Default profile must not delay between writes")
    }

    @Test
    fun `default profile does not retry on failure`() = runTest(timeout = 0.5.seconds) {
        val coordinator = WriteCoordinator()
        var attempts = 0
        val result = coordinator.write(WheelTransportProfile.Default, byteArrayOf(0x01)) {
            attempts++
            BleWriteResult.Failed("simulated", latencyMs = 0)
        }
        assertEquals(1, attempts, "Default profile must not retry even on Failed")
        assertTrue(result is BleWriteResult.Failed)
    }

    // ==================== Retry policy ====================

    @Test
    fun `retry policy attempts the configured number of additional writes`() = runTest(timeout = 0.5.seconds) {
        val coordinator = WriteCoordinator()
        val profile = WheelTransportProfile.Default.copy(
            retryPolicy = RetryPolicy(maxRetries = 2, retryBackoffMs = 0),
        )
        var attempts = 0
        coordinator.write(profile, byteArrayOf(0x01)) {
            attempts++
            BleWriteResult.Failed("simulated", latencyMs = 0)
        }
        // 1 initial attempt + 2 retries = 3 total
        assertEquals(3, attempts)
    }

    @Test
    fun `retry policy stops once a write succeeds`() = runTest(timeout = 0.5.seconds) {
        val coordinator = WriteCoordinator()
        val profile = WheelTransportProfile.Default.copy(
            retryPolicy = RetryPolicy(maxRetries = 5, retryBackoffMs = 0),
        )
        var attempts = 0
        val result = coordinator.write(profile, byteArrayOf(0x01)) {
            attempts++
            if (attempts >= 2) BleWriteResult.Submitted(latencyMs = 0)
            else BleWriteResult.Failed("transient", latencyMs = 0)
        }
        assertEquals(2, attempts, "Should stop retrying after first Submitted")
        assertTrue(result is BleWriteResult.Submitted)
    }

    // ==================== Spacing ====================

    @Test
    fun `interWriteSpacingMs delays subsequent writes`() = runTest(timeout = 5.seconds) {
        val coordinator = WriteCoordinator()
        val profile = WheelTransportProfile.Default.copy(interWriteSpacingMs = 50)

        coordinator.write(profile, byteArrayOf(0x01)) {
            BleWriteResult.Submitted(latencyMs = 0)
        }
        // Second write should suspend for ~50ms before invoking the transport.
        val deferred = async {
            coordinator.write(profile, byteArrayOf(0x02)) {
                BleWriteResult.Submitted(latencyMs = 0)
            }
        }
        runCurrent()
        // Coordinator must NOT have called the transport yet — it's waiting.
        assertTrue(!deferred.isCompleted, "Spacing should defer the second write")
        advanceTimeBy(60)
        runCurrent()
        assertTrue(deferred.isCompleted, "After spacing elapses the write completes")
    }

    @Test
    fun `first write of a session never waits for spacing`() = runTest(timeout = 0.5.seconds) {
        val coordinator = WriteCoordinator()
        val profile = WheelTransportProfile.Default.copy(interWriteSpacingMs = 5_000)
        val before = testScheduler.currentTime
        coordinator.write(profile, byteArrayOf(0x01)) {
            BleWriteResult.Submitted(latencyMs = 0)
        }
        val after = testScheduler.currentTime
        assertEquals(0L, after - before, "First write should not delay even with spacing configured")
    }

    // ==================== WITH_RESPONSE ====================

    @Test
    fun `WITH_RESPONSE profile carries that write type into the request`() = runTest(timeout = 0.5.seconds) {
        val coordinator = WriteCoordinator()
        val profile = WheelTransportProfile.Default.copy(writeType = BleWriteType.WITH_RESPONSE)
        var seen: BleWriteType? = null
        coordinator.write(profile, byteArrayOf(0x01)) { req ->
            seen = req.writeType
            BleWriteResult.Completed(
                ack = BleWriteAck(attemptId = 1L, success = true, data = req.data),
                latencyMs = 0,
            )
        }
        assertEquals(BleWriteType.WITH_RESPONSE, seen)
    }

    // ==================== reset() ====================

    @Test
    fun `reset clears spacing so the next write does not wait`() = runTest(timeout = 5.seconds) {
        val coordinator = WriteCoordinator()
        val profile = WheelTransportProfile.Default.copy(interWriteSpacingMs = 1_000)

        // First write establishes lastWriteAt.
        coordinator.write(profile, byteArrayOf(0x01)) {
            BleWriteResult.Submitted(latencyMs = 0)
        }

        // Without reset, the next write would suspend for ~1s. With reset,
        // the next write must complete immediately — proving that a
        // post-disconnect reconnect won't inherit the prior session's
        // cadence under a non-default spacing profile (Commit 3+).
        coordinator.reset()
        val before = testScheduler.currentTime
        coordinator.write(profile, byteArrayOf(0x02)) {
            BleWriteResult.Submitted(latencyMs = 0)
        }
        val after = testScheduler.currentTime
        assertEquals(0L, after - before, "reset() must clear lastWriteAt so the next write does not wait")
    }

    @Test
    fun `reset during an in-flight write still clears cadence for the next session`() = runTest(timeout = 5.seconds) {
        // Regression: previously, [WriteCoordinator.reset] could be undone by
        // an in-flight write that finished after reset() and repopulated
        // [lastWriteAt] with the post-reset wall-clock time. The generation-
        // marker fix makes the in-flight write skip its [lastWriteAt] update
        // when [reset] has run between entry and completion.
        val coordinator = WriteCoordinator()
        val pacedProfile = WheelTransportProfile.Default.copy(interWriteSpacingMs = 1_000)

        // Kick off a write that suspends inside the transport lambda so we
        // can deterministically interleave a reset() call before it
        // completes. The deferred only completes when the test asks it to.
        val transportArrived = kotlinx.coroutines.CompletableDeferred<Unit>()
        val transportRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
        val inflight = async {
            coordinator.write(pacedProfile, byteArrayOf(0x01)) {
                transportArrived.complete(Unit)
                transportRelease.await()
                BleWriteResult.Submitted(latencyMs = 0)
            }
        }
        transportArrived.await()

        // The reset must take effect even though a write is suspended inside
        // the coordinator (holding the mutex). The fix uses a generation
        // counter rather than mutex acquisition, so this is non-blocking.
        coordinator.reset()

        // Let the in-flight write finish. Under the bug it would now set
        // lastWriteAt = currentTimeMillis(), undoing the reset.
        transportRelease.complete(Unit)
        inflight.await()

        // Verify the reset survived: the next write must NOT wait for the
        // configured 1s spacing.
        val before = testScheduler.currentTime
        coordinator.write(pacedProfile, byteArrayOf(0x02)) {
            BleWriteResult.Submitted(latencyMs = 0)
        }
        val after = testScheduler.currentTime
        assertEquals(
            0L,
            after - before,
            "Next-session write must not inherit cadence from a write that straddled the reset",
        )
    }

    @Test
    fun `reset that fires after an in-flight write publishes its stamp does not leak cadence`() = runTest(timeout = 5.seconds) {
        // Narrower race: the in-flight write finishes its mutex section
        // (publishing a stamp tagged with the *current* generation) BEFORE
        // reset() runs. Under the prior `Long lastWriteAt` design, the next
        // write would have seen that fresh timestamp and applied spacing.
        // Under the session-stamp design, reset() invalidates the stamp by
        // bumping the generation — the next write sees a stamp whose
        // generation no longer matches and skips spacing.
        val coordinator = WriteCoordinator()
        val pacedProfile = WheelTransportProfile.Default.copy(interWriteSpacingMs = 1_000)

        // Issue a write that fully completes (mutex released, stamp
        // published, lastWrite reflects this write's generation/time).
        coordinator.write(pacedProfile, byteArrayOf(0x01)) {
            BleWriteResult.Submitted(latencyMs = 0)
        }

        // Now reset, AFTER the stamp has been published. The stamp's
        // generation is now stale relative to the post-reset generation.
        coordinator.reset()

        // The next write must NOT see the pre-reset stamp as "applicable
        // for spacing" — gen mismatch should make it skip the wait.
        val before = testScheduler.currentTime
        coordinator.write(pacedProfile, byteArrayOf(0x02)) {
            BleWriteResult.Submitted(latencyMs = 0)
        }
        val after = testScheduler.currentTime
        assertEquals(
            0L,
            after - before,
            "Reset must invalidate even a fully-published prior stamp via generation mismatch",
        )
    }

}
