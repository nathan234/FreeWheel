# GotwayDecoder vs official Begode app v1.0.52 — parity audit

Audited 2026-08-16 against `freewheel-data/euc-reference-apps/begode/jadx_official_1.0.52/`
(app package `com.zzcyi.monotroch`, located via BLE UUID `0000ffe1`). Parse sites:
`ui/home/HomeFragment.java` `onCharacteristicChanged` (~896–1885, frames 0x00–0x07),
`ui/home/HomeSettingActivity.java` (~1455–1930, settings readback). Helpers:
`Utils.getShort` = unsigned BE16, `Utils.getShort2` = signed BE16. Tiebreaker:
EUC World `eucworld/jadx_out_v2_gotway/sources/ug/u.java`.

Status legend: OPEN = not fixed; NEEDS-CAPTURE = references conflict, needs hardware data.

## Gaps

### 1. Miles-mode speed normalization (HIGH, NEEDS-CAPTURE)
FreeWheel (`GotwayDecoder.kt:377-380`) divides speed and trip distance by 0.62137 when
`inMiles`. Official app never converts speed (treats raw as km/h always; miles is a
display multiply); trip distance ×1.60934 in miles mode; total distance label-swap only.
EUC World converts both (agrees with FreeWheel); legacy WheelLog converts nothing.
Example: raw 500, miles mode → official 18 km/h, FreeWheel 28.97 km/h (61% inflation).
Three-way reference conflict — do not change without a miles-mode capture.
Rust mirror: `gotway.rs:528-541`.

### 2. SetWeakMagnetism clamps to 0–6, official range 0–9 (MEDIUM, FIXED)
FreeWheel `GotwayDecoder.kt:893`: `(level % 7) + 0x30`. Official `Config.java:7` +
`HomeSettingActivity.java:610-624`: `W C <digit 0–9>`. Requesting 8 sends '1' (8%7).
Rust mirror same bug: `gotway.rs:1117`.

### 3. Frame 0x07 byte 5 is two nibbles, FreeWheel reads a 16-bit short (MEDIUM, FIXED)
Official (`HomeSettingActivity.java:1829-1846`, EUC World agrees): low nibble = cutout
angle index (45–90° in 5° steps), high nibble = weak magnetism. FreeWheel
(`GotwayDecoder.kt:617-618`) reads bytes 4-5 as a short valid 0–9, so readback breaks
whenever weak magnetism ≠ 0 (e.g. weakMag 2 + step 3 → byte5=0x23=35 → cutout −1),
and `WheelSettings.Begode.weakMagnetism` is never populated. Rust: `gotway.rs:840-843`.

### 4. Frame 0x00 bytes 14-15: settings-readback bitfield on standard firmware (MEDIUM-LOW, OPEN)
Official: `extendedRollAngle = short >> 10`, `pedalTilt = (short >> 5) & 31` (UI shows
value−4), `plateProtection = (short >> 3) & 3`, `mode = short & 7` — all echo-verified
against writes. FreeWheel treats the word as status flags / CF-firmware PWM only, so
extendedRollAngle/plateProtection/pedalTilt readback never populates. Keep CF PWM
handling; gate the bitfield parse on non-CF firmware.

### 5. Frame 0x00 bytes 6-7 = estimated remaining range (LOW-MEDIUM, OPEN)
Official shows it as remaining km/mi (with >60000 → 65536−x correction). FreeWheel
ignores. Free telemetry field for range display.

### 6. Frame 0x01 bytes 2-3 = power-alarm % readback (LOW, OPEN)
Official reads it (echo of `W P`). FreeWheel's `processExtendedFrame` ignores bytes
2-5; `WheelSettings.Begode.powerAlarm` stays −1 despite `SetPowerAlarm` existing.

### 7. Frame 0x02 + byte19==0x18 is calibration status (LOW, OPEN)
0/1/2 = moving / calibrating / success. FreeWheel routes all type-2 frames to BMS
cells (silent no-op for pNum 24) — calibration feedback after `c`+`y` is dropped.

### 8. Frame 0x01 BMS pages 4-5 (packs 5-6) ignored (LOW, OPEN)
Official handles six pack-info sets; FreeWheel maps pages 0-3. Cell frames only cover
4 packs in both apps.

### 9. Frame 0x04 alert-byte naming (COSMETIC, OPEN)
Official: bit0 over-power, bit1 MOS fault, bit2 gyroscope fault (honored <15 km/h),
bit7 vehicle lock (3 consecutive frames). FreeWheel uses legacy WheelLog names
(bit1 "Speed2", bit2 "Speed1", bit7 "TransportMode"). Bits 3-6 match.

### 10. Official-app commands FreeWheel lacks (LOW, OPEN)
`+` then `-` (2 s apart) "model set"; light mode `R` for devideType 205; official
gates frame-4 light readback on devideType (frame 0x04 bytes 16-17 = device type),
FreeWheel ignores devideType. Official never sends `V` (FreeWheel's V/N loop is
additive, not a divergence).

### 11. Battery percent table (COSMETIC, recorded only)
Official uses decile voltage steps (100% ≥ 6550 raw … 0% < 5320); FreeWheel linear
(v−5290)/13. Raw 6550 → official 100%, FreeWheel 96%. Not proposing a change.

### 12. Frame 0x07 current sign (OBSERVATION, NEEDS-CAPTURE)
FreeWheel negates; official displays magnitude only; EUC World keeps raw sign.
Official can't adjudicate. No action without a capture.

## Verified matches

Frame structure (55 AA, 24-byte, 5A×4 footer, type@18, 0x18 context@19). Frame 0x00:
voltage 2-3, speed 4-5 (×3.6, abs), trip 8-9 m, phase current 10-11 signed ×0.01 A,
temp 12-13 signed `/340+36.53`, beeper volume 17. Frame 0x01: pack voltage 6-7
decivolts (no battery-class scaling in official — confirms trueVoltage handling), BMS
current ÷10 A, temps raw °C, semi-voltage ÷10. Cell frames 0x02/03/05/06: 8 cells/page
÷1000 V. Frame 0x04: total distance 2-5 32-bit m, pedals bits 13-15 (2−raw), alarm
mode bits 10-11 ↔ o/u/i, roll angle bits 7-8, miles flag, tiltback 10-11 (≥100
disabled), LED byte 13, light byte 15. Frame 0x07: battery current 2-3, motor temp 6-7
raw, hw PWM 8-9 % (1..100 gate deliberate). All command byte sequences match
(b/Q/T/E/h/f/s/g/m/>/=/</o/u/i/c+y/W B/W M/W U/W X/W R/W P/W Y/x/e). NAME: parsing.

Theoretical note: FreeWheel doesn't gate frames 0x00/0x04/0x07 on byte19==0x18; no
known wheel emits those with a page byte.
