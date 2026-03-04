package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comparison tests verifying KMP InMotionV2Decoder produces identical results
 * to the legacy InMotionAdapterV2 using real packet data from legacy tests.
 *
 * Each test cites the specific legacy test case and asserts ALL fields that
 * the legacy test asserts, ensuring comprehensive parity.
 *
 * Based on legacy InMotionAdapterV2Test packet data.
 */
class InMotionV2DecoderComparisonTest {

    private val decoder = InMotionV2Decoder()
    private val defaultState = WheelState()
    private val defaultConfig = DecoderConfig()

    /** Feed all packets in order, returning the final state. */
    private fun feedPackets(vararg hexPackets: String): WheelState {
        var state = defaultState
        for (hex in hexPackets) {
            val result = decoder.decode(hex.hexToByteArray(), state, defaultConfig)
            if (result != null) state = result.newState
        }
        return state
    }

    // ==================== V11 Full Data ====================

    @Test
    fun `V11 full data matches legacy`() {
        // From InMotionAdapterV2Test: decode with v11 full data
        decoder.reset()
        val state = feedPackets(
            "AAAA110882010206010201009C",                                                             // wheel type
            "AAAA11178202313438304341313232323037303032420000000000FD",                                 // serial
            "AAAA111D820622080004030F000602214000010110000602230D00010107000001F3",                     // versions
            "AAAA141AA0207C15C800106464140000000058020000006400001500100010",                           // settings
            "AAAA142B900001142614000000803E498AE00FB209D109CEB000C7DF010000BE720000AB1300008F040000AB0600004C", // statistics
            "AAAA141991E86C000066191C002DB2040064E60000974D050000C7DF01A4",                             // totals
            "AAAA143184E61EEB0561094A11AE04A004DF01402958CBB000CE004A010000D4FF7C15641900000000492B00000000000000000000C6" // real-time
        )

        // Identity
        assertEquals("1480CA122207002B", state.serialNumber)
        assertEquals("InMotion V11", state.model)
        assertEquals("Main:1.1.64 Drv:3.4.8 BLE:1.1.13", state.version)

        // Core telemetry
        assertEquals(24.01, state.speedKmh, 0.01)
        assertEquals(79.10, state.voltageV, 0.01)
        assertEquals(15.15, state.currentA, 0.01)

        // Temperatures
        assertEquals(27, state.temperatureC)
        assertEquals(30, state.temperature2C)
        assertEquals(-176, state.imuTemp)
        assertEquals(-176, state.cpuTemp)

        // IM2-specific
        assertEquals(44.26, state.torque, 0.01)
        assertEquals(1184.0, state.motorPower, 0.01)
        assertEquals(55.00, state.speedLimit, 0.01)
        assertEquals(65.00, state.currentLimit, 0.01)

        // Distances
        assertEquals(4.79, state.wheelDistanceKm, 0.001)
        assertEquals(278800, state.totalDistance.toInt())

        // Battery & power
        assertEquals(88, state.batteryLevel)
        assertEquals(1198.0, state.powerW, 0.01)

        // Orientation
        assertEquals(3.3, state.angle, 0.01)
        assertEquals(-0.44, state.roll, 0.01)
    }

    // ==================== V11 Escape Data ====================

    @Test
    fun `V11 escape data matches legacy`() {
        // From InMotionAdapterV2Test: decode with v11 escape data
        // Packet contains 0xA5A5 escape sequences
        decoder.reset()
        val state = feedPackets(
            "AAAA110882010206010201009C",  // wheel type (to set model)
            "aaaa1431843020a5a50068025207870080009400882c5fc4b000d7001000f4ff2b037c1564190000d9d9492b00000000000000000000a5a5" // real-time with escapes
        )

        assertEquals(6.16, state.speedKmh, 0.01)
        assertEquals(82.40, state.voltageV, 0.01)
        assertEquals(1.65, state.currentA, 0.01)
        assertEquals(20, state.temperatureC)
        assertEquals(39, state.temperature2C)
        assertEquals(41, state.imuTemp)
        assertEquals(41, state.cpuTemp)
        assertEquals(18.74, state.torque, 0.01)
        assertEquals(128.0, state.motorPower, 0.01)
        assertEquals(55.00, state.speedLimit, 0.01)
        assertEquals(65.00, state.currentLimit, 0.01)
        assertEquals(1.48, state.wheelDistanceKm, 0.001)
        assertEquals(95, state.batteryLevel)
        assertEquals(135.0, state.powerW, 0.01)
        assertEquals(0.16, state.angle, 0.01)
        assertEquals(8.11, state.roll, 0.01)
    }

    // ==================== V11 v1.4.0 ====================

    @Test
    fun `V11 v1_4_0 matches legacy`() {
        // From InMotionAdapterV2Test: decode with v11 v1_4_0
        // Uses proto 2 (detected from version 1.4.0)
        decoder.reset()
        val state = feedPackets(
            "AAAA110882010206010201009C",  // wheel type
            "aaaa111d820622000003040300070221000004011a000602230d00010107000001b9",  // version → Main:1.4.0
            "aaaa1445842d1d10000000efff070000000000000000002b0300000000000000008a149612e02e8813641900000000cbb000cccad1000028000000000049140000000000000000000021" // real-time (proto 2 layout)
        )

        assertEquals("Main:1.4.0 Drv:4.3.0 BLE:1.1.13", state.version)
        assertEquals(0.0, state.speedKmh, 0.01)
        assertEquals(74.69, state.voltageV, 0.01)
        assertEquals(0.16, state.currentA, 0.01)
        assertEquals(27, state.temperatureC)
        assertEquals(28, state.temperature2C)
        assertEquals(33, state.imuTemp)
        assertEquals(26, state.cpuTemp)
        assertEquals(-0.17, state.torque, 0.01)
        assertEquals(0.0, state.motorPower, 0.01)
        assertEquals(50.00, state.speedLimit, 0.01)
        assertEquals(65.00, state.currentLimit, 0.01)
        assertEquals(0.0, state.wheelDistanceKm, 0.001)
        assertEquals(53, state.batteryLevel)
        assertEquals(0.0, state.powerW, 0.01)
        assertEquals(0.0, state.angle, 0.01)
        assertEquals(0.0, state.roll, 0.01)
    }

    // ==================== V12HS Full Data ====================

    @Test
    fun `V12HS full data matches legacy`() {
        // From InMotionAdapterV2Test: decode with v12 full data
        decoder.reset()
        val state = feedPackets(
            "aaaa110882010207010103009c",                                                             // wheel type
            "aaaa11178202413033313135353133303030393733300000000000fb",                                 // serial
            "aaaa111d820622700002042000060221180004017d000602232400010203000402bc",                     // versions
            "aaaa142b900001082608000000c1b55622330000000000cdceb0ce0000000000000000000000000000000008000000ce", // statistics
            "aaaa1419916350000074471800d1140400c68e00007d350200b0ce000039",                             // totals
            "aaaa144384cd26090000000e00040000000000000000000000eafb000062009d2450463b1b581b000000000000cdce00ced1d0b03d2828000000004900000000000000000000008c" // real-time
        )

        // Identity
        assertEquals("A031155130009730", state.serialNumber)
        assertEquals("InMotion V12 HS", state.model)
        assertEquals("Main:1.4.24 Drv:4.2.112 BLE:2.1.36", state.version)

        // Core telemetry
        assertEquals(0.0, state.speedKmh, 0.01)
        assertEquals(99.33, state.voltageV, 0.01)
        assertEquals(0.09, state.currentA, 0.01)

        // Temperatures
        assertEquals(29, state.temperatureC)
        assertEquals(30, state.temperature2C)
        assertEquals(32, state.imuTemp)
        assertEquals(33, state.cpuTemp)

        // IM2-specific
        assertEquals(0.14, state.torque, 0.01)
        assertEquals(0.0, state.motorPower, 0.01)
        assertEquals(69.71, state.speedLimit, 0.01)
        assertEquals(70.00, state.currentLimit, 0.01)

        // Distances
        assertEquals(0.0, state.wheelDistanceKm, 0.001)
        assertEquals(205790, state.totalDistance.toInt())

        // Battery & power — batteryLevel = 1 is a known old firmware issue
        assertEquals(1, state.batteryLevel)
        assertEquals(0.0, state.powerW, 0.01)

        // Orientation
        assertEquals(0.0, state.angle, 0.01)
        assertEquals(-10.46, state.roll, 0.01)
    }

    // ==================== V12HS Full Data 2 (Moving) ====================

    @Test
    fun `V12HS moving data matches legacy`() {
        // From InMotionAdapterV2Test: decode with v12 full data 2
        decoder.reset()
        val state = feedPackets(
            "aaaa110882010207010103009c",
            "aaaa11178202413033313135353133303030393733300000000000fb",
            "aaaa111d820622700002042000060221180004017d000602232400010203000402bc",
            "aaaa142b900001082608000000c1b55622330000000000cdceb0ce0000000000000000000000000000000008000000ce",
            "aaaa1419916350000074471800d1140400c68e00007d350200b0ce000039",
            "aaaa144384ae24600479135909c61536085a0b00003f000000eb003700a5aa21b61f50463b1b581b000000000000ddd900dfe5e4b0f9646400000000490800000000000000000000dd"
        )

        assertEquals("A031155130009730", state.serialNumber)
        assertEquals("InMotion V12 HS", state.model)
        assertEquals("Main:1.4.24 Drv:4.2.112 BLE:2.1.36", state.version)

        assertEquals(49.85, state.speedKmh, 0.01)
        assertEquals(93.90, state.voltageV, 0.01)
        assertEquals(11.20, state.currentA, 0.01)
        assertEquals(45, state.temperatureC)
        assertEquals(41, state.temperature2C)
        assertEquals(52, state.imuTemp)
        assertEquals(53, state.cpuTemp)
        assertEquals(23.93, state.torque, 0.01)
        assertEquals(2906.0, state.motorPower, 0.01)
        assertEquals(69.71, state.speedLimit, 0.01)
        assertEquals(70.00, state.currentLimit, 0.01)
        assertEquals(0.55, state.wheelDistanceKm, 0.001)
        assertEquals(205790, state.totalDistance.toInt())
        assertEquals(86, state.batteryLevel)
        assertEquals(2102.0, state.powerW, 0.01)
        assertEquals(0.63, state.angle, 0.01)
        assertEquals(2.35, state.roll, 0.01)
    }

    // ==================== V12HS Data 3 ====================

    @Test
    fun `V12HS data 3 matches legacy`() {
        // From InMotionAdapterV2Test: decode with v12 data 3
        // Model pre-set via wheel type packet (no serial/version/totals)
        decoder.reset()
        val state = feedPackets(
            "aaaa110882010207010103009c",  // wheel type
            "aaaa14438415273500930496014b0535003a0000008d000000fdfe010010271c255046581b581b000000000000ceca00cfd1d0b08d646400000000490000000000000000000000bc" // real-time
        )

        assertEquals(11.71, state.speedKmh, 0.01)
        assertEquals(100.05, state.voltageV, 0.01)
        assertEquals(0.53, state.currentA, 0.01)
        assertEquals(30, state.temperatureC)
        assertEquals(26, state.temperature2C)
        assertEquals(32, state.imuTemp)
        assertEquals(33, state.cpuTemp)
        assertEquals(4.06, state.torque, 0.01)
        assertEquals(58.0, state.motorPower, 0.01)
        assertEquals(70.00, state.speedLimit, 0.01)
        assertEquals(70.00, state.currentLimit, 0.01)
        assertEquals(0.01, state.wheelDistanceKm, 0.001)
        assertEquals(100, state.batteryLevel)
        assertEquals(53.0, state.powerW, 0.01)
        assertEquals(1.41, state.angle, 0.01)
        assertEquals(-2.59, state.roll, 0.01)
    }

    // ==================== V12HS Data 4 ====================

    @Test
    fun `V12HS data 4 matches legacy`() {
        // From InMotionAdapterV2Test: decode with v12 data 4
        decoder.reset()
        val state = feedPackets(
            "aaaa110882010207010103009c",
            "aaaa1443842627090000000000060000000000000000000000b3fd000010271c255046581b581b000000000000ceca00ced0cfb048282800000000490000000000000000000000ef"
        )

        assertEquals(0.0, state.speedKmh, 0.01)
        assertEquals(100.22, state.voltageV, 0.01)
        assertEquals(0.09, state.currentA, 0.01)
        assertEquals(30, state.temperatureC)
        assertEquals(26, state.temperature2C)
        assertEquals(31, state.imuTemp)
        assertEquals(32, state.cpuTemp)
        assertEquals(0.0, state.torque, 0.01)
        assertEquals(0.0, state.motorPower, 0.01)
        assertEquals(70.0, state.speedLimit, 0.01)
        assertEquals(70.0, state.currentLimit, 0.01)
        assertEquals(0.0, state.wheelDistanceKm, 0.001)
        assertEquals(100, state.batteryLevel)
        assertEquals(0.0, state.powerW, 0.01)
        assertEquals(0.0, state.angle, 0.01)
        assertEquals(-5.89, state.roll, 0.01)
    }

    // ==================== V12 PRO Full Data ====================

    @Test
    fun `V12PRO full data matches legacy`() {
        // From InMotionAdapterV2Test: decode with v12 pro full data
        decoder.reset()
        val state = feedPackets(
            "aaaa110882010207030101009c",                                                             // wheel type
            "aaaa11178202413033313138333135303031333832340000000000f7",                                 // serial
            "aaaa111d820622120005060300080221100007016b00080223420001020000050281",                     // versions
            "aaaa142ca0200000000006030008e0151815111600004038114b6464010028646428b80b45450000000000000db0000067", // settings
            "aaaa142b90000140264f000000c5708649380000000000c8c8b0c8000000000000000000000000000000001a000000a7", // statistics
            "aaaa141991e12603006567dd00f6f117001cf40400954f1300b0c80000ca",                             // totals
            "aaaa144384b7261100000085ff5c00000000000000fcff0000eafe000076266d26803ee015581b000000000000c8c800c9b0c7b0b700000000000049000000000000000000000081" // real-time
        )

        // Identity
        assertEquals("A031183150013824", state.serialNumber)
        assertEquals("InMotion V12 PRO", state.model)
        assertEquals("Main:1.7.16 Drv:6.5.18 BLE:2.1.66", state.version)

        // Core telemetry
        assertEquals(0.0, state.speedKmh, 0.01)
        assertEquals(99.11, state.voltageV, 0.01)
        assertEquals(0.17, state.currentA, 0.01)

        // Temperatures
        assertEquals(24, state.temperatureC)
        assertEquals(24, state.temperature2C)
        assertEquals(23, state.imuTemp)
        assertEquals(0, state.cpuTemp)

        // IM2-specific
        assertEquals(-1.23, state.torque, 0.01)
        assertEquals(0.0, state.motorPower, 0.01)
        assertEquals(56.00, state.speedLimit, 0.01)
        assertEquals(70.00, state.currentLimit, 0.01)

        // Distances
        assertEquals(0.0, state.wheelDistanceKm, 0.001)
        assertEquals(2065610, state.totalDistance.toInt())

        // Battery & power
        assertEquals(98, state.batteryLevel)
        assertEquals(0.0, state.powerW, 0.01)

        // Orientation
        assertEquals(-0.04, state.angle, 0.01)
        assertEquals(-2.78, state.roll, 0.01)
    }

    // ==================== V13 Full Data ====================

    @Test
    fun `V13 full data matches legacy`() {
        // From InMotionAdapterV2Test: decode with v13 full data 1
        decoder.reset()
        val state = feedPackets(
            "aaaa1108820102080101010091",                                                             // wheel type
            "aaaa111782024130333131364231383030303130343600000000008a",                                 // serial
            "aaaa112f8206223a000005030008022115000002cf000802230a0002020000050224070001010200010125070001010200010172", // versions
            "aaaa142b9000010126010000004390a7d5010251000701cdcec9d000000000080000000000000004000000070000006c", // statistics
            "aaaa1419915e010000b7660000500900008c0600002d8b0000c9d000007e",                             // totals
            "aaaa145984092f3807000036003735000025130f27b108111d4203b00664fee703050000000000f225e225204e28233421401f401f204e401f709400000000cdccc9d1b0d10000b0286400000000004910000000000000001800000000b3" // real-time
        )

        // Identity
        assertEquals("A03116B180001046", state.serialNumber)
        assertEquals("InMotion V13", state.model)
        assertEquals("Main:2.0.21 Drv:5.0.58 BLE:2.2.10", state.version)

        // Core telemetry
        assertEquals(136.23, state.speedKmh, 0.01)
        assertEquals(120.41, state.voltageV, 0.01)
        assertEquals(18.48, state.currentA, 0.01)

        // Temperatures
        assertEquals(29, state.temperatureC)
        assertEquals(28, state.temperature2C)
        assertEquals(33, state.imuTemp)
        assertEquals(0, state.cpuTemp)

        // IM2-specific
        assertEquals(74.41, state.torque, 0.01)
        assertEquals(1712.0, state.motorPower, 0.01)
        assertEquals(90.00, state.speedLimit, 0.01)
        assertEquals(80.00, state.currentLimit, 0.01)

        // Distances
        assertEquals(4.901, state.wheelDistanceKm, 0.001)
        assertEquals(3500, state.totalDistance.toInt())

        // Battery & power
        assertEquals(97, state.batteryLevel)
        assertEquals(2225.0, state.powerW, 0.01)

        // Orientation
        assertEquals(0.54, state.angle, 0.01)
        assertEquals(-4.12, state.roll, 0.01)
    }

    // ==================== V14s Full Data ====================

    @Test
    fun `V14s full data matches legacy`() {
        // From InMotionAdapterV2Test: decode with v14 full data 1
        decoder.reset()
        val state = feedPackets(
            "aaaa1108820102090201010093",                                                             // wheel type
            "aaaa1117820241303332313743304230303131323245000000000084",                                 // serial
            "aaaa11418206223c00060503000802212800000301000902230100000208000201240200000501000204260200000501000204250200000501000204270200000501000204eb", // versions
            "aaaa142b9000011d261d00000044c5895e2c08ac049205d0d1cbd0510000001e0f0000fc010000070100003401000051", // statistics
            "aaaa1419911d9c000059293800d01106007134010097110600cbd051001c",                             // totals
            "aaaa1459847c334000000000002c0800009900430866004f002700efff6400bfff5e0000000000a5aa26a4261027581b581b401f401f401f401fb88800000000cdcfcad0b0d00000b0cc640000000000491000000000000000000000000064" // real-time
        )

        // Identity
        assertEquals("A03217C0B001122E", state.serialNumber)
        assertEquals("InMotion V14 50S", state.model)
        assertEquals("Main:3.0.40 Drv:5.6.60 BLE:2.0.1", state.version)

        // Core telemetry
        assertEquals(20.92, state.speedKmh, 0.01)
        assertEquals(131.80, state.voltageV, 0.01)
        assertEquals(0.64, state.currentA, 0.01)

        // Temperatures
        assertEquals(29, state.temperatureC)
        assertEquals(31, state.temperature2C)
        assertEquals(32, state.imuTemp)
        assertEquals(0, state.cpuTemp)

        // IM2-specific
        assertEquals(1.53, state.torque, 0.01)
        assertEquals(79.0, state.motorPower, 0.01)
        assertEquals(70.00, state.speedLimit, 0.01)
        assertEquals(80.00, state.currentLimit, 0.01)

        // Distances
        assertEquals(0.94, state.wheelDistanceKm, 0.001)
        assertEquals(399650, state.totalDistance.toInt())

        // Battery & power
        assertEquals(99, state.batteryLevel)
        assertEquals(102.0, state.powerW, 0.01)

        // Orientation
        assertEquals(0.39, state.angle, 0.01)
        assertEquals(-0.17, state.roll, 0.01)
    }

    // ==================== V11Y Full Data ====================

    @Test
    fun `V11Y full data matches legacy`() {
        // From InMotionAdapterV2Test: decode with v11y full data 1
        decoder.reset()
        val state = feedPackets(
            "aaaa110882010206020101009c",                                                             // wheel type
            "aaaa1117820241303332313831304430303130303139000000000083",                                 // serial
            "aaaa112f8206220800030603000802213400050201000902230300030108000201240d00010101000101250d00010101000101ac", // versions
            "aaaa1428a0200410100e401f401f0000006464323232000000005802000a28645a280000144001040100250d92", // settings
            "aaaa142b9000011f261f0000004456569ac5024c005400ccc5d0cb030000003e000000000000002000000073000000f5", // statistics
            "aaaa141991c82e0000266708008d62000091e400005e720300d0cb03009e",                             // totals
            "aaaa145984941e11000000000087000000090104020000000000006502000000000300000000004b20451fe02e0410100e401f401fa816a816c05d00000000ccc5cecdb0cd0000b0c36400000000004900000000000000000000000000fe" // real-time
        )

        // Identity
        assertEquals("A0321810D0010019", state.serialNumber)
        assertEquals("InMotion V11y", state.model)
        assertEquals("Main:2.5.52 Drv:6.3.8 BLE:1.3.3", state.version)

        // Core telemetry
        assertEquals(1.35, state.speedKmh, 0.01)
        assertEquals(78.28, state.voltageV, 0.01)
        assertEquals(0.17, state.currentA, 0.01)

        // Temperatures
        assertEquals(28, state.temperatureC)
        assertEquals(21, state.temperature2C)
        assertEquals(29, state.imuTemp)
        assertEquals(0, state.cpuTemp)

        // IM2-specific
        assertEquals(2.65, state.torque, 0.01)
        assertEquals(0.0, state.motorPower, 0.01)
        assertEquals(41.00, state.speedLimit, 0.01)
        assertEquals(58.00, state.currentLimit, 0.01)

        // Distances
        assertEquals(0.03, state.wheelDistanceKm, 0.001)
        assertEquals(119760, state.totalDistance.toInt())

        // Battery & power
        assertEquals(81, state.batteryLevel)
        assertEquals(0.0, state.powerW, 0.01)

        // Orientation
        assertEquals(0.0, state.angle, 0.01)
        assertEquals(6.13, state.roll, 0.01)
    }

    // ==================== V9 Full Data ====================

    @Test
    fun `V9 full data matches legacy`() {
        // From InMotionAdapterV2Test: decode with v9 full data 1
        decoder.reset()
        val state = feedPackets(
            "aaaa11088201020c0101010095",                                                             // wheel type
            "aaaa11178202413134323139353041303030343635460000000000fd",                                 // serial
            "aaaa11388206222800040719000802212600080101000902230a0004010a0002012401000102010001012501000102010001012f0500050101000000b8", // versions
            "aaaa142ca0202a000000071900089411a00f9511000058020064641a020a28646428d0071e32010001012501053015009c", // settings
            "aaaa142b900001162617000000c59d4980520367003100cdc9c9c9060000005d0000000000000044000000ca010000cf", // statistics
            "aaaa14199191620000c1a216008bc301006ffe000037890200ffffd5fe55",                             // totals
            "aaaa1457843e1e0c000000000000000000afffc30000000000ffffd7fe000000000600000000009a17191670178510a00f401f401fa00fa00f983a00000000cdc900ceb0cec8ceb03a6400000000004900000000000000000000003f" // real-time
        )

        // Identity
        assertEquals("A1421950A000465F", state.serialNumber)
        assertEquals("InMotion V9", state.model)
        assertEquals("Main:1.8.38 Drv:7.4.40 BLE:1.4.10", state.version)

        // Core telemetry
        assertEquals(0.0, state.speedKmh, 0.01)
        assertEquals(77.42, state.voltageV, 0.01)
        assertEquals(0.12, state.currentA, 0.01)

        // Temperatures
        assertEquals(29, state.temperatureC)
        assertEquals(25, state.temperature2C)
        assertEquals(30, state.imuTemp)
        assertEquals(0, state.cpuTemp)

        // IM2-specific
        assertEquals(-0.81, state.torque, 0.01)
        assertEquals(0.0, state.motorPower, 0.01)
        assertEquals(42.29, state.speedLimit, 0.01)
        assertEquals(40.00, state.currentLimit, 0.01)

        // Distances
        assertEquals(0.06, state.wheelDistanceKm, 0.001)
        assertEquals(252330, state.totalDistance.toInt())

        // Battery & power
        assertEquals(58, state.batteryLevel)
        assertEquals(0.0, state.powerW, 0.01)

        // Orientation
        assertEquals(-0.01, state.angle, 0.01)
        assertEquals(-2.97, state.roll, 0.01)
    }

    // ==================== V12S Full Data ====================

    @Test
    fun `V12S full data matches legacy`() {
        // From InMotionAdapterV2Test: decode with v12s full data 1
        decoder.reset()
        val state = feedPackets(
            "aaaa11088201020b0101010092",                                                             // wheel type
            "aaaa1117820241313432313934303730303333353943000000000084",                                 // serial
            "aaaa11418206220e0011060300080221380008016b000802232a0003010a0002012400000301040000002508000101040000002e18000001000000012f050005010100000087", // versions
            "aaaa142ca0200000000006030008581b581bb80b0000580210646415020a28646428d0073232040000002508053014008e", // settings
            "aaaa142b900001252626000000050629d50000000000008282828200000000000000000000000000000000090000007d", // statistics
            "aaaa1419911d81000080711c00bd92020019000100cd2002008282000037",                             // totals
            "aaaa145784b520010000000000000000000000000000000000d7e40000d7e400000000000000002427f026e02e581b581b401f401f581b581b786900000000cdce00ceb0cbccceb0216403000000000000000000000000000000001b" // real-time
        )

        // Identity
        assertEquals("A14219407003359C", state.serialNumber)
        assertEquals("InMotion V12S", state.model)
        assertEquals("Main:1.8.56 Drv:6.17.14 BLE:1.3.42", state.version)

        // Core telemetry
        assertEquals(0.0, state.speedKmh, 0.01)
        assertEquals(83.73, state.voltageV, 0.01)
        assertEquals(0.01, state.currentA, 0.01)

        // Temperatures
        assertEquals(29, state.temperatureC)
        assertEquals(30, state.temperature2C)
        assertEquals(27, state.imuTemp)
        assertEquals(0, state.cpuTemp)

        // IM2-specific
        assertEquals(0.00, state.torque, 0.01)
        assertEquals(0.0, state.motorPower, 0.01)
        assertEquals(70.00, state.speedLimit, 0.01)
        assertEquals(70.00, state.currentLimit, 0.01)

        // Distances
        assertEquals(0.0, state.wheelDistanceKm, 0.001)
        assertEquals(330530, state.totalDistance.toInt())

        // Battery & power
        assertEquals(100, state.batteryLevel)
        assertEquals(0.0, state.powerW, 0.01)

        // Orientation
        assertEquals(-69.53, state.angle, 0.01)
        assertEquals(0.0, state.roll, 0.01)
    }

    // ==================== Version Parsing ====================

    @Test
    fun `V11 version v1_4_0 parsed correctly`() {
        // From InMotionAdapterV2Test: decode version with v11 v1_4_0
        decoder.reset()
        feedPackets(
            "AAAA110882010206010201009C",  // wheel type (needed for model context)
            "aaaa111d820622000003040300070221000004011a000602230d00010107000001b9"
        )
        // Version should be set on the decoder even though it doesn't produce new telemetry.
        // Feed a dummy real-time packet to get the version in state... or just check the decoder directly.
        // The version is stored in the decoder and reflected in the next state update.
        // Since the legacy test asserts result=false and version on data, we verify via a subsequent decode.
        val wheelType = "AAAA110882010206010201009C"
        val version = "aaaa111d820622000003040300070221000004011a000602230d00010107000001b9"
        val mainData = "aaaa1445842d1d10000000efff070000000000000000002b0300000000000000008a149612e02e8813641900000000cbb000cccad1000028000000000049140000000000000000000021"
        decoder.reset()
        val state = feedPackets(wheelType, version, mainData)
        assertEquals("Main:1.4.0 Drv:4.3.0 BLE:1.1.13", state.version)
    }

    @Test
    fun `V12 version parsed correctly`() {
        // From InMotionAdapterV2Test: decode version with v12
        decoder.reset()
        val wheelType = "AAAA110882010207010103009C"
        val version = "aaaa111d820622790002042000060221040005017d000602233700010203000402bb"
        val mainData = "aaaa144384cd26090000000e00040000000000000000000000eafb000062009d2450463b1b581b000000000000cdce00ced1d0b03d2828000000004900000000000000000000008c"
        decoder.reset()
        val state = feedPackets(wheelType, version, mainData)
        assertEquals("Main:1.5.4 Drv:4.2.121 BLE:2.1.55", state.version)
    }
}
