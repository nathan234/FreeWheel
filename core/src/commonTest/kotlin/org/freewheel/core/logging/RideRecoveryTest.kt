package org.freewheel.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RideRecoveryTest {

    private val header = "date,time,speed,voltage,phase_current,current,power,torque,pwm," +
        "battery_level,distance,totaldistance,system_temp,temp2,tilt,roll,mode,alert"

    @Test
    fun `empty content returns null`() {
        assertNull(RideRecovery.deriveMetadata("", "x.csv"))
    }

    @Test
    fun `header only returns null`() {
        assertNull(RideRecovery.deriveMetadata(header, "x.csv"))
    }

    @Test
    fun `missing required columns returns null`() {
        assertNull(RideRecovery.deriveMetadata("foo,bar\n1,2", "x.csv"))
    }

    @Test
    fun `derives metadata from a happy-path ride`() {
        val rows = listOf(
            "2026-05-03,14:30:00.000,10.0,84.0,5.0,5.0,500.0,10,40,90,0,1000000,30,28,1,0,Sport,",
            "2026-05-03,14:30:01.000,15.0,84.0,6.0,6.0,600.0,10,50,90,5,1000005,30,28,1,0,Sport,",
            "2026-05-03,14:30:02.000,20.0,84.0,7.0,7.0,700.0,10,60,90,10,1000010,30,28,1,0,Sport,",
        )
        val csv = (listOf(header) + rows).joinToString("\n")

        val meta = RideRecovery.deriveMetadata(csv, "ride.csv")
        assertNotNull(meta)
        assertEquals("ride.csv", meta.fileName)
        assertEquals(3, meta.sampleCount)
        assertEquals(2L, meta.durationSeconds)
        assertEquals(20.0, meta.maxSpeedKmh)
        assertEquals(15.0, meta.avgSpeedKmh)
        assertEquals(7.0, meta.maxCurrentA)
        assertEquals(700.0, meta.maxPowerW)
        assertEquals(60.0, meta.maxPwmPercent)
        assertEquals(10L, meta.distanceMeters)
    }

    @Test
    fun `skips malformed rows but recovers from valid ones`() {
        val rows = listOf(
            "2026-05-03,14:30:00.000,10.0,,,,,,40,,0,0,,,,,,",
            "garbage line",
            "2026-05-03,14:30:01.000,15.0,,,,,,50,,5,0,,,,,,",
        )
        val csv = (listOf(header) + rows).joinToString("\n")

        val meta = RideRecovery.deriveMetadata(csv, "ride.csv")
        assertNotNull(meta)
        assertEquals(2, meta.sampleCount)
    }

    @Test
    fun `negative current is taken as absolute for maxCurrent`() {
        val rows = listOf(
            "2026-05-03,14:30:00.000,10.0,84.0,5.0,-3.0,500.0,0,40,90,0,0,30,28,0,0,Sport,",
            "2026-05-03,14:30:01.000,12.0,84.0,5.0,-9.0,500.0,0,40,90,0,0,30,28,0,0,Sport,",
        )
        val csv = (listOf(header) + rows).joinToString("\n")
        val meta = RideRecovery.deriveMetadata(csv, "ride.csv")
        assertNotNull(meta)
        assertEquals(9.0, meta.maxCurrentA)
    }
}
