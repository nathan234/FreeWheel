//! Port of `VeteranDecoder.kt` — Veteran/Leaperkim protocol decoder.
//!
//! Supports Sherman/Sherman S/Sherman L, Abrams, Patton/Patton S, Lynx/Lynx S,
//! Oryx, and Nosfet Apex/Aero/Aeon/Xeno. Data streams immediately — no init
//! commands. Model is derived from the three-byte firmware version in the
//! first valid frame.
//!
//! Sans-io deviation from the Kotlin source: the Kotlin decoder reads the
//! system clock for time-sync and password commands (`Clock.System.now()`).
//! This port takes the wall-clock via [`VeteranDecoder::set_wall_clock`] —
//! the integration layer supplies it; the crate never reads a clock.

use crate::byte_utils::{
    int_from_bytes_be, int_from_bytes_rev_be, round_to_i32, short_from_bytes_be,
    signed_short_from_bytes_be,
};
use crate::checksums::crc32;
use crate::decode_loop::{FrameOutcome, FrameResult};
use crate::soc_tables;
use crate::types::{
    resolve_wheel_identity, BmsState, CapabilitySet, DecodeResult, DecodedData, DecoderConfig,
    DecoderState, EventLogEntry, SettingsCommandId, SmartBms, TelemetryState, UnhandledReason,
    VeteranSettings, WheelCommand, WheelIdentity, WheelSettings, WheelType,
};
use crate::unpacker::UnpackerStats;
use crate::byte_utils::bytes_to_hex;

/// Wall-clock components injected by the host for time-stamped commands.
/// `year` is the full year (e.g. 2026); the wire encodes `year - 2000`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct WallClock {
    pub year: i32,
    pub month: i32,
    pub day: i32,
    pub hour: i32,
    pub minute: i32,
    pub second: i32,
    pub tz_offset_hours: i32,
}

/// Looks up SOC using the step-table behavior in the manufacturer-family apps.
///
/// Values at or below the first entry map to 0%, values at or above the last
/// entry map to 100%, and voltages between entries map to the upper entry's
/// index (ceiling/step lookup, not interpolation).
pub fn lookup_soc(voltage: i32, table: &[i32]) -> i32 {
    if voltage <= table[0] {
        return 0;
    }
    if voltage >= table[table.len() - 1] {
        return 100;
    }
    let mut low = 1usize;
    let mut high = table.len() - 1;
    while low < high {
        let mid = (low + high) / 2;
        if table[mid] < voltage {
            low = mid + 1;
        } else {
            high = mid;
        }
    }
    low as i32
}

// ---------------------------------------------------------------------------
// Unpacker
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum State {
    Unknown,
    Collecting,
    LenSearch,
    Done,
}

/// Frame unpacker for Veteran/Leaperkim wheels.
///
/// Frame format: DC 5A 5C header, length byte, payload, and a trailing CRC32
/// for newer firmware (len > 38, latched via `using_crc` for the connection).
#[derive(Debug)]
pub struct VeteranUnpacker {
    buffer: Vec<u8>,
    old1: i32,
    old2: i32,
    len: i32,
    state: State,
    using_crc: bool,
    error_resets: i32,
    bytes_discarded: i32,
}

impl Default for VeteranUnpacker {
    fn default() -> Self {
        VeteranUnpacker {
            buffer: Vec::new(),
            old1: 0,
            old2: 0,
            len: 0,
            state: State::Unknown,
            using_crc: false,
            error_resets: 0,
            bytes_discarded: 0,
        }
    }
}

impl VeteranUnpacker {
    pub fn stats(&self) -> UnpackerStats {
        UnpackerStats {
            error_resets: self.error_resets,
            bytes_discarded: self.bytes_discarded,
        }
    }

    pub fn reset_stats(&mut self) {
        self.error_resets = 0;
        self.bytes_discarded = 0;
    }

    /// Note: the buffer is intentionally NOT cleared here — reset() runs after
    /// frame assembly but before get_buffer(); the buffer must stay intact
    /// until the caller reads it. It is cleared when a new header is detected.
    pub fn reset(&mut self) {
        self.old1 = 0;
        self.old2 = 0;
        self.state = State::Unknown;
    }

    /// Clear framing and session-format state when attaching to a new wheel.
    /// `reset` deliberately preserves `using_crc` between frames on one
    /// connection; carrying that latch to another wheel would make a legacy
    /// no-CRC frame fail validation.
    pub fn reset_connection(&mut self) {
        self.reset();
        self.buffer.clear();
        self.len = 0;
        self.using_crc = false;
    }

    /// A wheel frame normally begins at a BLE notification boundary. If a
    /// notification was lost while assembling the prior frame, a complete new
    /// header is stronger evidence than the stale declared length.
    pub fn prepare_for_chunk(&mut self, data: &[u8]) {
        let starts_with_header =
            data.len() >= 3 && data[0] == 0xDC && data[1] == 0x5A && data[2] == 0x5C;
        if starts_with_header && (self.state == State::Collecting || self.state == State::LenSearch)
        {
            self.error_resets += 1;
            self.bytes_discarded += self.buffer.len() as i32;
            self.buffer.clear();
            self.len = 0;
            self.old1 = 0;
            self.old2 = 0;
            self.state = State::Unknown;
        }
    }

    pub fn get_buffer(&self) -> Vec<u8> {
        self.buffer.clone()
    }

    pub fn add_char(&mut self, c: i32) -> bool {
        let byte = (c & 0xFF) as u8;

        match self.state {
            State::Collecting => {
                let bsize = self.buffer.len() as i32;

                // Classic Veteran frames do not carry a CRC. Preserve the three
                // manufacturer-compatible structural sentinels so random bytes
                // cannot be accepted solely because their length matches.
                let invalid_legacy_sentinel = (bsize == 22 && byte != 0)
                    || (bsize == 23 && (byte & 0xFE) != 0)
                    || (bsize == 30 && byte != 0 && byte != 0x07);
                if !self.using_crc && self.len <= 38 && invalid_legacy_sentinel {
                    self.error_resets += 1;
                    self.bytes_discarded += self.buffer.len() as i32 + 1;
                    self.state = State::Done;
                    self.reset();
                    return false;
                }

                self.buffer.push(byte);

                if bsize == self.len + 3 {
                    self.state = State::Done;
                    self.reset();

                    // Check CRC32 for the new format
                    if self.len > 38 || self.using_crc {
                        let data = self.get_buffer();
                        let calc_crc = crc32(&data, 0, self.len as usize);
                        let provided_crc = int_from_bytes_be(&data, self.len as usize);
                        if calc_crc == provided_crc {
                            self.using_crc = true;
                            return true;
                        }
                        // CRC mismatch — fully assembled frame discarded
                        self.error_resets += 1;
                        self.bytes_discarded += data.len() as i32;
                        return false;
                    }
                    return true; // old format without CRC
                }
            }

            State::LenSearch => {
                self.buffer.push(byte);
                self.len = byte as i32;
                self.state = State::Collecting;
                self.old2 = self.old1;
                self.old1 = byte as i32;
            }

            State::Unknown | State::Done => {
                // Looking for header (DC 5A 5C)
                if byte == 0x5C && self.old1 == 0x5A && self.old2 == 0xDC {
                    self.buffer.clear();
                    self.buffer.extend_from_slice(&[0xDC, 0x5A, 0x5C]);
                    self.state = State::LenSearch;
                } else if byte == 0x5A && self.old1 == 0xDC {
                    self.old2 = self.old1;
                } else {
                    self.old2 = 0;
                }
                self.old1 = byte as i32;
            }
        }

        false
    }
}

// ---------------------------------------------------------------------------
// Decoder
// ---------------------------------------------------------------------------

/// Sub-type extended data extracted from frames with byte 46 present.
#[derive(Debug, Clone, Default)]
struct SubTypeData {
    roll: Option<f64>,
    lock_state: Option<i32>,
    battery_override: Option<i32>,
    high_speed_mode: Option<bool>,
    low_voltage_mode: Option<bool>,
    voltage_correction: Option<i32>,
    transport_mode: Option<bool>,
    key_tone: Option<i32>,
    pedal_hardness: Option<i32>,
    stop_speed: Option<i32>,
    stop_power_rate: Option<i32>,
    screen_backlight_rate: Option<i32>,
    max_charge_vol: Option<i32>,
    brake_pressure_alarm: Option<i32>,
    lateral_cutoff_angle: Option<i32>,
    dynamic_assist: Option<i32>,
    acceleration_limit: Option<i32>,
    charge_voltage_base: Option<i32>,
    wheel_display_unit: Option<i32>,
}

pub struct VeteranDecoder {
    unpacker: VeteranUnpacker,
    has_synced_time: bool,
    m_ver: i32,
    manufacturer_model_version: i32,
    version: String,
    uses_wheel_reported_battery: bool,
    retained_wheel_battery: i32,
    bms1: SmartBms,
    bms2: SmartBms,
    receiving_log: bool,
    clock: WallClock,
}

impl Default for VeteranDecoder {
    fn default() -> Self {
        Self::new()
    }
}

/// Single source of truth for Veteran command support by mVer
/// (port of `VeteranDecoder.CAPABILITY_MAP`).
pub const CAPABILITY_MAP: &[(SettingsCommandId, i32)] = &[
    // mVer 0+ (all models — ASCII protocol fallback)
    (SettingsCommandId::LightMode, 0),
    (SettingsCommandId::PedalsMode, 0),
    (SettingsCommandId::Lock, 0),
    (SettingsCommandId::ResetTrip, 0),
    // mVer 3+ (LkAp/LdAp binary protocol)
    (SettingsCommandId::AlarmSpeed1, 3),
    (SettingsCommandId::PedalTilt, 3),
    (SettingsCommandId::TransportMode, 3),
    (SettingsCommandId::HighSpeedMode, 3),
    (SettingsCommandId::LowVoltageMode, 3),
    (SettingsCommandId::KeyTone, 3),
    (SettingsCommandId::ScreenBacklight, 3),
    (SettingsCommandId::StopSpeed, 3),
    (SettingsCommandId::VeteranPwmLimit, 3),
    (SettingsCommandId::VoltageCorrection, 3),
    (SettingsCommandId::MaxChargeVoltage, 3),
    (SettingsCommandId::BrakePressureAlarm, 3),
    (SettingsCommandId::LateralCutoffAngle, 3),
    (SettingsCommandId::DynamicAssist, 3),
    (SettingsCommandId::AccelerationLimit, 3),
    (SettingsCommandId::WheelDisplayUnit, 3),
    (SettingsCommandId::PedalHardness, 3),
    (SettingsCommandId::Calibrate, 3),
    (SettingsCommandId::PowerOff, 3),
];

impl VeteranDecoder {
    pub fn new() -> Self {
        VeteranDecoder {
            unpacker: VeteranUnpacker::default(),
            has_synced_time: false,
            m_ver: 0,
            manufacturer_model_version: 0,
            version: String::new(),
            uses_wheel_reported_battery: false,
            retained_wheel_battery: 0,
            bms1: SmartBms::default(),
            bms2: SmartBms::default(),
            receiving_log: false,
            clock: WallClock::default(),
        }
    }

    pub fn wheel_type(&self) -> WheelType {
        WheelType::Veteran
    }

    /// Supply the current wall-clock for time-sync and password commands.
    pub fn set_wall_clock(&mut self, clock: WallClock) {
        self.clock = clock;
    }

    pub fn decode(
        &mut self,
        data: &[u8],
        current_state: &DecoderState,
        config: &DecoderConfig,
    ) -> DecodeResult {
        self.unpacker.prepare_for_chunk(data);

        let loop_result = self.decode_frames(data, current_state, config);

        match loop_result {
            DecodeResult::Success(loop_data) => {
                // The official app calls syncTime() on every received heartbeat
                // regardless of firmware version; we emit it once per connection.
                let extra_commands = if !self.has_synced_time {
                    self.has_synced_time = true;
                    self.build_time_sync_commands()
                } else {
                    Vec::new()
                };
                let bms_snapshot = BmsState {
                    bms1: Some(self.bms1.to_snapshot()),
                    bms2: Some(self.bms2.to_snapshot()),
                    bms3: None,
                    bms4: None,
                };
                let resolved_identity = resolve_wheel_identity(
                    loop_data.identity.clone(),
                    &current_state.identity,
                    WheelType::Veteran,
                );
                let mut commands = loop_data.commands;
                commands.extend(extra_commands);
                DecodeResult::Success(DecodedData {
                    telemetry: loop_data.telemetry,
                    identity: resolved_identity.filter(|i| *i != current_state.identity),
                    bms: Some(bms_snapshot).filter(|b| *b != current_state.bms),
                    settings: loop_data
                        .settings
                        .filter(|s| *s != current_state.settings),
                    commands,
                    has_new_data: loop_data.has_new_data,
                    news: None,
                    frame_types: loop_data.frame_types,
                    log_entries: loop_data.log_entries,
                })
            }
            other => other,
        }
    }

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
        let mut log_entries: Vec<EventLogEntry> = Vec::new();

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
                        log_entries.extend(result.log_entries);
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
                log_entries,
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
        if buff.len() < 36 {
            return FrameOutcome::Unrecognized(format!("size={}", buff.len()));
        }

        let veteran_negative = config.gotway_negative;
        let tel = &current_state.telemetry;
        let vet = veteran_settings(&current_state.settings);

        let voltage = short_from_bytes_be(buff, 4);
        let mut speed = signed_short_from_bytes_be(buff, 6) * 10;
        let distance = int_from_bytes_rev_be(buff, 8) as i64;
        let total_distance = int_from_bytes_rev_be(buff, 12) as i64;
        let mut phase_current = signed_short_from_bytes_be(buff, 16) * 10;
        let temperature = signed_short_from_bytes_be(buff, 18);
        let auto_off_sec = short_from_bytes_be(buff, 20);
        let charge_mode = short_from_bytes_be(buff, 22);
        let speed_alert = short_from_bytes_be(buff, 24) * 10;
        let speed_tiltback = short_from_bytes_be(buff, 26) * 10;

        // The official apps reconstruct a three-byte decimal firmware version
        // in the order byte 30, byte 28, byte 29. Leaperkim uses a zero high
        // byte; Nosfet uses 0x07, producing families 501/502/503/504.
        let full_version =
            ((buff[30] as i32) << 16) | ((buff[28] as i32) << 8) | (buff[29] as i32);
        self.manufacturer_model_version = full_version / 1000;
        self.m_ver = normalize_model_version(self.manufacturer_model_version);
        let version_digits = format!("{full_version:06}");
        self.version = format!(
            "{}.{}.{}",
            &version_digits[0..3],
            &version_digits[3..4],
            &version_digits[4..6]
        );

        // Byte 31 is the pedals/ride mode. Nosfet sends 0x80 when unsupported.
        let pedals_raw = buff[31] as i32;
        let pedals_mode = match pedals_raw {
            1 => 2, // wire soft -> app soft
            2 => 1, // wire medium -> app medium
            3 => 0, // wire hard -> app hard
            _ => -1,
        };
        let pitch_angle = signed_short_from_bytes_be(buff, 32);
        let hw_pwm = short_from_bytes_be(buff, 34);
        // Battery temp mode: bitmask where 111=normal, 100/101/110=high-temp
        // zone. Nosfet writes 0x80 (not-supported) at byte 36 → cap to range.
        let battery_temp_raw = if buff.len() >= 38 {
            short_from_bytes_be(buff, 36)
        } else {
            0
        };
        let battery_temp_mode = if (0..=111).contains(&battery_temp_raw) {
            battery_temp_raw
        } else {
            0
        };

        // Process SmartBMS data for newer wheels
        if self.m_ver >= 5 && buff.len() > 46 {
            self.process_bms_data(buff);
        }

        // Calculate battery percentage
        let voltage_battery = self.calculate_battery_percent(voltage);

        // Apply polarity
        if veteran_negative == 0 {
            speed = speed.abs();
            phase_current = phase_current.abs();
        } else {
            speed *= veteran_negative;
            phase_current *= veteran_negative;
        }

        // Calculate current and power
        let calculated_pwm: f64;
        let output: i32;
        if config.hw_pwm_enabled {
            output = hw_pwm;
            calculated_pwm = hw_pwm as f64 / 10000.0;
        } else {
            let rot_ratio = config.rotation_speed as f64 / config.rotation_voltage as f64;
            let denominator = rot_ratio * voltage as f64 * config.power_factor as f64;
            calculated_pwm = if denominator != 0.0 {
                speed as f64 / denominator
            } else {
                0.0
            };
            output = round_to_i32(calculated_pwm * 10000.0);
        }
        let current = round_to_i32(calculated_pwm * phase_current as f64);
        let power = round_to_i32((current as f64 / 100.0) * voltage as f64);

        // Parse sub-type extended data for newer wheels
        let sub_type = if self.m_ver >= 5 && buff.len() > 46 {
            Some(buff[46] as i32)
        } else {
            None
        };
        let sub_data = if sub_type.is_some() {
            self.parse_sub_type_data(buff)
        } else {
            None
        };
        let battery = self.resolve_battery_level(
            voltage_battery,
            sub_type,
            sub_data.as_ref().and_then(|d| d.battery_override),
        );

        let new_tel = TelemetryState {
            speed,
            voltage,
            phase_current,
            current,
            power,
            temperature,
            wheel_distance: distance,
            total_distance,
            battery_level: battery,
            charging_status: charge_mode,
            output,
            calculated_pwm,
            angle: pitch_angle as f64 / 100.0,
            roll: sub_data.as_ref().and_then(|d| d.roll).unwrap_or(tel.roll),
            ..tel.clone()
        };

        let new_identity = WheelIdentity {
            version: self.version.clone(),
            model: self.model_name().to_string(),
            wheel_type: WheelType::Veteran,
            ..current_state.identity.clone()
        };

        let mut new_settings = VeteranSettings {
            tilt_back_speed: speed_tiltback / 10,
            alert_speed: speed_alert / 10,
            auto_off_time: auto_off_sec,
            pedals_mode,
            battery_temp_mode,
            m_ver: self.m_ver,
            ..vet.clone()
        };

        // Merge sub-type settings data
        if let Some(sub) = &sub_data {
            new_settings = VeteranSettings {
                lock_state: sub.lock_state.unwrap_or(vet.lock_state),
                high_speed_mode: sub.high_speed_mode.or(vet.high_speed_mode),
                low_voltage_mode: sub.low_voltage_mode.or(vet.low_voltage_mode),
                voltage_correction: sub.voltage_correction.unwrap_or(vet.voltage_correction),
                transport_mode: sub.transport_mode.or(vet.transport_mode),
                key_tone: sub.key_tone.unwrap_or(vet.key_tone),
                pedal_sensitivity: sub.pedal_hardness.unwrap_or(vet.pedal_sensitivity),
                stop_speed: sub.stop_speed.unwrap_or(vet.stop_speed),
                pwm_limit: sub.stop_power_rate.unwrap_or(vet.pwm_limit),
                screen_backlight: sub.screen_backlight_rate.unwrap_or(vet.screen_backlight),
                max_charge_voltage: sub.max_charge_vol.unwrap_or(vet.max_charge_voltage),
                brake_pressure_alarm: sub
                    .brake_pressure_alarm
                    .unwrap_or(vet.brake_pressure_alarm),
                lateral_cutoff_angle: sub
                    .lateral_cutoff_angle
                    .unwrap_or(vet.lateral_cutoff_angle),
                dynamic_assist: sub.dynamic_assist.unwrap_or(vet.dynamic_assist),
                acceleration_limit: sub.acceleration_limit.unwrap_or(vet.acceleration_limit),
                charge_voltage_base: sub.charge_voltage_base.unwrap_or(vet.charge_voltage_base),
                wheel_display_unit: sub.wheel_display_unit.unwrap_or(vet.wheel_display_unit),
                ..new_settings
            };
        }

        // Parse event log entries when in log-receiving mode
        let p_num = if buff.len() > 46 {
            (buff[46] as i8) as i32
        } else {
            -1
        };
        let log_entries = if self.receiving_log {
            self.parse_log_entries(buff, p_num)
        } else {
            Vec::new()
        };

        FrameOutcome::Processed(FrameResult {
            telemetry: Some(new_tel),
            identity: Some(new_identity),
            settings: Some(WheelSettings::Veteran(new_settings)),
            has_new_data: true,
            frame_type: Some("TELEMETRY"),
            log_entries,
            ..Default::default()
        })
    }

    fn parse_sub_type_data(&self, buff: &[u8]) -> Option<SubTypeData> {
        if buff.len() <= 46 {
            return None;
        }
        let p_num = (buff[46] as i8) as i32;

        match p_num {
            0 | 4 => {
                // Roll angle (left-right) at bytes 67-68
                if buff.len() > 68 {
                    let roll_raw = signed_short_from_bytes_be(buff, 67);
                    Some(SubTypeData {
                        roll: Some(roll_raw as f64 / 100.0),
                        ..Default::default()
                    })
                } else {
                    None
                }
            }
            5 => {
                // Lock state at byte 51
                if buff.len() > 51 {
                    Some(SubTypeData {
                        lock_state: Some(buff[51] as i32),
                        ..Default::default()
                    })
                } else {
                    None
                }
            }
            2 => {
                // Fall protection angle at byte 47 (0 = not set)
                let angle = if buff.len() > 47 {
                    let raw = buff[47] as i32;
                    if raw == 0 {
                        None
                    } else {
                        Some(raw)
                    }
                } else {
                    None
                };
                // Battery % override at byte 50
                if buff.len() > 50 {
                    let pct = buff[50] as i32;
                    if (0..=100).contains(&pct) {
                        Some(SubTypeData {
                            battery_override: Some(pct),
                            lateral_cutoff_angle: angle,
                            ..Default::default()
                        })
                    } else {
                        Some(SubTypeData {
                            lateral_cutoff_angle: angle,
                            ..Default::default()
                        })
                    }
                } else {
                    Some(SubTypeData {
                        lateral_cutoff_angle: angle,
                        ..Default::default()
                    })
                }
            }
            8 => Some(self.parse_control_settings(buff)),
            _ => None,
        }
    }

    /// Control settings from sub-type 8. 0x80 means "not supported" per field.
    fn parse_control_settings(&self, buff: &[u8]) -> SubTypeData {
        const NOT_SUPPORTED: i32 = 0x80;

        let read_unsigned = |offset: usize| -> Option<i32> {
            if buff.len() <= offset {
                return None;
            }
            let raw = buff[offset] as i32;
            if raw == NOT_SUPPORTED {
                None
            } else {
                Some(raw)
            }
        };
        let read_signed = |offset: usize| -> Option<i32> {
            if buff.len() <= offset {
                return None;
            }
            let raw = (buff[offset] as i8) as i32; // signed byte (-128..127)
            if (raw & 0xFF) == NOT_SUPPORTED {
                None
            } else {
                Some(raw)
            }
        };
        let read_bool = |offset: usize| -> Option<bool> { read_unsigned(offset).map(|raw| raw != 0) };

        let nosfet = self.is_nosfet_model();
        SubTypeData {
            pedal_hardness: read_unsigned(50), // byte 50: pedal hardness 0-100
            stop_speed: read_unsigned(52),     // byte 52: stop speed (raw, +10 encoding)
            stop_power_rate: read_unsigned(53), // byte 53: PWM limit (raw, +30 encoding)
            screen_backlight_rate: read_unsigned(55), // byte 55: screen backlight 0-100%
            transport_mode: read_bool(57),     // byte 57: transport mode
            wheel_display_unit: read_unsigned(58), // byte 58: 0=km, 1=miles
            voltage_correction: read_signed(59), // byte 59: signed -15..+15
            low_voltage_mode: read_bool(60),   // byte 60
            high_speed_mode: read_bool(61),    // byte 61
            key_tone: read_unsigned(63),       // byte 63: key tone 0-100%
            max_charge_vol: read_unsigned(64), // byte 64: max charge voltage (0-120)
            charge_voltage_base: if nosfet {
                None
            } else {
                read_unsigned(65).map(|v| if v == 0 { 145 } else { v })
            },
            dynamic_assist: if nosfet { None } else { read_unsigned(66) },
            acceleration_limit: if nosfet { None } else { read_unsigned(68) },
            brake_pressure_alarm: read_unsigned(if nosfet { 65 } else { 69 }),
            ..Default::default()
        }
    }

    fn process_bms_data(&mut self, buff: &[u8]) {
        let p_num = (buff[46] as i8) as i32;
        let cells_for_wheel = self.cells_for_wheel();

        match p_num {
            // BMS current data
            0 | 4 if buff.len() > 72 => {
                self.bms1.current = signed_short_from_bytes_be(buff, 69) as f64 / 100.0;
                self.bms2.current = signed_short_from_bytes_be(buff, 71) as f64 / 100.0;
            }
            1 | 5 => {
                // First 15 cells
                let bms = if p_num < 4 { &mut self.bms1 } else { &mut self.bms2 };
                for i in 0..15 {
                    let cell = signed_short_from_bytes_be(buff, 53 + i * 2);
                    bms.cells[i] = cell as f64 / 1000.0;
                }
            }
            2 | 6 => {
                // Cells 15-29
                let bms = if p_num < 4 { &mut self.bms1 } else { &mut self.bms2 };
                for i in 0..15 {
                    let cell = short_from_bytes_be(buff, 53 + i * 2);
                    bms.cells[i + 15] = cell as f64 / 1000.0;
                }
            }
            3 | 7 => {
                // Cells 30+ and temperatures
                let bms = if p_num < 4 { &mut self.bms1 } else { &mut self.bms2 };
                for i in 0..12 {
                    let offset = 59 + i * 2;
                    if offset < buff.len() {
                        let cell = short_from_bytes_be(buff, offset);
                        bms.cells[i + 30] = cell as f64 / 1000.0;
                    }
                }
                bms.temp1 = signed_short_from_bytes_be(buff, 47) as f64 / 100.0;
                bms.temp2 = signed_short_from_bytes_be(buff, 49) as f64 / 100.0;
                bms.temp3 = signed_short_from_bytes_be(buff, 51) as f64 / 100.0;
                bms.temp4 = signed_short_from_bytes_be(buff, 53) as f64 / 100.0;
                bms.temp5 = signed_short_from_bytes_be(buff, 55) as f64 / 100.0;
                bms.temp6 = signed_short_from_bytes_be(buff, 57) as f64 / 100.0;

                bms.cell_num = cells_for_wheel;
                bms.recalculate_cell_stats(true);
            }
            _ => {}
        }
    }

    fn calculate_battery_percent(&self, voltage: i32) -> i32 {
        // Recognized models always use the manufacturer SOC table. The global
        // custom-percent preference belongs to other decoder families.
        if let Some(table) = self.soc_table() {
            return lookup_soc(voltage, table);
        }

        // Piecewise-linear fallback
        match self.m_ver {
            v if v < 4 => {
                // Sherman, Abrams, Sherman S (100V)
                if voltage <= 7935 {
                    0
                } else if voltage >= 9870 {
                    100
                } else {
                    round_to_i32((voltage - 7935) as f64 / 19.5)
                }
            }
            4 | 7 | 43 | 45 => {
                // Patton, Patton S, Nosfet Aero/Xeno (126V)
                if voltage <= 9918 {
                    0
                } else if voltage >= 12337 {
                    100
                } else {
                    round_to_i32((voltage - 9918) as f64 / 24.2)
                }
            }
            5 | 6 | 9 | 42 | 44 => {
                // Lynx, Sherman L, Lynx S, Nosfet Apex/Aeon (151V)
                if voltage <= 11902 {
                    0
                } else if voltage >= 14805 {
                    100
                } else {
                    round_to_i32((voltage - 11902) as f64 / 29.03)
                }
            }
            8 => {
                // Oryx (176V)
                if voltage <= 13886 {
                    0
                } else if voltage >= 17272 {
                    100
                } else {
                    round_to_i32((voltage - 13886) as f64 / 34.125)
                }
            }
            _ => 1, // Unknown wheel, default to 1%
        }
    }

    /// Official Leaperkim SOC table for the current model, or None.
    fn soc_table(&self) -> Option<&'static [i32]> {
        match self.m_ver {
            0..=3 => Some(&soc_tables::SHERMAN_100V),
            4 | 7 | 43 | 45 => Some(&soc_tables::PATTON_126V),
            5 | 6 | 9 | 42 | 44 => Some(&soc_tables::LYNX_151V),
            _ => None, // Oryx and unknown models use the model-class fallback
        }
    }

    fn is_nosfet_model(&self) -> bool {
        (501..=599).contains(&self.manufacturer_model_version) || (42..=45).contains(&self.m_ver)
    }

    /// Leaperkim latches a valid wheel-reported SOC from subtype 2 for the
    /// connection. Nosfet's app ignores that byte, so keep it brand-specific.
    fn resolve_battery_level(
        &mut self,
        voltage_battery: i32,
        sub_type: Option<i32>,
        override_pct: Option<i32>,
    ) -> i32 {
        if self.is_nosfet_model() {
            return voltage_battery;
        }

        if let Some(pct) = override_pct {
            self.uses_wheel_reported_battery = true;
            self.retained_wheel_battery = pct;
            return pct;
        }

        // On a subtype-2 frame without a valid SOC, the Leaperkim app writes
        // the voltage-derived value but does not disable the new-SOC mode.
        if sub_type == Some(2) {
            if self.uses_wheel_reported_battery {
                self.retained_wheel_battery = voltage_battery;
            }
            return voltage_battery;
        }

        if self.uses_wheel_reported_battery {
            self.retained_wheel_battery
        } else {
            voltage_battery
        }
    }

    fn cells_for_wheel(&self) -> i32 {
        match self.m_ver {
            4 | 7 | 43 | 45 => 30,          // Patton, Patton S, Aero, Xeno
            8 => 42,                        // Oryx
            5 | 6 | 9 | 42 | 44 => 36,      // Lynx, Sherman L, Lynx S, Apex, Aeon
            v if v >= 5 => 36,              // fallback for unknown mVer >= 5
            _ => 24,                        // Sherman, Abrams, Sherman S
        }
    }

    fn model_name(&self) -> &'static str {
        match self.m_ver {
            0 | 1 => "Leaperkim Sherman",
            2 => "Leaperkim Abrams",
            3 => "Leaperkim Sherman S",
            4 => "Leaperkim Patton",
            5 => "Leaperkim Lynx",
            6 => "Leaperkim Sherman L",
            7 => "Leaperkim Patton S",
            8 => "Leaperkim Oryx",
            9 => "Leaperkim Lynx S",
            42 => "Nosfet Apex",
            43 => "Nosfet Aero",
            44 => "Nosfet Aeon",
            45 => "Nosfet Xeno",
            _ => "Unknown",
        }
    }

    pub fn is_ready(&self) -> bool {
        self.m_ver != 0
    }

    pub fn get_capabilities(&self) -> CapabilitySet {
        if self.m_ver == 0 {
            return CapabilitySet::default();
        }
        let mut supported: Vec<SettingsCommandId> = CAPABILITY_MAP
            .iter()
            .filter(|(_, min_ver)| self.m_ver >= *min_ver)
            .map(|(id, _)| *id)
            .collect();
        if self.is_nosfet_model() {
            supported.retain(|id| {
                *id != SettingsCommandId::DynamicAssist
                    && *id != SettingsCommandId::AccelerationLimit
            });
        }
        CapabilitySet {
            supported_commands: supported,
            detected_model: self.model_name().to_string(),
            firmware_version: self.version.clone(),
            is_resolved: true,
        }
    }

    pub fn get_unpacker_stats(&self) -> UnpackerStats {
        self.unpacker.stats()
    }

    pub fn get_init_commands(&self) -> Vec<WheelCommand> {
        Vec::new()
    }

    pub fn get_keep_alive_command(&self) -> Option<WheelCommand> {
        None
    }

    pub fn reset(&mut self) {
        self.unpacker.reset_connection();
        self.has_synced_time = false;
        self.m_ver = 0;
        self.manufacturer_model_version = 0;
        self.version.clear();
        self.uses_wheel_reported_battery = false;
        self.retained_wheel_battery = 0;
        self.bms1 = SmartBms::default();
        self.bms2 = SmartBms::default();
        self.receiving_log = false;
    }

    /// Build time sync commands sent once per connection.
    /// Format: [4C 64 41 70 12 00 05 year-2000 month day hour min sec tz] + CRC32.
    /// Official apps send twice with a 2 s delay.
    fn build_time_sync_commands(&self) -> Vec<WheelCommand> {
        let c = &self.clock;
        let payload = [
            0x4C, 0x64, 0x41, 0x70, 0x12, 0x00, 0x05,
            (c.year - 2000) as u8,
            c.month as u8,
            c.day as u8,
            c.hour as u8,
            c.minute as u8,
            c.second as u8,
            c.tz_offset_hours as u8,
        ];
        let cmd = append_crc32(&payload);
        vec![
            WheelCommand::SendBytes(cmd.clone()),
            WheelCommand::SendDelayed(cmd, 2000),
        ]
    }

    /// Build a password-management command with time-based prefix.
    /// Format: [LdAp] [0x19] [00 05] [datetime 7B] [oldPwd 3B BE] [action] [newPwd 3B BE] + CRC32.
    fn build_pwd_command(&self, action: i32, old_password: &str, new_password: &str) -> Vec<u8> {
        let c = &self.clock;
        let old_pwd: i32 = old_password.parse().unwrap_or(0);
        let new_pwd: i32 = new_password.parse().unwrap_or(0);

        let mut payload = [0u8; 21];
        payload[0] = 0x4C;
        payload[1] = 0x64;
        payload[2] = 0x41;
        payload[3] = 0x70;
        payload[4] = 0x19; // time sync (0x12) + 7
        payload[5] = 0x00;
        payload[6] = 0x05;
        payload[7] = (c.year - 2000) as u8;
        payload[8] = c.month as u8;
        payload[9] = c.day as u8;
        payload[10] = c.hour as u8;
        payload[11] = c.minute as u8;
        payload[12] = c.second as u8;
        payload[13] = c.tz_offset_hours as u8;
        payload[14] = ((old_pwd >> 16) & 0xFF) as u8;
        payload[15] = ((old_pwd >> 8) & 0xFF) as u8;
        payload[16] = (old_pwd & 0xFF) as u8;
        payload[17] = action as u8;
        payload[18] = ((new_pwd >> 16) & 0xFF) as u8;
        payload[19] = ((new_pwd >> 8) & 0xFF) as u8;
        payload[20] = (new_pwd & 0xFF) as u8;

        append_crc32(&payload)
    }

    /// Build event log request payload (old LkAp / new LdAp variants).
    fn build_read_log_payload(&self, old: bool) -> Vec<u8> {
        if old {
            build_veteran_command(0x14, 15, 0x01, 0x01)
        } else {
            build_veteran_command_new(0x14, 15, 0x01, 0x01, 0x00)
        }
    }

    /// Parse event log entries from a telemetry frame when in log-receiving mode.
    pub fn parse_log_entries(&self, buff: &[u8], p_num: i32) -> Vec<EventLogEntry> {
        match p_num {
            0 | 4 => parse_log_basic(buff),
            32 => parse_log_extended(buff),
            33 => parse_log_detailed(buff),
            _ => Vec::new(),
        }
    }

    fn is_supported_at(&self, command_id: SettingsCommandId, firmware_ver: i32) -> bool {
        CAPABILITY_MAP
            .iter()
            .find(|(id, _)| *id == command_id)
            .map(|(_, min_ver)| firmware_ver >= *min_ver)
            .unwrap_or(false)
    }

    pub fn build_command(
        &mut self,
        command: &WheelCommand,
        state: Option<&DecoderState>,
    ) -> Vec<WheelCommand> {
        let ver = state
            .map(|s| match &s.settings {
                WheelSettings::Veteran(v) => v.m_ver,
                _ => 0,
            })
            .unwrap_or(0);
        self.build_command_with_ver(command, ver)
    }

    fn build_command_with_ver(&mut self, command: &WheelCommand, ver: i32) -> Vec<WheelCommand> {
        fn ascii(s: &str) -> Vec<u8> {
            s.as_bytes().to_vec()
        }
        match command {
            WheelCommand::Beep => {
                if ver < 3 {
                    vec![WheelCommand::SendBytes(ascii("b"))]
                } else {
                    vec![
                        WheelCommand::SendBytes(build_veteran_command(0x0E, 9, 0x01, 0x00)),
                        WheelCommand::SendBytes(build_veteran_command_new(0x0E, 9, 0x01, 0x00, 0x00)),
                    ]
                }
            }
            WheelCommand::SetLight(enabled) => {
                if ver < 3 {
                    vec![WheelCommand::SendBytes(ascii(if *enabled {
                        "SetLightON"
                    } else {
                        "SetLightOFF"
                    }))]
                } else {
                    let value = if *enabled { 1 } else { 0 };
                    vec![
                        WheelCommand::SendBytes(build_veteran_command(0x0D, 8, value, 0x01)),
                        WheelCommand::SendBytes(build_veteran_command_new(0x0D, 8, value, 0x01, 0x00)),
                    ]
                }
            }
            WheelCommand::SetPedalsMode(mode) => {
                if ver < 3 {
                    let cmd = match mode {
                        0 => "SETh",
                        1 => "SETm",
                        2 => "SETs",
                        _ => return Vec::new(),
                    };
                    vec![WheelCommand::SendBytes(ascii(cmd))]
                } else {
                    let value = match mode {
                        0 => 3,
                        1 => 2,
                        2 => 1, // hard/medium/soft
                        _ => return Vec::new(),
                    };
                    vec![
                        WheelCommand::SendBytes(build_veteran_command(0x0C, 7, value, 0x01)),
                        WheelCommand::SendBytes(build_veteran_command_new(0x0C, 7, value, 0x01, 0x00)),
                    ]
                }
            }
            WheelCommand::SetAlarmSpeed { speed, .. } => {
                if !self.is_supported_at(SettingsCommandId::AlarmSpeed1, ver) {
                    return Vec::new();
                }
                let v = speed + 10;
                vec![
                    WheelCommand::SendBytes(build_veteran_command(0x11, 12, v, 0x01)),
                    WheelCommand::SendBytes(build_veteran_command_new(0x11, 12, v, 0x01, 0x00)),
                ]
            }
            WheelCommand::SetPedalTilt(angle) => {
                if !self.is_supported_at(SettingsCommandId::PedalTilt, ver) {
                    return Vec::new();
                }
                let v = angle + 80;
                vec![
                    WheelCommand::SendBytes(build_veteran_command(0x10, 11, v, 0x01)),
                    WheelCommand::SendBytes(build_veteran_command_new(0x10, 11, v, 0x01, 0x00)),
                ]
            }
            WheelCommand::SetTransportMode(enabled) => {
                if !self.is_supported_at(SettingsCommandId::TransportMode, ver) {
                    return Vec::new();
                }
                // The official app ships transport-mode as LdAp-only.
                vec![WheelCommand::SendBytes(build_veteran_command_new(
                    0x16,
                    17,
                    if *enabled { 1 } else { 0 },
                    0x01,
                    0x02,
                ))]
            }
            WheelCommand::SetSpeakerVolume(_) => Vec::new(),
            WheelCommand::SetHighSpeedMode(enabled) => {
                if !self.is_supported_at(SettingsCommandId::HighSpeedMode, ver) {
                    return Vec::new();
                }
                vec![WheelCommand::SendBytes(build_veteran_command_new(
                    0x1A,
                    21,
                    if *enabled { 1 } else { 0 },
                    0x01,
                    0x02,
                ))]
            }
            WheelCommand::SetLowVoltageMode(enabled) => {
                if !self.is_supported_at(SettingsCommandId::LowVoltageMode, ver) {
                    return Vec::new();
                }
                vec![WheelCommand::SendBytes(build_veteran_command_new(
                    0x19,
                    20,
                    if *enabled { 1 } else { 0 },
                    0x01,
                    0x02,
                ))]
            }
            WheelCommand::SetKeyTone(value) => {
                if !self.is_supported_at(SettingsCommandId::KeyTone, ver) {
                    return Vec::new();
                }
                vec![WheelCommand::SendBytes(build_veteran_command_new(
                    0x1C, 23, *value, 0x01, 0x02,
                ))]
            }
            WheelCommand::PowerOff => {
                if !self.is_supported_at(SettingsCommandId::PowerOff, ver) {
                    return Vec::new();
                }
                // Value is at byte 16 with trailing 0x80 at byte 17 — value is
                // NOT the last byte, so both variants are hand-rolled.
                let lkap: [u8; 18] = [
                    0x4C, 0x6B, 0x41, 0x70, 0x16, 0x01, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
                    0x80, 0x80, 0x80, 0x01, 0x80,
                ];
                let ldap: [u8; 18] = [
                    0x4C, 0x64, 0x41, 0x70, 0x16, 0x01, 0x00, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
                    0x80, 0x80, 0x80, 0x01, 0x80,
                ];
                vec![
                    WheelCommand::SendBytes(append_crc32(&lkap)),
                    WheelCommand::SendBytes(append_crc32(&ldap)),
                ]
            }
            WheelCommand::ResetTrip => {
                if ver < 3 {
                    // Legacy strCmdMode wheels accept ASCII only.
                    vec![WheelCommand::SendBytes(ascii("CLEARMETER"))]
                } else {
                    vec![
                        WheelCommand::SendBytes(build_veteran_command(0x0D, 6, 0x01, 0x00)),
                        WheelCommand::SendBytes(build_veteran_command_new(0x0D, 8, 0x01, 0x00, 0x02)),
                    ]
                }
            }
            WheelCommand::SetVeteranLock { locked, password } => {
                // Manufacturer app sends action 1 to lock and action 0 to unlock.
                let action = if *locked { 1 } else { 0 };
                vec![WheelCommand::SendBytes(
                    self.build_pwd_command(action, password, ""),
                )]
            }
            WheelCommand::SetVeteranPassword { new_password } => {
                vec![WheelCommand::SendBytes(
                    self.build_pwd_command(11, "", new_password),
                )]
            }
            WheelCommand::ModifyVeteranPassword {
                old_password,
                new_password,
            } => {
                vec![WheelCommand::SendBytes(
                    self.build_pwd_command(11, old_password, new_password),
                )]
            }
            WheelCommand::ClearVeteranPassword { password } => {
                vec![WheelCommand::SendBytes(
                    self.build_pwd_command(11, password, ""),
                )]
            }
            WheelCommand::SetVeteranAutoLock { enabled, password } => {
                // Action 2 = auto-lock OFF, 3 = auto-lock ON.
                let action = if *enabled { 3 } else { 2 };
                vec![WheelCommand::SendBytes(
                    self.build_pwd_command(action, password, ""),
                )]
            }
            WheelCommand::RequestEventLog => {
                self.receiving_log = true;
                vec![
                    WheelCommand::SendBytes(self.build_read_log_payload(true)),
                    WheelCommand::SendBytes(self.build_read_log_payload(false)),
                ]
            }
            WheelCommand::SetScreenBacklight(value) => {
                if !self.is_supported_at(SettingsCommandId::ScreenBacklight, ver) {
                    return Vec::new();
                }
                vec![WheelCommand::SendBytes(build_veteran_command_new(
                    0x14, 15, *value, 0x01, 0x02,
                ))]
            }
            WheelCommand::SetStopSpeed(speed) => {
                if !self.is_supported_at(SettingsCommandId::StopSpeed, ver) {
                    return Vec::new();
                }
                vec![WheelCommand::SendBytes(build_veteran_command_new(
                    0x11, 12, *speed, 0x01, 0x02,
                ))]
            }
            WheelCommand::SetVeteranPwmLimit(limit) => {
                if !self.is_supported_at(SettingsCommandId::VeteranPwmLimit, ver) {
                    return Vec::new();
                }
                vec![WheelCommand::SendBytes(build_veteran_command_new(
                    0x12, 13, *limit, 0x01, 0x02,
                ))]
            }
            WheelCommand::SetVoltageCorrection(value) => {
                if !self.is_supported_at(SettingsCommandId::VoltageCorrection, ver) {
                    return Vec::new();
                }
                vec![WheelCommand::SendBytes(build_veteran_command_new(
                    0x18, 19, *value, 0x01, 0x02,
                ))]
            }
            WheelCommand::SetMaxChargeVoltage(value) => {
                if !self.is_supported_at(SettingsCommandId::MaxChargeVoltage, ver) {
                    return Vec::new();
                }
                vec![WheelCommand::SendBytes(build_veteran_command_new(
                    0x1D, 24, *value, 0x01, 0x02,
                ))]
            }
            WheelCommand::SetBrakePressureAlarm(value) => {
                if !self.is_supported_at(SettingsCommandId::BrakePressureAlarm, ver) {
                    return Vec::new();
                }
                let cmd = if self.is_nosfet_model() {
                    build_veteran_command_new(0x1E, 25, *value, 0x01, 0x02)
                } else {
                    build_veteran_command_new(0x22, 29, *value, 0x01, 0x02)
                };
                vec![WheelCommand::SendBytes(cmd)]
            }
            WheelCommand::SetLateralCutoffAngle(angle) => {
                if !self.is_supported_at(SettingsCommandId::LateralCutoffAngle, ver) {
                    return Vec::new();
                }
                vec![
                    WheelCommand::SendBytes(build_veteran_command(0x16, 17, *angle, 0x01)),
                    WheelCommand::SendBytes(build_veteran_command_new(0x16, 17, *angle, 0x01, 0x00)),
                ]
            }
            WheelCommand::Calibrate => {
                if !self.is_supported_at(SettingsCommandId::Calibrate, ver) {
                    return Vec::new();
                }
                vec![WheelCommand::SendBytes(build_veteran_command_new(
                    0x15, 16, 0x01, 0x01, 0x02,
                ))]
            }
            WheelCommand::SetDynamicAssist(value) => {
                if self.is_nosfet_model() {
                    return Vec::new();
                }
                if !self.is_supported_at(SettingsCommandId::DynamicAssist, ver) {
                    return Vec::new();
                }
                vec![WheelCommand::SendBytes(build_veteran_command_new(
                    0x1F, 26, *value, 0x01, 0x02,
                ))]
            }
            WheelCommand::SetAccelerationLimit(value) => {
                if self.is_nosfet_model() {
                    return Vec::new();
                }
                if !self.is_supported_at(SettingsCommandId::AccelerationLimit, ver) {
                    return Vec::new();
                }
                vec![WheelCommand::SendBytes(build_veteran_command_new(
                    0x21, 28, *value, 0x01, 0x02,
                ))]
            }
            WheelCommand::SetWheelDisplayUnit { miles } => {
                if !self.is_supported_at(SettingsCommandId::WheelDisplayUnit, ver) {
                    return Vec::new();
                }
                vec![WheelCommand::SendBytes(build_veteran_command_new(
                    0x17,
                    18,
                    if *miles { 1 } else { 0 },
                    0x01,
                    0x02,
                ))]
            }
            WheelCommand::SetPedalHardness(value) => {
                if !self.is_supported_at(SettingsCommandId::PedalHardness, ver) {
                    return Vec::new();
                }
                // PedalSoftnessSettingActivity: single LdAp, raw passthrough.
                vec![WheelCommand::SendBytes(build_veteran_command_new(
                    0x0F, 10, *value, 0x01, 0x02,
                ))]
            }
            // Kotlin: `else -> emptyList()` — commands other decoders handle
            _ => Vec::new(),
        }
    }
}

fn veteran_settings(settings: &WheelSettings) -> VeteranSettings {
    // Kotlin: `currentState.settings as? WheelSettings.Veteran ?: WheelSettings.Veteran()`
    match settings {
        WheelSettings::Veteran(v) => v.clone(),
        _ => VeteranSettings::default(),
    }
}

fn normalize_model_version(version: i32) -> i32 {
    match version {
        501 => 42, // Nosfet Apex
        502 => 43, // Nosfet Aero
        503 => 44, // Nosfet Aeon
        504 => 45, // Nosfet Xeno (inferred from sequential family numbering)
        other => other,
    }
}

// ---------------------------------------------------------------------------
// Command builders
// ---------------------------------------------------------------------------

/// Append 4-byte big-endian CRC32 to a payload.
pub fn append_crc32(data: &[u8]) -> Vec<u8> {
    let crc = crc32(data, 0, data.len());
    let mut result = data.to_vec();
    result.push(((crc >> 24) & 0xFF) as u8);
    result.push(((crc >> 16) & 0xFF) as u8);
    result.push(((crc >> 8) & 0xFF) as u8);
    result.push((crc & 0xFF) as u8);
    result
}

/// Build a Veteran "LkAp" binary command with CRC32.
/// Format: [4C 6B 41 70] [cmd] [byte5] [0x80 padding...] [value] + CRC32.
fn build_veteran_command(cmd_byte: i32, value_position: usize, value: i32, byte5: i32) -> Vec<u8> {
    let payload_size = value_position + 1;
    let mut payload = vec![0u8; payload_size];
    payload[0] = 0x4C;
    payload[1] = 0x6B;
    payload[2] = 0x41;
    payload[3] = 0x70;
    payload[4] = cmd_byte as u8;
    payload[5] = byte5 as u8;
    for slot in payload.iter_mut().take(payload_size - 1).skip(6) {
        *slot = 0x80;
    }
    payload[payload_size - 1] = value as u8;
    append_crc32(&payload)
}

/// Build a Veteran "LdAp" (new format) binary command with CRC32.
/// Format: [4C 64 41 70] [cmd] [byte5] [byte6] [0x80 padding...] [value] + CRC32.
fn build_veteran_command_new(
    cmd_byte: i32,
    value_position: usize,
    value: i32,
    byte5: i32,
    byte6: i32,
) -> Vec<u8> {
    let payload_size = value_position + 1;
    let mut payload = vec![0u8; payload_size];
    payload[0] = 0x4C;
    payload[1] = 0x64; // "LdAp" — new format
    payload[2] = 0x41;
    payload[3] = 0x70;
    payload[4] = cmd_byte as u8;
    payload[5] = byte5 as u8;
    if payload_size > 6 {
        payload[6] = byte6 as u8;
    }
    for slot in payload.iter_mut().take(payload_size - 1).skip(7) {
        *slot = 0x80;
    }
    payload[payload_size - 1] = value as u8;
    append_crc32(&payload)
}

// ---------------------------------------------------------------------------
// Event log parsing
// ---------------------------------------------------------------------------

/// Sub-type 0/4: 2 basic log entries at bytes 50-54.
fn parse_log_basic(buff: &[u8]) -> Vec<EventLogEntry> {
    if buff.len() <= 58 {
        return Vec::new();
    }
    let mut entries = Vec::new();
    let index = buff[50] as i32;
    let content = short_from_bytes_be(buff, 51);
    entries.push(EventLogEntry {
        index,
        content_code: content,
        ..Default::default()
    });
    if index < 255 {
        let content2 = short_from_bytes_be(buff, 53);
        entries.push(EventLogEntry {
            index: index + 1,
            content_code: content2,
            ..Default::default()
        });
    }
    entries
}

/// Sub-type 32: 3 extended log entries at bytes 47-80, each with 5 extra bytes.
fn parse_log_extended(buff: &[u8]) -> Vec<EventLogEntry> {
    if buff.len() <= 84 {
        return Vec::new();
    }
    let mut entries = Vec::new();
    let index = buff[47] as i32;
    entries.push(EventLogEntry {
        index,
        content_code: short_from_bytes_be(buff, 48),
        extra_bytes: buff[54..59].to_vec(),
        ..Default::default()
    });
    if index < 255 {
        entries.push(EventLogEntry {
            index: index + 1,
            content_code: short_from_bytes_be(buff, 59),
            extra_bytes: buff[65..70].to_vec(),
            ..Default::default()
        });
    }
    if index < 254 {
        entries.push(EventLogEntry {
            index: index + 2,
            content_code: short_from_bytes_be(buff, 70),
            extra_bytes: buff[76..81].to_vec(),
            ..Default::default()
        });
    }
    entries
}

/// Sub-type 33: 1 detailed entry with packed count/index, timestamp, extras, text.
fn parse_log_detailed(buff: &[u8]) -> Vec<EventLogEntry> {
    if buff.len() <= 60 {
        return Vec::new();
    }
    let b47 = buff[47] as i32;
    let b48 = buff[48] as i32;
    let b49 = buff[49] as i32;
    // Bit-packed: totalLogNum = b47*16 + b48/16, index = (b48%16)*256 + b49
    let total_log_num = b47 * 16 + b48 / 16;
    let index = (b48 % 16) * 256 + b49;
    let timestamp = int_from_bytes_be(buff, 50);
    let content_code = short_from_bytes_be(buff, 54);
    let extra_count = buff[56] as usize;
    let mut extras: Vec<i64> = Vec::new();
    for i in 0..extra_count {
        let offset = 57 + i * 4;
        if offset + 3 >= buff.len() {
            break;
        }
        let mut value = int_from_bytes_be(buff, offset);
        // Signed: values > 2^31 are negative
        if value > 2_147_483_648 {
            value -= 4_294_967_296;
        }
        extras.push(value);
    }
    // Text: null-terminated bytes after the extra values (-4 for CRC).
    // The wheel emits GBK; like the Kotlin port we decode as UTF-8 with
    // replacement, which matches for the ASCII subset.
    let text_start = 57 + extra_count * 4;
    let mut text_bytes: Vec<u8> = Vec::new();
    let mut i = text_start;
    while i < buff.len().saturating_sub(4) && buff[i] != 0 {
        text_bytes.push(buff[i]);
        i += 1;
    }
    let text = String::from_utf8_lossy(&text_bytes).to_string();

    vec![EventLogEntry {
        index,
        total_count: total_log_num,
        content_code,
        timestamp,
        extras,
        text,
        extra_bytes: Vec::new(),
    }]
}
