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

    /**
     * Batch 3 fixture: locks the rule that a `DC 5A 5C` framed packet always
     * promotes to [WheelType.VETERAN] under [AutoDetectDecoder], even when the
     * device name is from the Leaperkim brand family.
     *
     * Pairs with [autodetectAaaaRoutesToCan] (the inverse promotion guard) and
     * with [LeaperkimCorrectnessHarnessTest.name_hint_leaperkim_family_does_not_force_can]
     * (the no-frames name-only rule).
     */
    val autodetectDc5a5cRoutesToVeteran = LeaperkimCorrectnessFixture(
        id = "autodetect_dc5a5c_routes_to_veteran",
        description = "Leaperkim-family device name plus DC5A5C packet must route to VETERAN, never CAN.",
        model = "Leaperkim Sherman (legacy capture, name-overridden)",
        evidenceClasses = setOf(LeaperkimEvidenceClass.LEGACY_TRACE),
        status = LeaperkimFixtureStatus.APPROVED,
        sources = listOf(
            "FreeWheel VeteranFixtures.oldBoardStationary",
            "docs/leaperkim-correctness-plan.md Batch 3",
        ),
        protocolExpectation = ProtocolFamily.VETERAN,
        deviceName = "LK-SHERMAN",
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

    /**
     * Batch 3 fixture: locks the rule that an `AA AA` framed packet still
     * promotes the session to [WheelType.LEAPERKIM] under [AutoDetectDecoder],
     * even when the device name belongs to the Leaperkim brand family (which
     * by itself routes to VETERAN). Packet evidence overrides name evidence.
     *
     * Status remains DRAFT — the frame is synthesized via
     * [LeaperkimCanDecoder.buildCanFrame], not captured from real hardware.
     */
    val autodetectAaaaRoutesToCan = LeaperkimCorrectnessFixture(
        id = "autodetect_aaaa_routes_to_can",
        description = "AA AA packet must promote to LEAPERKIM under AutoDetectDecoder regardless of brand-family name.",
        model = "Leaperkim CAN (synthetic AA AA promotion)",
        evidenceClasses = setOf(LeaperkimEvidenceClass.HYPOTHESIS),
        status = LeaperkimFixtureStatus.DRAFT,
        sources = listOf(
            "FreeWheel LeaperkimCanDecoder.buildCanFrame(CAN_INIT_PASSWORD)",
            "docs/leaperkim-correctness-plan.md Batch 3",
        ),
        protocolExpectation = ProtocolFamily.LEAPERKIM,
        deviceName = "LK-SHERMAN",
        advertisedServices = legacyFfeGatt,
        golden = DecoderFixture(
            name = "Leaperkim CAN AA-AA promotion",
            description = "Synthetic password-ACK frame proving auto-detect promotes to CAN on AA AA.",
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

    val routingProtection = listOf(
        autodetectDc5a5cRoutesToVeteran,
        autodetectAaaaRoutesToCan,
    )

    val smokeOnly = listOf(
        canPasswordAckHypothesis,
    )
}
