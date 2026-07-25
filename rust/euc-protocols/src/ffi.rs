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
}
