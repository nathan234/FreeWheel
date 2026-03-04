package org.freewheel.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for SmartBms data container class.
 * Verifies initialization, reset behavior, and property storage.
 */
class SmartBmsTest {

    // ==================== Initialization Tests ====================

    @Test
    fun `new instance has default values`() {
        val bms = SmartBms()

        assertEquals("", bms.serialNumber)
        assertEquals("", bms.versionNumber)
        assertEquals(0, bms.factoryCap)
        assertEquals(0, bms.actualCap)
        assertEquals(0, bms.fullCycles)
        assertEquals(0, bms.chargeCount)
        assertEquals("", bms.mfgDateStr)
        assertEquals(0, bms.status)
        assertEquals(0, bms.remCap)
        assertEquals(0, bms.remPerc)
        assertEquals(0.0, bms.current)
        assertEquals(0.0, bms.voltage)
    }

    @Test
    fun `cells array is initialized with correct size`() {
        val bms = SmartBms()

        assertEquals(SmartBms.MAX_CELLS, bms.cells.size)
    }

    @Test
    fun `cells array is initialized with zeros`() {
        val bms = SmartBms()

        for (i in 0 until SmartBms.MAX_CELLS) {
            assertEquals(0.0, bms.cells[i], "Cell $i should be 0.0")
        }
    }

    @Test
    fun `MAX_CELLS constant is 56`() {
        assertEquals(56, SmartBms.MAX_CELLS)
    }

    // ==================== Reset Tests ====================

    @Test
    fun `reset clears serial number`() {
        val bms = SmartBms()
        bms.serialNumber = "TEST12345"

        bms.reset()

        assertEquals("", bms.serialNumber)
    }

    @Test
    fun `reset clears version number`() {
        val bms = SmartBms()
        bms.versionNumber = "1.2.3"

        bms.reset()

        assertEquals("", bms.versionNumber)
    }

    @Test
    fun `reset clears capacity values`() {
        val bms = SmartBms()
        bms.factoryCap = 10000
        bms.actualCap = 9500
        bms.remCap = 5000
        bms.remPerc = 50

        bms.reset()

        assertEquals(0, bms.factoryCap)
        assertEquals(0, bms.actualCap)
        assertEquals(0, bms.remCap)
        assertEquals(0, bms.remPerc)
    }

    @Test
    fun `reset clears cycle counts`() {
        val bms = SmartBms()
        bms.fullCycles = 100
        bms.chargeCount = 250

        bms.reset()

        assertEquals(0, bms.fullCycles)
        assertEquals(0, bms.chargeCount)
    }

    @Test
    fun `reset clears voltage and current`() {
        val bms = SmartBms()
        bms.voltage = 84.5
        bms.current = 15.3
        bms.semiVoltage1 = 42.25
        bms.semiVoltage2 = 42.25

        bms.reset()

        assertEquals(0.0, bms.voltage)
        assertEquals(0.0, bms.current)
        assertEquals(0.0, bms.semiVoltage1)
        assertEquals(0.0, bms.semiVoltage2)
    }

    @Test
    fun `reset clears all temperatures`() {
        val bms = SmartBms()
        bms.temp1 = 25.0
        bms.temp2 = 26.0
        bms.temp3 = 27.0
        bms.temp4 = 28.0
        bms.temp5 = 29.0
        bms.temp6 = 30.0
        bms.tempMos = 35.0
        bms.tempMosEnv = 22.0
        bms.temp1Env = 20.0
        bms.temp2Env = 21.0

        bms.reset()

        assertEquals(0.0, bms.temp1)
        assertEquals(0.0, bms.temp2)
        assertEquals(0.0, bms.temp3)
        assertEquals(0.0, bms.temp4)
        assertEquals(0.0, bms.temp5)
        assertEquals(0.0, bms.temp6)
        assertEquals(0.0, bms.tempMos)
        assertEquals(0.0, bms.tempMosEnv)
        assertEquals(0.0, bms.temp1Env)
        assertEquals(0.0, bms.temp2Env)
    }

    @Test
    fun `reset clears humidity values`() {
        val bms = SmartBms()
        bms.humidity1Env = 45.0
        bms.humidity2Env = 50.0

        bms.reset()

        assertEquals(0.0, bms.humidity1Env)
        assertEquals(0.0, bms.humidity2Env)
    }

    @Test
    fun `reset clears cell statistics`() {
        val bms = SmartBms()
        bms.minCell = 3.95
        bms.maxCell = 4.20
        bms.cellDiff = 0.25
        bms.avgCell = 4.10
        bms.minCellNum = 5
        bms.maxCellNum = 12
        bms.cellNum = 20

        bms.reset()

        assertEquals(0.0, bms.minCell)
        assertEquals(0.0, bms.maxCell)
        assertEquals(0.0, bms.cellDiff)
        assertEquals(0.0, bms.avgCell)
        assertEquals(0, bms.minCellNum)
        assertEquals(0, bms.maxCellNum)
        assertEquals(0, bms.cellNum)
    }

    @Test
    fun `reset clears balance map and health`() {
        val bms = SmartBms()
        bms.balanceMap = 0xFF00
        bms.health = 95

        bms.reset()

        assertEquals(0, bms.balanceMap)
        assertEquals(0, bms.health)
    }

    @Test
    fun `reset clears cells array`() {
        val bms = SmartBms()
        for (i in 0 until 16) {
            bms.cells[i] = 4.0 + (i * 0.01)
        }

        bms.reset()

        for (i in 0 until SmartBms.MAX_CELLS) {
            assertEquals(0.0, bms.cells[i], "Cell $i should be 0.0 after reset")
        }
    }

    // ==================== Property Storage Tests ====================

    @Test
    fun `serial number can be set and retrieved`() {
        val bms = SmartBms()
        bms.serialNumber = "49YEQ18H1Q0423"

        assertEquals("49YEQ18H1Q0423", bms.serialNumber)
    }

    @Test
    fun `version number can be set and retrieved`() {
        val bms = SmartBms()
        bms.versionNumber = "1.1.6"

        assertEquals("1.1.6", bms.versionNumber)
    }

    @Test
    fun `manufacturing date can be set and retrieved`() {
        val bms = SmartBms()
        bms.mfgDateStr = "01.08.2018"

        assertEquals("01.08.2018", bms.mfgDateStr)
    }

    @Test
    fun `capacity values can be set and retrieved`() {
        val bms = SmartBms()
        bms.factoryCap = 9600
        bms.actualCap = 9600
        bms.remCap = 9663
        bms.remPerc = 100

        assertEquals(9600, bms.factoryCap)
        assertEquals(9600, bms.actualCap)
        assertEquals(9663, bms.remCap)
        assertEquals(100, bms.remPerc)
    }

    @Test
    fun `cycle counts can be set and retrieved`() {
        val bms = SmartBms()
        bms.fullCycles = 175
        bms.chargeCount = 385

        assertEquals(175, bms.fullCycles)
        assertEquals(385, bms.chargeCount)
    }

    @Test
    fun `voltage and current can be set and retrieved`() {
        val bms = SmartBms()
        bms.voltage = 57.86
        bms.current = 0.17

        assertEquals(57.86, bms.voltage, 0.001)
        assertEquals(0.17, bms.current, 0.001)
    }

    @Test
    fun `semi voltages can be set and retrieved`() {
        val bms = SmartBms()
        bms.semiVoltage1 = 28.93
        bms.semiVoltage2 = 28.93

        assertEquals(28.93, bms.semiVoltage1, 0.001)
        assertEquals(28.93, bms.semiVoltage2, 0.001)
    }

    @Test
    fun `temperatures can be set and retrieved`() {
        val bms = SmartBms()
        bms.temp1 = 27.0
        bms.temp2 = 26.0
        bms.temp3 = 25.0
        bms.temp4 = 24.0
        bms.temp5 = 23.0
        bms.temp6 = 22.0

        assertEquals(27.0, bms.temp1)
        assertEquals(26.0, bms.temp2)
        assertEquals(25.0, bms.temp3)
        assertEquals(24.0, bms.temp4)
        assertEquals(23.0, bms.temp5)
        assertEquals(22.0, bms.temp6)
    }

    @Test
    fun `MOS temperatures can be set and retrieved`() {
        val bms = SmartBms()
        bms.tempMos = 35.5
        bms.tempMosEnv = 22.0

        assertEquals(35.5, bms.tempMos)
        assertEquals(22.0, bms.tempMosEnv)
    }

    @Test
    fun `environment sensors can be set and retrieved`() {
        val bms = SmartBms()
        bms.temp1Env = 20.0
        bms.temp2Env = 21.0
        bms.humidity1Env = 45.0
        bms.humidity2Env = 50.0

        assertEquals(20.0, bms.temp1Env)
        assertEquals(21.0, bms.temp2Env)
        assertEquals(45.0, bms.humidity1Env)
        assertEquals(50.0, bms.humidity2Env)
    }

    @Test
    fun `balance map can be set and retrieved`() {
        val bms = SmartBms()
        bms.balanceMap = 8192  // Bit 13 set

        assertEquals(8192, bms.balanceMap)
    }

    @Test
    fun `health percentage can be set and retrieved`() {
        val bms = SmartBms()
        bms.health = 98

        assertEquals(98, bms.health)
    }

    @Test
    fun `status can be set and retrieved`() {
        val bms = SmartBms()
        bms.status = 513

        assertEquals(513, bms.status)
    }

    // ==================== Cell Array Tests ====================

    @Test
    fun `individual cells can be set and retrieved`() {
        val bms = SmartBms()
        bms.cells[0] = 4.148
        bms.cells[1] = 4.102
        bms.cells[13] = 4.177

        assertEquals(4.148, bms.cells[0], 0.001)
        assertEquals(4.102, bms.cells[1], 0.001)
        assertEquals(4.177, bms.cells[13], 0.001)
    }

    @Test
    fun `cell statistics can be set and retrieved`() {
        val bms = SmartBms()
        bms.minCell = 4.090
        bms.maxCell = 4.177
        bms.cellDiff = 0.087
        bms.avgCell = 4.135
        bms.minCellNum = 7
        bms.maxCellNum = 14
        bms.cellNum = 14

        assertEquals(4.090, bms.minCell, 0.001)
        assertEquals(4.177, bms.maxCell, 0.001)
        assertEquals(0.087, bms.cellDiff, 0.001)
        assertEquals(4.135, bms.avgCell, 0.001)
        assertEquals(7, bms.minCellNum)
        assertEquals(14, bms.maxCellNum)
        assertEquals(14, bms.cellNum)
    }

    @Test
    fun `all 56 cells can be accessed`() {
        val bms = SmartBms()

        // Set all cells
        for (i in 0 until SmartBms.MAX_CELLS) {
            bms.cells[i] = 3.0 + (i * 0.02)
        }

        // Verify all cells
        for (i in 0 until SmartBms.MAX_CELLS) {
            assertEquals(3.0 + (i * 0.02), bms.cells[i], 0.001, "Cell $i value mismatch")
        }
    }

    // ==================== Real World Data Tests ====================

    @Test
    fun `BMS1 from Z10 test data can be stored`() {
        // From NinebotZAdapterTest: decode z10 bms1 sn data
        val bms = SmartBms()

        bms.serialNumber = "49YEQ18H1Q0423"
        bms.versionNumber = "1.1.6"
        bms.factoryCap = 9600
        bms.actualCap = 9600
        bms.fullCycles = 175
        bms.chargeCount = 385
        bms.mfgDateStr = "01.08.2018"

        assertEquals("49YEQ18H1Q0423", bms.serialNumber)
        assertEquals("1.1.6", bms.versionNumber)
        assertEquals(9600, bms.factoryCap)
        assertEquals(9600, bms.actualCap)
        assertEquals(175, bms.fullCycles)
        assertEquals(385, bms.chargeCount)
        assertEquals("01.08.2018", bms.mfgDateStr)
    }

    @Test
    fun `BMS1 status from Z10 test data can be stored`() {
        // From NinebotZAdapterTest: decode z10 bms1 status data
        val bms = SmartBms()

        bms.status = 513
        bms.remCap = 9663
        bms.remPerc = 100
        bms.current = 0.17
        bms.voltage = 57.86
        bms.temp1 = 27.0
        bms.temp2 = 26.0
        bms.balanceMap = 8192
        bms.health = 98

        assertEquals(513, bms.status)
        assertEquals(9663, bms.remCap)
        assertEquals(100, bms.remPerc)
        assertEquals(0.17, bms.current, 0.01)
        assertEquals(57.86, bms.voltage, 0.01)
        assertEquals(27.0, bms.temp1)
        assertEquals(26.0, bms.temp2)
        assertEquals(8192, bms.balanceMap)
        assertEquals(98, bms.health)
    }

    @Test
    fun `BMS1 cells from Z10 test data can be stored`() {
        // From NinebotZAdapterTest: decode z10 bms1 cells data
        val bms = SmartBms()

        val expectedCells = doubleArrayOf(
            4.148, 4.102, 4.145, 4.152, 4.101, 4.144, 4.090,
            4.102, 4.157, 4.160, 4.097, 4.148, 4.157, 4.177
        )

        for (i in expectedCells.indices) {
            bms.cells[i] = expectedCells[i]
        }
        bms.cellNum = 14

        for (i in expectedCells.indices) {
            assertEquals(expectedCells[i], bms.cells[i], 0.001, "Cell $i mismatch")
        }
        assertEquals(14, bms.cellNum)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `negative current for discharge is stored correctly`() {
        val bms = SmartBms()
        bms.current = -15.5  // Discharging

        assertEquals(-15.5, bms.current, 0.001)
    }

    @Test
    fun `zero values are valid`() {
        val bms = SmartBms()
        bms.voltage = 0.0
        bms.current = 0.0
        bms.health = 0

        assertEquals(0.0, bms.voltage)
        assertEquals(0.0, bms.current)
        assertEquals(0, bms.health)
    }

    @Test
    fun `maximum cell count is 56`() {
        val bms = SmartBms()
        bms.cellNum = 56

        assertEquals(56, bms.cellNum)
        assertEquals(56, bms.cells.size)
    }

    @Test
    fun `empty serial number is valid`() {
        val bms = SmartBms()
        // Serial number defaults to empty string

        assertEquals("", bms.serialNumber)
    }

    @Test
    fun `balance map with all bits set`() {
        val bms = SmartBms()
        bms.balanceMap = 0xFFFF  // All 16 cells balancing

        assertEquals(0xFFFF, bms.balanceMap)
    }

    @Test
    fun `health can be 100 percent`() {
        val bms = SmartBms()
        bms.health = 100

        assertEquals(100, bms.health)
    }

    @Test
    fun `remaining percentage can exceed 100`() {
        // Some BMS systems report > 100% when fully charged
        val bms = SmartBms()
        bms.remPerc = 102

        assertEquals(102, bms.remPerc)
    }

    // ==================== Multiple Instances ====================

    @Test
    fun `multiple BMS instances are independent`() {
        val bms1 = SmartBms()
        val bms2 = SmartBms()

        bms1.serialNumber = "BMS1_SERIAL"
        bms1.voltage = 57.86
        bms1.cells[0] = 4.148

        bms2.serialNumber = "BMS2_SERIAL"
        bms2.voltage = 57.92
        bms2.cells[0] = 4.123

        // Verify they are independent
        assertEquals("BMS1_SERIAL", bms1.serialNumber)
        assertEquals("BMS2_SERIAL", bms2.serialNumber)
        assertEquals(57.86, bms1.voltage, 0.01)
        assertEquals(57.92, bms2.voltage, 0.01)
        assertEquals(4.148, bms1.cells[0], 0.001)
        assertEquals(4.123, bms2.cells[0], 0.001)
    }

    @Test
    fun `reset on one instance does not affect other`() {
        val bms1 = SmartBms()
        val bms2 = SmartBms()

        bms1.serialNumber = "BMS1"
        bms2.serialNumber = "BMS2"

        bms1.reset()

        assertEquals("", bms1.serialNumber)
        assertEquals("BMS2", bms2.serialNumber)
    }

    // ==================== Cell Statistics Calculations ====================

    @Test
    fun `cell difference is max minus min`() {
        val bms = SmartBms()
        bms.minCell = 4.090
        bms.maxCell = 4.177
        bms.cellDiff = bms.maxCell - bms.minCell

        assertEquals(0.087, bms.cellDiff, 0.001)
    }

    @Test
    fun `cell numbers are 1-indexed`() {
        // minCellNum and maxCellNum are typically 1-indexed
        val bms = SmartBms()
        bms.minCellNum = 1   // First cell
        bms.maxCellNum = 14  // 14th cell

        assertTrue(bms.minCellNum >= 1, "Cell numbers should be 1-indexed")
        assertTrue(bms.maxCellNum >= 1, "Cell numbers should be 1-indexed")
    }

    // ==================== Typical Use Cases ====================

    @Test
    fun `typical 14-cell Ninebot Z10 BMS`() {
        val bms = SmartBms()

        // Set up typical Z10 BMS
        bms.cellNum = 14
        bms.voltage = 57.86
        bms.current = 0.17
        bms.temp1 = 27.0
        bms.temp2 = 26.0
        bms.health = 98
        bms.remPerc = 100

        // Cells at 4.1V average = 57.4V total (close to 57.86V)
        for (i in 0 until 14) {
            bms.cells[i] = 4.1 + (i * 0.005)
        }

        assertEquals(14, bms.cellNum)
        assertTrue(bms.voltage > 50.0 && bms.voltage < 70.0, "Z10 voltage should be 50-70V")
    }

    @Test
    fun `typical 20-cell 84V wheel BMS`() {
        val bms = SmartBms()

        // Set up typical 84V wheel (20S configuration)
        bms.cellNum = 20
        bms.voltage = 84.0

        for (i in 0 until 20) {
            bms.cells[i] = 4.2  // Fully charged cells
        }

        assertEquals(20, bms.cellNum)
        assertEquals(84.0, bms.voltage)
    }

    @Test
    fun `typical 24-cell 100V wheel BMS`() {
        val bms = SmartBms()

        // Set up typical 100V wheel (24S configuration)
        bms.cellNum = 24
        bms.voltage = 100.8

        for (i in 0 until 24) {
            bms.cells[i] = 4.2  // Fully charged cells
        }

        assertEquals(24, bms.cellNum)
        assertEquals(100.8, bms.voltage)
    }

    @Test
    fun `typical 30-cell 126V wheel BMS`() {
        val bms = SmartBms()

        // Set up typical 126V wheel (30S configuration)
        bms.cellNum = 30
        bms.voltage = 126.0

        for (i in 0 until 30) {
            bms.cells[i] = 4.2  // Fully charged cells
        }

        assertEquals(30, bms.cellNum)
        assertEquals(126.0, bms.voltage)
    }
}
