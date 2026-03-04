package org.freewheel.utils

import android.content.Context
import org.freewheel.AppConfig
import org.freewheel.WheelData
import org.freewheel.core.domain.WheelState
import org.freewheel.core.protocol.*
import org.freewheel.utils.Utils.Companion.hexToByteArray
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * Parity tests comparing legacy adapter BMS parsing to KMP decoder BMS parsing.
 * Each test feeds identical BMS packets to both legacy and KMP decoders, then
 * compares all parsed BMS fields.
 */
class BmsParityTest {

    private lateinit var data: WheelData
    private val appConfig = mockkClass(AppConfig::class, relaxed = true)
    private val mockContext = mockk<Context>(relaxed = true)

    // Legacy adapters
    private lateinit var kingsongAdapter: KingsongAdapter
    private lateinit var ninebotZAdapter: NinebotZAdapter

    // KMP decoders
    private val kingsongDecoder = KingsongDecoder()
    private val ninebotZDecoder = NinebotZDecoder()

    private val defaultState = WheelState()
    private val defaultConfig = DecoderConfig()

    @Before
    fun setUp() {
        startKoin {
            modules(module {
                single { appConfig }
                single { mockContext }
            })
        }
        data = spyk(WheelData())
        mockkStatic(WheelData::class)
        every { WheelData.getInstance() } returns data

        kingsongAdapter = KingsongAdapter()
        ninebotZAdapter = NinebotZAdapter()
    }

    @After
    fun tearDown() {
        unmockkAll()
        stopKoin()
    }

    // ==================== Helper ====================

    /**
     * Feed packets to the KMP NinebotZ decoder and return the latest non-null state,
     * accumulating state across packets.
     */
    private fun feedNinebotZPackets(vararg packets: ByteArray): WheelState {
        var state = defaultState
        for (packet in packets) {
            val result = ninebotZDecoder.decode(packet, state, defaultConfig)
            if (result != null) {
                state = result.newState
            }
        }
        return state
    }

    /**
     * Feed a 20-byte Kingsong packet to KMP decoder and return the state.
     */
    private fun feedKingsongPackets(vararg packets: ByteArray): WheelState {
        var state = defaultState
        for (packet in packets) {
            val result = kingsongDecoder.decode(packet, state, defaultConfig)
            if (result != null) {
                state = result.newState
            }
        }
        return state
    }

    // ==================== Kingsong BMS Parity ====================

    @Test
    fun `Kingsong S16 Pro BMS1 pNum 0x00 voltage and capacity match KMP`() {
        // S16 Pro BMS1 pNum=0x00: voltage/current/remCap/factoryCap/fullCycles
        val packet = "aa55f01dfcff4602e7031a00e8030000f1005a5a".hexToByteArray()

        // Legacy
        kingsongAdapter.decode(packet)
        val legacyBms = data.bms1

        // KMP — need a name packet first so the decoder has a model
        val namePacket = "aa554b532d5331362d50726f00000000bb1484fd".hexToByteArray()
        val state = feedKingsongPackets(namePacket, packet)
        val kmpBms = state.bms1!!

        // Compare fields parsed from pNum 0x00
        assertThat(kmpBms.voltage).isWithin(0.01).of(legacyBms.voltage)
        assertThat(kmpBms.current).isWithin(0.01).of(legacyBms.current)
        assertThat(kmpBms.remCap).isEqualTo(legacyBms.remCap)
        assertThat(kmpBms.factoryCap).isEqualTo(legacyBms.factoryCap)
        assertThat(kmpBms.fullCycles).isEqualTo(legacyBms.fullCycles)
    }

    @Test
    fun `Kingsong S16 Pro BMS1 pNum 0x01 temperatures match KMP`() {
        // S16 Pro BMS1 pNum=0x01: temperatures
        val packet = "aa55220b220b220b220b000000005e0bf1015a5a".hexToByteArray()

        // Legacy
        kingsongAdapter.decode(packet)
        val legacyBms = data.bms1

        // KMP
        val namePacket = "aa554b532d5331362d50726f00000000bb1484fd".hexToByteArray()
        val state = feedKingsongPackets(namePacket, packet)
        val kmpBms = state.bms1!!

        // Compare temperature fields
        assertThat(kmpBms.temp1).isWithin(0.1).of(legacyBms.temp1)
        assertThat(kmpBms.temp2).isWithin(0.1).of(legacyBms.temp2)
        assertThat(kmpBms.temp3).isWithin(0.1).of(legacyBms.temp3)
        assertThat(kmpBms.temp4).isWithin(0.1).of(legacyBms.temp4)
        assertThat(kmpBms.tempMos).isWithin(0.1).of(legacyBms.tempMos)
    }

    @Test
    fun `Kingsong S16 Pro BMS1 pNum 0x02 cell voltages match KMP`() {
        // S16 Pro BMS1 pNum=0x02: cells 0-6
        val packet = "aa55e90ee80ee80ee80ee70ef80ef80ef1025a5a".hexToByteArray()

        // Legacy
        kingsongAdapter.decode(packet)
        val legacyBms = data.bms1

        // KMP
        val namePacket = "aa554b532d5331362d50726f00000000bb1484fd".hexToByteArray()
        val state = feedKingsongPackets(namePacket, packet)
        val kmpBms = state.bms1!!

        // Compare first 7 cell voltages
        for (i in 0 until 7) {
            assertThat(kmpBms.cells[i]).isWithin(0.001).of(legacyBms.cells[i])
        }
    }

    @Test
    fun `Kingsong S16 Pro BMS1 pNum 0x03 cell voltages match KMP`() {
        // S16 Pro BMS1 pNum=0x03: cells 7-13
        val packet = "aa55f80ef80ef40ef80ef90efa0ef90ef1035a5a".hexToByteArray()

        // Legacy
        kingsongAdapter.decode(packet)
        val legacyBms = data.bms1

        // KMP
        val namePacket = "aa554b532d5331362d50726f00000000bb1484fd".hexToByteArray()
        val state = feedKingsongPackets(namePacket, packet)
        val kmpBms = state.bms1!!

        // Compare cells 7-13
        for (i in 7 until 14) {
            assertThat(kmpBms.cells[i]).isWithin(0.001).of(legacyBms.cells[i])
        }
    }

    @Test
    fun `Kingsong S16 Pro BMS1 full sequence matches KMP`() {
        // Feed all BMS1 packets (pNum 0x00 through 0x06) to get full BMS picture
        val namePacket = "aa554b532d5331362d50726f00000000bb1484fd".hexToByteArray()
        val packets = arrayOf(
            "aa55f01dfcff4602e7031a00e8030000f1005a5a".hexToByteArray(), // pNum 0x00
            "aa55220b220b220b220b000000005e0bf1015a5a".hexToByteArray(), // pNum 0x01
            "aa55e90ee80ee80ee80ee70ef80ef80ef1025a5a".hexToByteArray(), // pNum 0x02
            "aa55f80ef80ef40ef80ef90efa0ef90ef1035a5a".hexToByteArray(), // pNum 0x03
            "aa55f60ef80ef90ef90efa0ef70e0000f1045a5a".hexToByteArray(), // pNum 0x04
            "aa550000000000000000000000000000f1055a5a".hexToByteArray(), // pNum 0x05
            "aa5500000000000000004a0b00000000f1065a5a".hexToByteArray(), // pNum 0x06
        )

        // Legacy: feed name packet first so model is set, then all BMS packets
        kingsongAdapter.decode(namePacket)
        for (p in packets) {
            kingsongAdapter.decode(p)
        }
        val legacyBms = data.bms1

        // KMP: feed name packet then all BMS packets
        val state = feedKingsongPackets(namePacket, *packets)
        val kmpBms = state.bms1!!

        // Compare all fields
        assertThat(kmpBms.voltage).isWithin(0.01).of(legacyBms.voltage)
        assertThat(kmpBms.current).isWithin(0.01).of(legacyBms.current)
        assertThat(kmpBms.remCap).isEqualTo(legacyBms.remCap)
        assertThat(kmpBms.factoryCap).isEqualTo(legacyBms.factoryCap)
        assertThat(kmpBms.fullCycles).isEqualTo(legacyBms.fullCycles)
        assertThat(kmpBms.temp1).isWithin(0.1).of(legacyBms.temp1)
        assertThat(kmpBms.temp2).isWithin(0.1).of(legacyBms.temp2)
        assertThat(kmpBms.temp3).isWithin(0.1).of(legacyBms.temp3)
        assertThat(kmpBms.temp4).isWithin(0.1).of(legacyBms.temp4)
        assertThat(kmpBms.tempMos).isWithin(0.1).of(legacyBms.tempMos)

        // Cell voltages (S16 Pro has ~20 cells across pNum 0x02-0x06)
        for (i in 0 until 20) {
            assertThat(kmpBms.cells[i]).isWithin(0.001).of(legacyBms.cells[i])
        }

        // Cell statistics (calculated after pNum 0x06)
        assertThat(kmpBms.minCell).isWithin(0.001).of(legacyBms.minCell)
        assertThat(kmpBms.maxCell).isWithin(0.001).of(legacyBms.maxCell)
        assertThat(kmpBms.cellDiff).isWithin(0.001).of(legacyBms.cellDiff)
        assertThat(kmpBms.avgCell).isWithin(0.001).of(legacyBms.avgCell)
    }

    @Test
    fun `Kingsong S16 Pro BMS2 full sequence matches KMP`() {
        // Feed all BMS2 packets (pNum 0x00 through 0x06)
        val namePacket = "aa554b532d5331362d50726f00000000bb1484fd".hexToByteArray()
        val packets = arrayOf(
            "aa55ed1dfbff0d029003a101e8030000f2005a5a".hexToByteArray(), // pNum 0x00
            "aa552c0b220b220b2c0b000000005e0bf2015a5a".hexToByteArray(), // pNum 0x01
            "aa55e70ee50ee60ee60ee30ef70ef70ef2025a5a".hexToByteArray(), // pNum 0x02
            "aa55f70ef70ef50ef70ef80ef80ef80ef2035a5a".hexToByteArray(), // pNum 0x03
            "aa55f50ef70ef70ef80ef80ef40e0000f2045a5a".hexToByteArray(), // pNum 0x04
            "aa550000000000000000000000000000f2055a5a".hexToByteArray(), // pNum 0x05
            "aa5500000000000000004a0b00000000f2065a5a".hexToByteArray(), // pNum 0x06
        )

        // Legacy: feed name packet first so model is set, then all BMS packets
        kingsongAdapter.decode(namePacket)
        for (p in packets) {
            kingsongAdapter.decode(p)
        }
        val legacyBms = data.bms2

        // KMP: feed name packet then all BMS packets
        val state = feedKingsongPackets(namePacket, *packets)
        val kmpBms = state.bms2!!

        // Compare all fields
        assertThat(kmpBms.voltage).isWithin(0.01).of(legacyBms.voltage)
        assertThat(kmpBms.current).isWithin(0.01).of(legacyBms.current)
        assertThat(kmpBms.remCap).isEqualTo(legacyBms.remCap)
        assertThat(kmpBms.factoryCap).isEqualTo(legacyBms.factoryCap)
        assertThat(kmpBms.fullCycles).isEqualTo(legacyBms.fullCycles)
        assertThat(kmpBms.temp1).isWithin(0.1).of(legacyBms.temp1)
        assertThat(kmpBms.temp2).isWithin(0.1).of(legacyBms.temp2)
        assertThat(kmpBms.temp3).isWithin(0.1).of(legacyBms.temp3)
        assertThat(kmpBms.temp4).isWithin(0.1).of(legacyBms.temp4)
        assertThat(kmpBms.tempMos).isWithin(0.1).of(legacyBms.tempMos)

        for (i in 0 until 20) {
            assertThat(kmpBms.cells[i]).isWithin(0.001).of(legacyBms.cells[i])
        }

        assertThat(kmpBms.minCell).isWithin(0.001).of(legacyBms.minCell)
        assertThat(kmpBms.maxCell).isWithin(0.001).of(legacyBms.maxCell)
        assertThat(kmpBms.cellDiff).isWithin(0.001).of(legacyBms.cellDiff)
        assertThat(kmpBms.avgCell).isWithin(0.001).of(legacyBms.avgCell)
    }

    // ==================== Kingsong F-Series Extended BMS ====================

    @Test
    fun `Kingsong F22 extended BMS1 pNum D0 matches KMP`() {
        // F-series extended format with pNum=0xD0 — lots of data in one packet
        val packet = "aa55000000000000000000000000007ff1d05a5a002af90ff80ff90ff90ff80ff80ff80ff80ff70ff70ff70ff70ff70ff70ff60ff60ff60ff60ff50ff50ff50ff70ff80ff80ff70ff70ff80ff70ff70ff70ff80ff70ff80ff60ff60ff60ff60ff50ff50ff50ff50ff10f08b80bcc0bcc0bc20bcc0bd60bcc0bb80bf5ff0e43b60300e7033000e80300f90000000a0200000000".hexToByteArray()

        // Legacy
        kingsongAdapter.decode(packet)
        val legacyBms = data.bms1

        // KMP
        val namePacket = "aa554b532d463232000000000000000000148419".hexToByteArray()
        val state = feedKingsongPackets(namePacket, packet)
        val kmpBms = state.bms1!!

        // Compare voltage/current/capacity
        assertThat(kmpBms.voltage).isWithin(0.01).of(legacyBms.voltage)
        assertThat(kmpBms.current).isWithin(0.01).of(legacyBms.current)
        assertThat(kmpBms.factoryCap).isEqualTo(legacyBms.factoryCap)
        assertThat(kmpBms.fullCycles).isEqualTo(legacyBms.fullCycles)
        assertThat(kmpBms.remPerc).isEqualTo(legacyBms.remPerc)

        // Compare cell voltages (F22 has 42 cells)
        assertThat(kmpBms.cellNum).isEqualTo(legacyBms.cellNum)
        for (i in 0 until kmpBms.cellNum) {
            assertThat(kmpBms.cells[i]).isWithin(0.001).of(legacyBms.cells[i])
        }

        // Compare temperatures
        assertThat(kmpBms.temp1).isWithin(0.1).of(legacyBms.temp1)
        assertThat(kmpBms.temp2).isWithin(0.1).of(legacyBms.temp2)
        assertThat(kmpBms.temp3).isWithin(0.1).of(legacyBms.temp3)
        assertThat(kmpBms.temp4).isWithin(0.1).of(legacyBms.temp4)
        assertThat(kmpBms.tempMos).isWithin(0.1).of(legacyBms.tempMos)

        // Cell statistics
        assertThat(kmpBms.minCell).isWithin(0.001).of(legacyBms.minCell)
        assertThat(kmpBms.maxCell).isWithin(0.001).of(legacyBms.maxCell)
        assertThat(kmpBms.cellDiff).isWithin(0.001).of(legacyBms.cellDiff)
        assertThat(kmpBms.avgCell).isWithin(0.001).of(legacyBms.avgCell)
    }

    @Test
    fun `Kingsong F18 extended BMS1 pNum D0 matches KMP`() {
        val packet = "aa550100000000000000000000000077f1d05a5a00249e0ea30ea10ea10ea20ea20ea20ea20ea20ea10ea10e9e0e9c0e9f0ea20e9f0e9f0ea00ea10ea40ea20ea50ea50ea40ea50ea50ea50ea20ea10ea10ea40ea40ea00ea40ea30ea30e08540b5e0b540b540b680b00005e0b0000f5ffae34e10100e8030700e803ffa100a600b2025a03646c00000000".hexToByteArray()

        // Legacy
        kingsongAdapter.decode(packet)
        val legacyBms = data.bms1

        // KMP
        val namePacket = "aa554b532d463138500000000000000000148419".hexToByteArray()
        val state = feedKingsongPackets(namePacket, packet)
        val kmpBms = state.bms1!!

        assertThat(kmpBms.voltage).isWithin(0.01).of(legacyBms.voltage)
        assertThat(kmpBms.current).isWithin(0.01).of(legacyBms.current)
        assertThat(kmpBms.factoryCap).isEqualTo(legacyBms.factoryCap)
        assertThat(kmpBms.fullCycles).isEqualTo(legacyBms.fullCycles)
        assertThat(kmpBms.cellNum).isEqualTo(legacyBms.cellNum)

        for (i in 0 until kmpBms.cellNum) {
            assertThat(kmpBms.cells[i]).isWithin(0.001).of(legacyBms.cells[i])
        }

        assertThat(kmpBms.temp1).isWithin(0.1).of(legacyBms.temp1)
        assertThat(kmpBms.temp2).isWithin(0.1).of(legacyBms.temp2)
        assertThat(kmpBms.temp3).isWithin(0.1).of(legacyBms.temp3)
        assertThat(kmpBms.temp4).isWithin(0.1).of(legacyBms.temp4)
        assertThat(kmpBms.tempMos).isWithin(0.1).of(legacyBms.tempMos)
    }

    // ==================== NinebotZ BMS Parity ====================

    @Test
    fun `NinebotZ BMS1 serial number matches KMP`() {
        // Z10 BMS1 serial number data
        val p1 = "5AA522113E041034395945513138483151303432".hexToByteArray()
        val p2 = "33160180258025EC13AF00810100000000000001".hexToByteArray()
        val p3 = "256BF8".hexToByteArray()
        // Telemetry packet to trigger state emission
        val livePacket1 = "5aa520143e04b000000000489800004e009c0a7a".hexToByteArray()
        val livePacket2 = "059b97280023016d0472011a1892119c0a7a052a".hexToByteArray()
        val livePacket3 = "f8".hexToByteArray()

        // Legacy
        ninebotZAdapter.decode(p1)
        ninebotZAdapter.decode(p2)
        ninebotZAdapter.decode(p3)
        val legacyBms = data.bms1

        // KMP — feed BMS + live data to trigger state emission
        val state = feedNinebotZPackets(p1, p2, p3, livePacket1, livePacket2, livePacket3)
        val kmpBms = state.bms1!!

        assertThat(kmpBms.serialNumber).isEqualTo(legacyBms.serialNumber)
        assertThat(kmpBms.versionNumber).isEqualTo(legacyBms.versionNumber)
        assertThat(kmpBms.factoryCap).isEqualTo(legacyBms.factoryCap)
        assertThat(kmpBms.actualCap).isEqualTo(legacyBms.actualCap)
        assertThat(kmpBms.fullCycles).isEqualTo(legacyBms.fullCycles)
        assertThat(kmpBms.chargeCount).isEqualTo(legacyBms.chargeCount)
        assertThat(kmpBms.mfgDateStr).isEqualTo(legacyBms.mfgDateStr)
    }

    @Test
    fun `NinebotZ BMS1 status matches KMP`() {
        val p1 = "5AA518113E04300102BF25640011009A162F2E00".hexToByteArray()
        val p2 = "2000000000C025BD256200B2FA".hexToByteArray()
        val livePacket1 = "5aa520143e04b000000000489800004e009c0a7a".hexToByteArray()
        val livePacket2 = "059b97280023016d0472011a1892119c0a7a052a".hexToByteArray()
        val livePacket3 = "f8".hexToByteArray()

        // Legacy
        ninebotZAdapter.decode(p1)
        ninebotZAdapter.decode(p2)
        val legacyBms = data.bms1

        // KMP
        val state = feedNinebotZPackets(p1, p2, livePacket1, livePacket2, livePacket3)
        val kmpBms = state.bms1!!

        assertThat(kmpBms.status).isEqualTo(legacyBms.status)
        assertThat(kmpBms.remCap).isEqualTo(legacyBms.remCap)
        assertThat(kmpBms.remPerc).isEqualTo(legacyBms.remPerc)
        assertThat(kmpBms.current).isWithin(0.01).of(legacyBms.current)
        assertThat(kmpBms.voltage).isWithin(0.01).of(legacyBms.voltage)
        assertThat(kmpBms.temp1).isWithin(0.1).of(legacyBms.temp1)
        assertThat(kmpBms.temp2).isWithin(0.1).of(legacyBms.temp2)
        assertThat(kmpBms.balanceMap).isEqualTo(legacyBms.balanceMap)
        assertThat(kmpBms.health).isEqualTo(legacyBms.health)
    }

    @Test
    fun `NinebotZ BMS1 cell voltages match KMP`() {
        val p1 = "5AA520113E0440341006103110381005103010FA".hexToByteArray()
        val p2 = "0F06103D104010011034103D1051100000000055".hexToByteArray()
        val p3 = "FB".hexToByteArray()
        val livePacket1 = "5aa520143e04b000000000489800004e009c0a7a".hexToByteArray()
        val livePacket2 = "059b97280023016d0472011a1892119c0a7a052a".hexToByteArray()
        val livePacket3 = "f8".hexToByteArray()

        // Legacy
        ninebotZAdapter.decode(p1)
        ninebotZAdapter.decode(p2)
        ninebotZAdapter.decode(p3)
        val legacyBms = data.bms1

        // KMP
        val state = feedNinebotZPackets(p1, p2, p3, livePacket1, livePacket2, livePacket3)
        val kmpBms = state.bms1!!

        // Compare all 16 cells (14 active + 2 zero for Z10)
        assertThat(kmpBms.cellNum).isEqualTo(legacyBms.cellNum)
        for (i in 0 until 16) {
            assertThat(kmpBms.cells[i]).isWithin(0.001).of(legacyBms.cells[i])
        }

        // Cell statistics
        assertThat(kmpBms.minCell).isWithin(0.001).of(legacyBms.minCell)
        assertThat(kmpBms.maxCell).isWithin(0.001).of(legacyBms.maxCell)
        assertThat(kmpBms.cellDiff).isWithin(0.001).of(legacyBms.cellDiff)
        assertThat(kmpBms.avgCell).isWithin(0.001).of(legacyBms.avgCell)
        assertThat(kmpBms.minCellNum).isEqualTo(legacyBms.minCellNum)
        assertThat(kmpBms.maxCellNum).isEqualTo(legacyBms.maxCellNum)
    }

    @Test
    fun `NinebotZ BMS2 serial number matches KMP`() {
        val p1 = "5AA522123E041034395945513138483151303432".hexToByteArray()
        val p2 = "33160180258025EC13B000810100000000000001".hexToByteArray()
        val p3 = "2569F8".hexToByteArray()
        val livePacket1 = "5aa520143e04b000000000489800004e009c0a7a".hexToByteArray()
        val livePacket2 = "059b97280023016d0472011a1892119c0a7a052a".hexToByteArray()
        val livePacket3 = "f8".hexToByteArray()

        // Legacy
        ninebotZAdapter.decode(p1)
        ninebotZAdapter.decode(p2)
        ninebotZAdapter.decode(p3)
        val legacyBms = data.bms2

        // KMP
        val state = feedNinebotZPackets(p1, p2, p3, livePacket1, livePacket2, livePacket3)
        val kmpBms = state.bms2!!

        assertThat(kmpBms.serialNumber).isEqualTo(legacyBms.serialNumber)
        assertThat(kmpBms.versionNumber).isEqualTo(legacyBms.versionNumber)
        assertThat(kmpBms.factoryCap).isEqualTo(legacyBms.factoryCap)
        assertThat(kmpBms.actualCap).isEqualTo(legacyBms.actualCap)
        assertThat(kmpBms.fullCycles).isEqualTo(legacyBms.fullCycles)
        assertThat(kmpBms.chargeCount).isEqualTo(legacyBms.chargeCount)
        assertThat(kmpBms.mfgDateStr).isEqualTo(legacyBms.mfgDateStr)
    }

    @Test
    fun `NinebotZ BMS2 status matches KMP`() {
        val p1 = "5AA518123E043001029C2564000000A0162F2E00".hexToByteArray()
        val p2 = "00000000009C259C25620044FB".hexToByteArray()
        val livePacket1 = "5aa520143e04b000000000489800004e009c0a7a".hexToByteArray()
        val livePacket2 = "059b97280023016d0472011a1892119c0a7a052a".hexToByteArray()
        val livePacket3 = "f8".hexToByteArray()

        // Legacy
        ninebotZAdapter.decode(p1)
        ninebotZAdapter.decode(p2)
        val legacyBms = data.bms2

        // KMP
        val state = feedNinebotZPackets(p1, p2, livePacket1, livePacket2, livePacket3)
        val kmpBms = state.bms2!!

        assertThat(kmpBms.status).isEqualTo(legacyBms.status)
        assertThat(kmpBms.remCap).isEqualTo(legacyBms.remCap)
        assertThat(kmpBms.remPerc).isEqualTo(legacyBms.remPerc)
        assertThat(kmpBms.current).isWithin(0.01).of(legacyBms.current)
        assertThat(kmpBms.voltage).isWithin(0.01).of(legacyBms.voltage)
        assertThat(kmpBms.temp1).isWithin(0.1).of(legacyBms.temp1)
        assertThat(kmpBms.temp2).isWithin(0.1).of(legacyBms.temp2)
        assertThat(kmpBms.balanceMap).isEqualTo(legacyBms.balanceMap)
        assertThat(kmpBms.health).isEqualTo(legacyBms.health)
    }

    @Test
    fun `NinebotZ BMS2 cell voltages match KMP`() {
        val p1 = "5AA520123E04401B102C10221024102310211031".hexToByteArray()
        val p2 = "1030102F10271026103510321035100000000021".hexToByteArray()
        val p3 = "FC".hexToByteArray()
        val livePacket1 = "5aa520143e04b000000000489800004e009c0a7a".hexToByteArray()
        val livePacket2 = "059b97280023016d0472011a1892119c0a7a052a".hexToByteArray()
        val livePacket3 = "f8".hexToByteArray()

        // Legacy
        ninebotZAdapter.decode(p1)
        ninebotZAdapter.decode(p2)
        ninebotZAdapter.decode(p3)
        val legacyBms = data.bms2

        // KMP
        val state = feedNinebotZPackets(p1, p2, p3, livePacket1, livePacket2, livePacket3)
        val kmpBms = state.bms2!!

        assertThat(kmpBms.cellNum).isEqualTo(legacyBms.cellNum)
        for (i in 0 until 16) {
            assertThat(kmpBms.cells[i]).isWithin(0.001).of(legacyBms.cells[i])
        }

        assertThat(kmpBms.minCell).isWithin(0.001).of(legacyBms.minCell)
        assertThat(kmpBms.maxCell).isWithin(0.001).of(legacyBms.maxCell)
        assertThat(kmpBms.cellDiff).isWithin(0.001).of(legacyBms.cellDiff)
        assertThat(kmpBms.avgCell).isWithin(0.001).of(legacyBms.avgCell)
    }

    @Test
    fun `NinebotZ full BMS1 sequence SN + status + cells matches KMP`() {
        // Feed full BMS1 sequence: serial, status, cells
        val snP1 = "5AA522113E041034395945513138483151303432".hexToByteArray()
        val snP2 = "33160180258025EC13AF00810100000000000001".hexToByteArray()
        val snP3 = "256BF8".hexToByteArray()
        val statusP1 = "5AA518113E04300102BF25640011009A162F2E00".hexToByteArray()
        val statusP2 = "2000000000C025BD256200B2FA".hexToByteArray()
        val cellsP1 = "5AA520113E0440341006103110381005103010FA".hexToByteArray()
        val cellsP2 = "0F06103D104010011034103D1051100000000055".hexToByteArray()
        val cellsP3 = "FB".hexToByteArray()
        val livePacket1 = "5aa520143e04b000000000489800004e009c0a7a".hexToByteArray()
        val livePacket2 = "059b97280023016d0472011a1892119c0a7a052a".hexToByteArray()
        val livePacket3 = "f8".hexToByteArray()

        // Legacy
        for (p in arrayOf(snP1, snP2, snP3, statusP1, statusP2, cellsP1, cellsP2, cellsP3)) {
            ninebotZAdapter.decode(p)
        }
        val legacyBms = data.bms1

        // KMP
        val state = feedNinebotZPackets(
            snP1, snP2, snP3, statusP1, statusP2, cellsP1, cellsP2, cellsP3,
            livePacket1, livePacket2, livePacket3
        )
        val kmpBms = state.bms1!!

        // Full comparison
        assertThat(kmpBms.serialNumber).isEqualTo(legacyBms.serialNumber)
        assertThat(kmpBms.versionNumber).isEqualTo(legacyBms.versionNumber)
        assertThat(kmpBms.factoryCap).isEqualTo(legacyBms.factoryCap)
        assertThat(kmpBms.actualCap).isEqualTo(legacyBms.actualCap)
        assertThat(kmpBms.fullCycles).isEqualTo(legacyBms.fullCycles)
        assertThat(kmpBms.chargeCount).isEqualTo(legacyBms.chargeCount)
        assertThat(kmpBms.mfgDateStr).isEqualTo(legacyBms.mfgDateStr)
        assertThat(kmpBms.status).isEqualTo(legacyBms.status)
        assertThat(kmpBms.remCap).isEqualTo(legacyBms.remCap)
        assertThat(kmpBms.remPerc).isEqualTo(legacyBms.remPerc)
        assertThat(kmpBms.current).isWithin(0.01).of(legacyBms.current)
        assertThat(kmpBms.voltage).isWithin(0.01).of(legacyBms.voltage)
        assertThat(kmpBms.temp1).isWithin(0.1).of(legacyBms.temp1)
        assertThat(kmpBms.temp2).isWithin(0.1).of(legacyBms.temp2)
        assertThat(kmpBms.balanceMap).isEqualTo(legacyBms.balanceMap)
        assertThat(kmpBms.health).isEqualTo(legacyBms.health)
        assertThat(kmpBms.cellNum).isEqualTo(legacyBms.cellNum)
        for (i in 0 until 16) {
            assertThat(kmpBms.cells[i]).isWithin(0.001).of(legacyBms.cells[i])
        }
        assertThat(kmpBms.minCell).isWithin(0.001).of(legacyBms.minCell)
        assertThat(kmpBms.maxCell).isWithin(0.001).of(legacyBms.maxCell)
        assertThat(kmpBms.cellDiff).isWithin(0.001).of(legacyBms.cellDiff)
        assertThat(kmpBms.avgCell).isWithin(0.001).of(legacyBms.avgCell)
    }
}
