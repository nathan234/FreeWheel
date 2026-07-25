//! Replay a FreeWheel BLE capture CSV through the Rust Gotway decoder and
//! diff per-packet outcomes against the KMP decoder's recorded verdicts.
//!
//! The capture's `decode_result` column is what the KMP decoder said about
//! each packet on-device, so this is a packet-for-packet parity oracle over
//! real hardware traffic.
//!
//! Usage: cargo run --example replay -- <capture.csv>

use euc_protocols::byte_utils::hex_to_bytes;
use euc_protocols::{DecodeResult, DecoderConfig, DecoderState, GotwayDecoder};
use std::env;
use std::fs;

fn main() {
    let path = env::args().nth(1).expect("usage: replay <capture.csv>");
    let content = fs::read_to_string(&path).expect("failed to read capture");

    let mut decoder = GotwayDecoder::new();
    let mut state = DecoderState::default();
    let config = DecoderConfig::default(); // gotway_voltage: -1 (catalog auto)

    let mut rx = 0u32;
    let mut matches = 0u32;
    let mut mismatches = 0u32;
    let mut mismatch_samples: Vec<String> = Vec::new();
    let mut max_speed = 0i32;
    let mut min_voltage = i32::MAX;
    let mut max_voltage = 0i32;
    let mut frame_type_counts: std::collections::BTreeMap<String, u32> = Default::default();

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
            max_speed = max_speed.max(state.telemetry.speed.abs());
            if state.telemetry.voltage > 0 {
                min_voltage = min_voltage.min(state.telemetry.voltage);
                max_voltage = max_voltage.max(state.telemetry.voltage);
            }
        }

        if rust_verdict == kmp_verdict {
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
    println!("per-packet verdict:  {matches} match, {mismatches} mismatch");
    for sample in &mismatch_samples {
        println!("{sample}");
    }
    println!("frame types decoded: {frame_type_counts:?}");
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
