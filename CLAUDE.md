# WheelLog KMP Migration

## Overview

WheelLog is an Android/iOS app for electric unicycle telemetry. The codebase is undergoing a Kotlin Multiplatform (KMP) migration to share protocol decoders between platforms.

## Development Policy

**Do not modify legacy Android code.** All new work targets the KMP `core/` module and the new Compose UI in `app/`. The legacy Java adapters (`app/.../utils/*Adapter*.java`), `WheelData.java`, and related files are frozen — they will be removed once the KMP migration is complete, not incrementally refactored. The goal is to eventually separate the new Compose app entirely from the old Android app.

## Project Structure

```
Wheellog.Android/
├── core/                    # KMP shared module (commonMain, androidMain, iosMain)
│   └── src/commonMain/.../core/
│       ├── alarm/           # AlarmChecker
│       ├── ble/             # BleUuids, WheelTypeDetector, WheelConnectionInfo
│       ├── domain/          # WheelState, WheelType, SmartBms, AppConstants, WheelSettingsConfig, ControlSpec
│       ├── logging/         # RideLogger
│       ├── protocol/        # All decoders + unpackers (see Decoder Architecture below)
│       ├── service/         # WheelConnectionManager, KeepAliveTimer
│       ├── telemetry/       # TelemetryBuffer
│       └── utils/           # ByteUtils, DisplayUtils, StringUtil, Lock, Logger, EnergyCalculator
├── app/                     # Android app (Jetpack Compose)
├── shared/                  # Android-only library shared between app and wearos
│                            #   (WearPage, SmartDouble, Constants)
├── iosApp/                  # iOS SwiftUI app
│   ├── Scripts/             # build-kmp-framework.sh (Xcode build phase)
│   └── WheelLog/
│       ├── Bridge/          # Swift-to-KMP wrappers (see iOS Bridge below)
│       └── Views/           # SwiftUI views (11 files)
└── wearos/                  # WearOS companion app (does NOT use KMP core;
                             #   receives data from phone via DataClient)
```

### Module Dependencies

```
core  ← standalone KMP (no Android app deps)
app   → core + shared
shared ← standalone Android library (no core dep)
wearos → shared only (NO core)
iosApp → core framework
```

## KMP Decoder Architecture

All decoders are in `core/src/commonMain/.../protocol/` and implement the `WheelDecoder` interface. All are thread-safe (protected by Lock).

Some decoders have a paired `*Unpacker` for low-level frame reassembly; others handle framing internally:

| Decoder | Unpacker | Notes |
|---------|----------|-------|
| KingsongDecoder | — | Framing handled internally |
| GotwayDecoder | GotwayUnpacker | |
| VeteranDecoder | — | Framing handled internally |
| NinebotDecoder | NinebotUnpacker | |
| NinebotZDecoder | — | Framing handled internally |
| InMotionDecoder | InMotionUnpacker | |
| InMotionV2Decoder | InMotionV2Unpacker | |

Supporting files: `WheelDecoder.kt` (interface), `DefaultWheelDecoderFactory.kt` (creates decoder by wheel type), `AutoDetectDecoder.kt` (identifies wheel type from raw packets).

### Command Support Matrix

Which `WheelCommand` types each decoder supports in `buildCommand()`:

| Category | Commands | KS | GW | VT | NB | NZ | IM1 | IM2 |
|---|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| Basic | Beep, Calibrate, PowerOff | Y | Y | Y* | - | Y | Y | Y |
| Light | SetLight/Mode | Y | Y | Y | - | Y | Y | Y |
| LED | SetLedMode, SetStrobeMode | Y | Y | - | - | Y | - | - |
| LED | SetLed, SetLedColor | - | - | - | - | Y | Y | - |
| Light ext | SetDrl, SetTailLight, SetLightBrightness | - | - | - | - | Y | - | Y |
| Ride | SetPedalsMode | Y | Y | Y | - | - | - | - |
| Ride | SetHandleButton, SetRideMode | - | - | - | - | Y | Y | Y |
| Ride | SetTransportMode, SetGoHomeMode, SetFancierMode | - | - | - | - | - | - | Y |
| Ride | SetRollAngleMode | - | Y | - | - | - | - | - |
| Speed | SetMaxSpeed | - | Y | - | - | - | Y | Y |
| Speed | SetAlarmSpeed/Enabled, SetLimitedMode/Speed | - | - | - | - | Y | - | - |
| Speed | SetKingsongAlarms, RequestAlarmSettings | Y | - | - | - | - | - | - |
| Pedal | SetPedalTilt, SetPedalSensitivity | - | - | - | - | Y* | Y | Y |
| Audio | SetSpeakerVolume | - | - | - | - | Y | Y | Y |
| Audio | SetBeeperVolume, SetMute | - | Y | - | - | - | - | Y |
| Thermal | SetFan, SetFanQuiet | - | - | - | - | - | - | Y |
| Other | SetLock, ResetTrip | - | - | Y* | - | Y | - | Y |
| Other | SetAlarmMode, SetMilesMode, SetCutoutAngle | - | Y | - | - | - | - | - |
| BMS | RequestBmsData | Y | - | - | - | - | - | - |

Key: Y=supported, -=returns empty list, Y*=partial (VT Beep version-dependent, NZ PedalSensitivity only, VT ResetTrip only). NB has no buildCommand override.

### DecoderConfig Field Impact

| Field | Decoders | Purpose |
|---|---|---|
| `gotwayNegative` | GW only | Speed/current sign: 0=abs, 1=keep, -1=invert |
| `useRatio` | GW only | Apply 0.875 scaling to speed/distance |
| `gotwayVoltage` | GW only | Battery series (16S-40S) for % calculation |
| `wheelPassword` | IM1 only | InMotion V1 authentication |
| `useMph`, `useFahrenheit` | KS, GW, NZ | Unit conversion in decoded state |
| `useCustomPercents`, `cellVoltageTiltback` | KS, GW, VT, NZ, IM2 | Custom battery % from cell voltage |
| `rotationSpeed`, `rotationVoltage`, `powerFactor` | KS, GW, VT, IM2 | PWM/output calculation |
| `batteryCapacity` | All (via EnergyCalculator) | Remaining range estimation |

### Decoder Data Flow

```
BLE notification → WheelConnectionManager.onDataReceived(bytes)
                     ↓
              decoder.decode(bytes, currentState, config)
                     ↓ (returns DecodedData)
              ┌──────┴──────┐
              │              │
         newState        commands
              ↓              ↓
     _wheelState.emit()  sendCommand() → bleManager.write()
              ↓
        UI observes via StateFlow (Android) / polling (iOS)
```

Lifecycle:
1. **Connect** → `WheelTypeDetector` identifies wheel → `DefaultWheelDecoderFactory.createDecoder()`
2. **Init** → `decoder.getInitCommands()` sent to wheel (identity requests)
3. **Decode loop** → each BLE notification calls `decode()`, updates state, sends response commands
4. **Ready** → `decoder.isReady()` returns true → `ConnectionState.Connected` → keep-alive starts
5. **Keep-alive** → `decoder.getKeepAliveCommand()` sent at `keepAliveIntervalMs` interval
6. **Disconnect** → `decoder.reset()` → timers stopped → state cleared

### Unpacker Contract

Decoders with paired unpackers follow this interaction pattern:

1. **Feeding**: Decoder iterates `data` byte-by-byte → `unpacker.addChar(byte.toInt() and 0xFF)`
2. **Frame ready**: `addChar()` returns `true` only when a complete valid frame is assembled
3. **Retrieve**: `unpacker.getBuffer()` returns the reassembled frame for processing
4. **Null = incomplete**: `decode()` returns `null` when data is incomplete — not an error. Decoders never throw
5. **Reset coupling**: `decoder.reset()` must also call `unpacker.reset()`
6. **Stateful**: Unpackers maintain internal state machines (UNKNOWN → COLLECTING → DONE)

Decoders without unpackers (KS, VT, NZ) handle framing internally.

### WheelState Conventions

- **Internal units**: Speed/voltage/current/temp stored as `Int × 100`. Use computed properties for display (`speedKmh`, `voltageV`, etc.). Distance in meters.
- **Default -1 = unknown**: Settings fields (`pedalsMode`, `lightMode`, `ledMode`, `maxSpeed`, `pedalTilt`, `pedalSensitivity`, `speakerVolume`, `beeperVolume`, `lightBrightness`, `cutoutAngle`, `rollAngle`, `speedAlarms`) use -1 for "not yet read from wheel"
- **Immutable + copy**: Decoders return `currentState.copy(field = newValue)`. SmartBms is mutable internally but exposed via immutable `BmsSnapshot`
- **BMS accumulation**: `bms1`/`bms2` built across multiple frames. Cell voltages arrive separately (GW: frame 0x02/0x03, KS: via RequestBmsData)

## Decoder Mode (Android)

Users can choose which decoder to use:
- **Settings → Application Settings → Decoder Mode**

Options:
- `LEGACY_ONLY` (default): Original Java/Kotlin decoders
- `KMP_ONLY`: New cross-platform decoders

iOS always uses KMP decoders (no legacy option).

## Legacy vs Compose Boundary

The `app/` module contains two independent UI paths:

| | Legacy Path | Compose Path |
|---|---|---|
| Entry point | `MainActivity` | `ComposeActivity` |
| BLE service | `BluetoothService` | `WheelService` |
| Data model | `WheelData` singleton | KMP `WheelState` via `WheelViewModel` |
| Decoder | Legacy adapters or `KmpWheelBridge` | KMP decoders via `WheelConnectionManager` |
| Settings | `AppConfig` (full) | `AppConfig` (SharedPreferences only, no `wd` access) |
| UI | XML layouts + hybrid Compose | Pure Jetpack Compose |

Files in `compose/screens/` belong to the Compose path.
Files in `compose/legacy/` are hybrid screens rendered inside `MainActivity`.

When `use_compose_ui` is true, `WheelData` is never initialized and legacy Koin modules
(`notificationsModule`, `volumeKeyModule`) are not loaded.

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
| KMP bridge | `app/src/main/.../kmp/KmpWheelBridge.kt` |
| Decoder mode enum | `app/src/main/.../kmp/DecoderMode.kt` |
| ViewModel | `app/src/main/.../compose/WheelViewModel.kt` |
| Compose screens | `app/src/main/.../compose/screens/*.kt` |
| Compose components | `app/src/main/.../compose/components/*.kt` (incl. `DangerousActionDialog`, `TimeRangePicker`) |
| Legacy hybrid screens | `app/src/main/.../compose/legacy/*.kt` |
| **iOS App** | |
| Main bridge (orchestrator) | `iosApp/WheelLog/Bridge/WheelManager.swift` |
| StateFlow → @Published | `iosApp/WheelLog/Bridge/StateFlowObserver.swift` |
| Alarm bridge | `iosApp/WheelLog/Bridge/AlarmManager.swift` |
| Background mode | `iosApp/WheelLog/Bridge/BackgroundManager.swift` |
| Location tracking | `iosApp/WheelLog/Bridge/LocationManager.swift` |
| Ride logging bridge | `iosApp/WheelLog/Bridge/RideLogger.swift` |
| Ride storage (iOS-only) | `iosApp/WheelLog/Bridge/RideStore.swift` |
| Telemetry bridge | `iosApp/WheelLog/Bridge/TelemetryBuffer.swift` |
| Telemetry history | `iosApp/WheelLog/Bridge/TelemetryHistory.swift` |
| SwiftUI views | `iosApp/WheelLog/Views/*.swift` (incl. `ViewHelpers.swift` shared unit helpers) |

## iOS Bridge Architecture

`WheelConnectionManagerHelper.kt` (in iosMain) provides Swift-friendly wrappers around the KMP `WheelConnectionManager`. It exposes convenience methods that Swift can call without dealing with Kotlin coroutines directly.

On the Swift side, `WheelManager.swift` is the main orchestrator. It owns the KMP manager instance and coordinates the other Bridge files. `StateFlowObserver` polls KMP StateFlows on a timer and publishes changes to SwiftUI via `@Published` properties.

## Logging Architecture

The logging module (`core/src/commonMain/.../logging/`) handles ride CSV recording:

- **CsvFormatter** — Formats `WheelState` into CSV rows matching legacy WheelLog format. Supports optional GPS columns (6 extra columns inserted after time).
- **CsvParser** — Parses CSV files back into `TelemetrySample` lists. Dynamically reads header to handle both GPS and non-GPS layouts. Downsamples to 3600 points for chart rendering.
- **RideLogger** — Main recording engine. Throttles writes to 1Hz (1000ms minimum between samples). Tracks metadata during recording: max/avg speed, max current/power/PWM, energy consumption.
- **RideMetadata** — Data class with computed ride stats (duration, distance, energy, peaks).
- **FileWriter** — `expect`/`actual` class for platform I/O (BufferedWriter on JVM, NSFileHandle on iOS).
- **FormatUtils** — `expect`/`actual` functions for `formatFixed()` and `formatTimestamp()`.

CSV column order (without GPS): `date,time,speed,voltage,phase_current,current,power,torque,pwm,battery_level,distance,totaldistance,system_temp,temp2,tilt,roll,mode,alert`

## Expect/Actual Pattern

KMP uses `expect`/`actual` declarations for platform-specific implementations. Each `expect` in commonMain has corresponding `actual` implementations in androidMain and iosMain:

| Expect Declaration | Android Actual | iOS Actual | Purpose |
|---|---|---|---|
| `Lock` class | `ReentrantLock` | `NSRecursiveLock` | Thread-safe state access |
| `Logger` object | `android.util.Log` | `NSLog` | Platform logging |
| `FileWriter` class | `BufferedWriter` | `NSFileHandle` | CSV file I/O |
| `formatFixed()` / `formatTimestamp()` | `String.format` / `SimpleDateFormat` | `NSString.stringWithFormat` / `NSDateFormatter` | Number/date formatting |
| `PlatformDateFormatter` object | `SimpleDateFormat` | `NSDateFormatter` | Date display formatting |
| `BleManager` / `BleConnection` | Blessed library | CoreBluetooth | BLE communication |
| `PlatformTelemetryFileIO` class | `File` I/O | `NSFileManager` | Telemetry file storage |
| `currentTimeMillis()` | `System.currentTimeMillis()` | `NSDate().timeIntervalSince1970 * 1000` | Platform clock |

## Major Dependencies

| Dependency | Version | Used in |
|------------|---------|---------|
| Kotlin | 2.2.10 | All modules |
| Kotlinx Coroutines | 1.10.2 | core |
| Koin (DI) | 4.1.1 | core, app |
| Blessed (Android BLE) | 2.4.1 | core androidMain |
| Jetpack Compose | 1.9.2 | app |
| Room (SQLite) | 2.7.2 | app |
| Vico (charts) | 2.1.2 | app |
| Swift Charts | — | iosApp |
| CoreBluetooth | — | core iosMain |

## Build Commands

```bash
# Run KMP tests
./gradlew :core:testDebugUnitTest

# Compile Android app
./gradlew :app:compileDebugKotlin

# Build Android APK
./gradlew :app:assembleDebug

# Build KMP framework for iOS Simulator
./gradlew :core:linkReleaseFrameworkIosSimulatorArm64

# Build KMP framework for physical iPhone
./gradlew :core:linkReleaseFrameworkIosArm64

# Build iOS app (simulator)
xcodebuild -project iosApp/WheelLog.xcodeproj -scheme WheelLog \
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
| `app/src/test/` | JUnit 4, Truth, Mockk, Robolectric | Android app logic (ViewModel, bridge, services) |
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

### Comparison Test Convention

Comparison tests verify KMP decoders produce identical results to the legacy Java/Kotlin decoders:

1. **Cite the legacy test file** in class KDoc
2. **Cite specific test cases** in individual test comments
3. **Use identical packet data** from legacy — do not fabricate new packets

### Test Coverage

| Decoder | Unit Test | Comparison Test |
|---------|:---------:|:---------------:|
| KS | - | Y |
| GW | Y | Y |
| VT | - | Y |
| NB | Y | - |
| NZ | - | Y |
| IM1 | Y | Y |
| IM2 | Y | - |

| Unpacker | Unit Test |
|----------|:---------:|
| GotwayUnpacker | Y |
| NinebotUnpacker | Y |
| InMotionUnpacker | Y |
| InMotionV2Unpacker | Y |

| Service | Unit Test |
|---------|:---------:|
| DemoDataProvider | Y |

### iOS Testing on Simulator

BLE is not available on iOS Simulator. Use the test mode instead:
1. Run app on simulator
2. Tap "Test KMP Decoder" button
3. Verifies decoder with real Kingsong packets (12% battery, 13°C)

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

## Branch

Active development: `main`
