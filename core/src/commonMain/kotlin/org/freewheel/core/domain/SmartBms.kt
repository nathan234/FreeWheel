package org.freewheel.core.domain

/**
 * Battery Management System (BMS) data container.
 * Holds all BMS-related telemetry data including cell voltages,
 * temperatures, and battery health information.
 */
class SmartBms {
    var serialNumber: String = ""
    var versionNumber: String = ""
    var factoryCap: Int = 0
    var actualCap: Int = 0
    var fullCycles: Int = 0
    var chargeCount: Int = 0
    var mfgDateStr: String = ""
    var status: Int = 0
    var remCap: Int = 0
    var remPerc: Int = 0
    var current: Double = 0.0
    var voltage: Double = 0.0
    var semiVoltage1: Double = 0.0
    var semiVoltage2: Double = 0.0
    var temp1: Double = 0.0
    var temp2: Double = 0.0
    var temp3: Double = 0.0
    var temp4: Double = 0.0
    var temp5: Double = 0.0
    var temp6: Double = 0.0
    var tempMos: Double = 0.0
    var tempMosEnv: Double = 0.0
    var temp1Env: Double = 0.0
    var temp2Env: Double = 0.0
    var humidity1Env: Double = 0.0
    var humidity2Env: Double = 0.0
    var balanceMap: Int = 0
    var health: Int = 0
    var minCell: Double = 0.0
    var maxCell: Double = 0.0
    var cellDiff: Double = 0.0
    var avgCell: Double = 0.0
    var minCellNum: Int = 0
    var maxCellNum: Int = 0
    var cellNum: Int = 0
    var cells: Array<Double> = Array(MAX_CELLS) { 0.0 }

    init {
        reset()
    }

    fun reset() {
        serialNumber = ""
        versionNumber = ""
        factoryCap = 0
        actualCap = 0
        fullCycles = 0
        chargeCount = 0
        mfgDateStr = ""
        status = 0
        remCap = 0
        remPerc = 0
        current = 0.0
        voltage = 0.0
        semiVoltage1 = 0.0
        semiVoltage2 = 0.0
        temp1 = 0.0
        temp2 = 0.0
        temp3 = 0.0
        temp4 = 0.0
        temp5 = 0.0
        temp6 = 0.0
        tempMos = 0.0
        tempMosEnv = 0.0
        temp1Env = 0.0
        temp2Env = 0.0
        humidity1Env = 0.0
        humidity2Env = 0.0
        balanceMap = 0
        health = 0
        minCell = 0.0
        maxCell = 0.0
        cellDiff = 0.0
        avgCell = 0.0
        minCellNum = 0
        maxCellNum = 0
        cellNum = 0
        cells = Array(MAX_CELLS) { 0.0 }
    }

    fun toSnapshot(): BmsSnapshot = BmsSnapshot(
        serialNumber = serialNumber,
        versionNumber = versionNumber,
        factoryCap = factoryCap,
        actualCap = actualCap,
        fullCycles = fullCycles,
        chargeCount = chargeCount,
        mfgDateStr = mfgDateStr,
        status = status,
        remCap = remCap,
        remPerc = remPerc,
        current = current,
        voltage = voltage,
        semiVoltage1 = semiVoltage1,
        semiVoltage2 = semiVoltage2,
        temp1 = temp1,
        temp2 = temp2,
        temp3 = temp3,
        temp4 = temp4,
        temp5 = temp5,
        temp6 = temp6,
        tempMos = tempMos,
        tempMosEnv = tempMosEnv,
        temp1Env = temp1Env,
        temp2Env = temp2Env,
        humidity1Env = humidity1Env,
        humidity2Env = humidity2Env,
        balanceMap = balanceMap,
        health = health,
        minCell = minCell,
        maxCell = maxCell,
        cellDiff = cellDiff,
        avgCell = avgCell,
        minCellNum = minCellNum,
        maxCellNum = maxCellNum,
        cellNum = cellNum,
        cells = cells.copyOf()
    )

    companion object {
        const val MAX_CELLS = 56
    }
}

/**
 * Immutable snapshot of BMS data for use in [WheelState] StateFlow.
 * Created via [SmartBms.toSnapshot].
 */
data class BmsSnapshot(
    val serialNumber: String = "",
    val versionNumber: String = "",
    val factoryCap: Int = 0,
    val actualCap: Int = 0,
    val fullCycles: Int = 0,
    val chargeCount: Int = 0,
    val mfgDateStr: String = "",
    val status: Int = 0,
    val remCap: Int = 0,
    val remPerc: Int = 0,
    val current: Double = 0.0,
    val voltage: Double = 0.0,
    val semiVoltage1: Double = 0.0,
    val semiVoltage2: Double = 0.0,
    val temp1: Double = 0.0,
    val temp2: Double = 0.0,
    val temp3: Double = 0.0,
    val temp4: Double = 0.0,
    val temp5: Double = 0.0,
    val temp6: Double = 0.0,
    val tempMos: Double = 0.0,
    val tempMosEnv: Double = 0.0,
    val temp1Env: Double = 0.0,
    val temp2Env: Double = 0.0,
    val humidity1Env: Double = 0.0,
    val humidity2Env: Double = 0.0,
    val balanceMap: Int = 0,
    val health: Int = 0,
    val minCell: Double = 0.0,
    val maxCell: Double = 0.0,
    val cellDiff: Double = 0.0,
    val avgCell: Double = 0.0,
    val minCellNum: Int = 0,
    val maxCellNum: Int = 0,
    val cellNum: Int = 0,
    val cells: Array<Double> = Array(SmartBms.MAX_CELLS) { 0.0 }
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BmsSnapshot) return false
        return serialNumber == other.serialNumber &&
            versionNumber == other.versionNumber &&
            factoryCap == other.factoryCap &&
            actualCap == other.actualCap &&
            fullCycles == other.fullCycles &&
            chargeCount == other.chargeCount &&
            mfgDateStr == other.mfgDateStr &&
            status == other.status &&
            remCap == other.remCap &&
            remPerc == other.remPerc &&
            current == other.current &&
            voltage == other.voltage &&
            semiVoltage1 == other.semiVoltage1 &&
            semiVoltage2 == other.semiVoltage2 &&
            temp1 == other.temp1 &&
            temp2 == other.temp2 &&
            temp3 == other.temp3 &&
            temp4 == other.temp4 &&
            temp5 == other.temp5 &&
            temp6 == other.temp6 &&
            tempMos == other.tempMos &&
            tempMosEnv == other.tempMosEnv &&
            temp1Env == other.temp1Env &&
            temp2Env == other.temp2Env &&
            humidity1Env == other.humidity1Env &&
            humidity2Env == other.humidity2Env &&
            balanceMap == other.balanceMap &&
            health == other.health &&
            minCell == other.minCell &&
            maxCell == other.maxCell &&
            cellDiff == other.cellDiff &&
            avgCell == other.avgCell &&
            minCellNum == other.minCellNum &&
            maxCellNum == other.maxCellNum &&
            cellNum == other.cellNum &&
            cells.contentEquals(other.cells)
    }

    override fun hashCode(): Int {
        var result = serialNumber.hashCode()
        result = 31 * result + versionNumber.hashCode()
        result = 31 * result + factoryCap
        result = 31 * result + actualCap
        result = 31 * result + fullCycles
        result = 31 * result + chargeCount
        result = 31 * result + mfgDateStr.hashCode()
        result = 31 * result + status
        result = 31 * result + remCap
        result = 31 * result + remPerc
        result = 31 * result + current.hashCode()
        result = 31 * result + voltage.hashCode()
        result = 31 * result + semiVoltage1.hashCode()
        result = 31 * result + semiVoltage2.hashCode()
        result = 31 * result + temp1.hashCode()
        result = 31 * result + temp2.hashCode()
        result = 31 * result + temp3.hashCode()
        result = 31 * result + temp4.hashCode()
        result = 31 * result + temp5.hashCode()
        result = 31 * result + temp6.hashCode()
        result = 31 * result + tempMos.hashCode()
        result = 31 * result + tempMosEnv.hashCode()
        result = 31 * result + temp1Env.hashCode()
        result = 31 * result + temp2Env.hashCode()
        result = 31 * result + humidity1Env.hashCode()
        result = 31 * result + humidity2Env.hashCode()
        result = 31 * result + balanceMap
        result = 31 * result + health
        result = 31 * result + minCell.hashCode()
        result = 31 * result + maxCell.hashCode()
        result = 31 * result + cellDiff.hashCode()
        result = 31 * result + avgCell.hashCode()
        result = 31 * result + minCellNum
        result = 31 * result + maxCellNum
        result = 31 * result + cellNum
        result = 31 * result + cells.contentHashCode()
        return result
    }
}
