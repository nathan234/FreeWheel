package org.freewheel.core.protocol.fixtures

import org.freewheel.core.domain.ProtocolFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    }
}
