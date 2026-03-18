package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comparison tests verifying KMP InMotionDecoder produces identical results
 * to the legacy InMotionAdapter using real packet data from legacy tests.
 *
 * These tests use the same hex packet data and expected values from
 * InMotionAdapterTest.kt to ensure byte-for-byte compatibility.
 */
class InMotionDecoderComparisonTest {

    private val decoder = InMotionDecoder()
    private val defaultState = WheelState()
    private val defaultConfig = DecoderConfig()


    // ==================== V5F Full Data ====================

    @Test
    fun `decode V5F full data matches legacy`() {
        // From InMotionAdapterTest: decode with v5f full data
        // Slow info packets (model/serial/version)
        val slowPackets = listOf(
            "AAAA1401A5550F7C000000B4720020FE0001001B",
            "0076BA5C28711200000000000000000100000000",
            "000000FA010301FA0103010402020100000000C2",
            "040001C2040001900302010000000000000000A8",
            "6100000010000000000000000000000000000000",
            "0000000100000000000000000000000000000000",
            "0000000200000500000000000000000000000004",
            "020301E35555"
        )
        // Fast info packets (telemetry)
        val fastPackets = listOf(
            "AAAA1301A5550F60000000B4720020FE000100FF",
            "3F00003A18DEFF5D01000029F0FFFF29F0FFFFEC",
            "FFFFFF15200000000000001A1A00000000000000",
            "0000001CE3130000000000000026061A03D20721",
            "0000006F0100006F010000F7010000420C00002B",
            "110000070000000000000000000000265555"
        )

        decoder.reset()
        var state = defaultState

        // Feed slow info packets
        for (hex in slowPackets) {
            val result = decoder.decode(hex.hexToByteArray(), state, defaultConfig)
            if (result is DecodeResult.Success) state = result.data.newState!!
        }

        // Verify slow info results (model/serial/version)
        assertEquals("1271285CBA76001B", state.serialNumber, "Serial should match legacy")
        assertEquals("InMotion V5F", state.model, "Model should match legacy")
        assertEquals("1.3.506", state.version, "Version should match legacy")

        // Feed fast info packets
        var hasNewData = false
        for (hex in fastPackets) {
            val result = decoder.decode(hex.hexToByteArray(), state, defaultConfig)
            if (result is DecodeResult.Success) {
                state = result.data.newState!!
                if (result.data.hasNewData) hasNewData = true
            }
        }

        // Verify the last fast info packet produced new data
        assertTrue(hasNewData, "Should have new telemetry data")

        // Verify telemetry matches legacy expected values
        // KMP rounds (3.83) vs legacy truncates (3.82); rounding is more correct
        assertEquals(383, state.speed, "Raw speed should be 383 (3.83 km/h in 1/100 units)")
        assertEquals(3.83, state.speedKmh, 0.01, "Speed should be 3.83 km/h")
        assertEquals(26, state.temperatureC, "Temperature should be 26°C")
        assertEquals(0, state.imuTemp, "IMU temp should be 0")
        assertEquals(82.13, state.voltageV, 0.01, "Voltage should be 82.13V")
        assertEquals(-0.2, state.currentA, 0.01, "Current should be -0.2A")
        assertEquals(0.0, state.wheelDistanceKm, 0.001, "Wheel distance should be 0.0 km")
        assertEquals(1303324, state.totalDistance.toInt(), "Total distance should be 1303324m")
        assertEquals(97, state.batteryLevel, "Battery should be 97%")
        assertEquals(0.2499847412109375, state.angle, 1e-10, "Angle should match legacy")
        assertEquals(5.588888888888889, state.roll, 1e-10, "Roll should match legacy")
    }

    // ==================== V8F Full Data ====================

    @Test
    fun `decode V8F full data matches legacy`() {
        // From InMotionAdapterTest: decode with v8f full data
        val slowPackets = listOf(
            "AAAA1401A5550F8500000000000000FE0201000E",
            "009BBD5E4A601400000000000000000000000000",
            "0000001500020200000000070003020000000026",
            "0301010000000000000A000000000073000000C8",
            "AF00002510000000100000000000000000000000",
            "0000000100000000000000000000000000000000",
            "0000000600000800000000000000000000000000",
            "000000801027000001010A00DC5555"
        )
        val fastPackets = listOf(
            "AAAA1301A5550F9500000000000000FE0201008F",
            "020000000000000000000054FAFFFF54FAFFFFFB",
            "FFFFFFBE200000000000001B1B24240000000000",
            "000000AF5400000100000000302B140605E00722",
            "00000023000000C50000005D020000D900000006",
            "000000000000000000000000000000004000081B",
            "0000F221000033060000000000000B0000006216",
            "0000F42A0000030000000E000000110106000000",
            "000000000000C500765555"
        )

        decoder.reset()
        var state = defaultState

        for (hex in slowPackets) {
            val result = decoder.decode(hex.hexToByteArray(), state, defaultConfig)
            if (result is DecodeResult.Success) state = result.data.newState!!
        }

        assertEquals("14604A5EBD9B000E", state.serialNumber, "Serial should match legacy")
        assertEquals("InMotion V8F", state.model, "Model should match legacy")
        assertEquals("2.2.21", state.version, "Version should match legacy")

        var hasNewData = false
        for (hex in fastPackets) {
            val result = decoder.decode(hex.hexToByteArray(), state, defaultConfig)
            if (result is DecodeResult.Success) {
                state = result.data.newState!!
                if (result.data.hasNewData) hasNewData = true
            }
        }

        assertTrue(hasNewData, "Should have new telemetry data")

        assertEquals(137, state.speed, "Raw speed should be 137 (1.37 km/h in 1/100 units)")
        assertEquals(1.37, state.speedKmh, 0.01, "Speed should be 1.37 km/h")
        assertEquals(27, state.temperatureC, "Temperature should be 27°C")
        assertEquals(36, state.imuTemp, "IMU temp should be 36")
        assertEquals(83.82, state.voltageV, 0.01, "Voltage should be 83.82V")
        assertEquals(-0.05, state.currentA, 0.01, "Current should be -0.05A")
        assertEquals(0.001, state.wheelDistanceKm, 0.001, "Wheel distance should be 0.001 km")
        assertEquals(21679, state.totalDistance.toInt(), "Total distance should be 21679m")
        assertEquals(100, state.batteryLevel, "Battery should be 100%")
        assertEquals(0.0099945068359375, state.angle, 1e-10, "Angle should match legacy")
        assertEquals(0.0, state.roll, 0.001, "Roll should be 0.0")
        assertEquals("Drive", state.modeStr, "Mode should be Drive")
    }

    // ==================== V8F Full Data 2 ====================

    @Test
    fun `decode V8F full data 2 matches legacy`() {
        // From InMotionAdapterTest: decode with v8f full data 2
        // Same slow packets as V8F test above
        val slowPackets = listOf(
            "AAAA1401A5550F8500000000000000FE0201000E",
            "009BBD5E4A601400000000000000000000000000",
            "0000001500020200000000070003020000000026",
            "0301010000000000000A000000000073000000C8",
            "AF00002510000000100000000000000000000000",
            "0000000100000000000000000000000000000000",
            "0000000600000800000000000000000000000000",
            "000000801027000001010A00DC5555"
        )
        // Different telemetry snapshot
        val fastPackets = listOf(
            "AAAA1301A5550F9500000000000000FE0201007A",
            "14000000000000000000003CFDFFFF3CFDFFFFF6",
            "FFFFFFA7200000400100001C1C2424F8FFFFFFE7",
            "FFFFFFB75400000900000000042C140605E00722",
            "000000E301000023010000AC0500000302000056",
            "0000004C0000000000000000000000004000081C",
            "0000F221000033060000BF020000070100006F16",
            "0000032B0000100000001D000000380256004C00",
            "F8FFE7FFE7FF2301465555"
        )

        decoder.reset()
        var state = defaultState

        for (hex in slowPackets) {
            val result = decoder.decode(hex.hexToByteArray(), state, defaultConfig)
            if (result is DecodeResult.Success) state = result.data.newState!!
        }

        assertEquals("InMotion V8F", state.model, "Model should match legacy")
        assertEquals("2.2.21", state.version, "Version should match legacy")

        var hasNewData = false
        for (hex in fastPackets) {
            val result = decoder.decode(hex.hexToByteArray(), state, defaultConfig)
            if (result is DecodeResult.Success) {
                state = result.data.newState!!
                if (result.data.hasNewData) hasNewData = true
            }
        }

        assertTrue(hasNewData, "Should have new telemetry data")

        // KMP rounds (0.67) vs legacy truncates (0.66); rounding is more correct
        assertEquals(67, state.speed, "Raw speed should be 67 (0.67 km/h in 1/100 units)")
        assertEquals(0.67, state.speedKmh, 0.01, "Speed should be 0.67 km/h")
        assertEquals(28, state.temperatureC, "Temperature should be 28°C")
        assertEquals(36, state.imuTemp, "IMU temp should be 36")
        assertEquals(83.59, state.voltageV, 0.01, "Voltage should be 83.59V")
        assertEquals(-0.1, state.currentA, 0.01, "Current should be -0.1A")
        assertEquals(0.009, state.wheelDistanceKm, 0.001, "Wheel distance should be 0.009 km")
        assertEquals(21687, state.totalDistance.toInt(), "Total distance should be 21687m")
        assertEquals(100, state.batteryLevel, "Battery should be 100%")
        assertEquals(0.079986572265625, state.angle, 1e-10, "Angle should match legacy")
        assertEquals(0.0, state.roll, 0.001, "Roll should be 0.0")
        assertEquals("Drive", state.modeStr, "Mode should be Drive")
    }

    // ==================== V8S Full Data ====================

    @Test
    fun `decode V8S full data matches legacy`() {
        // From InMotionAdapterTest: decode with v8s full data
        val slowPackets = listOf(
            "aaaa1401a5550f8500000000000000fe02010006",
            "0146bd5ea5aa7115000000000000000000000000",
            "0000000015000266000000000700036600000000",
            "260301010000000000000a000000000000000800",
            "b888000043100000001000000000000000000000",
            "0000000001000000000000000000000000000000",
            "000000000700000800000000b005004f00000065",
            "00000000801027000001000a01a05555"
        )
        val fastPackets = listOf(
            "aaaa1301a5550f9500000000000000fe02010015",
            "eeffff0000000000000000000000000000000007",
            "00000006200000000000001e1e92920000000004",
            "000000af04000000000000000d370c1203d00723",
            "0000000000000000000000bcfeffff1400000000",
            "0000001100000000000000000000000040000892",
            "00007f0500006600000083b205004f0000006502",
            "0000ca45000000000000d51f0000600000001100",
            "0000040004000000bf5555"
        )

        decoder.reset()
        var state = defaultState

        for (hex in slowPackets) {
            val result = decoder.decode(hex.hexToByteArray(), state, defaultConfig)
            if (result is DecodeResult.Success) state = result.data.newState!!
        }

        assertEquals("1571AA5EBD460106", state.serialNumber, "Serial should match legacy")
        assertEquals("InMotion V8S", state.model, "Model should match legacy")
        assertEquals("102.2.21", state.version, "Version should match legacy")

        var hasNewData = false
        for (hex in fastPackets) {
            val result = decoder.decode(hex.hexToByteArray(), state, defaultConfig)
            if (result is DecodeResult.Success) {
                state = result.data.newState!!
                if (result.data.hasNewData) hasNewData = true
            }
        }

        assertTrue(hasNewData, "Should have new telemetry data")

        assertEquals(0, state.speed, "Raw speed should be 0")
        assertEquals(0.0, state.speedKmh, 0.01, "Speed should be 0.0 km/h")
        assertEquals(30, state.temperatureC, "Temperature should be 30°C")
        assertEquals(-110, state.imuTemp, "IMU temp should be -110")
        assertEquals(81.98, state.voltageV, 0.01, "Voltage should be 81.98V")
        assertEquals(0.07, state.currentA, 0.01, "Current should be 0.07A")
        assertEquals(0.0, state.wheelDistanceKm, 0.001, "Wheel distance should be 0.0 km")
        assertEquals(1199, state.totalDistance.toInt(), "Total distance should be 1199m")
        assertEquals(96, state.batteryLevel, "Battery should be 96%")
        assertEquals(-0.0699920654296875, state.angle, 1e-10, "Angle should match legacy")
        assertEquals(0.0, state.roll, 0.001, "Roll should be 0.0")
        assertEquals("Drive", state.modeStr, "Mode should be Drive")
    }

    // ==================== Escaped Checksum ====================

    @Test
    fun `decode data with escaped checksum matches legacy`() {
        // From InMotionAdapterTest: decode data with escaped checksum
        // The last byte A555 in the packet is an escaped 0x55 (0xA5 prefix)
        val packets = listOf(
            "aaaa1401a5550f8500000000000000fe02010001",
            "00da7c5e1a611400000000000000000000000000",
            "0000001500020200000000070003020000000026",
            "0301010000000000000a000000000000000200d0",
            "840000ea0f000000100000000000000000000000",
            "0000000100000000000000000000000000000000",
            "00000006000008000000005b0a006f6e01003a00",
            "0000006c3421000001010a00a5555555"
        )

        decoder.reset()
        var state = defaultState

        for (hex in packets) {
            val result = decoder.decode(hex.hexToByteArray(), state, defaultConfig)
            if (result is DecodeResult.Success) state = result.data.newState!!
        }

        // Legacy test: only control packets (no telemetry), so result is false
        // but model and version should be extracted
        assertEquals("InMotion V8F", state.model, "Model should match legacy")
        assertEquals("2.2.21", state.version, "Version should match legacy")
    }
}
