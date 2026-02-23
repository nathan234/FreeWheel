package com.cooper.wheellog.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals

class RideMetadataTest {

    @Test
    fun `default values are zero`() {
        val meta = RideMetadata(
            fileName = "test.csv",
            startTimeMillis = 0,
            endTimeMillis = 0,
            durationSeconds = 0,
            distanceMeters = 0,
            maxSpeedKmh = 0.0,
            avgSpeedKmh = 0.0,
            sampleCount = 0
        )
        assertEquals(0.0, meta.maxCurrentA, 0.01)
        assertEquals(0.0, meta.maxPowerW, 0.01)
        assertEquals(0.0, meta.maxPwmPercent, 0.01)
        assertEquals(0.0, meta.consumptionWh, 0.01)
        assertEquals(0.0, meta.consumptionWhPerKm, 0.01)
    }

    @Test
    fun `data class equality works`() {
        val meta1 = RideMetadata(
            fileName = "ride.csv",
            startTimeMillis = 1000,
            endTimeMillis = 5000,
            durationSeconds = 4,
            distanceMeters = 100,
            maxSpeedKmh = 25.0,
            avgSpeedKmh = 20.0,
            sampleCount = 4,
            maxCurrentA = 15.0,
            maxPowerW = 1500.0,
            consumptionWh = 50.0
        )
        val meta2 = meta1.copy()
        assertEquals(meta1, meta2)
    }

    @Test
    fun `copy preserves all fields`() {
        val meta = RideMetadata(
            fileName = "ride.csv",
            startTimeMillis = 1000,
            endTimeMillis = 61000,
            durationSeconds = 60,
            distanceMeters = 500,
            maxSpeedKmh = 35.0,
            avgSpeedKmh = 30.0,
            sampleCount = 60,
            maxCurrentA = 20.0,
            maxPowerW = 2000.0,
            maxPwmPercent = 85.0,
            consumptionWh = 100.0,
            consumptionWhPerKm = 200.0
        )
        val copied = meta.copy(fileName = "new.csv")
        assertEquals("new.csv", copied.fileName)
        assertEquals(meta.maxSpeedKmh, copied.maxSpeedKmh, 0.01)
        assertEquals(meta.consumptionWh, copied.consumptionWh, 0.01)
    }
}
