//! # euc-protocols
//!
//! Experimental Rust port of the FreeWheel KMP protocol decoders, starting
//! with the Gotway/Begode decoder. Sans-io design: the crate owns no BLE,
//! no threads, no clocks — callers feed raw notification bytes into a
//! decoder and receive state deltas plus wheel-bound commands.
//!
//! The test suite (`tests/gotway_test.rs`) is a direct port of
//! `GotwayDecoderTest.kt` and serves as the behavioral parity oracle against
//! the Kotlin implementation.

pub mod byte_utils;
pub mod catalog;
pub mod checksums;
pub(crate) mod decode_loop;
#[cfg(feature = "ffi")]
pub mod ffi;
pub mod gotway;
pub mod soc_tables;
pub mod types;
pub mod unpacker;
pub mod veteran;

#[cfg(feature = "ffi")]
uniffi::setup_scaffolding!();

pub use gotway::GotwayDecoder;
pub use types::{
    BegodeSettings, BmsSnapshot, BmsState, CapabilitySet, DecodeResult, DecodedData,
    DecoderConfig, DecoderState, EventLogEntry, SettingsCommandId, TelemetryState,
    VeteranSettings, WheelCommand, WheelIdentity, WheelSettings, WheelType,
};
pub use veteran::{VeteranDecoder, WallClock};
