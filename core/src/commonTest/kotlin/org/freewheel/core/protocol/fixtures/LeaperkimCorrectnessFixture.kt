package org.freewheel.core.protocol.fixtures

import org.freewheel.core.ble.DiscoveredService
import org.freewheel.core.ble.DiscoveredServices
import org.freewheel.core.ble.ServiceTopology
import org.freewheel.core.ble.WheelTypeDetector
import org.freewheel.core.domain.ProtocolFamily
import org.freewheel.core.domain.WheelType
import org.freewheel.core.protocol.AutoDetectDecoder
import org.freewheel.core.protocol.DecodeResult
import org.freewheel.core.protocol.DecoderState
import org.freewheel.core.protocol.LeaperkimCanDecoder
import org.freewheel.core.protocol.VeteranDecoder
import org.freewheel.core.protocol.WheelCommand
import org.freewheel.core.protocol.WheelDecoder
import org.freewheel.core.protocol.decoderStateFrom
import org.freewheel.core.protocol.hexToByteArray
import org.freewheel.core.utils.ByteUtils
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Evidence-ranked provenance for Leaperkim-family protocol claims.
 *
 * The ranking matches [docs/leaperkim-correctness-plan.md]: official manufacturer
 * app truth outranks legacy traces, which outrank raw captures, which outrank
 * unsupported hypotheses.
 */
internal enum class LeaperkimEvidenceClass(val precedence: Int) {
    OFFICIAL_APP(0),
    LEGACY_TRACE(1),
    LIVE_CAPTURE(2),
    HYPOTHESIS(3);

    companion object {
        fun highestOf(evidenceClasses: Set<LeaperkimEvidenceClass>): LeaperkimEvidenceClass {
            require(evidenceClasses.isNotEmpty()) { "evidenceClasses must not be empty" }
            return evidenceClasses.minBy { it.precedence }
        }
    }
}

internal enum class LeaperkimFixtureStatus {
    DRAFT,
    APPROVED,
    WAIVED,
}

internal enum class LeaperkimDecoderCandidate {
    VETERAN,
    LEAPERKIM_CAN,
    AUTO_DETECT;

    fun createDecoder(): WheelDecoder = when (this) {
        VETERAN -> VeteranDecoder()
        LEAPERKIM_CAN -> LeaperkimCanDecoder()
        AUTO_DETECT -> AutoDetectDecoder()
    }
}

internal data class CandidateExpectation(
    val accepted: Boolean? = null,
    val lastResult: DecoderFixture.ResultKind? = null,
    val ready: Boolean? = null,
    val resolvedWheelType: WheelType? = null,
    val protocolFamily: ProtocolFamily? = null,
)

internal enum class LeaperkimRoutingResultKind {
    DETECTED,
    UNKNOWN,
    AMBIGUOUS,
}

internal data class LeaperkimRoutingExpectation(
    val result: LeaperkimRoutingResultKind,
    val wheelType: WheelType? = null,
    val protocolFamily: ProtocolFamily? = wheelType?.let(ProtocolFamily::fromWheelType),
    val confidence: WheelTypeDetector.Confidence? = null,
) {
    init {
        require(result == LeaperkimRoutingResultKind.DETECTED || wheelType == null) {
            "only DETECTED routing expectations may specify a wheelType"
        }
        require(result == LeaperkimRoutingResultKind.DETECTED || protocolFamily == null) {
            "only DETECTED routing expectations may specify a protocolFamily"
        }
        require(result == LeaperkimRoutingResultKind.DETECTED || confidence == null) {
            "only DETECTED routing expectations may specify confidence"
        }
    }
}

/**
 * Cross-decoder fixture for the Leaperkim correctness program.
 *
 * A fixture wraps the existing single-decoder [golden] fixture with the extra
 * provenance and candidate expectations needed to compare the legacy Veteran
 * lane against the experimental Leaperkim CAN lane.
 *
 * [protocolExpectation] describes the protocol family the raw frame sequence is
 * expected to belong to once packet evidence is available.
 *
 * [routingExpectation] describes the protocol family our discovery-time
 * heuristics should choose from BLE name/topology alone before packet evidence
 * is seen. Those two expectations can intentionally diverge while CAN remains
 * evidence-only.
 */
internal data class LeaperkimCorrectnessFixture(
    val fixtureVersion: Int = 1,
    val id: String,
    val description: String,
    val model: String,
    val evidenceClasses: Set<LeaperkimEvidenceClass>,
    val status: LeaperkimFixtureStatus,
    val sources: List<String>,
    val protocolExpectation: ProtocolFamily,
    val deviceName: String,
    val advertisedServices: List<ServiceTopology> = emptyList(),
    val golden: DecoderFixture,
    val routingExpectation: LeaperkimRoutingExpectation? = null,
    val candidateExpectations: Map<LeaperkimDecoderCandidate, CandidateExpectation> = emptyMap(),
    val waiverReason: String? = null,
) {
    init {
        require(fixtureVersion >= 1) { "fixtureVersion must be >= 1" }
        require(id.isNotBlank()) { "id must not be blank" }
        require(description.isNotBlank()) { "description must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
        require(evidenceClasses.isNotEmpty()) { "evidenceClasses must not be empty" }
        require(sources.isNotEmpty()) { "sources must not be empty" }
        require(golden.frames.isNotEmpty()) { "golden.frames must not be empty" }
        require(status != LeaperkimFixtureStatus.WAIVED || !waiverReason.isNullOrBlank()) {
            "waived fixtures must include a waiverReason"
        }
        require(status == LeaperkimFixtureStatus.WAIVED || waiverReason == null) {
            "only waived fixtures may include a waiverReason"
        }
        require(status != LeaperkimFixtureStatus.APPROVED || highestEvidence() != LeaperkimEvidenceClass.HYPOTHESIS) {
            "hypothesis-backed fixtures may not be APPROVED"
        }
        require(routingExpectation == null || deviceName.isNotBlank() || advertisedServices.isNotEmpty()) {
            "routingExpectation requires deviceName or advertisedServices"
        }
    }

    fun highestEvidence(): LeaperkimEvidenceClass = LeaperkimEvidenceClass.highestOf(evidenceClasses)
}

internal data class LeaperkimRoutingOutcome(
    val result: LeaperkimRoutingResultKind,
    val wheelType: WheelType? = null,
    val protocolFamily: ProtocolFamily? = null,
    val confidence: WheelTypeDetector.Confidence? = null,
    val reason: String? = null,
)

internal data class LeaperkimCandidateOutcome(
    val candidate: LeaperkimDecoderCandidate,
    val finalState: DecoderState,
    val lastDecodeResult: DecodeResult?,
    val acceptedFrameCount: Int,
    val ready: Boolean,
    val emittedCommands: List<WheelCommand>,
    val detectedType: WheelType? = null,
    val lastUnhandledReason: String? = null,
) {
    val accepted: Boolean get() = acceptedFrameCount > 0

    val lastResultKind: DecoderFixture.ResultKind
        get() = when (lastDecodeResult) {
            is DecodeResult.Success -> DecoderFixture.ResultKind.SUCCESS
            is DecodeResult.Buffering -> DecoderFixture.ResultKind.BUFFERING
            is DecodeResult.Unhandled -> DecoderFixture.ResultKind.UNHANDLED
            null -> error("lastDecodeResult should never be null for non-empty fixtures")
        }

    val resolvedWheelType: WheelType?
        get() = when {
            detectedType != null -> detectedType
            finalState.identity.wheelType != WheelType.Unknown -> finalState.identity.wheelType
            else -> null
        }

    val protocolFamily: ProtocolFamily?
        get() = resolvedWheelType?.let(ProtocolFamily::fromWheelType)

    val commandSignatures: List<String>
        get() = emittedCommands.map(WheelCommand::toSignature)
}

internal data class LeaperkimFixtureRun(
    val fixture: LeaperkimCorrectnessFixture,
    val routingOutcome: LeaperkimRoutingOutcome,
    val outcomes: Map<LeaperkimDecoderCandidate, LeaperkimCandidateOutcome>,
) {
    fun outcome(candidate: LeaperkimDecoderCandidate): LeaperkimCandidateOutcome =
        requireNotNull(outcomes[candidate]) { "missing outcome for $candidate on fixture '${fixture.id}'" }
}

internal object LeaperkimCorrectnessHarness {
    fun run(
        fixture: LeaperkimCorrectnessFixture,
        candidates: Set<LeaperkimDecoderCandidate> = LeaperkimDecoderCandidate.entries.toSet(),
    ): LeaperkimFixtureRun {
        val routingOutcome = detectRouting(fixture)
        val outcomes = candidates.associateWith { candidate ->
            val decoder = candidate.createDecoder()
            var state = DecoderState()
            var lastResult: DecodeResult? = null
            var acceptedFrameCount = 0
            var lastUnhandledReason: String? = null
            val commands = mutableListOf<WheelCommand>()

            for (hex in fixture.golden.frames) {
                val bytes = hex.hexToByteArray()
                lastResult = decoder.decode(bytes, state, fixture.golden.config)
                when (lastResult) {
                    is DecodeResult.Success -> {
                        acceptedFrameCount += 1
                        state = lastResult.data.decoderStateFrom(state)
                        commands += lastResult.data.commands
                    }
                    is DecodeResult.Unhandled -> {
                        lastUnhandledReason = lastResult.reason.errorClassName
                    }
                    DecodeResult.Buffering -> Unit
                }
            }

            LeaperkimCandidateOutcome(
                candidate = candidate,
                finalState = state,
                lastDecodeResult = lastResult,
                acceptedFrameCount = acceptedFrameCount,
                ready = decoder.isReady(),
                emittedCommands = commands,
                detectedType = (decoder as? AutoDetectDecoder)?.getDetectedType(),
                lastUnhandledReason = lastUnhandledReason,
            )
        }

        return LeaperkimFixtureRun(
            fixture = fixture,
            routingOutcome = routingOutcome,
            outcomes = outcomes,
        )
    }

    private fun detectRouting(fixture: LeaperkimCorrectnessFixture): LeaperkimRoutingOutcome {
        val detector = WheelTypeDetector()
        val services = DiscoveredServices(
            services = fixture.advertisedServices.map { topology ->
                DiscoveredService(
                    uuid = topology.uuid,
                    characteristics = topology.characteristics.sorted(),
                )
            }
        )

        return when (val result = detector.detect(services, fixture.deviceName)) {
            is WheelTypeDetector.DetectionResult.Detected -> LeaperkimRoutingOutcome(
                result = LeaperkimRoutingResultKind.DETECTED,
                wheelType = result.wheelType,
                protocolFamily = ProtocolFamily.fromWheelType(result.wheelType),
                confidence = result.confidence,
            )
            is WheelTypeDetector.DetectionResult.Unknown -> LeaperkimRoutingOutcome(
                result = LeaperkimRoutingResultKind.UNKNOWN,
                reason = result.reason,
            )
            is WheelTypeDetector.DetectionResult.Ambiguous -> LeaperkimRoutingOutcome(
                result = LeaperkimRoutingResultKind.AMBIGUOUS,
                reason = result.reason,
            )
        }
    }
}

internal fun LeaperkimFixtureRun.assertCandidateExpectations() {
    for ((candidate, expected) in fixture.candidateExpectations) {
        val actual = outcome(candidate)
        val label = "fixture '${fixture.id}' candidate $candidate"

        expected.accepted?.let {
            assertEquals(it, actual.accepted, "$label: accepted")
        }
        expected.lastResult?.let {
            assertEquals(it, actual.lastResultKind, "$label: lastResult")
        }
        expected.ready?.let {
            assertEquals(it, actual.ready, "$label: ready")
        }
        expected.resolvedWheelType?.let {
            assertEquals(it, actual.resolvedWheelType, "$label: resolvedWheelType")
        }
        expected.protocolFamily?.let {
            assertEquals(it, actual.protocolFamily, "$label: protocolFamily")
        }
    }
}

internal fun LeaperkimFixtureRun.assertRoutingExpectation() {
    val expected = fixture.routingExpectation ?: return
    val label = "fixture '${fixture.id}' routing"

    assertEquals(expected.result, routingOutcome.result, "$label: result")
    expected.wheelType?.let {
        assertEquals(it, routingOutcome.wheelType, "$label: wheelType")
    }
    expected.protocolFamily?.let {
        assertEquals(it, routingOutcome.protocolFamily, "$label: protocolFamily")
    }
    expected.confidence?.let {
        assertEquals(it, routingOutcome.confidence, "$label: confidence")
    }
}

internal fun LeaperkimCandidateOutcome.assertMatchesGolden(
    fixture: LeaperkimCorrectnessFixture,
    labelSuffix: String = candidate.name,
) {
    assertNotNull(lastDecodeResult, "fixture '${fixture.id}' candidate $candidate produced no decode result")
    assertDecoderFixtureExpectation(
        label = "fixture '${fixture.id}' candidate $labelSuffix",
        expected = fixture.golden.expect,
        state = finalState,
        lastResult = lastDecodeResult,
    )
}

private fun WheelCommand.toSignature(): String = when (this) {
    is WheelCommand.SendBytes -> "send:${ByteUtils.bytesToHex(data)}"
    is WheelCommand.SendDelayed -> "delay:${delayMs}:${ByteUtils.bytesToHex(data)}"
    else -> toString()
}
