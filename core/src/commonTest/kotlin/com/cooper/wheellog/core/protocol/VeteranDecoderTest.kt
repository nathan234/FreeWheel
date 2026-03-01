package com.cooper.wheellog.core.protocol

import com.cooper.wheellog.core.domain.WheelState
import com.cooper.wheellog.core.domain.WheelType
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

        // Auto-off seconds at 20-21 (leave 0)

        // Charge mode at 22-23: byte 22 MUST be 0x00, byte 23 must be 0x00 or 0x01
        frame[22] = 0x00
        frame[23] = (chargeModeLow and 0x01).toByte()

        // Speed alert at 24-25 (leave 0)
        // Speed tiltback at 26-27 (leave 0)

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
    fun `model from mVer 0 is Sherman`() {
        // mVer=0 maps to "Sherman" but isReady will be false
        val result = decodeSingleFrame(ver = 0) // mVer = 0/1000 = 0
        assertNotNull(result)
        assertEquals("Sherman", result.newState.model)
    }

    @Test
    fun `model from mVer 1 is Sherman`() {
        val result = decodeSingleFrame(ver = 1000)
        assertNotNull(result)
        assertEquals("Sherman", result.newState.model)
    }

    @Test
    fun `model from mVer 2 is Abrams`() {
        val result = decodeSingleFrame(ver = 2000)
        assertNotNull(result)
        assertEquals("Abrams", result.newState.model)
    }

    @Test
    fun `model from mVer 3 is Sherman S`() {
        val result = decodeSingleFrame(ver = 3000)
        assertNotNull(result)
        assertEquals("Sherman S", result.newState.model)
    }

    @Test
    fun `model from mVer 4 is Patton`() {
        val result = decodeSingleFrame(ver = 4000)
        assertNotNull(result)
        assertEquals("Patton", result.newState.model)
    }

    @Test
    fun `model from mVer 5 is Lynx`() {
        val result = decodeSingleFrame(ver = 5000)
        assertNotNull(result)
        assertEquals("Lynx", result.newState.model)
    }

    @Test
    fun `model from mVer 6 is Sherman L`() {
        val result = decodeSingleFrame(ver = 6000)
        assertNotNull(result)
        assertEquals("Sherman L", result.newState.model)
    }

    @Test
    fun `model from mVer 7 is Patton S`() {
        val result = decodeSingleFrame(ver = 7000)
        assertNotNull(result)
        assertEquals("Patton S", result.newState.model)
    }

    @Test
    fun `model from mVer 8 is Oryx`() {
        val result = decodeSingleFrame(ver = 8000)
        assertNotNull(result)
        assertEquals("Oryx", result.newState.model)
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
    fun `unknown mVer returns Unknown Veteran`() {
        val result = decodeSingleFrame(ver = 99000) // mVer=99
        assertNotNull(result)
        assertEquals("Unknown Veteran", result.newState.model)
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

    // ==================== Battery Percentage (Better/Custom) ====================

    @Test
    fun `battery 100V better percents above 10020`() {
        // mVer=1 (Sherman), useBetterPercents → voltage > 10020 → 100%
        val cfg = config.copy(useCustomPercents = true)
        val result = decodeSingleFrame(voltage = 10050, ver = 1000, cfg = cfg)
        assertNotNull(result)
        assertEquals(100, result.newState.batteryLevel)
    }

    @Test
    fun `battery 100V better percents mid range`() {
        // mVer=1 (Sherman), useBetterPercents, voltage between 8160 and 10020
        // (voltage - 8070) / 19.5
        val cfg = config.copy(useCustomPercents = true)
        val voltage = 9000 // (9000 - 8070) / 19.5 = 47.7 → 48
        val result = decodeSingleFrame(voltage = voltage, ver = 1000, cfg = cfg)
        assertNotNull(result)
        assertEquals(((voltage - 8070) / 19.5).roundToInt(), result.newState.batteryLevel)
    }

    @Test
    fun `battery 100V better percents low range`() {
        // mVer=1 (Sherman), useBetterPercents, voltage between 7935 and 8160
        // (voltage - 7935) / 48.75
        val cfg = config.copy(useCustomPercents = true)
        val voltage = 8000 // (8000 - 7935) / 48.75 = 1.33 → 1
        val result = decodeSingleFrame(voltage = voltage, ver = 1000, cfg = cfg)
        assertNotNull(result)
        assertEquals(((voltage - 7935) / 48.75).roundToInt(), result.newState.batteryLevel)
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
        assertEquals("Lynx", result1.newState.model)

        // Reset
        freshDecoder.reset()
        assertFalse(freshDecoder.isReady())

        // Second decode with different model
        val frame2 = buildVeteranFrame(ver = 4000, voltage = 12000)
        val result2 = freshDecoder.decode(frame2, WheelState(), config)
        assertNotNull(result2)
        assertEquals("Patton", result2.newState.model)
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
}
