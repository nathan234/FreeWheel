package org.freewheel.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticsTest {

    // ---- redactMac --------------------------------------------------------

    @Test
    fun `redactMac null passes through`() {
        assertNull(redactMac(null))
    }

    @Test
    fun `redactMac with colons preserves OUI and last byte`() {
        assertEquals("AA:BB:CC:**:**:FF", redactMac("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `redactMac preserves dash separator`() {
        assertEquals("aa-bb-cc-**-**-ff", redactMac("aa-bb-cc-dd-ee-ff"))
    }

    @Test
    fun `redactMac no separator masks middle bytes`() {
        assertEquals("AABBCC****FF", redactMac("AABBCCDDEEFF"))
    }

    @Test
    fun `redactMac empty string returns empty`() {
        assertEquals("", redactMac(""))
    }

    @Test
    fun `redactMac unexpected format passes through`() {
        // Not 6 parts — leave alone rather than corrupt.
        assertEquals("AA:BB:CC", redactMac("AA:BB:CC"))
    }

    // ---- DiagnosticEventEncoder ------------------------------------------

    @Test
    fun `encodes all fields and no context as compact JSONL`() {
        val event = DiagnosticEvent(
            timestampMs = 1_700_000_000_000L,
            level = DiagLevel.WARN,
            category = DiagCategory.RIDE,
            type = "LOG_PAUSE",
            sessionId = "abc",
            message = "Ride paused (connection_lost)",
        )
        val line = DiagnosticEventEncoder.encodeLine(event)
        assertTrue(line.startsWith("{"))
        assertTrue(line.endsWith("}"))
        assertTrue(line.contains("\"level\":\"warn\""))
        assertTrue(line.contains("\"category\":\"ride\""))
        assertTrue(line.contains("\"type\":\"LOG_PAUSE\""))
        assertTrue(line.contains("\"session\":\"abc\""))
        assertTrue(line.contains("\"message\":\"Ride paused (connection_lost)\""))
    }

    @Test
    fun `encodes context with insertion order preserved`() {
        val event = DiagnosticEvent(
            timestampMs = 0L,
            level = DiagLevel.INFO,
            category = DiagCategory.RECOVERY,
            type = "RECOVERED",
            message = "ok",
            context = LinkedHashMap<String, JsonValue>().apply {
                put("first", JsonValue.of("alpha"))
                put("count", JsonValue.of(3))
                put("ratio", JsonValue.of(0.5))
                put("on", JsonValue.of(true))
            },
        )
        val line = DiagnosticEventEncoder.encodeLine(event)
        val ctxStart = line.indexOf("\"context\":{")
        assertTrue(ctxStart > 0)
        val ctx = line.substring(ctxStart + "\"context\":{".length)
        // Use quoted-key tokens to avoid substring collisions with literal text.
        assertTrue(ctx.indexOf("\"first\"") < ctx.indexOf("\"count\""))
        assertTrue(ctx.indexOf("\"count\"") < ctx.indexOf("\"ratio\""))
        assertTrue(ctx.indexOf("\"ratio\"") < ctx.indexOf("\"on\""))
        assertTrue(ctx.contains("\"count\":3"))
        assertTrue(ctx.contains("\"ratio\":0.5"))
        assertTrue(ctx.contains("\"on\":true"))
    }

    @Test
    fun `escapes special characters in strings`() {
        val event = DiagnosticEvent(
            timestampMs = 0L,
            level = DiagLevel.ERROR,
            category = DiagCategory.SYSTEM,
            type = "T",
            message = "He said \"hi\"\nand left",
        )
        val line = DiagnosticEventEncoder.encodeLine(event)
        // Resulting JSON should round-trip "...He said \"hi\"\nand left..."
        assertTrue(line.contains("He said \\\"hi\\\""))
        assertTrue(line.contains("\\nand left"))
    }

    @Test
    fun `omits session when null`() {
        val event = DiagnosticEvent(
            timestampMs = 0L,
            level = DiagLevel.INFO,
            category = DiagCategory.SYSTEM,
            type = "T",
            sessionId = null,
            message = "x",
        )
        val line = DiagnosticEventEncoder.encodeLine(event)
        assertTrue(!line.contains("\"session\":"))
    }
}
