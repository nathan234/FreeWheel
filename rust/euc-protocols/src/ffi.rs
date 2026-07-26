//! FFI session layer (enabled with the `ffi` feature).
//!
//! The pure decoder (`gotway::GotwayDecoder`) stays sans-io and `&mut self`;
//! this module adapts it for UniFFI's shared-object model:
//!
//! - **State ownership moves inside Rust.** The session owns the
//!   `DecoderState` and `DecoderConfig`, merges each decode's delta itself,
//!   and returns only the delta to the host. This removes the per-call state
//!   copy that a stateless boundary would pay, and gives the host a single
//!   source of truth it can query with `current_state()`.
//! - **Interior mutability.** UniFFI hands out `Arc<Self>`, so the decoder +
//!   state live behind a `Mutex` — the same role the Kotlin decoder's `Lock`
//!   plays on the WheelConnectionManager event loop.
//! - **No callbacks.** All methods are host → Rust; command timing crosses as
//!   data (`WheelCommand::SendDelayed`), never as a Rust-side timer.

use std::sync::Mutex;

use crate::gotway::GotwayDecoder;
use crate::types::{
    CapabilitySet, DecodeResult, DecodedData, DecoderConfig, DecoderState, WheelCommand,
};
use crate::veteran::{VeteranDecoder, WallClock};

struct SessionInner {
    decoder: GotwayDecoder,
    state: DecoderState,
    config: DecoderConfig,
}

/// One BLE connection's worth of decoding state.
#[derive(uniffi::Object)]
pub struct GotwaySession {
    inner: Mutex<SessionInner>,
}

fn merge(state: &mut DecoderState, delta: &DecodedData) {
    if let Some(telemetry) = &delta.telemetry {
        state.telemetry = telemetry.clone();
    }
    if let Some(identity) = &delta.identity {
        state.identity = identity.clone();
    }
    if let Some(bms) = &delta.bms {
        state.bms = bms.clone();
    }
    if let Some(settings) = &delta.settings {
        state.settings = settings.clone();
    }
}

#[uniffi::export]
impl GotwaySession {
    #[uniffi::constructor]
    pub fn new(config: DecoderConfig) -> Self {
        GotwaySession {
            inner: Mutex::new(SessionInner {
                decoder: GotwayDecoder::new(),
                state: DecoderState::default(),
                config,
            }),
        }
    }

    /// Feed one BLE notification. Returns the delta; the session has already
    /// merged it into its own state.
    pub fn decode(&self, data: Vec<u8>) -> DecodeResult {
        let mut inner = self.inner.lock().unwrap();
        let SessionInner {
            decoder,
            state,
            config,
        } = &mut *inner;
        let result = decoder.decode(&data, state, config);
        if let DecodeResult::Success(delta) = &result {
            merge(state, delta);
        }
        result
    }

    /// Accumulated state (telemetry, identity, BMS, settings) for this session.
    pub fn current_state(&self) -> DecoderState {
        self.inner.lock().unwrap().state.clone()
    }

    /// Replace the decoder configuration (pref changes mid-connection).
    pub fn update_config(&self, config: DecoderConfig) {
        self.inner.lock().unwrap().config = config;
    }

    /// Translate a high-level command into protocol bytes + delays.
    pub fn build_command(&self, command: WheelCommand) -> Vec<WheelCommand> {
        self.inner.lock().unwrap().decoder.build_command(&command)
    }

    /// Commands to send after connecting.
    pub fn init_commands(&self) -> Vec<WheelCommand> {
        self.inner.lock().unwrap().decoder.get_init_commands()
    }

    pub fn is_ready(&self) -> bool {
        self.inner.lock().unwrap().decoder.is_ready()
    }

    pub fn capabilities(&self) -> CapabilitySet {
        self.inner.lock().unwrap().decoder.get_capabilities()
    }

    /// Reset decoder + accumulated state (disconnect / wheel switch).
    pub fn reset(&self) {
        let mut inner = self.inner.lock().unwrap();
        inner.decoder.reset();
        inner.state = DecoderState::default();
    }
}

struct VeteranInner {
    decoder: VeteranDecoder,
    state: DecoderState,
    config: DecoderConfig,
}

/// One BLE connection's worth of Veteran/Leaperkim decoding state.
#[derive(uniffi::Object)]
pub struct VeteranSession {
    inner: Mutex<VeteranInner>,
}

#[uniffi::export]
impl VeteranSession {
    #[uniffi::constructor]
    pub fn new(config: DecoderConfig) -> Self {
        VeteranSession {
            inner: Mutex::new(VeteranInner {
                decoder: VeteranDecoder::new(),
                state: DecoderState::default(),
                config,
            }),
        }
    }

    /// Supply the current wall-clock. The crate is sans-io and never reads a
    /// clock; the host provides it for time-sync/password command timestamps.
    pub fn set_wall_clock(&self, clock: WallClock) {
        self.inner.lock().unwrap().decoder.set_wall_clock(clock);
    }

    /// Feed one BLE notification. Returns the delta; the session has already
    /// merged it into its own state.
    pub fn decode(&self, data: Vec<u8>) -> DecodeResult {
        let mut inner = self.inner.lock().unwrap();
        let VeteranInner {
            decoder,
            state,
            config,
        } = &mut *inner;
        let result = decoder.decode(&data, state, config);
        if let DecodeResult::Success(delta) = &result {
            merge(state, delta);
        }
        result
    }

    /// Accumulated state (telemetry, identity, BMS, settings) for this session.
    pub fn current_state(&self) -> DecoderState {
        self.inner.lock().unwrap().state.clone()
    }

    /// Replace the decoder configuration (pref changes mid-connection).
    pub fn update_config(&self, config: DecoderConfig) {
        self.inner.lock().unwrap().config = config;
    }

    /// Translate a high-level command into protocol bytes + delays.
    pub fn build_command(&self, command: WheelCommand) -> Vec<WheelCommand> {
        let mut inner = self.inner.lock().unwrap();
        let VeteranInner { decoder, state, .. } = &mut *inner;
        let state_snapshot = state.clone();
        decoder.build_command(&command, Some(&state_snapshot))
    }

    pub fn is_ready(&self) -> bool {
        self.inner.lock().unwrap().decoder.is_ready()
    }

    pub fn capabilities(&self) -> CapabilitySet {
        self.inner.lock().unwrap().decoder.get_capabilities()
    }

    /// Reset decoder + accumulated state (disconnect / wheel switch).
    pub fn reset(&self) {
        let mut inner = self.inner.lock().unwrap();
        inner.decoder.reset();
        inner.state = DecoderState::default();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::TelemetryState;

    fn live_frame(voltage: i32) -> Vec<u8> {
        let mut out = vec![0x55, 0xAA];
        out.extend_from_slice(&[((voltage >> 8) & 0xFF) as u8, (voltage & 0xFF) as u8]);
        out.extend_from_slice(&[0, 0, 0, 0, 0, 0, 0, 0]);
        out.extend_from_slice(&[0, 99]); // temperature
        out.extend_from_slice(&[0, 0, 0, 0, 0, 0x18, 0x5A, 0x5A, 0x5A, 0x5A]);
        out
    }

    #[test]
    fn session_owns_and_merges_state() {
        let session = GotwaySession::new(DecoderConfig {
            gotway_voltage: 0,
            ..Default::default()
        });
        session.decode(b"GW1.23".to_vec());
        let result = session.decode(live_frame(6000));
        assert!(matches!(result, DecodeResult::Success(_)));

        let state = session.current_state();
        assert_eq!(6000, state.telemetry.voltage);
        assert_eq!("Begode", state.identity.brand);
        assert!(session.is_ready());

        session.reset();
        assert_eq!(TelemetryState::default(), session.current_state().telemetry);
        assert!(!session.is_ready());
    }

    #[test]
    fn veteran_session_owns_and_merges_state() {
        let session = VeteranSession::new(DecoderConfig::default());
        session.set_wall_clock(WallClock {
            year: 2026,
            month: 7,
            day: 26,
            hour: 12,
            minute: 0,
            second: 0,
            tz_offset_hours: -4,
        });

        // Minimal 36-byte legacy frame: header DC 5A 5C, len 32, voltage 9686,
        // fw version 5000 (mVer 5 → Lynx) at bytes 28-29.
        let mut frame = vec![0u8; 36];
        frame[0] = 0xDC;
        frame[1] = 0x5A;
        frame[2] = 0x5C;
        frame[3] = 32;
        frame[4] = 0x25;
        frame[5] = 0xD6;
        frame[28] = ((5000 >> 8) & 0xFF) as u8;
        frame[29] = (5000 & 0xFF) as u8;

        let result = session.decode(frame);
        assert!(matches!(result, DecodeResult::Success(_)));

        let state = session.current_state();
        assert_eq!(9686, state.telemetry.voltage);
        assert_eq!("Leaperkim Lynx", state.identity.model);
        assert!(session.is_ready());

        // First frame emits the two time-sync commands with the injected clock
        // (year byte = 26) — verify via a fresh session.
        let fresh = VeteranSession::new(DecoderConfig::default());
        fresh.set_wall_clock(WallClock {
            year: 2026,
            month: 7,
            day: 26,
            hour: 12,
            minute: 0,
            second: 0,
            tz_offset_hours: -4,
        });
        let mut frame2 = vec![0u8; 36];
        frame2[..4].copy_from_slice(&[0xDC, 0x5A, 0x5C, 32]);
        frame2[28] = ((5000 >> 8) & 0xFF) as u8;
        frame2[29] = (5000 & 0xFF) as u8;
        if let DecodeResult::Success(delta) = fresh.decode(frame2) {
            let sync: Vec<_> = delta
                .commands
                .iter()
                .filter_map(|c| match c {
                    WheelCommand::SendBytes(d) | WheelCommand::SendDelayed(d, _) => Some(d),
                    _ => None,
                })
                .filter(|d| d.len() >= 8 && d[4] == 0x12)
                .collect();
            assert_eq!(2, sync.len());
            assert_eq!(26, sync[0][7], "year byte should come from injected WallClock");
        } else {
            panic!("expected Success");
        }
    }
}
