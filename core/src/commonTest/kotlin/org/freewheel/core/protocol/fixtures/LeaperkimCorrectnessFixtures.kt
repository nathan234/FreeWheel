package org.freewheel.core.protocol.fixtures

import org.freewheel.core.ble.ServiceTopology
import org.freewheel.core.domain.ProtocolFamily
import org.freewheel.core.domain.WheelType
import org.freewheel.core.protocol.LeaperkimCanDecoder
import org.freewheel.core.utils.ByteUtils

/**
 * Seed fixtures for the Leaperkim correctness harness.
 *
 * Keep evidence-backed parity fixtures separate from smoke-only fixtures:
 * - [parity] may grow into production-routing evidence over time
 * - [smokeOnly] is diagnostic coverage that must not steer routing policy
 */
internal object LeaperkimCorrectnessFixtures {

    private const val SUFFIX = "-0000-1000-8000-00805f9b34fb"

    private fun s(short: String): String = "0000${short.lowercase()}$SUFFIX"

    private fun service(uuid: String, vararg chars: String): ServiceTopology =
        ServiceTopology(uuid = uuid, characteristics = chars.toSet())

    private val legacyFfeGatt = listOf(
        service(s("ffe0"), s("ffe1")),
    )

    private fun canPasswordAckHex(): String {
        val decoder = LeaperkimCanDecoder()
        val frame = decoder.buildCanFrame(LeaperkimCanDecoder.CAN_INIT_PASSWORD, ByteArray(8))
        return ByteUtils.bytesToHex(frame)
    }

    val veteranOldBoardLegacyTrace = LeaperkimCorrectnessFixture(
        id = "veteran_old_board_stationary_legacy_trace",
        description = "Real legacy Veteran trace for the old-board Leaperkim streaming path.",
        model = "Veteran legacy old board",
        evidenceClasses = setOf(LeaperkimEvidenceClass.LEGACY_TRACE),
        status = LeaperkimFixtureStatus.APPROVED,
        sources = listOf(
            "Wheellog.Android VeteranAdapterTest old-board stationary capture",
            "FreeWheel VeteranFixtures.oldBoardStationary",
        ),
        protocolExpectation = ProtocolFamily.VETERAN,
        deviceName = "VETERAN-OLD-BOARD",
        advertisedServices = legacyFfeGatt,
        golden = VeteranFixtures.oldBoardStationary,
        routingExpectation = LeaperkimRoutingExpectation(
            result = LeaperkimRoutingResultKind.DETECTED,
            wheelType = WheelType.VETERAN,
        ),
        candidateExpectations = mapOf(
            LeaperkimDecoderCandidate.VETERAN to CandidateExpectation(
                accepted = true,
                lastResult = DecoderFixture.ResultKind.SUCCESS,
                protocolFamily = ProtocolFamily.VETERAN,
                resolvedWheelType = WheelType.VETERAN,
            ),
            LeaperkimDecoderCandidate.LEAPERKIM_CAN to CandidateExpectation(
                accepted = false,
            ),
            LeaperkimDecoderCandidate.AUTO_DETECT to CandidateExpectation(
                accepted = true,
                lastResult = DecoderFixture.ResultKind.SUCCESS,
                protocolFamily = ProtocolFamily.VETERAN,
                resolvedWheelType = WheelType.VETERAN,
            ),
        ),
    )

    val canPasswordAckHypothesis = LeaperkimCorrectnessFixture(
        id = "leaperkim_can_password_ack_hypothesis",
        description = "CAN lane handshake smoke fixture; useful for harness validation but not production truth.",
        model = "Leaperkim CAN hypothesis",
        evidenceClasses = setOf(LeaperkimEvidenceClass.HYPOTHESIS),
        status = LeaperkimFixtureStatus.DRAFT,
        sources = listOf(
            "FreeWheel LeaperkimCanDecoder internal frame builder",
        ),
        protocolExpectation = ProtocolFamily.LEAPERKIM,
        deviceName = "LPKIM-HYPOTHESIS",
        advertisedServices = legacyFfeGatt,
        golden = DecoderFixture(
            name = "Leaperkim CAN password ACK",
            description = "Synthetic init ACK proving the CAN lane can accept AA AA framed traffic.",
            frames = listOf(canPasswordAckHex()),
            expect = DecoderFixture.Expected(
                lastResult = DecoderFixture.ResultKind.SUCCESS,
            ),
        ),
        routingExpectation = LeaperkimRoutingExpectation(
            result = LeaperkimRoutingResultKind.DETECTED,
            wheelType = WheelType.VETERAN,
        ),
        candidateExpectations = mapOf(
            LeaperkimDecoderCandidate.VETERAN to CandidateExpectation(
                accepted = false,
            ),
            LeaperkimDecoderCandidate.LEAPERKIM_CAN to CandidateExpectation(
                accepted = true,
                lastResult = DecoderFixture.ResultKind.SUCCESS,
                ready = false,
                protocolFamily = ProtocolFamily.LEAPERKIM,
                resolvedWheelType = WheelType.LEAPERKIM,
            ),
            LeaperkimDecoderCandidate.AUTO_DETECT to CandidateExpectation(
                accepted = true,
                lastResult = DecoderFixture.ResultKind.SUCCESS,
                ready = false,
                protocolFamily = ProtocolFamily.LEAPERKIM,
                resolvedWheelType = WheelType.LEAPERKIM,
            ),
        ),
    )

    val parity = listOf(
        veteranOldBoardLegacyTrace,
    )

    val smokeOnly = listOf(
        canPasswordAckHypothesis,
    )
}
