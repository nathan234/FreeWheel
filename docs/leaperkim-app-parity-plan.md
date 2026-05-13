# Leaperkim App Parity Plan

## Purpose

Bring FreeWheel into functional parity with the official Leaperkim Android app v1.4.8 for official-app-backed Leaperkim-family wheels, while preserving the correctness-first routing policy in [leaperkim-correctness-plan.md](leaperkim-correctness-plan.md).

This plan is about product parity, not just decoder parity:

- protocol routing
- settings readback and command support
- model-specific settings UI
- lock and power workflows
- dashboard/home actions
- tests, fixtures, and rollout safety

## Main Conclusion

The official Leaperkim app is primarily a legacy Veteran-protocol parity target, not a CAN parity target.

Evidence from the decompiled app shows:

- telemetry is parsed from `DC 5A 5C` legacy frames
- commands are sent as `LkAp` / `LdAp` CRC32 payloads
- model-specific settings are read from legacy sub-type `8`
- there is no source-backed `AA AA ... 55 55` CAN-over-BLE stack comparable to our `LeaperkimCanDecoder`

That means:

- Sherman
- Sherman Max
- Sherman-S
- Abrams
- Patton
- Lynx
- Sherman L
- Patton S

should reach official-app parity through the `VeteranDecoder` and `WheelType.VETERAN` UI path unless capture-backed evidence proves otherwise.

`LeaperkimCanDecoder` remains an evidence-only lane until raw captures and approved fixtures justify broader use.

## What FreeWheel Already Has

FreeWheel is closer to official-app parity than the current `WheelType.LEAPERKIM` settings screen suggests.

Already present in the shared Veteran path:

- dual-format `LkAp` + `LdAp` command sending for light, ride mode, alarm speed, pedal tilt, power off, reset trip, and more in [core/src/commonMain/kotlin/org/freewheel/core/protocol/VeteranDecoder.kt](../core/src/commonMain/kotlin/org/freewheel/core/protocol/VeteranDecoder.kt)
- sub-type `8` control-settings parsing for stop speed, PWM limit, screen backlight, transport mode, wheel unit, voltage correction, low-voltage mode, high-speed mode, key tone, max charge voltage, dynamic assist, acceleration limit, and brake pressure alarm in [core/src/commonMain/kotlin/org/freewheel/core/protocol/VeteranDecoder.kt](../core/src/commonMain/kotlin/org/freewheel/core/protocol/VeteranDecoder.kt)
- Veteran settings sections for alarm speed, pedal tilt, stop speed, PWM limit, transport mode, high-speed mode, low-voltage mode, key tone, max charge voltage, brake pressure alarm, dynamic assist, acceleration limit, wheel display unit, lateral cutoff, lock, calibrate, power off, and reset trip in [core/src/commonMain/kotlin/org/freewheel/core/domain/WheelSettingsConfig.kt](../core/src/commonMain/kotlin/org/freewheel/core/domain/WheelSettingsConfig.kt)
- event log support and UI
- dashboard quick actions for horn and light

So the parity problem is not "Leaperkim support does not exist." The real problem is that official-app-backed Leaperkim models are split across the wrong product lane, and a few important Veteran-specific behaviors still need dedicated UI and test coverage.

## Current Gap Summary

| Area | Official App Behavior | FreeWheel Today | Gap |
|---|---|---|---|
| Routing | Official-app-backed models live on the legacy Veteran lane | Some catalog entries and UX still imply `WheelType.LEAPERKIM` / CAN for official-app-backed models | High |
| Settings source of truth | Official app reads advanced controls from legacy sub-type `8` | Veteran decoder already parses most of these fields | Low |
| Ride-mode UX | Model-dependent: either 3-step ride mode or 0-100 pedal hardness slider | Veteran UI always shows segmented pedals mode | High |
| Lock UX | Password-based lock/unlock plus password setup, modify, clear, and auto-lock | FreeWheel exposes a generic dangerous lock toggle with no password or auto-lock flow | High |
| Calibration UX | Official app has a stateful gyroscope calibration flow | FreeWheel exposes a generic calibrate action | Medium |
| Power-off UX | Official app checks that the wheel is stopped and not charging before sending shutdown | FreeWheel currently treats power off as a generic dangerous action | Medium |
| Home actions | Official app exposes light, horn, lock, and shutdown from the main screen | FreeWheel dashboard exposes horn and light, but not lock or power off | Medium |
| Label semantics | Official app labels some controls differently: pedal hardness, stop power, vol adjust, acc/dec helper, acceleration reduction | FreeWheel uses generalized Veteran names like PWM Limit, Voltage Correction, Dynamic Assist, Acceleration Limit | Medium |
| CAN lane | Official app does not validate CAN as the default Leaperkim lane | FreeWheel has a dedicated CAN decoder and settings surface | High if used as default |

## Key Product Decisions

Before implementation work starts, we should treat these as settled unless new evidence appears.

### 1. Official-app parity is a Veteran workstream

Do not add official-app-backed legacy settings to `LeaperkimCanDecoder` just because the wheel brand says "Leaperkim."

Instead:

- land parity in `VeteranDecoder`
- route official-app-backed models to `WheelType.VETERAN`
- keep CAN isolated behind evidence-backed routing

### 2. Model branding and protocol family must be separate

User-facing model names should keep Leaperkim / Veteran branding, but decoder selection must follow approved evidence.

Examples:

- "Veteran Sherman L" can still be the displayed model name
- its decoder route should be `VETERAN` until a capture-backed CAN fixture says otherwise

### 3. Capability-driven settings should own the final UI

The official app uses visibility toggles and model-specific branches. FreeWheel should preserve its shared `WheelSettingsConfig` architecture and make parity capability-driven rather than screen-fragment-driven.

## Phased Plan

### Phase 1 - Fix Routing and Truth Boundaries

Goal: make sure the correct product lane is the parity target.

Tasks:

- audit `WheelCatalog` and related routing so that official-app-backed models default to `WheelType.VETERAN`
- keep `WheelType.LEAPERKIM` reserved for capture-backed CAN sessions or explicitly evidence-backed models only
- review picker labels so the app does not imply that "Leaperkim" always means CAN
- add or tighten tests around model routing, picker choices, and name-based detection

Acceptance criteria:

- official-app-backed models no longer silently route to CAN
- CAN remains reachable only through approved evidence or explicit user override
- the correctness plan and production routing agree

### Phase 2 - Lock Decoder and Fixture Parity

Goal: convert the official-app comparison into executable truth.

Tasks:

- finish the official-app-backed fixture set in the existing Leaperkim correctness harness
- add explicit fixtures for:
  - sub-type `8` control settings
  - fall protection angle
  - lock state
  - shutdown command
  - ride mode commands
  - screen backlight, key tone, max charge voltage, brake pressure alarm
- add golden command tests that compare FreeWheel Veteran command bytes against official-app command shapes
- refresh docs where current notes still say a command is missing after it has been implemented

Acceptance criteria:

- every official-app-backed command we claim to support has a fixture or golden test
- the harness clearly shows why Veteran wins for official-app-backed models
- no stale "not implemented" documentation remains for implemented Veteran features

### Phase 3 - Close Shared Core Settings Gaps

Goal: make the shared settings model reflect official-app concepts cleanly.

Tasks:

- add a dedicated Veteran/Leaperkim concept for pedal hardness instead of overloading unrelated fields
- verify whether the current mappings are semantic matches or only byte-position matches:
  - `PWM Limit` vs official `Stop Power`
  - `Voltage Correction` vs official `Vol Adjust`
  - `Dynamic Assist` vs official `Acc/Dec Speed Helper`
  - `Acceleration Limit` vs official `Acceleration Reduction`
- add shared command/state support for any still-missing official-app-backed Veteran controls
- add shared command/state support for Veteran auto-lock if the official lock command semantics are confirmed

Acceptance criteria:

- shared settings state no longer relies on misleading field reuse
- every official-app-backed Veteran control has a clear shared state field, command path, or an explicit documented omission
- label differences are either resolved or intentionally documented

### Phase 4 - Veteran Settings UI Parity

Goal: expose the right controls for the right models.

Tasks:

- make Veteran settings model-aware or capability-aware so the UI can switch between:
  - segmented ride mode
  - continuous pedal hardness slider
- expose a stateful gyroscope calibration control instead of a generic one-shot action if the official flow matters in practice
- add an auto-lock control if Phase 3 confirms the shared command path
- decide whether wheel display unit should remain inside settings, move to app-level units, or be shown in both places
- review control labels and ranges against the official app for Veteran-family wheels

Acceptance criteria:

- Lynx, Sherman L, and Patton S can show the correct ride control without forking the whole screen
- users can reach the same important Veteran controls that the official app exposes
- settings visibility is driven by capability/model truth, not ad hoc UI branching

### Phase 5 - Password and Safety Flows

Goal: match the official app's lock and shutdown behavior closely enough to be trustworthy.

Tasks:

- add a Veteran-specific lock flow that can:
  - prompt for password
  - reuse stored password when appropriate
  - set password
  - modify password
  - clear password
- add auto-lock UI and persistence if the decoder path is confirmed
- add power-off safety checks that mirror the official app:
  - require zero speed
  - block while charging
- decide whether these flows belong in settings, on the dashboard, or both

Acceptance criteria:

- Veteran-family lock is no longer a blind toggle
- shutdown cannot be sent while the wheel is moving or charging
- the user can manage password state without leaving FreeWheel stuck in a half-supported mode

### Phase 6 - Dashboard and Workflow Parity

Goal: cover the highest-value official-app daily actions, not just deep settings.

Tasks:

- evaluate adding dashboard actions for:
  - lock/unlock
  - power off
- keep existing horn and light actions
- decide whether event-log download should remain screen-based or gain a quicker entry point for Veteran-family wheels

Acceptance criteria:

- the most common daily actions from the official app are reachable without drilling through settings
- added actions do not clutter non-Veteran wheels

### Phase 7 - CAN Lane Cleanup

Goal: stop the CAN lane from confusing the parity project.

Tasks:

- keep `LeaperkimCanDecoder` functional but clearly separate from official-app parity work
- audit the current `WheelType.LEAPERKIM` settings surface and remove any implication that it is the source of truth for Sherman / Patton / Lynx family parity
- consider renaming internal concepts for clarity after routing is stabilized:
  - `LEAPERKIM` -> `LEAPERKIM_CAN`

Acceptance criteria:

- official-app parity work can move forward without blocking on CAN
- future CAN work has a clean evidence-backed entry point

## Recommended Implementation Order

1. Phase 1 routing cleanup
2. Phase 2 fixtures and golden tests
3. Phase 3 shared-core field cleanup
4. Phase 4 settings UI parity
5. Phase 5 password and shutdown safety flows
6. Phase 6 dashboard actions
7. Phase 7 naming cleanup

This ordering matters:

- routing first prevents us from polishing the wrong lane
- fixtures second keep us from regressing parity during UI work
- shared-core cleanup before UI avoids baking bad abstractions into Compose

## Explicit Non-Goals

This plan does not require:

- cloning the official app's Android screen structure
- moving FreeWheel away from shared `WheelSettingsConfig`
- making CAN the default just because some wheels are newer
- shipping unsupported controls without readback or fixture evidence

## Exit Criteria

The parity project is complete when all of the following are true:

- official-app-backed Leaperkim-family models route through the legacy Veteran parity lane by default
- every important official-app control is either:
  - implemented and tested
  - intentionally hidden by capability
  - explicitly documented as unsupported with a reason
- the lock flow supports real Veteran password behavior
- dashboard and safety flows cover the main official-app daily actions
- CAN remains evidence-backed and does not distort official-app parity decisions

## Immediate Next Steps

1. Reclassify official-app-backed catalog entries away from default CAN routing.
2. Finish the official-app-backed subtype `8` fixture and command-golden coverage.
3. Introduce a dedicated pedal-hardness concept in shared state and settings config.
4. Design the Veteran password and auto-lock UX before touching the Compose settings screen.
5. Only after those steps, decide which remaining official-app workflows belong on the dashboard.
