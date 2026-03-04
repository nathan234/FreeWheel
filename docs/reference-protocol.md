# Reference EUC BLE Protocol

See [reference-implementation-plan.md](reference-implementation-plan.md) for the concrete hardware/software
phasing, and [protocol-quality-assessment.md](protocol-quality-assessment.md) for the analysis of existing
protocols that motivates this design.

## Goal

Design and implement an open, self-describing BLE protocol for electric unicycles,
with a reference firmware on ESP32 targeting VESC-based boards. Replace the current
landscape of ad-hoc, positional-offset protocols with something that doesn't require
app updates when wheels add features.

## Motivation

Every EUC manufacturer invented their own BLE protocol. After implementing seven
decoders (KS, GW, VT, NB, NZ, IM1, IM2) plus Leaperkim CAN, the pain points are
clear:

- **Positional offsets**: Field meaning depends on byte position. Firmware changes
  break parsers. Every decoder is a table of magic offsets.
- **Escape complexity**: Sentinel-framed protocols (Gotway, Leaperkim) require byte
  stuffing, adding variable-length encoding and edge cases.
- **Init state machines**: NinebotZ needs 14 ordered states. Leaperkim needs 3-step
  handshake. One wrong step = wheel stops responding.
- **Polling waste**: Leaperkim polls every 500ms. Kingsong streams without asking.
  No consistency, no configurability.
- **Hardcoded settings UI**: `WheelSettingsConfig.kt` has per-wheel section lists.
  New wheel = new code in app.

## Protocol Design

### Framing

Length-prefixed, no escape stuffing:

```
[magic: 0xEC 0x01] [length: uint16 LE] [payload: N bytes] [crc16: uint16 LE]
```

- `length` covers payload only (not magic or CRC)
- CRC-16/CCITT over payload bytes
- No sentinel trailers, no byte stuffing
- Max payload: 512 bytes (fits in BLE MTU negotiation up to 517)

### Message Format (TLV)

All payload content is TLV (Tag-Length-Value):

```
[msg_type: uint8] [seq: uint8] [field_id: uint16 LE] [length: uint8] [value: bytes] ...
```

Message types:
- `0x01` Capability Request (app -> wheel)
- `0x02` Capability Response (wheel -> app)
- `0x03` Subscribe (app -> wheel, list of field IDs + rate)
- `0x04` Telemetry Push (wheel -> app, subscribed fields)
- `0x05` Settings Read (app -> wheel)
- `0x06` Settings Response (wheel -> app)
- `0x07` Settings Write (app -> wheel)
- `0x08` Settings ACK (wheel -> app)
- `0x09` Auth Request (app -> wheel)
- `0x0A` Auth Response (wheel -> app)

Sequence numbers (`seq`) correlate requests to responses.

### Capability Exchange

On connect, app sends Capability Request. Wheel responds with:

```
device_name: string
firmware_version: string
protocol_version: uint8
serial: string
fields: [
  { field_id: uint16, type: uint8, scale: int16, unit: string, min: int32, max: int32 }
  ...
]
```

Field types: `0x01` int32, `0x02` float32, `0x03` bool, `0x04` string, `0x05` enum (options in `unit` field).

The app renders settings UI directly from this — no hardcoded per-wheel config.

### Telemetry Subscription

App sends Subscribe with desired field IDs and push rate (ms). Wheel pushes
Telemetry frames at that rate containing only subscribed fields. App can
change subscription at any time.

Default subscription: speed, voltage, current, temperature, battery, distance.

### Authentication

Single round-trip:
1. App sends Auth Request with password (or empty for no-auth wheels)
2. Wheel responds with Auth Response (success/failure + capability data)

No multi-step handshakes.

### Standard Field IDs

| ID | Name | Type | Scale | Unit |
|----|------|------|-------|------|
| 0x0001 | speed | int32 | 100 | km/h |
| 0x0002 | voltage | int32 | 100 | V |
| 0x0003 | current | int32 | 100 | A |
| 0x0004 | phase_current | int32 | 100 | A |
| 0x0005 | power | int32 | 100 | W |
| 0x0006 | temperature | int32 | 100 | C |
| 0x0007 | temperature2 | int32 | 100 | C |
| 0x0008 | battery_level | int32 | 1 | % |
| 0x0009 | total_distance | int32 | 1 | m |
| 0x000A | trip_distance | int32 | 1 | m |
| 0x000B | pwm | int32 | 100 | % |
| 0x000C | pitch_angle | int32 | 100 | deg |
| 0x000D | roll_angle | int32 | 100 | deg |
| 0x000E | motor_rpm | int32 | 1 | rpm |
| 0x0100 | max_speed | int32 | 1 | km/h |
| 0x0101 | pedal_tilt | int32 | 10 | deg |
| 0x0102 | pedal_sensitivity | int32 | 1 | % |
| 0x0103 | ride_mode | bool | - | - |
| 0x0104 | headlight | bool | - | - |
| 0x0105 | led_enabled | bool | - | - |
| 0x0106 | handle_button | bool | - | - |
| 0x0107 | transport_mode | bool | - | - |
| 0x0108 | speaker_volume | int32 | 1 | % |
| 0x0109 | lock | bool | - | - |
| 0x0200 | model_name | string | - | - |
| 0x0201 | serial_number | string | - | - |
| 0x0202 | firmware_version | string | - | - |

IDs 0x0001-0x00FF: telemetry (read-only, subscribable).
IDs 0x0100-0x01FF: settings (read-write).
IDs 0x0200-0x02FF: identity (read-only, returned in capability exchange).
IDs 0x8000+: vendor-specific extensions.

## Hardware Target

### Architecture

```
Phone App (KMP decoder)
    | BLE (this protocol)
ESP32-S3 (protocol firmware)
    | UART / CAN
VESC motor controller
```

### Firmware Language: Rust

The reference firmware targets Rust via Espressif's official `esp-idf-hal` / `esp-idf-svc`
crates. Rationale:

- **BLE GATT server**: `esp-idf-svc::bt` wraps the Bluedroid stack with type-safe callbacks
  instead of raw C function pointers.
- **TLV encoding**: Rust enums + `#[repr(u16)]` field IDs + zero-cost byte-slice abstractions
  are a natural fit for the frame format. No manual buffer management.
- **VESC UART**: `esp-idf-hal::uart` for hardware, existing Rust VESC UART crates for
  the protocol layer.
- **Safety**: No manual memory management for frame buffers, subscription lists, or capability
  tables — the main sources of bugs in embedded C BLE stacks.
- **Community fit**: Strong overlap between custom EUC firmware builders and Rust embedded
  enthusiasts. More likely to attract contributors than C.

Known friction: BLE bindings in `esp-idf-svc` cover GATT server/client but some edge
cases (MTU negotiation, connection parameter updates) may need occasional unsafe FFI
into the underlying C ESP-IDF. Not a dealbreaker.

Estimated size: ~2-4K lines of Rust (comparable to C, slightly more verbose with explicit
error handling but less boilerplate for data structures).

### Bill of Materials

- **ESP32-S3-DevKitC** (~$8): BLE 5.0, UART, CAN (via SN65HVD230 transceiver)
- **VESC** (any variant): open-source motor controller, existing UART/CAN telemetry API

### VESC Field Mapping

| VESC Value | Protocol Field |
|------------|---------------|
| `rpm * 3.6 / (poles * gear_ratio * wheel_diameter * pi)` | speed |
| `v_in` | voltage |
| `current_in` | current |
| `current_motor` | phase_current |
| `v_in * current_in` | power |
| `temp_mos` | temperature |
| `temp_motor` | temperature2 |
| `battery_level` (computed) | battery_level |
| `tachometer_abs * wheel_circumference / (poles * 3 * gear_ratio)` | distance |
| `abs(duty_cycle) * 100` | pwm |
| IMU pitch | pitch_angle |
| IMU roll | roll_angle |

## Implementation Plan

### Phase 1: Protocol Spec + KMP Decoder

- [ ] Finalize TLV field catalog and message format
- [ ] Implement `ReferenceDecoder` in `core/protocol/` — generic TLV parser
  driven by capability exchange, not hardcoded offsets
- [ ] Unit tests with synthetic frames
- [ ] Wire into `WheelType.REFERENCE`, factory, detector (dedicated BLE service UUID)

### Phase 2: ESP32 Firmware (Rust)

- [ ] Scaffold `esp-idf-hal` project with `cargo` + `espflash` toolchain
- [ ] BLE GATT server with dedicated service UUID (`esp-idf-svc::bt`)
- [ ] Capability exchange handler
- [ ] VESC UART comm module (`esp-idf-hal::uart` + VESC packet protocol)
- [ ] Telemetry subscription engine (configurable push rate)
- [ ] Settings read/write proxying to VESC

### Phase 3: Integration + Demo

- [ ] End-to-end test: VESC dev board + ESP32 + FreeWheel app
- [ ] Benchmark: latency, throughput, BLE packet efficiency vs existing protocols
- [ ] Document setup for Floatwheel / custom VESC builds

### Phase 4: Community

- [ ] Publish protocol spec as standalone document
- [ ] Open-source ESP32 firmware repo
- [ ] Engage Floatwheel / VESC EUC community for adoption feedback

## Berm Angle on VESC

No VESC firmware currently has a direct "berm angle" feature like InMotion's. The closest
analog is **Turn Tilt** in the [Float Package](https://pev.dev/t/float-package-start-here/742)
and its fork [Refloat](https://github.com/lukash/refloat/releases), but these are
Onewheel-oriented (single-axis balance), not EUC:

- **InMotion berm angle**: Allows the EUC to lean into banked turns up to a configurable
  max angle, suppressing the gyro's instinct to correct back to vertical.
- **Float/Refloat Turn Tilt**: Compensates for yaw-induced pitch artifacts when carving —
  when the board turns, roll gets coupled into pitch, causing nose-dip. Turn Tilt
  counteracts this. Not the same thing.

For an EUC on VESC, berm angle would need to be implemented in the balance loop — detect
sustained yaw rotation (from gyro) and relax the roll correction proportionally up to the
configured limit. Estimated ~50-100 lines in the balance controller. Nobody's done it yet
for VESC EUC firmware because the VESC EUC scene is still small.

In our reference protocol, berm angle is just two self-describing settings fields:
```
{ field_id: 0x010A, type: bool,  name: "berm_angle_mode" }
{ field_id: 0x010B, type: int32, scale: 1, unit: "deg", min: 0, max: 45, name: "berm_angle" }
```

## IMU Sensor Drift Mitigation

Sensor drift is a well-solved problem with multiple layers. The reference firmware should
implement all of these:

### 1. Gyro Bias Calibration at Startup

Sample gyro for 1-2 seconds while stationary, average the readings, subtract as offset.
VESC's [IMU Calibration Wizard](https://pev.dev/t/wiki-imu-calibration-wizard-guide-vesc-tool-6-02/699)
already does this.

### 2. Complementary / Mahony AHRS Filter

Fuse gyro (fast, drifts) with accelerometer (slow, noisy but absolute reference to gravity).
Classic complementary filter:
```
angle = alpha * (angle + gyro_rate * dt) + (1 - alpha) * accel_angle
```
VESC uses a [Mahony AHRS filter](https://github.com/vedderb/bldc/blob/master/imu/imu.c)
which is a more sophisticated version — tracks orientation as a quaternion with proportional
+ integral error correction from accelerometer/magnetometer.

### 3. Per-Axis Mahony KP Tuning

Key insight from [Refloat](https://github.com/lukash/refloat/releases): high KP on pitch
(fast balance correction), low KP on roll (prevents turn artifacts from coupling into
balance). When the board rotates in yaw, roll gets projected onto pitch — high roll KP
causes this coupling to linger, making the nose dip during turns. Lower roll KP reduces
this "drift" during carving.

Expose these as configurable protocol fields:
```
{ field_id: 0x0110, type: int32, scale: 1000, name: "mahony_kp_pitch" }
{ field_id: 0x0111, type: int32, scale: 1000, name: "mahony_kp_roll" }
```

### 4. Temperature Compensation

Gyro bias drifts with temperature. Sample bias periodically during known-stationary
moments (detected via accelerometer magnitude near 1g) and update the offset. More
important for long rides where board temperature changes significantly.

### What We Get for Free

Layers 1-3 are essentially free — VESC's IMU code already implements Mahony AHRS. The
reference firmware just needs to:
- Expose the KP parameters as configurable fields in the protocol
- Run the existing VESC IMU calibration on startup
- Optionally add temperature-aware bias tracking (layer 4)

## Open Questions

- Should the protocol support BMS cell voltage reporting? (Many wheels expose per-cell
  data over BLE — TLV handles variable-length naturally, but need to define the encoding.)
- Firmware OTA over this protocol, or leave that to manufacturer-specific channels?
- Encryption beyond password auth? BLE 4.2+ has LE Secure Connections, may be sufficient.
- Should the ESP32 firmware also expose a WebSocket/USB serial interface for desktop tools?
