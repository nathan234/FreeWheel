package org.freewheel.core.protocol.fixtures

/**
 * Golden fixtures for [org.freewheel.core.protocol.VeteranDecoder].
 *
 * Captured from real Leaperkim/Veteran hardware via legacy adapter tests.
 * New fixtures should prefer real BLE captures over synthesized frames.
 */
internal object VeteranFixtures {

    /**
     * Veteran "old board" (pre-mVer 3) stationary capture.
     * Originally from the legacy `VeteranAdapterTest`, replicated in
     * `VeteranDecoderTest.decode veteran old board data matches comparison test`.
     *
     * Frame 1 is a full 20-byte Veteran packet, frame 2 is a 16-byte continuation
     * (the Veteran protocol uses ~36 bytes split across BLE notifications).
     *
     * `gotwayNegative=0` (default) means abs() is applied to speed and phaseCurrent —
     * raw phaseCurrent is -340 (×10 scaling), abs gives 340.
     *
     * Verified: voltage=96.86 V, phaseCurrent=340 (abs), total distance=15349 m,
     * battery=90 %.
     */
    val oldBoardStationary = DecoderFixture(
        name = "Veteran old board — stationary, 90% battery",
        description = "Pre-mVer 3 firmware capture. gotwayNegative=0 (default).",
        frames = listOf(
            "DC5A5C2025D600003BF500003BF50000FFDE1399",
            "0DEF0000024602460000000000000000",
        ),
        expect = DecoderFixture.Expected(
            lastResult = DecoderFixture.ResultKind.SUCCESS,
            telemetry = DecoderFixture.TelemetryExpect(
                speed = 0,
                voltage = 9686,
                phaseCurrent = 340,
                wheelDistance = 15349L,
                totalDistance = 15349L,
                batteryLevel = 90,
            ),
        ),
    )
}
