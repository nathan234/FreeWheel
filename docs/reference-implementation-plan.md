# Reference Protocol — Implementation Plan

Companion to [`reference-protocol.md`](reference-protocol.md). See also
[`protocol-quality-assessment.md`](protocol-quality-assessment.md) for the analysis motivating this work.
This document covers the concrete hardware, software, and phasing decisions for building
the first working implementation of the open TLV protocol.

## Hardware Strategy

### Target Wheel: Begode MTen Mini

The MTen Mini is the development target for real-wheel integration:

| Spec | Value |
|------|-------|
| Battery | 10S, 42V full charge, 180Wh |
| Cells | 21700 40T |
| Motor | 500W |
| Wheel size | 11" |
| Weight | ~9 kg |
| Top speed | ~16 km/h (10 mph) |

**Why the MTen Mini:**
- **42V (10S) fits any VESC 6** — 60V max rating gives 18V of headroom. No need for
  expensive 75V+ controllers. The same Flipsky VESC 6.7 used for bench testing works
  for the real wheel.
- **Expendable** — Low-end wheel, acceptable to gut for a controller swap.
- **Begode** — Largest DIY/hacker community, most reverse-engineering documentation,
  simplest existing protocol (good baseline for comparison).
- **Small** — Easy to bench-test and transport.
- **Already owned** — No purchase needed.

The MTen5+ (20S, 84V, 750Wh) stays as a daily rider — its voltage exceeds affordable
VESC ratings, and it's too expensive to use as a dev board.

### Phased Approach

The protocol and app-side decoder are entirely testable without a motor or battery.
Hardware complexity ramps up only when needed:

| Phase | Hardware | Voltage | Approx Cost | Purpose |
|-------|----------|---------|-------------|---------|
| 1 — Protocol + Decoder | ESP32-S3-DevKitC | USB 5V | ~$10 | BLE GATT server with simulated telemetry |
| 2 — VESC Bridge | + Flipsky VESC 6.7 + small BLDC motor | 10S (42V) | ~$120 | Validate UART/CAN comm, real telemetry |
| 3 — Real Wheel | MTen Mini, swap controller | 10S (42V) | ~$0 (owned) | End-to-end rideable proof of concept |

The same Flipsky VESC 6.7 carries through from Phase 2 bench testing into Phase 3 real
wheel — no controller upgrade needed. 10S (42V) is well within its 60V rating at every
phase.

### Component Details

**ESP32-S3-DevKitC** (~$8) — BLE 5.0, dual-core, built-in USB-JTAG for debugging.
Espressif's `esp-idf-svc` Rust crate has mature GATT server support.

**Flipsky VESC 6.7** (~$70) — Cheapest VESC variant with UART + CAN interfaces.
Community-proven, VESC Tool compatible. 60V max rating vs 42V full charge = 18V
headroom — comfortable for both bench testing and real wheel use. 500W MTen Mini motor
is well within its current limits.

**10S bench battery** — For Phase 2 bench testing, a 10S LiPo pack (~$30-50) matches
the MTen Mini's voltage exactly. Commodity RC parts, readily available.

## Phase 1: Protocol + KMP Decoder (No Motor Required)

### What We Build

**ESP32-S3 Firmware (Rust)**
- BLE GATT server with dedicated service UUID (distinct from all manufacturer UUIDs)
- TLV frame codec: serialize/deserialize the framing format from the protocol spec
- Capability exchange handler: respond to requests with a hardcoded field catalog
- Simulated telemetry engine: generate realistic data patterns:
  - Speed: sinusoidal ramps 0-16 km/h (matching MTen Mini range)
  - Voltage: slow linear decay 42V → 33V (10S full → empty)
  - Current: correlated to speed changes (acceleration = current spike)
  - Temperature: slow ramp from 25°C to 45°C
  - Battery: derived from voltage curve
  - Distance: monotonic accumulation from speed integration
- Subscription engine: only push fields the app requested, at requested rate
- Settings read/write: store in NVS (ESP32 flash key-value store), echo back on read

**KMP Decoder (`core/protocol/`)**
- `ReferenceDecoder` implementing `WheelDecoder` interface
- TLV frame parser in `decode()` — generic, driven by capability response
- Field catalog stored from capability exchange — maps field IDs to WheelState properties
- `getInitCommands()`: capability request + default telemetry subscription
- `isReady()`: true after capability response received
- `getKeepAliveCommand()`: empty (push-based, no polling)
- `buildCommand()`: map `WheelCommand` enum to settings write TLV frames
- No unpacker needed — length-prefixed framing, no escape sequences

**Integration**
- Add `WheelType.REFERENCE` to the enum
- Add dedicated BLE service UUID to `BleUuids` and `WheelTypeDetector`
- Wire into `DefaultWheelDecoderFactory`
- Unit tests with synthetic TLV frames (same pattern as other decoder tests)

### What We Skip in Phase 1

- VESC communication (no motor)
- Authentication (not needed for dev)
- BMS cell voltage reporting (deferred — listed in open questions)
- OTA firmware updates

### Deliverables

- [ ] TLV frame codec (Rust, ESP32)
- [ ] BLE GATT server with service UUID
- [ ] Capability exchange (hardcoded field catalog)
- [ ] Simulated telemetry engine with realistic patterns
- [ ] Subscription engine (field selection + rate control)
- [ ] Settings read/write over NVS
- [ ] `ReferenceDecoder.kt` in `core/protocol/`
- [ ] `WheelType.REFERENCE` + factory + detector wiring
- [ ] Unit tests with synthetic TLV frames
- [ ] End-to-end: ESP32 dev board + FreeWheel app showing live simulated data

## Phase 2: VESC Bridge

### What We Build

- VESC UART comm module on ESP32 (`esp-idf-hal::uart`)
- VESC packet protocol: request telemetry, read/write config
- Field mapping from VESC values to TLV protocol fields (table in protocol spec)
- Replace simulated telemetry with real VESC data
- Settings write proxying: app writes max_speed → ESP32 translates to VESC config

### Hardware Setup

```
LiPo 10S ──► Flipsky VESC 6.7 ──► small BLDC motor (bench-mounted)
                    │
                  UART
                    │
                 ESP32-S3 ──► BLE ──► FreeWheel app
```

### Deliverables

- [ ] VESC UART driver (request/response with checksums)
- [ ] Telemetry mapping: RPM → speed, v_in → voltage, temps, etc.
- [ ] Settings proxying: at least max_speed, pedal_tilt, headlight
- [ ] Bench demo: spin motor, see real telemetry in FreeWheel

## Phase 3: Real Wheel Integration

### What We Build

- Swap MTen Mini control board with ESP32 + Flipsky VESC 6.7 (same unit from Phase 2)
- Motor detection + configuration via VESC Tool for MTen Mini's 500W hub motor
- Balance controller tuning (PID parameters for EUC single-axis balance)
- Alarm integration: speed alarms, temperature warnings, low battery
- Ride testing and protocol stress testing under real-world conditions

### Open Work

Balance control for an EUC on VESC is the hardest unsolved piece. The VESC float/refloat
packages are Onewheel-oriented (longitudinal balance). An EUC needs lateral balance with
very different dynamics. This is a firmware challenge independent of the BLE protocol — the
protocol layer should work identically whether telemetry comes from simulated data, a bench
motor, or a rideable wheel.

### Deliverables

- [ ] Motor detection + VESC configuration for MTen Mini hub motor
- [ ] Balance controller (or manual/assisted testing without self-balance)
- [ ] Ride log comparison: reference protocol vs legacy Gotway protocol
- [ ] Latency/throughput benchmarks

## Separate Firmware Repository

The ESP32 Rust firmware will live in its own repository — it's a standalone embedded
project with its own toolchain (`cargo`, `espflash`, `esp-idf`), CI, and release cycle.
This repo contains only the KMP decoder side.

## Risk Register

| Risk | Impact | Mitigation |
|------|--------|------------|
| `esp-idf-svc` BLE gaps (MTU negotiation, connection params) | Medium | Thin unsafe FFI wrappers — manageable |
| VESC EUC balance controller doesn't exist | High | Phase 3 only; protocol + decoder work is independent of balance |
| BLE throughput at high subscription rates | Low | TLV is compact; benchmark in Phase 2 |
| MTen Mini motor incompatibility with VESC | Medium | Motor detection wizard; Begode motors are well-documented |
| VESC 6.7 current limits for 500W motor | Low | 500W / 42V ≈ 12A — well within VESC 6.7 ratings |
