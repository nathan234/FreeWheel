# Wheel Identification Robustness Plan

## Goal

Improve FreeWheel's wheel identification so it keeps its current "do not silently guess wrong" behavior while matching the strongest parts of Wheellog.Android's auto-identification for shared BLE transports.

## Current Assessment

### FreeWheel strengths

- Topology-first matching is safer than name-first matching.
- Unknown and ambiguous cases surface explicitly instead of silently defaulting.
- The picker flow is a better fallback than mis-protocolling a live session.
- Name heuristics are better curated and better tested than the legacy ad hoc rules.

### FreeWheel current gaps

- Shared BLE transports are still treated too much like final wheel identity.
- `AutoDetectDecoder` exists but is not the default production path for the Gotway/Veteran/Leaperkim transport family.
- Ninebot S2/Mini advertisement-based subtype selection is not wired into production detection.
- Decoder selection and BLE transport selection are still too tightly coupled.

### Wheellog.Android strengths worth porting

- It keeps a shared transport unresolved when needed, then promotes to the real protocol from packet evidence.
- It uses manufacturer advertisement bytes to distinguish Ninebot S2/Mini from other Ninebot-family sessions.
- It treats shared physical BLE wiring and protocol identity as separate decisions.

### Wheellog.Android behavior not worth porting

- Silent fallback to a default decoder when the evidence is weak.
- Name-only shortcuts that bypass stronger topology or packet evidence.

## Design Principles

1. Topology evidence beats device-name heuristics.
2. Packet evidence beats topology when multiple protocols share the same BLE wiring.
3. Unknown is better than wrong.
4. User-confirmed picks remain valid durable state.
5. Experimental Leaperkim CAN support remains evidence-gated, not name-gated.

## Target End State

FreeWheel should support three stages of identification:

1. **Transport detection**
   Determine the BLE wiring to use for notifications and writes.

2. **Protocol confirmation**
   When multiple protocols share the same transport, promote from real packet headers or advertisement evidence.

3. **Model and capability discovery**
   Let the concrete decoder determine model, firmware, capabilities, and settings support.

## Proposed Changes

### 1. Add a shared-transport detection result

Extend `WheelTypeDetector` so it can return a production result for "transport known, protocol still needs confirmation" instead of forcing every match into a final `WheelType`.

Suggested shape:

- `Detected`
- `Ambiguous`
- `Unknown`
- `NeedsPacketConfirmation`

The first concrete users should be:

- Gotway/Veteran/Leaperkim-family `FFE0/FFE1`
- Nordic-UART Ninebot-family cases where transport alone does not fully choose the decoder

Primary files:

- `core/src/commonMain/kotlin/org/freewheel/core/ble/WheelTypeDetector.kt`
- `core/src/commonMain/kotlin/org/freewheel/core/ble/WheelConnectionInfo.kt`

### 2. Decouple transport from decoder identity

Today the connection path still mostly assumes one `WheelType` implies one transport and one decoder. That makes shared transports awkward.

Introduce an internal structure that carries:

- confirmed or provisional `wheelType`
- read/write BLE UUIDs
- decoder selection strategy
- optional decoder parameters derived from advertisement evidence

Possible names:

- `ConnectionResolution`
- `DecoderSpec`
- `ProtocolResolution`

Primary files:

- `core/src/commonMain/kotlin/org/freewheel/core/service/WheelConnectionManager.kt`
- `core/src/commonMain/kotlin/org/freewheel/core/ble/WheelConnectionInfo.kt`
- `core/src/commonMain/kotlin/org/freewheel/core/protocol/DefaultWheelDecoderFactory.kt`

### 3. Make `AutoDetectDecoder` production-grade

`AutoDetectDecoder` should become the real session decoder for shared Gotway-family transport until the first decisive frame lands.

The existing header rules are already the right starting point:

- `55 AA` -> Gotway/Begode
- `DC 5A 5C` -> Veteran
- `AA AA` -> Leaperkim CAN

Before promoting it into the main path, it should fully delegate post-detection behavior, not just frame decode:

- `buildCommand(...)`
- `getCapabilities()`
- `getKeepAliveCommand()`
- `keepAliveIntervalMs`
- unpacker stats, if relevant

Primary files:

- `core/src/commonMain/kotlin/org/freewheel/core/protocol/AutoDetectDecoder.kt`
- `core/src/commonMain/kotlin/org/freewheel/core/protocol/WheelDecoder.kt`

### 4. Wire packet-stage promotion into `WheelConnectionManager`

Use the existing `WheelTypeDetected` transition path to promote a live session from provisional shared-family state to a final decoder without reconnecting.

Desired flow:

1. service discovery resolves shared transport
2. BLE is configured once
3. provisional decoder starts
4. first decisive packet promotes to final decoder
5. session continues without disconnecting or re-running the picker

Important constraint:

- Keep FreeWheel's current "ConfigureBle before init commands" ordering intact

Primary files:

- `core/src/commonMain/kotlin/org/freewheel/core/service/WheelConnectionManager.kt`
- `core/src/commonMain/kotlin/org/freewheel/core/service/WheelEvent.kt`

### 5. Add advertisement-based Ninebot subtype resolution

FreeWheel already captures advertisement manufacturer data. Use it in production to select Ninebot S2/Mini variants the same way Wheellog.Android does.

Desired behavior:

- Nordic UART topology + matching S2/Mini advertisement bytes -> instantiate `NinebotDecoder` with the corresponding proto version
- same transport with no S2/Mini match -> use `NinebotZDecoder`

This keeps BLE transport and decoder choice separate, which is the core architectural fix.

Primary files:

- `core/src/commonMain/kotlin/org/freewheel/core/ble/BleAdvertisement.kt`
- `core/src/commonMain/kotlin/org/freewheel/core/service/WcmState.kt`
- `core/src/commonMain/kotlin/org/freewheel/core/service/WheelConnectionManager.kt`
- `core/src/commonMain/kotlin/org/freewheel/core/protocol/NinebotDecoder.kt`

### 6. Preserve current safety rules

Do not regress the following:

- topology wins over conflicting names
- `SCAN_NAME` hints do not bypass the picker
- saved-profile and explicit user hints remain durable
- Leaperkim CAN is never selected from model names alone

Relevant references:

- `docs/leaperkim-correctness-plan.md`
- `core/src/commonMain/kotlin/org/freewheel/core/service/ConnectionHint.kt`
- `core/src/commonMain/kotlin/org/freewheel/core/service/WheelConnectionManager.kt`

## Recommended Implementation Order

### Phase 1: Shared Gotway-family promotion

Implement provisional shared-family resolution and promote to:

- `GOTWAY`
- `VETERAN`
- `LEAPERKIM`

based on first packet evidence.

This delivers the biggest robustness gain with the lowest architectural risk.

### Phase 2: Ninebot advertisement-based subtype selection

Use cached advertisement evidence to choose:

- `NinebotDecoder(DEFAULT)`
- `NinebotDecoder(S2)`
- `NinebotDecoder(MINI)`
- `NinebotZDecoder`

while preserving the existing safe fallback behavior.

### Phase 3: Cleanup and generalization

- simplify remaining transport-vs-decoder coupling
- unify resolution terminology
- improve diagnostics for unresolved shared-transport sessions

## Testing Strategy

Follow the existing test-first KMP policy.

### Required test groups

1. `WheelTypeDetector` tests
   - shared transport returns provisional result
   - topology still beats conflicting device names
   - unresolved cases still return `Unknown` or `Ambiguous`

2. `AutoDetectDecoder` tests
   - `55 AA` promotes to Gotway
   - `DC 5A 5C` promotes to Veteran
   - `AA AA` promotes to Leaperkim CAN
   - delegated commands/capabilities/keep-alive match the chosen decoder

3. `WheelConnectionManager` lifecycle tests
   - provisional shared transport configures BLE once
   - packet-stage promotion does not reconnect
   - stale picker or stale hint cannot override a promoted live session

4. Ninebot resolution tests
   - manufacturer bytes select S2
   - manufacturer bytes select Mini
   - unmatched advertisement falls back safely
   - Nordic transport remains valid regardless of final decoder choice

## Success Criteria

This work is successful when all of the following are true:

- FreeWheel never silently defaults to the wrong protocol when evidence is weak.
- Gotway/Veteran/Leaperkim shared transport sessions auto-resolve from packet evidence.
- Ninebot S2/Mini sessions auto-resolve from advertisement evidence.
- Unknown wheels still surface cleanly to the picker.
- Leaperkim CAN remains evidence-gated per `docs/leaperkim-correctness-plan.md`.

## Key References

- `core/src/commonMain/kotlin/org/freewheel/core/ble/WheelTypeDetector.kt`
- `core/src/commonMain/kotlin/org/freewheel/core/protocol/AutoDetectDecoder.kt`
- `core/src/commonMain/kotlin/org/freewheel/core/service/WheelConnectionManager.kt`
- `core/src/commonMain/kotlin/org/freewheel/core/protocol/NinebotDecoder.kt`
- `docs/leaperkim-correctness-plan.md`
- `docs/decoder-parity.md`
