//! Shared per-frame result types (port of `DecodeLoop.kt`'s `FrameResult` /
//! `FrameOutcome`). Each decoder keeps its own byte loop — the Kotlin inline
//! `decodeFrames` helper doesn't translate cleanly through Rust's borrow rules,
//! and the loop is ~30 lines — but the frame-processing contract is shared.

use crate::types::{
    EventLogEntry, TelemetryState, WheelCommand, WheelIdentity, WheelSettings,
};

/// Result of processing a single unpacked frame.
#[derive(Debug, Clone, Default)]
pub(crate) struct FrameResult {
    pub telemetry: Option<TelemetryState>,
    pub identity: Option<WheelIdentity>,
    pub settings: Option<WheelSettings>,
    pub has_new_data: bool,
    /// Per-frame reply commands (e.g. Kingsong's 0xA4 → 0x98 ack). Unused by
    /// the Gotway/Veteran decoders but part of the shared frame contract.
    #[allow(dead_code)]
    pub commands: Vec<WheelCommand>,
    pub news: Option<String>,
    pub frame_type: Option<&'static str>,
    pub log_entries: Vec<EventLogEntry>,
}

pub(crate) enum FrameOutcome {
    Processed(FrameResult),
    Unrecognized(String),
}
