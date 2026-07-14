# Accuracy Parity Audit

This document tracks telemetry-accuracy differences found while comparing FreeWheel with
locally archived versions of DarknessBot, EUC World, and the Begode, KingSong, InMotion,
Leaperkim, and Nosfet manufacturer apps. It complements
[decoder-parity.md](decoder-parity.md), which remains the command and legacy-migration
checklist.

## Evidence Policy

Accuracy changes should be backed by the strongest available source:

1. Captured packets from the affected wheel, paired with the manufacturer app when possible.
2. Manufacturer-app protocol code or tables.
3. Agreement between independent multi-brand apps such as EUC World and DarknessBot.
4. A documented model-class fallback when no primary data exists.

Catalog or competitor defaults are useful corroboration, but they are not automatically
ground truth. Every model-derived calibration should record its source and confidence.

## Resolved Findings

| Area | Resolution |
|---|---|
| InMotion V14 BMS | Four independent BMS accumulators now route and publish battery IDs 0x24-0x27; packet tests prove distinct status for all four packs. |
| InMotion BMS temperatures | Exact-length V1 BMS frames now retain both temperature fields instead of requiring an extra trailing byte. |
| BMS cell statistics | `SmartBms.recalculateCellStats` now provides valid-positive-cell min/max/average behavior for Gotway, InMotion V2, KingSong, Veteran, and Ninebot Z while preserving protocol-specific pack-voltage semantics. |
| Settings navigation | App Settings no longer embeds connected-wheel controls. Android and iOS retain Wheel Settings as a dedicated destination, establishing the first visible ownership boundary. |
| Wheel calibration ownership | `WheelCalibration` now provides one typed per-wheel model for both platforms, with explicit current-polarity and Begode voltage-class enums. `DecoderConfigFactory` is the single mapping into protocol configuration, and app presentation preferences no longer enter or refresh decoder config. |
| Legacy calibration safety | Existing scoped keys remain compatible. Custom SOC now supports per-wheel values with the old global key as a migration fallback, and malformed Begode voltage values resolve to automatic detection rather than the 67.2 V class. |
| Write-only wheel commands | Last-sent slider values now live in a dedicated per-wheel `WheelCommandCacheStore`, retain send timestamps, and are labeled “Last sent — not confirmed by wheel” on Android and iOS. Defaults without readback are also identified as unconfirmed. |
| Calibration provenance | The Begode model catalog is now shared outside the decoder. `ResolvedWheelCalibration` merges detected pack voltage and no-load/PWM references with scoped overrides and records each field as model catalog, user override, legacy global, or app default. Explicit 42 V and 210 V classes cover every voltage currently represented by the catalog. |

## Confirmed Accuracy Gaps

| Priority | Area | Finding | Evidence / next action |
|---|---|---|---|
| P1 | InMotion E25 | Advertised-name routing exists, but model identification, telemetry offsets, and settings offsets are not capture-backed. | Obtain model-response and notification captures from the shipping E25. |
| P2 | Begode multi-pack status | Cell frames support four BMS packs, while frame `0x01` status contexts still collapse into two accumulators. | Capture frame `0x01` from a four-pack wheel and identify the byte-19 context before mapping packs 3/4. |
| P2 | Leaperkim Oryx SOC | Oryx uses a model-class piecewise-linear 176 V fallback rather than a manufacturer table. | Obtain a manufacturer-app table or voltage/SOC observations across a discharge. |
| P2 | InMotion V2 battery summary | The general battery real-time response is accepted but discarded; only extended per-pack BMS responses are retained. | Compare a capture with the manufacturer app and EUC World before assigning fields. |

## Known Accurate or Corroborated Areas

- Leaperkim and Nosfet recognized models use manufacturer-derived SOC tables. Patton S,
  Nosfet Aero, and Nosfet Xeno use the corroborated 126 V Patton-class table.
- Begode model-derived voltage class and no-load-speed defaults take precedence over the
  old generic fallback, with an explicit per-wheel override still available.
- Begode BMS cell frames accumulate up to four packs and calculate statistics from valid
  positive cells.
- KingSong F22/F22P and other newer voltage classes have explicit model mappings.
- InMotion P6 consumed SOC and output rate use distinct offsets.

## Settings Ownership

FreeWheel needs three settings domains, not a binary split between settings and preferences.

### Wheel Controls

Values read from or written to the connected wheel:

- pedal and ride modes
- lights, LEDs, and wheel speaker volume
- tiltback or maximum speed
- lift sensor, transport mode, lock, and charge limit

The wheel is the source of truth. Readback should replace local state. A value cached for a
write-only command must be labeled as the last value sent, not as confirmed wheel state.

### Wheel Profile and Calibration

App-owned data scoped to one physical wheel:

- detected manufacturer, protocol, model, voltage class, and cell count
- battery capacity and SOC curve or cell-voltage endpoints
- speed and distance correction
- current polarity and PWM calibration inputs
- gauge maximum and explicit protocol overrides

Defaults should come from a typed, provenance-bearing model catalog. User overrides should
be rare, visible under an advanced calibration section, and resettable to the catalog value.
Pairing credentials belong in a secure per-wheel credential store rather than this profile.

### User Preferences

Choices about application behavior and presentation:

- units, theme, navigation, and dashboard layout
- logging and reconnect behavior
- notification action and other global interface policy
- app-side alarms

App alarms remain user preferences even when their storage scope is per wheel. They do not
become wheel controls unless the value is actually written to wheel firmware.

## Current Boundary Problems

- `WheelProfile` and `WheelCalibration` are still loaded separately in platform UI, and
  resolved calibration does not yet have a product surface.
- `batteryCapacity` and `cellVoltageTiltback` remain in decoder config but are not consumed
  by a decoder. The obsolete `useMph` and `useFahrenheit` fields also remain in the data
  class for compatibility, although platform factories no longer populate them.

DarknessBot provides useful architectural corroboration here: device settings contain
battery capacity, pack voltage, 0%/100% cell voltage, and speed correction, separately from
application settings.

## Recommended Sequence

1. Add Profile & Calibration beneath the dedicated Wheel area; Controls are already
   separated from App Settings.
2. Continue capture-backed work for E25, Begode four-pack status, and Oryx SOC.
