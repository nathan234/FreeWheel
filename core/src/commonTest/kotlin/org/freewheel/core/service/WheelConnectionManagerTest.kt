package org.freewheel.core.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Additional tests for WheelConnectionManager-related classes.
 * Covers ConnectionState, BleDevice, and timer edge cases.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WheelConnectionManagerTest {

    // ==================== ConnectionState Additional Tests ====================

    @Test
    fun `Disconnected is a singleton`() {
        val state1 = ConnectionState.Disconnected
        val state2 = ConnectionState.Disconnected

        assertEquals(state1, state2)
        assertTrue(state1 === state2)  // Same instance
    }

    @Test
    fun `Scanning is a singleton`() {
        val state1 = ConnectionState.Scanning
        val state2 = ConnectionState.Scanning

        assertEquals(state1, state2)
        assertTrue(state1 === state2)
    }

    @Test
    fun `Connecting stores address`() {
        val state = ConnectionState.Connecting("AA:BB:CC:DD:EE:FF")

        assertEquals("AA:BB:CC:DD:EE:FF", state.address)
    }

    @Test
    fun `Connecting with same address are equal`() {
        val state1 = ConnectionState.Connecting("AA:BB:CC:DD:EE:FF")
        val state2 = ConnectionState.Connecting("AA:BB:CC:DD:EE:FF")

        assertEquals(state1, state2)
    }

    @Test
    fun `Connecting with different address are not equal`() {
        val state1 = ConnectionState.Connecting("AA:BB:CC:DD:EE:FF")
        val state2 = ConnectionState.Connecting("11:22:33:44:55:66")

        assertNotEquals(state1, state2)
    }

    @Test
    fun `DiscoveringServices stores address`() {
        val state = ConnectionState.DiscoveringServices("AA:BB:CC:DD:EE:FF")

        assertEquals("AA:BB:CC:DD:EE:FF", state.address)
    }

    @Test
    fun `Connected stores address and wheel name`() {
        val state = ConnectionState.Connected("AA:BB:CC:DD:EE:FF", "KS-S18")

        assertEquals("AA:BB:CC:DD:EE:FF", state.address)
        assertEquals("KS-S18", state.wheelName)
    }

    @Test
    fun `Connected with empty wheel name`() {
        val state = ConnectionState.Connected("AA:BB:CC:DD:EE:FF", "")

        assertEquals("AA:BB:CC:DD:EE:FF", state.address)
        assertEquals("", state.wheelName)
    }

    @Test
    fun `ConnectionLost stores address and reason`() {
        val state = ConnectionState.ConnectionLost("AA:BB:CC:DD:EE:FF", "Timeout")

        assertEquals("AA:BB:CC:DD:EE:FF", state.address)
        assertEquals("Timeout", state.reason)
    }

    @Test
    fun `Failed stores error message`() {
        val state = ConnectionState.Failed("Connection refused")

        assertEquals("Connection refused", state.error)
    }

    @Test
    fun `isConnected is false for Scanning`() {
        assertFalse(ConnectionState.Scanning.isConnected)
    }

    @Test
    fun `isConnecting is false for ConnectionLost`() {
        assertFalse(ConnectionState.ConnectionLost("addr", "reason").isConnecting)
    }

    @Test
    fun `isDisconnected is false for Scanning`() {
        assertFalse(ConnectionState.Scanning.isDisconnected)
    }

    @Test
    fun `isDisconnected is false for DiscoveringServices`() {
        assertFalse(ConnectionState.DiscoveringServices("addr").isDisconnected)
    }

    // ==================== ConnectionState Transition Scenarios ====================

    @Test
    fun `typical connection flow states`() {
        // Simulate typical connection flow
        val states = listOf(
            ConnectionState.Disconnected,
            ConnectionState.Connecting("AA:BB:CC:DD:EE:FF"),
            ConnectionState.DiscoveringServices("AA:BB:CC:DD:EE:FF"),
            ConnectionState.Connected("AA:BB:CC:DD:EE:FF", "Sherman Max")
        )

        // First state is disconnected
        assertTrue(states[0].isDisconnected)
        assertFalse(states[0].isConnecting)
        assertFalse(states[0].isConnected)

        // Second state is connecting
        assertFalse(states[1].isDisconnected)
        assertTrue(states[1].isConnecting)
        assertFalse(states[1].isConnected)

        // Third state is still connecting (discovering services)
        assertFalse(states[2].isDisconnected)
        assertTrue(states[2].isConnecting)
        assertFalse(states[2].isConnected)

        // Fourth state is connected
        assertFalse(states[3].isDisconnected)
        assertFalse(states[3].isConnecting)
        assertTrue(states[3].isConnected)
    }

    @Test
    fun `connection lost recovery scenario`() {
        val connected = ConnectionState.Connected("addr", "wheel")
        val lost = ConnectionState.ConnectionLost("addr", "Signal lost")
        val reconnecting = ConnectionState.Connecting("addr")

        // Was connected
        assertTrue(connected.isConnected)

        // Connection lost - considered disconnected
        assertTrue(lost.isDisconnected)
        assertFalse(lost.isConnected)

        // Reconnecting
        assertTrue(reconnecting.isConnecting)
        assertFalse(reconnecting.isDisconnected)
    }

    // ==================== BleDevice Tests ====================

    @Test
    fun `BleDevice stores all properties`() {
        val device = BleDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "KS-S18",
            rssi = -65
        )

        assertEquals("AA:BB:CC:DD:EE:FF", device.address)
        assertEquals("KS-S18", device.name)
        assertEquals(-65, device.rssi)
    }

    @Test
    fun `BleDevice with null name`() {
        val device = BleDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = null,
            rssi = -70
        )

        assertNull(device.name)
    }

    @Test
    fun `BleDevice equality`() {
        val device1 = BleDevice("AA:BB:CC:DD:EE:FF", "Wheel", -65)
        val device2 = BleDevice("AA:BB:CC:DD:EE:FF", "Wheel", -65)

        assertEquals(device1, device2)
        assertEquals(device1.hashCode(), device2.hashCode())
    }

    @Test
    fun `BleDevice not equal with different address`() {
        val device1 = BleDevice("AA:BB:CC:DD:EE:FF", "Wheel", -65)
        val device2 = BleDevice("11:22:33:44:55:66", "Wheel", -65)

        assertNotEquals(device1, device2)
    }

    @Test
    fun `BleDevice not equal with different name`() {
        val device1 = BleDevice("AA:BB:CC:DD:EE:FF", "Wheel1", -65)
        val device2 = BleDevice("AA:BB:CC:DD:EE:FF", "Wheel2", -65)

        assertNotEquals(device1, device2)
    }

    @Test
    fun `BleDevice not equal with different rssi`() {
        val device1 = BleDevice("AA:BB:CC:DD:EE:FF", "Wheel", -65)
        val device2 = BleDevice("AA:BB:CC:DD:EE:FF", "Wheel", -70)

        assertNotEquals(device1, device2)
    }

    @Test
    fun `BleDevice copy preserves values`() {
        val original = BleDevice("AA:BB:CC:DD:EE:FF", "Wheel", -65)
        val copied = original.copy()

        assertEquals(original, copied)
    }

    @Test
    fun `BleDevice copy can modify values`() {
        val original = BleDevice("AA:BB:CC:DD:EE:FF", "Wheel", -65)
        val modified = original.copy(rssi = -80)

        assertEquals("AA:BB:CC:DD:EE:FF", modified.address)
        assertEquals("Wheel", modified.name)
        assertEquals(-80, modified.rssi)
    }

    @Test
    fun `BleDevice typical RSSI values`() {
        // Strong signal
        val strong = BleDevice("addr", "name", -50)
        assertTrue(strong.rssi > -60)

        // Medium signal
        val medium = BleDevice("addr", "name", -70)
        assertTrue(medium.rssi > -80 && medium.rssi <= -60)

        // Weak signal
        val weak = BleDevice("addr", "name", -90)
        assertTrue(weak.rssi <= -80)
    }

    // ==================== KeepAliveTimer Additional Tests ====================

    @Test
    fun `KeepAliveTimer restart without stop preserves callback`() = runTest {
        val timer = KeepAliveTimer(this, UnconfinedTestDispatcher(testScheduler))
        var tickCount = 0

        timer.start(intervalMs = 50) {
            tickCount++
        }

        advanceTimeBy(120) // 2 ticks
        val countBeforeRestart = tickCount

        // Restart without stop - callback is preserved
        timer.restart()
        advanceTimeBy(120) // 2 more ticks
        timer.stop()

        assertTrue(tickCount > countBeforeRestart, "Should tick after restart")
    }

    @Test
    fun `KeepAliveTimer restart without prior start does nothing`() = runTest {
        val timer = KeepAliveTimer(this, UnconfinedTestDispatcher(testScheduler))

        // Restart without ever starting
        timer.restart()

        assertFalse(timer.isRunning.value)
    }

    @Test
    fun `KeepAliveTimer handles exception in callback`() = runTest {
        val timer = KeepAliveTimer(this, UnconfinedTestDispatcher(testScheduler))
        var callCount = 0

        timer.start(intervalMs = 50) {
            callCount++
            if (callCount == 1) {
                throw RuntimeException("Test exception")
            }
        }

        advanceTimeBy(150) // Should continue despite exception
        timer.stop()

        assertTrue(callCount >= 2, "Timer should continue after exception")
    }

    @Test
    fun `KeepAliveTimer with negative interval does not start`() = runTest {
        val timer = KeepAliveTimer(this, UnconfinedTestDispatcher(testScheduler))

        timer.start(intervalMs = -100) {}

        assertFalse(timer.isRunning.value)
    }

    @Test
    fun `KeepAliveTimer stop is idempotent`() = runTest {
        val timer = KeepAliveTimer(this, UnconfinedTestDispatcher(testScheduler))

        timer.start(intervalMs = 50) {}

        timer.stop()
        timer.stop()  // Second stop should not throw
        timer.stop()  // Third stop should not throw

        assertFalse(timer.isRunning.value)
    }

    // ==================== DataTimeoutTracker Additional Tests ====================

    @Test
    fun `DataTimeoutTracker DEFAULT_TIMEOUT_MS is 60 seconds`() {
        assertEquals(60_000L, DataTimeoutTracker.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun `DataTimeoutTracker multiple onDataReceived calls reset timer`() = runTest {
        val tracker = DataTimeoutTracker(this, UnconfinedTestDispatcher(testScheduler))

        tracker.start(timeoutMs = 10000) {}

        tracker.onDataReceived()
        val time1 = tracker.timeSinceLastDataMs()

        tracker.onDataReceived()
        val time2 = tracker.timeSinceLastDataMs()

        // Both should be very small
        assertTrue(time1 < 100)
        assertTrue(time2 < 100)

        tracker.stop()
    }

    @Test
    fun `DataTimeoutTracker stop is idempotent`() = runTest {
        val tracker = DataTimeoutTracker(this, UnconfinedTestDispatcher(testScheduler))

        tracker.start(timeoutMs = 1000) {}

        tracker.stop()
        tracker.stop()  // Should not throw
        tracker.stop()

        assertFalse(tracker.isTimedOut.value)
    }

    // ==================== CommandScheduler Additional Tests ====================

    @Test
    fun `CommandScheduler handles exception in command`() = runTest {
        val scheduler = CommandScheduler(this, UnconfinedTestDispatcher(testScheduler))
        var secondExecuted = false

        scheduler.schedule(50) {
            throw RuntimeException("Test exception")
        }

        scheduler.schedule(100) {
            secondExecuted = true
        }

        advanceTimeBy(150)

        assertTrue(secondExecuted, "Second command should execute despite first failing")
    }

    @Test
    fun `CommandScheduler cancelAll is idempotent`() = runTest {
        val scheduler = CommandScheduler(this, UnconfinedTestDispatcher(testScheduler))

        scheduler.schedule(100) {}
        scheduler.schedule(200) {}

        scheduler.cancelAll()
        scheduler.cancelAll()  // Should not throw
        scheduler.cancelAll()

        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun `CommandScheduler schedule with zero delay executes immediately`() = runTest {
        val scheduler = CommandScheduler(this, UnconfinedTestDispatcher(testScheduler))
        var executed = false

        scheduler.schedule(0) {
            executed = true
        }

        advanceTimeBy(10)

        assertTrue(executed, "Zero delay command should execute immediately")
    }

    @Test
    fun `CommandScheduler pendingCount decreases after execution`() = runTest {
        val scheduler = CommandScheduler(this, UnconfinedTestDispatcher(testScheduler))

        scheduler.schedule(50) {}
        scheduler.schedule(50) {}
        scheduler.schedule(50) {}

        assertTrue(scheduler.pendingCount() >= 3)

        advanceTimeBy(100)

        // After execution and cleanup, pending should be 0
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun `CommandScheduler empty after cancelAll`() = runTest {
        val scheduler = CommandScheduler(this, UnconfinedTestDispatcher(testScheduler))

        for (i in 1..10) {
            scheduler.schedule(i * 100L) {}
        }

        scheduler.cancelAll()

        assertEquals(0, scheduler.pendingCount())
    }

    // ==================== Real World Scenarios ====================

    @Test
    fun `simulated InMotion V2 keep-alive pattern`() = runTest {
        // InMotion V2 requires 25ms keep-alive
        val timer = KeepAliveTimer(this, UnconfinedTestDispatcher(testScheduler))
        var commandsSent = 0

        timer.start(intervalMs = 25) {
            commandsSent++
        }

        // Run for 1 second
        advanceTimeBy(1000)
        timer.stop()

        // Should send roughly 40 commands (1000/25 = 40)
        assertTrue(commandsSent >= 35, "Should send ~40 commands, got $commandsSent")
    }

    @Test
    fun `simulated Ninebot keep-alive pattern`() = runTest {
        // Ninebot requires 125ms keep-alive
        val timer = KeepAliveTimer(this, UnconfinedTestDispatcher(testScheduler))
        var commandsSent = 0

        timer.start(intervalMs = 125) {
            commandsSent++
        }

        // Run for 1 second
        advanceTimeBy(1000)
        timer.stop()

        // Should send roughly 8 commands (1000/125 = 8)
        assertTrue(commandsSent >= 6, "Should send ~8 commands, got $commandsSent")
    }

    @Test
    fun `connection state progression with wheel names`() {
        val wheelNames = listOf(
            "KS-S18", "KS-S22", "KS-16X",      // Kingsong
            "Sherman Max", "Abrams", "Patton",  // Veteran
            "Master", "Nikola+", "MCM5",        // Gotway/Begode
            "V11", "V12", "V13"                 // InMotion
        )

        for (name in wheelNames) {
            val connected = ConnectionState.Connected("addr", name)
            assertEquals(name, connected.wheelName)
            assertTrue(connected.isConnected)
        }
    }
}
