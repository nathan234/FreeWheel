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
| Profile and calibration UI | Android and iOS Wheel Settings now begin with an app-owned Profile & Calibration surface. It combines detected identity with effective calibration, labels model defaults versus overrides, explains that values are not wheel firmware, and resets overrides without sending a wheel command. |
| Decoder configuration ownership | `DecoderConfig` now contains only values actually consumed by protocol code. Display-unit flags and unused battery-capacity/empty-cell fields were removed; capacity and cell endpoints remain app-owned profile metadata until a validated range or SOC model consumes them. |
| Begode legacy current | Wheels that predate battery-current frame `0x07` now use EUC World's speed-adjusted phase-current estimate instead of reporting zero current and power indefinitely. |
| Begode model and multi-pack routing | Additional firmware-matchable models now carry explicit voltage classes, and manufacturer frame dispatch routes `0x01` contexts 0-3 to four independent BMS accumulators. User no-load/PWM overrides take precedence over catalog defaults. |
| Veteran command/readback parity | Manufacturer action bytes now drive lock/unlock in the correct direction, ride-mode readback is translated from wire values 1-3 to app values 2-0, event-log requests carry exactly one CRC32, and classic no-CRC frames retain structural validation. |
| Leaperkim/Nosfet divergence | Nosfet brake-alarm offsets and command ID are separate from Leaperkim's map. Unsupported Leaperkim-only dynamic-assist and acceleration controls are no longer advertised for Nosfet. |
| Lorin E25 | Manufacturer-internal series 12/type 1 is identified as the shipping E25. Dedicated manufacturer telemetry, settings, and dual-battery-summary layouts replace the prior generic V9 assumptions. |
| KingSong F18 | `KS-F18P` identity, 36S/151.2 V SOC selection, catalog routing, and both 36-cell BMS packs are covered by archived manufacturer-compatible notification replay. |
| InMotion protocol acknowledgements | V1 password authentication now checks the acknowledgement result, and remote-control acknowledgements re-arm slow settings readback. Lorin command `0x05` battery summaries populate both BMS packs using the manufacturer layouts. |

## Confirmed Accuracy Gaps

| Priority | Area | Finding | Evidence / next action |
|---|---|---|---|
| P2 | Leaperkim Oryx SOC | Oryx uses a model-class piecewise-linear 176 V fallback rather than a manufacturer table. | Obtain a manufacturer-app table or voltage/SOC observations across a discharge. |
| P2 | Nosfet Aeon/Xeno SOC | Aeon and the provisional manufacturer-version 504→Xeno mapping have no primary SOC table in the archived references. | Retain explicit model-class fallback provenance and replace it only when manufacturer evidence is available. |
| P2 | E25/F18 hardware validation | Manufacturer schemas and archived notifications establish the parsing boundary, but neither target has been exercised by FreeWheel on the physical wheel. | Validate E25 controls on hardware and replay a fresh F18 ride capture before treating every setting as confirmed. |

## Known Accurate or Corroborated Areas

- Leaperkim and Nosfet profiles distinguish manufacturer tables from model-class
  fallbacks. The embedded Leaperkim tables are manufacturer-backed; the archived Nosfet
  APK does not embed its downloaded per-model tables, so Apex, Aero, Aeon, and Xeno remain
  explicitly labeled voltage-class fallbacks.
- Begode model-derived voltage class and no-load-speed defaults take precedence over the
  old generic fallback, with an explicit per-wheel override still available.
- Begode BMS cell frames accumulate up to four packs and calculate statistics from valid
  positive cells.
- KingSong F18/F18P, F22/F22P, and other newer voltage classes have explicit model mappings.
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

## Remaining Boundary Work

- Catalog-backed calibration provenance covers Begode. Veteran-family profile metadata now
  exposes manufacturer-table versus model-class-fallback SOC provenance, while KingSong and
  InMotion voltage classes are still decoder-owned rather than fully represented in the
  app-owned wheel profile.
- Battery capacity and empty-cell voltage remain stored profile metadata but are deliberately
  not exposed as effective decoder settings until a validated range or SOC model uses them.

DarknessBot provides useful architectural corroboration here: device settings contain
battery capacity, pack voltage, 0%/100% cell voltage, and speed correction, separately from
application settings.

## Recommended Sequence

1. Extend profile provenance across KingSong and InMotion without
   duplicating decoder model tables.
2. Hardware-validate E25 and F18, and replace the Oryx/Aeon/Xeno fallback SOC curves when
   manufacturer tables become available.
