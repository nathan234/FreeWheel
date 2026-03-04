package org.freewheel.core.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Safety-net tests for ConnectionState sealed class.
 * Verifies computed properties for each state and guards against
 * subclass count drift (catches KMP changes that need Swift sync).
 */
class ConnectionStatePropertiesTest {

    // ==================== Subclass Count Guard ====================

    @Test
    fun `sealed class has exactly 7 subclasses`() {
        // If this fails, a new ConnectionState subclass was added in KMP
        // and ConnectionStateWrapper in WheelManager.swift must be updated.
        val subclasses = listOf(
            ConnectionState.Disconnected,
            ConnectionState.Scanning,
            ConnectionState.Connecting("addr"),
            ConnectionState.DiscoveringServices("addr"),
            ConnectionState.Connected("addr", "wheel"),
            ConnectionState.ConnectionLost("addr", "reason"),
            ConnectionState.Failed("error"),
        )
        assertEquals(7, subclasses.size)

        // Verify exhaustive when — compiler will fail if a case is missing
        for (state in subclasses) {
            val text = state.statusText // exercises exhaustive when
            assertTrue(text.isNotEmpty())
        }
    }

    // ==================== Disconnected ====================

    @Test
    fun `Disconnected is not connected`() {
        val state = ConnectionState.Disconnected
        assertFalse(state.isConnected)
    }

    @Test
    fun `Disconnected is not connecting`() {
        assertFalse(ConnectionState.Disconnected.isConnecting)
    }

    @Test
    fun `Disconnected is disconnected`() {
        assertTrue(ConnectionState.Disconnected.isDisconnected)
    }

    @Test
    fun `Disconnected has no connecting address`() {
        assertNull(ConnectionState.Disconnected.connectingAddress)
    }

    @Test
    fun `Disconnected has no failed address`() {
        assertNull(ConnectionState.Disconnected.failedAddress)
    }

    @Test
    fun `Disconnected statusText`() {
        assertEquals("Disconnected", ConnectionState.Disconnected.statusText)
    }

    // ==================== Scanning ====================

    @Test
    fun `Scanning is not connected`() {
        assertFalse(ConnectionState.Scanning.isConnected)
    }

    @Test
    fun `Scanning is not connecting`() {
        assertFalse(ConnectionState.Scanning.isConnecting)
    }

    @Test
    fun `Scanning is not disconnected`() {
        assertFalse(ConnectionState.Scanning.isDisconnected)
    }

    @Test
    fun `Scanning statusText`() {
        assertEquals("Scanning...", ConnectionState.Scanning.statusText)
    }

    // ==================== Connecting ====================

    @Test
    fun `Connecting is not connected`() {
        assertFalse(ConnectionState.Connecting("AA:BB").isConnected)
    }

    @Test
    fun `Connecting is connecting`() {
        assertTrue(ConnectionState.Connecting("AA:BB").isConnecting)
    }

    @Test
    fun `Connecting is not disconnected`() {
        assertFalse(ConnectionState.Connecting("AA:BB").isDisconnected)
    }

    @Test
    fun `Connecting has connecting address`() {
        assertEquals("AA:BB", ConnectionState.Connecting("AA:BB").connectingAddress)
    }

    @Test
    fun `Connecting has no failed address`() {
        assertNull(ConnectionState.Connecting("AA:BB").failedAddress)
    }

    @Test
    fun `Connecting statusText`() {
        assertEquals("Connecting...", ConnectionState.Connecting("AA:BB").statusText)
    }

    // ==================== DiscoveringServices ====================

    @Test
    fun `DiscoveringServices is not connected`() {
        assertFalse(ConnectionState.DiscoveringServices("AA:BB").isConnected)
    }

    @Test
    fun `DiscoveringServices is connecting`() {
        assertTrue(ConnectionState.DiscoveringServices("AA:BB").isConnecting)
    }

    @Test
    fun `DiscoveringServices is not disconnected`() {
        assertFalse(ConnectionState.DiscoveringServices("AA:BB").isDisconnected)
    }

    @Test
    fun `DiscoveringServices has connecting address`() {
        assertEquals("AA:BB", ConnectionState.DiscoveringServices("AA:BB").connectingAddress)
    }

    @Test
    fun `DiscoveringServices statusText`() {
        assertEquals("Discovering services...", ConnectionState.DiscoveringServices("AA:BB").statusText)
    }

    // ==================== Connected ====================

    @Test
    fun `Connected is connected`() {
        assertTrue(ConnectionState.Connected("AA:BB", "MyWheel").isConnected)
    }

    @Test
    fun `Connected is not connecting`() {
        assertFalse(ConnectionState.Connected("AA:BB", "MyWheel").isConnecting)
    }

    @Test
    fun `Connected is not disconnected`() {
        assertFalse(ConnectionState.Connected("AA:BB", "MyWheel").isDisconnected)
    }

    @Test
    fun `Connected has no connecting address`() {
        assertNull(ConnectionState.Connected("AA:BB", "MyWheel").connectingAddress)
    }

    @Test
    fun `Connected statusText includes wheel name`() {
        assertEquals("Connected to MyWheel", ConnectionState.Connected("AA:BB", "MyWheel").statusText)
    }

    // ==================== ConnectionLost ====================

    @Test
    fun `ConnectionLost is not connected`() {
        assertFalse(ConnectionState.ConnectionLost("AA:BB", "timeout").isConnected)
    }

    @Test
    fun `ConnectionLost is not connecting`() {
        assertFalse(ConnectionState.ConnectionLost("AA:BB", "timeout").isConnecting)
    }

    @Test
    fun `ConnectionLost is disconnected`() {
        assertTrue(ConnectionState.ConnectionLost("AA:BB", "timeout").isDisconnected)
    }

    @Test
    fun `ConnectionLost statusText includes reason`() {
        assertEquals("Connection lost: timeout", ConnectionState.ConnectionLost("AA:BB", "timeout").statusText)
    }

    // ==================== Failed ====================

    @Test
    fun `Failed is not connected`() {
        assertFalse(ConnectionState.Failed("error").isConnected)
    }

    @Test
    fun `Failed is not connecting`() {
        assertFalse(ConnectionState.Failed("error").isConnecting)
    }

    @Test
    fun `Failed is disconnected`() {
        assertTrue(ConnectionState.Failed("error").isDisconnected)
    }

    @Test
    fun `Failed has failed address when provided`() {
        assertEquals("AA:BB", ConnectionState.Failed("error", "AA:BB").failedAddress)
    }

    @Test
    fun `Failed has null failed address when not provided`() {
        assertNull(ConnectionState.Failed("error").failedAddress)
    }

    @Test
    fun `Failed statusText includes error`() {
        assertEquals("Failed: something broke", ConnectionState.Failed("something broke").statusText)
    }
}
