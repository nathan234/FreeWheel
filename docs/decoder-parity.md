# Decoder Parity Checklist

Tracks which legacy Android adapter behaviors have been replicated in the KMP decoders.
Updated after each migration pass. See also [protocol-quality-assessment.md](protocol-quality-assessment.md) for protocol quality comparison and [CLAUDE.md](../CLAUDE.md) for decoder architecture.

Legend: `[x]` = implemented, `[ ]` = known gap, `[n/a]` = intentionally skipped

Gap priority: **[P1]** = affects real-world usage, **[P2]** = correctness/completeness, **[P3]** = minor/edge-case

---

## GotwayDecoder

Legacy: `GotwayAdapter.java` | KMP: `GotwayDecoder.kt`
Tests: `GotwayDecoderTest.kt` · `GotwayDecoderComparisonTest.kt` · `GotwayUnpackerTest.kt`

### Init & Identity
- [x] Send V (firmware), b, N (name), b on connect
- [x] Retry V command when fw empty after receiving live data frames
- [x] Retry N command after fw populated but model still empty
- [x] Fallback naming after 50 attempts (fwProt or "Begode")
- [x] Fallback version "-" after 50 attempts with no fw response
- [x] Reset retry counter on `reset()`

### Frame Parsing
- [x] Frame 0x00: live telemetry (speed, voltage, current, temperature, distance)
- [x] Frame 0x01: extended data (true voltage, BMS temps)
- [x] Frame 0x02/0x03: BMS cell voltages
- [x] Frame 0x04: total distance, settings, alerts
- [x] Frame 0x07: battery current, motor temperature
- [x] Frame 0xFF: firmware settings (stub — no UI)

### Telemetry
- [x] MPU6050 temperature formula (standard boards)
- [x] MPU6500 temperature formula (SmirnoV boards)
- [x] gotwayNegative polarity (0=abs, 1=keep, -1=invert)
- [x] useRatio 0.875x scaling
- [x] inMiles normalization (speed, distances)
- [x] Voltage scaling per gotwayVoltage config (16S–40S)
- [x] Battery percent (standard and "better" curves)
- [x] SmartBMS cell stats (min, max, diff, avg)

### Commands
- [x] Beep, light, pedals mode, miles mode, roll angle
- [x] LED mode, beeper volume, cutout angle, alarm mode
- [x] Calibrate (two-step: "c" then "y" after 300ms)
- [x] Max speed (multi-step W/Y/digits sequence)

### Known Gaps
- [ ] **[P3]** `lock_Changes` debounce counter (legacy has 3-frame debounce before confirming settings change)

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
- [x] Frame 0xB9: distance, time, fan, temp2
- [x] Frame 0xBB: name/type
- [x] Frame 0xB3: serial number
- [x] Frame 0xF5: CPU load, PWM
- [x] Frame 0xF6: speed limit
- [x] Frame 0xA4/0xB5: max speed and alarm settings
- [x] Frame 0xF1/0xF2: BMS data (dual BMS)
- [x] Frame 0xE1/0xE2: BMS serial
- [x] Frame 0xE5/0xE6: BMS firmware
- [x] Frame 0xD0: extended BMS (F-series)

### Telemetry
- [x] KS-18L distance scaling (0.83x)
- [x] Battery percent for 67V/84V/100V/126V/151V/176V wheels
- [x] Custom battery percent curves

### Commands
- [x] Beep (0x88), light mode (0x73), pedals mode (0x87)
- [x] Calibrate (0x89), power off (0x40)
- [x] LED mode (0x6C), strobe mode (0x53)
- [x] Alarm/speed combo (0x85), alarm settings request (0x98)
- [x] BMS data request (serial/moreData/firmware)

### Known Gaps
- [ ] **[P2]** Auto-request BMS serial (0xE1/0xE2) and firmware (0xE5/0xE6) when first BMS F1/F2 data arrives (legacy triggers these automatically)
- [ ] **[P2]** 0xA4 response should also request BMS data for new wheels

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
- [x] Beep ("b" for old, structured frame for v3+)
- [x] Light on/off ("SetLightON"/"SetLightOFF")
- [x] Pedals mode (SETh/SETm/SETs)
- [x] Reset trip ("CLEARMETER")

### Known Gaps
- (none identified)

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
- [x] Model detection from slow info data
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
- [ ] **[P1]** Password authentication (legacy retries 6 times with 6-digit PIN before wheel responds)
- [ ] **[P2]** Slow data re-request (legacy re-requests slow data periodically to refresh settings)
- [ ] **[P3]** Full model-specific speed calculation factors (20+ V1 models with different factors)

---

## InMotionV2Decoder

Legacy: `InMotionAdapterV2.java` | KMP: `InMotionV2Decoder.kt`
Tests: `InMotionV2DecoderTest.kt` · `InMotionV2UnpackerTest.kt`

### Init & Identity
- [x] Send car type (0x01), serial (0x02), versions (0x06), settings, stats on connect
- [x] Keep-alive state machine: model → serial → version → real-time data
- [x] 12 model variants (V11, V11Y, V12HS/HT/PRO, V12S, V13, V13PRO, V14g, V14s, V9)

### Frame Parsing
- [x] Message verification with XOR checksum
- [x] Escape sequence handling (0xA5 prefix for 0xAA/0xA5 bytes)
- [x] Real-time info per model (V11, V12, V13, V14, V11Y, V9, V12S)
- [x] Settings parsing per model
- [x] Total stats (total distance)
- [x] Battery real-time info
- [x] Diagnostic data
- [x] Mode string and error string parsing

### Telemetry
- [x] Model-specific field offsets (V11 proto v1 vs v2, V12, V13, V14, V11Y)
- [x] Temperature decoding (byte + 80 - 256)
- [x] IM2-specific fields: torque, motorPower, cpuTemp, imuTemp, angle, roll

### Commands
- [x] Beep, light, lock, power off, calibrate
- [x] Handle button, ride mode, speaker volume, pedal tilt/sensitivity
- [x] Transport mode, DRL, go-home mode, fancier mode, mute
- [x] Fan quiet, fan control, light brightness, max speed

### Known Gaps
- [ ] **[P2]** Multi-stage shutdown (legacy sends 0x81 first, waits for ACK, then sends 0x82 — KMP sends single 0x81)
- [ ] **[P3]** Light state debounce (legacy has `lightSwitchCounter` with 3-frame debounce)
- [ ] **[P3]** `getUselessData` request in init sequence (legacy requests Something1 command, KMP skips it)
- [ ] **[P2]** Battery real-time info request in keep-alive loop (legacy alternates between live data and battery requests)
