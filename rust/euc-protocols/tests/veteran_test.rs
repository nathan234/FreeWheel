//! Port of `VeteranDecoderTest.kt` + `LookupSocTest` + `VeteranUnpackerTest.kt`
//! — the behavioral parity oracle for the Veteran/Leaperkim decoder.
//!
//! Test data and expected values are copied verbatim from the Kotlin suite,
//! including the real Nosfet Aero and Apex capture values.

use euc_protocols::byte_utils::hex_to_bytes;
use euc_protocols::checksums::crc32;
use euc_protocols::soc_tables;
use euc_protocols::types::SettingsCommandId;
use euc_protocols::veteran::{lookup_soc, VeteranUnpacker};
use euc_protocols::{
    DecodeResult, DecodedData, DecoderConfig, DecoderState, VeteranDecoder, VeteranSettings,
    WheelCommand, WheelSettings, WheelType,
};

// ==================== Helpers ====================

fn config() -> DecoderConfig {
    DecoderConfig {
        use_custom_percents: false,
        ..Default::default()
    }
}

fn success(result: DecodeResult) -> DecodedData {
    match result {
        DecodeResult::Success(data) => data,
        other => panic!("expected Success, got {other:?}"),
    }
}

fn tel(data: &DecodedData) -> &euc_protocols::TelemetryState {
    data.telemetry.as_ref().expect("telemetry expected")
}

fn idn(data: &DecodedData) -> &euc_protocols::WheelIdentity {
    data.identity.as_ref().expect("identity expected")
}

fn vet(data: &DecodedData) -> &VeteranSettings {
    match data.settings.as_ref().expect("settings expected") {
        WheelSettings::Veteran(v) => v,
        other => panic!("expected Veteran settings, got {other:?}"),
    }
}

fn merged(data: &DecodedData, ds: &DecoderState) -> DecoderState {
    DecoderState {
        telemetry: data.telemetry.clone().unwrap_or_else(|| ds.telemetry.clone()),
        identity: data.identity.clone().unwrap_or_else(|| ds.identity.clone()),
        bms: data.bms.clone().unwrap_or_else(|| ds.bms.clone()),
        settings: data.settings.clone().unwrap_or_else(|| ds.settings.clone()),
    }
}

fn send_bytes(command: &WheelCommand) -> &[u8] {
    match command {
        WheelCommand::SendBytes(data) => data,
        other => panic!("expected SendBytes, got {other:?}"),
    }
}

fn assert_veteran_crc(data: &[u8]) {
    let payload_size = data.len() - 4;
    let expected = crc32(data, 0, payload_size);
    let actual = ((data[payload_size] as i64) << 24)
        | ((data[payload_size + 1] as i64) << 16)
        | ((data[payload_size + 2] as i64) << 8)
        | (data[payload_size + 3] as i64);
    assert_eq!(expected, actual, "command should carry a valid CRC32");
}

/// Mirror of the Kotlin `buildVeteranFrame` test builder (36-byte frame).
#[derive(Clone)]
struct VetFrame {
    voltage: i32,
    speed: i32,
    distance: i32,
    total_distance: i32,
    phase_current: i32,
    temperature: i32,
    ver: i32,
    version_high_byte: i32,
    pedals_mode: i32,
    charge_mode_low: i32,
    speed_alert: i32,
    speed_tiltback: i32,
    auto_off_sec: i32,
    pitch_angle: i32,
    hw_pwm: i32,
}

impl Default for VetFrame {
    fn default() -> Self {
        VetFrame {
            voltage: 9686, // 96.86V
            speed: 0,
            distance: 0,
            total_distance: 0,
            phase_current: 0,
            temperature: 0,
            ver: 5000,
            version_high_byte: 0,
            pedals_mode: 0,
            charge_mode_low: 0,
            speed_alert: 0,
            speed_tiltback: 0,
            auto_off_sec: 0,
            pitch_angle: 0,
            hw_pwm: 0,
        }
    }
}

fn build_veteran_frame(f: &VetFrame) -> Vec<u8> {
    let mut frame = vec![0u8; 36];
    frame[0] = 0xDC;
    frame[1] = 0x5A;
    frame[2] = 0x5C;
    frame[3] = 32; // length of data payload
    frame[4] = ((f.voltage >> 8) & 0xFF) as u8;
    frame[5] = (f.voltage & 0xFF) as u8;
    frame[6] = ((f.speed >> 8) & 0xFF) as u8;
    frame[7] = (f.speed & 0xFF) as u8;
    // Distance in revBE at 8-11
    frame[8] = ((f.distance >> 8) & 0xFF) as u8;
    frame[9] = (f.distance & 0xFF) as u8;
    frame[10] = ((f.distance >> 24) & 0xFF) as u8;
    frame[11] = ((f.distance >> 16) & 0xFF) as u8;
    // Total distance in revBE at 12-15
    frame[12] = ((f.total_distance >> 8) & 0xFF) as u8;
    frame[13] = (f.total_distance & 0xFF) as u8;
    frame[14] = ((f.total_distance >> 24) & 0xFF) as u8;
    frame[15] = ((f.total_distance >> 16) & 0xFF) as u8;
    frame[16] = ((f.phase_current >> 8) & 0xFF) as u8;
    frame[17] = (f.phase_current & 0xFF) as u8;
    frame[18] = ((f.temperature >> 8) & 0xFF) as u8;
    frame[19] = (f.temperature & 0xFF) as u8;
    frame[20] = ((f.auto_off_sec >> 8) & 0xFF) as u8;
    frame[21] = (f.auto_off_sec & 0xFF) as u8;
    frame[22] = 0x00;
    frame[23] = (f.charge_mode_low & 0x01) as u8;
    frame[24] = ((f.speed_alert >> 8) & 0xFF) as u8;
    frame[25] = (f.speed_alert & 0xFF) as u8;
    frame[26] = ((f.speed_tiltback >> 8) & 0xFF) as u8;
    frame[27] = (f.speed_tiltback & 0xFF) as u8;
    frame[28] = ((f.ver >> 8) & 0xFF) as u8;
    frame[29] = (f.ver & 0xFF) as u8;
    frame[30] = (f.version_high_byte & 0xFF) as u8;
    frame[31] = (f.pedals_mode & 0xFF) as u8;
    frame[32] = ((f.pitch_angle >> 8) & 0xFF) as u8;
    frame[33] = (f.pitch_angle & 0xFF) as u8;
    frame[34] = ((f.hw_pwm >> 8) & 0xFF) as u8;
    frame[35] = (f.hw_pwm & 0xFF) as u8;
    frame
}

fn decode_single_frame_cfg(f: VetFrame, cfg: &DecoderConfig) -> DecodeResult {
    let mut decoder = VeteranDecoder::new();
    decoder.decode(&build_veteran_frame(&f), &DecoderState::default(), cfg)
}

fn decode_single_frame(f: VetFrame) -> DecodeResult {
    decode_single_frame_cfg(f, &config())
}

/// Mirror of the Kotlin `buildExtendedFrame` builder: extended frames
/// (> 46 bytes) require CRC32 validation since len > 38.
fn build_extended_frame(voltage: i32, ver: i32, version_high_byte: i32, sub_type: i32) -> Vec<u8> {
    let base = build_veteran_frame(&VetFrame {
        voltage,
        ver,
        version_high_byte,
        ..Default::default()
    });
    let extra_size = 40;
    let unpacker_len = 47 + extra_size; // byte[3]
    let total_size = unpacker_len + 4; // + CRC32

    let mut extended = vec![0u8; total_size];
    extended[..base.len()].copy_from_slice(&base);
    extended[3] = unpacker_len as u8;
    extended[46] = sub_type as u8;
    rewrite_crc(&mut extended);
    extended
}

fn rewrite_crc(frame: &mut [u8]) {
    let unpacker_len = frame[3] as usize;
    let crc = crc32(frame, 0, unpacker_len);
    frame[unpacker_len] = ((crc >> 24) & 0xFF) as u8;
    frame[unpacker_len + 1] = ((crc >> 16) & 0xFF) as u8;
    frame[unpacker_len + 2] = ((crc >> 8) & 0xFF) as u8;
    frame[unpacker_len + 3] = (crc & 0xFF) as u8;
}

fn decode_extended_frame<F: FnOnce(&mut [u8])>(sub_type: i32, modifier: F) -> DecodeResult {
    decode_extended_frame_full(9686, 5000, 0, sub_type, modifier)
}

fn decode_extended_frame_full<F: FnOnce(&mut [u8])>(
    voltage: i32,
    ver: i32,
    version_high_byte: i32,
    sub_type: i32,
    modifier: F,
) -> DecodeResult {
    let mut decoder = VeteranDecoder::new();
    let mut frame = build_extended_frame(voltage, ver, version_high_byte, sub_type);
    modifier(&mut frame);
    rewrite_crc(&mut frame);
    decoder.decode(&frame, &DecoderState::default(), &config())
}

/// Decoder + state pair for build_command tests (mirror of `decoderWithVer`).
struct DecoderWithState {
    decoder: VeteranDecoder,
    state: DecoderState,
}

impl DecoderWithState {
    fn build(&mut self, command: WheelCommand) -> Vec<WheelCommand> {
        self.decoder.build_command(&command, Some(&self.state))
    }
}

fn state_with_ver(m_ver: i32) -> DecoderState {
    DecoderState {
        settings: WheelSettings::Veteran(VeteranSettings {
            m_ver,
            ..Default::default()
        }),
        ..Default::default()
    }
}

fn decoder_with_ver(ver: i32) -> DecoderWithState {
    let mut decoder = VeteranDecoder::new();
    let frame = build_veteran_frame(&VetFrame {
        ver,
        ..Default::default()
    });
    decoder.decode(&frame, &DecoderState::default(), &config());
    DecoderWithState {
        decoder,
        state: state_with_ver(ver / 1000),
    }
}

fn decoder_with_nosfet_version() -> DecoderWithState {
    let mut decoder = VeteranDecoder::new();
    let frame = build_veteran_frame(&VetFrame {
        ver: 43_254,
        version_high_byte: 0x07,
        pedals_mode: 0x80,
        ..Default::default()
    });
    decoder.decode(&frame, &DecoderState::default(), &config());
    DecoderWithState {
        decoder,
        state: state_with_ver(43),
    }
}

// ==================== Basic Frame Validation ====================

#[test]
fn minimum_valid_frame_decodes_successfully() {
    let decoded = success(decode_single_frame(VetFrame::default()));
    assert!(decoded.has_new_data);
    assert_eq!(WheelType::Veteran, idn(&decoded).wheel_type);
}

#[test]
fn frame_too_short_returns_unhandled() {
    let mut decoder = VeteranDecoder::new();
    let mut frame = vec![0u8; 35];
    frame[0] = 0xDC;
    frame[1] = 0x5A;
    frame[2] = 0x5C;
    frame[3] = 31; // shorter than needed
    let result = decoder.decode(&frame, &DecoderState::default(), &config());
    assert!(
        matches!(result, DecodeResult::Unhandled { .. }),
        "Frame shorter than 36 bytes should return Unhandled"
    );
}

#[test]
fn corrupted_header_returns_buffering() {
    let mut decoder = VeteranDecoder::new();
    let mut frame = vec![0u8; 36];
    frame[0] = 0xAA;
    frame[1] = 0x55;
    frame[2] = 0x5C;
    frame[3] = 32;
    let result = decoder.decode(&frame, &DecoderState::default(), &config());
    assert_eq!(DecodeResult::Buffering, result);
}

#[test]
fn unpacker_rejects_invalid_legacy_structural_sentinels() {
    let mut decoder = VeteranDecoder::new();
    let mut frame = build_veteran_frame(&VetFrame::default());
    frame[22] = 0x01;
    frame[23] = 0x02;
    frame[30] = 0x08;
    let result = decoder.decode(&frame, &DecoderState::default(), &config());
    assert_eq!(DecodeResult::Buffering, result);
}

#[test]
fn unpacker_accepts_byte_23_equal_to_0x01() {
    let result = decode_single_frame(VetFrame {
        charge_mode_low: 1,
        ..Default::default()
    });
    assert!(matches!(result, DecodeResult::Success(_)));
}

#[test]
fn unpacker_accepts_nosfet_firmware_high_byte() {
    let result = decode_single_frame(VetFrame {
        version_high_byte: 0x07,
        ..Default::default()
    });
    assert!(matches!(result, DecodeResult::Success(_)));
}

// ==================== Field Parsing ====================

#[test]
fn voltage_is_parsed_correctly() {
    let decoded = success(decode_single_frame(VetFrame::default()));
    assert_eq!(9686, tel(&decoded).voltage, "Voltage should be 9686 (96.86V)");
}

#[test]
fn voltage_at_full_and_empty_follow_sherman_table() {
    let full = success(decode_single_frame(VetFrame {
        voltage: 9870,
        ver: 1000,
        ..Default::default()
    }));
    assert_eq!(9870, tel(&full).voltage);
    assert_eq!(98, tel(&full).battery_level);

    let empty = success(decode_single_frame(VetFrame {
        voltage: 7935,
        ver: 1000,
        ..Default::default()
    }));
    assert_eq!(10, tel(&empty).battery_level);
}

#[test]
fn speed_polarity_handling() {
    // (gotway_negative, raw speed, expected)
    for (gn, raw, expected) in [(0, -100, 1000), (1, -100, -1000), (-1, 100, -1000), (0, 50, 500)] {
        let cfg = DecoderConfig {
            gotway_negative: gn,
            ..config()
        };
        let decoded = success(decode_single_frame_cfg(
            VetFrame {
                speed: raw,
                ..Default::default()
            },
            &cfg,
        ));
        assert_eq!(expected, tel(&decoded).speed, "gn={gn} raw={raw}");
    }
}

#[test]
fn phase_current_polarity_handling() {
    for (gn, raw, expected) in [(0, -34, 340), (1, -34, -340), (-1, 34, -340)] {
        let cfg = DecoderConfig {
            gotway_negative: gn,
            ..config()
        };
        let decoded = success(decode_single_frame_cfg(
            VetFrame {
                phase_current: raw,
                ..Default::default()
            },
            &cfg,
        ));
        assert_eq!(expected, tel(&decoded).phase_current, "gn={gn} raw={raw}");
    }
}

#[test]
fn temperature_is_parsed_as_raw_signed_be() {
    let decoded = success(decode_single_frame(VetFrame {
        temperature: 5017,
        ..Default::default()
    }));
    assert_eq!(5017, tel(&decoded).temperature);

    let negative = success(decode_single_frame(VetFrame {
        temperature: -10,
        ..Default::default()
    }));
    assert_eq!(-10, tel(&negative).temperature);
}

#[test]
fn distance_decodes_correctly_via_rev_be() {
    for (dist, expected) in [(0i32, 0i64), (15349, 15349), (1_000_000, 1_000_000)] {
        let decoded = success(decode_single_frame(VetFrame {
            distance: dist,
            ..Default::default()
        }));
        assert_eq!(expected, tel(&decoded).wheel_distance, "distance {dist}");
    }
    let decoded = success(decode_single_frame(VetFrame {
        total_distance: 15349,
        ..Default::default()
    }));
    assert_eq!(15349, tel(&decoded).total_distance);
}

#[test]
fn pitch_angle_is_parsed_and_scaled() {
    let decoded = success(decode_single_frame(VetFrame {
        pitch_angle: 150,
        ..Default::default()
    }));
    assert!((tel(&decoded).angle - 1.5).abs() < 1e-9);

    let negative = success(decode_single_frame(VetFrame {
        pitch_angle: -250,
        ..Default::default()
    }));
    assert!((tel(&negative).angle + 2.5).abs() < 1e-9);
}

#[test]
fn hw_pwm_is_used_when_hw_pwm_enabled() {
    let cfg = DecoderConfig {
        hw_pwm_enabled: true,
        ..config()
    };
    let decoded = success(decode_single_frame_cfg(
        VetFrame {
            hw_pwm: 5000,
            ..Default::default()
        },
        &cfg,
    ));
    assert_eq!(5000, tel(&decoded).output);
    assert!((tel(&decoded).calculated_pwm - 0.5).abs() < 0.001);
}

// ==================== Model Detection ====================

#[test]
fn model_names_from_m_ver() {
    let cases = [
        (0, "Leaperkim Sherman"),
        (1000, "Leaperkim Sherman"),
        (2000, "Leaperkim Abrams"),
        (3000, "Leaperkim Sherman S"),
        (4000, "Leaperkim Patton"),
        (5000, "Leaperkim Lynx"),
        (6000, "Leaperkim Sherman L"),
        (7000, "Leaperkim Patton S"),
        (8000, "Leaperkim Oryx"),
        (42000, "Nosfet Apex"),
        (43000, "Nosfet Aero"),
        (99000, "Unknown"),
    ];
    for (ver, name) in cases {
        let decoded = success(decode_single_frame(VetFrame {
            ver,
            ..Default::default()
        }));
        assert_eq!(name, idn(&decoded).model, "ver {ver}");
    }
}

#[test]
fn manufacturer_family_504_is_nosfet_xeno() {
    // 504006 encoded as high byte 0x07 + low bytes 0xB0C6.
    let decoded = success(decode_single_frame(VetFrame {
        ver: 0xB0C6,
        version_high_byte: 0x07,
        pedals_mode: 0x80,
        ..Default::default()
    }));
    assert_eq!("Nosfet Xeno", idn(&decoded).model);
    assert_eq!("504.0.06", idn(&decoded).version);
}

// ==================== Battery Percentage ====================

#[test]
fn battery_soc_table_cases() {
    // (voltage, ver, expected battery)
    let cases = [
        (9900, 1000, 100),  // Sherman table full
        (7560, 1000, 0),    // Sherman table empty (first entry)
        (7500, 1000, 0),    // below first entry
        (8837, 1000, 50),   // exact table[50]
        (8828, 1000, 50),   // ceiling lookup between entries
        (9950, 1000, 100),  // above last entry
        (12375, 4000, 100), // Patton table full
        (9450, 4000, 0),    // Patton table empty
        (11046, 4000, 50),  // Patton table[50]
        (14850, 5000, 100), // Lynx table full
        (11340, 5000, 0),   // Lynx table empty
        (13255, 5000, 50),  // Lynx table[50]
        (17272, 8000, 100), // Oryx piecewise full (no table)
        (13886, 8000, 0),   // Oryx piecewise empty
        (10000, 99000, 1),  // unknown mVer defaults to 1%
    ];
    for (voltage, ver, expected) in cases {
        let decoded = success(decode_single_frame(VetFrame {
            voltage,
            ver,
            ..Default::default()
        }));
        assert_eq!(
            expected,
            tel(&decoded).battery_level,
            "voltage {voltage} ver {ver}"
        );
    }
}

#[test]
fn battery_manufacturer_table_is_independent_of_custom_percents() {
    for use_custom in [false, true] {
        let cfg = DecoderConfig {
            use_custom_percents: use_custom,
            ..config()
        };
        let decoded = success(decode_single_frame_cfg(
            VetFrame {
                voltage: 8837,
                ver: 1000,
                ..Default::default()
            },
            &cfg,
        ));
        assert_eq!(50, tel(&decoded).battery_level, "use_custom={use_custom}");
    }
}

#[test]
fn battery_oryx_piecewise_even_with_custom_percents() {
    let cfg = DecoderConfig {
        use_custom_percents: true,
        ..config()
    };
    let decoded = success(decode_single_frame_cfg(
        VetFrame {
            voltage: 17272,
            ver: 8000,
            ..Default::default()
        },
        &cfg,
    ));
    assert_eq!(100, tel(&decoded).battery_level);
}

#[test]
fn battery_nosfet_aero_uses_official_126v_table() {
    // Real Aero firmware 502.0.06: high byte 0x07, low bytes 0xA8F6.
    let decoded = success(decode_single_frame(VetFrame {
        voltage: 11046,
        ver: 0xA8F6,
        version_high_byte: 0x07,
        pedals_mode: 0x80,
        ..Default::default()
    }));
    assert_eq!(50, tel(&decoded).battery_level);
}

#[test]
fn battery_nosfet_xeno_uses_126v_table() {
    let decoded = success(decode_single_frame(VetFrame {
        voltage: 11046,
        ver: 0xB0C6,
        version_high_byte: 0x07,
        pedals_mode: 0x80,
        ..Default::default()
    }));
    assert_eq!(50, tel(&decoded).battery_level);
}

#[test]
fn battery_nosfet_apex_real_capture_uses_lynx_151v_table() {
    // Captured Apex packet: firmware 501.0.07 at 134.61 V → Nosfet app shows 58%.
    let decoded = success(decode_single_frame(VetFrame {
        voltage: 13461,
        ver: 0xA50F,
        version_high_byte: 0x07,
        pedals_mode: 0xB8,
        ..Default::default()
    }));
    assert_eq!(58, tel(&decoded).battery_level);
    assert_eq!("501.0.07", idn(&decoded).version);
    assert_eq!("Nosfet Apex", idn(&decoded).model);
}

// ==================== Version String ====================

#[test]
fn version_string_formatting() {
    let a = success(decode_single_frame(VetFrame {
        ver: 5000,
        ..Default::default()
    }));
    assert_eq!("005.0.00", idn(&a).version);

    let b = success(decode_single_frame(VetFrame {
        ver: 5123,
        ..Default::default()
    }));
    assert_eq!("005.1.23", idn(&b).version);
}

#[test]
fn nosfet_three_byte_version_decoded_without_truncation() {
    let decoded = success(decode_single_frame(VetFrame {
        ver: 0xA8F6,
        version_high_byte: 0x07,
        pedals_mode: 0x80,
        ..Default::default()
    }));
    assert_eq!("502.0.06", idn(&decoded).version);
    assert_eq!("Nosfet Aero", idn(&decoded).model);
    assert_eq!(-1, vet(&decoded).pedals_mode);
}

// ==================== isReady / Reset ====================

#[test]
fn is_ready_lifecycle() {
    let mut decoder = VeteranDecoder::new();
    assert!(!decoder.is_ready());

    decoder.decode(
        &build_veteran_frame(&VetFrame {
            ver: 5000,
            ..Default::default()
        }),
        &DecoderState::default(),
        &config(),
    );
    assert!(decoder.is_ready(), "ready after frame with mVer=5");

    decoder.reset();
    assert!(!decoder.is_ready(), "not ready after reset");
}

#[test]
fn is_ready_false_when_m_ver_is_0_true_for_1() {
    let mut decoder = VeteranDecoder::new();
    decoder.decode(
        &build_veteran_frame(&VetFrame {
            ver: 0,
            ..Default::default()
        }),
        &DecoderState::default(),
        &config(),
    );
    assert!(!decoder.is_ready());

    let mut decoder2 = VeteranDecoder::new();
    decoder2.decode(
        &build_veteran_frame(&VetFrame {
            ver: 1000,
            ..Default::default()
        }),
        &DecoderState::default(),
        &config(),
    );
    assert!(decoder2.is_ready());
}

#[test]
fn reset_allows_decoding_fresh_frames() {
    let mut decoder = VeteranDecoder::new();
    let d1 = success(decoder.decode(
        &build_veteran_frame(&VetFrame {
            ver: 5000,
            ..Default::default()
        }),
        &DecoderState::default(),
        &config(),
    ));
    assert_eq!("Leaperkim Lynx", idn(&d1).model);

    decoder.reset();
    assert!(!decoder.is_ready());

    let d2 = success(decoder.decode(
        &build_veteran_frame(&VetFrame {
            ver: 4000,
            voltage: 12000,
            ..Default::default()
        }),
        &DecoderState::default(),
        &config(),
    ));
    assert_eq!("Leaperkim Patton", idn(&d2).model);
    assert!(decoder.is_ready());
}

#[test]
fn reset_clears_crc_format_learned_from_previous_connection() {
    let mut decoder = VeteranDecoder::new();
    let crc_frame = build_extended_frame(9686, 5000, 0, 0);
    assert!(matches!(
        decoder.decode(&crc_frame, &DecoderState::default(), &config()),
        DecodeResult::Success(_)
    ));

    decoder.reset();

    let legacy_frame = build_veteran_frame(&VetFrame {
        ver: 1000,
        ..Default::default()
    });
    assert!(
        matches!(
            decoder.decode(&legacy_frame, &DecoderState::default(), &config()),
            DecodeResult::Success(_)
        ),
        "CRC-capable prior wheel must not force CRC on a new legacy connection"
    );
}

#[test]
fn new_frame_header_replaces_incomplete_prior_notification() {
    let mut decoder = VeteranDecoder::new();
    let partial: Vec<u8> = build_extended_frame(9686, 5000, 0, 0)[..20].to_vec();
    assert_eq!(
        DecodeResult::Buffering,
        decoder.decode(&partial, &DecoderState::default(), &config())
    );

    let complete = build_veteran_frame(&VetFrame {
        ver: 7000,
        voltage: 11046,
        ..Default::default()
    });
    let result = success(decoder.decode(&complete, &DecoderState::default(), &config()));
    assert_eq!("Leaperkim Patton S", idn(&result).model);
}

// ==================== Real-Capture Comparison ====================

#[test]
fn decode_veteran_old_board_data_matches_comparison_test() {
    let mut decoder = VeteranDecoder::new();
    let part1 = hex_to_bytes("DC5A5C2025D600003BF500003BF50000FFDE1399");
    let part2 = hex_to_bytes("0DEF0000024602460000000000000000");

    let mut ds = DecoderState::default();
    if let DecodeResult::Success(d) = decoder.decode(&part1, &ds, &config()) {
        ds = merged(&d, &ds);
    }
    let d2 = success(decoder.decode(&part2, &ds, &config()));
    let telemetry = merged(&d2, &ds).telemetry;

    assert_eq!(0, (telemetry.speed / 100).abs());
    assert_eq!(50, telemetry.temperature / 100);
    assert_eq!(9686, telemetry.voltage);
    assert_eq!(340, telemetry.phase_current); // raw -34 * 10 = -340, abs → 340
    assert_eq!(15349, telemetry.wheel_distance);
    assert_eq!(15349, telemetry.total_distance);
    assert_eq!(88, telemetry.battery_level);
}

#[test]
fn nosfet_aero_capture_frame_decodes_with_correct_m_ver_and_model() {
    // Real pNum=0 frame from a Nosfet Aero BLE capture (fw 43.2.54).
    let hex = "DC5A5C49".to_string()
        + "31200000E7FD00019DE9000A00000D1E"
        + "04A3000007D007D0A8F607801B8F0000"
        + "80C80000808080808080"
        + "0000000CFFFFFFFFFF3211FE850B7A0E"
        + "7901920000008C000100011CB808AE";
    let frame = hex_to_bytes(&hex);

    let mut decoder = VeteranDecoder::new();
    let data = success(decoder.decode(&frame, &DecoderState::default(), &config()));

    assert_eq!("Nosfet Aero", idn(&data).model);
    assert_eq!(WheelType::Veteran, idn(&data).wheel_type);
    assert_eq!(12576, tel(&data).voltage); // 125.76V
    assert_eq!(0, tel(&data).speed);
    assert_eq!(-1, vet(&data).pedals_mode); // 0x0780 sanitized
    assert_eq!(0, vet(&data).battery_temp_mode); // 0x80C8 sanitized
    assert_eq!(1187, vet(&data).auto_off_time);
    assert_eq!(2000, vet(&data).tilt_back_speed);
    assert_eq!(2000, vet(&data).alert_speed);
}

// ==================== Charging / Main-Frame Settings ====================

#[test]
fn charge_mode_is_parsed_correctly() {
    let one = success(decode_single_frame(VetFrame {
        charge_mode_low: 1,
        ..Default::default()
    }));
    assert_eq!(1, tel(&one).charging_status);

    let zero = success(decode_single_frame(VetFrame::default()));
    assert_eq!(0, tel(&zero).charging_status);
}

#[test]
fn tilt_back_speed_is_populated_from_frame() {
    let mut decoder = VeteranDecoder::new();
    let mut frame = build_veteran_frame(&VetFrame::default());
    frame[26] = ((450 >> 8) & 0xFF) as u8;
    frame[27] = (450 & 0xFF) as u8;
    let decoded = success(decoder.decode(&frame, &DecoderState::default(), &config()));
    assert_eq!(450, vet(&decoded).tilt_back_speed);
}

#[test]
fn ride_mode_wire_values_map_to_app_order() {
    for (wire, expected) in [(1, 2), (2, 1), (3, 0), (0, -1), (0x07, -1)] {
        let decoded = success(decode_single_frame(VetFrame {
            pedals_mode: wire,
            ..Default::default()
        }));
        assert_eq!(expected, vet(&decoded).pedals_mode, "wire {wire}");
    }
}

#[test]
fn alert_speed_and_auto_off_time_populated() {
    let decoded = success(decode_single_frame(VetFrame {
        speed_alert: 85,
        auto_off_sec: 1172,
        ..Default::default()
    }));
    assert_eq!(85, vet(&decoded).alert_speed);
    assert_eq!(1172, vet(&decoded).auto_off_time);

    let zeros = success(decode_single_frame(VetFrame::default()));
    assert_eq!(0, vet(&zeros).alert_speed);
    assert_eq!(0, vet(&zeros).auto_off_time);
}

#[test]
fn battery_temp_mode_from_bytes_36_37() {
    // (bytes 36-37 value, expected)
    for (raw, expected) in [(111i32, 111), (100, 100), (0x80C8, 0)] {
        let mut decoder = VeteranDecoder::new();
        let base = build_veteran_frame(&VetFrame::default());
        let mut frame = vec![0u8; 38];
        frame[..36].copy_from_slice(&base);
        frame[3] = 34; // length for a 38-byte frame
        frame[36] = ((raw >> 8) & 0xFF) as u8;
        frame[37] = (raw & 0xFF) as u8;
        let decoded = success(decoder.decode(&frame, &DecoderState::default(), &config()));
        assert_eq!(expected, vet(&decoded).battery_temp_mode, "raw {raw:#x}");
    }

    // Standard 36-byte frame — no bytes 36-37
    let short = success(decode_single_frame(VetFrame::default()));
    assert_eq!(0, vet(&short).battery_temp_mode);
}

// ==================== Sub-type Extended Data ====================

#[test]
fn sub_type_0_parses_roll_angle_from_bytes_67_68() {
    let decoded = success(decode_extended_frame(0, |frame| {
        frame[67] = ((250 >> 8) & 0xFF) as u8;
        frame[68] = (250 & 0xFF) as u8;
    }));
    assert!((tel(&decoded).roll - 2.5).abs() < 0.001);
}

#[test]
fn sub_type_5_parses_lock_state_from_byte_51() {
    let decoded = success(decode_extended_frame(5, |frame| {
        frame[51] = 0x50; // locked + password set
    }));
    assert_eq!(0x50, vet(&decoded).lock_state);
}

#[test]
fn sub_type_2_overrides_battery_percent_from_byte_50() {
    let decoded = success(decode_extended_frame(2, |frame| {
        frame[50] = 75;
    }));
    assert_eq!(75, tel(&decoded).battery_level);
}

#[test]
fn leaperkim_latches_wheel_reported_battery_across_later_frames() {
    let mut decoder = VeteranDecoder::new();
    let mut override_frame = build_extended_frame(13500, 5000, 0, 2);
    override_frame[50] = 77;
    rewrite_crc(&mut override_frame);
    let first = success(decoder.decode(&override_frame, &DecoderState::default(), &config()));
    assert_eq!(77, tel(&first).battery_level);

    let state = merged(&first, &DecoderState::default());
    let later_frame = build_extended_frame(11340, 5000, 0, 0);
    let second = success(decoder.decode(&later_frame, &state, &config()));
    assert_eq!(77, tel(&second).battery_level);
}

#[test]
fn nosfet_ignores_subtype_battery_byte_and_keeps_table_result() {
    let mut frame = build_extended_frame(11046, 0xA8F6, 0, 2);
    frame[30] = 0x07;
    frame[31] = 0x80;
    frame[50] = 75;
    rewrite_crc(&mut frame);
    let decoded = success(VeteranDecoder::new().decode(&frame, &DecoderState::default(), &config()));
    assert_eq!(50, tel(&decoded).battery_level);
}

#[test]
fn reset_clears_latched_leaperkim_wheel_reported_battery() {
    let mut decoder = VeteranDecoder::new();
    let mut override_frame = build_extended_frame(13500, 5000, 0, 2);
    override_frame[50] = 77;
    rewrite_crc(&mut override_frame);
    success(decoder.decode(&override_frame, &DecoderState::default(), &config()));

    decoder.reset();
    let after = success(decoder.decode(
        &build_extended_frame(11340, 5000, 0, 0),
        &DecoderState::default(),
        &config(),
    ));
    assert_eq!(0, tel(&after).battery_level);
}

#[test]
fn sub_type_2_parses_fall_protection_angle_and_battery() {
    let angle_only = success(decode_extended_frame(2, |frame| {
        frame[47] = 70;
    }));
    assert_eq!(70, vet(&angle_only).lateral_cutoff_angle);

    let both = success(decode_extended_frame(2, |frame| {
        frame[47] = 55;
        frame[50] = 80;
    }));
    assert_eq!(55, vet(&both).lateral_cutoff_angle);
    assert_eq!(80, tel(&both).battery_level);
}

#[test]
fn sub_type_2_ignores_invalid_battery_percent() {
    let decoded = success(decode_extended_frame(2, |frame| {
        frame[50] = 200; // invalid (> 100)
    }));
    assert!(
        (0..=100).contains(&tel(&decoded).battery_level),
        "Battery should fall back to voltage-derived value"
    );
}

#[test]
fn sub_type_8_parses_control_settings() {
    let decoded = success(decode_extended_frame(8, |frame| {
        frame[50] = 65; // pedal hardness
        frame[52] = 50; // stop speed
        frame[53] = 70; // PWM limit
        frame[55] = 80; // screen backlight
        frame[57] = 1; // transport mode
        frame[58] = 1; // display unit: miles
        frame[59] = 5; // voltage correction +5
        frame[60] = 1; // low voltage mode
        frame[61] = 1; // high speed mode
        frame[63] = 75; // key tone
        frame[64] = 100; // max charge voltage
        frame[65] = 120; // charge voltage base
        frame[66] = 75; // dynamic assist
        frame[68] = 60; // acceleration limit
        frame[69] = 105; // brake pressure alarm
    }));
    let s = vet(&decoded);
    assert_eq!(65, s.pedal_sensitivity);
    assert_eq!(50, s.stop_speed);
    assert_eq!(70, s.pwm_limit);
    assert_eq!(80, s.screen_backlight);
    assert_eq!(Some(true), s.transport_mode);
    assert_eq!(1, s.wheel_display_unit);
    assert_eq!(5, s.voltage_correction);
    assert_eq!(Some(true), s.low_voltage_mode);
    assert_eq!(Some(true), s.high_speed_mode);
    assert_eq!(75, s.key_tone);
    assert_eq!(100, s.max_charge_voltage);
    assert_eq!(120, s.charge_voltage_base);
    assert_eq!(75, s.dynamic_assist);
    assert_eq!(60, s.acceleration_limit);
    assert_eq!(105, s.brake_pressure_alarm);
}

#[test]
fn sub_type_8_voltage_correction_signed_values() {
    for (raw, expected) in [(5i8, 5), (-5, -5), (0, 0)] {
        let decoded = success(decode_extended_frame(8, |frame| {
            frame[59] = raw as u8;
        }));
        assert_eq!(expected, vet(&decoded).voltage_correction, "raw {raw}");
    }
}

#[test]
fn sub_type_8_ignores_unsupported_0x80_fields() {
    let decoded = success(decode_extended_frame(8, |frame| {
        frame[57] = 0x80; // transport mode = not supported
        frame[52] = 0x80; // stop speed
        frame[53] = 0x80; // PWM limit
        frame[55] = 0x80; // backlight
    }));
    let s = vet(&decoded);
    assert_eq!(None, s.transport_mode, "0x80 should leave transport mode unread");
    assert_eq!(-1, s.stop_speed);
    assert_eq!(-1, s.pwm_limit);
    assert_eq!(-1, s.screen_backlight);
}

#[test]
fn sub_type_8_charge_voltage_base_semantics() {
    let zero = success(decode_extended_frame(8, |frame| {
        frame[65] = 0;
    }));
    assert_eq!(145, vet(&zero).charge_voltage_base, "0 maps to default 145");

    let unsupported = success(decode_extended_frame(8, |frame| {
        frame[65] = 0x80;
    }));
    assert_eq!(145, vet(&unsupported).charge_voltage_base, "0x80 keeps default");
}

#[test]
fn sub_type_8_wheel_display_unit_km() {
    let decoded = success(decode_extended_frame(8, |frame| {
        frame[58] = 0;
    }));
    assert_eq!(0, vet(&decoded).wheel_display_unit);
}

#[test]
fn nosfet_sub_type_8_parses_brake_pressure_at_byte_65_only() {
    let decoded = success(decode_extended_frame_full(9686, 43_254, 0x07, 8, |frame| {
        frame[65] = 112;
        frame[66] = 70;
        frame[68] = 80;
        frame[69] = 121;
    }));
    let s = vet(&decoded);
    assert_eq!(112, s.brake_pressure_alarm);
    assert_eq!(145, s.charge_voltage_base, "Nosfet byte 65 must not overwrite charge base");
    assert_eq!(-1, s.dynamic_assist);
    assert_eq!(-1, s.acceleration_limit);
}

// ==================== Commands: old firmware (string protocol) ====================

#[test]
fn old_firmware_string_commands() {
    let mut decoder = VeteranDecoder::new();
    let no_state = |d: &mut VeteranDecoder, cmd: WheelCommand| d.build_command(&cmd, None);

    let beep = no_state(&mut decoder, WheelCommand::Beep);
    assert_eq!(b"b".to_vec(), *send_bytes(&beep[0]));

    let on = no_state(&mut decoder, WheelCommand::SetLight(true));
    assert_eq!(b"SetLightON".to_vec(), *send_bytes(&on[0]));
    let off = no_state(&mut decoder, WheelCommand::SetLight(false));
    assert_eq!(b"SetLightOFF".to_vec(), *send_bytes(&off[0]));

    for (mode, expected) in [(0, "SETh"), (1, "SETm"), (2, "SETs")] {
        let cmds = no_state(&mut decoder, WheelCommand::SetPedalsMode(mode));
        assert_eq!(expected.as_bytes().to_vec(), *send_bytes(&cmds[0]));
    }
    assert!(no_state(&mut decoder, WheelCommand::SetPedalsMode(3)).is_empty());

    let reset_trip = no_state(&mut decoder, WheelCommand::ResetTrip);
    assert_eq!(b"CLEARMETER".to_vec(), *send_bytes(&reset_trip[0]));

    assert!(
        no_state(&mut decoder, WheelCommand::SetMaxSpeed(50)).is_empty(),
        "Unsupported command should return empty list"
    );
}

#[test]
fn old_firmware_returns_empty_for_new_commands() {
    let mut d = decoder_with_ver(1000);
    assert!(d.build(WheelCommand::SetAlarmSpeed { speed: 50, num: 1 }).is_empty());
    assert!(d.build(WheelCommand::SetScreenBacklight(50)).is_empty());
    assert!(d.build(WheelCommand::SetStopSpeed(60)).is_empty());
    assert!(d.build(WheelCommand::SetVeteranPwmLimit(80)).is_empty());
    assert!(d.build(WheelCommand::SetVoltageCorrection(5)).is_empty());
    assert!(d.build(WheelCommand::SetMaxChargeVoltage(100)).is_empty());
    assert!(d.build(WheelCommand::SetBrakePressureAlarm(100)).is_empty());
    assert!(d.build(WheelCommand::SetLateralCutoffAngle(70)).is_empty());
    assert!(d.build(WheelCommand::SetDynamicAssist(50)).is_empty());
    assert!(d.build(WheelCommand::SetAccelerationLimit(50)).is_empty());
    assert!(d.build(WheelCommand::SetWheelDisplayUnit { miles: true }).is_empty());
    assert!(d.build(WheelCommand::Calibrate).is_empty());

    // Old firmware still uses the string format for light
    let cmds = d.build(WheelCommand::SetLight(true));
    assert_eq!(1, cmds.len());
    assert_eq!(b"SetLightON".to_vec(), *send_bytes(&cmds[0]));
}

// ==================== Commands: binary protocol (mVer >= 3) ====================

#[test]
fn beep_command_new_firmware_matches_hardcoded_bytes() {
    let mut d = decoder_with_ver(5000);
    let commands = d.build(WheelCommand::Beep);
    assert_eq!(2, commands.len());
    let lkap = send_bytes(&commands[0]);
    let expected: Vec<u8> = vec![
        0x4C, 0x6B, 0x41, 0x70, 0x0E, 0x00, 0x80, 0x80, 0x80, 0x01, 0xCA, 0x87, 0xE6, 0x6F,
    ];
    assert_eq!(expected, lkap, "LkAp variant must match legacy bytes");
    let ldap = send_bytes(&commands[1]);
    assert_eq!(0x64, ldap[1], "second emit must be LdAp");
    assert_eq!(0x0E, ldap[4]);
}

#[test]
fn beep_command_old_firmware() {
    let mut d = decoder_with_ver(1000);
    let commands = d.build(WheelCommand::Beep);
    assert_eq!(1, commands.len());
    assert_eq!(b"b".to_vec(), *send_bytes(&commands[0]));
}

#[test]
fn set_alarm_speed_command_dual_format() {
    let mut d = decoder_with_ver(5000);
    let commands = d.build(WheelCommand::SetAlarmSpeed { speed: 50, num: 1 });
    assert_eq!(2, commands.len());
    let old = send_bytes(&commands[0]);
    assert_eq!(0x6B, old[1]); // LkAp
    assert_eq!(0x11, old[4]);
    assert_eq!(60, old[12]); // speed + 10
    assert_eq!(17, old.len()); // 13 payload + 4 CRC
    let new = send_bytes(&commands[1]);
    assert_eq!(0x64, new[1]); // LdAp
    assert_eq!(0x11, new[4]);
    assert_eq!(60, new[12]);
}

#[test]
fn set_pedal_tilt_command_dual_format() {
    let mut d = decoder_with_ver(5000);
    let commands = d.build(WheelCommand::SetPedalTilt(0));
    assert_eq!(2, commands.len());
    let old = send_bytes(&commands[0]);
    assert_eq!(0x6B, old[1]);
    assert_eq!(0x10, old[4]);
    assert_eq!(80, old[11]); // angle + 80
    let new = send_bytes(&commands[1]);
    assert_eq!(0x64, new[1]);
    assert_eq!(0x10, new[4]);
    assert_eq!(80, new[11]);
}

#[test]
fn ldap_only_toggle_commands() {
    // (command, cmd byte, value position, expected value)
    let mut d = decoder_with_ver(5000);
    let cases: Vec<(WheelCommand, u8, usize, u8)> = vec![
        (WheelCommand::SetTransportMode(true), 0x16, 17, 1),
        (WheelCommand::SetTransportMode(false), 0x16, 17, 0),
        (WheelCommand::SetHighSpeedMode(true), 0x1A, 21, 1),
        (WheelCommand::SetLowVoltageMode(true), 0x19, 20, 1),
        (WheelCommand::SetKeyTone(75), 0x1C, 23, 75),
        (WheelCommand::SetScreenBacklight(80), 0x14, 15, 80),
        (WheelCommand::SetStopSpeed(60), 0x11, 12, 60),
        (WheelCommand::SetVeteranPwmLimit(80), 0x12, 13, 80),
        (WheelCommand::SetVoltageCorrection(10), 0x18, 19, 10),
        (WheelCommand::SetVoltageCorrection(-10), 0x18, 19, (-10i8) as u8),
        (WheelCommand::SetMaxChargeVoltage(100), 0x1D, 24, 100),
        (WheelCommand::SetBrakePressureAlarm(110), 0x22, 29, 110),
        (WheelCommand::SetDynamicAssist(75), 0x1F, 26, 75),
        (WheelCommand::SetAccelerationLimit(60), 0x21, 28, 60),
        (WheelCommand::SetWheelDisplayUnit { miles: false }, 0x17, 18, 0),
        (WheelCommand::SetWheelDisplayUnit { miles: true }, 0x17, 18, 1),
        (WheelCommand::Calibrate, 0x15, 16, 1),
        (WheelCommand::SetPedalHardness(65), 0x0F, 10, 65),
    ];
    for (command, cmd_byte, position, value) in cases {
        let commands = d.build(command.clone());
        assert_eq!(1, commands.len(), "{command:?}");
        let data = send_bytes(&commands[0]);
        assert_eq!(0x64, data[1], "{command:?} must be LdAp");
        assert_eq!(cmd_byte, data[4], "{command:?} cmd byte");
        assert_eq!(0x02, data[6], "{command:?} byte6 toggle marker");
        assert_eq!(value, data[position], "{command:?} value");
        assert_veteran_crc(data);
    }
}

#[test]
fn nosfet_brake_pressure_uses_command_0x1e_and_position_25() {
    let mut d = decoder_with_nosfet_version();
    let commands = d.build(WheelCommand::SetBrakePressureAlarm(110));
    assert_eq!(1, commands.len());
    let data = send_bytes(&commands[0]);
    assert_eq!(0x1E, data[4]);
    assert_eq!(0x02, data[6]);
    assert_eq!(110, data[25]);
    assert_eq!(30, data.len(), "26-byte payload plus CRC");
}

#[test]
fn nosfet_does_not_expose_dynamic_assist_or_acceleration() {
    let mut d = decoder_with_nosfet_version();
    assert!(d.build(WheelCommand::SetDynamicAssist(75)).is_empty());
    assert!(d.build(WheelCommand::SetAccelerationLimit(75)).is_empty());
    let caps = d.decoder.get_capabilities();
    assert!(!caps.supports(SettingsCommandId::DynamicAssist));
    assert!(!caps.supports(SettingsCommandId::AccelerationLimit));
    assert!(caps.supports(SettingsCommandId::BrakePressureAlarm));
}

#[test]
fn set_lateral_cutoff_angle_dual_format() {
    let mut d = decoder_with_ver(5000);
    let commands = d.build(WheelCommand::SetLateralCutoffAngle(70));
    assert_eq!(2, commands.len());
    let old = send_bytes(&commands[0]);
    assert_eq!(0x6B, old[1]);
    assert_eq!(0x16, old[4]);
    assert_eq!(70, old[17]);
    let new = send_bytes(&commands[1]);
    assert_eq!(0x64, new[1]);
    assert_eq!(0x16, new[4]);
    assert_eq!(70, new[17]);
}

#[test]
fn speaker_volume_returns_empty_for_veteran() {
    let mut d = decoder_with_ver(5000);
    assert!(
        d.build(WheelCommand::SetSpeakerVolume(50)).is_empty(),
        "Veteran has no speaker volume — byte 59 is voltage correction"
    );
}

#[test]
fn new_settings_supported_at_m_ver_3() {
    let mut d = decoder_with_ver(3000);
    assert!(!d.build(WheelCommand::SetDynamicAssist(50)).is_empty());
    assert!(!d.build(WheelCommand::SetAccelerationLimit(50)).is_empty());
    assert!(!d.build(WheelCommand::SetWheelDisplayUnit { miles: false }).is_empty());
}

#[test]
fn power_off_command_dual_format() {
    let mut d = decoder_with_ver(5000);
    let commands = d.build(WheelCommand::PowerOff);
    assert_eq!(2, commands.len());

    let lkap = send_bytes(&commands[0]);
    assert_eq!(0x4C, lkap[0]);
    assert_eq!(0x6B, lkap[1]);
    assert_eq!(0x41, lkap[2]);
    assert_eq!(0x70, lkap[3]);
    assert_eq!(0x16, lkap[4]);
    assert_eq!(22, lkap.len()); // 18 payload + 4 CRC

    let ldap = send_bytes(&commands[1]);
    assert_eq!(0x64, ldap[1]);
    assert_eq!(0x16, ldap[4]);
    assert_eq!(22, ldap.len());

    assert_veteran_crc(lkap);
    assert_veteran_crc(ldap);
}

#[test]
fn reset_trip_command_dual_format_for_new_firmware() {
    let mut d = decoder_with_ver(5000);
    let commands = d.build(WheelCommand::ResetTrip);
    assert_eq!(2, commands.len());
    let lkap = send_bytes(&commands[0]);
    assert_eq!(0x6B, lkap[1]);
    assert_eq!(0x0D, lkap[4]);
    let ldap = send_bytes(&commands[1]);
    assert_eq!(0x64, ldap[1]);
    assert_eq!(0x0D, ldap[4]);
    assert_eq!(0x02, ldap[6]);
}

#[test]
fn set_light_and_pedals_binary_for_new_firmware() {
    let mut d = decoder_with_ver(5000);
    let light = d.build(WheelCommand::SetLight(true));
    assert_eq!(2, light.len());
    let lkap = send_bytes(&light[0]);
    assert_eq!(0x6B, lkap[1]);
    assert_eq!(0x0D, lkap[4]);
    assert_eq!(1, lkap[8]);
    let ldap = send_bytes(&light[1]);
    assert_eq!(0x64, ldap[1]);
    assert_eq!(0x0D, ldap[4]);
    assert_eq!(1, ldap[8]);

    let pedals = d.build(WheelCommand::SetPedalsMode(0)); // hard -> wire 3
    assert_eq!(2, pedals.len());
    let lkap = send_bytes(&pedals[0]);
    assert_eq!(0x6B, lkap[1]);
    assert_eq!(0x0C, lkap[4]);
    assert_eq!(3, lkap[7]);
    let ldap = send_bytes(&pedals[1]);
    assert_eq!(0x64, ldap[1]);
    assert_eq!(0x0C, ldap[4]);
    assert_eq!(3, ldap[7]);
}

// ==================== Lock / Password Commands ====================

#[test]
fn set_veteran_lock_produces_ldap_with_command_0x19() {
    let mut d = decoder_with_ver(5000);
    let commands = d.build(WheelCommand::SetVeteranLock {
        locked: true,
        password: "000000".to_string(),
    });
    assert_eq!(1, commands.len());
    let data = send_bytes(&commands[0]);
    assert_eq!(0x4C, data[0]);
    assert_eq!(0x64, data[1]);
    assert_eq!(0x41, data[2]);
    assert_eq!(0x70, data[3]);
    assert_eq!(0x19, data[4]); // time sync (0x12) + 7
    assert_eq!(0x01, data[17]); // action 1 = lock
    assert_eq!(25, data.len()); // 21 payload + 4 CRC
    assert_veteran_crc(data);

    let unlock = d.build(WheelCommand::SetVeteranLock {
        locked: false,
        password: "000000".to_string(),
    });
    assert_eq!(0x00, send_bytes(&unlock[0])[17]); // action 0 = unlock
}

#[test]
fn set_veteran_lock_encodes_password_and_zeroes_new_pwd_slot() {
    let mut d = decoder_with_ver(5000);
    for locked in [true, false] {
        let commands = d.build(WheelCommand::SetVeteranLock {
            locked,
            password: "123456".to_string(),
        });
        let data = send_bytes(&commands[0]);
        // 123456 = 0x01E240
        assert_eq!(0x01, data[14]);
        assert_eq!(0xE2, data[15]);
        assert_eq!(0x40, data[16]);
        // New-password slot must stay zero
        assert_eq!(0x00, data[18]);
        assert_eq!(0x00, data[19]);
        assert_eq!(0x00, data[20]);
    }
}

#[test]
fn password_management_actions() {
    let mut d = decoder_with_ver(5000);

    // Set: old empty, new = 123456, action 11
    let set = d.build(WheelCommand::SetVeteranPassword {
        new_password: "123456".to_string(),
    });
    let data = send_bytes(&set[0]);
    assert_eq!(0x19, data[4]);
    assert_eq!([0x00, 0x00, 0x00], data[14..17]);
    assert_eq!(11, data[17]);
    assert_eq!([0x01, 0xE2, 0x40], data[18..21]);
    assert_eq!(25, data.len());

    // Modify: old = 111111 (0x01B207), new = 222222 (0x03640E)
    let modify = d.build(WheelCommand::ModifyVeteranPassword {
        old_password: "111111".to_string(),
        new_password: "222222".to_string(),
    });
    let data = send_bytes(&modify[0]);
    assert_eq!([0x01, 0xB2, 0x07], data[14..17]);
    assert_eq!(11, data[17]);
    assert_eq!([0x03, 0x64, 0x0E], data[18..21]);

    // Clear: old = 123456, new empty
    let clear = d.build(WheelCommand::ClearVeteranPassword {
        password: "123456".to_string(),
    });
    let data = send_bytes(&clear[0]);
    assert_eq!([0x01, 0xE2, 0x40], data[14..17]);
    assert_eq!(11, data[17]);
    assert_eq!([0x00, 0x00, 0x00], data[18..21]);

    // Auto-lock: action 3 on / 2 off
    let on = d.build(WheelCommand::SetVeteranAutoLock {
        enabled: true,
        password: "123456".to_string(),
    });
    assert_eq!(3, send_bytes(&on[0])[17]);
    let off = d.build(WheelCommand::SetVeteranAutoLock {
        enabled: false,
        password: "123456".to_string(),
    });
    assert_eq!(2, send_bytes(&off[0])[17]);

    // All password commands are 25 bytes with a valid CRC
    for cmds in [set, modify, clear, on, off] {
        let data = send_bytes(&cmds[0]);
        assert_eq!(25, data.len());
        assert_veteran_crc(data);
    }
}

// ==================== Init / Keep-Alive ====================

#[test]
fn init_commands_empty_and_no_keep_alive() {
    let decoder = VeteranDecoder::new();
    assert!(decoder.get_init_commands().is_empty());
    assert!(decoder.get_keep_alive_command().is_none());
}

#[test]
fn split_frame_across_two_ble_notifications_decodes() {
    let mut decoder = VeteranDecoder::new();
    let part1 = hex_to_bytes("DC5A5C2025D600003BF500003BF50000FFDE1399");
    let part2 = hex_to_bytes("0DEF0000024602460000000000000000");

    let ds = match decoder.decode(&part1, &DecoderState::default(), &config()) {
        DecodeResult::Success(d) => merged(&d, &DecoderState::default()),
        _ => DecoderState::default(),
    };
    let d2 = success(decoder.decode(&part2, &ds, &config()));
    assert_eq!(9686, tel(&d2).voltage);
}

// ==================== Time Sync ====================

fn is_time_sync(cmd: &WheelCommand) -> bool {
    let data = match cmd {
        WheelCommand::SendBytes(d) => d,
        WheelCommand::SendDelayed(d, _) => d,
        _ => return false,
    };
    data.len() >= 7 && data[0] == 0x4C && data[1] == 0x64 && data[4] == 0x12 && data[6] == 0x05
}

#[test]
fn first_frame_emits_time_sync_commands() {
    let mut decoder = VeteranDecoder::new();
    let frame = build_veteran_frame(&VetFrame {
        ver: 5000,
        ..Default::default()
    });
    let decoded = success(decoder.decode(&frame, &DecoderState::default(), &config()));
    let sync: Vec<&WheelCommand> = decoded.commands.iter().filter(|c| is_time_sync(c)).collect();
    assert_eq!(2, sync.len(), "2 time sync commands on first frame");
    match sync[1] {
        WheelCommand::SendDelayed(_, delay) => assert_eq!(2000, *delay),
        other => panic!("second sync should be delayed, got {other:?}"),
    }

    // Second frame: none
    let second = success(decoder.decode(&frame, &DecoderState::default(), &config()));
    assert!(second.commands.iter().filter(|c| is_time_sync(c)).count() == 0);
}

#[test]
fn m_ver_below_3_also_emits_time_sync() {
    // The official app calls syncTime() on every heartbeat, no mVer gate.
    let mut decoder = VeteranDecoder::new();
    let frame = build_veteran_frame(&VetFrame {
        ver: 1000,
        ..Default::default()
    });
    let decoded = success(decoder.decode(&frame, &DecoderState::default(), &config()));
    assert_eq!(2, decoded.commands.iter().filter(|c| is_time_sync(c)).count());
}

#[test]
fn reset_clears_time_sync_state_for_re_emission() {
    let mut decoder = VeteranDecoder::new();
    let frame = build_veteran_frame(&VetFrame {
        ver: 5000,
        ..Default::default()
    });
    decoder.decode(&frame, &DecoderState::default(), &config());
    decoder.reset();
    let decoded = success(decoder.decode(&frame, &DecoderState::default(), &config()));
    assert_eq!(2, decoded.commands.iter().filter(|c| is_time_sync(c)).count());
}

// ==================== SOC Table Lookup ====================

#[test]
fn lookup_soc_semantics() {
    assert_eq!(0, lookup_soc(7000, &soc_tables::SHERMAN_100V));
    assert_eq!(100, lookup_soc(9900, &soc_tables::SHERMAN_100V));
    assert_eq!(100, lookup_soc(10000, &soc_tables::SHERMAN_100V));
    // Exact entries
    assert_eq!(0, lookup_soc(7560, &soc_tables::SHERMAN_100V));
    assert_eq!(50, lookup_soc(8837, &soc_tables::SHERMAN_100V));
    // Ceiling between entries: table[49]=8820, table[50]=8837
    assert_eq!(50, lookup_soc(8821, &soc_tables::SHERMAN_100V));
    assert_eq!(50, lookup_soc(8828, &soc_tables::SHERMAN_100V));
    assert_eq!(50, lookup_soc(8829, &soc_tables::SHERMAN_100V));
}

#[test]
fn soc_tables_have_100_monotonic_entries() {
    for table in [
        &soc_tables::SHERMAN_100V,
        &soc_tables::PATTON_126V,
        &soc_tables::LYNX_151V,
    ] {
        assert_eq!(100, table.len());
        for i in 1..table.len() {
            assert!(table[i] > table[i - 1], "not monotonic at index {i}");
        }
    }
}

// ==================== Event Log Parsing ====================

#[test]
fn parse_log_basic_extracts_2_entries() {
    let decoder = VeteranDecoder::new();
    let mut buff = vec![0u8; 62];
    buff[50] = 5; // index
    buff[51] = 0x01;
    buff[52] = 0x23; // content = 0x0123
    buff[53] = 0x04;
    buff[54] = 0x56; // second content = 0x0456

    let entries = decoder.parse_log_entries(&buff, 0);
    assert_eq!(2, entries.len());
    assert_eq!(5, entries[0].index);
    assert_eq!(0x0123, entries[0].content_code);
    assert_eq!(6, entries[1].index);
    assert_eq!(0x0456, entries[1].content_code);
}

#[test]
fn parse_log_basic_single_entry_at_index_255() {
    let decoder = VeteranDecoder::new();
    let mut buff = vec![0u8; 62];
    buff[50] = 0xFF;
    buff[51] = 0x00;
    buff[52] = 0x42;

    let entries = decoder.parse_log_entries(&buff, 4);
    assert_eq!(1, entries.len());
    assert_eq!(255, entries[0].index);
    assert_eq!(0x0042, entries[0].content_code);
}

#[test]
fn parse_log_basic_empty_for_short_buffer() {
    let decoder = VeteranDecoder::new();
    let buff = vec![0u8; 55];
    assert!(decoder.parse_log_entries(&buff, 0).is_empty());
}

#[test]
fn parse_log_extended_extracts_3_entries() {
    let decoder = VeteranDecoder::new();
    let mut buff = vec![0u8; 90];
    buff[47] = 0;
    buff[48] = 0x00;
    buff[49] = 0x01;
    buff[54] = 0x0A;
    buff[55] = 0x0B;
    buff[56] = 0x0C;
    buff[57] = 0x0D;
    buff[58] = 0x0E;
    buff[59] = 0x00;
    buff[60] = 0x02;
    buff[65] = 0x1A;
    buff[70] = 0x00;
    buff[71] = 0x03;
    buff[76] = 0x2A;

    let entries = decoder.parse_log_entries(&buff, 32);
    assert_eq!(3, entries.len());
    assert_eq!(0, entries[0].index);
    assert_eq!(1, entries[0].content_code);
    assert_eq!(0x0A, entries[0].extra_bytes[0]);
    assert_eq!(1, entries[1].index);
    assert_eq!(2, entries[1].content_code);
    assert_eq!(2, entries[2].index);
    assert_eq!(3, entries[2].content_code);
}

#[test]
fn parse_log_detailed_extracts_packed_count_and_index() {
    let decoder = VeteranDecoder::new();
    let mut buff = vec![0u8; 65];
    // totalLogNum = 16, index = 3: b47=1, b48=0x00, b49=3
    buff[47] = 1;
    buff[48] = 0x00;
    buff[49] = 3;
    buff[50] = 0x67;
    buff[51] = 0x89;
    buff[52] = 0xAB;
    buff[53] = 0xCD;
    buff[54] = 0x01;
    buff[55] = 0x00; // content = 256
    buff[56] = 0; // no extras

    let entries = decoder.parse_log_entries(&buff, 33);
    assert_eq!(1, entries.len());
    assert_eq!(3, entries[0].index);
    assert_eq!(16, entries[0].total_count);
    assert_eq!(256, entries[0].content_code);
    assert_eq!(0x6789ABCD, entries[0].timestamp);
    assert!(entries[0].extras.is_empty());
}

#[test]
fn parse_log_detailed_parses_extras_as_signed() {
    let decoder = VeteranDecoder::new();
    let mut buff = vec![0u8; 70];
    buff[47] = 0;
    buff[48] = 0x10;
    buff[49] = 0; // total=1, index=0
    buff[54] = 0;
    buff[55] = 1; // content = 1
    buff[56] = 2; // 2 extras
    buff[57] = 0;
    buff[58] = 0;
    buff[59] = 0;
    buff[60] = 100; // +100
    buff[61] = 0xFF;
    buff[62] = 0xFF;
    buff[63] = 0xFF;
    buff[64] = 0xFF; // -1

    let entries = decoder.parse_log_entries(&buff, 33);
    assert_eq!(1, entries.len());
    assert_eq!(vec![100i64, -1], entries[0].extras);
}

#[test]
fn unknown_p_num_returns_empty_log_entries() {
    let decoder = VeteranDecoder::new();
    let buff = vec![0u8; 90];
    assert!(decoder.parse_log_entries(&buff, 99).is_empty());
}

#[test]
fn request_event_log_produces_dual_format_request() {
    let mut decoder = VeteranDecoder::new();
    let commands = decoder.build_command(&WheelCommand::RequestEventLog, None);
    assert_eq!(2, commands.len());
    let old = send_bytes(&commands[0]);
    let new = send_bytes(&commands[1]);
    assert_eq!(0x6B, old[1]);
    assert_eq!(0x14, old[4]);
    assert_eq!(0x64, new[1]);
    assert_eq!(0x14, new[4]);
    assert_eq!(20, old.len(), "16-byte payload plus CRC");
    assert_eq!(20, new.len());
    assert_veteran_crc(old);
    assert_veteran_crc(new);
}

// ==================== Unpacker Stats ====================

fn feed_bytes(unpacker: &mut VeteranUnpacker, bytes: &[u8]) -> bool {
    let mut completed = false;
    for &b in bytes {
        if unpacker.add_char(b as i32) {
            completed = true;
        }
    }
    completed
}

fn build_valid_unpacker_frame(payload_len: usize) -> Vec<u8> {
    let mut frame = vec![0u8; payload_len + 4];
    frame[0] = 0xDC;
    frame[1] = 0x5A;
    frame[2] = 0x5C;
    frame[3] = payload_len as u8;
    frame
}

#[test]
fn unpacker_stats_lifecycle() {
    let mut unpacker = VeteranUnpacker::default();
    assert_eq!(0, unpacker.stats().error_resets);
    assert_eq!(0, unpacker.stats().bytes_discarded);

    // Valid legacy frame does not increment
    feed_bytes(&mut unpacker, &build_valid_unpacker_frame(36));
    assert_eq!(0, unpacker.stats().error_resets);
}

#[test]
fn unpacker_legacy_sentinel_rejections() {
    for (index, value) in [(22usize, 0x01u8), (23, 0x04), (30, 0x08)] {
        let mut unpacker = VeteranUnpacker::default();
        let mut frame = build_valid_unpacker_frame(36);
        frame[index] = value;
        assert!(!feed_bytes(&mut unpacker, &frame), "byte {index}={value:#x}");
        assert_eq!(1, unpacker.stats().error_resets, "byte {index}");
    }
}

#[test]
fn unpacker_crc_mismatch_increments_counters() {
    let mut unpacker = VeteranUnpacker::default();
    // len > 38 triggers CRC check; zeros are a wrong CRC
    let frame = build_valid_unpacker_frame(42);
    assert!(!feed_bytes(&mut unpacker, &frame));
    assert_eq!(1, unpacker.stats().error_resets);
    assert!(unpacker.stats().bytes_discarded > 0);
}

#[test]
fn unpacker_stats_persist_across_reset_and_clear_on_reset_stats() {
    let mut unpacker = VeteranUnpacker::default();
    let frame = build_valid_unpacker_frame(42);

    feed_bytes(&mut unpacker, &frame);
    unpacker.reset();
    assert_eq!(1, unpacker.stats().error_resets, "persist across reset()");

    feed_bytes(&mut unpacker, &frame);
    assert_eq!(2, unpacker.stats().error_resets, "errors accumulate");

    unpacker.reset_stats();
    assert_eq!(0, unpacker.stats().error_resets);
    assert_eq!(0, unpacker.stats().bytes_discarded);
}
