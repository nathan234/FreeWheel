# FreeWheel — Deep Reference

Detailed reference material for specific subsystems. See [CLAUDE.md](../CLAUDE.md) for the main project guide.

## Command Support Matrix

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

## DecoderConfig Field Impact

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

## Decoder Data Flow

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
        UI observes via StateFlow (Android) / callback-based flow collection (iOS)
```

Lifecycle:
1. **Connect** → `WheelTypeDetector` identifies wheel → `DefaultWheelDecoderFactory.createDecoder()`
2. **Init** → `decoder.getInitCommands()` sent to wheel (identity requests)
3. **Decode loop** → each BLE notification calls `decode()`, updates state, sends response commands
4. **Ready** → `decoder.isReady()` returns true → `ConnectionState.Connected` → keep-alive starts
5. **Keep-alive** → `decoder.getKeepAliveCommand()` sent at `keepAliveIntervalMs` interval
6. **Disconnect** → `decoder.reset()` → timers stopped → state cleared

## Unpacker Contract

Decoders with paired unpackers follow this interaction pattern:

1. **Feeding**: Decoder iterates `data` byte-by-byte → `unpacker.addChar(byte.toInt() and 0xFF)`
2. **Frame ready**: `addChar()` returns `true` only when a complete valid frame is assembled
3. **Retrieve**: `unpacker.getBuffer()` returns the reassembled frame for processing
4. **Null = incomplete**: `decode()` returns `null` when data is incomplete — not an error. Decoders never throw
5. **Reset coupling**: `decoder.reset()` must also call `unpacker.reset()`
6. **Stateful**: Unpackers maintain internal state machines (UNKNOWN → COLLECTING → DONE)

Decoders without unpackers (KS, VT, NZ) handle framing internally.

## FreeWheel App Entry Flow

Startup sequence for the Compose path:

1. `ComposeActivity.onCreate()` → `setContent { AppNavigation(viewModel) }`
2. `requestBlePermissions()` → checks/requests BLE + location + notification permissions
3. All granted → `bindWheelService()` → starts `WheelService` as foreground service, binds with `BIND_AUTO_CREATE`
4. `WheelService.onCreate()` → creates `BleManager`, `WheelConnectionManager(bleManager, DefaultWheelDecoderFactory(), serviceScope)`, wires BLE callbacks (`onDataReceived` → `connectionManager.onDataReceived()`, `onServicesDiscovered` → `connectionManager.onServicesDiscovered()`)
5. `serviceConnection.onServiceConnected()` → `viewModel.attachService(service, cm, ble)` → pushes decoder config, creates `AutoConnectManager`, starts state/connection collection coroutines
6. `viewModel.startupScan()` → scans for `appConfig.lastMac`, auto-connects when found

A `bluetoothReceiver` in `ComposeActivity` re-binds the service when Bluetooth is toggled on.

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

## Test Coverage

**KMP Core Tests** (`core/src/commonTest/`):

Run with `./gradlew :core:testDebugUnitTest`. Covers protocol decoders, unpackers, comparison tests (legacy parity), service/connection, domain & alarms, telemetry & logging, BLE & detection, and utilities.

**FreeWheel App Tests** (`freewheel/src/test/`):

Run with `./gradlew :freewheel:testDebugUnitTest`. Covers ViewModel (AutoConnect, Finalization), AlarmHandler, CsvParity, NavigationConfigUI, and DashboardContent.

### iOS Testing on Simulator

BLE is not available on iOS Simulator. Use the test mode instead:
1. Run app on simulator
2. Tap "Test KMP Decoder" button
3. Verifies decoder with real Kingsong packets (12% battery, 13°C)
