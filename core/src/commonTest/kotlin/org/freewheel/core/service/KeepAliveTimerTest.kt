package org.freewheel.core.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for KeepAliveTimer, DataTimeoutTracker, and CommandScheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepAliveTimerTest {

    @Test
    fun `KeepAliveTimer starts and stops correctly`() = runTest {
        val timer = KeepAliveTimer(this, UnconfinedTestDispatcher(testScheduler))
        var tickCount = 0

        assertFalse(timer.isRunning.value)

        timer.start(intervalMs = 100) {
            tickCount++
        }

        assertTrue(timer.isRunning.value)

        // Advance time to trigger ticks
        advanceTimeBy(250)

        timer.stop()

        assertFalse(timer.isRunning.value)
        assertTrue(tickCount >= 2, "Expected at least 2 ticks, got $tickCount")
    }

    @Test
    fun `KeepAliveTimer respects initial delay`() = runTest {
        val timer = KeepAliveTimer(this, UnconfinedTestDispatcher(testScheduler))
        var tickCount = 0

        timer.start(intervalMs = 100, initialDelayMs = 200) {
            tickCount++
        }

        // Before initial delay, no ticks
        advanceTimeBy(150)
        assertEquals(0, tickCount, "Should not tick before initial delay")

        // After initial delay
        advanceTimeBy(100)
        assertTrue(tickCount >= 1, "Should tick after initial delay")

        timer.stop()
    }

    @Test
    fun `KeepAliveTimer tracks tick count`() = runTest {
        val timer = KeepAliveTimer(this, UnconfinedTestDispatcher(testScheduler))

        assertEquals(0, timer.tickCount.value)

        timer.start(intervalMs = 50) {
            // Tick
        }

        advanceTimeBy(175) // 3+ ticks at 50ms

        assertTrue(timer.tickCount.value >= 3, "Expected at least 3 ticks")

        timer.stop()
    }

    @Test
    fun `KeepAliveTimer does not start with zero interval`() = runTest {
        val timer = KeepAliveTimer(this, UnconfinedTestDispatcher(testScheduler))

        timer.start(intervalMs = 0) {
            // Should never be called
        }

        assertFalse(timer.isRunning.value)
    }

    @Test
    fun `KeepAliveTimer getIntervalMs returns correct value`() = runTest {
        val timer = KeepAliveTimer(this, UnconfinedTestDispatcher(testScheduler))

        timer.start(intervalMs = 250) {}
        assertEquals(250, timer.getIntervalMs())

        timer.stop()
    }

    @Test
    fun `KeepAliveTimer restart uses interval as initial delay`() = runTest {
        val timer = KeepAliveTimer(this, UnconfinedTestDispatcher(testScheduler))
        var tickCount = 0

        timer.start(intervalMs = 100) {
            tickCount++
        }

        // Let it tick a few times
        advanceTimeBy(250)
        val ticksBeforeRestart = tickCount
        assertTrue(ticksBeforeRestart >= 2, "Expected at least 2 ticks before restart")

        // Restart — should wait one full interval before first tick
        timer.restart()

        // Immediately after restart, no new tick
        val ticksRightAfterRestart = timer.tickCount.value
        advanceTimeBy(50)
        assertEquals(ticksRightAfterRestart, timer.tickCount.value,
            "Should not tick within interval after restart")

        // After interval, should tick
        advanceTimeBy(60)
        assertTrue(timer.tickCount.value >= 1, "Should tick after interval delay")

        timer.stop()
    }

    @Test
    fun `KeepAliveTimer concurrent start and stop does not crash`() = runTest {
        val timer = KeepAliveTimer(this, UnconfinedTestDispatcher(testScheduler))

        // Rapidly start/stop without crash
        repeat(20) {
            timer.start(intervalMs = 10) {}
            timer.stop()
        }

        assertFalse(timer.isRunning.value)
    }

    @Test
    fun `DataTimeoutTracker initial state is not timed out`() = runTest {
        val tracker = DataTimeoutTracker(this, UnconfinedTestDispatcher(testScheduler))

        assertFalse(tracker.isTimedOut.value)
        // Note: timeSinceLastDataMs() will be very large initially (current timestamp)
        // since lastDataTime starts at 0
    }

    @Test
    fun `DataTimeoutTracker tracks time since data`() = runTest {
        val tracker = DataTimeoutTracker(this, UnconfinedTestDispatcher(testScheduler))

        tracker.start(timeoutMs = 10000) {}

        // Call onDataReceived to set a baseline time
        tracker.onDataReceived()

        // timeSinceLastDataMs should be very small (< 100ms) right after onDataReceived
        assertTrue(tracker.timeSinceLastDataMs() < 100, "Time since data should be very small")

        tracker.stop()
    }

    @Test
    fun `DataTimeoutTracker onDataReceived resets timer`() = runTest {
        val tracker = DataTimeoutTracker(this, UnconfinedTestDispatcher(testScheduler))

        tracker.start(timeoutMs = 10000) {}

        // Simulate receiving data
        tracker.onDataReceived()

        // After receiving data, isTimedOut should be false
        assertFalse(tracker.isTimedOut.value)

        // timeSinceLastDataMs should be reset to near zero
        assertTrue(tracker.timeSinceLastDataMs() < 100)

        tracker.stop()
    }

    @Test
    fun `DataTimeoutTracker concurrent start stop does not crash`() = runTest {
        val tracker = DataTimeoutTracker(this, UnconfinedTestDispatcher(testScheduler))

        repeat(20) {
            tracker.start(timeoutMs = 100) {}
            tracker.onDataReceived()
            tracker.stop()
        }

        assertFalse(tracker.isTimedOut.value)
    }

    @Test
    fun `DataTimeoutTracker stop clears state`() = runTest {
        val tracker = DataTimeoutTracker(this, UnconfinedTestDispatcher(testScheduler))

        tracker.start(timeoutMs = 100) {}

        tracker.stop()

        assertFalse(tracker.isTimedOut.value)
    }

    @Test
    fun `CommandScheduler executes after delay`() = runTest {
        val scheduler = CommandScheduler(this, UnconfinedTestDispatcher(testScheduler))
        var executed = false

        scheduler.schedule(100) {
            executed = true
        }

        assertEquals(1, scheduler.pendingCount())

        advanceTimeBy(50)
        assertFalse(executed, "Should not execute before delay")

        advanceTimeBy(60)
        assertTrue(executed, "Should execute after delay")

        // Wait for job cleanup
        advanceTimeBy(10)
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun `CommandScheduler handles multiple commands`() = runTest {
        val scheduler = CommandScheduler(this, UnconfinedTestDispatcher(testScheduler))
        val executed = mutableListOf<Int>()

        scheduler.schedule(100) { executed.add(1) }
        scheduler.schedule(200) { executed.add(2) }
        scheduler.schedule(50) { executed.add(3) }

        assertTrue(scheduler.pendingCount() >= 3)

        advanceTimeBy(75)
        assertTrue(3 in executed, "Command 3 should execute first")
        assertFalse(1 in executed)
        assertFalse(2 in executed)

        advanceTimeBy(50)
        assertTrue(1 in executed, "Command 1 should execute second")

        advanceTimeBy(100)
        assertTrue(2 in executed, "Command 2 should execute last")
    }

    @Test
    fun `CommandScheduler cancelAll cancels pending commands`() = runTest {
        val scheduler = CommandScheduler(this, UnconfinedTestDispatcher(testScheduler))
        var executed = false

        scheduler.schedule(100) {
            executed = true
        }

        scheduler.cancelAll()
        assertEquals(0, scheduler.pendingCount())

        advanceTimeBy(200)
        assertFalse(executed, "Cancelled command should not execute")
    }
}

/**
 * Tests for ConnectionState.
 */
class ConnectionStateTest {

    @Test
    fun `isConnected returns true only for Connected state`() {
        assertTrue(ConnectionState.Connected("addr", "name").isConnected)
        assertFalse(ConnectionState.Disconnected.isConnected)
        assertFalse(ConnectionState.Connecting("addr").isConnected)
        assertFalse(ConnectionState.Failed("error").isConnected)
    }

    @Test
    fun `isConnecting returns true for Connecting and DiscoveringServices`() {
        assertTrue(ConnectionState.Connecting("addr").isConnecting)
        assertTrue(ConnectionState.DiscoveringServices("addr").isConnecting)
        assertFalse(ConnectionState.Connected("addr", "name").isConnecting)
        assertFalse(ConnectionState.Disconnected.isConnecting)
    }

    @Test
    fun `isDisconnected returns true for disconnected states`() {
        assertTrue(ConnectionState.Disconnected.isDisconnected)
        assertTrue(ConnectionState.Failed("error").isDisconnected)
        assertTrue(ConnectionState.ConnectionLost("addr", "reason").isDisconnected)
        assertFalse(ConnectionState.Connected("addr", "name").isDisconnected)
        assertFalse(ConnectionState.Connecting("addr").isDisconnected)
    }

    @Test
    fun `connectingAddress returns address for Connecting and DiscoveringServices`() {
        assertEquals("AA:BB:CC:DD:EE:FF", ConnectionState.Connecting("AA:BB:CC:DD:EE:FF").connectingAddress)
        assertEquals("11:22:33:44:55:66", ConnectionState.DiscoveringServices("11:22:33:44:55:66").connectingAddress)
    }

    @Test
    fun `connectingAddress returns null for non-connecting states`() {
        assertNull(ConnectionState.Disconnected.connectingAddress)
        assertNull(ConnectionState.Scanning.connectingAddress)
        assertNull(ConnectionState.Connected("addr", "name").connectingAddress)
        assertNull(ConnectionState.ConnectionLost("addr", "reason").connectingAddress)
        assertNull(ConnectionState.Failed("error").connectingAddress)
    }

    @Test
    fun `failedAddress returns address from Failed state`() {
        assertEquals("AA:BB:CC", ConnectionState.Failed("error", "AA:BB:CC").failedAddress)
    }

    @Test
    fun `failedAddress returns null when Failed has no address`() {
        assertNull(ConnectionState.Failed("error").failedAddress)
    }

    @Test
    fun `failedAddress returns null for non-Failed states`() {
        assertNull(ConnectionState.Disconnected.failedAddress)
        assertNull(ConnectionState.Connecting("addr").failedAddress)
        assertNull(ConnectionState.Connected("addr", "name").failedAddress)
    }

    @Test
    fun `statusText returns correct text for each state`() {
        assertEquals("Disconnected", ConnectionState.Disconnected.statusText)
        assertEquals("Scanning...", ConnectionState.Scanning.statusText)
        assertEquals("Connecting...", ConnectionState.Connecting("addr").statusText)
        assertEquals("Discovering services...", ConnectionState.DiscoveringServices("addr").statusText)
        assertEquals("Connected to KS-S18", ConnectionState.Connected("addr", "KS-S18").statusText)
        assertEquals("Connection lost: timeout", ConnectionState.ConnectionLost("addr", "timeout").statusText)
        assertEquals("Failed: GATT error 133", ConnectionState.Failed("GATT error 133").statusText)
    }
}
