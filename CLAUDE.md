# FreeWheel KMP Migration

## Overview

FreeWheel is an Android/iOS app for electric unicycle telemetry. The codebase uses Kotlin Multiplatform (KMP) to share protocol decoders, settings configuration, and business logic between platforms.

## Development Policy

All new work targets the KMP `core/` module and the Compose UI in `freewheel/`. The `freewheel/` module (`org.freewheel`) is the Compose-only Android app.

## Project Structure

```
Wheellog.Android/
├── core/                    # KMP shared module (commonMain, androidMain, iosMain)
│   └── src/commonMain/.../core/
│       ├── alarm/           # AlarmChecker
│       ├── ble/             # BleUuids, WheelTypeDetector, WheelConnectionInfo
│       ├── charger/         # ChargerState, ChargerConnectionManager, HwChargerDecoder, HwChargerProtocol
│       ├── domain/          # WheelState, WheelType, SmartBms, AppConstants, WheelSettingsConfig, ControlSpec
│       ├── logging/         # RideLogger
│       ├── protocol/        # All decoders + unpackers (see Decoder Architecture below)
│       ├── service/         # WheelConnectionManager, KeepAliveTimer
│       ├── telemetry/       # TelemetryBuffer
│       └── utils/           # ByteUtils, DisplayUtils, StringUtil, Lock, Logger, EnergyCalculator
├── freewheel/               # FreeWheel Android app (Compose-only, org.freewheel)
├── shared/                  # Android-only library shared between freewheel and wearos
│                            #   (WearPage, SmartDouble, Constants)
├── iosApp/                  # iOS SwiftUI app
│   ├── Scripts/             # build-kmp-framework.sh (Xcode build phase)
│   └── FreeWheel/
│       ├── Bridge/          # Swift-to-KMP wrappers (see iOS Bridge below)
│       └── Views/           # SwiftUI views
└── wearos/                  # WearOS companion app (does NOT use KMP core;
                             #   receives data from phone via DataClient)
```

### Module Dependencies

```
core      ← standalone KMP (no Android app deps)
freewheel → core + shared  (Compose-only app)
shared    ← standalone Android library (no core dep)
wearos    → shared only (NO core)
iosApp    → core framework
```

## KMP Decoder Architecture

All decoders are in `core/src/commonMain/.../protocol/` and implement the `WheelDecoder` interface. All are thread-safe (protected by Lock).

Some decoders have a paired `*Unpacker` for low-level frame reassembly; others handle framing internally:

| Decoder | Unpacker | Notes |
|---------|----------|-------|
| KingsongDecoder | — | Framing handled internally |
| GotwayDecoder | GotwayUnpacker | Also handles ExtremeBull (same protocol, "JN" firmware prefix) |
| VeteranDecoder | — | Framing handled internally; also handles Nosfet Apex/Aero (model IDs 42/43) |
| LeaperkimCanDecoder | CanUnpacker (internal) | CAN-over-BLE protocol for newer Leaperkim firmware |
| NinebotDecoder | NinebotUnpacker | |
| NinebotZDecoder | — | Framing handled internally |
| InMotionDecoder | InMotionUnpacker | |
| InMotionV2Decoder | InMotionV2Unpacker | |

Supporting files: `WheelDecoder.kt` (interface), `DefaultWheelDecoderFactory.kt` (creates decoder by wheel type), `AutoDetectDecoder.kt` (identifies wheel type from raw packets).

For command support matrix, decoder config fields, data flow diagrams, and unpacker contract details, see [docs/claude-reference.md](docs/claude-reference.md).

### WheelState Conventions

- **Internal units**: Speed/voltage/current/temp stored as `Int × 100`. Use computed properties for display (`speedKmh`, `voltageV`, etc.). Distance in meters.
- **Default -1 = unknown**: Settings fields (`pedalsMode`, `lightMode`, `ledMode`, `maxSpeed`, `pedalTilt`, `pedalSensitivity`, `speakerVolume`, `beeperVolume`, `lightBrightness`, `cutoutAngle`, `rollAngle`, `speedAlarms`) use -1 for "not yet read from wheel"
- **Immutable + copy**: Decoders return `currentState.copy(field = newValue)`. SmartBms is mutable internally but exposed via immutable `BmsSnapshot`
- **BMS accumulation**: `bms1`/`bms2` built across multiple frames. Cell voltages arrive separately (GW: frame 0x02/0x03, KS: via RequestBmsData)

## WheelViewModel

Central orchestrator for the Compose app (`AndroidViewModel`). Owns:

- **Service binding lifecycle** — `attachService()`/`detachService()` wire the `WheelService` to ViewModel state flows
- **Wheel state** — merges real state (`WheelConnectionManager.wheelState`) and demo state via `combine()`
- **BLE scanning** — `startupScan()`, `startScan()`, `connect()`, device discovery
- **Ride logging** — `RideLogger` for CSV recording, `TripRepository` for Room DB persistence
- **Telemetry** — `TelemetryBuffer` (5-min rolling), `TelemetryHistory` (24h persistent per-wheel), `TelemetryFileIO`
- **Alarm monitoring** — `AlarmChecker` + `AlarmHandler` with vibration/sound alerts
- **Wheel profiles** — `WheelProfileStore` (SharedPreferences-backed, per-MAC settings)
- **Decoder config propagation** — pref changes → `connectionManager.updateConfig()`
- **Command execution** — beep, light toggle, pedals mode, generic `WheelCommand`
- **Auto-connect / reconnect** — `AutoConnectManager` created on service attach, reconnects on `ConnectionLost`
- **GPS location tracking** — coordinates forwarded to `RideLogger` for GPS-enabled CSV columns

## Key Files

| Purpose | Path |
|---------|------|
| **KMP Core** | |
| Protocol decoders | `core/src/commonMain/.../protocol/*.kt` |
| Connection manager | `core/src/commonMain/.../service/WheelConnectionManager.kt` |
| Wheel state & types | `core/src/commonMain/.../domain/{WheelState,WheelType,SmartBms,SpeedDisplayMode}.kt` |
| Settings config | `core/src/commonMain/.../domain/{WheelSettingsConfig,ControlSpec}.kt` |
| Alarm logic | `core/src/commonMain/.../alarm/AlarmChecker.kt` |
| BLE UUIDs & detection | `core/src/commonMain/.../ble/{BleUuids,WheelTypeDetector}.kt` |
| Utils (formatting, platform) | `core/src/commonMain/.../utils/{ByteUtils,DisplayUtils,StringUtil,Lock,Logger}.kt` |
| Telemetry buffer | `core/src/commonMain/.../telemetry/TelemetryBuffer.kt` |
| Telemetry file I/O | `core/src/commonMain/.../telemetry/TelemetryFileIO.kt` |
| Charger protocol | `core/src/commonMain/.../charger/{ChargerState,ChargerConnectionManager,HwChargerDecoder,HwChargerProtocol}.kt` |
| Preference keys & defaults | `core/src/commonMain/.../domain/{PreferenceKeys,PreferenceDefaults}.kt` |
| Ride logging (CSV) | `core/src/commonMain/.../logging/{CsvFormatter,CsvParser,RideLogger,RideMetadata}.kt` |
| Demo data provider | `core/src/commonMain/.../service/DemoDataProvider.kt` |
| **Platform Implementations** | |
| Android BLE | `core/src/androidMain/.../service/BleManager.android.kt` |
| iOS BLE | `core/src/iosMain/.../service/BleManager.ios.kt` |
| iOS Swift bridge helper | `core/src/iosMain/.../service/WheelConnectionManagerHelper.kt` |
| Lock (Android/iOS) | `core/src/{androidMain,iosMain}/.../utils/Lock.{android,ios}.kt` |
| Logger (Android/iOS) | `core/src/{androidMain,iosMain}/.../utils/Logger.{android,ios}.kt` |
| **Android App** | |
| Compose entry point | `freewheel/src/main/.../compose/ComposeActivity.kt` |
| Foreground BLE service | `freewheel/src/main/.../compose/WheelService.kt` |
| ViewModel | `freewheel/src/main/.../compose/WheelViewModel.kt` |
| Wheel profiles | `freewheel/src/main/.../compose/WheelProfileStore.kt` |
| Trip database | `freewheel/src/main/.../data/{TripDatabase,TripDao,TripDataDbEntry}.kt` |
| Trip repository | `freewheel/src/main/.../data/TripRepository.kt` |
| Compose screens | `freewheel/src/main/.../compose/screens/*.kt` |
| Compose components | `freewheel/src/main/.../compose/components/*.kt` (incl. `DangerousActionDialog`, `TimeRangePicker`) |
| **iOS App** | |
| Main bridge (orchestrator) | `iosApp/FreeWheel/Bridge/WheelManager.swift` |
| Alarm bridge | `iosApp/FreeWheel/Bridge/AlarmManager.swift` |
| Background mode | `iosApp/FreeWheel/Bridge/BackgroundManager.swift` |
| Location tracking | `iosApp/FreeWheel/Bridge/LocationManager.swift` |
| Ride logging bridge | `iosApp/FreeWheel/Bridge/RideLogger.swift` |
| Ride storage (iOS-only) | `iosApp/FreeWheel/Bridge/RideStore.swift` |
| Telemetry bridge | `iosApp/FreeWheel/Bridge/TelemetryBuffer.swift` |
| Telemetry history | `iosApp/FreeWheel/Bridge/TelemetryHistory.swift` |
| Charger bridge | `iosApp/FreeWheel/Bridge/ChargerManager.swift` |
| SwiftUI views | `iosApp/FreeWheel/Views/*.swift` (incl. `ViewHelpers.swift`, `ChargerView.swift`) |

## iOS Bridge Architecture

`WheelConnectionManagerHelper.kt` (in iosMain) provides Swift-friendly wrappers around the KMP `WheelConnectionManager`. It exposes convenience methods that Swift can call without dealing with Kotlin coroutines directly.

On the Swift side, `WheelManager.swift` is the main orchestrator. It owns the KMP manager instance and coordinates the other Bridge files. StateFlow collection is handled reactively via `WheelConnectionManagerHelper.observeXxx()` methods (Kotlin coroutines collecting flows and invoking Swift callbacks), not polling.

For app lifecycle, shutdown paths, data persistence, and logging architecture details, see [docs/claude-reference.md](docs/claude-reference.md).

## Major Dependencies

| Dependency | Version | Used in |
|------------|---------|---------|
| Kotlin | 2.2.10 | All modules |
| Kotlinx Coroutines | 1.10.2 | core |
| Blessed (Android BLE) | 2.4.1 | core androidMain |
| Jetpack Compose | 1.9.2 | freewheel |
| Room (SQLite) | 2.7.2 | freewheel |
| Vico (charts) | 2.1.2 | freewheel |
| Swift Charts | — | iosApp |
| CoreBluetooth | — | core iosMain |

## Build Commands

```bash
# Run KMP tests
./gradlew :core:testDebugUnitTest

# Run FreeWheel app tests
./gradlew :freewheel:testDebugUnitTest

# Compile FreeWheel app
./gradlew :freewheel:compileDebugKotlin

# Build FreeWheel APK
./gradlew :freewheel:assembleDebug

# Build KMP framework for iOS Simulator
./gradlew :core:linkReleaseFrameworkIosSimulatorArm64

# Build KMP framework for physical iPhone
./gradlew :core:linkReleaseFrameworkIosArm64

# Build iOS app (simulator)
xcodebuild -project iosApp/FreeWheel.xcodeproj -scheme FreeWheel \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build
```

The Xcode project has a build phase that runs `iosApp/Scripts/build-kmp-framework.sh` automatically, so building from Xcode handles the KMP framework step. The manual Gradle commands above are for CI or command-line builds.

## KMP-to-Swift Conventions

- KMP `object` singletons are accessed as `.shared` in Swift
  (e.g., `DisplayUtils.shared.formatSpeed(...)`, `ByteUtils.shared.kmToMiles(...)`)
- KMP `enum` values are lowercase in Swift (e.g., `WheelType.kingsong`)
- KMP `Int` parameters become `Int32` in Swift; cast with `Int32(value)`
- KMP `WheelState` properties are accessed directly (e.g., `kmpState.displayName`)

## Testing

### Philosophy

Follow a **test-first** approach for all KMP shared code:

1. **Write tests before implementation** — define expected behavior in `core/src/commonTest/` first
2. **Run tests to confirm they fail** — validates the test is meaningful
3. **Implement the code** — make tests pass
4. **Verify all tests pass** before moving to platform integration

Every new KMP module (`core/src/commonMain/`) should have a corresponding test file (`core/src/commonTest/`).

### Test Locations

| Location | Framework | What it covers |
|----------|-----------|----------------|
| `core/src/commonTest/` | kotlin-test, coroutines-test | KMP shared code (decoders, state, utils) |
| `freewheel/src/test/` | JUnit 4, Truth, Mockk, Robolectric | FreeWheel app logic (ViewModel, alarms, CSV parity) |
| `shared/src/test/` | JUnit 4 | Shared Android utilities |

Platform-specific UI (Compose, SwiftUI) is verified via build compilation + manual testing.

### Test Patterns

Decoder tests follow a consistent pattern:

1. **Instantiate decoder directly**: `val decoder = GotwayDecoder()`
2. **Create default state and config**: `val state = WheelState()`, `val config = DecoderConfig()`
3. **Build test frames**: Use hex strings or frame builders to create raw byte arrays
4. **Feed to decoder**: `val result = decoder.decode(frame, state, config)`
5. **Assert on result**: Check `result.newState.speed`, `result.commands`, etc.

Shared test utilities are in `core/src/commonTest/.../protocol/TestUtils.kt`:
- `"55AA...".hexToByteArray()` — convert hex string to bytes
- `shortToBytesBE(value)` — encode Int as 2-byte big-endian array

Frame builders (decoder-specific, in respective test files):
- `buildLiveDataFrame()` / `buildGotwayLiveDataFrame()` — Gotway frame 0x00
- `buildIM2Frame(flags, command, data)` — InMotion V2 message with escaping + checksum
- `buildSettingsFrame(payload)` — InMotion V2 settings frame

For detailed test coverage breakdown, see [docs/claude-reference.md](docs/claude-reference.md).

## Common Pitfalls

Protocol decoder gotchas discovered during development:

- **Gotway FRAME_00 bytes 16-17**: Byte 16 is unknown/unused. Byte 17 contains beeper volume (0-9).
  These bytes were previously undocumented — discovered via BLE PacketLogger capture (the ATT summary
  truncates notifications to 16 bytes; full data is in the L2CAP/ACL hex dump).
- **GotwayDecoder retry counter**: `infoAttempt` is 0-indexed and compared with `<`. Fallback triggers
  when counter **reaches** `MAX_INFO_ATTEMPTS` (50), not after 50 iterations — loop needs 51 iterations
  to see the fallback.
- **InMotionV2 MAIN_INFO sub-types**: Command `0x02` (MAIN_INFO) is used for car type, serial, AND
  version requests. The sub-type is in `data[0]`: `0x01`=car type, `0x02`=serial, `0x06`=versions.
  Don't confuse the command byte with the sub-type.
- **InMotionV2 response bit**: Response frames have command byte OR'd with `0x80`
  (e.g., SETTINGS `0x20` → response `0xA0`). Mask with `0x7F` to get the base command.
- **NinebotZ state ordering**: The 14 connection states must be traversed in order — skipping states
  causes the wheel to stop responding. BMS states are conditional on `bmsReadingMode`.
- **Kingsong 0xA4 acknowledgment**: When a `0xA4` frame is received, the decoder must respond with a
  `0x98` (alarm settings request) command. This is done via the `commands` return list from `decode()`.
- **LeaperkimCan 0xA5 escape bytes**: The CAN-over-BLE protocol uses `0xA5` as escape marker — doubled
  `0xA5 0xA5` in transit represents a single `0xA5` data byte. The internal CanUnpacker handles
  deduplication. Frame building (`buildCanFrame`) must also escape payload bytes.
- **LeaperkimCan init sequence**: 3-step handshake (PASSWORD → INIT_COMM → INIT_STATUS) must complete
  before polling begins. Each step waits for a response before sending the next command.
- **LeaperkimCan uses Gotway BLE UUIDs**: Despite being a completely different protocol, Leaperkim CAN
  reuses the same BLE service/characteristic UUIDs as Gotway. Detection relies on device name
  ("LEAPERKIM"/"LPKIM") and must be checked *before* Veteran/Gotway name matching.

## Related Documentation

- [README.md](README.md) — Project overview, build commands, architecture diagram, contributing guide
- [KMP_MIGRATION_PLAN.md](KMP_MIGRATION_PLAN.md) — Migration phases, BLE implementation status, current progress
- [RESOURCES.md](RESOURCES.md) — EUC protocol references, open-source hardware, VESC resources
- [docs/claude-reference.md](docs/claude-reference.md) — Deep reference: command matrix, data flow, lifecycle, persistence, expect/actual, test coverage
- [docs/decoder-parity.md](docs/decoder-parity.md) — Per-decoder checklist of legacy parity gaps
- [docs/protocol-quality-assessment.md](docs/protocol-quality-assessment.md) — Opinionated comparison of manufacturer protocol quality
- [docs/reference-protocol.md](docs/reference-protocol.md) — Open TLV protocol spec for VESC-based EUCs
- [docs/reference-implementation-plan.md](docs/reference-implementation-plan.md) — ESP32 hardware phases for reference protocol

## Branch

Active development: `main`
