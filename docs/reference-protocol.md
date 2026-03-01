# Reference EUC BLE Protocol

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

### Phase 2: ESP32 Firmware

- [ ] Scaffold ESP-IDF project (or Arduino framework)
- [ ] BLE GATT server with dedicated service UUID
- [ ] Capability exchange handler
- [ ] VESC UART comm library (read telemetry, write config)
- [ ] Telemetry subscription engine (configurable push rate)
- [ ] Settings read/write proxying to VESC

### Phase 3: Integration + Demo

- [ ] End-to-end test: VESC dev board + ESP32 + WheelLog app
- [ ] Benchmark: latency, throughput, BLE packet efficiency vs existing protocols
- [ ] Document setup for Floatwheel / custom VESC builds

### Phase 4: Community

- [ ] Publish protocol spec as standalone document
- [ ] Open-source ESP32 firmware repo
- [ ] Engage Floatwheel / VESC EUC community for adoption feedback

## Open Questions

- Should the protocol support BMS cell voltage reporting? (Many wheels expose per-cell
  data over BLE — TLV handles variable-length naturally, but need to define the encoding.)
- Firmware OTA over this protocol, or leave that to manufacturer-specific channels?
- Encryption beyond password auth? BLE 4.2+ has LE Secure Connections, may be sufficient.
- Should the ESP32 firmware also expose a WebSocket/USB serial interface for desktop tools?
