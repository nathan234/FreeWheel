# euc-protocols (experiment)

Rust port of the FreeWheel KMP protocol decoders, starting with the
Gotway/Begode decoder. This is the "test the thesis cheaply" experiment for a
possible long-term move of the protocol engine to Rust: port one
well-understood decoder plus its full test suite, and see what the Rust shape
of the decoder architecture feels like — before any FFI or app integration.

## What's here

| Rust | Ported from |
|------|-------------|
| `src/gotway.rs` | `core/.../protocol/GotwayDecoder.kt` |
| `src/veteran.rs` | `core/.../protocol/VeteranDecoder.kt` (incl. `VeteranUnpacker` with CRC latch + event-log parsing + password commands) |
| `src/unpacker.rs` | `core/.../protocol/GotwayUnpacker.kt` |
| `src/decode_loop.rs` | `DecodeLoop.kt` frame contract (`FrameResult`/`FrameOutcome`; each decoder keeps its own byte loop) |
| `src/catalog.rs` | `core/.../domain/profile/BegodeModelCatalog.kt` (all 61 entries) |
| `src/soc_tables.rs` | `VeteranSocTables.kt` (300 values, generated mechanically from the Kotlin source) |
| `src/checksums.rs` | `ProtocolChecksums.kt` (Veteran CRC32) |
| `src/types.rs` | `WheelDecoder.kt` result types + `TelemetryState`, `WheelIdentity`, `SmartBms`/`BmsSnapshot`, `WheelSettings.{Begode,Veteran}`, `EventLogEntry`, `DecoderConfig` |
| `src/byte_utils.rs` | `ByteUtils.kt` (BE/revBE reads) + JVM-exact rounding helpers |
| `tests/gotway_test.rs` | `GotwayDecoderTest.kt` — 80 tests, same frames/hex vectors/expected values |
| `tests/veteran_test.rs` | `VeteranDecoderTest.kt` + `LookupSocTest` + `VeteranUnpackerTest.kt` — 87 tests, incl. real Nosfet Aero/Apex capture frames |

**Sans-io deviation (Veteran):** the Kotlin decoder reads the system clock for
time-sync and password commands. The Rust port takes wall-clock components via
`VeteranDecoder::set_wall_clock` — the integration layer supplies the time,
the crate never reads a clock (defaults to zeroed fields until set).

All firmware variants are covered: Begode (GW/JL), ExtremeBull (JN),
Freestyl3r (CF), SmirnoV/Alexovik (BF), including the model catalog matching,
firmware-signature fallback, info-request retry ladder, settings echo
suppression, four-pack BMS accumulation, and the truePWM/trueVoltage/
trueCurrent latching semantics.

## Design

Sans-io, like the Kotlin decoder but with the boundary made explicit:

```rust
let mut decoder = GotwayDecoder::new();
// per BLE notification:
match decoder.decode(&bytes, &current_state, &config) {
    DecodeResult::Success(delta) => { /* merge delta, send delta.commands */ }
    DecodeResult::Buffering => {}
    DecodeResult::Unhandled { reason, .. } => { /* log */ }
}
```

The crate owns no BLE, threads, or clocks. Command timing (`SendDelayed`)
is expressed as data; the caller schedules it.

## Parity notes (things that would silently diverge without care)

- **Rounding**: Kotlin `roundToInt()` is JVM `Math.round` — `floor(x + 0.5)`,
  round-half-toward-+inf. Rust's `f64::round()` rounds half away from zero,
  which differs on negative ties (speed can be negative with
  `gotwayNegative=1`). `byte_utils::round_to_i32/round_to_i64` replicate the
  JVM rule.
- **f32 temperature math**: the MPU6050/6500 formulas are computed in Kotlin
  `Float`; the port keeps f32 so e.g. raw 99 → exactly 3682.
- **Signed BE reads**: `signedShortFromBytesBE` sign-extends byte 0 only;
  ported bit-for-bit rather than via `i16::from_be_bytes` to keep the
  out-of-bounds-returns-0 contract.
- **Battery % before voltage scaling**: percent is computed from the raw
  frame voltage, then the display voltage is scaled — order matters.
- **`hasNewData` timing**: computed *before* latching `trueVoltage` /
  `trueCurrent` (first 0x01/0x07 frame reports `false`).

## Running

```bash
cargo test                  # 168 parity tests ported from the Kotlin suites (no deps)
cargo test --features ffi   # same + the UniFFI session-layer tests
```

## Formal verification (Kani)

The parity tests prove agreement with the Kotlin decoder on known inputs;
the Kani harnesses (`#[cfg(kani)] mod verification` in `veteran.rs`) prove
robustness over **all** inputs up to a bound — a guarantee no test suite can
give for radio-facing parsers. Panic-freedom here covers index bounds,
arithmetic overflow, and slice errors — a corrupted BLE payload cannot crash
the decoder.

```bash
cargo kani --harness lookup_soc_bounds              # SOC ∈ [0,100] ∀ voltage, all tables (~1s)
cargo kani --harness battery_percent_bounds         # battery ∈ [0,100] ∀ mVer × u16 voltage (~12s)
cargo kani --harness sub_type_parsing_never_panics  # ∀ 90-byte buffer (~1s)
cargo kani --harness bms_accumulation_never_panics  # ∀ page × buffer, cells stay in-array (~5min)
cargo kani --harness log_basic_extended_never_panic # sub-types 0/4/32, ∀ 90-byte buffer (~6s)
cargo kani -Z stubbing --harness log_detailed_never_panics  # sub-type 33, ∀ buffer (~13s)
```

`log_detailed` stubs `decode_log_text`: `from_utf8_lossy` is total (it never
panics), but validating UTF-8 over symbolic bytes is intractable for a model
checker and isn't part of the index-safety surface under proof.

**Not model-checked:** the two frame unpackers. They are `Vec`-manipulating
state machines whose symbolic state space is intractable for CBMC even at a
16-byte window, so their panic surface (fixed `buffer[0..=5]` indexing in the
garbage-recovery paths) is covered by the 168 parity tests plus real capture
replays instead — thousands of valid, truncated, and garbage frames.

## FFI layer (`--features ffi`)

The pure crate stays dependency-free; the `ffi` feature adds UniFFI 0.29 and
`src/ffi.rs`, a session object that resolves the boundary design questions:

- `GotwaySession` **owns** `DecoderState` + `DecoderConfig` inside Rust
  (behind a `Mutex`, mirroring the Kotlin decoder's `Lock`). The host feeds
  `decode(bytes)` and gets the delta back; `current_state()` returns the
  accumulated state on demand. No per-call state marshaling host → Rust.
- Domain types carry `#[cfg_attr(feature = "ffi", derive(uniffi::Record/Enum))]`
  — single source of truth, no mirrored DTO layer.

Generate bindings (already checked into `bindings/` for inspection):

```bash
cargo build --features ffi
cargo run --features "ffi uniffi/cli" --bin uniffi-bindgen -- \
  generate --library target/debug/libeuc_protocols.dylib \
  --language swift --language kotlin --out-dir bindings
```

## iOS integration (verified on device 2026-07-25)

The iOS app consumes this crate as a **local Swift package** (`Package.swift`
here): a binary target wrapping `EucProtocols.xcframework` plus a source
target at `swift/EucProtocols/euc_protocols.swift`. The package gives the
Rust types their own `EucProtocols` module — compiling the generated Swift
directly into the app target shadows the KMP `FreeWheelCore` types
(`WheelIdentity`, `BmsState`, ...) and breaks the app.

After changing Rust code, refresh all three artifacts:

```bash
cargo build --release --features ffi --target aarch64-apple-ios
cargo build --release --features ffi --target aarch64-apple-ios-sim
xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libeuc_protocols.a -headers xcframework-staging/headers \
  -library target/aarch64-apple-ios-sim/release/libeuc_protocols.a -headers xcframework-staging/headers \
  -output EucProtocols.xcframework   # rm -rf EucProtocols.xcframework first
cp bindings/euc_protocols.swift swift/EucProtocols/euc_protocols.swift  # after regenerating bindings
```

(`xcframework-staging/headers/` holds `euc_protocolsFFI.h` + the modulemap
renamed to `module.modulemap`.)

Generated surface (~3.2k lines each side):

```swift
// Swift — real enums with associated values, Vec<u8> → Data
public enum DecodeResult {
    case success(DecodedData)
    case buffering
    case unhandled(reason: UnhandledReason, frameData: Data)
}
func decode(data: Data) -> DecodeResult
```

```kotlin
// Kotlin — sealed classes, Vec<u8> → ByteArray (JNA-backed calls)
sealed class DecodeResult {
    data class Success(val v1: DecodedData) : DecodeResult()
    object Buffering : DecodeResult()
    data class Unhandled(val reason: UnhandledReason, val frameData: ByteArray) : DecodeResult()
}
fun decode(data: ByteArray): DecodeResult
```

## Deliberate deviations

- Only the `WheelSettings::Begode` variant and the Gotway-relevant
  `WheelCommand` variants exist; other decoders would extend these enums.
- `BmsSnapshot` carries only the fields the Gotway decoder writes.
- No `Lock`: the decoder is `&mut self`; thread-safety is the caller's
  concern (wrap in a mutex at the integration layer if needed).
- Clippy's `large_enum_variant` on `DecodeResult`/`FrameOutcome` is left
  as-is to mirror the Kotlin sealed-class shape; a production crate would
  `Box` the large payloads.

## Not evaluated yet (next steps if the experiment continues)

- Consuming the generated bindings from a real Xcode / Gradle build
  (cross-compilation targets, XCFramework packaging, the Gobley JNI
  alternative on Android).
- A second decoder (Veteran) to see how much of `types.rs` generalizes.
- `no_std` feasibility for the ESP32 reference-protocol work (currently uses
  `std` for String/Vec/OnceLock/Mutex — all replaceable with `alloc` +
  `once_cell`, with the FFI layer staying std-only).
