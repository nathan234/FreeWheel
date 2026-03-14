package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for VeteranDecoder.
 *
 * Frame format (36 bytes minimum):
 * - Bytes 0-2: Header (DC 5A 5C)
 * - Byte 3: Length (32 for minimum frame)
 * - Bytes 4-5: Voltage (BE, raw units = 1/100 V)
 * - Bytes 6-7: Speed (signed BE, multiplied by 10 in decoder)
 * - Bytes 8-11: Distance (revBE int)
 * - Bytes 12-15: Total distance (revBE int)
 * - Bytes 16-17: Phase current (signed BE, multiplied by 10 in decoder)
 * - Bytes 18-19: Temperature (signed BE)
 * - Bytes 20-21: Auto-off seconds (BE)
 * - Bytes 22-23: Charge mode (BE; byte 22 must be 0x00 for unpacker validation)
 * - Bytes 24-25: Speed alert (BE, multiplied by 10 in decoder)
 * - Bytes 26-27: Speed tiltback (BE, multiplied by 10 in decoder)
 * - Bytes 28-29: Version (BE; mVer = ver / 1000)
 * - Bytes 30-31: Pedals mode (BE; byte 30 must be 0x00 or 0x07 for unpacker validation)
 * - Bytes 32-33: Pitch angle (signed BE)
 * - Bytes 34-35: HW PWM (BE)
 *
 * Unpacker validation rules:
 * - Byte 22 must be 0x00
 * - Byte 23 must satisfy (byte & 0xFE) == 0x00 (i.e., 0x00 or 0x01)
 * - Byte 30 must be 0x00 or 0x07
 */
class VeteranDecoderTest {

    private val decoder = VeteranDecoder()
    private val config = DecoderConfig(
        useMph = false,
        useFahrenheit = false,
        useCustomPercents = false
    )

    // ==================== Frame Builder ====================

    /**
     * Build a Veteran frame with the given field values.
     *
     * Distance and totalDistance are encoded in "reversed Big Endian" format
     * as consumed by ByteUtils.intFromBytesRevBE:
     *   bytes[s+0] = (value >> 8) & 0xFF
     *   bytes[s+1] = value & 0xFF
     *   bytes[s+2] = (value >> 24) & 0xFF
     *   bytes[s+3] = (value >> 16) & 0xFF
     */
    private fun buildVeteranFrame(
        voltage: Int = 9686,       // 96.86V
        speed: Int = 0,            // raw signed value, decoder multiplies by 10
        distance: Int = 0,
        totalDistance: Int = 0,
        phaseCurrent: Int = 0,     // raw signed value, decoder multiplies by 10
        temperature: Int = 0,      // raw signed value
        ver: Int = 5000,           // mVer = ver/1000 = 5 (Lynx)
        pedalsMode: Int = 0,       // 0x00 or 0x07 for byte 30
        chargeModeLow: Int = 0,    // byte 23: must be 0x00 or 0x01
        speedAlert: Int = 0,       // raw value, decoder multiplies by 10
        speedTiltback: Int = 0,    // raw value, decoder multiplies by 10
        autoOffSec: Int = 0,       // raw value (seconds)
        pitchAngle: Int = 0,       // raw signed value
        hwPwm: Int = 0
    ): ByteArray {
        val frame = ByteArray(36)
        // Header
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A
        frame[2] = 0x5C
        frame[3] = 32 // length of data payload

        // Voltage BE at 4-5
        frame[4] = ((voltage shr 8) and 0xFF).toByte()
        frame[5] = (voltage and 0xFF).toByte()

        // Speed signed BE at 6-7
        frame[6] = ((speed shr 8) and 0xFF).toByte()
        frame[7] = (speed and 0xFF).toByte()

        // Distance in revBE at 8-11
        frame[8] = ((distance shr 8) and 0xFF).toByte()
        frame[9] = (distance and 0xFF).toByte()
        frame[10] = ((distance shr 24) and 0xFF).toByte()
        frame[11] = ((distance shr 16) and 0xFF).toByte()

        // Total distance in revBE at 12-15
        frame[12] = ((totalDistance shr 8) and 0xFF).toByte()
        frame[13] = (totalDistance and 0xFF).toByte()
        frame[14] = ((totalDistance shr 24) and 0xFF).toByte()
        frame[15] = ((totalDistance shr 16) and 0xFF).toByte()

        // Phase current signed BE at 16-17
        frame[16] = ((phaseCurrent shr 8) and 0xFF).toByte()
        frame[17] = (phaseCurrent and 0xFF).toByte()

        // Temperature signed BE at 18-19
        frame[18] = ((temperature shr 8) and 0xFF).toByte()
        frame[19] = (temperature and 0xFF).toByte()

        // Auto-off seconds BE at 20-21
        frame[20] = ((autoOffSec shr 8) and 0xFF).toByte()
        frame[21] = (autoOffSec and 0xFF).toByte()

        // Charge mode at 22-23: byte 22 MUST be 0x00, byte 23 must be 0x00 or 0x01
        frame[22] = 0x00
        frame[23] = (chargeModeLow and 0x01).toByte()

        // Speed alert BE at 24-25
        frame[24] = ((speedAlert shr 8) and 0xFF).toByte()
        frame[25] = (speedAlert and 0xFF).toByte()

        // Speed tiltback BE at 26-27
        frame[26] = ((speedTiltback shr 8) and 0xFF).toByte()
        frame[27] = (speedTiltback and 0xFF).toByte()

        // Version BE at 28-29
        frame[28] = ((ver shr 8) and 0xFF).toByte()
        frame[29] = (ver and 0xFF).toByte()

        // Pedals mode at 30-31: byte 30 must be 0x00 or 0x07
        frame[30] = (pedalsMode and 0xFF).toByte()
        frame[31] = 0x00

        // Pitch angle signed BE at 32-33
        frame[32] = ((pitchAngle shr 8) and 0xFF).toByte()
        frame[33] = (pitchAngle and 0xFF).toByte()

        // HW PWM at 34-35
        frame[34] = ((hwPwm shr 8) and 0xFF).toByte()
        frame[35] = (hwPwm and 0xFF).toByte()

        return frame
    }

    /**
     * Convenience: decode a single Veteran frame and return the result.
     * Uses a fresh decoder to avoid state leaking between tests.
     */
    private fun decodeSingleFrame(
        voltage: Int = 9686,
        speed: Int = 0,
        distance: Int = 0,
        totalDistance: Int = 0,
        phaseCurrent: Int = 0,
        temperature: Int = 0,
        ver: Int = 5000,
        pedalsMode: Int = 0,
        chargeModeLow: Int = 0,
        speedAlert: Int = 0,
        speedTiltback: Int = 0,
        autoOffSec: Int = 0,
        pitchAngle: Int = 0,
        hwPwm: Int = 0,
        cfg: DecoderConfig = config
    ): DecodedData? {
        val freshDecoder = VeteranDecoder()
        val frame = buildVeteranFrame(
            voltage = voltage,
            speed = speed,
            distance = distance,
            totalDistance = totalDistance,
            phaseCurrent = phaseCurrent,
            temperature = temperature,
            ver = ver,
            pedalsMode = pedalsMode,
            chargeModeLow = chargeModeLow,
            speedAlert = speedAlert,
            speedTiltback = speedTiltback,
            autoOffSec = autoOffSec,
            pitchAngle = pitchAngle,
            hwPwm = hwPwm
        )
        return freshDecoder.decode(frame, WheelState(), cfg)
    }

    // ==================== Basic Frame Validation ====================

    @Test
    fun `minimum valid frame decodes successfully`() {
        val result = decodeSingleFrame()
        assertNotNull(result, "36-byte frame with valid header should decode")
        assertTrue(result.hasNewData)
        assertEquals(WheelType.VETERAN, result.newState.wheelType)
    }

    @Test
    fun `frame too short returns null`() {
        val freshDecoder = VeteranDecoder()
        // 35 bytes — one byte short of minimum
        val frame = ByteArray(35)
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A
        frame[2] = 0x5C
        frame[3] = 31 // length shorter than needed
        frame[22] = 0x00 // validation byte
        // byte 23: 0x00 (default)
        // byte 30: 0x00 (default)

        val result = freshDecoder.decode(frame, WheelState(), config)
        assertNull(result, "Frame shorter than 36 bytes should return null")
    }

    @Test
    fun `corrupted header returns null`() {
        val freshDecoder = VeteranDecoder()
        // Valid-sized frame but wrong header
        val frame = ByteArray(36)
        frame[0] = 0xAA.toByte() // wrong header
        frame[1] = 0x55
        frame[2] = 0x5C
        frame[3] = 32
        frame[22] = 0x00
        val result = freshDecoder.decode(frame, WheelState(), config)
        assertNull(result, "Frame with wrong header should return null")
    }

    @Test
    fun `unpacker rejects invalid byte 22`() {
        val freshDecoder = VeteranDecoder()
        // Build a frame where byte 22 is not 0x00
        val frame = buildVeteranFrame()
        frame[22] = 0x01 // invalid: must be 0x00
        val result = freshDecoder.decode(frame, WheelState(), config)
        assertNull(result, "Frame with byte 22 != 0x00 should be rejected by unpacker")
    }

    @Test
    fun `unpacker rejects invalid byte 23`() {
        val freshDecoder = VeteranDecoder()
        // Build a frame where byte 23 has (byte & 0xFE) != 0x00
        val frame = buildVeteranFrame()
        frame[23] = 0x02 // invalid: (0x02 & 0xFE) = 0x02 != 0x00
        val result = freshDecoder.decode(frame, WheelState(), config)
        assertNull(result, "Frame with byte 23 & 0xFE != 0x00 should be rejected by unpacker")
    }

    @Test
    fun `unpacker accepts byte 23 equal to 0x01`() {
        val result = decodeSingleFrame(chargeModeLow = 1)
        assertNotNull(result, "Frame with byte 23 = 0x01 should pass unpacker validation")
    }

    @Test
    fun `unpacker rejects invalid byte 30`() {
        val freshDecoder = VeteranDecoder()
        // Build a frame where byte 30 is not 0x00 or 0x07
        val frame = buildVeteranFrame()
        frame[30] = 0x02 // invalid: must be 0x00 or 0x07
        val result = freshDecoder.decode(frame, WheelState(), config)
        assertNull(result, "Frame with byte 30 not 0x00 or 0x07 should be rejected by unpacker")
    }

    @Test
    fun `unpacker accepts byte 30 equal to 0x07`() {
        val freshDecoder = VeteranDecoder()
        val frame = buildVeteranFrame(pedalsMode = 0x07)
        val result = freshDecoder.decode(frame, WheelState(), config)
        assertNotNull(result, "Frame with byte 30 = 0x07 should pass unpacker validation")
    }

    // ==================== Voltage Parsing ====================

    @Test
    fun `voltage is parsed correctly`() {
        val result = decodeSingleFrame(voltage = 9686)
        assertNotNull(result)
        assertEquals(9686, result.newState.voltage, "Voltage should be 9686 (96.86V)")
    }

    @Test
    fun `voltage at full charge level`() {
        val result = decodeSingleFrame(voltage = 9870, ver = 1000) // mVer=1 → Sherman
        assertNotNull(result)
        assertEquals(9870, result.newState.voltage)
        assertEquals(100, result.newState.batteryLevel, "9870 should be 100% for Sherman (100V)")
    }

    @Test
    fun `voltage at empty level`() {
        val result = decodeSingleFrame(voltage = 7935, ver = 1000) // mVer=1 → Sherman
        assertNotNull(result)
        assertEquals(7935, result.newState.voltage)
        assertEquals(0, result.newState.batteryLevel, "7935 should be 0% for Sherman (100V)")
    }

    // ==================== Speed with gotwayNegative ====================

    @Test
    fun `speed with gotwayNegative 0 uses abs`() {
        // speed = -100, decoder multiplies by 10 → -1000, abs() → 1000
        val cfg = config.copy(gotwayNegative = 0)
        val result = decodeSingleFrame(speed = -100, cfg = cfg)
        assertNotNull(result)
        assertEquals(1000, result.newState.speed, "gotwayNegative=0 should abs(speed*10)")
    }

    @Test
    fun `speed with gotwayNegative 1 keeps sign`() {
        // speed = -100, decoder multiplies by 10 → -1000, * 1 → -1000
        val cfg = config.copy(gotwayNegative = 1)
        val result = decodeSingleFrame(speed = -100, cfg = cfg)
        assertNotNull(result)
        assertEquals(-1000, result.newState.speed, "gotwayNegative=1 should keep sign")
    }

    @Test
    fun `speed with gotwayNegative -1 inverts sign`() {
        // speed = 100, decoder multiplies by 10 → 1000, * -1 → -1000
        val cfg = config.copy(gotwayNegative = -1)
        val result = decodeSingleFrame(speed = 100, cfg = cfg)
        assertNotNull(result)
        assertEquals(-1000, result.newState.speed, "gotwayNegative=-1 should invert sign")
    }

    @Test
    fun `positive speed with gotwayNegative 0 stays positive`() {
        val cfg = config.copy(gotwayNegative = 0)
        val result = decodeSingleFrame(speed = 50, cfg = cfg)
        assertNotNull(result)
        assertEquals(500, result.newState.speed, "gotwayNegative=0 should abs(speed*10)")
    }

    // ==================== Phase Current with gotwayNegative ====================

    @Test
    fun `phaseCurrent with gotwayNegative 0 uses abs`() {
        val cfg = config.copy(gotwayNegative = 0)
        val result = decodeSingleFrame(phaseCurrent = -34, cfg = cfg)
        assertNotNull(result)
        assertEquals(340, result.newState.phaseCurrent, "gotwayNegative=0 should abs(phaseCurrent*10)")
    }

    @Test
    fun `phaseCurrent with gotwayNegative 1 keeps sign`() {
        val cfg = config.copy(gotwayNegative = 1)
        val result = decodeSingleFrame(phaseCurrent = -34, cfg = cfg)
        assertNotNull(result)
        assertEquals(-340, result.newState.phaseCurrent, "gotwayNegative=1 should keep sign")
    }

    @Test
    fun `phaseCurrent with gotwayNegative -1 inverts sign`() {
        val cfg = config.copy(gotwayNegative = -1)
        val result = decodeSingleFrame(phaseCurrent = 34, cfg = cfg)
        assertNotNull(result)
        assertEquals(-340, result.newState.phaseCurrent, "gotwayNegative=-1 should invert sign")
    }

    // ==================== Temperature ====================

    @Test
    fun `temperature is parsed as raw signed BE`() {
        // Veteran temperature is stored directly as the raw signed BE value
        // (unlike Gotway which uses the MPU6050 formula)
        val result = decodeSingleFrame(temperature = 5017) // 0x1399 = 5017
        assertNotNull(result)
        assertEquals(5017, result.newState.temperature)
    }

    @Test
    fun `negative temperature is handled correctly`() {
        // -10 as signed short = 0xFFF6
        val result = decodeSingleFrame(temperature = -10)
        assertNotNull(result)
        assertEquals(-10, result.newState.temperature)
    }

    // ==================== Distance (revBE encoding) ====================

    @Test
    fun `distance zero decodes correctly`() {
        val result = decodeSingleFrame(distance = 0)
        assertNotNull(result)
        assertEquals(0L, result.newState.wheelDistance)
    }

    @Test
    fun `distance non-zero decodes correctly via revBE`() {
        // 15349 = 0x3BF5
        // revBE encoding: frame[8]=0x3B, frame[9]=0xF5, frame[10]=0x00, frame[11]=0x00
        // intFromBytesRevBE reads: (frame[10]<<24)|(frame[11]<<16)|(frame[8]<<8)|frame[9]
        // = (0x00<<24)|(0x00<<16)|(0x3B<<8)|0xF5 = 0x3BF5 = 15349
        val result = decodeSingleFrame(distance = 15349)
        assertNotNull(result)
        assertEquals(15349L, result.newState.wheelDistance)
    }

    @Test
    fun `totalDistance non-zero decodes correctly via revBE`() {
        val result = decodeSingleFrame(totalDistance = 15349)
        assertNotNull(result)
        assertEquals(15349L, result.newState.totalDistance)
    }

    @Test
    fun `large distance decodes correctly via revBE`() {
        // 1000000 = 0x000F4240
        // revBE encoding: frame[8]=(0x42), frame[9]=(0x40), frame[10]=(0x0F), frame[11]=(0x00)
        // intFromBytesRevBE: (0x0F<<24)|(0x00<<16)|(0x42<<8)|0x40 = 0x0F004240 -- that's wrong
        // Actually let me recalculate. For value 0x000F4240:
        //   A = (value >> 8) & 0xFF = 0x42
        //   B = value & 0xFF = 0x40
        //   C = (value >> 24) & 0xFF = 0x00
        //   D = (value >> 16) & 0xFF = 0x0F
        // Read back: (C<<24)|(D<<16)|(A<<8)|B = (0x00<<24)|(0x0F<<16)|(0x42<<8)|0x40
        //          = 0x000F4240 = 1000000. Correct!
        val result = decodeSingleFrame(distance = 1000000)
        assertNotNull(result)
        assertEquals(1000000L, result.newState.wheelDistance)
    }

    // ==================== Pitch Angle ====================

    @Test
    fun `pitch angle is parsed and scaled`() {
        // Decoder: angle = pitchAngle / 100.0
        // pitchAngle = 150 → angle = 1.5
        val result = decodeSingleFrame(pitchAngle = 150)
        assertNotNull(result)
        assertEquals(1.5, result.newState.angle, "Pitch angle should be raw/100")
    }

    @Test
    fun `negative pitch angle works`() {
        // pitchAngle = -250 → angle = -2.5
        val result = decodeSingleFrame(pitchAngle = -250)
        assertNotNull(result)
        assertEquals(-2.5, result.newState.angle)
    }

    // ==================== HW PWM ====================

    @Test
    fun `hwPwm is used when hwPwmEnabled is true`() {
        val cfg = config.copy(hwPwmEnabled = true)
        // hwPwm = 5000 → output = 5000, calculatedPwm = 5000/10000.0 = 0.5
        val result = decodeSingleFrame(hwPwm = 5000, cfg = cfg)
        assertNotNull(result)
        assertEquals(5000, result.newState.output)
        assertEquals(0.5, result.newState.calculatedPwm, 0.001)
    }

    // ==================== Model Detection ====================

    @Test
    fun `model from mVer 0 is Leaperkim Sherman`() {
        // mVer=0 maps to "Leaperkim Sherman" but isReady will be false
        val result = decodeSingleFrame(ver = 0) // mVer = 0/1000 = 0
        assertNotNull(result)
        assertEquals("Leaperkim Sherman", result.newState.model)
    }

    @Test
    fun `model from mVer 1 is Leaperkim Sherman`() {
        val result = decodeSingleFrame(ver = 1000)
        assertNotNull(result)
        assertEquals("Leaperkim Sherman", result.newState.model)
    }

    @Test
    fun `model from mVer 2 is Leaperkim Abrams`() {
        val result = decodeSingleFrame(ver = 2000)
        assertNotNull(result)
        assertEquals("Leaperkim Abrams", result.newState.model)
    }

    @Test
    fun `model from mVer 3 is Leaperkim Sherman S`() {
        val result = decodeSingleFrame(ver = 3000)
        assertNotNull(result)
        assertEquals("Leaperkim Sherman S", result.newState.model)
    }

    @Test
    fun `model from mVer 4 is Leaperkim Patton`() {
        val result = decodeSingleFrame(ver = 4000)
        assertNotNull(result)
        assertEquals("Leaperkim Patton", result.newState.model)
    }

    @Test
    fun `model from mVer 5 is Leaperkim Lynx`() {
        val result = decodeSingleFrame(ver = 5000)
        assertNotNull(result)
        assertEquals("Leaperkim Lynx", result.newState.model)
    }

    @Test
    fun `model from mVer 6 is Leaperkim Sherman L`() {
        val result = decodeSingleFrame(ver = 6000)
        assertNotNull(result)
        assertEquals("Leaperkim Sherman L", result.newState.model)
    }

    @Test
    fun `model from mVer 7 is Leaperkim Patton S`() {
        val result = decodeSingleFrame(ver = 7000)
        assertNotNull(result)
        assertEquals("Leaperkim Patton S", result.newState.model)
    }

    @Test
    fun `model from mVer 8 is Leaperkim Oryx`() {
        val result = decodeSingleFrame(ver = 8000)
        assertNotNull(result)
        assertEquals("Leaperkim Oryx", result.newState.model)
    }

    @Test
    fun `model from mVer 42 is Nosfet Apex`() {
        val result = decodeSingleFrame(ver = 42000)
        assertNotNull(result)
        assertEquals("Nosfet Apex", result.newState.model)
    }

    @Test
    fun `model from mVer 43 is Nosfet Aero`() {
        val result = decodeSingleFrame(ver = 43000)
        assertNotNull(result)
        assertEquals("Nosfet Aero", result.newState.model)
    }

    @Test
    fun `unknown mVer returns Unknown`() {
        val result = decodeSingleFrame(ver = 99000) // mVer=99
        assertNotNull(result)
        assertEquals("Unknown", result.newState.model)
    }

    // ==================== Battery Percentage (Standard) ====================

    @Test
    fun `battery 100V wheel (Sherman) at full charge`() {
        // mVer < 4 → Sherman class, standard %: voltage >= 9870 → 100%
        val result = decodeSingleFrame(voltage = 9870, ver = 1000)
        assertNotNull(result)
        assertEquals(100, result.newState.batteryLevel)
    }

    @Test
    fun `battery 100V wheel (Sherman) at empty`() {
        // mVer < 4 → Sherman class, standard %: voltage <= 7935 → 0%
        val result = decodeSingleFrame(voltage = 7935, ver = 1000)
        assertNotNull(result)
        assertEquals(0, result.newState.batteryLevel)
    }

    @Test
    fun `battery 100V wheel (Sherman) mid charge`() {
        // mVer < 4, standard %: (voltage - 7935) / 19.5
        // voltage = 9686 → (9686 - 7935) / 19.5 = 89.7... → 90
        val result = decodeSingleFrame(voltage = 9686, ver = 1000)
        assertNotNull(result)
        assertEquals(((9686 - 7935) / 19.5).roundToInt(), result.newState.batteryLevel)
    }

    @Test
    fun `battery 126V wheel (Patton) at full charge`() {
        // mVer=4 → Patton, standard %: voltage >= 12337 → 100%
        val result = decodeSingleFrame(voltage = 12337, ver = 4000)
        assertNotNull(result)
        assertEquals(100, result.newState.batteryLevel)
    }

    @Test
    fun `battery 126V wheel (Patton) at empty`() {
        // mVer=4, standard %: voltage <= 9918 → 0%
        val result = decodeSingleFrame(voltage = 9918, ver = 4000)
        assertNotNull(result)
        assertEquals(0, result.newState.batteryLevel)
    }

    @Test
    fun `battery 151V wheel (Lynx) at full charge`() {
        // mVer=5 → Lynx, standard %: voltage >= 14805 → 100%
        val result = decodeSingleFrame(voltage = 14805, ver = 5000)
        assertNotNull(result)
        assertEquals(100, result.newState.batteryLevel)
    }

    @Test
    fun `battery 151V wheel (Lynx) at empty`() {
        // mVer=5, standard %: voltage <= 11902 → 0%
        val result = decodeSingleFrame(voltage = 11902, ver = 5000)
        assertNotNull(result)
        assertEquals(0, result.newState.batteryLevel)
    }

    @Test
    fun `battery 176V wheel (Oryx) at full charge`() {
        // mVer=8 → Oryx, standard %: voltage >= 17272 → 100%
        val result = decodeSingleFrame(voltage = 17272, ver = 8000)
        assertNotNull(result)
        assertEquals(100, result.newState.batteryLevel)
    }

    @Test
    fun `battery 176V wheel (Oryx) at empty`() {
        // mVer=8, standard %: voltage <= 13886 → 0%
        val result = decodeSingleFrame(voltage = 13886, ver = 8000)
        assertNotNull(result)
        assertEquals(0, result.newState.batteryLevel)
    }

    @Test
    fun `battery unknown mVer defaults to 1 percent`() {
        // mVer=99 → unknown wheel → returns 1%
        val result = decodeSingleFrame(voltage = 10000, ver = 99000)
        assertNotNull(result)
        assertEquals(1, result.newState.batteryLevel)
    }

    // ==================== Battery Percentage (SOC Table Lookup) ====================

    @Test
    fun `battery 100V SOC table at full charge`() {
        // mVer=1 (Sherman), useCustomPercents → uses official SOC table
        // Voltage >= last table entry (9900) → 100%
        val cfg = config.copy(useCustomPercents = true)
        val result = decodeSingleFrame(voltage = 9950, ver = 1000, cfg = cfg)
        assertNotNull(result)
        assertEquals(100, result.newState.batteryLevel)
    }

    @Test
    fun `battery 100V SOC table at empty`() {
        // Voltage below first table entry (7560) → 0%
        val cfg = config.copy(useCustomPercents = true)
        val result = decodeSingleFrame(voltage = 7500, ver = 1000, cfg = cfg)
        assertNotNull(result)
        assertEquals(0, result.newState.batteryLevel)
    }

    @Test
    fun `battery 100V SOC table at exact entry`() {
        // Voltage exactly at table[50] = 8837 → 50%
        val cfg = config.copy(useCustomPercents = true)
        val result = decodeSingleFrame(voltage = 8837, ver = 1000, cfg = cfg)
        assertNotNull(result)
        assertEquals(50, result.newState.batteryLevel)
    }

    @Test
    fun `battery 126V SOC table mid range`() {
        // mVer=4 (Patton), table[50] = 11046
        val cfg = config.copy(useCustomPercents = true)
        val result = decodeSingleFrame(voltage = 11046, ver = 4000, cfg = cfg)
        assertNotNull(result)
        assertEquals(50, result.newState.batteryLevel)
    }

    @Test
    fun `battery 151V SOC table mid range`() {
        // mVer=5 (Lynx), table[50] = 13255
        val cfg = config.copy(useCustomPercents = true)
        val result = decodeSingleFrame(voltage = 13255, ver = 5000, cfg = cfg)
        assertNotNull(result)
        assertEquals(50, result.newState.batteryLevel)
    }

    @Test
    fun `battery SOC table interpolates between entries`() {
        // mVer=1 (Sherman), table[49]=8820, table[50]=8837
        // Voltage 8828 is about halfway → should interpolate to ~49
        val cfg = config.copy(useCustomPercents = true)
        val result = decodeSingleFrame(voltage = 8828, ver = 1000, cfg = cfg)
        assertNotNull(result)
        val battery = result.newState.batteryLevel
        assertTrue(battery in 49..50, "Expected ~49-50 but got $battery")
    }

    @Test
    fun `battery Oryx falls back to piecewise (no SOC table)`() {
        // mVer=8 (Oryx) has no official table → uses piecewise-linear even with useCustomPercents
        val cfg = config.copy(useCustomPercents = true)
        val result = decodeSingleFrame(voltage = 17272, ver = 8000, cfg = cfg)
        assertNotNull(result)
        assertEquals(100, result.newState.batteryLevel)
    }

    @Test
    fun `battery Nosfet Aero falls back to piecewise (no SOC table)`() {
        // mVer=43 (Nosfet Aero) has no official table → piecewise fallback
        val cfg = config.copy(useCustomPercents = true)
        val result = decodeSingleFrame(voltage = 12337, ver = 43000, cfg = cfg)
        assertNotNull(result)
        assertEquals(100, result.newState.batteryLevel)
    }

    @Test
    fun `battery Nosfet Apex uses Lynx 151V table`() {
        // mVer=42 (Nosfet Apex) shares Lynx table, table[50] = 13255
        val cfg = config.copy(useCustomPercents = true)
        val result = decodeSingleFrame(voltage = 13255, ver = 42000, cfg = cfg)
        assertNotNull(result)
        assertEquals(50, result.newState.batteryLevel)
    }

    // ==================== Version String ====================

    @Test
    fun `version string is formatted correctly`() {
        // ver=5000 → "5.0.00" padded to length 9 → "000005.00.000" no...
        // version = "${ver/1000}.${(ver%1000)/100}.${ver%100}".padStart(9, '0')
        // ver=5000 → "5.0.0".padStart(9, '0') = "00005.0.0"
        val result = decodeSingleFrame(ver = 5000)
        assertNotNull(result)
        assertEquals("00005.0.0", result.newState.version)
    }

    @Test
    fun `version string with non-trivial values`() {
        // ver=5123 → "${5}.${1}.${23}" = "5.1.23".padStart(9, '0') = "0005.1.23"
        val result = decodeSingleFrame(ver = 5123)
        assertNotNull(result)
        assertEquals("0005.1.23", result.newState.version)
    }

    // ==================== isReady ====================

    @Test
    fun `isReady true after valid frame with mVer greater than 0`() {
        val freshDecoder = VeteranDecoder()
        assertFalse(freshDecoder.isReady(), "Should not be ready before any frame")

        val frame = buildVeteranFrame(ver = 5000) // mVer=5
        val result = freshDecoder.decode(frame, WheelState(), config)
        assertNotNull(result)
        assertTrue(freshDecoder.isReady(), "Should be ready after frame with mVer=5")
    }

    @Test
    fun `isReady false when mVer is 0`() {
        val freshDecoder = VeteranDecoder()
        val frame = buildVeteranFrame(ver = 0) // mVer=0
        freshDecoder.decode(frame, WheelState(), config)
        assertFalse(freshDecoder.isReady(), "Should not be ready when mVer=0")
    }

    @Test
    fun `isReady true for mVer 1`() {
        val freshDecoder = VeteranDecoder()
        val frame = buildVeteranFrame(ver = 1000) // mVer=1
        freshDecoder.decode(frame, WheelState(), config)
        assertTrue(freshDecoder.isReady(), "Should be ready when mVer=1")
    }

    // ==================== Reset ====================

    @Test
    fun `reset clears isReady state`() {
        val freshDecoder = VeteranDecoder()

        // Decode a valid frame to set isReady = true
        val frame = buildVeteranFrame(ver = 5000)
        freshDecoder.decode(frame, WheelState(), config)
        assertTrue(freshDecoder.isReady(), "Should be ready after decode")

        // Reset should clear mVer back to 0
        freshDecoder.reset()
        assertFalse(freshDecoder.isReady(), "Should not be ready after reset")
    }

    @Test
    fun `reset allows decoding fresh frames`() {
        val freshDecoder = VeteranDecoder()

        // First decode
        val frame1 = buildVeteranFrame(ver = 5000, voltage = 9686)
        val result1 = freshDecoder.decode(frame1, WheelState(), config)
        assertNotNull(result1)
        assertEquals("Leaperkim Lynx", result1.newState.model)

        // Reset
        freshDecoder.reset()
        assertFalse(freshDecoder.isReady())

        // Second decode with different model
        val frame2 = buildVeteranFrame(ver = 4000, voltage = 12000)
        val result2 = freshDecoder.decode(frame2, WheelState(), config)
        assertNotNull(result2)
        assertEquals("Leaperkim Patton", result2.newState.model)
        assertTrue(freshDecoder.isReady())
    }

    // ==================== Comparison Test (from GotwayDecoderTest) ====================

    @Test
    fun `decode veteran old board data matches comparison test`() {
        // Same test data from GotwayDecoderTest (originally from VeteranAdapterTest)
        val freshDecoder = VeteranDecoder()
        val byteArray1 = "DC5A5C2025D600003BF500003BF50000FFDE1399".hexToByteArray()
        val byteArray2 = "0DEF0000024602460000000000000000".hexToByteArray()

        var state = WheelState()

        val result1 = freshDecoder.decode(byteArray1, state, config)
        if (result1 != null) state = result1.newState

        val result2 = freshDecoder.decode(byteArray2, state, config)
        assertNotNull(result2)
        state = result2.newState

        // Original expected values from VeteranAdapterTest
        // gotwayNegative=0 (default) → abs() applied to speed and phaseCurrent
        assertEquals(0, abs(state.speed / 100))
        assertEquals(50, state.temperature / 100)
        assertEquals(9686, state.voltage)
        assertEquals(340, state.phaseCurrent) // raw -34 * 10 = -340, abs() → 340
        assertEquals(15349L, state.wheelDistance)
        assertEquals(15349L, state.totalDistance)
        assertEquals(90, state.batteryLevel)
    }

    // ==================== Charging Status ====================

    @Test
    fun `chargeMode is parsed correctly`() {
        // chargeMode = shortFromBytesBE(buff, 22)
        // byte 22 = 0x00 (must be for validation), byte 23 = 0x01 → chargeMode = 1
        val result = decodeSingleFrame(chargeModeLow = 1)
        assertNotNull(result)
        assertEquals(1, result.newState.chargingStatus)
    }

    @Test
    fun `chargeMode zero when both bytes zero`() {
        val result = decodeSingleFrame(chargeModeLow = 0)
        assertNotNull(result)
        assertEquals(0, result.newState.chargingStatus)
    }

    // ==================== Build Command ====================

    @Test
    fun `beep command for old firmware (mVer less than 3)`() {
        val freshDecoder = VeteranDecoder()
        // Decode a frame with mVer=1 to set internal state
        val frame = buildVeteranFrame(ver = 1000)
        freshDecoder.decode(frame, WheelState(), config)

        val commands = freshDecoder.buildCommand(WheelCommand.Beep)
        assertEquals(1, commands.size)
        val sendBytes = commands[0] as WheelCommand.SendBytes
        assertEquals("b", sendBytes.data.decodeToString())
    }

    @Test
    fun `beep command for new firmware (mVer 3 or higher)`() {
        val freshDecoder = VeteranDecoder()
        // Decode a frame with mVer=5 to set internal state
        val frame = buildVeteranFrame(ver = 5000)
        freshDecoder.decode(frame, WheelState(), config)

        val commands = freshDecoder.buildCommand(WheelCommand.Beep)
        assertEquals(1, commands.size)
        val sendBytes = commands[0] as WheelCommand.SendBytes
        // New beep packet: 4C 6B 41 70 0E 00 80 80 80 01 CA 87 E6 6F
        val expected = byteArrayOf(
            0x4C, 0x6B, 0x41, 0x70, 0x0E, 0x00,
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01,
            0xCA.toByte(), 0x87.toByte(), 0xE6.toByte(), 0x6F
        )
        assertTrue(expected.contentEquals(sendBytes.data))
    }

    @Test
    fun `set light on command`() {
        val commands = decoder.buildCommand(WheelCommand.SetLight(enabled = true))
        assertEquals(1, commands.size)
        val sendBytes = commands[0] as WheelCommand.SendBytes
        assertEquals("SetLightON", sendBytes.data.decodeToString())
    }

    @Test
    fun `set light off command`() {
        val commands = decoder.buildCommand(WheelCommand.SetLight(enabled = false))
        assertEquals(1, commands.size)
        val sendBytes = commands[0] as WheelCommand.SendBytes
        assertEquals("SetLightOFF", sendBytes.data.decodeToString())
    }

    @Test
    fun `set pedals mode hard`() {
        val commands = decoder.buildCommand(WheelCommand.SetPedalsMode(mode = 0))
        assertEquals(1, commands.size)
        val sendBytes = commands[0] as WheelCommand.SendBytes
        assertEquals("SETh", sendBytes.data.decodeToString())
    }

    @Test
    fun `set pedals mode medium`() {
        val commands = decoder.buildCommand(WheelCommand.SetPedalsMode(mode = 1))
        assertEquals(1, commands.size)
        val sendBytes = commands[0] as WheelCommand.SendBytes
        assertEquals("SETm", sendBytes.data.decodeToString())
    }

    @Test
    fun `set pedals mode soft`() {
        val commands = decoder.buildCommand(WheelCommand.SetPedalsMode(mode = 2))
        assertEquals(1, commands.size)
        val sendBytes = commands[0] as WheelCommand.SendBytes
        assertEquals("SETs", sendBytes.data.decodeToString())
    }

    @Test
    fun `set pedals mode invalid returns empty`() {
        val commands = decoder.buildCommand(WheelCommand.SetPedalsMode(mode = 3))
        assertTrue(commands.isEmpty(), "Invalid pedals mode should return empty list")
    }

    @Test
    fun `reset trip command`() {
        val commands = decoder.buildCommand(WheelCommand.ResetTrip)
        assertEquals(1, commands.size)
        val sendBytes = commands[0] as WheelCommand.SendBytes
        assertEquals("CLEARMETER", sendBytes.data.decodeToString())
    }

    @Test
    fun `unsupported command returns empty list`() {
        val commands = decoder.buildCommand(WheelCommand.SetMaxSpeed(speed = 50))
        assertTrue(commands.isEmpty(), "Unsupported command should return empty list")
    }

    // ==================== Init Commands & Keep-Alive ====================

    @Test
    fun `getInitCommands returns empty list`() {
        assertTrue(decoder.getInitCommands().isEmpty(),
            "Veteran decoder needs no init commands")
    }

    @Test
    fun `getKeepAliveCommand returns null`() {
        assertNull(decoder.getKeepAliveCommand(),
            "Veteran decoder has no keep-alive")
    }

    // ==================== Multi-frame Processing ====================

    @Test
    fun `split frame across two BLE notifications decodes correctly`() {
        // Same as comparison test: frame split across two BLE packets
        val freshDecoder = VeteranDecoder()
        val part1 = "DC5A5C2025D600003BF500003BF50000FFDE1399".hexToByteArray()
        val part2 = "0DEF0000024602460000000000000000".hexToByteArray()

        val result1 = freshDecoder.decode(part1, WheelState(), config)
        // First part is incomplete — may return null
        val state = result1?.newState ?: WheelState()

        val result2 = freshDecoder.decode(part2, state, config)
        assertNotNull(result2, "Complete frame should decode after second notification")
        assertEquals(9686, result2.newState.voltage)
    }

    // ==================== Wheel Type ====================

    @Test
    fun `wheelType is always VETERAN`() {
        assertEquals(WheelType.VETERAN, decoder.wheelType)

        val result = decodeSingleFrame()
        assertNotNull(result)
        assertEquals(WheelType.VETERAN, result.newState.wheelType)
    }

    // ==================== New Fields from Main Frame ====================

    @Test
    fun `tiltBackSpeed is populated from frame`() {
        // Bytes 26-27 are speed tiltback (BE), multiplied by 10 in decoder,
        // then divided by 10 when stored as tiltBackSpeed
        // speedTiltback = shortFromBytesBE(buff, 26) * 10
        // state.tiltBackSpeed = speedTiltback / 10
        // So tiltBackSpeed = shortFromBytesBE(buff, 26)
        val freshDecoder = VeteranDecoder()
        val frame = buildVeteranFrame()
        // Set bytes 26-27 to 450 (BE) -> tiltBackSpeed = 450 * 10 / 10 = 450
        frame[26] = ((450 shr 8) and 0xFF).toByte()
        frame[27] = (450 and 0xFF).toByte()
        val result = freshDecoder.decode(frame, WheelState(), config)
        assertNotNull(result)
        assertEquals(450, result.newState.tiltBackSpeed)
    }

    @Test
    fun `pedalsMode is populated from frame`() {
        // Bytes 30-31 are pedals mode (BE); byte 30 must be 0x00 or 0x07
        // With byte 30=0x00, byte 31=0x02 -> pedalsMode = 2
        val freshDecoder = VeteranDecoder()
        val frame = buildVeteranFrame()
        frame[30] = 0x00
        frame[31] = 0x02
        val result = freshDecoder.decode(frame, WheelState(), config)
        assertNotNull(result)
        assertEquals(2, result.newState.pedalsMode)
    }

    @Test
    fun `alertSpeed is populated from frame`() {
        // speedAlert = shortFromBytesBE(buff, 24) * 10
        // alertSpeed = speedAlert / 10 = raw value
        // Raw value 85 -> alertSpeed = 85 km/h
        val result = decodeSingleFrame(speedAlert = 85)
        assertNotNull(result)
        assertEquals(85, result.newState.alertSpeed)
    }

    @Test
    fun `alertSpeed zero means off`() {
        val result = decodeSingleFrame(speedAlert = 0)
        assertNotNull(result)
        assertEquals(0, result.newState.alertSpeed)
    }

    @Test
    fun `autoOffTime is populated from frame`() {
        // autoOffSec = shortFromBytesBE(buff, 20) — stored directly in seconds
        val result = decodeSingleFrame(autoOffSec = 1172)
        assertNotNull(result)
        assertEquals(1172, result.newState.autoOffTime)
    }

    @Test
    fun `autoOffTime zero means disabled`() {
        val result = decodeSingleFrame(autoOffSec = 0)
        assertNotNull(result)
        assertEquals(0, result.newState.autoOffTime)
    }

    // ==================== Extended Frame Builder ====================

    /**
     * Build an extended Veteran frame with sub-type data.
     *
     * Extended frames (> 46 bytes) require CRC32 validation by the unpacker
     * since len > 38. The CRC is computed over bytes 0..(len-1) and appended
     * as 4 bytes BE at offset len.
     */
    private fun buildExtendedFrame(
        voltage: Int = 9686,
        ver: Int = 5000,
        subType: Int = 0,
        extraSize: Int = 40
    ): ByteArray {
        val base = buildVeteranFrame(voltage = voltage, ver = ver)
        // Unpacker: len = byte[3]. CRC over bytes 0..(len-1). CRC at offset len.
        // Total buffer = len + 4. We need sub-type at byte 46 plus extra data.
        // len must be > 38 to trigger CRC checking.
        val unpackerLen = 47 + extraSize  // this is what byte[3] will be set to
        val totalSize = unpackerLen + 4   // 4 bytes for CRC appended at offset len

        val extended = ByteArray(totalSize)
        base.copyInto(extended)

        // Update length byte: unpacker reads this as len
        extended[3] = unpackerLen.toByte()

        // Set sub-type at byte 46
        extended[46] = subType.toByte()

        // Compute and append CRC32 over first unpackerLen bytes
        val crc = veteranCrc32(extended, 0, unpackerLen)
        extended[unpackerLen] = ((crc shr 24) and 0xFF).toByte()
        extended[unpackerLen + 1] = ((crc shr 16) and 0xFF).toByte()
        extended[unpackerLen + 2] = ((crc shr 8) and 0xFF).toByte()
        extended[unpackerLen + 3] = (crc and 0xFF).toByte()

        return extended
    }

    /**
     * Convenience: decode an extended frame with a fresh decoder.
     */
    private fun decodeExtendedFrame(
        voltage: Int = 9686,
        ver: Int = 5000,
        subType: Int = 0,
        extraSize: Int = 40,
        frameModifier: (ByteArray) -> Unit = {}
    ): DecodedData? {
        val freshDecoder = VeteranDecoder()
        val frame = buildExtendedFrame(voltage = voltage, ver = ver, subType = subType, extraSize = extraSize)

        // Apply modifications before CRC
        // We need to rebuild CRC after modification
        frameModifier(frame)

        // Recalculate CRC after modification
        val unpackerLen = frame[3].toInt() and 0xFF
        val crc = veteranCrc32(frame, 0, unpackerLen)
        frame[unpackerLen] = ((crc shr 24) and 0xFF).toByte()
        frame[unpackerLen + 1] = ((crc shr 16) and 0xFF).toByte()
        frame[unpackerLen + 2] = ((crc shr 8) and 0xFF).toByte()
        frame[unpackerLen + 3] = (crc and 0xFF).toByte()

        return freshDecoder.decode(frame, WheelState(), config)
    }

    // ==================== Sub-type Extended Data Parsing ====================

    @Test
    fun `sub-type 0 parses roll angle from bytes 67-68`() {
        // Roll angle at bytes 67-68, signed BE, divided by 100
        // 250 -> 2.5 degrees
        val result = decodeExtendedFrame(subType = 0) { frame ->
            frame[67] = ((250 shr 8) and 0xFF).toByte()
            frame[68] = (250 and 0xFF).toByte()
        }
        assertNotNull(result)
        assertEquals(2.5, result.newState.roll, 0.001)
    }

    @Test
    fun `sub-type 5 parses lock state from byte 51`() {
        val result = decodeExtendedFrame(subType = 5) { frame ->
            frame[51] = 0x50.toByte() // locked + password set
        }
        assertNotNull(result)
        assertEquals(0x50, result.newState.lockState)
    }

    @Test
    fun `sub-type 2 overrides battery percent from byte 50`() {
        val result = decodeExtendedFrame(subType = 2) { frame ->
            frame[50] = 75.toByte()
        }
        assertNotNull(result)
        assertEquals(75, result.newState.batteryLevel)
    }

    @Test
    fun `sub-type 2 ignores invalid battery percent`() {
        // Battery > 100 should be ignored, falling back to voltage-based calc
        val result = decodeExtendedFrame(subType = 2, voltage = 9686) { frame ->
            frame[50] = 200.toByte() // invalid (> 100)
        }
        assertNotNull(result)
        // Should use voltage-based calculation, not 200
        assertTrue(result.newState.batteryLevel in 0..100,
            "Battery should use voltage-based calc when sub-type 2 value > 100")
    }

    @Test
    fun `sub-type 8 parses transport mode`() {
        val result = decodeExtendedFrame(subType = 8) { frame ->
            frame[57] = 1.toByte()
        }
        assertNotNull(result)
        assertTrue(result.newState.transportMode)
    }

    @Test
    fun `sub-type 8 parses high speed mode`() {
        val result = decodeExtendedFrame(subType = 8) { frame ->
            frame[61] = 1.toByte()
        }
        assertNotNull(result)
        assertTrue(result.newState.highSpeedMode)
    }

    @Test
    fun `sub-type 8 parses low voltage mode`() {
        val result = decodeExtendedFrame(subType = 8) { frame ->
            frame[60] = 1.toByte()
        }
        assertNotNull(result)
        assertTrue(result.newState.lowVoltageMode)
    }

    @Test
    fun `sub-type 8 parses key tone`() {
        val result = decodeExtendedFrame(subType = 8) { frame ->
            frame[63] = 75.toByte()
        }
        assertNotNull(result)
        assertEquals(75, result.newState.keyTone)
    }

    @Test
    fun `sub-type 8 parses voltage correction`() {
        val result = decodeExtendedFrame(subType = 8) { frame ->
            frame[59] = 20.toByte() // raw 20, decoded = 20 - 15 = +5
        }
        assertNotNull(result)
        assertEquals(5, result.newState.voltageCorrection)
    }

    @Test
    fun `sub-type 8 ignores unsupported fields (0x80)`() {
        // 0x80 means "not supported" — should not change defaults
        val result = decodeExtendedFrame(subType = 8) { frame ->
            frame[57] = 0x80.toByte() // transport mode = not supported
        }
        assertNotNull(result)
        assertFalse(result.newState.transportMode, "0x80 should be treated as unsupported, keeping default false")
    }

    @Test
    fun `sub-type 8 parses pedal hardness as pedalSensitivity`() {
        val result = decodeExtendedFrame(subType = 8) { frame ->
            frame[50] = 65.toByte()
        }
        assertNotNull(result)
        assertEquals(65, result.newState.pedalSensitivity)
    }

    @Test
    fun `sub-type 8 parses stop speed`() {
        val result = decodeExtendedFrame(subType = 8) { frame ->
            frame[52] = 50.toByte() // raw protocol value 50
        }
        assertNotNull(result)
        assertEquals(50, result.newState.stopSpeed)
    }

    @Test
    fun `sub-type 8 parses PWM limit`() {
        val result = decodeExtendedFrame(subType = 8) { frame ->
            frame[53] = 70.toByte()
        }
        assertNotNull(result)
        assertEquals(70, result.newState.pwmLimit)
    }

    @Test
    fun `sub-type 8 parses screen backlight`() {
        val result = decodeExtendedFrame(subType = 8) { frame ->
            frame[55] = 80.toByte()
        }
        assertNotNull(result)
        assertEquals(80, result.newState.screenBacklight)
    }

    @Test
    fun `sub-type 8 parses voltage correction negative`() {
        val result = decodeExtendedFrame(subType = 8) { frame ->
            frame[59] = 5.toByte() // raw 5, decoded = 5 - 15 = -10
        }
        assertNotNull(result)
        assertEquals(-10, result.newState.voltageCorrection)
    }

    @Test
    fun `sub-type 8 parses voltage correction zero`() {
        val result = decodeExtendedFrame(subType = 8) { frame ->
            frame[59] = 15.toByte() // raw 15, decoded = 15 - 15 = 0
        }
        assertNotNull(result)
        assertEquals(0, result.newState.voltageCorrection)
    }

    @Test
    fun `sub-type 8 parses max charge voltage`() {
        val result = decodeExtendedFrame(subType = 8) { frame ->
            frame[64] = 100.toByte()
            frame[65] = 84.toByte() // base voltage
        }
        assertNotNull(result)
        assertEquals(100, result.newState.maxChargeVoltage)
        assertEquals(84, result.newState.maxChargeVoltageBase)
    }

    @Test
    fun `sub-type 8 unsupported fields stay at defaults`() {
        val result = decodeExtendedFrame(subType = 8) { frame ->
            frame[52] = 0x80.toByte() // stopSpeed = not supported
            frame[53] = 0x80.toByte() // PWM limit = not supported
            frame[55] = 0x80.toByte() // backlight = not supported
        }
        assertNotNull(result)
        assertEquals(-1, result.newState.stopSpeed)
        assertEquals(-1, result.newState.pwmLimit)
        assertEquals(-1, result.newState.screenBacklight)
    }

    // ==================== New Binary Commands (mVer >= 3) ====================

    /**
     * Helper: create a fresh decoder with mVer set by decoding one frame.
     */
    private fun decoderWithVer(ver: Int): VeteranDecoder {
        val d = VeteranDecoder()
        val frame = buildVeteranFrame(ver = ver)
        d.decode(frame, WheelState(), config)
        return d
    }

    @Test
    fun `set alarm speed command for new firmware`() {
        val freshDecoder = decoderWithVer(5000) // mVer=5
        val commands = freshDecoder.buildCommand(WheelCommand.SetAlarmSpeed(50, 1))
        assertEquals(2, commands.size) // old "LkAp" + new "LdAp" format
        val oldData = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x6B.toByte(), oldData[1]) // "LkAp"
        assertEquals(0x11.toByte(), oldData[4])
        assertEquals(60.toByte(), oldData[12]) // speed + 10 = 60
        assertEquals(17, oldData.size) // 13 payload + 4 CRC
        val newData = (commands[1] as WheelCommand.SendBytes).data
        assertEquals(0x64.toByte(), newData[1]) // "LdAp"
        assertEquals(0x11.toByte(), newData[4])
        assertEquals(60.toByte(), newData[12])
    }

    @Test
    fun `set pedal tilt command for new firmware`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetPedalTilt(0))
        assertEquals(2, commands.size) // old "LkAp" + new "LdAp" format
        val oldData = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x6B.toByte(), oldData[1]) // "LkAp"
        assertEquals(0x10.toByte(), oldData[4])
        assertEquals(80.toByte(), oldData[11]) // angle + 80 = 80
        val newData = (commands[1] as WheelCommand.SendBytes).data
        assertEquals(0x64.toByte(), newData[1]) // "LdAp"
        assertEquals(0x10.toByte(), newData[4])
        assertEquals(80.toByte(), newData[11])
    }

    @Test
    fun `set transport mode on command for new firmware`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetTransportMode(enabled = true))
        assertEquals(2, commands.size) // old "LkAp" + new "LdAp" format
        val oldData = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x6B.toByte(), oldData[1]) // "LkAp"
        assertEquals(0x16.toByte(), oldData[4])
        assertEquals(1.toByte(), oldData[17])
        val newData = (commands[1] as WheelCommand.SendBytes).data
        assertEquals(0x64.toByte(), newData[1]) // "LdAp"
        assertEquals(0x16.toByte(), newData[4])
        assertEquals(0x02.toByte(), newData[6]) // byte6 = 0x02 for toggle
        assertEquals(1.toByte(), newData[17])
    }

    @Test
    fun `set transport mode off command for new firmware`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetTransportMode(enabled = false))
        assertEquals(2, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x16.toByte(), data[4])
        assertEquals(0.toByte(), data[17])
    }

    @Test
    fun `set voltage correction command for new firmware`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetVoltageCorrection(5))
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x64.toByte(), data[1]) // "LdAp" new format
        assertEquals(0x18.toByte(), data[4]) // cmd byte
        assertEquals(0x02.toByte(), data[6]) // byte6 = 0x02 for control setting
        assertEquals(20.toByte(), data[19]) // value = 5 + 15 = 20
    }

    @Test
    fun `set high speed mode on command for new firmware`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetHighSpeedMode(enabled = true))
        assertEquals(2, commands.size) // old + new format
        val data = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x1A.toByte(), data[4]) // cmd byte
        assertEquals(1.toByte(), data[21]) // value at byte 21
    }

    @Test
    fun `set low voltage mode on command for new firmware`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetLowVoltageMode(enabled = true))
        assertEquals(2, commands.size) // old + new format
        val data = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x19.toByte(), data[4]) // cmd byte
        assertEquals(1.toByte(), data[20]) // value at byte 20
    }

    @Test
    fun `set key tone command for new firmware`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetKeyTone(75))
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x64.toByte(), data[1]) // "LdAp" new format
        assertEquals(0x1C.toByte(), data[4]) // cmd byte
        assertEquals(0x02.toByte(), data[6]) // byte6 = 0x02 for control setting
        assertEquals(75.toByte(), data[23]) // value at byte 23
    }

    // ==================== New LdAp Commands ====================

    @Test
    fun `set screen backlight uses LdAp format`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetScreenBacklight(80))
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x64.toByte(), data[1]) // "LdAp"
        assertEquals(0x14.toByte(), data[4]) // cmd byte
        assertEquals(0x02.toByte(), data[6]) // byte6 = 0x02 for control setting
        assertEquals(80.toByte(), data[15]) // value at byte 15
    }

    @Test
    fun `set stop speed command`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetStopSpeed(60))
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x64.toByte(), data[1]) // "LdAp"
        assertEquals(0x11.toByte(), data[4]) // cmd byte
        assertEquals(0x02.toByte(), data[6]) // byte6 = 0x02 for control setting
        assertEquals(60.toByte(), data[12]) // value at byte 12
    }

    @Test
    fun `set PWM limit command`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetVeteranPwmLimit(80))
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x64.toByte(), data[1]) // "LdAp"
        assertEquals(0x12.toByte(), data[4]) // cmd byte
        assertEquals(0x02.toByte(), data[6]) // byte6 = 0x02 for control setting
        assertEquals(80.toByte(), data[13]) // value at byte 13
    }

    @Test
    fun `set voltage correction encodes offset`() {
        val freshDecoder = decoderWithVer(5000)
        // +10 → protocol value 25 (10 + 15)
        val commands = freshDecoder.buildCommand(WheelCommand.SetVoltageCorrection(10))
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x64.toByte(), data[1]) // "LdAp"
        assertEquals(0x18.toByte(), data[4]) // cmd byte
        assertEquals(0x02.toByte(), data[6]) // byte6 = 0x02 for control setting
        assertEquals(25.toByte(), data[19]) // value = 10 + 15
    }

    @Test
    fun `set voltage correction negative encodes offset`() {
        val freshDecoder = decoderWithVer(5000)
        // -10 → protocol value 5 (-10 + 15)
        val commands = freshDecoder.buildCommand(WheelCommand.SetVoltageCorrection(-10))
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(5.toByte(), data[19]) // value = -10 + 15
    }

    @Test
    fun `set max charge voltage command`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetMaxChargeVoltage(100))
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x64.toByte(), data[1]) // "LdAp"
        assertEquals(0x1D.toByte(), data[4]) // cmd byte
        assertEquals(0x02.toByte(), data[6]) // byte6 = 0x02 for control setting
        assertEquals(100.toByte(), data[24]) // value at byte 24
    }

    @Test
    fun `set lateral cutoff angle command`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetLateralCutoffAngle(70))
        assertEquals(2, commands.size) // old "LkAp" + new "LdAp" format
        val oldData = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x6B.toByte(), oldData[1]) // "LkAp"
        assertEquals(0x16.toByte(), oldData[4])
        assertEquals(70.toByte(), oldData[17])
        val newData = (commands[1] as WheelCommand.SendBytes).data
        assertEquals(0x64.toByte(), newData[1]) // "LdAp"
        assertEquals(0x16.toByte(), newData[4])
        assertEquals(70.toByte(), newData[17])
    }

    @Test
    fun `calibrate command uses LdAp format`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.Calibrate)
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x64.toByte(), data[1]) // "LdAp"
        assertEquals(0x15.toByte(), data[4]) // cmd byte
        assertEquals(0x02.toByte(), data[6]) // byte6 = 0x02 for control setting
        assertEquals(0x01.toByte(), data[16]) // fixed value
    }

    @Test
    fun `new LdAp commands return empty for old firmware`() {
        val freshDecoder = decoderWithVer(1000) // mVer=1
        assertTrue(freshDecoder.buildCommand(WheelCommand.SetScreenBacklight(50)).isEmpty())
        assertTrue(freshDecoder.buildCommand(WheelCommand.SetStopSpeed(60)).isEmpty())
        assertTrue(freshDecoder.buildCommand(WheelCommand.SetVeteranPwmLimit(80)).isEmpty())
        assertTrue(freshDecoder.buildCommand(WheelCommand.SetVoltageCorrection(5)).isEmpty())
        assertTrue(freshDecoder.buildCommand(WheelCommand.SetMaxChargeVoltage(100)).isEmpty())
        assertTrue(freshDecoder.buildCommand(WheelCommand.SetLateralCutoffAngle(70)).isEmpty())
        assertTrue(freshDecoder.buildCommand(WheelCommand.Calibrate).isEmpty())
    }

    @Test
    fun `LdAp command has valid CRC32`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetScreenBacklight(50))
        val data = (commands[0] as WheelCommand.SendBytes).data
        val payloadSize = data.size - 4
        val computedCrc = veteranCrc32(data, 0, payloadSize)
        val extractedCrc = ((data[payloadSize].toLong() and 0xFF) shl 24) or
                ((data[payloadSize + 1].toLong() and 0xFF) shl 16) or
                ((data[payloadSize + 2].toLong() and 0xFF) shl 8) or
                (data[payloadSize + 3].toLong() and 0xFF)
        assertEquals(computedCrc, extractedCrc, "LdAp command should have valid CRC32")
    }

    @Test
    fun `speaker volume returns empty for Veteran`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetSpeakerVolume(50))
        assertTrue(commands.isEmpty(), "Veteran has no speaker volume — byte 59 is voltage correction")
    }

    @Test
    fun `power off command for new firmware`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.PowerOff)
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        // Header
        assertEquals(0x4C.toByte(), data[0])
        assertEquals(0x6B.toByte(), data[1])
        assertEquals(0x41.toByte(), data[2])
        assertEquals(0x70.toByte(), data[3])
        // cmd byte 0x16
        assertEquals(0x16.toByte(), data[4])
        // Total = 18 payload + 4 CRC = 22
        assertEquals(22, data.size)
        // Verify the CRC is present (non-trivial last 4 bytes)
        val payloadSize = data.size - 4
        val crc = veteranCrc32(data, 0, payloadSize)
        val providedCrc = ((data[payloadSize].toLong() and 0xFF) shl 24) or
                ((data[payloadSize + 1].toLong() and 0xFF) shl 16) or
                ((data[payloadSize + 2].toLong() and 0xFF) shl 8) or
                (data[payloadSize + 3].toLong() and 0xFF)
        assertEquals(crc, providedCrc, "PowerOff command should have valid CRC32")
    }

    @Test
    fun `set light binary for new firmware`() {
        val freshDecoder = decoderWithVer(5000) // mVer=5
        val commands = freshDecoder.buildCommand(WheelCommand.SetLight(enabled = true))
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        // Binary format, not string
        assertEquals(0x4C.toByte(), data[0]) // binary header
        assertEquals(0x6B.toByte(), data[1])
        assertEquals(0x0D.toByte(), data[4]) // cmd byte for light
        assertEquals(1.toByte(), data[8]) // value at byte 8
    }

    @Test
    fun `set pedals mode binary for new firmware`() {
        val freshDecoder = decoderWithVer(5000) // mVer=5
        // mode 0 = hard -> inverted to 3
        val commands = freshDecoder.buildCommand(WheelCommand.SetPedalsMode(mode = 0))
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x4C.toByte(), data[0]) // binary header
        assertEquals(0x0C.toByte(), data[4]) // cmd byte for pedals mode
        assertEquals(3.toByte(), data[7]) // value at byte 7 (0 -> 3 inverted)
    }

    @Test
    fun `old firmware commands still use string format`() {
        val freshDecoder = decoderWithVer(1000) // mVer=1
        val commands = freshDecoder.buildCommand(WheelCommand.SetLight(enabled = true))
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        assertEquals("SetLightON", data.decodeToString())
    }

    @Test
    fun `old firmware returns empty for new commands`() {
        val freshDecoder = decoderWithVer(1000) // mVer=1
        val commands = freshDecoder.buildCommand(WheelCommand.SetAlarmSpeed(50, 1))
        assertTrue(commands.isEmpty(), "Old firmware should not support SetAlarmSpeed")
    }

    // ==================== CRC32 Correctness ====================

    @Test
    fun `binary beep command matches hardcoded bytes`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.Beep)
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        val expected = byteArrayOf(
            0x4C, 0x6B, 0x41, 0x70, 0x0E, 0x00,
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01,
            0xCA.toByte(), 0x87.toByte(), 0xE6.toByte(), 0x6F
        )
        assertTrue(expected.contentEquals(data),
            "Beep command should match hardcoded bytes exactly")
    }

    @Test
    fun `binary command has valid CRC32`() {
        val freshDecoder = decoderWithVer(5000)
        val commands = freshDecoder.buildCommand(WheelCommand.SetKeyTone(50))
        assertEquals(1, commands.size)
        val data = (commands[0] as WheelCommand.SendBytes).data
        // Extract and verify CRC
        val payloadSize = data.size - 4
        val computedCrc = veteranCrc32(data, 0, payloadSize)
        val extractedCrc = ((data[payloadSize].toLong() and 0xFF) shl 24) or
                ((data[payloadSize + 1].toLong() and 0xFF) shl 16) or
                ((data[payloadSize + 2].toLong() and 0xFF) shl 8) or
                (data[payloadSize + 3].toLong() and 0xFF)
        assertEquals(computedCrc, extractedCrc,
            "CRC32 appended to command should match computed CRC32")
    }
}

class LookupSocTest {

    @Test
    fun `below table minimum returns 0`() {
        assertEquals(0, lookupSoc(7000, VeteranSocTables.SHERMAN_100V))
    }

    @Test
    fun `at or above table maximum returns 100`() {
        assertEquals(100, lookupSoc(9900, VeteranSocTables.SHERMAN_100V))
        assertEquals(100, lookupSoc(10000, VeteranSocTables.SHERMAN_100V))
    }

    @Test
    fun `exact table entry returns exact index`() {
        // table[0] = 7560
        assertEquals(0, lookupSoc(7560, VeteranSocTables.SHERMAN_100V))
        // table[50] = 8837
        assertEquals(50, lookupSoc(8837, VeteranSocTables.SHERMAN_100V))
        // table[99] = 9900
        assertEquals(100, lookupSoc(9900, VeteranSocTables.SHERMAN_100V))
    }

    @Test
    fun `interpolation between entries`() {
        // table[49]=8820, table[50]=8837, range=17
        // 8828 is 8/17 = 0.47 into the range → 49.47 → rounds to 49
        assertEquals(49, lookupSoc(8828, VeteranSocTables.SHERMAN_100V))
        // 8829 is 9/17 = 0.53 → 49.53 → rounds to 50
        assertEquals(50, lookupSoc(8829, VeteranSocTables.SHERMAN_100V))
    }

    @Test
    fun `all three tables have 100 entries`() {
        assertEquals(100, VeteranSocTables.SHERMAN_100V.size)
        assertEquals(100, VeteranSocTables.PATTON_126V.size)
        assertEquals(100, VeteranSocTables.LYNX_151V.size)
    }

    @Test
    fun `tables are monotonically increasing`() {
        for (table in listOf(VeteranSocTables.SHERMAN_100V, VeteranSocTables.PATTON_126V, VeteranSocTables.LYNX_151V)) {
            for (i in 1 until table.size) {
                assertTrue(table[i] > table[i - 1], "Table not monotonic at index $i: ${table[i - 1]} >= ${table[i]}")
            }
        }
    }
}
