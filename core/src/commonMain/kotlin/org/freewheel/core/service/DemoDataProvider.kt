package org.freewheel.core.service

import org.freewheel.core.domain.BmsSnapshot
import org.freewheel.core.domain.SmartBms
import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Cross-platform demo data provider that generates realistic wheel telemetry.
 * Useful for testing on iOS Simulator (no BLE) or demonstrating the app.
 *
 * Produces [WheelState] updates at 10Hz with a 60-second ride cycle:
 * accelerate → cruise → decelerate → stop.
 */
class DemoDataProvider {

    private val _wheelState = MutableStateFlow(WheelState())
    val wheelState: StateFlow<WheelState> = _wheelState.asStateFlow()

    private var job: Job? = null
    private var tick: Int = 0

    // Mutable simulation state
    private var speed: Double = 0.0
    private var battery: Int = 85
    private var totalDistanceM: Double = 1523500.0 // meters (1523.5 km)
    private var tripDistanceM: Double = 0.0

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Start the simulation loop in the given coroutine scope.
     */
    fun start(scope: CoroutineScope) {
        if (isRunning) return
        tick = 0
        speed = 0.0
        battery = 85
        tripDistanceM = 0.0

        job = scope.launch {
            while (isActive) {
                generateData()
                delay(100) // 10Hz
            }
        }
    }

    /**
     * Stop the simulation loop.
     */
    fun stop() {
        job?.cancel()
        job = null
        _wheelState.value = WheelState()
    }

    private fun generateBmsSnapshot(packVoltage: Double, packCurrent: Double): BmsSnapshot {
        val cellCount = 16
        val nominalCell = packVoltage / cellCount
        val cells = Array(SmartBms.MAX_CELLS) { i ->
            if (i < cellCount) {
                // Small variation per cell with slight sine wobble
                nominalCell + sin((tick + i * 37).toDouble() * 0.02) * 0.015
            } else 0.0
        }

        var minV = cells[0]; var maxV = cells[0]; var sum = 0.0
        var minI = 0; var maxI = 0
        for (i in 0 until cellCount) {
            val v = cells[i]
            sum += v
            if (v < minV) { minV = v; minI = i }
            if (v > maxV) { maxV = v; maxI = i }
        }

        return BmsSnapshot(
            serialNumber = "DEMO-BMS-001",
            versionNumber = "1.0.0",
            factoryCap = 9600,
            actualCap = 9600,
            fullCycles = 42,
            chargeCount = 100,
            mfgDateStr = "01.01.2024",
            status = 1,
            remCap = (9600 * battery / 100),
            remPerc = battery,
            current = packCurrent,
            voltage = packVoltage,
            temp1 = 25.0 + sin(tick.toDouble() * 0.01) * 3.0,
            temp2 = 24.0 + sin(tick.toDouble() * 0.012) * 2.0,
            health = 98,
            cellNum = cellCount,
            cells = cells,
            minCell = minV,
            maxCell = maxV,
            cellDiff = maxV - minV,
            avgCell = sum / cellCount,
            minCellNum = minI + 1,
            maxCellNum = maxI + 1
        )
    }

    private fun generateData() {
        tick++

        // 60-second ride cycle (600 ticks at 10Hz)
        val phase = (tick % 600).toDouble() / 600.0

        // Speed: accelerate, cruise, decelerate, stop
        if (phase < 0.2) {
            speed = min(speed + 0.5, 25.0)
        } else if (phase < 0.7) {
            speed = 22.0 + sin(tick.toDouble() * 0.1) * 3.0
        } else if (phase < 0.9) {
            speed = max(speed - 0.3, 5.0)
        } else {
            speed = max(speed - 1.0, 0.0)
        }

        // Current based on speed + sine variation
        val current = if (speed > 0) {
            speed * 0.8 + sin(tick.toDouble() * 0.05) * 2.0
        } else {
            0.0
        }

        // Temperature rises with speed
        val temperature = 25.0 + (speed / 25.0) * 10.0 + sin(tick.toDouble() * 0.01) * 2.0

        // Voltage sag under load
        val voltage = 84.0 - (current * 0.05)

        // Power
        val power = voltage * current

        // Battery drains ~1% per 10 seconds
        if (tick % 100 == 0 && battery > 10) {
            battery--
        }

        // Trip distance accumulates: speed (km/h) → m per 0.1s tick
        tripDistanceM += speed / 3.6 * 0.1

        // Generate BMS data (update every second = every 10 ticks)
        val bmsSnapshot = if (tick % 10 == 0 || tick == 1) {
            generateBmsSnapshot(voltage, current)
        } else {
            _wheelState.value.bms1
        }

        // Convert to WheelState internal units (1/100)
        _wheelState.value = WheelState(
            speed = (speed * 100).toInt(),
            voltage = (voltage * 100).toInt(),
            current = (current * 100).toInt(),
            power = (power * 100).toInt(),
            temperature = (temperature * 100).toInt(),
            batteryLevel = battery,
            totalDistance = ((totalDistanceM + tripDistanceM)).toLong(),
            wheelDistance = tripDistanceM.toLong(),
            calculatedPwm = speed / 50.0,
            wheelType = WheelType.Unknown,
            name = "Demo",
            model = "Demo Wheel",
            bms1 = bmsSnapshot
        )
    }
}
