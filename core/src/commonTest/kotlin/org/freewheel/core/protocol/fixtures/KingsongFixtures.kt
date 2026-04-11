package org.freewheel.core.protocol.fixtures

import org.freewheel.core.domain.WheelType

/**
 * Golden fixtures for [org.freewheel.core.protocol.KingsongDecoder].
 *
 * Most Kingsong unit tests use `KingsongFrameBuilders` (parameterized builders)
 * rather than raw hex because KS frames have a tight 20-byte layout. Fixtures
 * here are reserved for real capture sequences — prefer builder-based tests
 * for single-field parameter sweeps.
 */
internal object KingsongFixtures {

    /**
     * KS-S18 model identification frame + first live data frame.
     * Originally from the legacy `KingsongAdapterTest`, replicated in
     * `GotwayDecoderTest.decode kingsong live data` (cross-decoder sanity check).
     *
     * Frame 1 (0xBB): model name "KS-S18" in ASCII.
     * Frame 2 (0xA9): live data — speed, voltage, current, temperature, total distance.
     *
     * Verified: model="KS-S18", speed=515 (5.15 km/h), voltage=6505 (65.05 V),
     * current=215 (2.15 A), totalDistance=13983 m, temperature=1300 (13 °C).
     */
    val ks18ModelAndLive = DecoderFixture(
        name = "Kingsong KS-S18 — model + first live frame",
        description = "Sequence: model name frame (0xBB) followed by live data frame (0xA9).",
        frames = listOf(
            "aa554b532d5331382d30323035000000bb1484fd",
            "aa556919030200009f36d700140500e0a9145a5a",
        ),
        expect = DecoderFixture.Expected(
            lastResult = DecoderFixture.ResultKind.SUCCESS,
            telemetry = DecoderFixture.TelemetryExpect(
                speed = 515,
                voltage = 6505,
                current = 215,
                totalDistance = 13983L,
            ),
            identity = DecoderFixture.IdentityExpect(
                wheelType = WheelType.KINGSONG,
                model = "KS-S18",
            ),
        ),
    )
}
