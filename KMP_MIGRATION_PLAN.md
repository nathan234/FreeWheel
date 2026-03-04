# FreeWheel KMP Migration Plan

This document tracks the migration of FreeWheel's core functionality to Kotlin Multiplatform for iOS support. See also [CLAUDE.md](CLAUDE.md) for detailed architecture and [README.md](README.md) for build commands.

## Overview

**Goal**: Extract protocol decoding and BLE communication into a shared KMP module (`core`) that can be used by both Android and iOS apps.

**Branch**: `main`

---

## Phase 1: Domain Models & Utilities ✅ COMPLETE

- [x] WheelState data class
- [x] WheelType enum
- [x] SmartBms data class
- [x] AlarmType enum
- [x] ByteUtils (LE/BE byte conversions)
- [x] StringUtil

---

## Phase 2: Protocol Decoders ✅ COMPLETE

### Interface & Infrastructure
- [x] WheelDecoder interface
- [x] DecoderConfig (useMph, useFahrenheit, useCustomPercents)
- [x] WheelCommand sealed class
- [x] DecodedData result class
- [x] WheelDecoderFactory interface
- [x] DefaultWheelDecoderFactory
- [x] CachingWheelDecoderFactory

### Wheel Decoders
| Decoder | Unpacker | Status | Tests |
|---------|----------|--------|-------|
| GotwayDecoder | GotwayUnpacker | ✅ | ✅ |
| VeteranDecoder | (uses Gotway) | ✅ | ✅ |
| KingsongDecoder | (inline) | ✅ | ✅ |
| InMotionDecoder | InMotionUnpacker | ✅ | ✅ |
| InMotionV2Decoder | InMotionV2Unpacker | ✅ | ✅ |
| NinebotDecoder | NinebotUnpacker | ✅ | ✅ |
| NinebotZDecoder | NinebotZUnpacker | ✅ | ✅ |
| AutoDetectDecoder | (delegates) | ✅ | ✅ |

---

## Phase 3: BLE Layer ✅ COMPLETE

### 3.1 BLE UUIDs & Service Detection ✅
- [x] Create `BleUuids.kt` with all manufacturer UUIDs
- [x] Create `WheelTypeDetector` to identify wheel type from services
- [x] Unit tests for UUID matching and wheel detection

### 3.2 BLE Abstractions (Common) ✅
- [x] ConnectionState sealed class
- [x] BleDevice data class
- [x] BleManager expect class (stubs)
- [x] WheelConnectionManager with keep-alive timer
- [x] KeepAliveTimer for periodic commands
- [x] DataTimeoutTracker for connection loss detection
- [x] CommandScheduler for delayed commands
- [x] Unit tests for timer components

### 3.3 Android BLE Implementation ✅
- [x] Implement BleManager.android.kt with blessed-android
- [x] Bridge mode for existing BluetoothService (incremental migration)
- [x] Characteristic read/write with chunking (20-byte for InMotion)
- [x] MTU negotiation support
- [x] WheelConnectionInfo for connection configuration
- [ ] Integration tests (requires device)

### 3.4 iOS BLE Implementation ✅
- [x] BleManager.ios.kt with full CoreBluetooth integration
- [x] CBCentralManager wrapper with delegate callbacks
- [x] CBPeripheral handling with delegate callbacks
- [x] Characteristic discovery and notification subscription
- [x] ByteArray <-> NSData conversion utilities
- [x] Chunked write support for InMotion V1
- [x] Real device testing

### 3.5 Keep-Alive Timer ✅
- [x] Platform-agnostic timer abstraction (KeepAliveTimer)
- [x] Decoder-specific intervals (see table below)
- [x] Unit tests for timer behavior
- [x] currentTimeMillis expect/actual for Android/iOS

**Keep-Alive Intervals by Decoder**:
| Decoder | Interval (ms) |
|---------|---------------|
| Gotway/Veteran | N/A (wheel-initiated) |
| Kingsong | N/A (wheel-initiated) |
| InMotion V1 | 250 |
| InMotion V2 | 25 |
| Ninebot | 125 |
| Ninebot Z | 25 |

---

## Phase 4: Integration

### 4.1 Android Integration ✅
- [x] ~~KmpWheelBridge for parallel decoding~~ (removed)
- [x] FreeWheel Compose app (`freewheel/`) with WheelService using KMP WheelConnectionManager directly
- [x] WheelViewModel orchestrates KMP state, scanning, logging, alarms
- [x] Legacy `app/` module removed (all code lives in `freewheel/` + `core/`)

### 4.2 iOS App Scaffold ✅
- [x] Create iOS Xcode project
- [x] Add core module as dependency
- [x] SwiftUI app with dashboard, charts, settings, ride history
- [x] BLE scanning, connection, and background mode

---

## Phase 5: Advanced Features

- [x] Alarm handling (AlarmChecker + AlarmHandler with vibration/sound/notifications)
- [x] Trip statistics (RideLogger CSV + TripRepository on Android, RideStore on iOS)
- [x] PWM/power calculations (per-decoder output % and power factor)
- [ ] Firmware update support (where applicable)

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        App Layer                             │
│  ┌─────────────────┐              ┌─────────────────┐       │
│  │  Android App    │              │    iOS App      │       │
│  │  (Kotlin/Java)  │              │   (Swift/UI)    │       │
│  └────────┬────────┘              └────────┬────────┘       │
└───────────┼────────────────────────────────┼────────────────┘
            │                                │
┌───────────┼────────────────────────────────┼────────────────┐
│           ▼                                ▼                 │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              WheelConnectionManager                  │    │
│  │  - State management (StateFlow)                      │    │
│  │  - Decoder orchestration                             │    │
│  │  - Keep-alive timer                                  │    │
│  └─────────────────────────────────────────────────────┘    │
│                            │                                 │
│           ┌────────────────┼────────────────┐               │
│           ▼                ▼                ▼               │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │  Decoders   │  │  BleManager │  │ WheelState  │         │
│  │  (Gotway,   │  │  (expect/   │  │ (Domain)    │         │
│  │   KS, etc.) │  │   actual)   │  │             │         │
│  └─────────────┘  └──────┬──────┘  └─────────────┘         │
│                          │                                   │
│              KMP Core Module (commonMain)                    │
└──────────────────────────┼───────────────────────────────────┘
                           │
         ┌─────────────────┼─────────────────┐
         ▼                                   ▼
┌─────────────────┐                 ┌─────────────────┐
│  androidMain    │                 │    iosMain      │
│  BleManager     │                 │   BleManager    │
│  (blessed-      │                 │  (CoreBluetooth)│
│   android)      │                 │                 │
└─────────────────┘                 └─────────────────┘
```

---

## BLE Service UUIDs Reference

### Common (0000xxxx-0000-1000-8000-00805f9b34fb)
- `ffe0` - Primary service for most wheels
- `ffe1` - Read/Write characteristic (KS, Gotway, Ninebot)
- `ffe4` - InMotion read
- `ffe5` - InMotion write service
- `ffe9` - InMotion write characteristic

### Nordic UART (InMotion V2, Ninebot Z)
- Service: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- Write:   `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
- Read:    `6e400003-b5a3-f393-e0a9-e50e24dcca9e`

---

## Testing Strategy

1. **Unit Tests** (commonTest)
   - Decoder parsing with real packet data
   - UUID matching logic
   - State machine transitions
   - Timer behavior (mocked)

2. **Integration Tests** (androidTest)
   - BLE connection with mock peripheral
   - Full decode → state update flow

3. **Behavioral Equivalence**
   - Compare KMP decoder output with original adapter output
   - Use packet captures from real wheels

---

## Current Status

**Last Updated**: 2026-03-03

**Recent Commits**:
- `fdff864` Rename WheelLog → FreeWheel, separate legacy and new apps
- `28cb4c3` Fix iOS alarm throttling, use KMP sanitizeAddress, add IM2 comparison tests
- `c0c4993` Improve README, migration plan, decoder parity docs, and issue templates
- `cfd10db` Document lifecycle, persistence, entry flow, and test inventory in CLAUDE.md
- `22ab3cc` Finalize ride recording on app close to preserve trip metadata

**Completed**:
- All 8 protocol decoders ported to KMP with ~1,436 unit tests
- iOS app fully functional: dashboard, charts, settings, ride history, background mode
- Android Compose app with WheelService, WheelViewModel, auto-reconnect
- Alarm system (speed, current, temp, battery) on both platforms
- Ride logging with CSV recording and trip persistence
- Telemetry buffer (5-min rolling) and history (24h persistent per-wheel)
- Wheel settings config defined once in KMP, rendered natively on both platforms
- LeaperkimCan decoder for CAN-over-BLE protocol

**Next Steps**:
1. Close remaining decoder parity gaps (see [decoder-parity.md](docs/decoder-parity.md))
2. Firmware update support (where applicable)
3. Wider device testing across wheel manufacturers
4. Integration tests with real wheel connections
