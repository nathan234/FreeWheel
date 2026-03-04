# FreeWheel KMP Migration

## Overview

FreeWheel is an Android/iOS app for electric unicycle telemetry. The codebase is undergoing a Kotlin Multiplatform (KMP) migration to share protocol decoders between platforms.

## Development Policy

All new work targets the KMP `core/` module and the Compose UI in `freewheel/`. The `freewheel/` module (`org.freewheel`) is the Compose-only Android app.

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
├── freewheel/               # FreeWheel Android app (Compose-only, org.freewheel)
├── shared/                  # Android-only library shared between freewheel and wearos
│                            #   (WearPage, SmartDouble, Constants)
├── iosApp/                  # iOS SwiftUI app
│   ├── Scripts/             # build-kmp-framework.sh (Xcode build phase)
│   └── FreeWheel/
│       ├── Bridge/          # Swift-to-KMP wrappers (see iOS Bridge below)
│       └── Views/           # SwiftUI views (11 files)
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

### Command Support Matrix

Which `WheelCommand` types each decoder supports in `buildCommand()`:

| Category | Commands | KS | GW | VT | LK | NB | NZ | IM1 | IM2 |
|---|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| Basic | Beep, Calibrate, PowerOff | Y | Y | Y* | Y* | - | Y | Y | Y |
| Light | SetLight/Mode | Y | Y | Y | Y | - | Y | Y | Y |
| LED | SetLedMode, SetStrobeMode | Y | Y | - | - | - | Y | - | - |
| LED | SetLed, SetLedColor | - | - | - | Y | - | Y | Y | - |
| Light ext | SetDrl, SetTailLight, SetLightBrightness | - | - | - | - | - | Y | - | Y |
| Ride | SetPedalsMode | Y | Y | Y | - | - | - | - | - |
| Ride | SetHandleButton, SetRideMode | - | - | - | Y | - | Y | Y | Y |
| Ride | SetTransportMode, SetGoHomeMode, SetFancierMode | - | - | - | Y* | - | - | - | Y |
| Ride | SetRollAngleMode | - | Y | - | - | - | - | - | - |
| Speed | SetMaxSpeed | - | Y | - | Y | - | - | Y | Y |
| Speed | SetAlarmSpeed/Enabled, SetLimitedMode/Speed | - | - | - | - | - | Y | - | - |
| Speed | SetKingsongAlarms, RequestAlarmSettings | Y | - | - | - | - | - | - | - |
| Pedal | SetPedalTilt, SetPedalSensitivity | - | - | - | Y | - | Y* | Y | Y |
| Audio | SetSpeakerVolume | - | - | - | Y | - | Y | Y | Y |
| Audio | SetBeeperVolume, SetMute | - | Y | - | - | - | - | - | Y |
| Thermal | SetFan, SetFanQuiet | - | - | - | - | - | - | - | Y |
| Other | SetLock, ResetTrip | - | - | Y* | Y* | - | Y | - | Y |
| Other | SetAlarmMode, SetMilesMode, SetCutoutAngle | - | Y | - | - | - | - | - | - |
| BMS | RequestBmsData | Y | - | - | - | - | - | - | - |

Key: Y=supported, -=returns empty list, Y*=partial (VT Beep version-dependent, NZ PedalSensitivity only, VT ResetTrip only, LK Beep/PowerOff only for Basic, LK TransportMode only for that row, LK Lock only for that row). NB has no buildCommand override. LK=LeaperkimCan.

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

## FreeWheel App Entry Flow

Startup sequence for the Compose path:

1. `ComposeActivity.onCreate()` → `setContent { AppNavigation(viewModel) }`
2. `requestBlePermissions()` → checks/requests BLE + location + notification permissions
3. All granted → `bindWheelService()` → starts `WheelService` as foreground service, binds with `BIND_AUTO_CREATE`
4. `WheelService.onCreate()` → creates `BleManager`, `WheelConnectionManager(bleManager, DefaultWheelDecoderFactory(), serviceScope)`, wires BLE callbacks (`onDataReceived` → `connectionManager.onDataReceived()`, `onServicesDiscovered` → `connectionManager.onServicesDiscovered()`)
5. `serviceConnection.onServiceConnected()` → `viewModel.attachService(service, cm, ble)` → pushes decoder config, creates `AutoConnectManager`, starts state/connection collection coroutines
6. `viewModel.startupScan()` → scans for `appConfig.lastMac`, auto-connects when found

A `bluetoothReceiver` in `ComposeActivity` re-binds the service when Bluetooth is toggled on.

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
| StateFlow → @Published | `iosApp/FreeWheel/Bridge/StateFlowObserver.swift` |
| Alarm bridge | `iosApp/FreeWheel/Bridge/AlarmManager.swift` |
| Background mode | `iosApp/FreeWheel/Bridge/BackgroundManager.swift` |
| Location tracking | `iosApp/FreeWheel/Bridge/LocationManager.swift` |
| Ride logging bridge | `iosApp/FreeWheel/Bridge/RideLogger.swift` |
| Ride storage (iOS-only) | `iosApp/FreeWheel/Bridge/RideStore.swift` |
| Telemetry bridge | `iosApp/FreeWheel/Bridge/TelemetryBuffer.swift` |
| Telemetry history | `iosApp/FreeWheel/Bridge/TelemetryHistory.swift` |
| SwiftUI views | `iosApp/FreeWheel/Views/*.swift` (incl. `ViewHelpers.swift` shared unit helpers) |

## iOS Bridge Architecture

`WheelConnectionManagerHelper.kt` (in iosMain) provides Swift-friendly wrappers around the KMP `WheelConnectionManager`. It exposes convenience methods that Swift can call without dealing with Kotlin coroutines directly.

On the Swift side, `WheelManager.swift` is the main orchestrator. It owns the KMP manager instance and coordinates the other Bridge files. `StateFlowObserver` polls KMP StateFlows on a timer and publishes changes to SwiftUI via `@Published` properties.

## App Lifecycle & Shutdown Paths

### Android — 3 shutdown paths

| Scenario | Entry point | Flow |
|---|---|---|
| User disconnect | `VM.disconnect()` | `stopLogging()` → `telemetryHistory.save()` → coroutine `cm.disconnect()` |
| Menu exit | `VM.shutdownService()` | `stopLogging()` → `telemetryHistory.save()` → `WheelService.shutdown()` → `stopSelf()` → `onDestroy()` runs `runBlocking { disconnect() }` |
| ViewModel destroyed | `VM.onCleared()` | `rideLogger.stop()` → `runBlocking(Dispatchers.IO) { tripRepository.insert(...) }` → `telemetryHistory.save()` |

**`runBlocking` pattern**: Used in two places because coroutine scopes are already cancelled:
- `onCleared()` — `viewModelScope` is cancelled, so `runBlocking(Dispatchers.IO)` guarantees the Room INSERT completes before the process exits
- `WheelService.onDestroy()` — `serviceScope` is about to be cancelled, so `runBlocking` guarantees the GATT connection is released

`shutdownService()` deliberately avoids coroutines — calling `finishAffinity()` immediately after would cancel the scope before the work runs.

### iOS — 4 shutdown paths

| Scenario | Entry point | Flow |
|---|---|---|
| User disconnect | `WheelManager.disconnect()` | `stopLogging()` → async `connectionManager.disconnect {}` → state reset in callback |
| App termination | `willTerminateNotification` in `FreeWheelApp.swift` | `stopLogging()` if logging active |
| WheelManager dealloc | `WheelManager.deinit` | `MainActor.assumeIsolated { stopLogging() }` (only if on main thread) |
| Connection lost/disconnected | `handleConnectionStateChange()` | `stopLogging()` → `telemetryHistory.save()` → `telemetryBuffer.clear()` |

iOS also handles background transitions: `onEnterBackground()` saves telemetry history and begins a background task if connected.

## Data Persistence

| Component | Platform | Storage | Role |
|---|---|---|---|
| `TripDatabase` | Android | Room SQLite (`trip_database`, v2) | Singleton factory with migrations (v1→v2 adds distance/consumption columns) |
| `TripDao` | Android | Room DAO | CRUD queries, unique constraint on `fileName`, `onConflict = IGNORE` for inserts |
| `TripDataDbEntry` | Android | Room Entity | Trip record: stats, timing, ElectroClub sync fields (`ecId`, `ecUrl`, etc.) |
| `TripRepository` | Android | Repository | `suspend` wrapper dispatching to `Dispatchers.IO` |
| `RideStore` | iOS | JSON (`metadata.json`) + CSV in `Documents/rides/` | `@MainActor ObservableObject` with `@Published rides` array |
| `RideLogger` | KMP shared | CSV file | Writes samples at 1Hz, produces `RideMetadata` on stop |

### RideMetadata → TripDataDbEntry mapping

When logging stops, `RideMetadata` (KMP) is converted to `TripDataDbEntry` (Room):

| `RideMetadata` field | Conversion | `TripDataDbEntry` field |
|---|---|---|
| `fileName` | direct | `fileName: String` |
| `startTimeMillis` | `/ 1000` → `toInt()` | `start: Int` (unix seconds) |
| `durationSeconds` | `/ 60` → `toInt()` | `duration: Int` (minutes) |
| `maxSpeedKmh` | `toFloat()` | `maxSpeed: Float` |
| `avgSpeedKmh` | `toFloat()` | `avgSpeed: Float` |
| `maxCurrentA` | `toFloat()` | `maxCurrent: Float` |
| `maxPowerW` | `toFloat()` | `maxPower: Float` |
| `maxPwmPercent` | `toFloat()` | `maxPwm: Float` |
| `distanceMeters` | `toInt()` | `distance: Int` |
| `consumptionWh` | `toFloat()` | `consumptionTotal: Float` |
| `consumptionWhPerKm` | `toFloat()` | `consumptionByKm: Float` |

On iOS, `RideMetadata` (KMP) is converted to `RideMetadata` (Swift struct in `RideStore.swift`) and serialized to JSON.

## Logging Architecture

The logging module (`core/src/commonMain/.../logging/`) handles ride CSV recording:

- **CsvFormatter** — Formats `WheelState` into CSV rows matching legacy FreeWheel format. Supports optional GPS columns (6 extra columns inserted after time).
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
| Koin (DI) | 4.1.1 | core |
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

### Test Coverage

**KMP Core Tests** (`core/src/commonTest/`) — ~1,436 tests across 49 test files:

| Area | Key test files | Approx tests |
|---|---|---:|
| Protocol decoders | 8 decoder tests (KS, GW, VT, LK, NB, NZ, IM1, IM2) + AutoDetect, DecoderLifecycle, DecodeLoop, Factory, WheelCommand | ~670 |
| Protocol unpackers | GotwayUnpacker, NinebotUnpacker, InMotionUnpacker, InMotionV2Unpacker | ~33 |
| Decoder comparison | GW, KS, VT, NZ, IM1 comparison tests (verify parity using legacy packet data) | ~132 |
| Service/Connection | WheelConnectionManager (3 files), AutoConnect, KeepAlive, ConnectionState, DemoData | ~175 |
| Domain & Alarms | WheelState, WheelSettingsConfig, AlarmChecker, AlarmType, SmartBms | ~185 |
| Telemetry & Logging | TelemetryBuffer/History/Sample/CsvSerializer, CsvFormatter/Parser, RideLogger/Metadata | ~116 |
| BLE & Detection | BleUuids, WheelTypeDetector, WheelConnectionInfo | ~64 |
| Utilities | ByteUtils, DisplayUtils, StringUtil | ~192 |
| Alarms (vibration) | VibrationPatterns | ~14 |

**FreeWheel App Tests** (`freewheel/src/test/`) — 4 test files:

| Area | Key test files | Approx tests |
|---|---|---:|
| ViewModel | AutoConnect, Finalization | ~16 |
| Alarm | AlarmHandler | ~10 |
| CSV parity | CsvParityTest | ~5 |

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
- [docs/decoder-parity.md](docs/decoder-parity.md) — Per-decoder checklist of legacy parity gaps
- [docs/protocol-quality-assessment.md](docs/protocol-quality-assessment.md) — Opinionated comparison of manufacturer protocol quality
- [docs/reference-protocol.md](docs/reference-protocol.md) — Open TLV protocol spec for VESC-based EUCs
- [docs/reference-implementation-plan.md](docs/reference-implementation-plan.md) — ESP32 hardware phases for reference protocol

## Branch

Active development: `main`
