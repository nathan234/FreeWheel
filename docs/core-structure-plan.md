# Core Structure Plan

Status: Phase 0 and Phase 1 complete (2026-05-15). Phase 2 and Phase 3 remain.

## Goal

Make the project easier to understand without adding new Gradle modules yet.

This plan treats the current `core/` module as one build unit, but gives it a
clearer internal shape so contributors can answer three questions quickly:

1. What are the stable data types?
2. Where do raw wheel packets become state and commands?
3. Where does connection/session orchestration live?

## Non-goals

- No new Gradle subprojects in this pass.
- No behavior changes.
- No large package move in one commit.

## Recommended Mental Model

Think about the codebase as five logical layers:

| Logical layer | Purpose | Current anchors |
|---|---|---|
| `core-model` | Shared vocabulary: wheel identity, telemetry, settings, profiles | `core/domain/*` |
| `core-protocol` | Manufacturer-specific decode/build-command logic | `core/protocol/*` |
| `core-session` | BLE/session orchestration and lifecycle | `core/service/*`, `core/ble/*` |
| `core-features` | Alarming, telemetry history, logging, charger, replay, ride import/export, diagnostics, validation | `core/alarm/*`, `core/telemetry/*`, `core/logging/*`, `core/charger/*`, `core/replay/*`, `core/ride/*`, `core/diagnostics/*`, `core/validation/*` |
| `app-shell` | Platform orchestration, persistence wiring, UI state | `freewheel/.../WheelViewModel.kt`, `iosApp/FreeWheel/Bridge/*` |

`core/utils/*` stays as shared support code and should remain a leaf/helper area,
not a place for new business concepts.

## Dependency Direction

Preferred dependency direction:

`model <- protocol <- session <- app-shell`

`features` should usually depend on `model`, and only depend on `session` when a
feature truly needs connection lifecycle context.

Practical rules:

- `domain` should not depend on `service`.
- `protocol` should not depend on app state, persistence, or UI code.
- `service` may depend on `ble`, `protocol`, `domain`, `logging`, and `utils`.
- `freewheel` and `iosApp` should prefer `WheelConnectionManager` and shared
  domain types rather than reaching into decoder internals.

## Reading Order For New Contributors

When learning the project, read these in order:

1. `core/domain/TelemetryState.kt`
2. `core/domain/WheelIdentity.kt`
3. `core/domain/WheelType.kt`
4. `core/domain/WheelSettings.kt`
5. `core/domain/WheelSettingsConfig.kt`
6. `core/protocol/WheelDecoder.kt`
7. `core/protocol/DefaultWheelDecoderFactory.kt`
8. One manufacturer decoder, such as `GotwayDecoder.kt` or `KingsongDecoder.kt`
9. `core/service/WcmState.kt`
10. `core/service/WheelConnectionManager.kt`
11. `freewheel/.../compose/WheelViewModel.kt`

That path moves from data model to packet decoding to session orchestration to
app glue.

## Proposed Package Cleanup

The biggest comprehension issue today is that `core/domain/` is carrying too
many unrelated ideas. The first cleanup should happen there.

### Target `domain` shape

Keep the `domain/` root, but split it into smaller conceptual subpackages.

| Target area | Example files to group there |
|---|---|
| `domain/identity` | `WheelType`, `WheelIdentity`, `WheelCapabilities`, `ProtocolFamily`, `wheel/*` |
| `domain/telemetry` | `TelemetryState`, `BmsState`, `SmartBms`, `SpeedDisplayMode`, `BmsLabels`, `ChartLabels` |
| `domain/settings` | `WheelSettings`, `WheelSettingsConfig`, `ControlSpec`, `PreferenceKeys`, `PreferenceDefaults`, `AppSetting*`, `SettingsLabels` |
| `domain/profile` | `WheelProfile`, `WheelProfileStore`, `WheelPasswordStore`, `PasswordManagementState`, `LockPromptState`, `ChargerProfile`, `ChargerProfileStore`, `DecoderConfigStore` |
| `domain/dashboard` | Keep existing `dashboard/*` area and related layout/config types |
| `domain/common` | Small constants/labels that are genuinely cross-cutting, such as `AppConstants` and shared label buckets if they do not fit elsewhere |
| `domain/events` | `EventLogEntry` and other event-like value types |

This does not need to happen all at once. The point is to make files easier to
find by concept, not to chase purity.

`domain/wheel/` should stay as a nested catalog area, but under identity:
the intended destination is `domain/identity/wheel/*`, not a flattening of all
wheel catalog files directly into `domain/identity/`.

## Proposed Package Roles Outside `domain`

### `protocol`

`protocol/` is already close to a good seam. Keep it centered on:

- decoder interfaces and factories
- per-vendor decoders
- unpackers/frame reassembly
- wheel command construction

Avoid pushing session state or storage concerns into this package.

### `service`

Treat `service/` as the session/runtime layer:

- connect/disconnect lifecycle
- decoder instance ownership
- command dispatch
- retry and reconnect policy
- StateFlow surfaces

Files like `WheelConnectionManager`, `WcmState`, `ConnectionState`,
`AutoConnectManager`, `KeepAliveTimer`, and `WheelEvent` belong to the same
mental bucket even when they are implemented as separate helpers.

### `features`

The existing dedicated packages are already useful:

- `alarm`
- `telemetry`
- `logging`
- `charger`
- `diagnostics`
- `replay`
- `ride`
- `validation`
- `location`

These are easier to understand once `domain` and `service` have cleaner
boundaries, so they are not the first package-move target.

`ride/` is the ride import/export and bundle area. `validation/` is a small
guardrail/support area that currently sits closest to telemetry/features; it
does not need a package move in Phase 1.

## KMP / Swift Export Impact

Package moves inside KMP are not Kotlin-import-only changes for this repo.

Exported Objective-C/Swift symbol names are derived from Kotlin packages, so
moving types such as `WheelType`, `WheelIdentity`, `TelemetryState`, or
`WheelSettings` changes the names seen by the Swift bridge and SwiftUI code in
`iosApp/FreeWheel/*`.

Default strategy for this cleanup:

- Move Kotlin packages in small batches.
- Update all affected Swift call sites in the same batch.
- Treat source compatibility for the in-repo iOS app as the goal.
- Do not use `@ObjCName` by default just to preserve old package-derived names.

Reserve `@ObjCName` for a specific case where preserving exported names is worth
the annotation overhead, not as the default plan for every batch.

## Verification Rules

Every package-move batch must:

- run `./gradlew :core:testDebugUnitTest`
- run `./gradlew :freewheel:testDebugUnitTest`
- leave those results no worse than the starting baseline; if a pre-existing
  failure exists, the batch must not add new failures and should note the
  baseline in the commit/PR
- compile the iOS app or equivalent Swift bridge path when the batch moves KMP
  types that are exported into Swift

## Rollout Plan

### Phase 0: Documentation

Status: complete.

- Add short package READMEs for `domain`, `protocol`, and `service`.
- Keep this plan in `docs/` as the source of truth for the intended shape.

### Phase 1: `domain` package cleanup

Status: complete. Landed across five commits:

1. `fc59c90a` — identity types (`domain/identity/` + `domain/identity/wheel/`)
2. `662a9f2e` — telemetry/BMS types (`domain/telemetry/`)
3. `39101f57` — settings/config types (`domain/settings/`)
4. `1dd36543` — profile/store types (`domain/profile/`)
5. `b14b1afc` — event/common leftovers (`domain/events/`, `domain/common/`)

`KeyValueStore`, the `*Labels.kt` files, and the `dashboard/` subpackage
were deliberately left at the `domain/` root as out of scope for this pass.

Original suggested move batches (kept here for traceability):

1. Identity types, including `domain/wheel/* -> domain/identity/wheel/*`
2. Telemetry/BMS types
3. Settings/config types
4. Profile/store types
5. Event/common leftovers

### Phase 2: Session surface cleanup

- Add a short `service` package index comment or README if the first doc pass is
  not enough.
- Keep `WheelConnectionManager` as the single obvious orchestrator entry point.
- Reduce any accidental leakage of protocol details into app-facing code.

### Phase 3: Optional physical modularization

Only revisit real Gradle modularization after the package boundaries feel
obvious in day-to-day work. If the code naturally settles into stable seams,
future module candidates would likely be:

- `core-model`
- `core-protocol`
- `core-session`

That should come after package cleanup proves the boundaries are real.

## Recommendation

Start with docs and package moves, not build-graph changes.

If the project becomes easier to navigate after the `domain` cleanup, that is a
good sign the logical split is correct. If it does not, adding Gradle modules
would probably add ceremony without helping comprehension.
