# Leaperkim Correctness Plan

## Goal

Make Leaperkim-family protocol support **provably correct** by routing production behavior from an evidence-backed test harness instead of assumptions, model names, or catalog entries.

This plan is intentionally correctness-first:

- No model is allowed to silently route to a decoder without fixture-backed evidence.
- No protocol family is considered production truth until it passes an approved harness.
- Official manufacturer app behavior outranks local architectural preference.

## Core Rule

For Leaperkim-family wheels:

- `VeteranDecoder` is the default candidate.
- `LeaperkimCanDecoder` is an **evidence-only** candidate.
- A model may route to CAN only if there are approved raw-capture-backed CAN fixtures for that model or protocol lane.

## Source-of-Truth Order

Evidence is ranked in this order:

1. `official_app`
   - Decompiled official Leaperkim app source in `freewheel-data/euc-reference-apps/leaperkim/jadx_out/`
2. `legacy_trace`
   - Characterization/parity fixtures from Wheellog.Android `VeteranAdapterTraceTest`
3. `live_capture`
   - Raw BLE captures from real hardware
4. `hypothesis`
   - Unverified implementation or inference

## Truth Known Today

The official Leaperkim app `v1.4.8` shows:

- BLE service UUID `FFE0` and characteristic UUID `FFE1`
- telemetry framing beginning with `DC 5A 5C`
- one parser path for supported ride telemetry
- built-in model entries for:
  - Sherman
  - Sherman Max
  - Sherman S
  - Abrams
  - Patton
  - Lynx
  - Sherman L
  - Patton S

The official app does **not** currently provide strong truth for:

- Oryx
- Lynx S
- a separate CAN-over-BLE telemetry path

## Production Policy

Until the harness says otherwise:

- name-based detection for Leaperkim-family wheels resolves to the legacy Veteran protocol family
- catalog entries must not force `LeaperkimCanDecoder`
- picker/profile persistence must not force `LeaperkimCanDecoder`
- packet evidence may promote a session to CAN
- unresolved models remain legacy-by-default unless raw captures prove CAN

## Truth Table

| Model / Lane | Expected Protocol Family | Evidence Class | Current Confidence | Production Eligibility | Notes |
|---|---|---:|---:|---|---|
| Sherman | `VETERAN` | `official_app` | High | Approved once fixture passes | Official app model table + legacy parser path |
| Sherman Max | `VETERAN` | `official_app` | High | Approved once fixture passes | Same as above |
| Sherman S | `VETERAN` | `official_app` | High | Approved once fixture passes | Same as above |
| Abrams | `VETERAN` | `official_app` | High | Approved once fixture passes | Same as above |
| Patton | `VETERAN` | `official_app` | High | Approved once fixture passes | Same as above |
| Patton S | `VETERAN` | `official_app` | High | Approved once fixture passes | Official app explicitly includes `0070` |
| Lynx | `VETERAN` | `official_app` | High | Approved once fixture passes | Official app explicitly includes `0050` |
| Sherman L | `VETERAN` | `official_app` | High | Approved once fixture passes | Official app explicitly includes `0060` |
| Lynx S | `VETERAN` | `legacy_trace` | Medium | Provisional after fixture pass | Present in Wheellog legacy lane, not official app table |
| Oryx | `VETERAN` | `legacy_trace` | Medium | Provisional after fixture pass | Present in Wheellog legacy lane, not official app table |
| Nosfet Aero | `VETERAN` | `live_capture` + `legacy_trace` | High | Approved once fixture passes | Captures show `FFE0/FFE1 + DC5A5C` |
| Nosfet Apex | `VETERAN` | `live_capture` + `legacy_trace` | High | Approved once fixture passes | Captures show `FFE0/FFE1 + DC5A5C` |
| Nosfet Aeon | `VETERAN` | `legacy_trace` | Medium | Provisional after fixture pass | No direct local capture yet |
| Leaperkim CAN lane | `LEAPERKIM_CAN` | `hypothesis` unless capture-backed | Low | Not production-default | Must remain evidence-only until raw captures exist |

## Harness Contract

Every fixture must run against:

- `VeteranDecoder`
- `LeaperkimCanDecoder`
- optionally `AutoDetectDecoder`

The harness must record:

- accepted or rejected by decoder
- detected wheel identity
- parsed hardware/version code
- readiness
- voltage
- speed
- current
- temperature
- battery percent / SOC result
- BMS partial or complete state
- emitted commands

The winning decoder is the one that matches the approved expected result for that fixture.

## Fixture Schema Requirements

Each fixture should include:

- `fixtureVersion`
- `id`
- `model`
- `evidenceClass`
- `source`
- `protocolExpectation`
- `deviceName`
- `advertisedServices`
- `frames`
- `expected`

The `expected` section must contain normalized fields only:

- `protocolFamily`
- `modelName`
- `hardwareVersionCode`
- `softwareVersionCode`
- `ready`
- `telemetry`
- `battery`
- `bmsSummary`
- `commands`

## Approval States

Each fixture must be labeled as one of:

- `draft`
- `approved`
- `waived`

Rules:

- `draft` fixtures inform development but do not decide production routing
- `approved` fixtures may decide production routing
- `waived` fixtures document a known intentional mismatch and must include a reason

## First Fixture Set

The first fixture set should prove the legacy lane before touching routing.

### Batch 1: Official-App Legacy Baseline

1. `leaperkim_sherman_baseline_main_frame`
   - Source: official app parser structure
   - Goal: prove baseline legacy main-frame mapping
2. `leaperkim_patton_baseline_main_frame`
   - Goal: prove 126V-class legacy path
3. `leaperkim_patton_s_baseline_main_frame`
   - Goal: prove `hardwareVersion=0070` still belongs to legacy path
4. `leaperkim_sherman_l_baseline_main_frame`
   - Goal: prove `hardwareVersion=0060` still belongs to legacy path
5. `leaperkim_lynx_baseline_main_frame`
   - Goal: prove `hardwareVersion=0050` still belongs to legacy path
6. `leaperkim_control_settings_block`
   - Goal: prove subtype `8` control settings parsing
7. `leaperkim_bms_left_1_to_15`
   - Goal: prove subtype `1` battery cell mapping
8. `leaperkim_bms_right_1_to_15`
   - Goal: prove subtype `5` battery cell mapping
9. `leaperkim_battery_percent_from_frame_when_present`
   - Goal: prove `isNewBatteryCulModel` path
10. `leaperkim_soc_table_lookup_by_hardware_version`
   - Goal: prove official-app SOC tables for `0040`, `0050`, `0060`, `0070`

### Batch 2: Wheellog Legacy Extension

11. `leaperkim_oryx_mver8_legacy_trace`
   - Goal: prove Oryx legacy handling
12. `leaperkim_lynx_s_mver9_legacy_trace`
   - Goal: prove Lynx S legacy handling
13. `nosfet_aero_legacy_trace`
   - Goal: prove Aero legacy handling
14. `nosfet_apex_legacy_trace`
   - Goal: prove Apex legacy handling
15. `nosfet_aeon_legacy_trace`
   - Goal: prove Aeon legacy handling

### Batch 3: Auto-Detect Protection

16. `autodetect_dc5a5c_routes_to_veteran`
   - Goal: lock legacy promotion
17. `autodetect_aaaa_routes_to_can`
   - Goal: keep explicit CAN promotion working
18. `name_hint_leaperkim_family_does_not_force_can`
   - Goal: lock the no-silent-CAN rule

## CAN Admission Criteria

`LeaperkimCanDecoder` is not allowed to become default for any model unless all of the following exist:

1. raw BLE captures from real hardware
2. stable frame signature across at least two sessions
3. expected decoder outputs documented as fixtures
4. approved fixture pass on `LeaperkimCanDecoder`
5. corresponding legacy decoder either rejects the frames or demonstrably fails the fixture

Without those five conditions, CAN remains:

- implementation present
- non-default
- evidence-seeking
- not source-of-truth

## Required Refactors After Harness Exists

These changes should happen only after the first fixture batches are green:

1. split user-facing model identity from protocol-family decoder routing
2. prevent catalog entries from directly selecting `LeaperkimCanDecoder`
3. rename protocol concepts if needed for clarity:
   - `LEAPERKIM` -> `LEAPERKIM_CAN`
   - keep model branding independent from transport family

## Exit Criteria

This protocol collapse is complete only when:

- every Leaperkim-family production model has an evidence class
- every supported production route has approved fixtures
- `VeteranDecoder` or `LeaperkimCanDecoder` wins by fixture result, not by assumption
- unresolved models cannot silently route to CAN
- CAN has at least one approved raw-capture-backed fixture before any production-default usage

## Immediate Next Steps

1. Create the shared fixture schema for Leaperkim-family protocols
2. Encode the 10 official-app-backed fixtures in Batch 1
3. Encode the 5 Wheellog-backed fixtures in Batch 2
4. Add the 3 routing-protection fixtures in Batch 3
5. Run the fixtures against both FreeWheel decoders
6. Only then change production routing
