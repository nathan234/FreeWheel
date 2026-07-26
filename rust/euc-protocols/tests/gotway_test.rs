//! Port of `GotwayDecoderTest.kt` — the behavioral parity oracle.
//!
//! Test data and expected values are copied verbatim from the Kotlin test
//! suite (which itself inherited them from the legacy Android adapter tests
//! and real BLE captures). Cross-decoder tests (Veteran/Kingsong/Lorin) are
//! not ported — they exercise other decoders.

use euc_protocols::byte_utils::{hex_to_bytes, KM_TO_MILES_MULTIPLIER};
use euc_protocols::types::SettingsCommandId;
use euc_protocols::{
    BegodeSettings, DecodeResult, DecodedData, DecoderConfig, DecoderState, GotwayDecoder,
    WheelCommand, WheelSettings,
};

// ==================== Helpers ====================

/// Test fixtures were captured against a 16S profile (no voltage scaling).
fn config() -> DecoderConfig {
    DecoderConfig {
        use_custom_percents: false,
        gotway_voltage: 0,
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

fn beg(data: &DecodedData) -> &BegodeSettings {
    match data.settings.as_ref().expect("settings expected") {
        WheelSettings::Begode(s) => s,
        other => panic!("expected Begode settings, got {other:?}"),
    }
}

fn bms(data: &DecodedData) -> &euc_protocols::BmsState {
    data.bms.as_ref().expect("bms expected")
}

/// Port of the Kotlin test helper `DecodedData.decoderStateFrom(ds)`.
fn merged(data: &DecodedData, ds: &DecoderState) -> DecoderState {
    DecoderState {
        telemetry: data.telemetry.clone().unwrap_or_else(|| ds.telemetry.clone()),
        identity: data.identity.clone().unwrap_or_else(|| ds.identity.clone()),
        bms: data.bms.clone().unwrap_or_else(|| ds.bms.clone()),
        settings: data.settings.clone().unwrap_or_else(|| ds.settings.clone()),
    }
}

fn apply(decoder: &mut GotwayDecoder, bytes: &[u8], ds: &mut DecoderState, cfg: &DecoderConfig) {
    if let DecodeResult::Success(data) = decoder.decode(bytes, ds, cfg) {
        *ds = merged(&data, ds);
    }
}

fn short_be(value: i32) -> [u8; 2] {
    [((value >> 8) & 0xFF) as u8, (value & 0xFF) as u8]
}

/// Send a firmware response to put the decoder in Begode mode.
fn init_decoder(decoder: &mut GotwayDecoder) {
    decoder.decode(b"GW1.23", &DecoderState::default(), &config());
}

#[derive(Clone)]
struct LiveFrame {
    voltage: i32,
    speed: i32,
    distance: i32,
    beeper_volume: i32,
    phase_current: i32,
    status_word: i32,
}

impl Default for LiveFrame {
    fn default() -> Self {
        LiveFrame {
            voltage: 6000,
            speed: 0,
            distance: 0,
            beeper_volume: 0,
            phase_current: 0,
            status_word: 0,
        }
    }
}

fn build_live_data_frame(f: LiveFrame) -> Vec<u8> {
    let mut out = vec![0x55, 0xAA];
    out.extend_from_slice(&short_be(f.voltage));
    out.extend_from_slice(&short_be(f.speed));
    out.extend_from_slice(&[0, 0]);
    out.extend_from_slice(&short_be(f.distance));
    out.extend_from_slice(&short_be(f.phase_current));
    out.extend_from_slice(&short_be(99)); // temperature
    out.extend_from_slice(&short_be(f.status_word));
    out.extend_from_slice(&[0, f.beeper_volume as u8, 0, 0x18, 0x5A, 0x5A, 0x5A, 0x5A]);
    out
}

#[derive(Clone, Default)]
struct SettingsFrame {
    total_distance: i64,
    in_miles: bool,
    pedals_mode: i32,
    speed_alarms: i32,
    roll_angle: i32,
    tilt_back_speed: i32,
    led_mode: i32,
    light_mode: i32,
}

fn build_settings_frame(f: SettingsFrame) -> Vec<u8> {
    let mut out = vec![0x55, 0xAA];
    out.extend_from_slice(&[
        ((f.total_distance >> 24) & 0xFF) as u8,
        ((f.total_distance >> 16) & 0xFF) as u8,
        ((f.total_distance >> 8) & 0xFF) as u8,
        (f.total_distance & 0xFF) as u8,
    ]);
    let settings = (f.pedals_mode << 13)
        | (f.speed_alarms << 10)
        | (f.roll_angle << 7)
        | i32::from(f.in_miles);
    out.extend_from_slice(&short_be(settings)); // offset 6-7
    out.extend_from_slice(&short_be(0)); // powerOffTime (offset 8-9)
    out.extend_from_slice(&short_be(f.tilt_back_speed)); // offset 10-11
    out.push(0); // byte 12
    out.push(f.led_mode as u8); // byte 13
    out.push(0); // byte 14 (alert)
    out.push(f.light_mode as u8); // byte 15
    out.extend_from_slice(&[0, 0, 0x04, 0x18, 0x5A, 0x5A, 0x5A, 0x5A]);
    out
}

#[derive(Clone, Default)]
struct ExtFrame {
    bat_voltage: i32,
    context: i32,
    bms_current: i32,
    temp1: i32,
    temp2: i32,
    semi_voltage: i32,
}

fn build_extended_frame(f: ExtFrame) -> Vec<u8> {
    let mut payload = [0u8; 18];
    payload[4] = ((f.bat_voltage >> 8) & 0xFF) as u8;
    payload[5] = (f.bat_voltage & 0xFF) as u8;
    payload[6] = ((f.bms_current >> 8) & 0xFF) as u8;
    payload[7] = (f.bms_current & 0xFF) as u8;
    payload[8] = ((f.temp1 >> 8) & 0xFF) as u8;
    payload[9] = (f.temp1 & 0xFF) as u8;
    payload[10] = ((f.temp2 >> 8) & 0xFF) as u8;
    payload[11] = (f.temp2 & 0xFF) as u8;
    payload[12] = ((f.semi_voltage >> 8) & 0xFF) as u8;
    payload[13] = (f.semi_voltage & 0xFF) as u8;
    payload[16] = 0x01; // frame type at byte 18
    payload[17] = f.context as u8;
    let mut out = vec![0x55, 0xAA];
    out.extend_from_slice(&payload);
    out.extend_from_slice(&[0x5A, 0x5A, 0x5A, 0x5A]);
    out
}

#[derive(Clone, Default)]
struct CtFrame {
    battery_current: i32,
    motor_temp: i32,
    hw_pwm: i32,
    cutout_step: i32,
}

fn build_current_temp_frame(f: CtFrame) -> Vec<u8> {
    let mut payload = [0u8; 18];
    payload[0] = ((f.battery_current >> 8) & 0xFF) as u8;
    payload[1] = (f.battery_current & 0xFF) as u8;
    payload[2] = ((f.cutout_step >> 8) & 0xFF) as u8;
    payload[3] = (f.cutout_step & 0xFF) as u8;
    payload[4] = ((f.motor_temp >> 8) & 0xFF) as u8;
    payload[5] = (f.motor_temp & 0xFF) as u8;
    payload[6] = ((f.hw_pwm >> 8) & 0xFF) as u8;
    payload[7] = (f.hw_pwm & 0xFF) as u8;
    payload[16] = 0x07;
    payload[17] = 0x18;
    let mut out = vec![0x55, 0xAA];
    out.extend_from_slice(&payload);
    out.extend_from_slice(&[0x5A, 0x5A, 0x5A, 0x5A]);
    out
}

fn build_alexovik_live_frame(voltage: i32, has_battery_current: bool, battery_current: i32) -> Vec<u8> {
    let mut payload = [0u8; 18];
    payload[0] = ((voltage >> 8) & 0xFF) as u8;
    payload[1] = (voltage & 0xFF) as u8;
    if has_battery_current {
        payload[5] = 0x01;
        payload[6] = ((battery_current >> 8) & 0xFF) as u8;
        payload[7] = (battery_current & 0xFF) as u8;
    }
    payload[16] = 0x00;
    payload[17] = 0x18;
    let mut out = vec![0x55, 0xAA];
    out.extend_from_slice(&payload);
    out.extend_from_slice(&[0x5A, 0x5A, 0x5A, 0x5A]);
    out
}

fn build_firmware_settings_frame(cutout_angle_raw: i32) -> Vec<u8> {
    let mut payload = [0u8; 18];
    payload[3] = cutout_angle_raw as u8;
    payload[16] = 0xFF;
    payload[17] = 0x18;
    let mut out = vec![0x55, 0xAA];
    out.extend_from_slice(&payload);
    out.extend_from_slice(&[0x5A, 0x5A, 0x5A, 0x5A]);
    out
}

fn build_bms_cell_frame(frame_type: u8, p_num: u8, cells: &[i32]) -> Vec<u8> {
    let mut frame = vec![0u8; 24];
    frame[0] = 0x55;
    frame[1] = 0xAA;
    for (i, cell) in cells.iter().take(8).enumerate() {
        let offset = 2 + i * 2;
        frame[offset] = ((cell >> 8) & 0xFF) as u8;
        frame[offset + 1] = (cell & 0xFF) as u8;
    }
    frame[18] = frame_type;
    frame[19] = p_num;
    frame[20] = 0x5A;
    frame[21] = 0x5A;
    frame[22] = 0x5A;
    frame[23] = 0x5A;
    frame
}

fn decode_normal_data(voltage: i32, cfg: &DecoderConfig) -> DecodedData {
    let mut decoder = GotwayDecoder::new();
    let mut bytes = vec![0x55, 0xAA];
    bytes.extend_from_slice(&short_be(voltage));
    bytes.extend_from_slice(&short_be(0)); // speed
    bytes.extend_from_slice(&[0, 0]);
    bytes.extend_from_slice(&short_be(0)); // distance
    bytes.extend_from_slice(&short_be(0)); // phaseCurrent
    bytes.extend_from_slice(&short_be(99)); // temperature
    bytes.extend_from_slice(&[14, 15, 16, 17, 0, 0x18, 0x5A, 0x5A, 0x5A, 0x5A]);
    success(decoder.decode(&bytes, &DecoderState::default(), cfg))
}

fn send_bytes_str(command: &WheelCommand) -> String {
    match command {
        WheelCommand::SendBytes(data) => String::from_utf8(data.clone()).unwrap(),
        other => panic!("expected SendBytes, got {other:?}"),
    }
}

fn send_delayed(command: &WheelCommand) -> (String, u64) {
    match command {
        WheelCommand::SendDelayed(data, delay) => {
            (String::from_utf8(data.clone()).unwrap(), *delay)
        }
        other => panic!("expected SendDelayed, got {other:?}"),
    }
}

// ==================== Basic Decode ====================

#[test]
fn decode_with_corrupted_data_returns_buffering() {
    let mut decoder = GotwayDecoder::new();
    let mut bytes: Vec<u8> = Vec::new();
    for i in 0..30u8 {
        bytes.push(i);
        let result = decoder.decode(&bytes, &DecoderState::default(), &config());
        assert_eq!(
            result,
            DecodeResult::Buffering,
            "Should return Buffering for corrupted data of size {}",
            i + 1
        );
    }
}

#[test]
fn decode_with_normal_data() {
    let mut decoder = GotwayDecoder::new();
    let mut bytes = vec![0x55, 0xAA];
    bytes.extend_from_slice(&short_be(6000)); // voltage
    bytes.extend_from_slice(&short_be(-1111)); // speed
    bytes.extend_from_slice(&[0, 0]);
    bytes.extend_from_slice(&short_be(3231)); // distance
    bytes.extend_from_slice(&short_be(-8322)); // phase current
    bytes.extend_from_slice(&short_be(99)); // temperature
    bytes.extend_from_slice(&[14, 15, 16, 17, 0, 0x18, 0x5A, 0x5A, 0x5A, 0x5A]);

    let decoded = success(decoder.decode(&bytes, &DecoderState::default(), &config()));
    assert!(decoded.has_new_data);

    // Default gotway_negative=0 takes abs: abs(round(-1111 * 3.6)) = 4000
    assert_eq!(4000, tel(&decoded).speed);
    // MPU6050: (99/340 + 36.53) * 100 = 3682
    assert_eq!(3682, tel(&decoded).temperature);
    assert_eq!(8322, tel(&decoded).phase_current);
    assert_eq!(6000, tel(&decoded).voltage);
    assert_eq!(3231, tel(&decoded).wheel_distance);
    // Standard percent: (6000 - 5290) / 13 = 54
    assert_eq!(54, tel(&decoded).battery_level);
}

#[test]
fn decode_with_2020_board_data() {
    let mut decoder = GotwayDecoder::new();
    let b1 = hex_to_bytes("55AA19C1000000000000008CF0000001FFF80018");
    let b2 = hex_to_bytes("5A5A5A5A55AA000060D248001C20006400010007");
    let b3 = hex_to_bytes("000804185A5A5A5A");

    let mut ds = DecoderState::default();
    apply(&mut decoder, &b1, &mut ds, &config());
    let r2 = decoder.decode(&b2, &ds, &config());
    let d2 = success(r2);
    ds = merged(&d2, &ds);
    apply(&mut decoder, &b3, &mut ds, &config());

    assert_eq!(0, ds.telemetry.speed.abs());
    assert_eq!(6593, ds.telemetry.voltage); // 65.93V
    assert_eq!(24786, ds.telemetry.total_distance);
    assert_eq!(100, ds.telemetry.battery_level);
}

#[test]
fn decode_with_new_board_data() {
    let mut decoder = GotwayDecoder::new();
    let packets = [
        hex_to_bytes("55aa17750538007602eefb64f494148100090018"),
        hex_to_bytes("5a5a5a5a55aa0032000004b10000000013880000"),
        hex_to_bytes("000001005a5a5a5a55aa00000000000000000000"),
        hex_to_bytes("00000000000003005a5a5a5a55aa003c278c4900"),
        hex_to_bytes("1c2000c800000000001204185a5a5a5a55aa022c"),
        hex_to_bytes("000000000000000000000000000007185a5a5a5a"),
    ];

    let mut ds = DecoderState::default();
    for _pass in 0..2 {
        for packet in &packets {
            apply(&mut decoder, packet, &mut ds, &config());
        }
    }

    assert!(ds.telemetry.speed.abs() > 0, "Speed should be non-zero");
    assert!(
        ds.telemetry.voltage > 11000 && ds.telemetry.voltage < 13000,
        "Voltage should be ~120V, got {}",
        ds.telemetry.voltage
    );
    assert!(ds.telemetry.total_distance > 0, "Total distance should be set");
    assert!(
        (0..=100).contains(&ds.telemetry.battery_level),
        "Battery should be 0-100%"
    );
}

// ==================== Voltage Scaling ====================

#[test]
fn voltage_scaling_table() {
    // (gotway_voltage config, expected scaled voltage for raw 6000)
    let cases = [
        (0, 6000),   // 67.2V → 1x
        (1, 7500),   // 84V → 1.25x
        (2, 9000),   // 100.8V → 1.5x
        (3, 10429),  // 126V → 1.738...x
        (4, 12000),  // 134.4V → 2x
        (5, 15000),  // 168V → 2.5x
        (6, 13500),  // 151V → 2.25x
        (7, 3750),   // 42V → 0.625x
        (8, 18750),  // 210V → 3.125x
        (99, 6000),  // unknown → 1x fallback
    ];
    for (setting, expected) in cases {
        let cfg = DecoderConfig {
            gotway_voltage: setting,
            ..config()
        };
        let decoded = decode_normal_data(6000, &cfg);
        assert_eq!(
            expected,
            tel(&decoded).voltage,
            "gotway_voltage={setting}"
        );
    }
}

#[test]
fn auto_voltage_uses_commander_max_model_profile() {
    let mut decoder = GotwayDecoder::new();
    decoder.decode(b"NAME:MAX", &DecoderState::default(), &config());

    let cfg = DecoderConfig {
        gotway_voltage: -1,
        ..config()
    };
    let decoded = success(decoder.decode(
        &build_live_data_frame(LiveFrame::default()),
        &DecoderState::default(),
        &cfg,
    ));

    assert_eq!(15000, tel(&decoded).voltage);
    assert_eq!("Commander Max", idn(&decoded).model);
    assert_eq!("Extreme Bull", idn(&decoded).brand);
}

#[test]
fn manual_voltage_selection_overrides_matched_model_profile() {
    let mut decoder = GotwayDecoder::new();
    decoder.decode(b"NAME:MAX", &DecoderState::default(), &config());

    let cfg = DecoderConfig {
        gotway_voltage: 1,
        ..config()
    };
    let decoded = success(decoder.decode(
        &build_live_data_frame(LiveFrame::default()),
        &DecoderState::default(),
        &cfg,
    ));
    assert_eq!(7500, tel(&decoded).voltage);
}

#[test]
fn auto_voltage_keeps_legacy_84v_fallback_for_unknown_model() {
    let mut decoder = GotwayDecoder::new();
    let cfg = DecoderConfig {
        gotway_voltage: -1,
        ..config()
    };
    let decoded = success(decoder.decode(
        &build_live_data_frame(LiveFrame::default()),
        &DecoderState::default(),
        &cfg,
    ));
    assert_eq!(7500, tel(&decoded).voltage);
}

// ==================== Gotway Model Field ====================

#[test]
fn live_data_frame_does_not_set_model_when_name_not_received() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let decoded = success(decoder.decode(
        &build_live_data_frame(LiveFrame::default()),
        &DecoderState::default(),
        &config(),
    ));
    assert_eq!(
        "",
        idn(&decoded).model,
        "model should remain empty before NAME response"
    );
}

#[test]
fn name_response_sets_model_correctly() {
    let mut decoder = GotwayDecoder::new();
    let decoded = success(decoder.decode(b"NAME MCM5", &DecoderState::default(), &config()));
    assert_eq!("MCM5", idn(&decoded).model);
}

#[test]
fn model_persists_across_subsequent_frames_after_name() {
    let mut decoder = GotwayDecoder::new();
    let mut ds = DecoderState::default();

    apply(&mut decoder, b"GW1.23", &mut ds, &config());

    let d2 = success(decoder.decode(b"NAME MCM5", &ds, &config()));
    assert_eq!("MCM5", idn(&d2).model);
    ds = merged(&d2, &ds);

    let d3 = success(decoder.decode(&build_live_data_frame(LiveFrame::default()), &ds, &config()));
    let identity3 = merged(&d3, &ds).identity;
    assert_eq!("MCM5", identity3.model, "model should persist after NAME response");
}

// ==================== Firmware Brand ====================

#[test]
fn jn_firmware_sets_extreme_bull_brand() {
    let mut decoder = GotwayDecoder::new();
    let mut ds = DecoderState::default();

    let d1 = success(decoder.decode(b"JN2.05", &ds, &config()));
    ds = merged(&d1, &ds);
    assert_eq!("Extreme Bull", ds.identity.brand);

    let d2 = success(decoder.decode(b"NAME Commander Max", &ds, &config()));
    ds = merged(&d2, &ds);
    assert_eq!("Commander Max", ds.identity.model);
    assert_eq!("Extreme Bull", ds.identity.brand);
    assert_eq!("Extreme Bull Commander Max", ds.identity.display_name());
}

#[test]
fn gw_firmware_sets_begode_brand() {
    let mut decoder = GotwayDecoder::new();
    let decoded = success(decoder.decode(b"GW1.23", &DecoderState::default(), &config()));
    assert_eq!("Begode", idn(&decoded).brand);
}

#[test]
fn jl_firmware_resolves_as_begode() {
    let mut decoder = GotwayDecoder::new();
    let decoded = success(decoder.decode(b"JL2035101", &DecoderState::default(), &config()));
    assert_eq!("Begode", idn(&decoded).brand);
    assert_eq!("2035101", idn(&decoded).version);
}

#[test]
fn known_name_resolves_catalog_brand_before_firmware_arrives() {
    let mut decoder = GotwayDecoder::new();
    let mut ds = DecoderState::default();

    let d1 = success(decoder.decode(b"NAME Rocket", &ds, &config()));
    ds = merged(&d1, &ds);
    assert_eq!("Rocket", ds.identity.model);
    assert_eq!("Extreme Bull", ds.identity.brand);
    assert_eq!("Extreme Bull Rocket", ds.identity.display_name());
}

// ==================== Miles Normalization ====================

fn round_i32(x: f64) -> i64 {
    (x + 0.5).floor() as i64
}

#[test]
fn speed_is_normalized_to_kmh_when_wheel_reports_in_miles() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    let d1 = success(decoder.decode(
        &build_settings_frame(SettingsFrame {
            in_miles: true,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert!(beg(&d1).in_miles, "in_miles should be set after settings frame");
    ds = merged(&d1, &ds);

    // 778 * 3.6 = 2800.8 → 2801 (1/100 mph); normalized: 2801 / 0.62137 ≈ 4508
    let d2 = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            speed: 778,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    let expected_kmh = round_i32(2801.0 / KM_TO_MILES_MULTIPLIER) as i32;
    assert_eq!(expected_kmh, tel(&d2).speed);
    assert!((tel(&d2).speed_mph() - 28.0).abs() < 0.5);
}

#[test]
fn speed_is_not_normalized_when_wheel_reports_in_km() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    let d1 = success(decoder.decode(
        &build_settings_frame(SettingsFrame::default()),
        &ds,
        &config(),
    ));
    assert!(!beg(&d1).in_miles);
    ds = merged(&d1, &ds);

    let d2 = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            speed: 778,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert_eq!(2801, tel(&d2).speed);
    assert!((tel(&d2).speed_kmh() - 28.01).abs() < 0.01);
}

#[test]
fn wheel_distance_is_normalized_when_wheel_reports_in_miles() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    let d1 = success(decoder.decode(
        &build_settings_frame(SettingsFrame {
            in_miles: true,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    ds = merged(&d1, &ds);

    let d2 = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            distance: 1000,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    let expected = round_i32(1000.0 / KM_TO_MILES_MULTIPLIER);
    assert_eq!(expected, tel(&d2).wheel_distance);
}

#[test]
fn total_distance_is_normalized_when_wheel_reports_in_miles() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let d1 = success(decoder.decode(
        &build_settings_frame(SettingsFrame {
            total_distance: 5_000_000,
            in_miles: true,
            ..Default::default()
        }),
        &DecoderState::default(),
        &config(),
    ));
    let expected = round_i32(5_000_000.0 / KM_TO_MILES_MULTIPLIER);
    assert_eq!(expected, tel(&d1).total_distance);
}

#[test]
fn total_distance_is_not_normalized_when_wheel_reports_in_km() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let d1 = success(decoder.decode(
        &build_settings_frame(SettingsFrame {
            total_distance: 5_000_000,
            ..Default::default()
        }),
        &DecoderState::default(),
        &config(),
    ));
    assert_eq!(5_000_000, tel(&d1).total_distance);
}

#[test]
fn speed_not_normalized_before_settings_frame_arrives() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let decoded = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            speed: 778,
            ..Default::default()
        }),
        &DecoderState::default(),
        &config(),
    ));
    assert!(!beg(&decoded).in_miles);
    assert_eq!(2801, tel(&decoded).speed);
}

#[test]
fn full_roundtrip_mph_wheel_speed_displays_correctly() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    let d1 = success(decoder.decode(
        &build_settings_frame(SettingsFrame {
            total_distance: 1_000_000,
            in_miles: true,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    ds = merged(&d1, &ds);

    let d2 = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            speed: 778,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert!((tel(&d2).speed_mph() - 28.0).abs() < 0.5);
    assert!((tel(&d2).speed_kmh() - 45.0).abs() < 1.0);
}

// ==================== 0x04 Settings Readback ====================

#[test]
fn settings_frame_decodes_pedals_mode_matching_begode_strong() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);
    let decoded = success(decoder.decode(
        &build_settings_frame(SettingsFrame {
            pedals_mode: 2,
            ..Default::default()
        }),
        &DecoderState::default(),
        &config(),
    ));
    assert_eq!(0, beg(&decoded).pedals_mode, "Raw 2 → decoded 0 (Hard = Begode 'Strong')");
}

#[test]
fn settings_frame_decodes_speed_alarms() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);
    let decoded = success(decoder.decode(
        &build_settings_frame(SettingsFrame {
            speed_alarms: 1,
            ..Default::default()
        }),
        &DecoderState::default(),
        &config(),
    ));
    assert_eq!(1, beg(&decoded).speed_alarms);
}

#[test]
fn settings_frame_decodes_roll_angle() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);
    let decoded = success(decoder.decode(
        &build_settings_frame(SettingsFrame {
            roll_angle: 2,
            ..Default::default()
        }),
        &DecoderState::default(),
        &config(),
    ));
    assert_eq!(2, beg(&decoded).roll_angle);
}

#[test]
fn settings_frame_decodes_tilt_back_speed_off() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);
    let decoded = success(decoder.decode(
        &build_settings_frame(SettingsFrame::default()),
        &DecoderState::default(),
        &config(),
    ));
    assert_eq!(0, beg(&decoded).tilt_back_speed);
}

#[test]
fn settings_frame_decodes_led_mode() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);
    let decoded = success(decoder.decode(
        &build_settings_frame(SettingsFrame::default()),
        &DecoderState::default(),
        &config(),
    ));
    assert_eq!(0, beg(&decoded).led_mode);
}

#[test]
fn settings_frame_decodes_all_begode_screenshot_settings() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);
    let decoded = success(decoder.decode(
        &build_settings_frame(SettingsFrame {
            pedals_mode: 2,
            speed_alarms: 1,
            roll_angle: 2,
            tilt_back_speed: 0,
            led_mode: 0,
            light_mode: 1,
            ..Default::default()
        }),
        &DecoderState::default(),
        &config(),
    ));
    let s = beg(&decoded);
    assert_eq!(0, s.pedals_mode);
    assert_eq!(1, s.speed_alarms);
    assert_eq!(2, s.roll_angle);
    assert_eq!(0, s.tilt_back_speed);
    assert_eq!(0, s.led_mode);
    assert_eq!(1, s.light_mode);
}

#[test]
fn settings_frame_tilt_back_100_or_above_clamps_to_0() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);
    let decoded = success(decoder.decode(
        &build_settings_frame(SettingsFrame {
            tilt_back_speed: 100,
            ..Default::default()
        }),
        &DecoderState::default(),
        &config(),
    ));
    assert_eq!(0, beg(&decoded).tilt_back_speed, ">=100 clamps to 0 (off)");
}

// ==================== Settings Echo Suppression ====================

#[test]
fn short_begode_setting_command_ignores_two_stale_settings_echoes() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);
    decoder.build_command(&WheelCommand::SetLightMode(1));
    let stale_frame = build_settings_frame(SettingsFrame::default());

    for i in 0..2 {
        let stale = success(decoder.decode(&stale_frame, &DecoderState::default(), &config()));
        assert!(
            stale.settings.is_none(),
            "Stale frame {} must not overwrite the requested setting",
            i + 1
        );
    }

    let confirmed = success(decoder.decode(&stale_frame, &DecoderState::default(), &config()));
    assert_eq!(0, beg(&confirmed).light_mode);
}

#[test]
fn multi_step_begode_setting_command_ignores_five_stale_settings_echoes() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);
    decoder.build_command(&WheelCommand::SetMaxSpeed(74));
    let stale_frame = build_settings_frame(SettingsFrame::default());

    for _ in 0..5 {
        let stale = success(decoder.decode(&stale_frame, &DecoderState::default(), &config()));
        assert!(stale.settings.is_none());
    }

    let confirmed = success(decoder.decode(&stale_frame, &DecoderState::default(), &config()));
    assert_eq!(0, beg(&confirmed).tilt_back_speed);
}

// ==================== Command Encoding ====================

#[test]
fn set_pedals_mode_commands() {
    let mut decoder = GotwayDecoder::new();
    for (mode, expected) in [(0, "h"), (1, "f"), (2, "s")] {
        let commands = decoder.build_command(&WheelCommand::SetPedalsMode(mode));
        assert_eq!(1, commands.len());
        assert_eq!(expected, send_bytes_str(&commands[0]));
    }
}

#[test]
fn set_alarm_mode_commands() {
    let mut decoder = GotwayDecoder::new();
    for (mode, expected) in [(0, "o"), (1, "u")] {
        let commands = decoder.build_command(&WheelCommand::SetAlarmMode(mode));
        assert_eq!(1, commands.len());
        assert_eq!(expected, send_bytes_str(&commands[0]));
    }
}

#[test]
fn set_roll_angle_mode_commands() {
    let mut decoder = GotwayDecoder::new();
    for (mode, expected) in [(2, "<"), (0, ">")] {
        let commands = decoder.build_command(&WheelCommand::SetRollAngleMode(mode));
        assert_eq!(1, commands.len());
        assert_eq!(expected, send_bytes_str(&commands[0]));
    }
}

#[test]
fn set_light_mode_commands() {
    let mut decoder = GotwayDecoder::new();
    for (mode, expected) in [(1, "Q"), (0, "E"), (2, "T")] {
        let commands = decoder.build_command(&WheelCommand::SetLightMode(mode));
        assert_eq!(1, commands.len());
        assert_eq!(expected, send_bytes_str(&commands[0]));
    }
}

#[test]
fn set_led_mode_0_sends_w_m_0_b() {
    let mut decoder = GotwayDecoder::new();
    let commands = decoder.build_command(&WheelCommand::SetLedMode(0));
    assert_eq!(4, commands.len());
    assert_eq!("W", send_bytes_str(&commands[0]));
    assert_eq!("M", send_delayed(&commands[1]).0);
    assert_eq!("0", send_delayed(&commands[2]).0);
    assert_eq!("b", send_delayed(&commands[3]).0);
}

#[test]
fn set_beeper_volume_1_sends_w_b_1() {
    // Begode app sends exactly 3 bytes: 57 42 3x (no trailing "b")
    let mut decoder = GotwayDecoder::new();
    let commands = decoder.build_command(&WheelCommand::SetBeeperVolume(1));
    assert_eq!(3, commands.len());
    assert_eq!("W", send_bytes_str(&commands[0]));
    assert_eq!("B", send_delayed(&commands[1]).0);
    assert_eq!("1", send_delayed(&commands[2]).0);
}

#[test]
fn set_max_speed_74_sends_b_w_y_7_4_b_b() {
    let mut decoder = GotwayDecoder::new();
    let commands = decoder.build_command(&WheelCommand::SetMaxSpeed(74));
    assert_eq!(7, commands.len());
    assert_eq!("b", send_bytes_str(&commands[0]));
    assert_eq!("W", send_delayed(&commands[1]).0);
    assert_eq!("Y", send_delayed(&commands[2]).0);
    assert_eq!("7", send_delayed(&commands[3]).0);
    assert_eq!("4", send_delayed(&commands[4]).0);
    assert_eq!("b", send_delayed(&commands[5]).0);
    assert_eq!("b", send_delayed(&commands[6]).0);
}

#[test]
fn set_max_speed_0_sends_b_quote_b_b() {
    let mut decoder = GotwayDecoder::new();
    let commands = decoder.build_command(&WheelCommand::SetMaxSpeed(0));
    assert_eq!(4, commands.len());
    assert_eq!("b", send_bytes_str(&commands[0]));
    assert_eq!("\"", send_delayed(&commands[1]).0);
    assert_eq!("b", send_delayed(&commands[2]).0);
    assert_eq!("b", send_delayed(&commands[3]).0);
}

#[test]
fn set_cutout_angle_commands() {
    let mut decoder = GotwayDecoder::new();
    for (angle, digit) in [(60, "3"), (45, "0"), (90, "9"), (50, "1")] {
        let commands = decoder.build_command(&WheelCommand::SetCutoutAngle(angle));
        assert_eq!(3, commands.len());
        assert_eq!("W", send_bytes_str(&commands[0]));
        let (x, x_delay) = send_delayed(&commands[1]);
        assert_eq!("X", x);
        assert_eq!(200, x_delay);
        let (d, d_delay) = send_delayed(&commands[2]);
        assert_eq!(digit, d, "angle {angle}");
        assert_eq!(200, d_delay);
    }
}

#[test]
fn set_wheel_display_unit_maps_to_begode_miles_command() {
    let mut decoder = GotwayDecoder::new();
    let commands = decoder.build_command(&WheelCommand::SetWheelDisplayUnit { miles: true });
    assert_eq!(1, commands.len());
    assert_eq!("m", send_bytes_str(&commands[0]));
}

// ==================== 0xFF Settings Frame ====================

#[test]
fn firmware_settings_frame_does_not_set_cutout_angle() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);
    let result = decoder.decode(
        &build_firmware_settings_frame(90),
        &DecoderState::default(),
        &config(),
    );
    let decoded = success(result);
    assert!(
        decoded.settings.is_none(),
        "0xFF frame should not emit settings — cutout angle is read from FRAME_07"
    );
}

// ==================== isReady ====================

#[test]
fn is_ready_lifecycle() {
    let mut decoder = GotwayDecoder::new();
    assert!(!decoder.is_ready(), "Should not be ready before any data");

    let mut ds = DecoderState::default();
    apply(&mut decoder, b"GW1.23", &mut ds, &config());
    assert!(!decoder.is_ready(), "Should not be ready without voltage data");

    decoder.decode(&build_live_data_frame(LiveFrame::default()), &ds, &config());
    assert!(decoder.is_ready(), "Should be ready after fw + voltage data");

    decoder.reset();
    assert!(!decoder.is_ready(), "Should not be ready after reset");
}

#[test]
fn is_ready_does_not_return_true_from_bms_voltage_alone() {
    let mut decoder = GotwayDecoder::new();
    let mut ds = DecoderState::default();
    apply(&mut decoder, b"GW1.23", &mut ds, &config());

    decoder.decode(
        &build_extended_frame(ExtFrame {
            bat_voltage: 6700,
            ..Default::default()
        }),
        &ds,
        &config(),
    );
    assert!(
        !decoder.is_ready(),
        "BMS voltage alone should not make decoder ready — needs frame 0x00"
    );
}

// ==================== Voltage Precedence ====================

#[test]
fn frame_00_voltage_is_used_before_frame_01_arrives() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);
    let decoded = success(decoder.decode(
        &build_live_data_frame(LiveFrame::default()),
        &DecoderState::default(),
        &config(),
    ));
    assert_eq!(6000, tel(&decoded).voltage);
}

#[test]
fn frame_01_voltage_overrides_frame_00_voltage() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    let d1 = success(decoder.decode(&build_live_data_frame(LiveFrame::default()), &ds, &config()));
    assert_eq!(6000, tel(&d1).voltage);
    ds = merged(&d1, &ds);

    let d2 = success(decoder.decode(
        &build_extended_frame(ExtFrame {
            bat_voltage: 6700,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert_eq!(67000, tel(&d2).voltage);
}

#[test]
fn frame_01_context_routes_status_to_each_of_four_bms_packs() {
    let mut decoder = GotwayDecoder::new();
    let mut state = DecoderState::default();

    for context in 0..4 {
        let result = decoder.decode(
            &build_extended_frame(ExtFrame {
                bat_voltage: 6700,
                context,
                bms_current: (context + 1) * 10,
                semi_voltage: (context + 1) * 100,
                ..Default::default()
            }),
            &state,
            &config(),
        );
        let data = success(result);
        state = merged(&data, &state);
    }

    let packs = [
        state.bms.bms1.as_ref(),
        state.bms.bms2.as_ref(),
        state.bms.bms3.as_ref(),
        state.bms.bms4.as_ref(),
    ];
    for (index, pack) in packs.iter().enumerate() {
        let pack = pack.unwrap_or_else(|| panic!("context {index} must publish a distinct BMS pack"));
        assert!((pack.current - (index as f64 + 1.0)).abs() < 0.001);
        assert!((pack.semi_voltage1 - (index as f64 + 1.0) * 10.0).abs() < 0.001);
    }
}

#[test]
fn subsequent_frame_00_does_not_overwrite_voltage_after_frame_01() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    apply(
        &mut decoder,
        &build_live_data_frame(LiveFrame::default()),
        &mut ds,
        &config(),
    );

    let d2 = success(decoder.decode(
        &build_extended_frame(ExtFrame {
            bat_voltage: 6700,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert_eq!(67000, tel(&d2).voltage);
    ds = merged(&d2, &ds);

    let d3 = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            voltage: 5900,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert_eq!(67000, tel(&d3).voltage, "Frame 0x00 should not overwrite after 0x01");
}

// ==================== Miles/km Edge Cases ====================

#[test]
fn wheel_distance_is_not_normalized_when_in_miles_is_false() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    apply(
        &mut decoder,
        &build_settings_frame(SettingsFrame::default()),
        &mut ds,
        &config(),
    );

    let d2 = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            distance: 1000,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert_eq!(1000, tel(&d2).wheel_distance);
}

#[test]
fn zero_speed_and_distance_unchanged_regardless_of_in_miles() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    let d1 = success(decoder.decode(
        &build_settings_frame(SettingsFrame {
            in_miles: true,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert!(beg(&d1).in_miles);
    ds = merged(&d1, &ds);

    let d2 = success(decoder.decode(&build_live_data_frame(LiveFrame::default()), &ds, &config()));
    assert_eq!(0, tel(&d2).speed);
    assert_eq!(0, tel(&d2).wheel_distance);
}

#[test]
fn in_miles_persists_across_consecutive_live_frames() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    apply(
        &mut decoder,
        &build_settings_frame(SettingsFrame {
            in_miles: true,
            ..Default::default()
        }),
        &mut ds,
        &config(),
    );

    let live = LiveFrame {
        speed: 778,
        distance: 1000,
        ..Default::default()
    };
    let d2 = success(decoder.decode(&build_live_data_frame(live.clone()), &ds, &config()));
    ds = merged(&d2, &ds);
    let speed1 = tel(&d2).speed;
    let dist1 = tel(&d2).wheel_distance;

    let d3 = success(decoder.decode(&build_live_data_frame(live), &ds, &config()));
    let telemetry3 = d3.telemetry.clone().unwrap_or_else(|| ds.telemetry.clone());
    assert_eq!(speed1, telemetry3.speed, "Both frames should normalize speed identically");
    assert_eq!(dist1, telemetry3.wheel_distance);
    assert!(speed1 > 2801, "Speed should be normalized from mph to kmh (larger value)");
}

#[test]
fn ratio_plus_in_miles_combined_produce_correct_values() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let ratio_config = DecoderConfig {
        use_ratio: true,
        ..config()
    };

    let mut ds = DecoderState::default();
    apply(
        &mut decoder,
        &build_settings_frame(SettingsFrame {
            in_miles: true,
            ..Default::default()
        }),
        &mut ds,
        &ratio_config,
    );

    let d2 = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            speed: 778,
            distance: 1000,
            ..Default::default()
        }),
        &ds,
        &ratio_config,
    ));

    // raw → *3.6 → abs → *ratio → /miles
    // Speed: 778*3.6=2800.8→2801 → *0.875=2450.875→2451 → /0.62137=3944.6→3945
    let raw_speed = round_i32(778.0 * 3.6);
    let after_ratio = round_i32(raw_speed as f64 * 0.875);
    let after_miles = round_i32(after_ratio as f64 / KM_TO_MILES_MULTIPLIER) as i32;
    assert_eq!(after_miles, tel(&d2).speed);

    // Distance: 1000*0.875=875 → /0.62137=1408.4→1408
    let dist_after_ratio = round_i32(1000.0 * 0.875);
    let dist_after_miles = round_i32(dist_after_ratio as f64 / KM_TO_MILES_MULTIPLIER);
    assert_eq!(dist_after_miles, tel(&d2).wheel_distance);
}

// ==================== Current/PWM Verification ====================

#[test]
fn standard_firmware_uses_status_word_and_model_no_load_pwm() {
    let mut decoder = GotwayDecoder::new();
    decoder.decode(b"GW2035101", &DecoderState::default(), &config());
    decoder.decode(b"NAME:Blitz", &DecoderState::default(), &config());

    let cfg = DecoderConfig {
        gotway_voltage: -1,
        ..config()
    };
    let decoded = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            voltage: 6720,
            speed: 1000, // 36 km/h
            phase_current: 500,
            status_word: 3000,
            ..Default::default()
        }),
        &DecoderState::default(),
        &cfg,
    ));

    let telemetry = tel(&decoded);
    assert_eq!(500, telemetry.phase_current);
    assert_eq!(250, telemetry.current, "Legacy wheels estimate battery current from phase current");
    assert!((telemetry.calculated_pwm - 36.0 / 150.0).abs() < 0.001);
    assert_eq!(2400, telemetry.output);
}

#[test]
fn legacy_standard_firmware_estimates_signed_battery_current_before_frame_07() {
    let mut decoder = GotwayDecoder::new();
    let cfg = DecoderConfig {
        gotway_negative: 1,
        ..config()
    };
    let positive = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            speed: 1000,
            phase_current: 1000,
            ..Default::default()
        }),
        &DecoderState::default(),
        &cfg,
    ));
    assert_eq!(500, tel(&positive).current);

    decoder.reset();
    let negative = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            speed: 1000,
            phase_current: -1000,
            ..Default::default()
        }),
        &DecoderState::default(),
        &cfg,
    ));
    assert_eq!(-250, tel(&negative).current);
    assert_eq!(-15_000, tel(&negative).power);
}

#[test]
fn per_wheel_pwm_overrides_take_precedence_over_model_profile() {
    let mut decoder = GotwayDecoder::new();
    decoder.decode(b"GW2035101", &DecoderState::default(), &config());
    decoder.decode(b"NAME:Blitz", &DecoderState::default(), &config());

    let cfg = DecoderConfig {
        gotway_voltage: -1,
        rotation_speed: 1200,
        rotation_voltage: 1680,
        power_factor: 80,
        ..config()
    };
    let decoded = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            voltage: 6720,
            speed: 1000,
            ..Default::default()
        }),
        &DecoderState::default(),
        &cfg,
    ));
    assert!((tel(&decoded).calculated_pwm - 0.46875).abs() < 0.0001);
}

#[test]
fn frame_00_does_not_store_duty_cycle_multiplier_as_display_pwm() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let decoded = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            status_word: 2500,
            ..Default::default()
        }),
        &DecoderState::default(),
        &config(),
    ));
    // Standard firmware: bytes 14-15 are status flags, not PWM. Zero speed →
    // speed-based fallback is also zero.
    assert_eq!(0, tel(&decoded).output);
    assert_eq!(0.0, tel(&decoded).calculated_pwm);
}

#[test]
fn frame_07_with_in_range_hw_pwm_sets_display_pwm() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    apply(
        &mut decoder,
        &build_live_data_frame(LiveFrame::default()),
        &mut ds,
        &config(),
    );

    let d2 = success(decoder.decode(
        &build_current_temp_frame(CtFrame {
            battery_current: 100,
            motor_temp: 40,
            hw_pwm: 45,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert_eq!(4500, tel(&d2).output);
    assert_eq!(0.45, tel(&d2).calculated_pwm);
}

#[test]
fn frame_07_actual_battery_current_refreshes_power() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    apply(
        &mut decoder,
        &build_live_data_frame(LiveFrame::default()),
        &mut ds,
        &config(),
    );

    let decoded = success(decoder.decode(
        &build_current_temp_frame(CtFrame {
            battery_current: 100,
            motor_temp: 40,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert_eq!(-100, tel(&decoded).current);
    assert_eq!(-6000, tel(&decoded).power);
}

#[test]
fn frame_07_with_out_of_range_hw_pwm_is_rejected() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    apply(
        &mut decoder,
        &build_live_data_frame(LiveFrame::default()),
        &mut ds,
        &config(),
    );

    // Pre-hwPwm firmware puts unrelated data in byte 8 (observed constant 320).
    let d2 = success(decoder.decode(
        &build_current_temp_frame(CtFrame {
            battery_current: 100,
            motor_temp: 40,
            hw_pwm: 320,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert_eq!(0, tel(&d2).output);
    assert_eq!(0.0, tel(&d2).calculated_pwm);
}

#[test]
fn frame_07_with_zero_hw_pwm_does_not_set_display_pwm() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    apply(
        &mut decoder,
        &build_live_data_frame(LiveFrame::default()),
        &mut ds,
        &config(),
    );

    let d2 = success(decoder.decode(
        &build_current_temp_frame(CtFrame {
            battery_current: 100,
            motor_temp: 40,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert_eq!(0, tel(&d2).output);
    assert_eq!(0.0, tel(&d2).calculated_pwm);
}

// ==================== hasNewData OR Semantics ====================

#[test]
fn has_new_data_true_when_any_frame_in_packet_has_new_data() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    apply(
        &mut decoder,
        &build_settings_frame(SettingsFrame {
            total_distance: 1000,
            ..Default::default()
        }),
        &mut ds,
        &config(),
    );

    let d2 = success(decoder.decode(&build_live_data_frame(LiveFrame::default()), &ds, &config()));
    assert!(
        d2.has_new_data,
        "hasNewData should be true when any frame has new data (OR semantics)"
    );
}

// ==================== Alexovik Current + BMS Current ====================

#[test]
fn alexovik_frame_with_battery_current_flag_stores_current() {
    let mut decoder = GotwayDecoder::new();
    let mut ds = DecoderState::default();
    apply(&mut decoder, b"BF1.23", &mut ds, &config());

    let d2 = success(decoder.decode(&build_alexovik_live_frame(6000, true, -250), &ds, &config()));
    assert_eq!(-250, tel(&d2).current, "Alexovik battery current should be stored in state");
}

#[test]
fn alexovik_frame_without_battery_current_flag_uses_calculated_current() {
    let mut decoder = GotwayDecoder::new();
    let mut ds = DecoderState::default();
    apply(&mut decoder, b"BF1.23", &mut ds, &config());

    let d2 = success(decoder.decode(&build_alexovik_live_frame(6000, false, 0), &ds, &config()));
    assert_eq!(0, tel(&d2).current, "Without battery current flag, should use calculated current");
}

#[test]
fn frame_01_does_not_overwrite_current() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    let d1 = success(decoder.decode(&build_live_data_frame(LiveFrame::default()), &ds, &config()));
    let original_current = tel(&d1).current;
    ds = merged(&d1, &ds);

    let d2 = success(decoder.decode(
        &build_extended_frame(ExtFrame {
            bat_voltage: 6700,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert_eq!(
        original_current,
        tel(&d2).current,
        "Frame 0x01 should preserve current from prior frames"
    );
}

// ==================== autoVoltage Config Gate ====================

#[test]
fn auto_voltage_true_frame_00_voltage_blocked_after_frame_01() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);
    let cfg = DecoderConfig {
        auto_voltage: true,
        ..config()
    };

    let mut ds = DecoderState::default();
    let d1 = success(decoder.decode(&build_live_data_frame(LiveFrame::default()), &ds, &cfg));
    assert_eq!(6000, tel(&d1).voltage);
    ds = merged(&d1, &ds);

    let d2 = success(decoder.decode(
        &build_extended_frame(ExtFrame {
            bat_voltage: 6700,
            ..Default::default()
        }),
        &ds,
        &cfg,
    ));
    assert_eq!(67000, tel(&d2).voltage);
    ds = merged(&d2, &ds);

    let d3 = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            voltage: 5900,
            ..Default::default()
        }),
        &ds,
        &cfg,
    ));
    assert_eq!(
        67000,
        tel(&d3).voltage,
        "autoVoltage=true: frame 0x00 voltage should be blocked after frame 0x01"
    );
}

#[test]
fn auto_voltage_false_frame_00_voltage_always_written() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);
    let cfg = DecoderConfig {
        auto_voltage: false,
        ..config()
    };

    let mut ds = DecoderState::default();
    apply(&mut decoder, &build_live_data_frame(LiveFrame::default()), &mut ds, &cfg);

    let d2 = success(decoder.decode(
        &build_extended_frame(ExtFrame {
            bat_voltage: 6700,
            ..Default::default()
        }),
        &ds,
        &cfg,
    ));
    ds = merged(&d2, &ds);
    assert_eq!(
        6000, ds.telemetry.voltage,
        "autoVoltage=false: frame 0x01 should NOT write BMS voltage"
    );

    let d3 = success(decoder.decode(
        &build_live_data_frame(LiveFrame {
            voltage: 5900,
            ..Default::default()
        }),
        &ds,
        &cfg,
    ));
    assert_eq!(
        5900,
        tel(&d3).voltage,
        "autoVoltage=false: frame 0x00 voltage should always be written"
    );
}

#[test]
fn auto_voltage_true_frame_01_writes_bms_voltage() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);
    let cfg = DecoderConfig {
        auto_voltage: true,
        ..config()
    };

    let mut ds = DecoderState::default();
    apply(&mut decoder, &build_live_data_frame(LiveFrame::default()), &mut ds, &cfg);

    let d2 = success(decoder.decode(
        &build_extended_frame(ExtFrame {
            bat_voltage: 6700,
            ..Default::default()
        }),
        &ds,
        &cfg,
    ));
    assert_eq!(67000, tel(&d2).voltage, "autoVoltage=true: BMS voltage should be written");
}

// ==================== hasNewData Timing ====================

#[test]
fn first_frame_01_has_new_data_is_false() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    apply(&mut decoder, &build_live_data_frame(LiveFrame::default()), &mut ds, &config());

    let d2 = success(decoder.decode(
        &build_extended_frame(ExtFrame {
            bat_voltage: 6700,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert!(!d2.has_new_data, "First frame 0x01 should have hasNewData=false");
}

#[test]
fn second_frame_01_has_new_data_reflects_true_voltage() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    apply(&mut decoder, &build_live_data_frame(LiveFrame::default()), &mut ds, &config());

    let ext = build_extended_frame(ExtFrame {
        bat_voltage: 6700,
        ..Default::default()
    });
    apply(&mut decoder, &ext, &mut ds, &config());

    let d3 = success(decoder.decode(&ext, &ds, &config()));
    assert!(d3.has_new_data, "Second frame 0x01 should have hasNewData=true");
}

#[test]
fn first_frame_07_has_new_data_is_false() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    apply(&mut decoder, &build_live_data_frame(LiveFrame::default()), &mut ds, &config());

    let d2 = success(decoder.decode(
        &build_current_temp_frame(CtFrame {
            battery_current: 100,
            motor_temp: 40,
            ..Default::default()
        }),
        &ds,
        &config(),
    ));
    assert!(!d2.has_new_data, "First frame 0x07 should have hasNewData=false");
}

#[test]
fn second_frame_07_has_new_data_reflects_true_current() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let mut ds = DecoderState::default();
    apply(&mut decoder, &build_live_data_frame(LiveFrame::default()), &mut ds, &config());

    let ct = build_current_temp_frame(CtFrame {
        battery_current: 100,
        motor_temp: 40,
        ..Default::default()
    });
    apply(&mut decoder, &ct, &mut ds, &config());

    let d3 = success(decoder.decode(&ct, &ds, &config()));
    assert!(d3.has_new_data, "Second frame 0x07 should have hasNewData=true");
}

// ==================== Cutout Angle Readback (FRAME_07 bytes 4-5) ====================

#[test]
fn frame_07_decodes_cutout_angle_steps() {
    for (step, expected) in [(0, 45), (9, 90), (3, 60), (15, -1)] {
        let mut decoder = GotwayDecoder::new();
        init_decoder(&mut decoder);
        let decoded = success(decoder.decode(
            &build_current_temp_frame(CtFrame {
                cutout_step: step,
                ..Default::default()
            }),
            &DecoderState::default(),
            &config(),
        ));
        assert_eq!(expected, beg(&decoded).cutout_angle, "step {step}");
    }
}

// ==================== Beeper Volume Readback (FRAME_00 byte 17) ====================

#[test]
fn frame_00_decodes_beeper_volume_from_byte_17() {
    for (volume, expected) in [(3, 3), (7, 7), (15, -1)] {
        let mut decoder = GotwayDecoder::new();
        init_decoder(&mut decoder);
        let decoded = success(decoder.decode(
            &build_live_data_frame(LiveFrame {
                beeper_volume: volume,
                ..Default::default()
            }),
            &DecoderState::default(),
            &config(),
        ));
        assert_eq!(expected, beg(&decoded).beeper_volume, "volume {volume}");
    }
}

// ==================== BMS Cell Accumulation ====================

#[test]
fn bms_frame_02_accumulates_cells_for_bms1() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let cells0 = [4200, 4190, 4180, 4170, 4160, 4150, 4140, 4130];
    success(decoder.decode(
        &build_bms_cell_frame(0x02, 0, &cells0),
        &DecoderState::default(),
        &config(),
    ));

    let decoded = success(decoder.decode(
        &build_live_data_frame(LiveFrame::default()),
        &DecoderState::default(),
        &config(),
    ));
    let snapshot = bms(&decoded).bms1.as_ref().expect("bms1");
    assert!((snapshot.cells[0] - 4.200).abs() < 0.001);
    assert!((snapshot.cells[1] - 4.190).abs() < 0.001);
    assert!((snapshot.cells[7] - 4.130).abs() < 0.001);
}

#[test]
fn bms_frame_03_accumulates_cells_for_bms2() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let cells0 = [4100, 4090, 4080, 4070, 4060, 4050, 4040, 4030];
    decoder.decode(
        &build_bms_cell_frame(0x03, 0, &cells0),
        &DecoderState::default(),
        &config(),
    );

    let decoded = success(decoder.decode(
        &build_live_data_frame(LiveFrame::default()),
        &DecoderState::default(),
        &config(),
    ));
    let snapshot = bms(&decoded).bms2.as_ref().expect("bms2");
    assert!((snapshot.cells[0] - 4.100).abs() < 0.001);
    assert!((snapshot.cells[7] - 4.030).abs() < 0.001);
}

#[test]
fn bms_cells_accumulate_across_multiple_p_nums() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    decoder.decode(
        &build_bms_cell_frame(0x02, 0, &[4200; 8]),
        &DecoderState::default(),
        &config(),
    );
    decoder.decode(
        &build_bms_cell_frame(0x02, 1, &[4100; 8]),
        &DecoderState::default(),
        &config(),
    );

    let decoded = success(decoder.decode(
        &build_live_data_frame(LiveFrame::default()),
        &DecoderState::default(),
        &config(),
    ));
    let snapshot = bms(&decoded).bms1.as_ref().expect("bms1");
    assert!((snapshot.cells[0] - 4.200).abs() < 0.001);
    assert!((snapshot.cells[7] - 4.200).abs() < 0.001);
    assert!((snapshot.cells[8] - 4.100).abs() < 0.001);
    assert!((snapshot.cells[15] - 4.100).abs() < 0.001);
}

#[test]
fn bms_cell_stats_are_computed_after_accumulation() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    let cells = [4200, 4150, 4100, 4050, 4000, 3950, 3900, 3850];
    decoder.decode(
        &build_bms_cell_frame(0x02, 0, &cells),
        &DecoderState::default(),
        &config(),
    );

    let decoded = success(decoder.decode(
        &build_live_data_frame(LiveFrame::default()),
        &DecoderState::default(),
        &config(),
    ));
    let snapshot = bms(&decoded).bms1.as_ref().expect("bms1");
    assert!((snapshot.max_cell - 4.200).abs() < 0.001);
    assert!((snapshot.min_cell - 3.850).abs() < 0.001);
    assert!((snapshot.cell_diff - 0.350).abs() < 0.001);
    assert_eq!(1, snapshot.max_cell_num); // 1-indexed
    assert_eq!(8, snapshot.min_cell_num);
}

#[test]
fn dual_bms_cell_counts_and_averages_accumulate_independently() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    decoder.decode(
        &build_bms_cell_frame(0x02, 0, &[4200; 8]),
        &DecoderState::default(),
        &config(),
    );
    decoder.decode(
        &build_bms_cell_frame(0x02, 1, &[4200; 8]),
        &DecoderState::default(),
        &config(),
    );

    let decoded = success(decoder.decode(
        &build_bms_cell_frame(0x03, 0, &[4000; 8]),
        &DecoderState::default(),
        &config(),
    ));

    let bms_state = bms(&decoded);
    assert_eq!(16, bms_state.bms1.as_ref().unwrap().cell_num);
    assert_eq!(8, bms_state.bms2.as_ref().unwrap().cell_num);
    assert!((bms_state.bms2.as_ref().unwrap().avg_cell - 4.0).abs() < 0.001);
    assert!((bms_state.bms2.as_ref().unwrap().voltage - 32.0).abs() < 0.001);
}

#[test]
fn new_begode_frame_types_05_and_06_populate_bms_3_and_4() {
    let mut decoder = GotwayDecoder::new();
    init_decoder(&mut decoder);

    decoder.decode(
        &build_bms_cell_frame(0x05, 0, &[4100; 8]),
        &DecoderState::default(),
        &config(),
    );
    let decoded = success(decoder.decode(
        &build_bms_cell_frame(0x06, 0, &[3900; 8]),
        &DecoderState::default(),
        &config(),
    ));

    let bms_state = bms(&decoded);
    assert_eq!(8, bms_state.bms3.as_ref().unwrap().cell_num);
    assert!((bms_state.bms3.as_ref().unwrap().avg_cell - 4.1).abs() < 0.001);
    assert_eq!(8, bms_state.bms4.as_ref().unwrap().cell_num);
    assert!((bms_state.bms4.as_ref().unwrap().avg_cell - 3.9).abs() < 0.001);
}

// ==================== Capabilities ====================

#[test]
fn begode_capabilities_expose_implemented_commands() {
    let mut decoder = GotwayDecoder::new();
    decoder.decode(b"GW2035101", &DecoderState::default(), &config());

    let commands = decoder.get_capabilities().supported_commands;
    assert!(commands.contains(&SettingsCommandId::MaxSpeed));
    assert!(commands.contains(&SettingsCommandId::AlarmMode));
    assert!(commands.contains(&SettingsCommandId::WheelDisplayUnit));
}
