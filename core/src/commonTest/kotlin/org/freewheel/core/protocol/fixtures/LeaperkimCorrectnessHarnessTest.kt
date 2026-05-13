package org.freewheel.core.protocol.fixtures

import org.freewheel.core.ble.DiscoveredService
import org.freewheel.core.ble.DiscoveredServices
import org.freewheel.core.ble.WheelTypeDetector
import org.freewheel.core.domain.ProtocolFamily
import org.freewheel.core.domain.WheelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class LeaperkimCorrectnessHarnessTest {

    @Test
    fun `evidence precedence matches correctness plan`() {
        assertEquals(
            LeaperkimEvidenceClass.OFFICIAL_APP,
            LeaperkimEvidenceClass.highestOf(
                setOf(
                    LeaperkimEvidenceClass.HYPOTHESIS,
                    LeaperkimEvidenceClass.OFFICIAL_APP,
                    LeaperkimEvidenceClass.LIVE_CAPTURE,
                )
            )
        )
        assertEquals(
            LeaperkimEvidenceClass.LEGACY_TRACE,
            LeaperkimEvidenceClass.highestOf(
                setOf(
                    LeaperkimEvidenceClass.LEGACY_TRACE,
                    LeaperkimEvidenceClass.LIVE_CAPTURE,
                )
            )
        )
    }

    @Test
    fun `approved fixtures may not be hypothesis backed`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            LeaperkimCorrectnessFixture(
                id = "bad_hypothesis_fixture",
                description = "Invalid because hypothesis fixtures cannot be approved.",
                model = "Bad hypothesis",
                evidenceClasses = setOf(LeaperkimEvidenceClass.HYPOTHESIS),
                status = LeaperkimFixtureStatus.APPROVED,
                sources = listOf("test"),
                protocolExpectation = ProtocolFamily.LEAPERKIM,
                deviceName = "BAD",
                golden = DecoderFixture(
                    name = "bad",
                    frames = listOf("AAAA"),
                    expect = DecoderFixture.Expected(
                        lastResult = DecoderFixture.ResultKind.BUFFERING,
                    ),
                ),
            )
        }

        assertEquals(
            "hypothesis-backed fixtures may not be APPROVED",
            ex.message,
        )
    }

    @Test
    fun `legacy trace fixture prefers veteran lane and matches golden telemetry`() {
        val fixture = LeaperkimCorrectnessFixtures.veteranOldBoardLegacyTrace

        val run = LeaperkimCorrectnessHarness.run(fixture)

        run.assertRoutingExpectation()
        run.assertCandidateExpectations()
        run.outcome(LeaperkimDecoderCandidate.VETERAN).assertMatchesGolden(fixture)
        run.outcome(LeaperkimDecoderCandidate.AUTO_DETECT).assertMatchesGolden(fixture, "AUTO_DETECT")
    }

    @Test
    fun `draft CAN smoke fixture proves comparison lane without changing discovery routing`() {
        val fixture = LeaperkimCorrectnessFixtures.canPasswordAckHypothesis

        val run = LeaperkimCorrectnessHarness.run(fixture)

        assertEquals(LeaperkimFixtureStatus.DRAFT, fixture.status)
        run.assertRoutingExpectation()
        run.assertCandidateExpectations()
        run.outcome(LeaperkimDecoderCandidate.LEAPERKIM_CAN).assertMatchesGolden(fixture)
        run.outcome(LeaperkimDecoderCandidate.AUTO_DETECT).assertMatchesGolden(fixture, "AUTO_DETECT")
    }

    @Test
    fun `parity catalog excludes smoke-only CAN fixture`() {
        assertEquals(
            listOf(LeaperkimCorrectnessFixtures.veteranOldBoardLegacyTrace),
            LeaperkimCorrectnessFixtures.parity,
        )
        assertEquals(
            listOf(LeaperkimCorrectnessFixtures.canPasswordAckHypothesis),
            LeaperkimCorrectnessFixtures.smokeOnly,
        )
        assertEquals(
            listOf(
                LeaperkimCorrectnessFixtures.autodetectDc5a5cRoutesToVeteran,
                LeaperkimCorrectnessFixtures.autodetectAaaaRoutesToCan,
            ),
            LeaperkimCorrectnessFixtures.routingProtection,
        )
    }

    // ==================== Batch 3: Auto-Detect Protection ====================

    @Test
    fun `autodetect dc5a5c routes to veteran even under Leaperkim-family name`() {
        val fixture = LeaperkimCorrectnessFixtures.autodetectDc5a5cRoutesToVeteran

        val run = LeaperkimCorrectnessHarness.run(fixture)

        run.assertRoutingExpectation()
        run.assertCandidateExpectations()
        run.outcome(LeaperkimDecoderCandidate.VETERAN).assertMatchesGolden(fixture)
        run.outcome(LeaperkimDecoderCandidate.AUTO_DETECT).assertMatchesGolden(fixture, "AUTO_DETECT")
        assertEquals(
            WheelType.VETERAN,
            run.outcome(LeaperkimDecoderCandidate.AUTO_DETECT).resolvedWheelType,
            "DC5A5C frame must promote to VETERAN under AutoDetectDecoder, never LEAPERKIM",
        )
    }

    @Test
    fun `autodetect aaaa routes to can even under Leaperkim-family name`() {
        val fixture = LeaperkimCorrectnessFixtures.autodetectAaaaRoutesToCan

        val run = LeaperkimCorrectnessHarness.run(fixture)

        run.assertRoutingExpectation()
        run.assertCandidateExpectations()
        run.outcome(LeaperkimDecoderCandidate.LEAPERKIM_CAN).assertMatchesGolden(fixture)
        run.outcome(LeaperkimDecoderCandidate.AUTO_DETECT).assertMatchesGolden(fixture, "AUTO_DETECT")
        // Discovery-time routing landed on VETERAN (name-only), but the AA AA
        // packet must promote the session to LEAPERKIM. Asserting both halves
        // is the whole point of the fixture.
        assertEquals(
            WheelType.VETERAN,
            run.routingOutcome.wheelType,
            "Leaperkim-family name must resolve to VETERAN at discovery time",
        )
        assertEquals(
            WheelType.LEAPERKIM,
            run.outcome(LeaperkimDecoderCandidate.AUTO_DETECT).resolvedWheelType,
            "AA AA packet evidence must promote the session to LEAPERKIM",
        )
    }

    @Test
    fun `name hint leaperkim family does not force can`() {
        // Locks the no-silent-CAN rule at the discovery layer. Every known
        // Leaperkim brand-family name pattern (per WheelTypeDetector.deriveTypeFromName)
        // must resolve to VETERAN, never LEAPERKIM. CAN may only be reached via
        // packet evidence (see [autodetect_aaaa_routes_to_can]) or an explicit
        // user override.
        val familyNames = listOf(
            "LEAPERKIM-SHERMAN",
            "LPKIM-PATTON",
            "LK-LYNX",
            "LK",
            "VETERAN-SHERMAN-L",
            "SHERMAN-MAX",
            "LYNX-S",
            "PATTON-S",
            "ABRAMS-001",
            "ORYX-PROTO",
            "NOSFET-APEX",
            "NF-AERO",
        )

        val detector = WheelTypeDetector()
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb"),
                ),
            ),
        )

        for (name in familyNames) {
            val derived = WheelTypeDetector.deriveTypeFromName(name)
            assertEquals(WheelType.VETERAN, derived, "deriveTypeFromName('$name') must yield VETERAN")
            assertNotEquals(WheelType.LEAPERKIM, derived, "deriveTypeFromName('$name') must NOT yield LEAPERKIM")

            val detected = detector.detect(services, name)
            val resolved = (detected as? WheelTypeDetector.DetectionResult.Detected)?.wheelType
            assertEquals(WheelType.VETERAN, resolved, "detect('$name', FFE0/FFE1) must yield VETERAN")
            assertNotEquals(WheelType.LEAPERKIM, resolved, "detect('$name', FFE0/FFE1) must NOT yield LEAPERKIM")
        }
    }
}
