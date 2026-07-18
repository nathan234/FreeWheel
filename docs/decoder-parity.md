# Decoder Parity Checklist

Tracks which legacy Android adapter behaviors have been replicated in the KMP decoders.
Updated after each migration pass. See also [accuracy-parity-audit.md](accuracy-parity-audit.md)
for the cross-app accuracy audit, [protocol-quality-assessment.md](protocol-quality-assessment.md)
for protocol quality comparison, and [CLAUDE.md](../CLAUDE.md) for decoder architecture.

Legend: `[x]` = implemented, `[ ]` = known gap, `[n/a]` = intentionally skipped

Gap priority: **[P1]** = affects real-world usage, **[P2]** = correctness/completeness, **[P3]** = minor/edge-case

## Cross-Decoder Accuracy

- [x] Shared valid-positive-cell BMS statistics now cover Gotway, InMotion V2, KingSong,
  Veteran, and Ninebot Z. Partial or zero-filled pages no longer produce a false 0 V minimum
  or depress the average by dividing by absent cells.

---

## GotwayDecoder

Legacy: `GotwayAdapter.java` | KMP: `GotwayDecoder.kt`
Tests: `GotwayDecoderTest.kt` · `GotwayDecoderComparisonTest.kt` · `GotwayUnpackerTest.kt`

### Init & Identity
- [x] Send V (firmware), b, N (name), b on connect
- [x] Recognize GW and JL firmware prefixes as Begode (plus JN Extreme Bull and custom CF/BF prefixes)
- [x] Match controller name or firmware signature to a Begode/Extreme Bull model catalog for voltage and no-load-speed defaults
- [x] Retry V command when fw empty after receiving live data frames
- [x] Retry N command after fw populated but model still empty
- [x] Fallback naming after 50 attempts (fwProt or "Begode")
- [x] Fallback version "-" after 50 attempts with no fw response
- [x] Reset retry counter on `reset()`

### Frame Parsing
- [x] Frame 0x00: live telemetry (speed, voltage, current, temperature, distance)
- [x] Frame 0x01: extended data (true voltage, BMS temps)
- [x] Frame 0x02/0x03/0x05/0x06: BMS cell voltages for packs 1-4
- [x] Accumulate cell count and statistics independently for each BMS pack
- [x] Frame 0x04: total distance, settings, alerts
- [x] Frame 0x07: battery current, motor temperature
- [x] Frame 0xFF: firmware settings (stub — no UI)

### Telemetry
- [x] MPU6050 temperature formula (standard boards)
- [x] MPU6500 temperature formula (SmirnoV boards)
- [x] gotwayNegative polarity (0=abs, 1=keep, -1=invert)
- [x] useRatio 0.875x scaling
- [x] inMiles normalization (speed, distances)
- [x] Voltage scaling per gotwayVoltage config (10S–50S catalog classes)
- [x] Automatic model-derived voltage scaling, with explicit per-wheel manual selection taking precedence
- [x] Battery percent (standard and "better" curves)
- [x] SmartBMS cell stats (min, max, diff, avg)
- [x] Firmware-specific frame 0x00 bytes 14-15: status on standard GW/JL/JN firmware, PWM on CF firmware; standard firmware uses frame 0x07 current and a model-speed PWM fallback

### Commands
- [x] Beep, light, pedals mode, miles mode, roll angle
- [x] LED mode, beeper volume, cutout angle, alarm mode
- [x] Calibrate (two-step: "c" then "y" after 300ms)
- [x] Max speed (multi-step W/Y/digits sequence)
- [x] Compose settings expose max speed, alarm mode, and controller units
- [x] Suppress stale frame-0x04 settings echoes for 2 frames after simple writes and 5 after multi-step LED/max-speed writes

### Known Gaps
- [ ] **[P2]** Frame 0x01 contains firmware-reported battery/BMS status fields that EUC World exposes (including pack flags and additional pack contexts); FreeWheel currently retains voltage, current, temperatures, and half-pack voltages only. The meaning of byte 19 for these extended frames still needs a capture before mapping it to packs 3/4.

---

## KingsongDecoder

Legacy: `KingsongAdapter.java` | KMP: `KingsongDecoder.kt`
Tests: `KingsongDecoderTest.kt` · `KingsongDecoderComparisonTest.kt`

### Init & Identity
- [x] Send 0x9B (name), 0x63 (serial, 100ms delay), 0x98 (alarms, 200ms delay) on connect
- [x] Name/model extraction from 0xBB frame
- [x] Version extraction from name string (last segment)
- [x] Serial number extraction from 0xB3 frame

### Frame Parsing
- [x] Frame 0xA9: live telemetry
- [x] Frame 0xB9: distance, time, fan, temp2, rideTime, topSpeed, lightMode, mute
- [x] Frame 0xBB: name/type (with checksum validation for fw >= 1.17)
- [x] Frame 0xB3: serial number
- [x] Frame 0xF5: CPU load, PWM, hardware faults
- [x] Frame 0xF6: speed limit, BMS SOC (off-by-1 corrected), totalOnTime
- [x] Frame 0xA4/0xB5: max speed and alarm settings (surfaced in WheelState)
- [x] Frame 0xF1/0xF2: BMS data (dual BMS)
- [x] Frame 0xE1/0xE2: BMS serial
- [x] Frame 0xE5/0xE6: BMS firmware
- [x] Frame 0xD0: extended BMS (F-series)
- [x] Frame 0xA2: ride mode change confirmation
- [x] Frame 0xC9: battery temperature + charge flag
- [x] Frame 0x46: password login result
- [x] Frame 0x4C: lift sensor status
- [x] Frame 0x55: headlight mode readback
- [x] Frame 0x4D: LED mode readback
- [x] Frame 0x3F: turn-off timer

### Telemetry
- [x] KS-18L distance scaling (0.83x)
- [x] Battery percent for 42.5V/51V/55.25V/67V/84V/100V/126V/151V/157V/176V wheels
- [x] Newer pack identification (KS-X 10S, KS-S9 12S, KS-N 13S, KS-F22 37S, KS-F22P 42S)
- [x] Custom battery percent curves

### Commands
- [x] Beep (0x88), light mode (0x73, voice-safe: byte[3]=0), pedals mode (0x87)
- [x] Calibrate (0x89), power off (0x40)
- [x] Color LED on/off (0x6C, inverted logic), LED pattern mode (0x4D), strobe mode (0x53)
- [x] Mute/unmute voice (0x73 with current light mode preserved)
- [x] Lift sensor on/off (0x7E)
- [x] Display brightness (0x54, range 50-100)
- [x] Alarm/speed combo (0x85), alarm settings request (0x98)
- [x] BMS data request (serial/moreData/firmware)
- [x] Init: request light status (0x5B), lift sensor (0x81) on connect

### Known Gaps
- [x] ~~**[P2]** Auto-request BMS serial and firmware~~: one-shot E1/E5 or E2/E6 requests are sent when each BMS first reports F1/F2 page 0.
- [n/a] ~~**[P2]** Send BMS requests after 0xA4~~: rejected after cross-checking the official KingSong 4.8.73 app and three WheelLog implementations. The 0xA4 response only repeats the frame as 0x98; BMS requests are a separate flow.
- [ ] **[P2]** Wheel lock command remains unverified. The official app's 0x41/0x42 frames set/clear its connection password; a lock/unlock capture is required before enabling this control.
- [ ] **[P3]** Volume up/down (0x95) — KS uses relative +/- buttons, not absolute slider
- [ ] **[P3]** Extended settings readback frames (0x87, 0x8A, 0x8B) — sub-typed, informational
- [ ] **[P3]** Date/time frame (0xF9) — informational only

---

## VeteranDecoder

Legacy: `VeteranAdapter.java` | KMP: `VeteranDecoder.kt`
Tests: `VeteranDecoderTest.kt` · `VeteranDecoderComparisonTest.kt`

### Init & Identity
- [x] No init commands — data streaming starts immediately
- [x] Model detection from mVer byte in first frame
- [x] Model name mapping (Sherman, Abrams, Patton, Lynx, etc.)
- [x] Version string from frame ver field

### Frame Parsing
- [x] Live telemetry (speed, voltage, phaseCurrent, temperature, distance)
- [x] SmartBMS data for mVer >= 5 (cell voltages, temps, current)
- [x] BMS cell stat calculation per model
- [x] Timeout-based unpacker reset (100ms)

### Telemetry
- [x] Battery percent curves per model (100V/126V/151V/176V)
- [x] Custom battery percent option
- [x] veteranNegative polarity (same as gotwayNegative)
- [x] PWM and current calculation from hwPwm and phaseCurrent

### Commands
- [x] Beep ("b" for old, binary CRC32 frame for v3+)
- [x] Light on/off (binary CRC32 frame)
- [x] Pedals mode (binary CRC32 frame, 3 levels)
- [x] Alarm speed (binary CRC32 frame, 10-80 km/h)
- [x] Pedal tilt (binary CRC32 frame, -8 to +8°)
- [x] Transport mode (binary CRC32 toggle)
- [x] Speaker volume (binary CRC32 frame, 0-100%)
- [x] High speed mode (binary CRC32 toggle)
- [x] Low voltage mode (binary CRC32 toggle)
- [x] Key tone (binary CRC32 frame, 0-100%)
- [x] Power off (binary CRC32 frame)
- [x] Reset trip ("CLEARMETER")

### Sub-type Extended Data (mVer >= 5)
- [x] Sub-type 0/4: roll angle
- [x] Sub-type 1/5: lock state
- [x] Sub-type 2/6: battery percent override
- [x] Sub-type 8: control settings readback (pedal hardness, transport mode, volume, low voltage mode, high speed mode, key tone; 0x80 = not supported sentinel)

### Known Gaps
- [x] ~~**[P1]** Nosfet Aero SOC fallback~~: mVer 43 uses the official Nosfet 5020
  voltage table, which matches the Patton-class 126 V table.
- [x] ~~**[P2]** Dual-format commands~~: LdAp command format added. Old (LkAp) format still sent for basic commands; new format used for extended settings.
- [ ] **[P2]** Lock command: requires time-based password prefix (`genPwdCmd` in official app). Currently returns empty.
- [ ] **[P2]** Oryx (mVer 8) SOC table: no official table available (not in Leaperkim app v1.4.8). Uses piecewise-linear fallback.
- [x] ~~**[P3]** Fall protection angle~~: parsed from sub-type 2 (byte 47) and surfaced in WheelState.
- [x] ~~**[P3]** Time sync on connect~~: sends time sync commands on first valid frame.
- [x] Sub-types 1/5 cell voltages (cells 1-15), 2/6 cells 16-30, and 3/7 remaining
  cells plus temperatures are accumulated independently for both BMS packs.

---

## NinebotDecoder

Legacy: `NinebotAdapter.java` | KMP: `NinebotDecoder.kt`
Tests: `NinebotDecoderTest.kt` · `NinebotUnpackerTest.kt`

### Init & Identity
- [x] Send serial number request on connect
- [x] State machine: WAITING_SERIAL → WAITING_VERSION → READY
- [x] Serial number from multi-part CAN messages (Param 0x10, 0x13, 0x16)
- [x] Firmware version parsing

### Frame Parsing
- [x] CAN message parsing with CRC16 verification
- [x] Gamma XOR encryption/decryption
- [x] Live data (speed, voltage, current, battery, distance, temperature)
- [x] Multiple protocol versions (Default, S2, Mini)

### Keep-Alive
- [x] 125ms interval (25ms × 5 steps)
- [x] State-dependent: serial → version → live data requests

### Known Gaps
- [ ] **[P1]** Key exchange (legacy requests actual key from KeyGenerator address; KMP starts with zero key — works but less secure)
- [ ] **[P3]** Ninebot Mini angle data parsing (Param 0x61)

---

## NinebotZDecoder

Legacy: `NinebotZAdapter.java` | KMP: `NinebotZDecoder.kt`
Tests: `NinebotZDecoderTest.kt` · `NinebotZDecoderComparisonTest.kt`

### Init & Identity
- [x] Send BLE version request on connect
- [x] 14-state sequential state machine (INIT → READY)
- [x] Key exchange via KEY_GENERATOR address
- [x] Serial number, version, params1-3 sequence

### Frame Parsing
- [x] CAN message parsing with gamma XOR encryption
- [x] BMS dual-pack sequential reads (BMS1_SN → BMS1_LIFE → BMS1_CELLS → BMS2_*)
- [x] Live telemetry data
- [x] Settings and lock/limited mode parsing

### Keep-Alive
- [x] 25ms interval
- [x] State-dependent command per connection state

### Commands
- [x] Light on/off (DriveFlags)
- [x] Calibrate (CAN message)
- [x] Lock/unlock

### Known Gaps
- [ ] **[P2]** `settingRequest` / `settingCommandReady` two-phase command pattern (legacy sends a read-settings request, waits for response, then sends command)
- [ ] **[P2]** Alarm settings request cycle after params3

---

## InMotionDecoder (V1)

Legacy: `InMotionAdapter.java` | KMP: `InMotionDecoder.kt`
Tests: `InMotionDecoderTest.kt` · `InMotionDecoderComparisonTest.kt` · `InMotionUnpackerTest.kt`

### Init & Identity
- [x] CAN frame parsing with header 0xAA 0xAA
- [x] Model, version, serial, and settings readback from slow info data
- [x] Version extraction
- [x] Serial number parsing

### Frame Parsing
- [x] Fast info (live telemetry): speed, voltage, current, angle, roll, distance
- [x] Slow info (settings): model, version, serial, max speed
- [x] Alert parsing with typed alert IDs
- [x] Battery calculation per model voltage curves

### Commands
- [x] Beep (play sound)
- [x] Light on/off
- [x] Calibrate
- [x] Power off

### Known Gaps
- [x] Password authentication retries a configured six-digit PIN up to six times before
  continuing discovery, and advances immediately on acknowledgement.
- [x] ~~**[P2]** Slow data discovery and settings refresh~~: keep-alive requests slow info until the model resolves, switches to fast telemetry after a valid response, and re-arms slow readback after setting acknowledgements.
- [ ] **[P3]** Full model-specific speed calculation factors (20+ V1 models with different factors)

---

## LorinDecoder

Legacy: `InMotionAdapterV2.java` | KMP: `LorinDecoder.kt`
Tests: `LorinDecoderTest.kt` · `LorinUnpackerTest.kt`

### Init & Identity
- [x] Send car type (0x01), serial (0x02), versions (0x06), settings, stats on connect
- [x] Keep-alive state machine: model → serial → version → real-time data
- [x] V11, V11Y, V12HS/HT/PRO/S, V13/PRO, V14g/s, V9, P6, and E20 model routing
- [x] E25 advertised-name routing to the InMotion V2/Lorin decoder family

### Frame Parsing
- [x] Message verification with XOR checksum
- [x] Escape sequence handling (0xA5 prefix for 0xAA/0xA5 bytes)
- [x] Real-time info per model (including dedicated E20 layout and P6 extended 0x87 data)
- [x] Settings parsing per model (including dedicated E20 offsets)
- [x] Total stats (total distance)
- [x] Extended per-pack BMS status and cell-voltage responses
- [x] Diagnostic data
- [x] Mode string and error string parsing

### Telemetry
- [x] Model-specific field offsets (V11 proto v1 vs v2, V12, V13, V14, V11Y)
- [x] Temperature decoding (signed byte + 80)
- [x] Lorin-specific fields: torque, motorPower, cpuTemp, imuTemp, angle, roll
- [x] P6 output rate and consumed-SOC fields kept separate (offsets 14 and 32)

### Commands
- [x] Beep, light, lock, power off, calibrate
- [x] Handle button, ride mode, speaker volume, pedal tilt/sensitivity
- [x] Transport mode, DRL, go-home mode, fancier mode/performance mode, mute
- [x] Fan quiet, fan control, light brightness, max speed
- [x] Motor sound, motor no-load detection, low battery riding
- [x] Extended lateral tilt, standby time
- [x] Split riding modes (enable + settings), speed alarms
- [x] Motor sound sensitivity, screen auto-off, auto headlight
- [x] Model-dependent command routing (V9/V11/V12/V13/V14 branching)
- [x] Firmware-version-dependent fan/headlight sub-commands (V11 fw ≥1.4 vs <1.4)
- [x] V14 max speed uses EXTENDED flag (0x16) with different payload structure
- [x] V9 pedal sensitivity byte order swap (100,value vs value,100)
- [x] V9 DRL uses sub-cmd 0x44 (others use 0x2D)
- [x] V9/V12 split riding modes sub-cmd 0x42 (others use 0x3E)

### Known Gaps
- [x] V14 battery IDs 0x24-0x27 accumulate and publish independently as BMS packs 1-4.
- [ ] **[P1]** E25 is a shipping Lorin-protocol target, but telemetry/settings need an E25 model-response and notification capture. V18 appears only as dormant official-app protocol code and is not treated as a shipping support target.
- [ ] **[P2]** General battery real-time info (`flag 0x14`, command `0x05`) is currently
  discarded; determine its authoritative fields from a capture before mapping them.
- [ ] **[P2]** Multi-stage shutdown (legacy sends 0x81 first, waits for ACK, then sends 0x82 — KMP sends single 0x81)
- [x] ~~**[P2]** Battery real-time info request in keep-alive loop~~: BMS polling added to keep-alive via `bmsPollCounter` cycle.
- [ ] **[P3]** Light state debounce (legacy has `lightSwitchCounter` with 3-frame debounce)
- [ ] **[P3]** `getUselessData` request in init sequence (legacy requests Something1 command, KMP skips it)
- [ ] **[P3]** V12 headlight 4-state mode (low/high/both) — KMP only sends simple on/off
- [ ] **[P3]** V12 headlight brightness two-byte mode (low + high separate) — KMP sends single byte
- [ ] **[P3]** V12 auto headlight thresholds (sub-cmd 0x2A, 0x30) — not exposed via WheelCommand
- [ ] **[P3]** Commands not in EUC World (may be InMotion-app-only): berm angle, turning sensitivity, one-pedal mode, speeding braking, sound wave, safe speed limit, backward overspeed alert, tail light mode, turn signal mode, logo light brightness, light effect, two-battery mode, low battery safe mode, spin kill, cruise, load detect, charge limit
