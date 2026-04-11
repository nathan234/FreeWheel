package org.freewheel.core.telemetry

import org.freewheel.core.domain.TelemetryState
import kotlin.test.Test
import kotlin.test.assertEquals

class TelemetrySampleTest {

    @Test
    fun `fromTelemetry maps speed correctly`() {
        val telemetry = TelemetryState(speed = 2500) // 25.00 km/h
        val sample = TelemetrySample.fromTelemetry(telemetry, timestampMs = 1000L)
        assertEquals(25.0, sample.speedKmh)
    }

    @Test
    fun `fromTelemetry maps voltage correctly`() {
        val telemetry = TelemetryState(voltage = 8400) // 84.00 V
        val sample = TelemetrySample.fromTelemetry(telemetry, timestampMs = 1000L)
        assertEquals(84.0, sample.voltageV)
    }

    @Test
    fun `fromTelemetry maps current correctly`() {
        val telemetry = TelemetryState(current = 1500) // 15.00 A
        val sample = TelemetrySample.fromTelemetry(telemetry, timestampMs = 1000L)
        assertEquals(15.0, sample.currentA)
    }

    @Test
    fun `fromTelemetry maps power correctly`() {
        val telemetry = TelemetryState(power = 150000) // 1500.00 W
        val sample = TelemetrySample.fromTelemetry(telemetry, timestampMs = 1000L)
        assertEquals(1500.0, sample.powerW)
    }

    @Test
    fun `fromTelemetry maps temperature correctly`() {
        val telemetry = TelemetryState(temperature = 3500) // 35°C
        val sample = TelemetrySample.fromTelemetry(telemetry, timestampMs = 1000L)
        assertEquals(35.0, sample.temperatureC)
    }

    @Test
    fun `fromTelemetry maps battery correctly`() {
        val telemetry = TelemetryState(batteryLevel = 80)
        val sample = TelemetrySample.fromTelemetry(telemetry, timestampMs = 1000L)
        assertEquals(80.0, sample.batteryPercent)
    }

    @Test
    fun `fromTelemetry maps pwm correctly`() {
        val telemetry = TelemetryState(calculatedPwm = 0.45) // 45%
        val sample = TelemetrySample.fromTelemetry(telemetry, timestampMs = 1000L)
        assertEquals(45.0, sample.pwmPercent)
    }

    @Test
    fun `fromTelemetry passes timestamp and gpsSpeed`() {
        val telemetry = TelemetryState()
        val sample = TelemetrySample.fromTelemetry(telemetry, timestampMs = 42L, gpsSpeedKmh = 12.5)
        assertEquals(42L, sample.timestampMs)
        assertEquals(12.5, sample.gpsSpeedKmh)
    }

    @Test
    fun `fromTelemetry defaults gpsSpeed to zero`() {
        val telemetry = TelemetryState()
        val sample = TelemetrySample.fromTelemetry(telemetry, timestampMs = 1000L)
        assertEquals(0.0, sample.gpsSpeedKmh)
    }

    @Test
    fun `fromTelemetry maps all fields from realistic state`() {
        val telemetry = TelemetryState(
            speed = 3000,         // 30.0 km/h
            voltage = 6720,       // 67.20 V
            current = 850,        // 8.50 A
            power = 57120,        // 571.20 W
            temperature = 4200,   // 42°C
            batteryLevel = 65,
            calculatedPwm = 0.32  // 32%
        )
        val sample = TelemetrySample.fromTelemetry(telemetry, timestampMs = 99999L, gpsSpeedKmh = 28.5)

        assertEquals(99999L, sample.timestampMs)
        assertEquals(30.0, sample.speedKmh)
        assertEquals(67.2, sample.voltageV)
        assertEquals(8.5, sample.currentA)
        assertEquals(571.2, sample.powerW)
        assertEquals(42.0, sample.temperatureC)
        assertEquals(65.0, sample.batteryPercent)
        assertEquals(32.0, sample.pwmPercent)
        assertEquals(28.5, sample.gpsSpeedKmh)
    }
}
