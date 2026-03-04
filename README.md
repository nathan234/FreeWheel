# FreeWheel Multiplatform

Open-source companion app for electric unicycles (EUC), built with **Kotlin Multiplatform** to share protocol decoders, settings configuration, and business logic across Android and iOS.

## Supported Wheels

| Manufacturer | Models |
|---|---|
| KingSong | S18, S22, 16X, 18L/XL, and more |
| Gotway / Begode | MCM5, Nikola, Monster, Sherman, etc. |
| Veteran | Sherman, Abrams, Patton |
| InMotion | V5F, V8, V10, V11, V12, V13 |
| InMotion V2 | V14, Challenger |
| Ninebot | Z6/Z10, S2, One C/P/E+, Mini |

## Features

### Real-Time Telemetry
Live dashboard with speed gauge, battery, temperature, voltage, current, power, PWM, and distance tracking.

### Alarm System
Configurable speed, current, temperature, and low-battery alarms with audio beeps, haptic feedback, and optional wheel horn activation. Alarms fire with a 5-second cooldown and work in background mode via local notifications.

### Ride Logging
Record rides to CSV with optional GPS coordinates. Auto-start logging on connect, browse past rides, and share CSV files directly from the app.

### Real-Time Charts
Telemetry charts with selectable time ranges (5 min, 1 hour, 24 hours) and toggleable series for speed, GPS speed, current, power, temperature, and voltage. Tap and hold to scrub through data points. Browse past rides with full chart replay from CSV logs. Built with Vico on Android and Swift Charts on iOS.

### Wheel Settings
Per-wheel-type control panels defined once in KMP (`WheelSettingsConfig`) and rendered natively on both platforms. Supports lighting, ride modes, speed limits, alarms, audio, thermal controls, and dangerous actions (calibrate, power off, lock) with confirmation dialogs.

### Auto-Reconnect
Automatic reconnection with exponential backoff (2s to 30s) when connection drops, with cancel UI in the connection banner.

### Background Mode
Maintains BLE connection, alarms, and ride logging when the app is backgrounded. Alarm notifications are delivered as local push notifications.

<!-- Screenshots will go here -->
<!-- | ![Dashboard](screenshots/dashboard.png) | ![Chart](screenshots/chart.png) | ![Settings](screenshots/settings.png) | -->
<!-- |---|---|---| -->

## Installation

### Android
Build from source: clone the repo and run `./gradlew :freewheel:assembleDebug`, then install the APK from `freewheel/build/outputs/apk/debug/`.

### iOS
Build from source with Xcode 15+. Open `iosApp/FreeWheel.xcodeproj`, select your device/simulator, and build. Requires the KMP framework to be built first (see [Building](#building) below).

> **Note**: BLE is not available on iOS Simulator. Use a physical device for wheel connectivity, or use the built-in [test mode](#ios-testing-on-simulator) to verify decoder functionality.

## Quick Start

```bash
git clone https://github.com/nathan234/FreeWheel.git
cd FreeWheel
./gradlew :core:testDebugUnitTest         # Run all KMP tests (~1,436 tests)
./gradlew :freewheel:assembleDebug        # Build FreeWheel Android APK
```

For iOS, also build the KMP framework before opening Xcode:
```bash
./gradlew :core:linkReleaseFrameworkIosSimulatorArm64
```

## Architecture

```mermaid
%%{init: {
  "theme": "dark",
  "flowchart": {
    "curve": "basis",
    "nodeSpacing": 40,
    "rankSpacing": 55
  }
}}%%

flowchart LR

%% ---------- Styles ----------
classDef hw fill:#111827,stroke:#60a5fa,stroke-width:1.5px,color:#e6edf3,rx:8,ry:8;
classDef core fill:#0b1b2e,stroke:#34d399,stroke-width:1.5px,color:#e6edf3,rx:8,ry:8;
classDef target fill:#1a1f2e,stroke:#a78bfa,stroke-width:1.5px,color:#e6edf3,rx:8,ry:8;
classDef android fill:#132217,stroke:#22c55e,stroke-width:1.5px,color:#e6edf3,rx:8,ry:8;
classDef ios fill:#1f1a12,stroke:#f59e0b,stroke-width:1.5px,color:#e6edf3,rx:8,ry:8;
classDef wear fill:#1a1a22,stroke:#38bdf8,stroke-width:1.5px,color:#e6edf3,rx:8,ry:8;

%% ---------- Hardware ----------
subgraph HW["EUC Hardware"]
    WHEEL["BLE Radio"]:::hw
end

%% ---------- Shared Core ----------
subgraph CORE["KMP Shared Core · commonMain"]
    direction TB
    WCM["WheelConnectionManager"]:::core
    DEC["Protocol Decoders<br/>Kingsong · Gotway · Veteran<br/>Ninebot · InMotion"]:::core
    STATE["WheelState · ConnectionState<br/>WheelCommand · WheelType"]:::core
    CFG["Settings · AlarmChecker<br/>TelemetryBuffer"]:::core
    UTIL["RideLogger · ByteUtils · BleUuids"]:::core
end

WCM --> DEC --> STATE
WCM --> STATE
STATE --> CFG
STATE --> UTIL

%% ---------- Targets Layer ----------
subgraph TARGETS["KMP Targets · expect/actual"]
    direction TB

    subgraph AND_T["Android Target"]
        BLE_A["BleManager.android<br/>(Blessed)"]:::target
        LOCK_A["Lock · File · Logger"]:::target
    end

    subgraph IOS_T["iOS Target"]
        BLE_I["BleManager.ios<br/>(CoreBluetooth)"]:::target
        LOCK_I["Lock · File · Logger"]:::target
    end
end

%% Hardware connections
WHEEL <-->|BLE packets| BLE_A
WHEEL <-->|BLE packets| BLE_I

%% actual -> core
BLE_A --> WCM
BLE_I --> WCM

BLE_A -.-> LOCK_A
BLE_I -.-> LOCK_I

%% ---------- Android App ----------
subgraph ANDROID["Android App"]
    direction TB
    SVC["WheelService"]:::android
    VM["WheelViewModel"]:::android
    UI_A["Jetpack Compose UI"]:::android
end

STATE -->|"StateFlow"| SVC
SVC --> VM --> UI_A

%% ---------- iOS App ----------
subgraph IOS_APP["iOS App"]
    direction TB
    HELPER["WheelConnectionManagerHelper"]:::ios
    WM["WheelManager.swift<br/>@Published"]:::ios
    UI_I["SwiftUI"]:::ios
end

STATE -->|"StateFlow"| HELPER
HELPER --> WM --> UI_I

%% ---------- WearOS ----------
subgraph WEAR["WearOS"]
    WEARUI["DataClient (from phone)"]:::wear
end

VM -.->|"DataClient"| WEARUI
```

### Directory Structure

```
Wheellog.Android/
├── core/                    # Kotlin Multiplatform shared module
│   └── src/
│       ├── commonMain/      # Protocol decoders, wheel state, settings config, connection manager
│       ├── commonTest/      # Unit tests for all shared code
│       ├── androidMain/     # Android BLE implementation
│       └── iosMain/         # iOS CoreBluetooth implementation + Swift bridge helper
├── freewheel/               # FreeWheel Android app (Compose-only, org.freewheel)
├── shared/                  # Android-only library shared between freewheel and wearos
├── iosApp/                  # iOS SwiftUI app
│   └── FreeWheel/
│       ├── Bridge/          # WheelManager (Swift-to-KMP wrapper)
│       └── Views/           # SwiftUI views
└── wearos/                  # WearOS companion app
```

See [CLAUDE.md](CLAUDE.md) for detailed architecture, decoder documentation, and module dependency graph.

### Key Principle: Define Once in KMP, Render Natively

Logic that both platforms need lives in `core/src/commonMain/`. Platform UI code (Compose, SwiftUI) reads from shared models and calls shared APIs. Examples:

| Shared in KMP | Android renders with | iOS renders with |
|---|---|---|
| `WheelSettingsConfig` (per-wheel controls) | Compose `WheelSettingsScreen` | SwiftUI `WheelSettingsView` |
| `WheelState` (telemetry data) | Compose `DashboardScreen` | SwiftUI `DashboardView` |
| `DisplayUtils` (formatting) | Both platforms call the same functions | |
| `WheelConnectionManager` (BLE commands) | `WheelViewModel.executeWheelCommand()` | `WheelManager.executeCommand()` |

## Building

### Prerequisites

- Android Studio or IntelliJ with Kotlin Multiplatform plugin
- Xcode 15+ (for iOS)
- JDK 17+

### Commands

```bash
# Run all KMP unit tests
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

# Build iOS app (simulator, from command line)
xcodebuild -project iosApp/FreeWheel.xcodeproj -scheme FreeWheel \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build
```

### Verification Checklist

Before submitting a PR, run these in order:

```bash
./gradlew :core:testDebugUnitTest                        # 1. All KMP tests pass
./gradlew :freewheel:testDebugUnitTest                   # 2. All FreeWheel app tests pass
./gradlew :freewheel:compileDebugKotlin                  # 3. FreeWheel compiles
./gradlew :core:linkReleaseFrameworkIosSimulatorArm64    # 4. iOS framework builds
```

For changes touching iOS Swift code, also run:

```bash
xcodebuild -project iosApp/FreeWheel.xcodeproj -scheme FreeWheel \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build
```

## Troubleshooting

| Problem | Solution |
|---|---|
| Gradle daemon OOM / build hangs | Add `org.gradle.jvmargs=-Xmx4g` to `gradle.properties` |
| iOS framework link error | Run `./gradlew :core:linkReleaseFrameworkIosSimulatorArm64` before building in Xcode |
| BLE not available on Simulator | Use a physical iOS device, or use the [test mode](#ios-testing-on-simulator) to verify decoders |
| `JAVA_HOME` not set | Install JDK 17+ and set `JAVA_HOME` to the JDK path |

## Developing Features

### Workflow Overview

Most new features follow this pattern:

1. **Define shared model/logic in KMP** (`core/src/commonMain/`)
2. **Write tests first** (`core/src/commonTest/`)
3. **Run tests to confirm they fail**, then implement, then pass
4. **Build Android UI** (Compose screen + ViewModel wiring + navigation)
5. **Build iOS UI** (SwiftUI view consuming KMP via bridge)
6. **Run the full verification checklist**

### Step-by-Step: Adding a New Shared Feature

#### 1. Create KMP Model

Add your shared types in `core/src/commonMain/kotlin/org/freewheel/core/`:

```
core/src/commonMain/.../yourpackage/
├── YourModel.kt          # Data classes, enums, sealed classes
└── YourConfig.kt          # Configuration object (if needed)
```

#### 2. Write Tests First

Create a corresponding test file in `core/src/commonTest/`:

```
core/src/commonTest/.../yourpackage/
└── YourModelTest.kt
```

Run with `./gradlew :core:testDebugUnitTest`. Tests should **fail** first (classes don't exist yet), then **pass** after implementation.

#### 3. Wire Up Android

- **ViewModel**: Add a method to `WheelViewModel.kt` that calls into the shared KMP code
- **Screen**: Create a new Composable in `freewheel/src/main/.../compose/screens/`
- **Navigation**: Add a route in `AppNavigation.kt` and hide the bottom bar if it's a detail screen

#### 4. Wire Up iOS

- **Bridge**: If the KMP code needs a Swift-callable wrapper, add it to `WheelConnectionManagerHelper.kt` (iOS-side) and `WheelManager.swift`
- **View**: Create a SwiftUI view in `iosApp/FreeWheel/Views/`
- **Xcode**: Add the new `.swift` file to the Xcode project (`FreeWheel.xcodeproj`)

### KMP-to-Swift Conventions

When writing KMP code that iOS will consume:

| Kotlin | Swift |
|---|---|
| `object Foo` | `Foo.shared` |
| `enum class Bar { VALUE_A }` | `Bar.valueA` (lowercase) |
| `fun doThing(count: Int)` | `doThing(count: Int32)` (cast with `Int32(value)`) |
| `data class` properties | Direct access (e.g., `state.displayName`) |
| `sealed class` subclasses | `as?` casts (e.g., `control as? ControlSpec.Toggle`) |
| `List<String>` | `[String]` (bridged via NSArray) |
| `companion object` functions | `ClassName.companion.functionName()` |

### Adding Wheel Commands

The `WheelConnectionManager` exposes ~42 suspend commands. To add a new command:

1. Add the command variant to `WheelCommand` sealed class
2. Implement encoding in the relevant decoder's `buildCommand()`
3. Add a suspend method to `WheelConnectionManager`
4. Add a case to `executeCommand()` dispatch (maps `SettingsCommandId` to method)
5. Add iOS bridge wrapper in `WheelConnectionManagerHelper.kt`
6. Add Swift-side method in `WheelManager.swift`

### iOS Testing on Simulator

BLE is not available on iOS Simulator. Use the test mode instead:
1. Run app on simulator
2. Tap "Test KMP Decoder" button
3. Verifies decoder with real Kingsong packets (12% battery, 13 C)

## Decoder Architecture

Both the Android app (`freewheel/`) and iOS app use the KMP decoders exclusively via `WheelConnectionManager`. See [CLAUDE.md](CLAUDE.md#kmp-decoder-architecture) for detailed decoder documentation.

## Resources

See **[RESOURCES.md](RESOURCES.md)** for a curated collection of EUC protocol documentation, BLE references, open-source hardware projects, VESC resources, and community links. The long-term goal is to move toward open-source EUC hardware and firmware, similar to what VESC did for electric skateboards.

## Documentation

| Document | Purpose |
|----------|---------|
| [CLAUDE.md](CLAUDE.md) | Detailed architecture, decoder docs, key files, testing guide |
| [KMP_MIGRATION_PLAN.md](KMP_MIGRATION_PLAN.md) | Migration phases and current status |
| [RESOURCES.md](RESOURCES.md) | EUC protocol references, open-source hardware, VESC resources |
| [docs/decoder-parity.md](docs/decoder-parity.md) | Per-decoder legacy parity checklist |
| [docs/protocol-quality-assessment.md](docs/protocol-quality-assessment.md) | Manufacturer protocol quality comparison |
| [docs/reference-protocol.md](docs/reference-protocol.md) | Open TLV protocol spec |
| [docs/reference-implementation-plan.md](docs/reference-implementation-plan.md) | ESP32 hardware implementation plan |

## Contributing

Pull requests are welcome on the `main` branch.

1. Run `./gradlew :core:testDebugUnitTest` before submitting
2. Follow the test-first approach for any new KMP shared code
3. Ensure both Android and iOS compile after your changes
4. Add tests for new behavior - every `commonMain` module should have a corresponding `commonTest` file

## Acknowledgments

Originally based on [Wheellog.Android](https://github.com/Wheellog/Wheellog.Android) by the WheelLog team and [palachzzz fork](https://github.com/palachzzz/FreeWheelAndroid).
