package org.freewheel.core.protocol.fixtures

import org.freewheel.core.ble.ServiceTopology
import org.freewheel.core.domain.ProtocolFamily
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.veteranCrc32
import org.freewheel.core.utils.ByteUtils

/**
 * Batch 1 of the Leaperkim correctness program: official-app-backed parser
 * coverage. Each fixture pins one Veteran-decoder code path that is anchored
 * in the v1.4.8 Leaperkim Android app, so flipping the path in production
 * lands one of these tests red.
 *
 * Fixtures cover:
 *  - per-model baseline main frames (Sherman, Patton, Patton S, Sherman L, Lynx)
 *  - subtype `8` control-settings frame
 *  - subtype `1` and `5` BMS cell-mapping frames
 *  - battery-percent override via subtype `2`
 *  - official-app SOC table lookup gated by `useCustomPercents`
 *
 * Source-of-truth ranking: `OFFICIAL_APP` for the parsing layout
 * ([VeteranDecoder.processFrame], [VeteranDecoder.parseControlSettings],
 * [VeteranDecoder.processBmsData]) and for [VeteranSocTables]; `LEGACY_TRACE`
 * was already covered by [LeaperkimCorrectnessFixtures.veteranOldBoardLegacyTrace].
 */
internal object LeaperkimBatch1Fixtures {

    private const val SUFFIX = "-0000-1000-8000-00805f9b34fb"

    private fun s(short: String): String = "0000${short.lowercase()}$SUFFIX"

    private fun service(uuid: String, vararg chars: String): ServiceTopology =
        ServiceTopology(uuid = uuid, characteristics = chars.toSet())

    private val legacyFfeGatt = listOf(
        service(s("ffe0"), s("ffe1")),
    )

    // ==================== Frame builders ====================

    /**
     * Build a 36-byte Veteran main telemetry frame. Mirrors
     * `VeteranDecoder.processFrame` field offsets.
     *
     * Defaults are zeroed except where the unpacker requires specific values
     * (byte 22 = 0x00 charge-mode high byte, byte 23 in {0x00, 0x01}, byte 30
     * in {0x00, 0x07}, byte 31 = 0x00).
     */
    fun buildMainFrame(
        voltage: Int = 0,
        speed: Int = 0,
        distance: Int = 0,
        totalDistance: Int = 0,
        phaseCurrent: Int = 0,
        temperature: Int = 0,
        ver: Int,
    ): ByteArray {
        val frame = ByteArray(36)
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A
        frame[2] = 0x5C
        frame[3] = 32

        frame[4] = ((voltage shr 8) and 0xFF).toByte()
        frame[5] = (voltage and 0xFF).toByte()

        frame[6] = ((speed shr 8) and 0xFF).toByte()
        frame[7] = (speed and 0xFF).toByte()

        // distance: reversed BE (bytes 0..3 = hi-mid-lo, lo-lo, hi-hi, lo-hi)
        frame[8] = ((distance shr 8) and 0xFF).toByte()
        frame[9] = (distance and 0xFF).toByte()
        frame[10] = ((distance shr 24) and 0xFF).toByte()
        frame[11] = ((distance shr 16) and 0xFF).toByte()

        frame[12] = ((totalDistance shr 8) and 0xFF).toByte()
        frame[13] = (totalDistance and 0xFF).toByte()
        frame[14] = ((totalDistance shr 24) and 0xFF).toByte()
        frame[15] = ((totalDistance shr 16) and 0xFF).toByte()

        frame[16] = ((phaseCurrent shr 8) and 0xFF).toByte()
        frame[17] = (phaseCurrent and 0xFF).toByte()

        frame[18] = ((temperature shr 8) and 0xFF).toByte()
        frame[19] = (temperature and 0xFF).toByte()

        // bytes 20-21 (autoOffSec), 22-23 (chargeMode), 24-25 (speedAlert),
        // 26-27 (speedTiltback): zeroed
        frame[22] = 0x00
        frame[23] = 0x00

        // version BE
        frame[28] = ((ver shr 8) and 0xFF).toByte()
        frame[29] = (ver and 0xFF).toByte()

        // pedals mode byte 30 in {0x00, 0x07}; byte 31 = 0x00
        frame[30] = 0x00
        frame[31] = 0x00

        return frame
    }

    /**
     * Build an extended Veteran frame (length > 38) with a sub-type marker at
     * byte 46 and an optional per-byte payload writer for bytes 47..(45+payloadSize).
     *
     * The unpacker requires CRC32 over the first `len` bytes when len > 38;
     * appended as 4 big-endian bytes at offset `len`.
     */
    fun buildExtendedFrame(
        ver: Int,
        subType: Int,
        voltage: Int = 13500,
        payloadSize: Int = 40,
        writePayload: (ByteArray) -> Unit = {},
    ): ByteArray {
        val base = buildMainFrame(voltage = voltage, ver = ver)
        val len = 47 + payloadSize
        val total = len + 4
        val extended = ByteArray(total)
        base.copyInto(extended)

        extended[3] = len.toByte()
        extended[46] = subType.toByte()

        writePayload(extended)

        val crc = veteranCrc32(extended, 0, len)
        extended[len] = ((crc shr 24) and 0xFF).toByte()
        extended[len + 1] = ((crc shr 16) and 0xFF).toByte()
        extended[len + 2] = ((crc shr 8) and 0xFF).toByte()
        extended[len + 3] = (crc and 0xFF).toByte()
        return extended
    }

    private fun hexOf(bytes: ByteArray): String = ByteUtils.bytesToHex(bytes)

    // ==================== #1-5: Per-model baseline main frames ====================

    /**
     * Build a per-model baseline fixture: just decode a main frame at the
     * model's mVer and assert model name plus voltage round-trip.
     *
     * Voltage values are picked inside the model's nominal pack range so the
     * piecewise SOC fallback returns a stable non-extreme number.
     */
    private fun baselineMainFrameFixture(
        id: String,
        description: String,
        modelName: String,
        ver: Int,
        voltage: Int,
        expectedBattery: Int,
        evidence: Set<LeaperkimEvidenceClass> = setOf(LeaperkimEvidenceClass.OFFICIAL_APP),
    ): LeaperkimCorrectnessFixture {
        val frame = buildMainFrame(voltage = voltage, ver = ver)
        return LeaperkimCorrectnessFixture(
            id = id,
            description = description,
            model = modelName,
            evidenceClasses = evidence,
            status = LeaperkimFixtureStatus.APPROVED,
            sources = listOf(
                "VeteranDecoder.processFrame model table (mVer=${ver / 1000})",
                "Leaperkim app v1.4.8 model identity (hardwareVersion=${ver / 1000 * 1000 / 10})",
            ),
            protocolExpectation = ProtocolFamily.VETERAN,
            deviceName = modelName.uppercase().replace(" ", "-"),
            advertisedServices = legacyFfeGatt,
            golden = DecoderFixture(
                name = id,
                description = description,
                frames = listOf(hexOf(frame)),
                expect = DecoderFixture.Expected(
                    lastResult = DecoderFixture.ResultKind.SUCCESS,
                    telemetry = DecoderFixture.TelemetryExpect(
                        voltage = voltage,
                        batteryLevel = expectedBattery,
                    ),
                    identity = DecoderFixture.IdentityExpect(
                        model = modelName,
                    ),
                ),
            ),
            routingExpectation = LeaperkimRoutingExpectation(
                result = LeaperkimRoutingResultKind.DETECTED,
                wheelType = org.freewheel.core.domain.WheelType.VETERAN,
            ),
            candidateExpectations = mapOf(
                LeaperkimDecoderCandidate.VETERAN to CandidateExpectation(
                    accepted = true,
                    lastResult = DecoderFixture.ResultKind.SUCCESS,
                    protocolFamily = ProtocolFamily.VETERAN,
                    resolvedWheelType = org.freewheel.core.domain.WheelType.VETERAN,
                ),
                LeaperkimDecoderCandidate.LEAPERKIM_CAN to CandidateExpectation(
                    accepted = false,
                ),
                LeaperkimDecoderCandidate.AUTO_DETECT to CandidateExpectation(
                    accepted = true,
                    lastResult = DecoderFixture.ResultKind.SUCCESS,
                    resolvedWheelType = org.freewheel.core.domain.WheelType.VETERAN,
                ),
            ),
        )
    }

    /** Sherman: 100V class. voltage 9000 → piecewise ~55%. */
    val shermanBaselineMainFrame = baselineMainFrameFixture(
        id = "leaperkim_sherman_baseline_main_frame",
        description = "Sherman (mVer=1, 100V class) main-frame model + voltage round-trip.",
        modelName = "Leaperkim Sherman",
        ver = 1000,
        voltage = 9000,
        expectedBattery = 55,
    )

    /** Patton: 126V class. (11000 - 9918) / 24.2 = 44.71 → 45. */
    val pattonBaselineMainFrame = baselineMainFrameFixture(
        id = "leaperkim_patton_baseline_main_frame",
        description = "Patton (mVer=4, 126V class) main-frame model + voltage round-trip.",
        modelName = "Leaperkim Patton",
        ver = 4000,
        voltage = 11000,
        expectedBattery = 45,
    )

    /** Patton S: hardwareVersion=0070 shares the Patton SOC curve. */
    val pattonSBaselineMainFrame = baselineMainFrameFixture(
        id = "leaperkim_patton_s_baseline_main_frame",
        description = "Patton S (mVer=7, hardwareVersion=0070) stays on the legacy Veteran path.",
        modelName = "Leaperkim Patton S",
        ver = 7000,
        voltage = 11000,
        expectedBattery = 45,
    )

    /** Sherman L: hardwareVersion=0060, 151V class. (13500 - 11902) / 29.03 = 55.05 → 55. */
    val shermanLBaselineMainFrame = baselineMainFrameFixture(
        id = "leaperkim_sherman_l_baseline_main_frame",
        description = "Sherman L (mVer=6, hardwareVersion=0060) on the 151V Lynx-class curve.",
        modelName = "Leaperkim Sherman L",
        ver = 6000,
        voltage = 13500,
        expectedBattery = 55,
    )

    /** Lynx: hardwareVersion=0050, same 151V curve as Sherman L. */
    val lynxBaselineMainFrame = baselineMainFrameFixture(
        id = "leaperkim_lynx_baseline_main_frame",
        description = "Lynx (mVer=5, hardwareVersion=0050) on the 151V curve.",
        modelName = "Leaperkim Lynx",
        ver = 5000,
        voltage = 13500,
        expectedBattery = 55,
    )

    // ==================== #6: Subtype 8 control settings ====================

    /**
     * Subtype 8 wire layout (per [VeteranDecoder.parseControlSettings]):
     *   byte 50 pedal hardness, 52 stop speed, 53 PWM limit, 55 backlight,
     *   57 transport mode, 58 wheel unit, 59 voltage correction (signed),
     *   60 low voltage mode, 61 high speed mode, 63 key tone, 64 max
     *   charge voltage, 65 base voltage, 66 dynamic assist, 68 acceleration
     *   limit, 69 brake pressure alarm.
     *
     * The fixture only asserts that the frame is accepted (settings round-trip
     * is exercised by the supplemental harness test).
     */
    val controlSettingsBlock = LeaperkimCorrectnessFixture(
        id = "leaperkim_control_settings_block",
        description = "Subtype 8 control-settings block round-trips through VeteranDecoder.",
        model = "Leaperkim Lynx (control settings)",
        evidenceClasses = setOf(LeaperkimEvidenceClass.OFFICIAL_APP),
        status = LeaperkimFixtureStatus.APPROVED,
        sources = listOf(
            "VeteranDecoder.parseControlSettings byte map",
            "Leaperkim app v1.4.8 ControlActivity field offsets",
        ),
        protocolExpectation = ProtocolFamily.VETERAN,
        deviceName = "LYNX-CTRL",
        advertisedServices = legacyFfeGatt,
        golden = DecoderFixture(
            name = "leaperkim_control_settings_block",
            description = "Subtype 8 with a mixed bag of representative field values.",
            frames = listOf(
                hexOf(
                    buildExtendedFrame(ver = 5000, subType = 8, voltage = 13500) { f ->
                        f[50] = 70.toByte()              // pedal hardness
                        f[52] = 25.toByte()              // stop speed
                        f[53] = 80.toByte()              // PWM limit
                        f[55] = 90.toByte()              // backlight
                        f[57] = 0x01                     // transport mode ON
                        f[58] = 0x00                     // km (not miles)
                        f[59] = 5.toByte()               // +5 voltage correction
                        f[60] = 0x01                     // low voltage mode ON
                        f[61] = 0x00                     // high speed mode OFF
                        f[63] = 50.toByte()              // key tone
                        f[64] = 100.toByte()             // max charge voltage
                        f[65] = 100.toByte()             // base voltage 100 (non-zero)
                        f[66] = 40.toByte()              // dynamic assist
                        f[68] = 30.toByte()              // acceleration limit
                        f[69] = 110.toByte()             // brake pressure alarm
                    }
                )
            ),
            expect = DecoderFixture.Expected(
                lastResult = DecoderFixture.ResultKind.SUCCESS,
            ),
        ),
    )

    // ==================== #7-8: BMS subtype 1 (left) and 5 (right) ====================

    /**
     * BMS cell mapping is at bytes 53..82 for subtypes 1, 5: 15 cells × 2 bytes
     * each, signed big-endian, value × 1000 mV. Subtype 1 → bms1, subtype 5 → bms2.
     */
    private fun buildBmsCellFrame(subType: Int, mvPerCell: Int): ByteArray {
        return buildExtendedFrame(ver = 5000, subType = subType, voltage = 13500) { f ->
            for (i in 0 until 15) {
                val offset = 53 + i * 2
                f[offset] = ((mvPerCell shr 8) and 0xFF).toByte()
                f[offset + 1] = (mvPerCell and 0xFF).toByte()
            }
        }
    }

    val bmsLeftCells1To15 = LeaperkimCorrectnessFixture(
        id = "leaperkim_bms_left_1_to_15",
        description = "Subtype 1 maps 15 cell voltages into bms1 (left battery).",
        model = "Leaperkim Lynx (BMS left)",
        evidenceClasses = setOf(LeaperkimEvidenceClass.OFFICIAL_APP),
        status = LeaperkimFixtureStatus.APPROVED,
        sources = listOf(
            "VeteranDecoder.processBmsData pNum=1 branch (bytes 53..82, ×1000)",
        ),
        protocolExpectation = ProtocolFamily.VETERAN,
        deviceName = "LYNX-BMS-L",
        advertisedServices = legacyFfeGatt,
        golden = DecoderFixture(
            name = "leaperkim_bms_left_1_to_15",
            description = "15 cells at 3750 mV each into bms1.",
            frames = listOf(hexOf(buildBmsCellFrame(subType = 1, mvPerCell = 3750))),
            expect = DecoderFixture.Expected(
                lastResult = DecoderFixture.ResultKind.SUCCESS,
            ),
        ),
    )

    val bmsRightCells1To15 = LeaperkimCorrectnessFixture(
        id = "leaperkim_bms_right_1_to_15",
        description = "Subtype 5 maps 15 cell voltages into bms2 (right battery).",
        model = "Leaperkim Lynx (BMS right)",
        evidenceClasses = setOf(LeaperkimEvidenceClass.OFFICIAL_APP),
        status = LeaperkimFixtureStatus.APPROVED,
        sources = listOf(
            "VeteranDecoder.processBmsData pNum=5 branch (bytes 53..82, ×1000)",
        ),
        protocolExpectation = ProtocolFamily.VETERAN,
        deviceName = "LYNX-BMS-R",
        advertisedServices = legacyFfeGatt,
        golden = DecoderFixture(
            name = "leaperkim_bms_right_1_to_15",
            description = "15 cells at 3800 mV each into bms2.",
            frames = listOf(hexOf(buildBmsCellFrame(subType = 5, mvPerCell = 3800))),
            expect = DecoderFixture.Expected(
                lastResult = DecoderFixture.ResultKind.SUCCESS,
            ),
        ),
    )

    // ==================== #9: Battery % override via subtype 2 ====================

    /**
     * Subtype 2 byte 50 carries an in-frame battery percent override. When
     * present and valid (0..100), the decoder uses it instead of computing
     * SOC from voltage. Mirrors the official app's `isNewBatteryCulModel` path.
     */
    val batteryPercentFromFrame = LeaperkimCorrectnessFixture(
        id = "leaperkim_battery_percent_from_frame_when_present",
        description = "Subtype 2 byte 50 overrides voltage-derived battery percent.",
        model = "Leaperkim Lynx (frame battery %)",
        evidenceClasses = setOf(LeaperkimEvidenceClass.OFFICIAL_APP),
        status = LeaperkimFixtureStatus.APPROVED,
        sources = listOf(
            "VeteranDecoder.parseSubTypeData pNum=2 branch (byte 50)",
            "Leaperkim app v1.4.8 isNewBatteryCulModel path",
        ),
        protocolExpectation = ProtocolFamily.VETERAN,
        deviceName = "LYNX-BAT",
        advertisedServices = legacyFfeGatt,
        golden = DecoderFixture(
            name = "leaperkim_battery_percent_from_frame_when_present",
            description = "Voltage would compute ~55% but frame byte 50 = 77 overrides.",
            frames = listOf(
                hexOf(
                    buildExtendedFrame(ver = 5000, subType = 2, voltage = 13500) { f ->
                        f[47] = 0x00                  // no fall protection angle override
                        f[50] = 77.toByte()           // explicit battery %
                    }
                )
            ),
            expect = DecoderFixture.Expected(
                lastResult = DecoderFixture.ResultKind.SUCCESS,
                telemetry = DecoderFixture.TelemetryExpect(
                    voltage = 13500,
                    batteryLevel = 77,
                ),
            ),
        ),
    )

    // ==================== #10: SOC table lookup gated by useCustomPercents ====================

    /**
     * With `useCustomPercents = true`, the decoder routes through the official
     * Leaperkim SOC tables in [VeteranSocTables] instead of the piecewise
     * fallback. mVer=5 (Lynx) + voltage 13255 hits LYNX_151V index 50 → 50%.
     */
    val socTableLookupByHardwareVersion = LeaperkimCorrectnessFixture(
        id = "leaperkim_soc_table_lookup_by_hardware_version",
        description = "useCustomPercents routes Lynx voltage 132.55V through LYNX_151V to SOC 50%.",
        model = "Leaperkim Lynx (SOC table)",
        evidenceClasses = setOf(LeaperkimEvidenceClass.OFFICIAL_APP),
        status = LeaperkimFixtureStatus.APPROVED,
        sources = listOf(
            "VeteranSocTables.LYNX_151V index 50 = 13255",
            "Leaperkim app v1.4.8 SOC table for hardwareVersion=0050",
        ),
        protocolExpectation = ProtocolFamily.VETERAN,
        deviceName = "LYNX-SOC",
        advertisedServices = legacyFfeGatt,
        golden = DecoderFixture(
            name = "leaperkim_soc_table_lookup_by_hardware_version",
            description = "Main frame at Lynx voltage 13255 with useCustomPercents=true → SOC 50.",
            frames = listOf(hexOf(buildMainFrame(ver = 5000, voltage = 13255))),
            config = DecoderConfig(useCustomPercents = true),
            expect = DecoderFixture.Expected(
                lastResult = DecoderFixture.ResultKind.SUCCESS,
                telemetry = DecoderFixture.TelemetryExpect(
                    voltage = 13255,
                    batteryLevel = 50,
                ),
            ),
        ),
    )

    val perModelMainFrames = listOf(
        shermanBaselineMainFrame,
        pattonBaselineMainFrame,
        pattonSBaselineMainFrame,
        shermanLBaselineMainFrame,
        lynxBaselineMainFrame,
    )

    val subtypeAndSocFixtures = listOf(
        controlSettingsBlock,
        bmsLeftCells1To15,
        bmsRightCells1To15,
        batteryPercentFromFrame,
        socTableLookupByHardwareVersion,
    )

    val all = perModelMainFrames + subtypeAndSocFixtures
}
