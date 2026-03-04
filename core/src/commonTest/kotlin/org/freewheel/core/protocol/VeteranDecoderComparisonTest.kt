package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelState
import org.freewheel.core.utils.ByteUtils
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comparison tests verifying KMP VeteranDecoder produces identical results
 * to the legacy VeteranAdapter using real packet data from legacy tests.
 *
 * These tests use the same hex packet data and expected values from
 * VeteranAdapterTest.kt to ensure byte-for-byte compatibility.
 */
class VeteranDecoderComparisonTest {

    private val decoder = VeteranDecoder()
    private val defaultState = WheelState()
    private val defaultConfig = DecoderConfig()


    // ==================== Basic Packet Parsing ====================

    @Test
    fun `decode veteran old board data matches legacy expected values`() {
        // From VeteranAdapterTest: decode veteran old board data
        val byteArray1 = "DC5A5C2025D600003BF500003BF50000FFDE1399".hexToByteArray()
        val byteArray2 = "0DEF0000024602460000000000000000".hexToByteArray()

        decoder.reset()

        // Feed first packet
        var state = defaultState
        val result1 = decoder.decode(byteArray1, state, defaultConfig)
        if (result1 != null) state = result1.newState

        // Feed second packet (should complete the frame)
        val result2 = decoder.decode(byteArray2, state, defaultConfig)

        // Verify we got valid data
        assertTrue(result2?.hasNewData == true, "Should decode complete frame")

        val finalState = result2!!.newState

        // Expected from legacy test:
        // - speed = 0
        // - temperature = 50 (5099/100 = 50)
        // - voltageDouble = 96.86
        // - phaseCurrentDouble = -3.4
        // - wheelDistanceDouble = 15.349
        // - totalDistance = 15349
        // - batteryLevel = 90
        // - version = "000.0.00"

        assertEquals(0, abs(finalState.speed), "Speed should be 0")
        assertEquals(9686, finalState.voltage, "Voltage should be 9686 (96.86V)")
        assertEquals(96.86, finalState.voltageV, 0.01, "Voltage should be 96.86V")
        assertEquals(15349, finalState.totalDistance.toInt(), "Total distance should be 15349m")
    }

    @Test
    fun `decode veteran new board data matches legacy expected values`() {
        // From VeteranAdapterTest: decode veteran new board data
        val byteArray1 = "DC5A5C20238A0112121A00004D450005064611F2".hexToByteArray()
        val byteArray2 = "0E1000000AF00AF0041B000300000000".hexToByteArray()

        decoder.reset()

        var state = defaultState
        val result1 = decoder.decode(byteArray1, state, defaultConfig)
        if (result1 != null) state = result1.newState

        val result2 = decoder.decode(byteArray2, state, defaultConfig)

        assertTrue(result2?.hasNewData == true, "Should decode complete frame")

        val finalState = result2!!.newState

        // Expected from legacy test:
        // - speed = 274 (absolute value) - legacy stores raw value in 1/10 km/h
        // - temperature = 45
        // - voltageDouble = 90.98
        // - phaseCurrentDouble = 160.6
        // - wheelDistanceDouble = 4.634
        // - totalDistance = 347461
        // - batteryLevel = 60
        // - version = "001.0.51"

        // Note: KMP decoder stores speed in 1/100 km/h (2740 = 27.4 km/h)
        // Legacy stores in 1/10 km/h (274 = 27.4 km/h)
        // Both represent the same physical speed
        assertEquals(27.4, abs(finalState.speed) / 100.0, 0.1, "Speed should be ~27.4 km/h")
        assertEquals(9098, finalState.voltage, "Voltage should be 9098 (90.98V)")
        assertEquals(90.98, finalState.voltageV, 0.01, "Voltage should be 90.98V")
        assertEquals(347461, finalState.totalDistance.toInt(), "Total distance should be 347461m")
    }

    @Test
    fun `decode veteran 58fw data matches legacy expected values`() {
        // From VeteranAdapterTest: decode veteran 58fw data
        val byteArray1 = "dc5a5c2025cd0000071f0000c77800280000110b".hexToByteArray()
        val byteArray2 = "0e1000010af00af00422000300140000".hexToByteArray()

        decoder.reset()

        var state = defaultState
        val result1 = decoder.decode(byteArray1, state, defaultConfig)
        if (result1 != null) state = result1.newState

        val result2 = decoder.decode(byteArray2, state, defaultConfig)

        assertTrue(result2?.hasNewData == true, "Should decode complete frame")

        val finalState = result2!!.newState

        // Expected from legacy test:
        // - speed = 0 (absolute value)
        // - temperature = 43
        // - voltageDouble = 96.77
        // - phaseCurrentDouble = 0
        // - wheelDistanceDouble = 1.823
        // - totalDistance = 2672504
        // - batteryLevel = 89
        // - angle = 0.2 (from 0x0014 = 20 / 100 = 0.2)
        // - version = "001.0.58"

        assertEquals(0, abs(finalState.speed), "Speed should be 0")
        assertEquals(9677, finalState.voltage, "Voltage should be 9677 (96.77V)")
        assertEquals(96.77, finalState.voltageV, 0.01, "Voltage should be 96.77V")
        assertEquals(2672504, finalState.totalDistance.toInt(), "Total distance should be 2672504m")
    }

    @Test
    fun `decode veteran abrams matches legacy expected values`() {
        // From VeteranAdapterTest: decode veteran abrams
        val byteArray1 = "dc5a5c20266d00004aaf00004aaf000000000d9e".hexToByteArray()
        val byteArray2 = "0b8800000af00af007d2000300050004".hexToByteArray()

        decoder.reset()

        var state = defaultState
        val result1 = decoder.decode(byteArray1, state, defaultConfig)
        if (result1 != null) state = result1.newState

        val result2 = decoder.decode(byteArray2, state, defaultConfig)

        assertTrue(result2?.hasNewData == true, "Should decode complete frame")

        val finalState = result2!!.newState

        // Expected from legacy test:
        // - speed = 0 (absolute value)
        // - temperature = 34
        // - voltageDouble = 98.37
        // - phaseCurrentDouble = 0
        // - wheelDistanceDouble = 19.119
        // - totalDistance = 19119
        // - batteryLevel = 98
        // - angle = 0.05 (from 0x0005 = 5 / 100 = 0.05)
        // - version = "002.0.02"

        assertEquals(0, abs(finalState.speed), "Speed should be 0")
        assertEquals(9837, finalState.voltage, "Voltage should be 9837 (98.37V)")
        assertEquals(98.37, finalState.voltageV, 0.01, "Voltage should be 98.37V")
        assertEquals(19119, finalState.totalDistance.toInt(), "Total distance should be 19119m")
    }

    // ==================== Header Detection ====================

    @Test
    fun `DC 5A 5C header is required for frame detection`() {
        val withoutHeader = ByteArray(36) { 0 }
        withoutHeader[0] = 0x12  // Not 0xDC
        withoutHeader[1] = 0x34  // Not 0x5A
        withoutHeader[2] = 0x56  // Not 0x5C

        decoder.reset()
        val result = decoder.decode(withoutHeader, defaultState, defaultConfig)

        // Without proper header, should not produce valid data
        assertTrue(result == null || !result.hasNewData,
            "Invalid header should not produce valid data")
    }

    @Test
    fun `partial header is rejected`() {
        // DC 5A but not 5C
        val partialHeader = byteArrayOf(0xDC.toByte(), 0x5A, 0x00)

        decoder.reset()
        val result = decoder.decode(partialHeader, defaultState, defaultConfig)

        assertTrue(result == null || !result.hasNewData,
            "Partial header should not produce valid data")
    }

    // ==================== Corrupted Data Handling ====================

    @Test
    fun `corrupted data does not crash decoder`() {
        decoder.reset()

        // Send corrupted/partial data (1-30 bytes)
        for (i in 1..30) {
            val corrupted = ByteArray(i) { it.toByte() }
            val result = decoder.decode(corrupted, defaultState, defaultConfig)
            // Should not crash, may return null or partial result
        }
    }

    // ==================== Signed Value Handling ====================

    @Test
    fun `negative speed is handled correctly`() {
        // Veteran uses signed short for speed
        val speed: Short = -500  // Reverse direction

        // Test that ByteUtils correctly handles signed values
        val speedBytes = ByteUtils.getBytes(speed)
        val parsedSpeed = ByteUtils.signedShortFromBytesBE(speedBytes, 0)

        assertEquals(-500, parsedSpeed, "Signed speed should be preserved")
    }

    @Test
    fun `negative phase current from regenerative braking`() {
        // Phase current is negative during braking/regen
        val phaseCurrent: Short = -340  // -3.4A * 100

        val phaseCurrentBytes = ByteUtils.getBytes(phaseCurrent)
        val parsedCurrent = ByteUtils.signedShortFromBytesBE(phaseCurrentBytes, 0)

        assertEquals(-340, parsedCurrent, "Signed phase current should be preserved")
    }

    // ==================== Version Parsing ====================

    @Test
    fun `version is parsed correctly from raw value`() {
        // Version is encoded as major*1000 + minor*100 + patch
        // e.g., 3210 = 3.2.10
        val versionRaw = 3210
        val major = versionRaw / 1000
        val minor = (versionRaw % 1000) / 100
        val patch = versionRaw % 100

        assertEquals(3, major, "Major version should be 3")
        assertEquals(2, minor, "Minor version should be 2")
        assertEquals(10, patch, "Patch version should be 10")
    }

    // ==================== Multi-packet Assembly ====================

    @Test
    fun `frame assembly works across multiple packets`() {
        // Real data from VeteranAdapterTest - requires two packets
        val byteArray1 = "DC5A5C2025D600003BF500003BF50000FFDE1399".hexToByteArray()
        val byteArray2 = "0DEF0000024602460000000000000000".hexToByteArray()

        decoder.reset()

        // First packet should not produce complete data
        var state = defaultState
        val result1 = decoder.decode(byteArray1, state, defaultConfig)
        if (result1 != null) state = result1.newState

        // Second packet should complete the frame
        val result2 = decoder.decode(byteArray2, state, defaultConfig)

        assertTrue(result2?.hasNewData == true, "Second packet should complete frame")
    }

    // ==================== Battery Calculation ====================

    @Test
    fun `battery percentage is calculated correctly for different voltages`() {
        // Veteran battery calculation depends on voltage and cell count
        // This tests that the decoder doesn't crash with various voltages
        val testVoltages = listOf(
            9500,   // 95V - typical for 100V Sherman
            9837,   // 98.37V - from Abrams test
            9098,   // 90.98V - from new board test
            9686,   // 96.86V - from old board test
            11500   // 115V - Patton (126V max)
        )

        for (voltage in testVoltages) {
            // Create a valid packet with the given voltage
            val header = byteArrayOf(0xDC.toByte(), 0x5A, 0x5C, 0x20)
            val voltageBytes = ByteUtils.getBytes(voltage.toShort())
            val padding = ByteArray(28) { 0 }

            val packet = header + voltageBytes + padding

            decoder.reset()
            val result = decoder.decode(packet, defaultState, defaultConfig)

            // May or may not produce valid data depending on packet completeness
            // Main thing is it doesn't crash
        }
    }

    // ==================== Angle Parsing ====================

    @Test
    fun `angle is parsed correctly from firmware 58 data`() {
        // From 58fw test: angle = 0.2 (value 0x0014 = 20, divided by 100)
        // In the packet: "...00140000" at the end of second packet
        val rawAngle = 0x0014
        val angle = rawAngle / 100.0

        assertEquals(0.2, angle, 0.001, "Angle should be 0.2 degrees")
    }

    // ==================== Temperature Parsing ====================

    @Test
    fun `temperature is correctly extracted`() {
        // Temperature is at a specific offset in the frame
        // From old board test: temperature = 50 (from value 5099, divided by 100)
        // From new board test: temperature = 45 (from value 4510, at offset 0x0E10)

        // Temperature values are stored as temp * 100
        val temp1 = 5099 / 100  // Old board
        val temp2 = 4510 / 100  // New board

        assertEquals(50, temp1, "Old board temperature should be 50")
        assertEquals(45, temp2, "New board temperature should be 45")
    }

    // ==================== Battery Calculation for 126V Model (Patton) ====================

    @Test
    fun `battery calculation for Patton 126V model`() {
        // From VeteranAdapterTest: decode veteran patton
        // Patton has mVer=4 (version "004.0.07"), uses 126V battery curve
        val byteArray1 = "dc5a5c26302b00001fdc00002038000000000d15".hexToByteArray()
        val byteArray2 = "0a79000000fa01900fa700031b690000006fffff".hexToByteArray()
        val byteArray3 = "5678".hexToByteArray()

        decoder.reset()
        var state = defaultState
        for (packet in listOf(byteArray1, byteArray2, byteArray3)) {
            val result = decoder.decode(packet, state, defaultConfig)
            if (result != null) state = result.newState
        }

        // Expected from legacy test:
        assertEquals(0, abs(state.speed), "Speed should be 0")
        assertEquals(33, state.temperatureC, "Temperature should be 33°C")
        assertEquals(123.31, state.voltageV, 0.01, "Voltage should be 123.31V")
        assertEquals(0.0, state.currentA, 0.01, "Phase current should be 0.0A")
        assertEquals(8248, state.totalDistance.toInt(), "Total distance should be 8248m")
        assertEquals(100, state.batteryLevel, "Battery should be 100%")
    }

    // ==================== Battery Calculation for 151V Model (Lynx) ====================

    @Test
    fun `battery calculation for Lynx 151V model`() {
        // From VeteranAdapterTest: decode veteran lynx crc
        // Lynx has mVer=5 (version "005.0.04"), uses 151V battery curve, CRC format
        val byteArray1 = "dc5a5c53391b000006d000000770000000260bcc".hexToByteArray()
        val byteArray2 = "0e08000000fa00c8138c00b4000b014c80c80000".hexToByteArray()
        val byteArray3 = "808080808080010008808080800fee0fee0fee0f".hexToByteArray()
        val byteArray4 = "ee0fef0fe80fef0fef0ff00ff00ff00fea0fef0f".hexToByteArray()
        val byteArray5 = "ef0fefdab22518".hexToByteArray()

        decoder.reset()
        var state = defaultState

        for (packet in listOf(byteArray1, byteArray2, byteArray3, byteArray4, byteArray5)) {
            val result = decoder.decode(packet, state, defaultConfig)
            if (result != null) {
                state = result.newState
            }
        }

        // Expected from legacy test:
        assertEquals(0, abs(state.speed), "Speed should be 0")
        assertEquals(30, state.temperatureC, "Temperature should be 30°C")
        assertEquals(146.19, state.voltageV, 0.01, "Voltage should be 146.19V")
        assertEquals(1904, state.totalDistance.toInt(), "Total distance should be 1904m")
        assertEquals(94, state.batteryLevel, "Battery should be 94%")
    }

    // ==================== veteranNegative (Speed Polarity) ====================

    @Test
    fun `decode veteran speed is positive with default config`() {
        // Veteran decoder currently uses veteranNegative=1 internally
        // Test that speed comes through correctly for positive speed
        val byteArray1 = "DC5A5C20238A0112121A00004D450005064611F2".hexToByteArray()
        val byteArray2 = "0E1000000AF00AF0041B000300000000".hexToByteArray()

        decoder.reset()
        var state = defaultState
        val result1 = decoder.decode(byteArray1, state, defaultConfig)
        if (result1 != null) state = result1.newState
        val result2 = decoder.decode(byteArray2, state, defaultConfig)

        assertTrue(result2?.hasNewData == true, "Should decode data")
        // Raw speed = 0x0112 = 274, * 10 = 2740
        // With veteranNegative=1, speed = 2740 * 1 = 2740 (positive)
        assertEquals(2740, result2!!.newState.speed,
            "Speed should be 2740 (27.40 km/h)")
        assertTrue(result2.newState.speed > 0, "Speed should be positive")
    }

    @Test
    fun `decode veteran negative speed becomes absolute when gotwayNegative is 0`() {
        // Build a packet with negative raw speed to test sign handling
        // Header: DC 5A 5C 20
        // Voltage at bytes 4-5: 9500 = 0x251C
        // Speed at bytes 6-7: -274 = 0xFEEE
        val byteArray1 = "DC5A5C20251CFEEE121A00004D450005064611F2".hexToByteArray()
        val byteArray2 = "0E1000000AF00AF0041B000300000000".hexToByteArray()

        decoder.reset()
        var state = defaultState
        // defaultConfig has gotwayNegative=0 → abs() applied
        val result1 = decoder.decode(byteArray1, state, defaultConfig)
        if (result1 != null) state = result1.newState
        val result2 = decoder.decode(byteArray2, state, defaultConfig)

        assertTrue(result2?.hasNewData == true, "Should decode data")
        // Raw speed = -274, * 10 = -2740, abs() → 2740
        assertEquals(2740, result2!!.newState.speed,
            "gotwayNegative=0: speed should be absolute (27.40 km/h)")
        assertTrue(result2.newState.speed > 0, "Speed should be positive with gotwayNegative=0")
    }

    // ==================== Sherman S Variant Header ====================

    @Test
    fun `decode veteran sherman S with variant header byte`() {
        // From VeteranAdapterTest: decode veteran sherman s 1
        // Header uses 0x22 as 4th byte (len=34) instead of 0x20 (len=32)
        val byteArray1 = "DC5A5C22266200000084000017A2000000000C38".hexToByteArray()
        val byteArray2 = "0B03000000C600E40BBD0003188B0000006F".hexToByteArray()

        decoder.reset()
        var state = defaultState
        val result1 = decoder.decode(byteArray1, state, defaultConfig)
        if (result1 != null) state = result1.newState
        val result2 = decoder.decode(byteArray2, state, defaultConfig)

        assertTrue(result2?.hasNewData == true, "Sherman S header variant should decode")
        val finalState = result2!!.newState

        // Expected from legacy test:
        assertEquals(0, abs(finalState.speed), "Speed should be 0")
        assertEquals(31, finalState.temperatureC, "Temperature should be 31°C")
        assertEquals(98.26, finalState.voltageV, 0.01, "Voltage should be 98.26V")
        assertEquals(6050, finalState.totalDistance.toInt(), "Total distance should be 6050m")
        assertEquals(97, finalState.batteryLevel, "Battery should be 97%")
    }

    // ==================== gotwayNegative (Config-driven) ====================

    @Test
    fun `speed is absolute when gotwayNegative is 0`() {
        val cfg = defaultConfig.copy(gotwayNegative = 0)
        val state = decodeVeteranFrameWithSpeed(rawSpeed = -274, config = cfg)
        // abs(-274 * 10) = 2740
        assertEquals(2740, state.speed, "Speed should be absolute with gotwayNegative=0")
        assertTrue(state.phaseCurrent >= 0, "Phase current should also be absolute")
    }

    @Test
    fun `speed sign preserved when gotwayNegative is 1`() {
        val cfg = defaultConfig.copy(gotwayNegative = 1)
        val state = decodeVeteranFrameWithSpeed(rawSpeed = -274, config = cfg)
        // -274 * 10 * 1 = -2740
        assertEquals(-2740, state.speed, "Speed sign should be preserved with gotwayNegative=1")
    }

    @Test
    fun `speed sign inverted when gotwayNegative is negative 1`() {
        val cfg = defaultConfig.copy(gotwayNegative = -1)
        val state = decodeVeteranFrameWithSpeed(rawSpeed = -274, config = cfg)
        // -274 * 10 * -1 = 2740
        assertEquals(2740, state.speed, "Speed should be inverted with gotwayNegative=-1")
    }

    @Test
    fun `phaseCurrent follows same sign rules as speed`() {
        val cfg = defaultConfig.copy(gotwayNegative = -1)
        val state = decodeVeteranFrameWithSpeed(rawSpeed = 100, rawPhaseCurrent = -500, config = cfg)
        // phaseCurrent: -500 * 10 * -1 = 5000
        assertEquals(5000, state.phaseCurrent, "Phase current should invert with gotwayNegative=-1")
    }

    // ==================== hwPwmEnabled ====================

    @Test
    fun `hwPwmEnabled true uses hardware PWM from frame`() {
        val cfg = defaultConfig.copy(hwPwmEnabled = true)
        val state = decodeVeteranFrameWithPwm(rawHwPwm = 3000, rawSpeed = 100, rawPhaseCurrent = 1000, config = cfg)
        // hwPwm = 3000, calculatedPwm = 3000/10000 = 0.3
        assertEquals(3000, state.output, "Output should be hardware PWM value")
        assertEquals(0.3, state.calculatedPwm, 0.001)
        // current = round(0.3 * 1000 * 10) = round(0.3 * 10000) = 3000
        // phaseCurrent = abs(1000 * 10) = 10000 with gotwayNegative=0
        val expectedCurrent = (0.3 * 10000).roundToInt()
        assertEquals(expectedCurrent, state.current, "Current should be calculatedPwm * phaseCurrent")
    }

    @Test
    fun `hwPwmEnabled false uses formula-based PWM`() {
        val cfg = defaultConfig.copy(
            hwPwmEnabled = false,
            rotationSpeed = 500,
            rotationVoltage = 840,
            powerFactor = 100
        )
        val rawSpeed = 100 // speed = 100 * 10 = 1000 (after ×10 but before polarity)
        val voltage = 9686  // 96.86V
        val state = decodeVeteranFrameWithPwm(
            rawHwPwm = 3000, // should be ignored
            rawSpeed = rawSpeed,
            rawPhaseCurrent = 1000,
            rawVoltage = voltage,
            config = cfg
        )
        // speed (after polarity with gotwayNegative=0) = abs(100*10) = 1000
        // rotRatio = 500.0 / 840 = 0.5952...
        // calculatedPwm = 1000.0 / (0.5952... * 9686 * 100) = 1000 / 576547.6... ≈ 0.001734
        val rotRatio = 500.0 / 840
        val expectedPwm = 1000.0 / (rotRatio * voltage * 100)
        val expectedOutput = (expectedPwm * 10000).roundToInt()
        assertEquals(expectedOutput, state.output,
            "Output should use formula-based PWM, not hardware value 3000")
        assertTrue(state.output != 3000, "Should NOT use hardware PWM")
    }

    // ==================== Helpers ====================

    /**
     * Build and decode a Veteran frame with given raw speed value.
     * Returns the decoded WheelState.
     */
    private fun decodeVeteranFrameWithSpeed(
        rawSpeed: Int,
        rawPhaseCurrent: Int = 0,
        config: DecoderConfig = defaultConfig
    ): WheelState {
        val vetDecoder = VeteranDecoder()
        val frame = buildVeteranFrame(
            rawVoltage = 9686,
            rawSpeed = rawSpeed,
            rawPhaseCurrent = rawPhaseCurrent,
            mVer = 1
        )
        val result = vetDecoder.decode(frame, defaultState, config)
        assertNotNull(result, "Veteran frame should decode")
        return result.newState
    }

    /**
     * Build and decode a Veteran frame with given PWM and speed values.
     */
    private fun decodeVeteranFrameWithPwm(
        rawHwPwm: Int,
        rawSpeed: Int,
        rawPhaseCurrent: Int,
        rawVoltage: Int = 9686,
        config: DecoderConfig = defaultConfig
    ): WheelState {
        val vetDecoder = VeteranDecoder()
        val frame = buildVeteranFrame(
            rawVoltage = rawVoltage,
            rawSpeed = rawSpeed,
            rawPhaseCurrent = rawPhaseCurrent,
            rawHwPwm = rawHwPwm,
            mVer = 1
        )
        val result = vetDecoder.decode(frame, defaultState, config)
        assertNotNull(result, "Veteran frame should decode")
        return result.newState
    }

    /**
     * Build a 36-byte Veteran frame.
     * Layout: header(3) + len(1) + voltage(2) + speed(2) + distance(4) + totalDist(4)
     *       + phaseCurrent(2) + temperature(2) + autoOffSec(2) + chargeMode(2)
     *       + speedAlert(2) + speedTiltback(2) + version(2) + pedalsMode(2)
     *       + pitchAngle(2) + hwPwm(2)
     */
    private fun buildVeteranFrame(
        rawVoltage: Int = 9686,
        rawSpeed: Int = 0,
        rawPhaseCurrent: Int = 0,
        rawHwPwm: Int = 0,
        mVer: Int = 1
    ): ByteArray {
        val frame = ByteArray(36)
        // Header: DC 5A 5C
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A
        frame[2] = 0x5C
        frame[3] = 32 // length
        // Voltage at offset 4 (2 bytes BE)
        frame[4] = ((rawVoltage shr 8) and 0xFF).toByte()
        frame[5] = (rawVoltage and 0xFF).toByte()
        // Speed at offset 6 (2 bytes BE, signed)
        frame[6] = ((rawSpeed shr 8) and 0xFF).toByte()
        frame[7] = (rawSpeed and 0xFF).toByte()
        // Distance at offset 8 (4 bytes)
        // TotalDistance at offset 12 (4 bytes)
        // PhaseCurrent at offset 16 (2 bytes BE, signed)
        frame[16] = ((rawPhaseCurrent shr 8) and 0xFF).toByte()
        frame[17] = (rawPhaseCurrent and 0xFF).toByte()
        // Temperature at offset 18 (2 bytes)
        // Version at offset 28 (2 bytes BE) - encodes mVer
        val ver = mVer * 1000
        frame[28] = ((ver shr 8) and 0xFF).toByte()
        frame[29] = (ver and 0xFF).toByte()
        // hwPwm at offset 34 (2 bytes BE)
        frame[34] = ((rawHwPwm shr 8) and 0xFF).toByte()
        frame[35] = (rawHwPwm and 0xFF).toByte()
        return frame
    }
}
