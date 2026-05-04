package org.freewheel.core.protocol.fixtures

import org.freewheel.core.protocol.DecoderConfig

/**
 * Golden fixtures for [org.freewheel.core.protocol.GotwayDecoder].
 *
 * Each fixture is a real BLE capture (raw hex frames) paired with the expected
 * accumulated state. When adding a new fixture, prefer copying the frames from
 * an actual capture over hand-building them — the point of this file is that
 * protocol knowledge lives in one place, grep-able by name.
 */
internal object GotwayFixtures {

    /**
     * Captured from a 2020 Begode mainboard sitting stationary, fully charged.
     * Originally from the legacy `GotwayAdapterTest`.
     *
     * Frame 1: live data (voltage, speed=0, distance)
     * Frame 2: protocol frame with `5A5A5A5A` footer + start of next
     * Frame 3: trailing bytes completing frame 2
     *
     * Verified: voltage=65.93 V, speed=0, total distance=24786 m, battery=100 %.
     */
    val board2020Stationary = DecoderFixture(
        name = "Gotway 2020 board — stationary, full battery",
        description = "Legacy capture. Stationary, 100% battery.",
        frames = listOf(
            "55AA19C1000000000000008CF0000001FFF80018",
            "5A5A5A5A55AA000060D248001C20006400010007",
            "000804185A5A5A5A",
        ),
        config = DecoderConfig(
            useMph = false,
            useFahrenheit = false,
            useCustomPercents = false,
            // 2020 board capture is from a 16S wheel — pin scaler so the raw 6593
            // voltage passes through unchanged.
            gotwayVoltage = 0,
        ),
        expect = DecoderFixture.Expected(
            lastResult = DecoderFixture.ResultKind.SUCCESS,
            telemetry = DecoderFixture.TelemetryExpect(
                speed = 0,
                voltage = 6593,
                totalDistance = 24786L,
                batteryLevel = 100,
            ),
        ),
    )
}
