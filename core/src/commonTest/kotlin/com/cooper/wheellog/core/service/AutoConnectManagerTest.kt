package com.cooper.wheellog.core.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AutoConnectManagerTest {

    private fun TestScope.createManager(
        connectionState: MutableStateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected),
        connectCalls: MutableList<String> = mutableListOf()
    ): Triple<AutoConnectManager, MutableStateFlow<ConnectionState>, MutableList<String>> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = AutoConnectManager(
            connectionState = connectionState,
            connect = { address -> connectCalls.add(address) },
            scope = this,
            dispatcher = dispatcher
        )
        // Start the init observer coroutine
        runCurrent()
        return Triple(manager, connectionState, connectCalls)
    }

    // --- Startup auto-connect ---

    @Test
    fun `attemptStartupConnect with blank address is no-op`() = runTest {
        val (manager, _, connectCalls) = createManager()

        manager.attemptStartupConnect("")
        runCurrent()

        assertFalse(manager.isAutoConnecting.value)
        assertTrue(connectCalls.isEmpty())

        manager.destroy()
    }

    @Test
    fun `attemptStartupConnect sets flag and calls connect`() = runTest {
        val (manager, _, connectCalls) = createManager()

        manager.attemptStartupConnect("AA:BB:CC:DD:EE:FF")

        // Flag set synchronously
        assertTrue(manager.isAutoConnecting.value)

        // Let the coroutine run to call connect
        runCurrent()

        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), connectCalls)
        assertTrue(manager.isAutoConnecting.value) // Still true before timeout

        manager.destroy()
    }

    @Test
    fun `timeout clears auto-connect flag after 10 seconds`() = runTest {
        val (manager, _, _) = createManager()

        manager.attemptStartupConnect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        assertTrue(manager.isAutoConnecting.value)

        // Just before timeout
        advanceTimeBy(9_999)
        assertTrue(manager.isAutoConnecting.value)

        // After timeout
        advanceTimeBy(2)
        assertFalse(manager.isAutoConnecting.value)

        manager.destroy()
    }

    @Test
    fun `Connected state clears auto-connect flag and cancels timeout`() = runTest {
        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val (manager, _, _) = createManager(connectionState)

        manager.attemptStartupConnect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        assertTrue(manager.isAutoConnecting.value)

        // Simulate successful connection
        connectionState.value = ConnectionState.Connected("AA:BB:CC:DD:EE:FF", "TestWheel")
        runCurrent()

        assertFalse(manager.isAutoConnecting.value)

        manager.destroy()
    }

    @Test
    fun `Failed state clears auto-connect flag`() = runTest {
        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val (manager, _, _) = createManager(connectionState)

        manager.attemptStartupConnect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        assertTrue(manager.isAutoConnecting.value)

        connectionState.value = ConnectionState.Failed("Connection refused")
        runCurrent()

        assertFalse(manager.isAutoConnecting.value)

        manager.destroy()
    }

    @Test
    fun `stop cancels startup auto-connect`() = runTest {
        val (manager, _, _) = createManager()

        manager.attemptStartupConnect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        assertTrue(manager.isAutoConnecting.value)

        manager.stop()

        assertFalse(manager.isAutoConnecting.value)

        manager.destroy()
    }

    @Test
    fun `custom timeout is respected`() = runTest {
        val (manager, _, _) = createManager()

        manager.attemptStartupConnect("AA:BB:CC:DD:EE:FF", timeoutMs = 5_000)
        runCurrent()
        assertTrue(manager.isAutoConnecting.value)

        advanceTimeBy(4_999)
        assertTrue(manager.isAutoConnecting.value)

        advanceTimeBy(2)
        assertFalse(manager.isAutoConnecting.value)

        manager.destroy()
    }

    // --- Reconnect after loss ---

    @Test
    fun `startReconnecting with blank address is no-op`() = runTest {
        val (manager, _, connectCalls) = createManager()

        manager.startReconnecting("")
        runCurrent()

        assertEquals(AutoConnectManager.ReconnectState.Idle, manager.reconnectState.value)
        assertTrue(connectCalls.isEmpty())

        manager.destroy()
    }

    @Test
    fun `startReconnecting follows exponential backoff`() = runTest {
        val connectCalls = mutableListOf<String>()
        val (manager, _, _) = createManager(connectCalls = connectCalls)

        manager.startReconnecting("AA:BB:CC:DD:EE:FF", backoffMs = listOf(1_000L, 2_000L, 4_000L))
        runCurrent()

        // Initially waiting for attempt 1
        val state1 = manager.reconnectState.value
        assertTrue(state1 is AutoConnectManager.ReconnectState.Waiting)
        assertEquals(1, (state1 as AutoConnectManager.ReconnectState.Waiting).attempt)
        assertEquals(1_000L, state1.nextRetryMs)

        // After first delay: should attempt
        advanceTimeBy(1_000)
        runCurrent()
        val state2 = manager.reconnectState.value
        assertTrue(state2 is AutoConnectManager.ReconnectState.Attempting, "Expected Attempting but got $state2")
        assertEquals(1, (state2 as AutoConnectManager.ReconnectState.Attempting).attempt)
        assertEquals(1, connectCalls.size)

        // After settle time (10s): should be waiting for attempt 2
        advanceTimeBy(10_000)
        runCurrent()
        val state3 = manager.reconnectState.value
        assertTrue(state3 is AutoConnectManager.ReconnectState.Waiting, "Expected Waiting but got $state3")
        assertEquals(2, (state3 as AutoConnectManager.ReconnectState.Waiting).attempt)
        assertEquals(2_000L, state3.nextRetryMs)

        // After second delay
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, connectCalls.size)

        // After settle: attempt 3
        advanceTimeBy(10_000)
        runCurrent()
        val state5 = manager.reconnectState.value
        assertTrue(state5 is AutoConnectManager.ReconnectState.Waiting, "Expected Waiting but got $state5")
        assertEquals(3, (state5 as AutoConnectManager.ReconnectState.Waiting).attempt)
        assertEquals(4_000L, state5.nextRetryMs)

        manager.destroy()
    }

    @Test
    fun `backoff reuses last value for extra attempts`() = runTest {
        val connectCalls = mutableListOf<String>()
        val (manager, _, _) = createManager(connectCalls = connectCalls)

        manager.startReconnecting("AA:BB:CC:DD:EE:FF", backoffMs = listOf(500L))
        runCurrent()

        // First attempt after 500ms
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, connectCalls.size)

        // Settle
        advanceTimeBy(10_000)
        runCurrent()

        // Second attempt also after 500ms (reuses last)
        val state = manager.reconnectState.value
        assertTrue(state is AutoConnectManager.ReconnectState.Waiting)
        assertEquals(500L, (state as AutoConnectManager.ReconnectState.Waiting).nextRetryMs)

        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, connectCalls.size)

        manager.destroy()
    }

    @Test
    fun `Connected state stops reconnect loop`() = runTest {
        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val connectCalls = mutableListOf<String>()
        val (manager, _, _) = createManager(connectionState, connectCalls)

        manager.startReconnecting("AA:BB:CC:DD:EE:FF", backoffMs = listOf(1_000L))
        runCurrent()

        // First attempt
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, connectCalls.size)

        // Connection succeeds
        connectionState.value = ConnectionState.Connected("AA:BB:CC:DD:EE:FF", "TestWheel")
        runCurrent()

        assertEquals(AutoConnectManager.ReconnectState.Idle, manager.reconnectState.value)

        // No more attempts after long time
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, connectCalls.size)

        manager.destroy()
    }

    @Test
    fun `stop during reconnect returns to Idle`() = runTest {
        val (manager, _, _) = createManager()

        manager.startReconnecting("AA:BB:CC:DD:EE:FF")
        runCurrent()

        assertTrue(manager.reconnectState.value is AutoConnectManager.ReconnectState.Waiting)

        manager.stop()

        assertEquals(AutoConnectManager.ReconnectState.Idle, manager.reconnectState.value)

        manager.destroy()
    }

    @Test
    fun `stop cancels both startup and reconnect`() = runTest {
        val (manager, _, _) = createManager()

        manager.attemptStartupConnect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.startReconnecting("AA:BB:CC:DD:EE:FF")
        runCurrent()

        assertTrue(manager.isAutoConnecting.value)
        assertTrue(manager.reconnectState.value is AutoConnectManager.ReconnectState.Waiting)

        manager.stop()

        assertFalse(manager.isAutoConnecting.value)
        assertEquals(AutoConnectManager.ReconnectState.Idle, manager.reconnectState.value)

        manager.destroy()
    }

    // --- Edge cases ---

    @Test
    fun `ConnectionLost does not clear auto-connect flag`() = runTest {
        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val (manager, _, _) = createManager(connectionState)

        manager.attemptStartupConnect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        assertTrue(manager.isAutoConnecting.value)

        // ConnectionLost should NOT clear the flag (only Connected and Failed do)
        connectionState.value = ConnectionState.ConnectionLost("AA:BB:CC:DD:EE:FF", "timeout")
        runCurrent()

        assertTrue(manager.isAutoConnecting.value)

        manager.destroy()
    }

    @Test
    fun `starting new startup connect cancels previous`() = runTest {
        val connectCalls = mutableListOf<String>()
        val (manager, _, _) = createManager(connectCalls = connectCalls)

        manager.attemptStartupConnect("FIRST")
        runCurrent()
        assertEquals(listOf("FIRST"), connectCalls)

        // Start another — should cancel the first timeout
        manager.attemptStartupConnect("SECOND")
        runCurrent()
        assertEquals(listOf("FIRST", "SECOND"), connectCalls)

        // Only second timeout should fire at 10s
        advanceTimeBy(10_001)
        assertFalse(manager.isAutoConnecting.value)

        manager.destroy()
    }

    @Test
    fun `starting new reconnect cancels previous`() = runTest {
        val connectCalls = mutableListOf<String>()
        val (manager, _, _) = createManager(connectCalls = connectCalls)

        manager.startReconnecting("FIRST", backoffMs = listOf(5_000L))
        runCurrent()

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, connectCalls.size) // Still waiting

        // Start new reconnect — cancels previous
        manager.startReconnecting("SECOND", backoffMs = listOf(1_000L))
        runCurrent()

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("SECOND"), connectCalls)

        manager.destroy()
    }

    @Test
    fun `destroy stops everything and cancels observer`() = runTest {
        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val (manager, _, _) = createManager(connectionState)

        manager.attemptStartupConnect("AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.startReconnecting("AA:BB:CC:DD:EE:FF")
        runCurrent()

        manager.destroy()

        assertFalse(manager.isAutoConnecting.value)
        assertEquals(AutoConnectManager.ReconnectState.Idle, manager.reconnectState.value)

        // Changing connection state should not cause issues after destroy
        connectionState.value = ConnectionState.Connected("AA:BB:CC:DD:EE:FF", "TestWheel")
        runCurrent()
    }
}
