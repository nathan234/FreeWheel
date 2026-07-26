//! Domain types mirroring the KMP `core/domain` package, restricted to what
//! the Gotway decoder needs. Field names, defaults, and equality semantics
//! follow the Kotlin data classes so decode results diff identically.

use crate::byte_utils::KM_TO_MILES_MULTIPLIER;

pub const MAX_CELLS: usize = 56;

// ---------------------------------------------------------------------------
// WheelType / WheelIdentity
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
pub enum WheelType {
    #[default]
    Unknown,
    Kingsong,
    Gotway,
    Ninebot,
    NinebotZ,
    Inmotion,
    Lorin,
    Veteran,
    Leaperkim,
    GotwayVirtual,
}

impl WheelType {
    pub fn display_name(&self) -> &'static str {
        match self {
            WheelType::Kingsong => "KingSong",
            WheelType::Gotway | WheelType::GotwayVirtual => "Begode",
            WheelType::Ninebot | WheelType::NinebotZ => "Ninebot",
            WheelType::Inmotion | WheelType::Lorin => "InMotion",
            WheelType::Veteran => "",
            WheelType::Leaperkim => "Leaperkim",
            WheelType::Unknown => "",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct WheelIdentity {
    pub wheel_type: WheelType,
    pub name: String,
    pub model: String,
    pub mode_str: String,
    pub version: String,
    pub serial_number: String,
    pub bt_name: String,
    /// Firmware-derived brand override (e.g. "Extreme Bull" for JN-prefix firmware).
    pub brand: String,
}

impl WheelIdentity {
    pub fn display_name(&self) -> String {
        let effective_brand = if self.brand.is_empty() {
            self.wheel_type.display_name().to_string()
        } else {
            self.brand.clone()
        };
        let label = if !self.model.is_empty() {
            self.model.clone()
        } else if !self.name.is_empty() {
            self.name.clone()
        } else {
            self.bt_name.clone()
        };
        if label.is_empty() {
            return if effective_brand.is_empty() {
                "Dashboard".to_string()
            } else {
                effective_brand
            };
        }
        if effective_brand.is_empty()
            || label.to_lowercase().starts_with(&effective_brand.to_lowercase())
        {
            return label;
        }
        format!("{effective_brand} {label}")
    }
}

// ---------------------------------------------------------------------------
// TelemetryState
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct TelemetryState {
    pub speed: i32,
    pub voltage: i32,
    pub current: i32,
    pub phase_current: i32,
    pub power: i32,
    pub temperature: i32,
    pub temperature2: i32,
    pub battery_level: i32,
    pub bms_soc: i32,
    pub total_distance: i64,
    pub total_energy_wh: i64,
    pub wheel_distance: i64,
    pub top_speed: i32,
    pub ride_time: i32,
    pub total_on_time: i32,
    pub output: i32,
    pub calculated_pwm: f64,
    pub angle: f64,
    pub roll: f64,
    pub torque: f64,
    pub motor_power: f64,
    pub cpu_temp: i32,
    pub imu_temp: i32,
    pub cpu_load: i32,
    pub hw_faults: i32,
    pub speed_limit: f64,
    pub current_limit: f64,
    pub fan_status: i32,
    pub charging_status: i32,
    pub wheel_alarm: bool,
    pub error: String,
    pub fault_code: i32,
    pub alert: String,
    pub timestamp: i64,
    pub alert_speed: i32,
    pub auto_off_time: i32,
    pub max_speed: i32,
}

impl Default for TelemetryState {
    fn default() -> Self {
        TelemetryState {
            speed: 0,
            voltage: 0,
            current: 0,
            phase_current: 0,
            power: 0,
            temperature: 0,
            temperature2: 0,
            battery_level: 0,
            bms_soc: -1,
            total_distance: 0,
            total_energy_wh: 0,
            wheel_distance: 0,
            top_speed: 0,
            ride_time: 0,
            total_on_time: 0,
            output: 0,
            calculated_pwm: 0.0,
            angle: 0.0,
            roll: 0.0,
            torque: 0.0,
            motor_power: 0.0,
            cpu_temp: 0,
            imu_temp: 0,
            cpu_load: 0,
            hw_faults: 0,
            speed_limit: 0.0,
            current_limit: 0.0,
            fan_status: 0,
            charging_status: 0,
            wheel_alarm: false,
            error: String::new(),
            fault_code: 0,
            alert: String::new(),
            timestamp: 0,
            alert_speed: 0,
            auto_off_time: 0,
            max_speed: -1,
        }
    }
}

impl TelemetryState {
    pub fn speed_kmh(&self) -> f64 {
        self.speed as f64 / 100.0
    }
    pub fn speed_mph(&self) -> f64 {
        self.speed_kmh() * KM_TO_MILES_MULTIPLIER
    }
    pub fn voltage_v(&self) -> f64 {
        self.voltage as f64 / 100.0
    }
}

// ---------------------------------------------------------------------------
// SmartBms / BmsSnapshot / BmsState
// ---------------------------------------------------------------------------

/// Mutable per-pack accumulator (cells arrive across multiple frames).
#[derive(Debug, Clone)]
pub struct SmartBms {
    pub serial_number: String,
    pub version_number: String,
    pub factory_cap: i32,
    pub actual_cap: i32,
    pub full_cycles: i32,
    pub charge_count: i32,
    pub mfg_date_str: String,
    pub status: i32,
    pub rem_cap: i32,
    pub rem_perc: i32,
    pub current: f64,
    pub voltage: f64,
    pub semi_voltage1: f64,
    pub semi_voltage2: f64,
    pub temp1: f64,
    pub temp2: f64,
    pub temp3: f64,
    pub temp4: f64,
    pub temp5: f64,
    pub temp6: f64,
    pub temp_mos: f64,
    pub temp_mos_env: f64,
    pub temp1_env: f64,
    pub temp2_env: f64,
    pub humidity1_env: f64,
    pub humidity2_env: f64,
    pub balance_map: i32,
    pub health: i32,
    pub min_cell: f64,
    pub max_cell: f64,
    pub cell_diff: f64,
    pub avg_cell: f64,
    pub min_cell_num: i32,
    pub max_cell_num: i32,
    pub cell_num: i32,
    pub cells: Vec<f64>,
}

impl Default for SmartBms {
    fn default() -> Self {
        SmartBms {
            serial_number: String::new(),
            version_number: String::new(),
            factory_cap: 0,
            actual_cap: 0,
            full_cycles: 0,
            charge_count: 0,
            mfg_date_str: String::new(),
            status: 0,
            rem_cap: 0,
            rem_perc: 0,
            current: 0.0,
            voltage: 0.0,
            semi_voltage1: 0.0,
            semi_voltage2: 0.0,
            temp1: 0.0,
            temp2: 0.0,
            temp3: 0.0,
            temp4: 0.0,
            temp5: 0.0,
            temp6: 0.0,
            temp_mos: 0.0,
            temp_mos_env: 0.0,
            temp1_env: 0.0,
            temp2_env: 0.0,
            humidity1_env: 0.0,
            humidity2_env: 0.0,
            balance_map: 0,
            health: 0,
            min_cell: 0.0,
            max_cell: 0.0,
            cell_diff: 0.0,
            avg_cell: 0.0,
            min_cell_num: 0,
            max_cell_num: 0,
            cell_num: 0,
            cells: vec![0.0; MAX_CELLS],
        }
    }
}

impl SmartBms {
    /// Port of `SmartBms.recalculateCellStats` — zero cells are "unknown", not 0 V.
    pub fn recalculate_cell_stats(&mut self, update_pack_voltage: bool) {
        let bounded = self.cell_num.clamp(0, self.cells.len() as i32);
        self.cell_num = bounded;
        let mut valid = 0;
        let mut total = 0.0;

        for i in 0..bounded as usize {
            let cell = self.cells[i];
            if cell <= 0.0 {
                continue;
            }
            total += cell;
            valid += 1;
            if valid == 1 || cell > self.max_cell {
                self.max_cell = cell;
                self.max_cell_num = i as i32 + 1;
            }
            if valid == 1 || cell < self.min_cell {
                self.min_cell = cell;
                self.min_cell_num = i as i32 + 1;
            }
        }

        if valid == 0 {
            self.min_cell = 0.0;
            self.max_cell = 0.0;
            self.min_cell_num = 0;
            self.max_cell_num = 0;
            self.cell_diff = 0.0;
            self.avg_cell = 0.0;
            if update_pack_voltage {
                self.voltage = 0.0;
            }
            return;
        }

        self.cell_diff = self.max_cell - self.min_cell;
        self.avg_cell = total / valid as f64;
        if update_pack_voltage {
            self.voltage = total;
        }
    }

    pub fn to_snapshot(&self) -> BmsSnapshot {
        BmsSnapshot {
            serial_number: self.serial_number.clone(),
            version_number: self.version_number.clone(),
            current: self.current,
            voltage: self.voltage,
            semi_voltage1: self.semi_voltage1,
            semi_voltage2: self.semi_voltage2,
            temp1: self.temp1,
            temp2: self.temp2,
            min_cell: self.min_cell,
            max_cell: self.max_cell,
            cell_diff: self.cell_diff,
            avg_cell: self.avg_cell,
            min_cell_num: self.min_cell_num,
            max_cell_num: self.max_cell_num,
            cell_num: self.cell_num,
            cells: self.cells.clone(),
        }
    }
}

/// Immutable snapshot published to state. Subset of the Kotlin `BmsSnapshot`
/// covering every field the Gotway decoder writes.
#[derive(Debug, Clone, PartialEq)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct BmsSnapshot {
    pub serial_number: String,
    pub version_number: String,
    pub current: f64,
    pub voltage: f64,
    pub semi_voltage1: f64,
    pub semi_voltage2: f64,
    pub temp1: f64,
    pub temp2: f64,
    pub min_cell: f64,
    pub max_cell: f64,
    pub cell_diff: f64,
    pub avg_cell: f64,
    pub min_cell_num: i32,
    pub max_cell_num: i32,
    pub cell_num: i32,
    pub cells: Vec<f64>,
}

#[derive(Debug, Clone, PartialEq, Default)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct BmsState {
    pub bms1: Option<BmsSnapshot>,
    pub bms2: Option<BmsSnapshot>,
    pub bms3: Option<BmsSnapshot>,
    pub bms4: Option<BmsSnapshot>,
}

// ---------------------------------------------------------------------------
// WheelSettings
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct BegodeSettings {
    pub pedals_mode: i32,
    pub speed_alarms: i32,
    pub roll_angle: i32,
    pub tilt_back_speed: i32,
    pub light_mode: i32,
    pub led_mode: i32,
    pub cutout_angle: i32,
    pub beeper_volume: i32,
    pub in_miles: bool,
    pub weak_magnetism: i32,
    pub extended_roll_angle: i32,
    pub power_alarm: i32,
    pub plate_protection: Option<bool>,
}

impl Default for BegodeSettings {
    fn default() -> Self {
        BegodeSettings {
            pedals_mode: -1,
            speed_alarms: -1,
            roll_angle: -1,
            tilt_back_speed: 0,
            light_mode: -1,
            led_mode: -1,
            cutout_angle: -1,
            beeper_volume: -1,
            in_miles: false,
            weak_magnetism: -1,
            extended_roll_angle: -1,
            power_alarm: -1,
            plate_protection: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct VeteranSettings {
    pub pedals_mode: i32,
    pub light_mode: i32,
    pub tilt_back_speed: i32,
    pub alert_speed: i32,
    pub auto_off_time: i32,
    pub lock_state: i32,
    pub high_speed_mode: Option<bool>,
    pub low_voltage_mode: Option<bool>,
    pub voltage_correction: i32,
    pub transport_mode: Option<bool>,
    pub key_tone: i32,
    pub pedal_sensitivity: i32,
    pub stop_speed: i32,
    pub pwm_limit: i32,
    pub screen_backlight: i32,
    pub max_charge_voltage: i32,
    pub brake_pressure_alarm: i32,
    pub lateral_cutoff_angle: i32,
    pub dynamic_assist: i32,
    pub acceleration_limit: i32,
    pub charge_voltage_base: i32,
    pub wheel_display_unit: i32,
    pub battery_temp_mode: i32,
    /// Firmware major version (e.g. 3, 4, 43). Used by build_command for capability checks.
    pub m_ver: i32,
}

impl Default for VeteranSettings {
    fn default() -> Self {
        VeteranSettings {
            pedals_mode: -1,
            light_mode: -1,
            tilt_back_speed: 0,
            alert_speed: 0,
            auto_off_time: 0,
            lock_state: -1,
            high_speed_mode: None,
            low_voltage_mode: None,
            voltage_correction: -1,
            transport_mode: None,
            key_tone: -1,
            pedal_sensitivity: -1,
            stop_speed: -1,
            pwm_limit: -1,
            screen_backlight: -1,
            max_charge_voltage: -1,
            brake_pressure_alarm: -1,
            lateral_cutoff_angle: -1,
            dynamic_assist: -1,
            acceleration_limit: -1,
            charge_voltage_base: 145,
            wheel_display_unit: -1,
            battery_temp_mode: 0,
            m_ver: 0,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Default)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
pub enum WheelSettings {
    #[default]
    None,
    Begode(BegodeSettings),
    Veteran(VeteranSettings),
}

// ---------------------------------------------------------------------------
// Event log (Veteran/Leaperkim)
// ---------------------------------------------------------------------------

/// A single event log entry from the wheel's internal error/event history.
#[derive(Debug, Clone, PartialEq)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct EventLogEntry {
    pub index: i32,
    pub total_count: i32,
    pub content_code: i32,
    pub timestamp: i64,
    pub extras: Vec<i64>,
    pub text: String,
    pub extra_bytes: Vec<u8>,
}

impl Default for EventLogEntry {
    fn default() -> Self {
        EventLogEntry {
            index: 0,
            total_count: -1,
            content_code: 0,
            timestamp: 0,
            extras: Vec::new(),
            text: String::new(),
            extra_bytes: Vec::new(),
        }
    }
}

// ---------------------------------------------------------------------------
// DecoderState / DecoderConfig
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Default)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct DecoderState {
    pub telemetry: TelemetryState,
    pub identity: WheelIdentity,
    pub bms: BmsState,
    pub settings: WheelSettings,
}

#[derive(Debug, Clone, PartialEq)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct DecoderConfig {
    pub use_custom_percents: bool,
    pub rotation_speed: i32,
    pub rotation_voltage: i32,
    pub power_factor: i32,
    pub wheel_password: String,
    pub gotway_negative: i32,
    pub use_ratio: bool,
    pub gotway_voltage: i32,
    pub hw_pwm_enabled: bool,
    pub ks18l_scaler: bool,
    pub auto_voltage: bool,
}

impl Default for DecoderConfig {
    fn default() -> Self {
        DecoderConfig {
            use_custom_percents: false,
            rotation_speed: 500,
            rotation_voltage: 840,
            power_factor: 90,
            wheel_password: String::new(),
            gotway_negative: 0,
            use_ratio: false,
            gotway_voltage: -1,
            hw_pwm_enabled: false,
            ks18l_scaler: false,
            auto_voltage: true,
        }
    }
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
pub enum WheelCommand {
    SendBytes(Vec<u8>),
    SendDelayed(Vec<u8>, u64),
    Beep,
    SetLight(bool),
    SetLightMode(i32),
    SetPedalsMode(i32),
    SetMilesMode(bool),
    SetWheelDisplayUnit { miles: bool },
    SetRollAngleMode(i32),
    SetLedMode(i32),
    SetBeeperVolume(i32),
    SetCutoutAngle(i32),
    SetAlarmMode(i32),
    Calibrate,
    SetPedalTilt(i32),
    SetWeakMagnetism(i32),
    SetExtendedRollAngle(i32),
    SetPowerAlarm(i32),
    SetPlateProtection(bool),
    SetMaxSpeed(i32),
    // --- Veteran/Leaperkim ---
    SetAlarmSpeed { speed: i32, num: i32 },
    SetTransportMode(bool),
    SetSpeakerVolume(i32),
    SetHighSpeedMode(bool),
    SetLowVoltageMode(bool),
    SetKeyTone(i32),
    PowerOff,
    ResetTrip,
    SetScreenBacklight(i32),
    SetStopSpeed(i32),
    SetVeteranPwmLimit(i32),
    SetVoltageCorrection(i32),
    SetMaxChargeVoltage(i32),
    SetBrakePressureAlarm(i32),
    SetLateralCutoffAngle(i32),
    SetDynamicAssist(i32),
    SetAccelerationLimit(i32),
    /// Continuous Veteran pedal-hardness slider (0..100), routed through cmd 0x0F.
    SetPedalHardness(i32),
    SetVeteranLock { locked: bool, password: String },
    SetVeteranPassword { new_password: String },
    ModifyVeteranPassword { old_password: String, new_password: String },
    ClearVeteranPassword { password: String },
    SetVeteranAutoLock { enabled: bool, password: String },
    RequestEventLog,
}

// ---------------------------------------------------------------------------
// Decode results
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Default)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct DecodedData {
    pub telemetry: Option<TelemetryState>,
    pub identity: Option<WheelIdentity>,
    pub bms: Option<BmsState>,
    pub settings: Option<WheelSettings>,
    pub commands: Vec<WheelCommand>,
    pub has_new_data: bool,
    pub news: Option<String>,
    pub frame_types: Vec<String>,
    /// Event log entries decoded from this frame (Veteran/Leaperkim).
    pub log_entries: Vec<EventLogEntry>,
}

#[derive(Debug, Clone, PartialEq)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
pub enum UnhandledReason {
    UnknownCommand { frame_hex: String },
}

#[derive(Debug, Clone, PartialEq)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
pub enum DecodeResult {
    Success(DecodedData),
    Buffering,
    Unhandled {
        reason: UnhandledReason,
        frame_data: Vec<u8>,
    },
}

/// Port of `resolveWheelIdentity` in WheelDecoder.kt.
pub fn resolve_wheel_identity(
    result_identity: Option<WheelIdentity>,
    current_identity: &WheelIdentity,
    expected_type: WheelType,
) -> Option<WheelIdentity> {
    match result_identity {
        Some(identity) if identity.wheel_type == WheelType::Unknown => Some(WheelIdentity {
            wheel_type: expected_type,
            ..identity
        }),
        Some(identity) => Some(identity),
        None if current_identity.wheel_type == WheelType::Unknown => Some(WheelIdentity {
            wheel_type: expected_type,
            ..current_identity.clone()
        }),
        None => None,
    }
}

// ---------------------------------------------------------------------------
// Capabilities
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[cfg_attr(feature = "ffi", derive(uniffi::Enum))]
pub enum SettingsCommandId {
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
    // --- Veteran/Leaperkim ---
    Lock,
    ResetTrip,
    AlarmSpeed1,
    TransportMode,
    HighSpeedMode,
    LowVoltageMode,
    KeyTone,
    ScreenBacklight,
    StopSpeed,
    VeteranPwmLimit,
    VoltageCorrection,
    MaxChargeVoltage,
    BrakePressureAlarm,
    LateralCutoffAngle,
    DynamicAssist,
    AccelerationLimit,
    PedalHardness,
    PowerOff,
}

#[derive(Debug, Clone, PartialEq, Default)]
#[cfg_attr(feature = "ffi", derive(uniffi::Record))]
pub struct CapabilitySet {
    pub supported_commands: Vec<SettingsCommandId>,
    pub detected_model: String,
    pub firmware_version: String,
    pub is_resolved: bool,
}

impl CapabilitySet {
    pub fn supports(&self, command_id: SettingsCommandId) -> bool {
        self.supported_commands.contains(&command_id)
    }
}
