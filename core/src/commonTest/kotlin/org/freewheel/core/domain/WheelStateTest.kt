package org.freewheel.core.domain

import org.freewheel.core.utils.ByteUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for WheelState immutable data class.
 * Verifies default values, computed properties, unit conversions,
 * and data class behavior (copy, equality).
 */
class WheelStateTest {

    // ==================== Default Values ====================

    @Test
    fun `new instance has default values`() {
        val state = WheelState()

        assertEquals(0, state.speed)
        assertEquals(0, state.voltage)
        assertEquals(0, state.current)
        assertEquals(0, state.phaseCurrent)
        assertEquals(0, state.power)
        assertEquals(0, state.temperature)
        assertEquals(0, state.temperature2)
        assertEquals(0, state.batteryLevel)
    }

    @Test
    fun `default constructor produces zero-valued state`() {
        val state = WheelState()
        assertEquals(0, state.speed)
        assertEquals(0, state.voltage)
    }

    @Test
    fun `distance defaults to zero`() {
        val state = WheelState()

        assertEquals(0L, state.totalDistance)
        assertEquals(0L, state.wheelDistance)
    }

    @Test
    fun `orientation defaults to zero`() {
        val state = WheelState()

        assertEquals(0.0, state.angle)
        assertEquals(0.0, state.roll)
    }

    @Test
    fun `motor performance defaults to zero`() {
        val state = WheelState()

        assertEquals(0.0, state.torque)
        assertEquals(0.0, state.motorPower)
        assertEquals(0, state.cpuTemp)
        assertEquals(0, state.imuTemp)
        assertEquals(0, state.cpuLoad)
    }

    @Test
    fun `limits default to zero`() {
        val state = WheelState()

        assertEquals(0.0, state.speedLimit)
        assertEquals(0.0, state.currentLimit)
    }

    @Test
    fun `status flags default to off`() {
        val state = WheelState()

        assertEquals(0, state.fanStatus)
        assertEquals(0, state.chargingStatus)
        assertFalse(state.wheelAlarm)
    }

    @Test
    fun `wheel identification defaults to empty`() {
        val state = WheelState()

        assertEquals(WheelType.Unknown, state.wheelType)
        assertEquals("", state.name)
        assertEquals("", state.model)
        assertEquals("", state.modeStr)
        assertEquals("", state.version)
        assertEquals("", state.serialNumber)
        assertEquals("", state.btName)
    }

    @Test
    fun `BMS defaults to null`() {
        val state = WheelState()

        assertNull(state.bms1)
        assertNull(state.bms2)
    }

    @Test
    fun `error tracking defaults to empty`() {
        val state = WheelState()

        assertEquals("", state.error)
        assertEquals("", state.alert)
    }

    @Test
    fun `timestamp defaults to zero`() {
        val state = WheelState()

        assertEquals(0L, state.timestamp)
    }

    // ==================== Speed Computed Properties ====================

    @Test
    fun `speedKmh converts correctly`() {
        val state = WheelState(speed = 2500)  // 25.00 km/h

        assertEquals(25.0, state.speedKmh, 0.001)
    }

    @Test
    fun `speedKmh handles zero`() {
        val state = WheelState(speed = 0)

        assertEquals(0.0, state.speedKmh)
    }

    @Test
    fun `speedKmh handles fractional values`() {
        val state = WheelState(speed = 2555)  // 25.55 km/h

        assertEquals(25.55, state.speedKmh, 0.001)
    }

    @Test
    fun `speedMph converts km to miles`() {
        val state = WheelState(speed = 10000)  // 100 km/h

        // 100 km/h * 0.62137 = 62.137 mph
        assertEquals(62.137, state.speedMph, 0.01)
    }

    @Test
    fun `speedMph at common speeds`() {
        // 30 km/h is roughly 18.64 mph
        val state30 = WheelState(speed = 3000)
        assertEquals(18.64, state30.speedMph, 0.01)

        // 50 km/h is roughly 31.07 mph
        val state50 = WheelState(speed = 5000)
        assertEquals(31.07, state50.speedMph, 0.01)
    }

    // ==================== Voltage Computed Property ====================

    @Test
    fun `voltageV converts correctly`() {
        val state = WheelState(voltage = 8400)  // 84.00 V

        assertEquals(84.0, state.voltageV, 0.001)
    }

    @Test
    fun `voltageV handles typical wheel voltages`() {
        // 67.2V (fully charged 16S)
        val state67 = WheelState(voltage = 6720)
        assertEquals(67.2, state67.voltageV, 0.001)

        // 84.0V (fully charged 20S)
        val state84 = WheelState(voltage = 8400)
        assertEquals(84.0, state84.voltageV, 0.001)

        // 100.8V (fully charged 24S)
        val state100 = WheelState(voltage = 10080)
        assertEquals(100.8, state100.voltageV, 0.001)

        // 126.0V (fully charged 30S)
        val state126 = WheelState(voltage = 12600)
        assertEquals(126.0, state126.voltageV, 0.001)
    }

    // ==================== Current Computed Properties ====================

    @Test
    fun `currentA converts correctly`() {
        val state = WheelState(current = 1500)  // 15.00 A

        assertEquals(15.0, state.currentA, 0.001)
    }

    @Test
    fun `currentA handles negative for regen`() {
        val state = WheelState(current = -500)  // -5.00 A (regen braking)

        assertEquals(-5.0, state.currentA, 0.001)
    }

    @Test
    fun `phaseCurrentA converts correctly`() {
        val state = WheelState(phaseCurrent = 3500)  // 35.00 A

        assertEquals(35.0, state.phaseCurrentA, 0.001)
    }

    // ==================== Power Computed Property ====================

    @Test
    fun `powerW converts correctly`() {
        val state = WheelState(power = 150000)  // 1500.00 W

        assertEquals(1500.0, state.powerW, 0.001)
    }

    @Test
    fun `powerW handles typical values`() {
        // 500W cruising
        val state500 = WheelState(power = 50000)
        assertEquals(500.0, state500.powerW, 0.001)

        // 2000W acceleration
        val state2000 = WheelState(power = 200000)
        assertEquals(2000.0, state2000.powerW, 0.001)
    }

    @Test
    fun `powerW handles negative for regen`() {
        val state = WheelState(power = -50000)  // -500 W regen

        assertEquals(-500.0, state.powerW, 0.001)
    }

    // ==================== Temperature Computed Properties ====================

    @Test
    fun `temperatureC converts correctly`() {
        val state = WheelState(temperature = 3500)  // 35.00 °C

        assertEquals(35, state.temperatureC)
    }

    @Test
    fun `temperatureC truncates decimal`() {
        val state = WheelState(temperature = 3599)  // 35.99 °C

        assertEquals(35, state.temperatureC)  // Truncated, not rounded
    }

    @Test
    fun `temperatureF converts correctly`() {
        val state = WheelState(temperature = 0)  // 0 °C = 32 °F

        assertEquals(32.0, state.temperatureF, 0.001)
    }

    @Test
    fun `temperatureF at common temperatures`() {
        // 20°C = 68°F
        val state20 = WheelState(temperature = 2000)
        assertEquals(68.0, state20.temperatureF, 0.1)

        // 35°C = 95°F
        val state35 = WheelState(temperature = 3500)
        assertEquals(95.0, state35.temperatureF, 0.1)

        // 50°C = 122°F
        val state50 = WheelState(temperature = 5000)
        assertEquals(122.0, state50.temperatureF, 0.1)
    }

    @Test
    fun `temperature2C converts correctly`() {
        val state = WheelState(temperature2 = 4000)  // 40.00 °C

        assertEquals(40, state.temperature2C)
    }

    // ==================== Distance Computed Properties ====================

    @Test
    fun `totalDistanceKm converts meters to km`() {
        val state = WheelState(totalDistance = 15000)  // 15000 meters = 15 km

        assertEquals(15.0, state.totalDistanceKm, 0.001)
    }

    @Test
    fun `totalDistanceKm handles large distances`() {
        val state = WheelState(totalDistance = 12345678)  // 12345.678 km

        assertEquals(12345.678, state.totalDistanceKm, 0.001)
    }

    @Test
    fun `wheelDistanceKm converts meters to km`() {
        val state = WheelState(wheelDistance = 5500)  // 5.5 km

        assertEquals(5.5, state.wheelDistanceKm, 0.001)
    }

    // ==================== Distance — inMiles is informational only ====================
    // Decoders normalize imperial values to metric before storing in WheelState.
    // The computed properties always treat raw values as metric.

    @Test
    fun `totalDistanceKm always divides by 1000 regardless of inMiles`() {
        val stateKm = WheelState(totalDistance = 1163400, inMiles = false)
        val stateMi = WheelState(totalDistance = 1163400, inMiles = true)

        // Both should give the same result — decoder already normalized
        assertEquals(1163.4, stateKm.totalDistanceKm, 0.001)
        assertEquals(1163.4, stateMi.totalDistanceKm, 0.001)
    }

    @Test
    fun `wheelDistanceKm always divides by 1000 regardless of inMiles`() {
        val stateKm = WheelState(wheelDistance = 5500, inMiles = false)
        val stateMi = WheelState(wheelDistance = 5500, inMiles = true)

        assertEquals(5.5, stateKm.wheelDistanceKm, 0.001)
        assertEquals(5.5, stateMi.wheelDistanceKm, 0.001)
    }

    // ==================== PWM and Output Computed Properties ====================

    @Test
    fun `pwmPercent converts to percentage`() {
        val state = WheelState(calculatedPwm = 0.75)  // 75%

        assertEquals(75.0, state.pwmPercent, 0.001)
    }

    @Test
    fun `pwmPercent at limits`() {
        val state0 = WheelState(calculatedPwm = 0.0)
        assertEquals(0.0, state0.pwmPercent)

        val state100 = WheelState(calculatedPwm = 1.0)
        assertEquals(100.0, state100.pwmPercent)
    }

    @Test
    fun `outputPercent converts correctly`() {
        val state = WheelState(output = 8000)  // 80%

        assertEquals(80, state.outputPercent)
    }

    // ==================== KM_TO_MILES Constant ====================

    @Test
    fun `KM_TO_MILES constant is accurate`() {
        // 1 km = 0.621371 miles (approximately)
        assertEquals(0.62137119223733, ByteUtils.KM_TO_MILES_MULTIPLIER, 0.00000001)
    }

    // ==================== Display-Layer Unit Conversion ====================
    // These tests verify the conversion formulas used by both Android (MathsUtil)
    // and iOS (DashboardView) produce identical results for typical EUC values.

    @Test
    fun `speed display - metric vs imperial at typical EUC speeds`() {
        // Android: formatSpeed(kmh, useMph) = "%.1f mph" with MathsUtil.kmToMiles(kmh)
        // iOS: displaySpeed = speedKmh * kmToMiles
        val speeds = listOf(
            WheelState(speed = 0),      // 0 km/h = 0 mph
            WheelState(speed = 1500),   // 15 km/h = 9.3 mph (walking speed EUC)
            WheelState(speed = 2500),   // 25 km/h = 15.5 mph (city cruising)
            WheelState(speed = 4000),   // 40 km/h = 24.9 mph (fast cruising)
            WheelState(speed = 5000),   // 50 km/h = 31.1 mph (high speed)
            WheelState(speed = 8000),   // 80 km/h = 49.7 mph (Begode Master top speed)
        )

        val expectedMph = listOf(0.0, 9.32, 15.53, 24.85, 31.07, 49.71)

        for (i in speeds.indices) {
            // Verify speedKmh * KM_TO_MILES == speedMph (same formula both platforms use)
            assertEquals(
                speeds[i].speedKmh * ByteUtils.KM_TO_MILES_MULTIPLIER,
                speeds[i].speedMph,
                0.001,
                "speedMph should equal speedKmh * KM_TO_MILES for speed=${speeds[i].speed}"
            )
            assertEquals(expectedMph[i], speeds[i].speedMph, 0.01, "mph at ${speeds[i].speedKmh} km/h")
        }
    }

    @Test
    fun `distance display - metric vs imperial at typical EUC distances`() {
        // Android: formatDistance(km, useMph) = "%.2f mi" with MathsUtil.kmToMiles(km)
        // iOS: formatDistance(km) = "%.2f mi" with km * kmToMiles
        val distances = listOf(
            WheelState(wheelDistance = 500),      // 0.5 km trip
            WheelState(wheelDistance = 5000),     // 5 km trip
            WheelState(wheelDistance = 25000),    // 25 km trip (good range)
            WheelState(wheelDistance = 80000),    // 80 km trip (max range)
        )

        val expectedMiles = listOf(0.31, 3.11, 15.53, 49.71)

        for (i in distances.indices) {
            val miles = distances[i].wheelDistanceKm * ByteUtils.KM_TO_MILES_MULTIPLIER
            assertEquals(expectedMiles[i], miles, 0.01, "miles at ${distances[i].wheelDistanceKm} km")
        }
    }

    @Test
    fun `total distance display - metric vs imperial`() {
        val distances = listOf(
            WheelState(totalDistance = 100000),     // 100 km
            WheelState(totalDistance = 1500000),    // 1500 km
            WheelState(totalDistance = 10000000),   // 10000 km (well-used wheel)
        )

        val expectedMiles = listOf(62.1, 932.1, 6213.7)

        for (i in distances.indices) {
            val miles = distances[i].totalDistanceKm * ByteUtils.KM_TO_MILES_MULTIPLIER
            assertEquals(expectedMiles[i], miles, 0.1, "miles at ${distances[i].totalDistanceKm} km")
        }
    }

    @Test
    fun `temperature display - Celsius vs Fahrenheit at EUC operating range`() {
        // Android: MathsUtil.celsiusToFahrenheit(temp) = temp * 9.0 / 5.0 + 32
        // iOS: Double(tempC) * 9.0 / 5.0 + 32
        val temps = listOf(
            WheelState(temperature = -1000),  // -10°C = 14°F (winter riding)
            WheelState(temperature = 0),      //   0°C = 32°F (freezing)
            WheelState(temperature = 2000),   //  20°C = 68°F (room temp)
            WheelState(temperature = 2500),   //  25°C = 77°F (comfortable)
            WheelState(temperature = 3500),   //  35°C = 95°F (warm motor)
            WheelState(temperature = 4000),   //  40°C = 104°F (color threshold: green→orange)
            WheelState(temperature = 5000),   //  50°C = 122°F (hot motor)
            WheelState(temperature = 5500),   //  55°C = 131°F (color threshold: orange→red)
            WheelState(temperature = 7000),   //  70°C = 158°F (overheating)
        )

        val expectedF = listOf(14.0, 32.0, 68.0, 77.0, 95.0, 104.0, 122.0, 131.0, 158.0)

        for (i in temps.indices) {
            assertEquals(
                expectedF[i], temps[i].temperatureF, 0.1,
                "°F at ${temps[i].temperatureC}°C"
            )
            // Verify formula: temperatureC * 9/5 + 32 == temperatureF
            val manualF = temps[i].temperatureC * 9.0 / 5.0 + 32
            assertEquals(temps[i].temperatureF, manualF, 0.001,
                "temperatureF should match manual formula")
        }
    }

    @Test
    fun `conversion constant matches Android MathsUtil kmToMilesMultiplier`() {
        // Android: MathsUtil.kmToMilesMultiplier = 0.62137119223733
        // KMP:     ByteUtils.KM_TO_MILES_MULTIPLIER         = 0.62137119223733
        // iOS:     DashboardView.kmToMiles         = 0.62137119223733
        // All three must be identical to avoid display discrepancies
        assertEquals(0.62137119223733, ByteUtils.KM_TO_MILES_MULTIPLIER)
    }

    @Test
    fun `imperial conversion roundtrip preserves reasonable precision`() {
        // Converting km→mi→km should not accumulate significant error
        val originalKm = 42.195  // Marathon distance
        val miles = originalKm * ByteUtils.KM_TO_MILES_MULTIPLIER
        val backToKm = miles / ByteUtils.KM_TO_MILES_MULTIPLIER

        assertEquals(originalKm, backToKm, 0.0001)
        assertEquals(26.22, miles, 0.01)  // Marathon is ~26.22 miles
    }

    // ==================== Data Class Copy ====================

    @Test
    fun `copy preserves unchanged values`() {
        val original = WheelState(
            speed = 2500,
            voltage = 8400,
            temperature = 3500
        )

        val copied = original.copy(speed = 3000)

        assertEquals(3000, copied.speed)
        assertEquals(8400, copied.voltage)  // Preserved
        assertEquals(3500, copied.temperature)  // Preserved
    }

    @Test
    fun `copy can change multiple values`() {
        val original = WheelState()

        val copied = original.copy(
            speed = 2500,
            voltage = 8400,
            batteryLevel = 75
        )

        assertEquals(2500, copied.speed)
        assertEquals(8400, copied.voltage)
        assertEquals(75, copied.batteryLevel)
    }

    @Test
    fun `copy creates new instance`() {
        val original = WheelState(speed = 2500)
        val copied = original.copy()

        // Different instances
        assertTrue(original !== copied)

        // But equal values
        assertEquals(original, copied)
    }

    // ==================== Data Class Equality ====================

    @Test
    fun `equals compares all fields`() {
        val state1 = WheelState(speed = 2500, voltage = 8400)
        val state2 = WheelState(speed = 2500, voltage = 8400)

        assertEquals(state1, state2)
    }

    @Test
    fun `not equals when any field differs`() {
        val state1 = WheelState(speed = 2500)
        val state2 = WheelState(speed = 2501)

        assertNotEquals(state1, state2)
    }

    @Test
    fun `hashCode is consistent with equals`() {
        val state1 = WheelState(speed = 2500, voltage = 8400)
        val state2 = WheelState(speed = 2500, voltage = 8400)

        assertEquals(state1.hashCode(), state2.hashCode())
    }

    // ==================== Real World Scenarios ====================

    @Test
    fun `typical cruising state`() {
        val state = WheelState(
            speed = 2500,           // 25 km/h
            voltage = 7920,         // 79.2V (80% of 100V wheel)
            current = 800,          // 8A
            power = 63360,          // ~634W
            temperature = 3500,     // 35°C
            batteryLevel = 80,
            totalDistance = 1500000, // 1500 km lifetime
            wheelDistance = 15000    // 15 km this trip
        )

        assertEquals(25.0, state.speedKmh, 0.01)
        assertEquals(79.2, state.voltageV, 0.01)
        assertEquals(8.0, state.currentA, 0.01)
        assertEquals(633.6, state.powerW, 0.1)
        assertEquals(35, state.temperatureC)
        assertEquals(1500.0, state.totalDistanceKm, 0.01)
        assertEquals(15.0, state.wheelDistanceKm, 0.01)
    }

    @Test
    fun `high performance state`() {
        val state = WheelState(
            speed = 5000,           // 50 km/h
            voltage = 9500,         // 95V
            current = 4000,         // 40A
            phaseCurrent = 8000,    // 80A phase
            power = 380000,         // 3800W
            temperature = 5500,     // 55°C (hot motor)
            temperature2 = 4500,    // 45°C (board temp)
            batteryLevel = 60,
            calculatedPwm = 0.85    // 85% PWM
        )

        assertEquals(50.0, state.speedKmh, 0.01)
        assertEquals(31.07, state.speedMph, 0.1)
        assertEquals(95.0, state.voltageV, 0.01)
        assertEquals(40.0, state.currentA, 0.01)
        assertEquals(80.0, state.phaseCurrentA, 0.01)
        assertEquals(3800.0, state.powerW, 0.01)
        assertEquals(55, state.temperatureC)
        assertEquals(45, state.temperature2C)
        assertEquals(85.0, state.pwmPercent, 0.01)
    }

    @Test
    fun `regenerative braking state`() {
        val state = WheelState(
            speed = 3000,           // 30 km/h (decelerating)
            voltage = 8600,         // 86V (voltage rises during regen)
            current = -1500,        // -15A (negative = regen)
            power = -129000,        // -1290W regen
            batteryLevel = 85       // Battery charging
        )

        assertEquals(30.0, state.speedKmh, 0.01)
        assertEquals(-15.0, state.currentA, 0.01)
        assertEquals(-1290.0, state.powerW, 0.01)
    }

    @Test
    fun `fully charged stationary state`() {
        val state = WheelState(
            speed = 0,
            voltage = 10080,        // 100.8V (24S fully charged)
            current = 0,
            power = 0,
            temperature = 2500,     // 25°C
            batteryLevel = 100,
            chargingStatus = 0      // Not currently charging
        )

        assertEquals(0.0, state.speedKmh)
        assertEquals(100.8, state.voltageV, 0.01)
        assertEquals(0.0, state.currentA)
        assertEquals(100, state.batteryLevel)
    }

    @Test
    fun `low battery warning state`() {
        val state = WheelState(
            speed = 1500,           // 15 km/h (speed limited)
            voltage = 7200,         // 72V (low)
            batteryLevel = 10,
            wheelAlarm = true,      // Alarm active
            alert = "Low Battery"
        )

        assertEquals(15.0, state.speedKmh, 0.01)
        assertEquals(72.0, state.voltageV, 0.01)
        assertEquals(10, state.batteryLevel)
        assertTrue(state.wheelAlarm)
        assertEquals("Low Battery", state.alert)
    }

    // ==================== Wheel Identification ====================

    @Test
    fun `Gotway wheel state`() {
        val state = WheelState(
            wheelType = WheelType.GOTWAY,
            model = "Begode Master",
            version = "2.04",
            serialNumber = "GW12345678"
        )

        assertEquals(WheelType.GOTWAY, state.wheelType)
        assertEquals("Begode Master", state.model)
        assertEquals("2.04", state.version)
        assertEquals("GW12345678", state.serialNumber)
    }

    @Test
    fun `Kingsong wheel state`() {
        val state = WheelState(
            wheelType = WheelType.KINGSONG,
            model = "KS-S18",
            version = "2.05",
            modeStr = "0"
        )

        assertEquals(WheelType.KINGSONG, state.wheelType)
        assertEquals("KS-S18", state.model)
        assertEquals("2.05", state.version)
        assertEquals("0", state.modeStr)
    }

    @Test
    fun `Veteran wheel state`() {
        val state = WheelState(
            wheelType = WheelType.VETERAN,
            model = "Leaperkim Sherman",
            version = "001.0.58"
        )

        assertEquals(WheelType.VETERAN, state.wheelType)
        assertEquals("Leaperkim Sherman", state.model)
    }

    @Test
    fun `InMotion V2 wheel state`() {
        val state = WheelState(
            wheelType = WheelType.INMOTION_V2,
            model = "InMotion V11",
            version = "Main:1.1.64 Drv:3.4.8 BLE:1.1.13",
            serialNumber = "1480CA122207002B"
        )

        assertEquals(WheelType.INMOTION_V2, state.wheelType)
        assertEquals("InMotion V11", state.model)
    }

    @Test
    fun `Ninebot Z wheel state`() {
        val state = WheelState(
            wheelType = WheelType.NINEBOT_Z,
            model = "Ninebot Z",
            serialNumber = "N3OTC2020T0001",
            version = "0.7.7"
        )

        assertEquals(WheelType.NINEBOT_Z, state.wheelType)
        assertEquals("Ninebot Z", state.model)
        assertEquals("N3OTC2020T0001", state.serialNumber)
    }

    // ==================== BMS Data ====================

    @Test
    fun `state with BMS1 data`() {
        val bms = SmartBms()
        bms.voltage = 57.86
        bms.current = 0.17
        bms.cellNum = 14

        val state = WheelState(bms1 = bms.toSnapshot())

        assertEquals(57.86, state.bms1!!.voltage, 0.01)
        assertEquals(0.17, state.bms1!!.current, 0.01)
        assertEquals(14, state.bms1!!.cellNum)
        assertNull(state.bms2)
    }

    @Test
    fun `state with dual BMS data`() {
        val bms1 = SmartBms()
        bms1.serialNumber = "BMS1"

        val bms2 = SmartBms()
        bms2.serialNumber = "BMS2"

        val state = WheelState(bms1 = bms1.toSnapshot(), bms2 = bms2.toSnapshot())

        assertEquals("BMS1", state.bms1?.serialNumber)
        assertEquals("BMS2", state.bms2?.serialNumber)
    }

    // ==================== Error and Alert ====================

    @Test
    fun `state with error message`() {
        val state = WheelState(
            error = "Err:25 VGM - Voltage < 10V"
        )

        assertEquals("Err:25 VGM - Voltage < 10V", state.error)
    }

    @Test
    fun `state with alert message`() {
        val state = WheelState(
            alert = "Temperature Warning"
        )

        assertEquals("Temperature Warning", state.alert)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `negative speed is valid`() {
        // Some wheels report negative speed for reverse
        val state = WheelState(speed = -500)

        assertEquals(-5.0, state.speedKmh, 0.01)
    }

    @Test
    fun `very high speed values`() {
        val state = WheelState(speed = 10000)  // 100 km/h

        assertEquals(100.0, state.speedKmh, 0.01)
        assertEquals(62.14, state.speedMph, 0.1)
    }

    @Test
    fun `very high voltage values`() {
        val state = WheelState(voltage = 15120)  // 151.2V (36S)

        assertEquals(151.2, state.voltageV, 0.01)
    }

    @Test
    fun `maximum battery level`() {
        val state = WheelState(batteryLevel = 100)

        assertEquals(100, state.batteryLevel)
    }

    @Test
    fun `battery level over 100`() {
        // Some wheels report > 100% when freshly charged
        val state = WheelState(batteryLevel = 102)

        assertEquals(102, state.batteryLevel)
    }

    @Test
    fun `zero temperature in Fahrenheit`() {
        val state = WheelState(temperature = 0)

        assertEquals(0, state.temperatureC)
        assertEquals(32.0, state.temperatureF, 0.01)
    }

    @Test
    fun `negative temperature`() {
        val state = WheelState(temperature = -1000)  // -10°C

        assertEquals(-10, state.temperatureC)
        assertEquals(14.0, state.temperatureF, 0.01)  // -10°C = 14°F
    }

    @Test
    fun `timestamp can store epoch millis`() {
        val timestamp = 1707350400000L  // Feb 8, 2024

        val state = WheelState(timestamp = timestamp)

        assertEquals(1707350400000L, state.timestamp)
    }

    // ==================== Computed Properties Don't Affect Equality ====================

    @Test
    fun `computed properties are derived from stored values`() {
        val state = WheelState(speed = 2500)

        // Computed property is derived from speed
        assertEquals(25.0, state.speedKmh)

        // Changing stored value changes computed
        val modified = state.copy(speed = 3000)
        assertEquals(30.0, modified.speedKmh)
    }

    // ==================== All WheelType Values ====================

    @Test
    fun `all wheel types can be assigned`() {
        val types = listOf(
            WheelType.Unknown,
            WheelType.KINGSONG,
            WheelType.GOTWAY,
            WheelType.NINEBOT,
            WheelType.NINEBOT_Z,
            WheelType.INMOTION,
            WheelType.INMOTION_V2,
            WheelType.VETERAN
        )

        for (type in types) {
            val state = WheelState(wheelType = type)
            assertEquals(type, state.wheelType)
        }
    }
}
