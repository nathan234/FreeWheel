# Capability Discovery and Diagnostic Data Collection System

## 1. Problem Statement

FreeWheel currently shows the same settings UI for every wheel of a given `WheelType`, regardless of model or firmware version. A Veteran Sherman (mVer 1) sees the same controls as a Veteran Lynx (mVer 5), even though the Sherman does not support extended commands (`ver < 3` guards in `VeteranDecoder.buildCommand()`). An InMotion V11 with old firmware (`protoVer < 2`) sees the same settings as a V14. Users have no way to collect and share raw BLE traffic for wheels the app does not yet support.

## 2. Design Overview

Two complementary systems:

**System A: Capability-Driven Settings** — Each decoder declares which `SettingsCommandId`s it supports at each firmware level via a static capability map. A single `CapabilitySet` is derived from this map at runtime once the firmware level is known. `WheelSettingsConfig` uses a single superset section list per `WheelType`, filtered entirely by capabilities — eliminating model-specific section functions.

**System B: Diagnostic Data Collection** — A `DiagnosticReport` that bundles BLE packet history with structured metadata (capabilities, connection log, unknown frames) into a shareable JSON package. Captured reports become test fixtures.

---

## 3. System A: Capability-Driven Settings

### 3.1 Data Structures

**New file: `core/src/commonMain/.../domain/WheelCapabilities.kt`**

```kotlin
/**
 * Immutable set of capabilities for the connected wheel.
 * Constructed by the decoder from its capability map once firmware level is known.
 *
 * Capabilities are monotonically expanding within a session — once a command is
 * reported as supported, it is never removed. This prevents UI controls from
 * disappearing while the user is interacting with them.
 *
 * [isResolved] means the minimum identification needed to determine command
 * support has been received (e.g., mVer for Veteran, model + protoVer for Lorin).
 * It does NOT mean all metadata (serial, BMS, etc.) is available.
 */
data class CapabilitySet(
    /** Commands this wheel supports. */
    val supportedCommands: Set<SettingsCommandId> = emptySet(),

    /** Per-command control replacements (e.g., different min/max for a Slider). */
    val controlOverrides: Map<SettingsCommandId, ControlSpec> = emptyMap(),

    /** Human-readable model name as detected by the decoder. */
    val detectedModel: String = "",

    /** Firmware version string. */
    val firmwareVersion: String = "",

    /** Decoder-specific firmware level (e.g., mVer for Veteran, protoVer for Lorin). */
    val firmwareLevel: Int = 0,

    /** Whether capability resolution is complete. See class doc. */
    val isResolved: Boolean = false
) {
    /** True if [commandId] is supported by this wheel. */
    fun supports(commandId: SettingsCommandId): Boolean =
        commandId in supportedCommands

    /** True if this wheel supports any extended (firmware-gated) settings. */
    fun supportsExtendedSettings(): Boolean =
        supportedCommands.any { it.isExtended }
}
```

`controlOverrides` maps a `SettingsCommandId` to a complete replacement `ControlSpec`. This avoids the complexity of partial field merging — the decoder provides a fully-formed control when the default doesn't fit (e.g., a Slider with different min/max for a specific model). Only controls that differ from the superset defaults need entries here. This is cleaner than a generic `ParameterOverride` because:

- No ambiguity about which fields apply to which control types.
- The decoder can change the control *type* if needed (e.g., a Picker with fewer options).
- The override is a complete, type-safe `ControlSpec` — no nullable fields to reason about.

### 3.2 Decoder Capability Map

Rather than maintaining parallel logic in `getCapabilities()` and `buildCommand()`, each decoder declares its capabilities via a static map from `SettingsCommandId` to the minimum firmware level required. Both capability resolution and command gating derive from this single source of truth.

**New file: `core/src/commonMain/.../domain/DecoderCapabilityMap.kt`**

```kotlin
/**
 * Maps a SettingsCommandId to the minimum firmware level that supports it.
 * Level 0 means "always supported" (no firmware gate).
 */
typealias CapabilityMap = Map<SettingsCommandId, Int>
```

**Example — VeteranDecoder:**

```kotlin
companion object {
    /** Single source of truth for Veteran command support by mVer. */
    val CAPABILITY_MAP: CapabilityMap = mapOf(
        // mVer 0+ (all models, ASCII protocol)
        SettingsCommandId.LIGHT_MODE to 0,
        SettingsCommandId.PEDALS_MODE to 0,
        SettingsCommandId.LOCK to 0,

        // mVer 3+ (LkAp/LdAp binary protocol)
        SettingsCommandId.ALARM_SPEED_1 to 3,
        SettingsCommandId.PEDAL_TILT to 3,
        SettingsCommandId.TRANSPORT_MODE to 3,
        SettingsCommandId.HIGH_SPEED_MODE to 3,
        SettingsCommandId.LOW_VOLTAGE_MODE to 3,
        SettingsCommandId.KEY_TONE to 3,
        SettingsCommandId.SCREEN_BACKLIGHT to 3,
        SettingsCommandId.STOP_SPEED to 3,
        SettingsCommandId.VETERAN_PWM_LIMIT to 3,
        SettingsCommandId.VOLTAGE_CORRECTION to 3,
        SettingsCommandId.MAX_CHARGE_VOLTAGE to 3,
        SettingsCommandId.LATERAL_CUTOFF_ANGLE to 3,
        SettingsCommandId.CALIBRATE to 3,
        SettingsCommandId.POWER_OFF to 3,
        SettingsCommandId.RESET_TRIP to 3,
    )
}
```

Both `getCapabilities()` and `buildCommand()` use this map:

```kotlin
override fun getCapabilities(): CapabilitySet {
    val ver = stateLock.withLock { mVer }
    if (ver == 0) return CapabilitySet() // Not yet resolved

    val supported = CAPABILITY_MAP.filterValues { minVer -> ver >= minVer }.keys
    return CapabilitySet(
        supportedCommands = supported,
        detectedModel = getModelName(),
        firmwareVersion = version,
        firmwareLevel = ver,
        isResolved = true
    )
}

override fun buildCommand(command: WheelCommand): List<WheelCommand> {
    val ver = stateLock.withLock { mVer }
    val commandId = command.settingsCommandId ?: return emptyList()

    // Single gate: check the capability map
    val minVer = CAPABILITY_MAP[commandId] ?: return emptyList()
    if (ver < minVer) return emptyList()

    // Build the actual bytes (no version checks needed here)
    return when (command) {
        is WheelCommand.Beep -> { /* ... */ }
        // ...
    }
}
```

This eliminates the duplicated `if (ver < 3) return emptyList()` guards scattered throughout `buildCommand()`. The version check happens once at the top; the `when` branches only handle byte construction.

**For decoders with model-based gating** (LorinDecoder), the map key can be a `Pair<SettingsCommandId, Model>` or the decoder can maintain multiple maps merged at resolution time:

```kotlin
companion object {
    val BASE_COMMANDS: CapabilityMap = mapOf(
        SettingsCommandId.LIGHT_MODE to 0,
        SettingsCommandId.MAX_SPEED to 0,
        // ...
    )

    val V11_COMMANDS: CapabilityMap = mapOf(
        SettingsCommandId.FAN to 0,
        SettingsCommandId.FAN_QUIET to 0,
    )

    val V13_V14_COMMANDS: CapabilityMap = mapOf(
        SettingsCommandId.BERM_ANGLE_MODE to 0,
        SettingsCommandId.TURNING_SENSITIVITY to 0,
        // ...
    )

    val P6_COMMANDS: CapabilityMap = mapOf(
        SettingsCommandId.SCREEN_AUTO_OFF to 0,
        SettingsCommandId.LOGO_LIGHT_BRIGHTNESS to 0,
        // ...
    )
}

override fun getCapabilities(): CapabilitySet {
    val m = stateLock.withLock { model }
    if (m == Model.UNKNOWN) return CapabilitySet()

    val commands = buildMap {
        putAll(BASE_COMMANDS)
        when {
            isV11Family -> putAll(V11_COMMANDS)
            isV13Family || isV14Family -> putAll(V13_V14_COMMANDS)
            m == Model.P6 -> putAll(P6_COMMANDS)
        }
    }
    val supported = commands.filterValues { minVer -> protoVer >= minVer }.keys
    return CapabilitySet(
        supportedCommands = supported,
        detectedModel = m.name,
        firmwareLevel = protoVer,
        isResolved = true
    )
}
```

**For decoders with static capabilities** (Gotway, Kingsong, NinebotZ), the map is declared once and `getCapabilities()` returns a fixed `CapabilitySet` with all entries at level 0:

```kotlin
// GotwayDecoder
override fun getCapabilities(): CapabilitySet = CapabilitySet(
    supportedCommands = CAPABILITY_MAP.keys,
    isResolved = true
)
```

### 3.3 WheelSettingsConfig Migration

The current `WheelSettingsConfig.sections()` maintains separate functions per wheel type and model (e.g., `lorinSections()`, `inmotionP6Sections()`). This approach doesn't scale — every new model variant requires a new section function, and model-specific controls end up duplicated across functions.

**New approach**: One superset section list per `WheelType`. The superset contains every control that *any* model of that type might support. Capabilities are the sole filter.

```kotlin
object WheelSettingsConfig {

    fun sections(
        wheelType: WheelType,
        capabilities: CapabilitySet? = null
    ): List<SettingsSection> {
        val superset = when (wheelType) {
            WheelType.KINGSONG -> kingsongSections()
            WheelType.GOTWAY, WheelType.GOTWAY_VIRTUAL -> gotwaySections()
            WheelType.VETERAN -> veteranSections()
            WheelType.LEAPERKIM -> leaperkimSections()
            WheelType.NINEBOT_Z -> ninebotZSections()
            WheelType.INMOTION -> inmotionSections()
            WheelType.LORIN -> lorinSupersetSections()
            else -> emptyList()
        }
        if (capabilities == null || !capabilities.isResolved) return superset

        return superset.mapNotNull { section ->
            val filtered = section.controls
                .filter { capabilities.supports(it.commandId) }
                .map { capabilities.controlOverrides[it.commandId] ?: it }
            if (filtered.isEmpty()) null else SettingsSection(section.title, filtered)
        }
    }
}
```

The InMotion V2 superset merges all controls from V11, V12, V13/V14, and P6 into one list. Controls that only apply to specific models (e.g., `FAN_QUIET` for V11, `SCREEN_AUTO_OFF` for P6) are all present in the superset but get filtered out by capabilities. Section titles are shared where possible; model-unique sections (e.g., P6's Logo Light) are included and simply disappear when empty after filtering.

The existing `sections(wheelType, model)` overload is removed. The `model` parameter was only used for InMotion P6 branching, which is now handled by capabilities.

**Migration path**: The old signature remains during Phase 2 as a bridge that ignores `model` and returns the unfiltered superset. Once both platforms pass capabilities, it is deleted.

### 3.4 State Integration

- Add `capabilities: CapabilitySet` field to `WcmState` (default: unresolved empty set)
- Add a derived `StateFlow<CapabilitySet>` on `WheelConnectionManager`, mapped from the WcmState flow
- The reducer updates capabilities whenever `decode()` returns successfully and `decoder.getCapabilities()` has changed
- **Monotonic expansion**: The reducer merges the new capability set with the previous one via set union — capabilities are never removed within a session. This prevents a race between identification frames causing UI controls to flicker.
- Both platforms observe the capabilities flow

### 3.5 UI Changes

**Android (`WheelSettingsScreen.kt`)**:
```kotlin
val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
val sections = remember(wheelState.wheelType, capabilities) {
    WheelSettingsConfig.sections(wheelState.wheelType, capabilities)
}
```

When `capabilities.isResolved == false`, show a subtle "Detecting wheel capabilities..." indicator.

**iOS (`WheelSettingsView.swift`)**: Same pattern via `WheelConnectionManagerHelper.observeCapabilities()`.

---

## 4. System B: Diagnostic Data Collection

### 4.1 Existing Infrastructure

The app already has:
- `BleCaptureLogger` — writes CSV with hex packets, timestamps, direction, and metadata header
- `WheelConnectionManager.captureCallback` — hook for every BLE packet (RX and TX)
- Android UI for start/stop capture
- iOS bridge in `WheelManager.swift`

The diagnostic system extends this rather than replacing it.

### 4.2 Two-Tier Packet History

Diagnostic value is highest for init-phase packets (handshake, model identification, settings readback) which arrive at connection time. A single ring buffer large enough to hold an entire session wastes memory; a small one evicts the most valuable packets first. Solution: two tiers.

**New file: `core/.../diagnostic/BlePacketHistory.kt`**

```kotlin
/**
 * Stores BLE packets as hex strings (not ByteArray) for correct equality
 * semantics and debugger-friendly display.
 */
data class CapturedPacket(
    val timestampMs: Long,
    val direction: String,  // "RX" or "TX"
    val hex: String,
    val size: Int,
    val frameType: String? = null  // Decoder-assigned label, e.g., "FRAME_00", "SETTINGS"
)

/**
 * Two-tier always-on packet history.
 *
 * Tier 1 (init snapshot): The first [initSize] packets of each connection are preserved
 * for the session lifetime. These capture the handshake, identification, and initial
 * settings readback — the most diagnostically valuable packets.
 *
 * Tier 2 (rolling buffer): A ring buffer of the most recent [rollingSize] packets.
 * Older packets are evicted as new ones arrive.
 *
 * Both tiers are in-memory with zero disk I/O. The existing [BleCaptureLogger]
 * remains available for explicit full-session file capture.
 */
class BlePacketHistory(
    private val initSize: Int = 500,
    private val rollingSize: Int = 2000
) {
    private val lock = Lock()
    private val initBuffer = ArrayList<CapturedPacket>(initSize)
    private val rollingBuffer = ArrayDeque<CapturedPacket>(rollingSize)
    private var initFull = false

    fun add(packet: CapturedPacket) {
        lock.withLock {
            if (!initFull) {
                initBuffer.add(packet)
                if (initBuffer.size >= initSize) initFull = true
            }
            rollingBuffer.addLast(packet)
            if (rollingBuffer.size > rollingSize) rollingBuffer.removeFirst()
        }
    }

    /** Returns init packets + rolling buffer, deduplicated by timestamp. */
    fun snapshot(): List<CapturedPacket> {
        lock.withLock {
            // Init packets come first, then rolling (skipping any overlap)
            val initEnd = initBuffer.lastOrNull()?.timestampMs ?: 0
            val rollingNew = rollingBuffer.filter { it.timestampMs > initEnd }
            return initBuffer + rollingNew
        }
    }

    fun clear() {
        lock.withLock {
            initBuffer.clear()
            rollingBuffer.clear()
            initFull = false
        }
    }
}
```

This gives ~500 packets of init coverage (handshake through settings readback, typically 10-30 seconds) plus a rolling window of ~2000 recent packets (~20-100 seconds depending on wheel). Total memory footprint stays bounded.

**Ownership**: `BlePacketHistory` is owned by `WheelConnectionManager` directly — not routed through `captureCallback`. The WCM feeds the history in `executeEffects()` as an internal side-effect (zero-cost: just a lock + deque append). The existing `captureCallback` remains an independent, external, opt-in hook for `BleCaptureLogger`. The two mechanisms are fully decoupled.

### 4.3 Connection Event Log

**New file: `core/.../diagnostic/ConnectionEventLog.kt`**

```kotlin
data class ConnectionEvent(
    val timestampMs: Long,
    val type: EventType,
    val detail: String = ""
) {
    enum class EventType {
        SCAN_STARTED, DEVICE_FOUND, CONNECT_REQUESTED,
        BLE_CONNECTED, SERVICES_DISCOVERED, WHEEL_TYPE_DETECTED,
        DECODER_READY, CONNECTED, BLE_ERROR, DECODE_ERROR,
        DATA_TIMEOUT, DISCONNECT_REQUESTED, CONNECTION_LOST
    }
}

class ConnectionEventLog(private val maxEvents: Int = 200) {
    private val lock = Lock()
    private val events = ArrayDeque<ConnectionEvent>(maxEvents)

    fun log(event: ConnectionEvent) { /* lock-protected */ }
    fun snapshot(): List<ConnectionEvent> { /* lock-protected copy */ }
    fun clear() { /* lock-protected */ }
}
```

The `WheelConnectionManager` reducer emits log entries as a new effect type `WcmEffect.LogEvent`.

### 4.4 Unknown Frame Tracking

**New file: `core/.../diagnostic/UnknownFrameTracker.kt`**

```kotlin
data class UnknownFrameSummary(
    val frameSignature: String,  // e.g., "0xAA 0xAA 0x14 0x0B cmd=0x42"
    val count: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val sampleHex: String        // First occurrence hex dump
)
```

Each decoder reports unknown/unhandled frames by returning metadata in `DecodedData`:

```kotlin
data class DecodedData(
    // ... existing fields ...
    val unknownFrameSignature: String? = null  // non-null when frame was unrecognized
)
```

The `WheelConnectionManager` accumulates these into an `UnknownFrameTracker`, which deduplicates by signature and counts occurrences.

### 4.5 Diagnostic Report

**New file: `core/.../diagnostic/DiagnosticReport.kt`**

```kotlin
data class DiagnosticReport(
    /** Schema version. Bump when report structure changes. */
    val reportVersion: Int = CURRENT_REPORT_VERSION,

    // Identity
    val wheelType: String,
    val model: String,
    val firmwareVersion: String,
    val btName: String,
    val firmwareLevel: Int,

    // Capabilities (serialized as list of command ID strings)
    val supportedCommands: List<String>,

    // Connection timeline
    val connectionEvents: List<ConnectionEvent>,

    // Unknown frames
    val unknownFrames: List<UnknownFrameSummary>,

    // Packets (init snapshot + recent rolling buffer)
    val packets: List<SerializedPacket>,

    // App metadata
    val appVersion: String,
    val platform: String,  // "android" or "ios"
    val timestamp: Long
) {
    companion object {
        const val CURRENT_REPORT_VERSION = 1
    }
}

data class SerializedPacket(
    val timestampMs: Long,
    val direction: String,
    val hex: String,
    val size: Int
)
```

Note: `serialNumber` is omitted from the report structure entirely (see Privacy section). `supportedCommands` is serialized as a `List<String>` of enum names rather than embedding the full `CapabilitySet` — this decouples the report format from the internal Kotlin enum, making reports readable across app versions even if enum values are renamed.

**`DiagnosticReportSerializer.kt`**: Uses `kotlinx.serialization` with `@Serializable` annotations. The diagnostic report is a complex nested structure (lists of events, packets, unknown frame summaries) that would be error-prone and maintenance-heavy to serialize by hand. The `kotlinx-serialization-json` dependency is scoped to the diagnostic package and adds minimal build overhead since the KMP project already uses the Kotlin compiler plugin infrastructure.

### 4.6 Report Generation Flow

```kotlin
class DiagnosticReportBuilder {
    fun buildReport(
        wheelState: WheelState,
        capabilities: CapabilitySet,
        connectionEvents: List<ConnectionEvent>,
        unknownFrames: List<UnknownFrameSummary>,
        packets: List<CapturedPacket>,
        appVersion: String,
        platform: String,
        sanitization: SanitizationLevel = SanitizationLevel.SHAREABLE
    ): DiagnosticReport
}
```

- **Android**: `WheelViewModel.generateDiagnosticReport()` collects data from `WheelConnectionManager`'s owned `BlePacketHistory`, `ConnectionEventLog`, and `UnknownFrameTracker`, then writes JSON to cache and presents the share sheet.
- **iOS**: `WheelManager` does the same via a `DiagnosticReportBridge` helper.

### 4.7 Using Reports as Test Fixtures

The `SerializedPacket` list in a diagnostic report can be replayed through a decoder in tests:

```kotlin
class DiagnosticReplayTest {
    @Test
    fun `replay captured packets through decoder produces expected state`() {
        val report = loadFixture("veteran_lynx_mver5.json")
        assertEquals(DiagnosticReport.CURRENT_REPORT_VERSION, report.reportVersion)

        val decoder = VeteranDecoder()
        var state = WheelState()
        for (packet in report.packets.filter { it.direction == "RX" }) {
            val result = decoder.decode(packet.hex.hexToByteArray(), state, DecoderConfig())
            if (result != null) state = result.newState
        }
        assertTrue(state.model.isNotEmpty())
        assertTrue(decoder.getCapabilities().isResolved)
    }
}
```

Test fixture JSON files go in `core/src/commonTest/resources/fixtures/` or are inlined as string constants in test companion objects (following existing test patterns).

### 4.8 User-Facing UI

**Android** (in existing settings screen):
- "Diagnostics" section at the bottom
- "Generate Report" button — collects and shares JSON
- "BLE Capture" button (already exists) — extended to show packet history stats
- For unknown models: info banner saying "Some settings may not apply to your wheel. Generate a diagnostic report to help us add support."

**iOS**: Mirror the same "Diagnostics" section with share sheet.

---

## 5. Privacy and Data Sanitization

### 5.1 Sensitive Fields

Diagnostic reports may contain personally-identifiable or device-identifying information:

| Field | Risk | Mitigation |
|-------|------|------------|
| Serial number | Device-unique identifier | Omit entirely from all reports |
| BLE device name | Often contains serial suffix (e.g., "P6-50002437") | Truncate to model prefix only |
| BLE MAC address | Device-unique, trackable | Omit entirely from all reports |
| GPS coordinates | Location history (if ride log attached) | Omit from diagnostic reports |
| Firmware version | Low risk, high diagnostic value | Include as-is |
| Raw BLE frames | May contain serial in init frames | Redact known serial byte ranges |

Serial numbers are omitted from the `DiagnosticReport` data structure entirely — not hashed, not truncated, just absent. The model prefix + firmware version + unknown frame signatures provide sufficient diagnostic context for protocol analysis. Hashing serials provides only weak anonymization (EUC serials follow predictable sequential patterns and could be brute-forced), so omission is the safer choice.

### 5.2 Sanitization Levels

The `DiagnosticReportBuilder` supports two modes:

```kotlin
enum class SanitizationLevel {
    /** Full data — for local debugging only. Never shared. */
    LOCAL,
    /** Sensitive fields stripped — safe for sharing. */
    SHAREABLE
}
```

**LOCAL**: All fields included verbatim (serial number included in a separate local-only field, BLE name untruncated). Saved to device storage for the user's own troubleshooting.

**SHAREABLE** (default for export/share):
- Serial number → omitted
- BLE name → model prefix only (e.g., "P6-50002437" → "P6")
- MAC address → omitted
- Raw BLE frames → known serial byte ranges (e.g., InMotion V2 init frame bytes 5-20) replaced with `00` bytes. This is inherently fragile (firmware updates may shift byte offsets), so the fixture promotion pipeline includes a manual review step as a backstop.
- GPS → omitted entirely

### 5.3 Consent Flow

When the user taps "Generate Report":

1. Brief explanation dialog: "This report contains BLE communication data from your wheel. Serial numbers and device identifiers are removed before sharing. No location data is included."
2. Two options: "Save Locally" (LOCAL mode) or "Share Report" (SHAREABLE mode)
3. Share uses the platform share sheet — user chooses the destination (email, message, file manager, etc.)

### 5.4 Sharing and Storage

**Phase 1 (implemented)**: Platform-native share sheet only. The user taps "Share Report," the app writes a sanitized JSON file to the platform's temporary/cache directory, and presents the native share sheet (Android `Intent.ACTION_SEND` with `application/json` MIME type; iOS `UIActivityViewController`). The user picks the destination — email, message, AirDrop, Files, cloud drive, etc. No app-managed upload endpoint required.

**Phase 2 (future)**: Optional direct upload to a private endpoint. When available, add an "Upload to FreeWheel" option alongside the share sheet. Reports are access-controlled and reviewed before being promoted to test fixtures. The share sheet remains the primary path; the upload is a convenience shortcut.

**Fixture promotion pipeline**:
1. User generates and shares a SHAREABLE report
2. Developer receives report, reviews for quality (does it contain useful frames?)
3. Developer runs a sanitization pass (verify no PII leaked through raw frames)
4. Promote to test fixture: strip to just the frame hex sequences + expected decoded state
5. Fixture committed to the FreeWheel repo (no PII, just protocol data)

This keeps user data private while still feeding the test suite. The fixture file contains only the byte-level protocol exchange — no identity information survives the promotion step.

---

## 6. Implementation Phases

### Phase 1: Capability Maps and CapabilitySet (KMP Core)
1. Create `CapabilitySet` in `core/.../domain/WheelCapabilities.kt`
2. Create `DecoderCapabilityMap.kt` with `CapabilityMap` typealias
3. Add `getCapabilities()` to `WheelDecoder` interface with default implementation
4. Add `CAPABILITY_MAP` and implement `getCapabilities()` in `VeteranDecoder` (mVer gating)
5. Refactor `VeteranDecoder.buildCommand()` to use `CAPABILITY_MAP` for the version gate instead of per-branch `if (ver < 3)` checks
6. Add capability maps and implement `getCapabilities()` in `LorinDecoder` (model + protoVer gating via merged maps)
7. Add static capability maps for remaining decoders (Gotway, Kingsong, NinebotZ, Leaperkim, InMotion)
8. Add `capabilities: CapabilitySet` to `WcmState` and derive `StateFlow<CapabilitySet>` on WCM
9. Update reducer to refresh capabilities on successful decode (monotonic set union with previous)
10. Write tests: `CapabilitySetTest.kt`, `VeteranCapabilityMapTest.kt`, `LorinCapabilityMapTest.kt`

### Phase 2: Capability-Filtered Settings UI
1. Create `lorinSupersetSections()` merging all InMotion V2/P6 controls into one list
2. Update `WheelSettingsConfig.sections()` to accept `CapabilitySet?` and filter the superset
3. Remove `inmotionP6Sections()` and the `model` parameter from `sections()`
4. Update Android `WheelSettingsScreen.kt` to pass capabilities
5. Update iOS `WheelSettingsView.swift` to pass capabilities
6. Add "detecting capabilities" indicator for unresolved state
7. Add `WheelConnectionManagerHelper.observeCapabilities()` for iOS
8. Tests for capability-filtered sections: verify Veteran mVer 1 vs mVer 5, InMotion V11 vs P6

### Phase 3: Diagnostic Infrastructure (KMP Core)
1. Create `core/.../diagnostic/` package
2. Implement `BlePacketHistory` (two-tier: init snapshot + rolling buffer)
3. Implement `ConnectionEventLog`
4. Implement `UnknownFrameTracker`
5. Add `unknownFrameSignature` to `DecodedData`
6. **Audit VeteranDecoder and LorinDecoder for unhandled frame types** and add `unknownFrameSignature` reporting to their decode paths (at minimum these two, to ensure Phase 4 reports have content)
7. Wire `BlePacketHistory` into WCM as an internally-owned instance (fed in `executeEffects()`, independent of `captureCallback`)
8. Wire `ConnectionEventLog` into WCM via `WcmEffect.LogEvent`
9. Wire `UnknownFrameTracker` into WCM (accumulate from `DecodedData.unknownFrameSignature`)
10. Tests: `BlePacketHistoryTest.kt`, `ConnectionEventLogTest.kt`, `UnknownFrameTrackerTest.kt`

### Phase 4: Diagnostic Reports and Remaining Decoder Wiring
1. Add `kotlinx-serialization-json` dependency scoped to core module
2. Implement `DiagnosticReport` with `@Serializable` annotations and `reportVersion` field
3. Implement `DiagnosticReportBuilder` with `SanitizationLevel` support
4. Add `generateDiagnosticReport()` to Android `WheelViewModel` (pulls from WCM-owned history/log/tracker)
5. Add share functionality on Android
6. Add iOS bridge and share functionality
7. Audit remaining decoders (Gotway, Kingsong, NinebotZ, Leaperkim, InMotion) for unhandled frame types and add `unknownFrameSignature` reporting
8. Add UI banner for unknown/unresolved models
9. Create sample test fixtures and `DiagnosticReplayTest`

---

## 7. Key Design Decisions

**Why a capability map instead of parallel `getCapabilities()` logic?** The VeteranDecoder had `if (ver < 3) return emptyList()` repeated 16 times in `buildCommand()`. A separate `getCapabilities()` with the same checks would create a second source of truth that could silently diverge. The static `CAPABILITY_MAP` is declared once and consumed by both methods. Adding a new command requires one map entry, not two code paths.

**Why a superset section list instead of model-specific functions?** The old `inmotionP6Sections()` duplicated most of `lorinSections()`. Every new InMotion model would need another near-duplicate function. A single superset with capability filtering scales to any number of model variants without code duplication, and ensures Android and iOS always get identical sections from the same KMP code.

**Why `controlOverrides` with full `ControlSpec` instead of `ParameterOverride`?** A `ParameterOverride` with nullable fields (`min?`, `max?`, `step?`, `options?`) is ambiguous — which fields apply to which control types? Applying an override to a sealed class hierarchy requires type-checking every variant. Full `ControlSpec` replacement is type-safe, unambiguous, and handles the case where a model needs a different control type entirely (e.g., a Slider that should be a Picker on certain firmware).

**Why monotonic capability expansion?** If the decoder receives a frame that changes its internal state (e.g., re-identification after a BLE reconnect within the same session), capabilities could theoretically shrink. If the user is interacting with a settings slider that suddenly disappears, the UX is broken. Monotonic expansion (set union in the reducer) prevents this.

**Why `kotlinx.serialization` for diagnostic reports?** `DiagnosticReport` is a deeply nested structure: lists of events, packets, unknown frame summaries, and string sets. Hand-writing a JSON serializer for this is error-prone and maintenance-heavy. The `kotlinx-serialization-json` library is well-suited for KMP, adds minimal build overhead, and the compiler plugin is already part of the Kotlin toolchain. Scoping it to the diagnostic package keeps the dependency surface small.

**Why a two-tier packet history instead of a single ring buffer?** A ring buffer of 500 packets (the original design) covers only 5-25 seconds at typical BLE rates. Init-phase packets — handshake, model identification, settings readback — are the most diagnostically valuable and arrive at connection time. By the time a user navigates to "Generate Report," they've been evicted. The two-tier design preserves the first 500 packets of each connection indefinitely while still providing a rolling window of recent data.

**Why is the packet history owned by WCM, not fed via `captureCallback`?** `captureCallback` is a nullable external hook set by the UI layer for explicit BLE logging (`BleCaptureLogger`). Making always-on diagnostic state depend on an external opt-in hook conflates two concerns. The packet history is internal WCM state — always on, always fed — while `captureCallback` remains an independent, external, opt-in mechanism. The two are fully decoupled.

**Why omit serial numbers entirely instead of hashing?** EUC serial numbers follow predictable sequential patterns per manufacturer. A partial hash (first 4 chars + SHA-256) is brute-forceable by anyone who knows the format. The model prefix + firmware version + protocol behavior (unknown frames, capability set) provides sufficient diagnostic context. True omission is the only reliable anonymization.

**Why hex strings instead of `ByteArray` in `CapturedPacket`?** Kotlin `ByteArray` does not implement structural `equals()`/`hashCode()`. A `data class` containing `ByteArray` silently breaks equality checks, set membership, deduplication, and test assertions. Hex strings have correct value semantics and are debugger-friendly. Since packets are serialized to hex for reports anyway, storing them as hex from the start eliminates a conversion step.

**Why `reportVersion`?** Diagnostic reports accumulate over time as test fixtures. If `SettingsCommandId` enum values are renamed or the report structure changes, old fixtures break silently. A version field lets the replay test branch on schema version for backward compatibility, and lets the serializer reject reports from incompatible future versions.

---

## 8. File Layout Summary

### New files in `core/src/commonMain/.../core/`

| File | Purpose |
|------|---------|
| `domain/WheelCapabilities.kt` | `CapabilitySet` data class |
| `domain/DecoderCapabilityMap.kt` | `CapabilityMap` typealias and shared utilities |
| `diagnostic/BlePacketHistory.kt` | Two-tier packet history (init snapshot + rolling buffer) |
| `diagnostic/ConnectionEventLog.kt` | Connection state transition log |
| `diagnostic/UnknownFrameTracker.kt` | Deduplicating tracker for unhandled frames |
| `diagnostic/DiagnosticReport.kt` | Report data class with `@Serializable` |
| `diagnostic/DiagnosticReportBuilder.kt` | Collects data, applies sanitization |

### New test files in `core/src/commonTest/.../core/`

| File | Purpose |
|------|---------|
| `domain/CapabilitySetTest.kt` | CapabilitySet behavior, monotonic expansion |
| `domain/VeteranCapabilityMapTest.kt` | Veteran mVer → capability set mapping |
| `domain/LorinCapabilityMapTest.kt` | Lorin model/protoVer → capability set mapping |
| `diagnostic/BlePacketHistoryTest.kt` | Two-tier buffer behavior, snapshot dedup |
| `diagnostic/ConnectionEventLogTest.kt` | Event log behavior |
| `diagnostic/UnknownFrameTrackerTest.kt` | Tracker dedup and counting |
| `diagnostic/DiagnosticReportSerializerTest.kt` | JSON round-trip, version compat |
| `diagnostic/DiagnosticReplayTest.kt` | Replay captured packets through decoders |

### Modified files

| File | Change |
|------|--------|
| `protocol/WheelDecoder.kt` | Add `getCapabilities()` default method |
| `protocol/WheelDecoder.kt` (DecodedData) | Add `unknownFrameSignature` field |
| `protocol/VeteranDecoder.kt` | Add `CAPABILITY_MAP`, implement `getCapabilities()`, refactor `buildCommand()` to use map |
| `protocol/LorinDecoder.kt` | Add capability maps, implement `getCapabilities()` |
| All other decoders | Add `CAPABILITY_MAP`, implement `getCapabilities()` |
| `domain/ControlSpec.kt` | Add `isExtended` property to `SettingsCommandId` |
| `domain/WheelSettingsConfig.kt` | Replace model-dispatch sections with superset + capability filter |
| `service/WheelConnectionManager.kt` | Add capabilities flow (monotonic), packet history, event log, unknown frame tracker |
| `freewheel/.../WheelSettingsScreen.kt` | Pass capabilities to sections() |
| `freewheel/.../WheelViewModel.kt` | Expose capabilities flow, add report generation |
| `iosApp/.../WheelSettingsView.swift` | Pass capabilities to sections() |
| `iosApp/.../WheelManager.swift` | Observe capabilities, report generation |
| `build.gradle.kts` (core) | Add `kotlinx-serialization-json` dependency |
