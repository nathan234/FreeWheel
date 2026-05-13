package org.freewheel.core.protocol.fixtures

import org.freewheel.core.ble.DiscoveredService
import org.freewheel.core.ble.DiscoveredServices
import org.freewheel.core.ble.WheelTypeDetector
import org.freewheel.core.domain.ProtocolFamily
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelType
import org.freewheel.core.protocol.DecodeResult
import org.freewheel.core.protocol.DecoderState
import org.freewheel.core.protocol.DefaultWheelDecoderFactory
import org.freewheel.core.protocol.VeteranDecoder
import org.freewheel.core.protocol.hexToByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        // Parity = the legacy-trace baseline plus every Batch 1 fixture.
        assertEquals(
            listOf(LeaperkimCorrectnessFixtures.veteranOldBoardLegacyTrace) +
                LeaperkimBatch1Fixtures.all,
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

    // ==================== Batch 1: Official-app-backed parity ====================

    @Test
    fun `batch 1 per-model baseline main frames decode under Veteran lane`() {
        for (fixture in LeaperkimBatch1Fixtures.perModelMainFrames) {
            val run = LeaperkimCorrectnessHarness.run(fixture)
            run.assertRoutingExpectation()
            run.assertCandidateExpectations()
            run.outcome(LeaperkimDecoderCandidate.VETERAN).assertMatchesGolden(fixture)
            run.outcome(LeaperkimDecoderCandidate.AUTO_DETECT).assertMatchesGolden(fixture, "AUTO_DETECT")
        }
    }

    @Test
    fun `batch 1 subtype 8 control settings round-trips`() {
        val fixture = LeaperkimBatch1Fixtures.controlSettingsBlock
        val run = LeaperkimCorrectnessHarness.run(fixture)
        run.outcome(LeaperkimDecoderCandidate.VETERAN).assertMatchesGolden(fixture)
        run.outcome(LeaperkimDecoderCandidate.AUTO_DETECT).assertMatchesGolden(fixture, "AUTO_DETECT")

        // The harness's structural Expected can't pin individual settings
        // fields without locking the entire WheelSettings struct. Replay the
        // same hex frame through a direct VeteranDecoder call so a byte-offset
        // regression in parseControlSettings() shows up here.
        val decoder = VeteranDecoder()
        val frame = fixture.golden.frames.single().hexToByteArray()
        val result = decoder.decode(frame, DecoderState(), fixture.golden.config)
        assertTrue(result is DecodeResult.Success, "subtype 8 fixture must decode")
        val settings = assertNotNull((result as DecodeResult.Success).data.settings, "expected settings update") as WheelSettings.Veteran

        assertEquals(70, settings.pedalSensitivity, "byte 50 (pedal hardness)")
        assertEquals(25, settings.stopSpeed, "byte 52 (stop speed)")
        assertEquals(80, settings.pwmLimit, "byte 53 (PWM limit)")
        assertEquals(90, settings.screenBacklight, "byte 55 (backlight)")
        assertEquals(true, settings.transportMode, "byte 57 (transport)")
        assertEquals(0, settings.wheelDisplayUnit, "byte 58 (display unit)")
        assertEquals(5, settings.voltageCorrection, "byte 59 (voltage correction)")
        assertEquals(true, settings.lowVoltageMode, "byte 60 (low voltage)")
        assertEquals(false, settings.highSpeedMode, "byte 61 (high speed)")
        assertEquals(50, settings.keyTone, "byte 63 (key tone)")
        assertEquals(100, settings.maxChargeVoltage, "byte 64 (max charge)")
        assertEquals(100, settings.chargeVoltageBase, "byte 65 (base voltage)")
        assertEquals(40, settings.dynamicAssist, "byte 66 (dynamic assist)")
        assertEquals(30, settings.accelerationLimit, "byte 68 (acceleration limit)")
        assertEquals(110, settings.brakePressureAlarm, "byte 69 (brake pressure)")
    }

    @Test
    fun `batch 1 BMS subtype 1 and 5 map 15 cells into the correct pack`() {
        // Subtype 1 → bms1 (left pack), subtype 5 → bms2 (right pack).
        // Cell values are signed BE at bytes 53..82, scaled ÷1000.
        val cases = listOf(
            Triple(LeaperkimBatch1Fixtures.bmsLeftCells1To15, "bms1", 3.750),
            Triple(LeaperkimBatch1Fixtures.bmsRightCells1To15, "bms2", 3.800),
        )

        for ((fixture, packLabel, expectedCellV) in cases) {
            val run = LeaperkimCorrectnessHarness.run(fixture)
            run.outcome(LeaperkimDecoderCandidate.VETERAN).assertMatchesGolden(fixture)

            // Direct decoder pass to inspect parsed cell voltages.
            val decoder = VeteranDecoder()
            val frame = fixture.golden.frames.single().hexToByteArray()
            val result = decoder.decode(frame, DecoderState(), fixture.golden.config)
            assertTrue(result is DecodeResult.Success, "$packLabel fixture must decode")
            val bms = assertNotNull((result as DecodeResult.Success).data.bms, "expected BMS update")
            val pack = if (packLabel == "bms1") bms.bms1 else bms.bms2
            assertNotNull(pack, "$packLabel must be populated for this subtype")
            for (i in 0 until 15) {
                assertEquals(expectedCellV, pack.cells[i], "$packLabel cell $i")
            }
            // The opposite pack must stay untouched (no cells written).
            val otherPack = if (packLabel == "bms1") bms.bms2 else bms.bms1
            if (otherPack != null) {
                for (i in 0 until 15) {
                    assertEquals(0.0, otherPack.cells[i], "opposite pack cell $i should be 0.0")
                }
            }
        }
    }

    @Test
    fun `batch 1 battery percent from frame overrides voltage-derived SOC`() {
        val fixture = LeaperkimBatch1Fixtures.batteryPercentFromFrame
        val run = LeaperkimCorrectnessHarness.run(fixture)
        run.outcome(LeaperkimDecoderCandidate.VETERAN).assertMatchesGolden(fixture)
    }

    @Test
    fun `batch 1 SOC table lookup runs via useCustomPercents`() {
        val fixture = LeaperkimBatch1Fixtures.socTableLookupByHardwareVersion
        val run = LeaperkimCorrectnessHarness.run(fixture)
        run.outcome(LeaperkimDecoderCandidate.VETERAN).assertMatchesGolden(fixture)
    }

    @Test
    fun `phase 2 subtype 2 byte 47 writes fall protection angle`() {
        val fixture = LeaperkimBatch1Fixtures.fallProtectionAngleSubtype2
        val run = LeaperkimCorrectnessHarness.run(fixture)
        run.outcome(LeaperkimDecoderCandidate.VETERAN).assertMatchesGolden(fixture)

        val decoder = VeteranDecoder()
        val frame = fixture.golden.frames.single().hexToByteArray()
        val result = decoder.decode(frame, DecoderState(), fixture.golden.config)
        assertTrue(result is DecodeResult.Success, "fall-protection fixture must decode")
        val settings = assertNotNull((result as DecodeResult.Success).data.settings, "expected settings update") as WheelSettings.Veteran
        assertEquals(70, settings.lateralCutoffAngle, "byte 47 → lateralCutoffAngle")
    }

    @Test
    fun `phase 2 subtype 5 byte 51 writes lock state`() {
        val fixture = LeaperkimBatch1Fixtures.lockStateSubtype5
        val run = LeaperkimCorrectnessHarness.run(fixture)
        run.outcome(LeaperkimDecoderCandidate.VETERAN).assertMatchesGolden(fixture)

        val decoder = VeteranDecoder()
        val frame = fixture.golden.frames.single().hexToByteArray()
        val result = decoder.decode(frame, DecoderState(), fixture.golden.config)
        assertTrue(result is DecodeResult.Success, "lock-state fixture must decode")
        val settings = assertNotNull((result as DecodeResult.Success).data.settings, "expected settings update") as WheelSettings.Veteran
        assertEquals(1, settings.lockState, "byte 51 → lockState (1 = locked)")
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
        // Scope: this protects [AutoDetectDecoder]'s internal invariant, which
        // is not currently in the shipped session-picking path (see fixture
        // doc). It locks the decoder contract for any future re-enable.
        val fixture = LeaperkimCorrectnessFixtures.autodetectAaaaRoutesToCan

        val run = LeaperkimCorrectnessHarness.run(fixture)

        run.assertRoutingExpectation()
        run.assertCandidateExpectations()
        run.outcome(LeaperkimDecoderCandidate.LEAPERKIM_CAN).assertMatchesGolden(fixture)
        run.outcome(LeaperkimDecoderCandidate.AUTO_DETECT).assertMatchesGolden(fixture, "AUTO_DETECT")
        assertEquals(
            WheelType.VETERAN,
            run.routingOutcome.wheelType,
            "Leaperkim-family name must resolve to VETERAN at discovery time",
        )
        assertEquals(
            WheelType.LEAPERKIM,
            run.outcome(LeaperkimDecoderCandidate.AUTO_DETECT).resolvedWheelType,
            "AA AA packet evidence must promote AutoDetectDecoder to LEAPERKIM",
        )
    }

    @Test
    fun `production routing picks VeteranDecoder for Leaperkim-family devices`() {
        // Mirror the shipped flow: WheelConnectionManager.reduceServicesDiscovered
        // calls wheelTypeDetector.detect(...) and immediately picks a concrete
        // decoder via DefaultWheelDecoderFactory — never AutoDetectDecoder.
        //
        // For an LK-family name on FFE0/FFE1 topology this locks the production
        // decision: VETERAN, not LEAPERKIM. (A wheel that then sent AA AA frames
        // would stay stuck on VeteranDecoder; that is by design — promotion is
        // not the production contract.)
        val detector = WheelTypeDetector()
        val factory = DefaultWheelDecoderFactory()
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb"),
                ),
            ),
        )

        val detection = detector.detect(services, "LK-SHERMAN")
        val detected = detection as? WheelTypeDetector.DetectionResult.Detected
            ?: error("detect must return Detected for LK-SHERMAN + FFE0/FFE1, got $detection")
        assertEquals(WheelType.VETERAN, detected.wheelType)

        val decoder = factory.createDecoder(detected.wheelType)
        assertEquals(
            "VeteranDecoder",
            decoder?.let { it::class.simpleName },
            "Production factory must pick VeteranDecoder for VETERAN detection",
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
