//! Port of `GotwayDecoder.kt` — Gotway/Begode protocol decoder.
//!
//! Sans-io: raw BLE bytes in, `DecodeResult` (state deltas + commands) out.
//! Supports Begode (GW/JL), ExtremeBull (JN), Freestyl3r (CF), and
//! SmirnoV/Alexovik (BF) firmware variants. See the Kotlin source for the
//! frame layout documentation; byte offsets here match it exactly.

use crate::byte_utils::{
    bytes_to_hex, get_int4, round_f32_to_i32, round_to_i32, round_to_i64, short_from_bytes_be,
    signed_short_from_bytes_be, KM_TO_MILES_MULTIPLIER,
};
use crate::catalog::{match_profile, BegodeModelProfile};
use crate::decode_loop::{FrameOutcome, FrameResult};
use crate::types::{
    resolve_wheel_identity, BegodeSettings, BmsSnapshot, BmsState, CapabilitySet, DecodeResult,
    DecodedData, DecoderConfig, DecoderState, SettingsCommandId, SmartBms, TelemetryState,
    UnhandledReason, WheelCommand, WheelIdentity, WheelSettings, WheelType,
};
use crate::unpacker::{GotwayUnpacker, UnpackerStats};

const MAX_INFO_ATTEMPTS: i32 = 50;
const RATIO_GW: f64 = 0.875;

// PreferenceDefaults used by the per-wheel PWM override checks.
const DEFAULT_ROTATION_SPEED: i32 = 500;
const DEFAULT_ROTATION_VOLTAGE: i32 = 840;
const DEFAULT_POWER_FACTOR: i32 = 90;

// Frame types (byte 18 of the unpacked frame)
const FRAME_LIVE_DATA: i32 = 0x00;
const FRAME_EXTENDED: i32 = 0x01;
const FRAME_BMS_CELLS_1: i32 = 0x02;
const FRAME_BMS_CELLS_2: i32 = 0x03;
const FRAME_TOTAL_DISTANCE: i32 = 0x04;
const FRAME_BMS_CELLS_3: i32 = 0x05;
const FRAME_BMS_CELLS_4: i32 = 0x06;
const FRAME_CURRENT_TEMP: i32 = 0x07;
const FRAME_SETTINGS: i32 = 0xFF;

pub struct GotwayDecoder {
    unpacker: GotwayUnpacker,
    model: String,
    imu: String,
    fw: String,
    fw_prot: String,
    firmware_signature: String,
    model_profile: Option<BegodeModelProfile>,
    true_voltage: bool,
    true_current: bool,
    true_pwm: bool,
    is_ready: bool,
    has_received_data: bool,
    alexovik_current: i32,
    settings_echo_frames_to_ignore: i32,
    info_attempt: i32,
    bms1: SmartBms,
    bms2: SmartBms,
    bms3: SmartBms,
    bms4: SmartBms,
}

impl Default for GotwayDecoder {
    fn default() -> Self {
        Self::new()
    }
}

impl GotwayDecoder {
    pub fn new() -> Self {
        GotwayDecoder {
            unpacker: GotwayUnpacker::default(),
            model: String::new(),
            imu: String::new(),
            fw: String::new(),
            fw_prot: String::new(),
            firmware_signature: String::new(),
            model_profile: None,
            true_voltage: false,
            true_current: false,
            true_pwm: false,
            is_ready: false,
            has_received_data: false,
            alexovik_current: 0,
            settings_echo_frames_to_ignore: 0,
            info_attempt: 0,
            bms1: SmartBms::default(),
            bms2: SmartBms::default(),
            bms3: SmartBms::default(),
            bms4: SmartBms::default(),
        }
    }

    pub fn wheel_type(&self) -> WheelType {
        WheelType::Gotway
    }

    /// User-facing brand derived from firmware prefix.
    fn brand_display_name(&self) -> String {
        if let Some(profile) = &self.model_profile {
            return profile.brand.to_string();
        }
        match self.fw_prot.as_str() {
            "ExtremeBull" => "Extreme Bull".to_string(),
            other => other.to_string(), // "Begode", "Freestyl3r", "SV", or "" (not yet known)
        }
    }

    fn model_display_name(&self) -> String {
        match &self.model_profile {
            Some(profile) => profile.display_name.to_string(),
            None => self.model.clone(),
        }
    }

    fn refresh_model_profile(&mut self) {
        self.model_profile = match_profile(&self.model, &self.firmware_signature);
    }

    fn apply_firmware_response(
        &mut self,
        data_str: &str,
        prot: &str,
        current_identity: &WheelIdentity,
    ) -> WheelIdentity {
        self.firmware_signature = data_str.to_string();
        self.fw = data_str.chars().skip(2).collect::<String>().trim().to_string();
        self.fw_prot = prot.to_string();
        self.refresh_model_profile();
        self.is_ready = true;
        let model_dn = self.model_display_name();
        WheelIdentity {
            model: if model_dn.is_empty() {
                current_identity.model.clone()
            } else {
                model_dn
            },
            version: self.fw.clone(),
            brand: self.brand_display_name(),
            ..current_identity.clone()
        }
    }

    pub fn decode(
        &mut self,
        data: &[u8],
        current_state: &DecoderState,
        config: &DecoderConfig,
    ) -> DecodeResult {
        // Pre-loop: parse firmware/model info from string data. Binary frames
        // always start with 0x55; string responses start with ASCII letters.
        let mut pre_identity: Option<WheelIdentity> = None;
        if (self.model.is_empty() || self.fw.is_empty()) && !data.is_empty() && data[0] != 0x55 {
            let data_str = String::from_utf8_lossy(data).trim().to_string();
            if data_str.starts_with("NAME") {
                self.model = data_str
                    .chars()
                    .skip(5)
                    .collect::<String>()
                    .trim()
                    .to_string();
                self.refresh_model_profile();
                pre_identity = Some(WheelIdentity {
                    model: self.model_display_name(),
                    brand: self.brand_display_name(),
                    ..current_state.identity.clone()
                });
            } else if data_str.starts_with("GW") || data_str.starts_with("JL") {
                pre_identity =
                    Some(self.apply_firmware_response(&data_str, "Begode", &current_state.identity));
            } else if data_str.starts_with("JN") {
                pre_identity = Some(self.apply_firmware_response(
                    &data_str,
                    "ExtremeBull",
                    &current_state.identity,
                ));
            } else if data_str.starts_with("CF") {
                pre_identity = Some(self.apply_firmware_response(
                    &data_str,
                    "Freestyl3r",
                    &current_state.identity,
                ));
            } else if data_str.starts_with("BF") {
                pre_identity =
                    Some(self.apply_firmware_response(&data_str, "SV", &current_state.identity));
            } else if data_str.starts_with("MPU") {
                self.imu = data_str
                    .chars()
                    .skip(1)
                    .take(6)
                    .collect::<String>()
                    .trim()
                    .to_string();
            }
        }

        // Inject pre-loop identity into loop input so process_frame sees it
        let loop_input = match &pre_identity {
            Some(identity) => DecoderState {
                identity: identity.clone(),
                ..current_state.clone()
            },
            None => current_state.clone(),
        };

        // Unpacker loop
        let loop_result = self.decode_frames(data, &loop_input, config);

        let success_data = match &loop_result {
            DecodeResult::Success(data) => Some(data.clone()),
            _ => None,
        };
        let final_has_new_data = success_data.as_ref().is_some_and(|d| d.has_new_data);
        let mut commands = success_data
            .as_ref()
            .map_or_else(Vec::new, |d| d.commands.clone());
        let news = success_data.as_ref().and_then(|d| d.news.clone());

        // Accumulate identity from pre-loop and loop results
        let mut result_identity = success_data
            .as_ref()
            .and_then(|d| d.identity.clone())
            .or_else(|| pre_identity.clone());

        // Retry firmware/model requests until both are populated (like legacy adapter)
        if final_has_new_data && (self.fw.is_empty() || self.model.is_empty()) {
            if self.info_attempt < MAX_INFO_ATTEMPTS {
                self.info_attempt += 1;
                if self.fw.is_empty() {
                    commands.push(WheelCommand::SendBytes(b"V".to_vec()));
                } else if self.model.is_empty() {
                    commands.push(WheelCommand::SendBytes(b"N".to_vec()));
                }
            } else {
                // Fallback after max attempts
                if self.model.is_empty() {
                    let model_dn = self.model_display_name();
                    self.model = if !model_dn.is_empty() {
                        model_dn
                    } else if !self.fw_prot.is_empty() {
                        self.fw_prot.clone()
                    } else {
                        "Begode".to_string()
                    };
                    let base = result_identity
                        .clone()
                        .unwrap_or_else(|| current_state.identity.clone());
                    result_identity = Some(WheelIdentity {
                        model: self.model.clone(),
                        brand: self.brand_display_name(),
                        ..base
                    });
                }
                if self.fw.is_empty() {
                    self.fw = "-".to_string();
                    let base = result_identity
                        .clone()
                        .unwrap_or_else(|| current_state.identity.clone());
                    result_identity = Some(WheelIdentity {
                        version: self.fw.clone(),
                        brand: self.brand_display_name(),
                        ..base
                    });
                    self.is_ready = true;
                }
            }
        }

        if success_data.is_some() || pre_identity.is_some() || result_identity.is_some() {
            let mut frame_types = success_data
                .as_ref()
                .map_or_else(Vec::new, |d| d.frame_types.clone());
            if pre_identity.is_some() && success_data.as_ref().is_none_or(|d| d.identity.is_none())
            {
                frame_types.insert(0, "IDENTITY".to_string());
            }
            let resolved_identity =
                resolve_wheel_identity(result_identity, &current_state.identity, WheelType::Gotway);
            let bms_snapshot = BmsState {
                bms1: Some(self.bms1.to_snapshot()),
                bms2: Some(self.bms2.to_snapshot()),
                bms3: Some(self.bms3.to_snapshot()).filter(has_begode_pack_data),
                bms4: Some(self.bms4.to_snapshot()).filter(has_begode_pack_data),
            };
            DecodeResult::Success(DecodedData {
                telemetry: success_data.as_ref().and_then(|d| d.telemetry.clone()),
                identity: resolved_identity.filter(|i| *i != current_state.identity),
                bms: Some(bms_snapshot).filter(|b| *b != current_state.bms),
                settings: success_data
                    .as_ref()
                    .and_then(|d| d.settings.clone())
                    .filter(|s| *s != current_state.settings),
                commands,
                has_new_data: final_has_new_data,
                news,
                frame_types,
                log_entries: Vec::new(),
            })
        } else {
            loop_result
        }
    }

    /// Port of the shared `decodeFrames` loop specialized to this decoder.
    fn decode_frames(
        &mut self,
        data: &[u8],
        current_state: &DecoderState,
        config: &DecoderConfig,
    ) -> DecodeResult {
        let mut state = current_state.clone();
        let mut has_new_data = false;
        let mut frame_processed = false;
        let commands: Vec<WheelCommand> = Vec::new();
        let mut news: Option<String> = None;
        let mut had_complete_frame = false;
        let mut first_unhandled: Option<(Vec<u8>, String)> = None;
        let mut frame_types: Vec<String> = Vec::new();

        for &byte in data {
            if self.unpacker.add_char(byte as i32) {
                let buffer = self.unpacker.get_buffer();
                self.unpacker.reset();
                had_complete_frame = true;
                match self.process_frame(&buffer, &state, config) {
                    FrameOutcome::Processed(result) => {
                        frame_processed = true;
                        if let Some(telemetry) = result.telemetry {
                            state.telemetry = telemetry;
                        }
                        if let Some(identity) = result.identity {
                            state.identity = identity;
                        }
                        if let Some(settings) = result.settings {
                            state.settings = settings;
                        }
                        has_new_data = has_new_data || result.has_new_data;
                        if let Some(n) = result.news {
                            news = Some(n);
                        }
                        if let Some(ft) = result.frame_type {
                            frame_types.push(ft.to_string());
                        }
                    }
                    FrameOutcome::Unrecognized(hint) => {
                        if first_unhandled.is_none() {
                            first_unhandled = Some((buffer, hint));
                        }
                    }
                }
            }
        }

        if frame_processed || has_new_data || state != *current_state {
            DecodeResult::Success(DecodedData {
                telemetry: Some(state.telemetry.clone())
                    .filter(|t| *t != current_state.telemetry),
                identity: Some(state.identity.clone()).filter(|i| *i != current_state.identity),
                bms: Some(state.bms.clone()).filter(|b| *b != current_state.bms),
                settings: Some(state.settings.clone()).filter(|s| *s != current_state.settings),
                commands,
                has_new_data,
                news,
                frame_types,
                log_entries: Vec::new(),
            })
        } else if had_complete_frame {
            let (buf, hint) = first_unhandled.unwrap_or((Vec::new(), String::new()));
            let hex = bytes_to_hex(&buf);
            let detail = if hint.is_empty() {
                hex
            } else {
                format!("{hint} {hex}")
            };
            DecodeResult::Unhandled {
                reason: UnhandledReason::UnknownCommand { frame_hex: detail },
                frame_data: buf,
            }
        } else {
            DecodeResult::Buffering
        }
    }

    fn process_frame(
        &mut self,
        buff: &[u8],
        current_state: &DecoderState,
        config: &DecoderConfig,
    ) -> FrameOutcome {
        if buff.len() < 20 {
            return FrameOutcome::Unrecognized(format!("size={}", buff.len()));
        }

        let frame_type = buff[18] as i32;
        let is_alexovik_fw = self.fw_prot == "SV";
        let gotway_negative = config.gotway_negative;

        let result = match frame_type {
            FRAME_LIVE_DATA => {
                let mut r = self.process_live_data_frame(
                    buff,
                    current_state,
                    config,
                    is_alexovik_fw,
                    gotway_negative,
                );
                r.frame_type = Some("LIVE_DATA");
                r
            }
            FRAME_EXTENDED => {
                let mut r =
                    self.process_extended_frame(buff, current_state, config, is_alexovik_fw);
                r.frame_type = Some("EXTENDED");
                r
            }
            FRAME_BMS_CELLS_1 => {
                let mut r = self.process_bms_cells_frame(buff, frame_type);
                r.frame_type = Some("BMS_CELLS_1");
                r
            }
            FRAME_BMS_CELLS_2 => {
                let mut r = self.process_bms_cells_frame(buff, frame_type);
                r.frame_type = Some("BMS_CELLS_2");
                r
            }
            FRAME_TOTAL_DISTANCE => {
                let mut r =
                    self.process_total_distance_frame(buff, current_state, config, is_alexovik_fw);
                r.frame_type = Some("TOTAL_DISTANCE");
                r
            }
            FRAME_BMS_CELLS_3 => {
                let mut r = self.process_bms_cells_frame(buff, frame_type);
                r.frame_type = Some("BMS_CELLS_3");
                r
            }
            FRAME_BMS_CELLS_4 => {
                let mut r = self.process_bms_cells_frame(buff, frame_type);
                r.frame_type = Some("BMS_CELLS_4");
                r
            }
            FRAME_CURRENT_TEMP => {
                let mut r = self.process_current_temp_frame(
                    buff,
                    current_state,
                    is_alexovik_fw,
                    gotway_negative,
                );
                r.frame_type = Some("CURRENT_TEMP");
                r
            }
            FRAME_SETTINGS => FrameResult {
                has_new_data: false,
                frame_type: Some("SETTINGS"),
                ..Default::default()
            },
            other => return FrameOutcome::Unrecognized(format!("type=0x{other:x}")),
        };
        FrameOutcome::Processed(result)
    }

    /// Frame type 0x00: live telemetry data.
    fn process_live_data_frame(
        &mut self,
        buff: &[u8],
        current_state: &DecoderState,
        config: &DecoderConfig,
        is_alexovik_fw: bool,
        gotway_negative: i32,
    ) -> FrameResult {
        let tel = &current_state.telemetry;
        let gw = begode_settings(&current_state.settings);

        let auto_voltage = config.auto_voltage && !is_alexovik_fw;
        let mut voltage = short_from_bytes_be(buff, 2);
        let mut speed = round_to_i32(signed_short_from_bytes_be(buff, 4) as f64 * 3.6);
        let mut distance: i64 = 0;

        if !is_alexovik_fw {
            distance = short_from_bytes_be(buff, 8) as i64;
        } else if (buff[7] & 0x01) == 1 {
            // SmirnoV protocol: battery current in a different location
            let battery_current = signed_short_from_bytes_be(buff, 8);
            self.true_current = true;
            self.alexovik_current = battery_current;
        }

        let mut phase_current = signed_short_from_bytes_be(buff, 10);

        let temperature = if !is_alexovik_fw {
            // MPU6050 temperature formula
            round_f32_to_i32(
                ((signed_short_from_bytes_be(buff, 12) as f32) / 340.0 + 36.53) * 100.0,
            )
        } else {
            // MPU6500 temperature formula
            round_f32_to_i32(
                ((signed_short_from_bytes_be(buff, 12) as f32) / 333.87 + 21.0) * 100.0,
            )
        };

        let beeper_volume = buff[17] as i32;
        let frame0_status_or_pwm = signed_short_from_bytes_be(buff, 14);

        // Apply direction/polarity settings
        if gotway_negative == 0 {
            speed = speed.abs();
            phase_current = phase_current.abs();
        } else {
            phase_current *= gotway_negative;
            if !is_alexovik_fw {
                speed *= gotway_negative;
            }
        }

        // Calculate battery percentage (from the unscaled voltage)
        let battery = if config.use_custom_percents {
            calculate_better_percent(voltage)
        } else {
            calculate_standard_percent(voltage)
        };

        // Apply ratio if configured (some boards report inflated values)
        if config.use_ratio {
            speed = round_to_i32(speed as f64 * RATIO_GW);
            distance = round_to_i32(distance as f64 * RATIO_GW) as i64;
        }

        // Normalize to metric when the wheel reports in miles
        if gw.in_miles {
            speed = round_to_i32(speed as f64 / KM_TO_MILES_MULTIPLIER);
            distance = round_to_i64(distance as f64 / KM_TO_MILES_MULTIPLIER);
        }

        // Scale voltage based on wheel configuration
        voltage = round_to_i32(self.scale_voltage(voltage, config));

        // Track that we've received valid live data (for is_ready check)
        if voltage > 0 {
            self.has_received_data = true;
        }

        // Standard GW/JN/JL firmware uses bytes 14-15 as a status bitfield; CF
        // firmware uses the same word as PWM in 0.1% units. Until a frame 0x07
        // provides actual battery current, standard firmware retains the last
        // known current instead of multiplying status bits by phase current.
        let calculated_pwm = if self.true_pwm {
            tel.calculated_pwm
        } else if self.fw_prot == "Freestyl3r" {
            frame0_status_or_pwm.abs() as f64 / 1000.0
        } else {
            self.calculate_speed_based_pwm(speed, voltage, config)
        };
        let output = if self.true_pwm {
            tel.output
        } else {
            round_to_i32(calculated_pwm * 10000.0)
        };
        let current = if is_alexovik_fw && self.true_current {
            self.alexovik_current
        } else if self.true_current {
            tel.current
        } else if self.fw_prot == "Freestyl3r" {
            round_to_i32(calculated_pwm * phase_current as f64)
        } else {
            estimate_battery_current(speed, phase_current)
        };
        let power = round_to_i32((current as f64 / 100.0) * voltage as f64);

        let new_tel = TelemetryState {
            speed,
            voltage: if !(self.true_voltage && auto_voltage) {
                voltage
            } else {
                tel.voltage
            },
            phase_current,
            current,
            power,
            output,
            calculated_pwm,
            temperature,
            wheel_distance: distance,
            battery_level: battery,
            ..tel.clone()
        };

        let new_identity = WheelIdentity {
            wheel_type: WheelType::Gotway,
            model: {
                let model_dn = self.model_display_name();
                if model_dn.is_empty() {
                    current_state.identity.model.clone()
                } else {
                    model_dn
                }
            },
            brand: self.brand_display_name(),
            ..current_state.identity.clone()
        };

        let new_settings = BegodeSettings {
            beeper_volume: if (0..=9).contains(&beeper_volume) {
                beeper_volume
            } else {
                gw.beeper_volume
            },
            ..gw
        };

        let has_new_data =
            !((self.true_voltage && auto_voltage) || self.true_current) || is_alexovik_fw;

        FrameResult {
            telemetry: Some(new_tel),
            identity: Some(new_identity),
            settings: Some(WheelSettings::Begode(new_settings)),
            has_new_data,
            ..Default::default()
        }
    }

    /// Frame type 0x01: extended data (true voltage, BMS temps).
    fn process_extended_frame(
        &mut self,
        buff: &[u8],
        current_state: &DecoderState,
        config: &DecoderConfig,
        is_alexovik_fw: bool,
    ) -> FrameResult {
        if is_alexovik_fw {
            return FrameResult {
                has_new_data: false,
                ..Default::default()
            };
        }

        let tel = &current_state.telemetry;
        let auto_voltage = config.auto_voltage && !is_alexovik_fw;

        // Compute has_new_data BEFORE setting the flag (matches legacy timing)
        let has_new_data = !self.true_current && self.true_voltage;
        self.true_voltage = true;
        let bat_voltage = short_from_bytes_be(buff, 6);
        let bms_num = buff[19] as i32;
        let bms = match bms_num {
            0 => Some(&mut self.bms1),
            1 => Some(&mut self.bms2),
            2 => Some(&mut self.bms3),
            3 => Some(&mut self.bms4),
            _ => None,
        };

        if let Some(bms) = bms {
            let bms_current_val = signed_short_from_bytes_be(buff, 8);
            bms.current = bms_current_val as f64 / 10.0;
            bms.temp1 = signed_short_from_bytes_be(buff, 10) as f64;
            bms.temp2 = signed_short_from_bytes_be(buff, 12) as f64;
            bms.semi_voltage1 = signed_short_from_bytes_be(buff, 14) as f64 / 10.0;
        }

        FrameResult {
            telemetry: Some(TelemetryState {
                voltage: if auto_voltage {
                    bat_voltage * 10
                } else {
                    tel.voltage
                },
                ..tel.clone()
            }),
            has_new_data,
            ..Default::default()
        }
    }

    /// Frame types 0x02/0x03/0x05/0x06: BMS cell voltages for packs 1-4.
    fn process_bms_cells_frame(&mut self, buff: &[u8], frame_type: i32) -> FrameResult {
        let bms = match frame_type {
            FRAME_BMS_CELLS_1 => &mut self.bms1,
            FRAME_BMS_CELLS_2 => &mut self.bms2,
            FRAME_BMS_CELLS_3 => &mut self.bms3,
            FRAME_BMS_CELLS_4 => &mut self.bms4,
            _ => {
                return FrameResult {
                    has_new_data: false,
                    ..Default::default()
                }
            }
        };
        let p_num = buff[19] as i32;

        for i in 0..8 {
            let cell_num = i + p_num * 8;
            if cell_num as usize >= bms.cells.len() {
                break;
            }
            let cell_val = short_from_bytes_be(buff, ((i + 1) * 2) as usize) as f64 / 1000.0;
            bms.cells[cell_num as usize] = cell_val;
            if cell_val > 0.0 {
                bms.cell_num = bms.cell_num.max(cell_num + 1);
            }
        }

        bms.recalculate_cell_stats(true);

        FrameResult {
            has_new_data: false,
            ..Default::default()
        }
    }

    /// Frame type 0x04: total distance and settings.
    fn process_total_distance_frame(
        &mut self,
        buff: &[u8],
        current_state: &DecoderState,
        config: &DecoderConfig,
        is_alexovik_fw: bool,
    ) -> FrameResult {
        let tel = &current_state.telemetry;
        let mut total_distance = get_int4(buff, 2);
        if config.use_ratio {
            total_distance = round_to_i32(total_distance as f64 * RATIO_GW) as i64;
        }

        if !is_alexovik_fw {
            let gw = begode_settings(&current_state.settings);
            let settings = short_from_bytes_be(buff, 6);
            let pedals_mode = (settings >> 13) & 0x03;
            let speed_alarms = (settings >> 10) & 0x03;
            let roll_angle = (settings >> 7) & 0x03;
            let in_miles = settings & 0x01;
            let _power_off_time = short_from_bytes_be(buff, 8);
            let mut tilt_back_speed = short_from_bytes_be(buff, 10);
            if tilt_back_speed >= 100 {
                tilt_back_speed = 0;
            }
            let alert = buff[14] as i32;
            let led_mode = buff[13] as i32;
            let light_mode = (buff[15] as i32) & 0x03;

            // Build alert string
            let mut alert_builder = String::new();
            let wheel_alarm = (alert & 0x01) == 1;
            if (alert >> 1) & 0x01 == 1 {
                alert_builder.push_str("Speed2 ");
            }
            if (alert >> 2) & 0x01 == 1 {
                alert_builder.push_str("Speed1 ");
            }
            if (alert >> 3) & 0x01 == 1 {
                alert_builder.push_str("LowVoltage ");
            }
            if (alert >> 4) & 0x01 == 1 {
                alert_builder.push_str("OverVoltage ");
            }
            if (alert >> 5) & 0x01 == 1 {
                alert_builder.push_str("OverTemperature ");
            }
            if (alert >> 6) & 0x01 == 1 {
                alert_builder.push_str("errHallSensors ");
            }
            if (alert >> 7) & 0x01 == 1 {
                alert_builder.push_str("TransportMode");
            }

            let alert_line = alert_builder.trim().to_string();
            let news = if !alert_line.is_empty() {
                Some(alert_line.clone())
            } else {
                None
            };

            // Normalize to metric when the wheel reports in miles
            let is_miles = in_miles == 1;
            if is_miles {
                total_distance = round_to_i64(total_distance as f64 / KM_TO_MILES_MULTIPLIER);
            }

            let settings_update = if self.consume_settings_echo_suppression() {
                None
            } else {
                Some(WheelSettings::Begode(BegodeSettings {
                    pedals_mode: 2 - pedals_mode,
                    speed_alarms,
                    roll_angle,
                    tilt_back_speed,
                    light_mode,
                    led_mode,
                    in_miles: is_miles,
                    ..gw
                }))
            };

            return FrameResult {
                telemetry: Some(TelemetryState {
                    total_distance,
                    wheel_alarm,
                    alert: alert_line,
                    ..tel.clone()
                }),
                settings: settings_update,
                has_new_data: false,
                news,
                ..Default::default()
            };
        }

        FrameResult {
            telemetry: Some(TelemetryState {
                total_distance,
                ..tel.clone()
            }),
            has_new_data: false,
            ..Default::default()
        }
    }

    /// Frame type 0x07: battery current, motor temperature, and cutout angle.
    fn process_current_temp_frame(
        &mut self,
        buff: &[u8],
        current_state: &DecoderState,
        is_alexovik_fw: bool,
        gotway_negative: i32,
    ) -> FrameResult {
        if is_alexovik_fw {
            return FrameResult {
                has_new_data: false,
                ..Default::default()
            };
        }

        let tel = &current_state.telemetry;
        let gw = begode_settings(&current_state.settings);

        // Compute has_new_data BEFORE setting the flag (matches legacy timing)
        let has_new_data = self.true_current;
        self.true_current = true;
        let battery_current = signed_short_from_bytes_be(buff, 2);
        let cutout_step = short_from_bytes_be(buff, 4); // 0-9 → 45-90° in 5° increments
        let cutout_angle = if (0..=9).contains(&cutout_step) {
            cutout_step * 5 + 45
        } else {
            -1
        };
        let motor_temp = signed_short_from_bytes_be(buff, 6);
        let mut hw_pwm = signed_short_from_bytes_be(buff, 8);

        // Hardware PWM reporting was added in BG firmware after 09.2024. Older
        // firmwares put unrelated data here (observed constant 320); only trust
        // the field when its value is inside the legal duty-cycle range.
        if (1..=100).contains(&hw_pwm) {
            self.true_pwm = true;
        }

        if self.true_pwm {
            hw_pwm = if gotway_negative == 0 {
                hw_pwm.abs()
            } else {
                -(hw_pwm * gotway_negative)
            };
        }

        let current = -battery_current;
        let power = round_to_i32((current as f64 / 100.0) * tel.voltage as f64);
        let output = if self.true_pwm { hw_pwm * 100 } else { tel.output };
        let calculated_pwm = if self.true_pwm {
            output as f64 / 10000.0
        } else {
            tel.calculated_pwm
        };

        FrameResult {
            telemetry: Some(TelemetryState {
                current,
                power,
                temperature2: motor_temp * 100,
                output,
                calculated_pwm,
                ..tel.clone()
            }),
            settings: Some(WheelSettings::Begode(BegodeSettings {
                cutout_angle,
                ..gw
            })),
            has_new_data,
            ..Default::default()
        }
    }

    fn calculate_speed_based_pwm(&self, speed: i32, voltage: i32, config: &DecoderConfig) -> f64 {
        let speed_kmh = speed.abs() as f64 / 100.0;
        if speed_kmh == 0.0 || voltage <= 0 {
            return 0.0;
        }

        let profile = &self.model_profile;
        let has_speed_override = config.rotation_speed != DEFAULT_ROTATION_SPEED;
        let has_voltage_override = config.rotation_voltage != DEFAULT_ROTATION_VOLTAGE;
        let has_power_factor_override = config.power_factor != DEFAULT_POWER_FACTOR;

        let reference_speed_kmh = if has_speed_override {
            config.rotation_speed as f64 / 10.0
        } else if let Some(no_load) = profile.as_ref().and_then(|p| p.no_load_speed_kmh) {
            no_load
        } else {
            config.rotation_speed as f64 / 10.0
        };
        let reference_voltage_v = if has_voltage_override {
            config.rotation_voltage as f64 / 10.0
        } else if let Some(p) = profile {
            p.full_voltage_v
        } else {
            config.rotation_voltage as f64 / 10.0
        };
        let power_factor = if has_power_factor_override {
            config.power_factor as f64 / 100.0
        } else if profile.as_ref().and_then(|p| p.no_load_speed_kmh).is_some() {
            1.0
        } else {
            config.power_factor as f64 / 100.0
        };
        if reference_speed_kmh <= 0.0 || reference_voltage_v <= 0.0 || power_factor <= 0.0 {
            return 0.0;
        }
        let available_speed =
            reference_speed_kmh * ((voltage as f64 / 100.0) / reference_voltage_v) * power_factor;
        if available_speed > 0.0 {
            speed_kmh / available_speed
        } else {
            0.0
        }
    }

    fn scale_voltage(&self, voltage: i32, config: &DecoderConfig) -> f64 {
        let scaler = match config.gotway_voltage {
            -1 => self
                .model_profile
                .as_ref()
                .map(|p| p.full_voltage_v / 67.2)
                .unwrap_or(1.25),
            0 => 1.0,                  // 67.2V (16S)
            1 => 1.25,                 // 84V (20S)
            2 => 1.5,                  // 100.8V (24S)
            3 => 1.738_095_238_095_238_1, // 126V (28S)
            4 => 2.0,                  // 134.4V (32S)
            5 => 2.5,                  // 168V (40S)
            6 => 2.25,                 // 151V (36S)
            7 => 0.625,                // 42V (10S)
            8 => 3.125,                // 210V (50S)
            _ => 1.0,
        };
        voltage as f64 * scaler
    }

    fn arm_settings_echo_suppression(&mut self, frames: i32) {
        self.settings_echo_frames_to_ignore = frames;
    }

    fn consume_settings_echo_suppression(&mut self) -> bool {
        if self.settings_echo_frames_to_ignore <= 0 {
            return false;
        }
        self.settings_echo_frames_to_ignore -= 1;
        true
    }

    pub fn is_ready(&self) -> bool {
        self.is_ready && self.has_received_data
    }

    pub fn get_capabilities(&self) -> CapabilitySet {
        if !self.is_ready {
            return CapabilitySet::default();
        }
        CapabilitySet {
            supported_commands: supported_commands(),
            detected_model: self.model_display_name(),
            firmware_version: self.fw.clone(),
            is_resolved: true,
        }
    }

    pub fn get_unpacker_stats(&self) -> UnpackerStats {
        self.unpacker.stats()
    }

    pub fn reset(&mut self) {
        self.unpacker.reset();
        self.model.clear();
        self.imu.clear();
        self.fw.clear();
        self.fw_prot.clear();
        self.firmware_signature.clear();
        self.model_profile = None;
        self.true_voltage = false;
        self.true_current = false;
        self.true_pwm = false;
        self.is_ready = false;
        self.has_received_data = false;
        self.alexovik_current = 0;
        self.info_attempt = 0;
        self.settings_echo_frames_to_ignore = 0;
        self.bms1 = SmartBms::default();
        self.bms2 = SmartBms::default();
        self.bms3 = SmartBms::default();
        self.bms4 = SmartBms::default();
    }

    pub fn build_command(&mut self, command: &WheelCommand) -> Vec<WheelCommand> {
        fn ascii(s: &str) -> Vec<u8> {
            s.as_bytes().to_vec()
        }
        match command {
            WheelCommand::Beep => vec![WheelCommand::SendBytes(ascii("b"))],
            WheelCommand::SetLight(enabled) => {
                let mode = if *enabled { 1 } else { 0 };
                self.build_command(&WheelCommand::SetLightMode(mode))
            }
            WheelCommand::SetLightMode(mode) => {
                // 0=off("E"), 1=on("Q"), 2=strobe("T")
                let cmd = match mode {
                    1 => "Q",
                    2 => "T",
                    _ => "E",
                };
                self.arm_settings_echo_suppression(2);
                vec![WheelCommand::SendBytes(ascii(cmd))]
            }
            WheelCommand::SetPedalsMode(mode) => {
                // 0=hard("h"), 1=fast("f"), 2=soft("s"), 3=intermediate("i")
                let cmd = match mode {
                    0 => "h",
                    1 => "f",
                    2 => "s",
                    3 => "i",
                    _ => return Vec::new(),
                };
                self.arm_settings_echo_suppression(2);
                vec![WheelCommand::SendBytes(ascii(cmd))]
            }
            WheelCommand::SetMilesMode(enabled) => {
                let cmd = if *enabled { "m" } else { "g" };
                self.arm_settings_echo_suppression(2);
                vec![WheelCommand::SendBytes(ascii(cmd))]
            }
            WheelCommand::SetWheelDisplayUnit { miles } => {
                self.build_command(&WheelCommand::SetMilesMode(*miles))
            }
            WheelCommand::SetRollAngleMode(mode) => {
                // 0=normal(">"), 1=equal("="), 2=reverse("<")
                let cmd = match mode {
                    0 => ">",
                    1 => "=",
                    2 => "<",
                    _ => return Vec::new(),
                };
                self.arm_settings_echo_suppression(2);
                vec![WheelCommand::SendBytes(ascii(cmd))]
            }
            WheelCommand::SetLedMode(mode) => {
                // Multi-step: W, then M 100ms later, digit 100ms later, b 100ms later
                let param = vec![((mode % 10) + 0x30) as u8];
                self.arm_settings_echo_suppression(5);
                vec![
                    WheelCommand::SendBytes(ascii("W")),
                    WheelCommand::SendDelayed(ascii("M"), 100),
                    WheelCommand::SendDelayed(param, 100),
                    WheelCommand::SendDelayed(ascii("b"), 100),
                ]
            }
            WheelCommand::SetBeeperVolume(volume) => {
                // Begode app BLE capture confirms 3 bytes only: 57 42 3x
                let param = vec![((volume % 10) + 0x30) as u8];
                vec![
                    WheelCommand::SendBytes(ascii("W")),
                    WheelCommand::SendDelayed(ascii("B"), 100),
                    WheelCommand::SendDelayed(param, 100),
                ]
            }
            WheelCommand::SetCutoutAngle(angle) => {
                // Angle 45-90° in 5° steps → digit 0-9: (angle - 45) / 5
                let step = ((angle - 45) / 5).clamp(0, 9);
                let param = vec![(step + 0x30) as u8];
                vec![
                    WheelCommand::SendBytes(ascii("W")),
                    WheelCommand::SendDelayed(ascii("X"), 200),
                    WheelCommand::SendDelayed(param, 200),
                ]
            }
            WheelCommand::SetAlarmMode(mode) => {
                // 0=two alarms("o"), 1=one alarm("u"), 2=off("i"), 3=CF tiltback("I")
                let cmd = match mode {
                    0 => "o",
                    1 => "u",
                    2 => "i",
                    3 => "I",
                    _ => return Vec::new(),
                };
                self.arm_settings_echo_suppression(2);
                vec![WheelCommand::SendBytes(ascii(cmd))]
            }
            WheelCommand::Calibrate => vec![
                WheelCommand::SendBytes(ascii("c")),
                WheelCommand::SendDelayed(ascii("y"), 300),
            ],
            WheelCommand::SetPedalTilt(angle) => {
                // Slider value arrives ×10 (Lorin convention); Gotway uses raw 0-9
                let param = vec![((((angle / 10) % 10) + 0x30) as u8)];
                vec![
                    WheelCommand::SendBytes(ascii("W")),
                    WheelCommand::SendDelayed(ascii("U"), 100),
                    WheelCommand::SendDelayed(param, 100),
                ]
            }
            WheelCommand::SetWeakMagnetism(level) => {
                let param = vec![((level % 7) + 0x30) as u8];
                vec![
                    WheelCommand::SendBytes(ascii("W")),
                    WheelCommand::SendDelayed(ascii("C"), 100),
                    WheelCommand::SendDelayed(param, 100),
                ]
            }
            WheelCommand::SetExtendedRollAngle(level) => {
                let param = vec![((level % 10) + 0x30) as u8];
                vec![
                    WheelCommand::SendBytes(ascii("W")),
                    WheelCommand::SendDelayed(ascii("R"), 100),
                    WheelCommand::SendDelayed(param, 100),
                ]
            }
            WheelCommand::SetPowerAlarm(percentage) => {
                let hhh = vec![((percentage / 10) + 0x30) as u8];
                let lll = vec![((percentage % 10) + 0x30) as u8];
                vec![
                    WheelCommand::SendBytes(ascii("W")),
                    WheelCommand::SendDelayed(ascii("P"), 100),
                    WheelCommand::SendDelayed(hhh, 100),
                    WheelCommand::SendDelayed(lll, 100),
                ]
            }
            WheelCommand::SetPlateProtection(enabled) => {
                let cmd = if *enabled { "x" } else { "e" };
                vec![WheelCommand::SendBytes(ascii(cmd))]
            }
            WheelCommand::SetMaxSpeed(speed) => {
                self.arm_settings_echo_suppression(5);
                if *speed != 0 {
                    let hhh = vec![((speed / 10) + 0x30) as u8];
                    let lll = vec![((speed % 10) + 0x30) as u8];
                    vec![
                        WheelCommand::SendBytes(ascii("b")),
                        WheelCommand::SendDelayed(ascii("W"), 100),
                        WheelCommand::SendDelayed(ascii("Y"), 100),
                        WheelCommand::SendDelayed(hhh, 100),
                        WheelCommand::SendDelayed(lll, 100),
                        WheelCommand::SendDelayed(ascii("b"), 100),
                        WheelCommand::SendDelayed(ascii("b"), 100),
                    ]
                } else {
                    vec![
                        WheelCommand::SendBytes(ascii("b")),
                        WheelCommand::SendDelayed(ascii("\""), 100),
                        WheelCommand::SendDelayed(ascii("b"), 100),
                        WheelCommand::SendDelayed(ascii("b"), 100),
                    ]
                }
            }
            // Kotlin: `else -> emptyList()` — commands other decoders handle
            _ => Vec::new(),
        }
    }

    pub fn get_init_commands(&self) -> Vec<WheelCommand> {
        // Request firmware version and name
        vec![
            WheelCommand::SendBytes(b"V".to_vec()),
            WheelCommand::SendDelayed(b"b".to_vec(), 100),
            WheelCommand::SendDelayed(b"N".to_vec(), 200),
            WheelCommand::SendDelayed(b"b".to_vec(), 300),
        ]
    }
}

fn begode_settings(settings: &WheelSettings) -> BegodeSettings {
    // Kotlin: `currentState.settings as? WheelSettings.Begode ?: WheelSettings.Begode()`
    match settings {
        WheelSettings::Begode(s) => s.clone(),
        _ => BegodeSettings::default(),
    }
}

fn has_begode_pack_data(snapshot: &BmsSnapshot) -> bool {
    snapshot.cell_num > 0
        || snapshot.voltage > 0.0
        || snapshot.current != 0.0
        || snapshot.semi_voltage1 != 0.0
        || snapshot.semi_voltage2 != 0.0
}

fn calculate_better_percent(voltage: i32) -> i32 {
    if voltage > 6680 {
        100
    } else if voltage > 5440 {
        round_to_i32((voltage - 5320) as f64 / 13.6)
    } else if voltage > 5120 {
        (voltage - 5120) / 36
    } else {
        0
    }
}

fn calculate_standard_percent(voltage: i32) -> i32 {
    if voltage <= 5290 {
        0
    } else if voltage >= 6580 {
        100
    } else {
        (voltage - 5290) / 13
    }
}

/// EUC World fallback for wheels that never emit frame 0x07 battery current.
fn estimate_battery_current(speed: i32, phase_current: i32) -> i32 {
    let speed_kmh = speed.abs() as f64 / 100.0;
    let estimate = phase_current as f64 * (0.14 + speed_kmh / 100.0);
    round_to_i32(if phase_current < 0 {
        estimate * 0.5
    } else {
        estimate
    })
}

/// All Begode/Gotway wheels share the same command set.
pub fn supported_commands() -> Vec<SettingsCommandId> {
    use SettingsCommandId::*;
    Vec::from([
        LightMode,
        LedMode,
        PedalsMode,
        RollAngleMode,
        CutoutAngle,
        PedalTilt,
        WeakMagnetism,
        ExtendedRollAngle,
        BeeperVolume,
        PlateProtection,
        PowerAlarm,
        Calibrate,
        MaxSpeed,
        AlarmMode,
        WheelDisplayUnit,
    ])
}
