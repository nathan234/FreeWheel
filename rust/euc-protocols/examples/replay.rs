//! Replay a FreeWheel BLE capture CSV through the matching Rust decoder and
//! diff per-packet outcomes against the KMP decoder's recorded verdicts.
//!
//! The decoder is selected from the capture header (`# wheel_type: GOTWAY` /
//! `VETERAN`). The capture's `decode_result` column is what the KMP decoder
//! said about each packet on-device, so this is a packet-for-packet parity
//! oracle over real hardware traffic.
//!
//! Usage: cargo run --example replay -- <capture.csv>

use euc_protocols::byte_utils::hex_to_bytes;
use euc_protocols::{
    DecodeResult, DecoderConfig, DecoderState, GotwayDecoder, VeteranDecoder, WheelSettings,
};
use std::env;
use std::fs;

enum ReplayDecoder {
    Gotway(GotwayDecoder),
    Veteran(VeteranDecoder),
}

impl ReplayDecoder {
    fn for_wheel_type(wheel_type: &str) -> Option<ReplayDecoder> {
        match wheel_type {
            "GOTWAY" | "GOTWAY_VIRTUAL" => Some(ReplayDecoder::Gotway(GotwayDecoder::new())),
            "VETERAN" => Some(ReplayDecoder::Veteran(VeteranDecoder::new())),
            _ => None,
        }
    }

    fn decode(
        &mut self,
        data: &[u8],
        state: &DecoderState,
        config: &DecoderConfig,
    ) -> DecodeResult {
        match self {
            ReplayDecoder::Gotway(d) => d.decode(data, state, config),
            ReplayDecoder::Veteran(d) => d.decode(data, state, config),
        }
    }

    fn is_ready(&self) -> bool {
        match self {
            ReplayDecoder::Gotway(d) => d.is_ready(),
            ReplayDecoder::Veteran(d) => d.is_ready(),
        }
    }

    fn unpacker_stats(&self) -> euc_protocols::unpacker::UnpackerStats {
        match self {
            ReplayDecoder::Gotway(d) => d.get_unpacker_stats(),
            ReplayDecoder::Veteran(d) => d.get_unpacker_stats(),
        }
    }
}

/// Header comment value, e.g. `# wheel_type: VETERAN` → `VETERAN`.
fn header_value<'a>(content: &'a str, key: &str) -> Option<&'a str> {
    content
        .lines()
        .take_while(|l| l.starts_with('#'))
        .find_map(|l| l.strip_prefix('#').unwrap().trim().strip_prefix(key))
        .map(|v| v.trim_start_matches(':').trim())
}

fn main() {
    let path = env::args().nth(1).expect("usage: replay <capture.csv>");
    let content = fs::read_to_string(&path).expect("failed to read capture");

    let wheel_type = header_value(&content, "wheel_type").unwrap_or("GOTWAY");
    let wheel_name = header_value(&content, "wheel_name").unwrap_or("?");
    println!("capture:             {wheel_name} (wheel_type {wheel_type})");

    let mut decoder = match ReplayDecoder::for_wheel_type(wheel_type) {
        Some(decoder) => decoder,
        None => {
            eprintln!("unsupported wheel_type '{wheel_type}' — ported decoders: GOTWAY, VETERAN");
            std::process::exit(1);
        }
    };
    let mut state = DecoderState::default();
    // Defaults: gotway_voltage -1 (catalog auto), gotway_negative 0, hw_pwm off.
    // Per-packet verdicts don't depend on config; telemetry values can differ
    // from the on-device app if its calibration prefs deviate from defaults.
    let config = DecoderConfig::default();

    let mut rx = 0u32;
    let mut matches = 0u32;
    let mut mismatches = 0u32;
    let mut uncompared = 0u32;
    let mut mismatch_samples: Vec<String> = Vec::new();
    let mut max_speed = 0i32;
    let mut min_voltage = i32::MAX;
    let mut max_voltage = 0i32;
    let mut frame_type_counts: std::collections::BTreeMap<String, u32> = Default::default();
    let mut log_entry_count = 0usize;

    for (lineno, line) in content.lines().enumerate() {
        if line.starts_with('#') || line.starts_with("timestamp_ms") || line.trim().is_empty() {
            continue;
        }
        let fields: Vec<&str> = line.split(',').collect();
        if fields.len() < 5 || fields[1] != "RX" {
            continue;
        }
        rx += 1;
        let bytes = hex_to_bytes(fields[3]);
        let kmp_verdict = fields[4].trim();

        let result = decoder.decode(&bytes, &state, &config);
        let rust_verdict = match &result {
            DecodeResult::Success(_) => "success",
            DecodeResult::Buffering => "buffering",
            DecodeResult::Unhandled { .. } => "unhandled",
        };

        if let DecodeResult::Success(delta) = &result {
            if let Some(t) = &delta.telemetry {
                state.telemetry = t.clone();
            }
            if let Some(i) = &delta.identity {
                state.identity = i.clone();
            }
            if let Some(b) = &delta.bms {
                state.bms = b.clone();
            }
            if let Some(s) = &delta.settings {
                state.settings = s.clone();
            }
            for ft in &delta.frame_types {
                *frame_type_counts.entry(ft.clone()).or_insert(0) += 1;
            }
            log_entry_count += delta.log_entries.len();
            max_speed = max_speed.max(state.telemetry.speed.abs());
            if state.telemetry.voltage > 0 {
                min_voltage = min_voltage.min(state.telemetry.voltage);
                max_voltage = max_voltage.max(state.telemetry.voltage);
            }
        }

        // The KMP capture annotates "success" / "buffering" / "unhandled…";
        // compare on the first token so detail suffixes don't false-mismatch.
        let kmp_normalized = kmp_verdict.split([':', ' ']).next().unwrap_or("");
        if kmp_normalized.is_empty() {
            uncompared += 1;
        } else if rust_verdict == kmp_normalized {
            matches += 1;
        } else {
            mismatches += 1;
            if mismatch_samples.len() < 10 {
                mismatch_samples.push(format!(
                    "  line {}: kmp={} rust={} hex={}",
                    lineno + 1,
                    kmp_verdict,
                    rust_verdict,
                    fields[3]
                ));
            }
        }
    }

    println!("RX packets replayed: {rx}");
    println!(
        "per-packet verdict:  {matches} match, {mismatches} mismatch, {uncompared} unannotated"
    );
    for sample in &mismatch_samples {
        println!("{sample}");
    }
    println!("frame types decoded: {frame_type_counts:?}");
    if log_entry_count > 0 {
        println!("event log entries:   {log_entry_count}");
    }
    let stats = decoder.unpacker_stats();
    println!(
        "unpacker stats:      errorResets={} bytesDiscarded={}",
        stats.error_resets, stats.bytes_discarded
    );
    println!(
        "final identity:      model='{}' brand='{}' fw='{}'",
        state.identity.model, state.identity.brand, state.identity.version
    );
    let t = &state.telemetry;
    println!(
        "final telemetry:     voltage={:.2}V battery={}% temp={:.1}C current={:.2}A",
        t.voltage as f64 / 100.0,
        t.battery_level,
        t.temperature as f64 / 100.0,
        t.current as f64 / 100.0,
    );
    match &state.settings {
        WheelSettings::Veteran(v) => {
            println!(
                "veteran settings:    mVer={} pedalsMode={} tiltBack={}km/h alert={}km/h lockState={} batteryTempMode={}",
                v.m_ver, v.pedals_mode, v.tilt_back_speed, v.alert_speed, v.lock_state, v.battery_temp_mode
            );
        }
        WheelSettings::Begode(_) | WheelSettings::None => {}
    }
    println!(
        "ride stats:          maxSpeed={:.2}km/h voltage=[{:.2}V..{:.2}V] totalDistance={}m wheelDistance={}m",
        max_speed as f64 / 100.0,
        min_voltage as f64 / 100.0,
        max_voltage as f64 / 100.0,
        t.total_distance,
        t.wheel_distance,
    );
    println!("decoder ready:       {}", decoder.is_ready());
}
