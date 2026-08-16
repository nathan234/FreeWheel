# KingsongDecoder vs official KingSong app 4.8.73 — parity audit

Audited 2026-08-16. The official parser `com.kingsong.dlc.MainFragmentAty.T1(byte[])`
fails standard jadx decompilation; it was re-decompiled with
`jadx --single-class com.kingsong.dlc.MainFragmentAty --show-bad-code`. Byte helpers:
`U1(a,b)` = signed 16-bit LE (≡ FreeWheel `getInt2R`), `W1(a,b,c,d)` = word-swapped
32-bit (≡ `getInt4R`), `d2(hi,lo)` = BE16 with the official's own off-by-one signed
correction (subtracts 65535 — FreeWheel's proper two's complement is more correct).
No Kingsong port exists in the Rust crate.

Status legend: OPEN = not fixed; NEEDS-CAPTURE = wants hardware data before acting.

## Gaps

### 1. 0xF6 total energy read with wrong 32-bit byte order (HIGH, FIXED)
FreeWheel `KingsongDecoder.kt:358` uses plain LE `intFromBytesLE(data, 6)`; official
uses `W1(b6,b7,b8,b9)` — the same word-swapped order FreeWheel itself uses for the
0xA9 odometer and 0xB9 trip distance. 0xF6 is the lone outlier. Example: 12,345 Wh on
the wire → official 12,345; FreeWheel 808,648,704. `totalEnergyWh` is garbage for any
real frame.

### 2. 0xC9 battery temperature stored 100x too small (HIGH, FIXED)
Official clamps `U1(b4,b5)` to [−20, 100] and displays directly as °C (the clamp
range proves whole degrees). FreeWheel stores the raw value into `temperature2`,
whose display accessor divides by 100 → battery temp from 0xC9 renders ~0 °C always.
Note: the 0xB9 temp2 path (bytes 14-15) IS centi-degrees (official divides by 100),
so the fix belongs in the 0xC9 handler (`* 100`), not the field convention.

### 3. 0x55 strobe readback overloads lightMode (MEDIUM, FIXED — new strobeMode field)
FreeWheel maps strobe → `lightMode = 2`, but 2 means "auto" in the 0xB9-defined enum.
Official keeps strobe a separate field (`setHeadlightmode` 0=normal/1=strobe) distinct
from on/off/auto. Concrete corruption path: after a strobe readback, `SetMute` echoes
lightMode as byte 0x14 (auto) in the 0x73 command — muting the wheel silently switches
the headlight to auto.

### 4. F1/F2 cell page 0x07 (cells 31-37) ignored (LOW, NEEDS-CAPTURE)
Official subpage 7 stores 7 more cells at index 30 (37-cell classic map). Affects
36S/37S/42S wheels (F18P/F22/F22P) only if firmware reports classic pages rather than
0xD0. Dormant if not; want a capture.

### 5. 0xF3/0xF4 not "always empty" (INFORMATIONAL, NEEDS-CAPTURE)
Official parses both, keyed on subpage b17 (0-3): per-BMS SOC (averaged for battery
display on 14L/16L models), weight-sensor fault bits (b12/b13: bit0 disconnected,
bit5 reversed), lift-stop calibration state machine (U1(b14,b15), states 0-6).
Cross-ref: memory `project_kingsong_speculative_regressions` — on S22 these may be
genuinely empty; on 14L/16L and weight-sensor models they are not.

### 6. 0xE3/0xE4 requested but never parsed (LOW, OPEN)
FreeWheel sends the requests (`RequestBmsData dataType=1`) but `decode()` has no
branch → Unhandled. Official parses sub 0xB1 = BMS manufacture date, 0xBE = humidity
(U1(b9,b8)/10 — note BE arg order) + env temp (d2(b10,b11)/10), 0xA1 = mode flag.
FreeWheel gets humidity/env from 0xD0 instead; matters only on wheels answering E3/E4.

### 7. 0xA4 is a pure write-ACK in the official app (INFORMATIONAL)
Official reads only b2/b3 as success/fail and sends nothing; alarm speeds come
exclusively from 0xB5 (same offsets as FreeWheel). FreeWheel additionally ingests
alarm1/2/3/tiltback from 0xA4 payload bytes 4-10 and echoes a 0x98 — harmless legacy
behavior, but ingesting alarm speeds from an ACK payload could load junk if a wheel
sends 0xA4 as a bare ACK. The only official 0x98 sender is SpeedSettingActivity on
screen entry (plain request).

### 8. 0xBB name checksum formula (RECORDED, no action)
Official 4.8.73 does validate: sum name bytes (≤14, stop at 0), `cs = (65535−sum) &
0xFFFF`, compare to bytes 18-19 LE; firmware ≥117 re-requests 0x9B on mismatch, else
sends a 0x63 serial request. FreeWheel removed its checksum after the S22 infinite
REQUEST_NAME loop (known context); S20/S22 are special-cased in the official app too.
Formula recorded in case the S22 loop is revisited.

### 9. Battery percent derivation (INFORMATIONAL)
Official prefers 0xF6 BMS SOC (b4, 1..101, −1 offset — identical to FreeWheel's
`bmsSoc`) whenever valid; voltage fallback uses server-side per-model low/full as one
linear ramp. FreeWheel computes hardcoded per-family curves into `batteryLevel` and
exposes `bmsSoc` separately — whether the UI should prefer `bmsSoc` is an app-layer
decision worth deciding, not a decoder bug. Also: F1/F2 page 0 bytes 12-13 = design
capacity, used by official as SOH denominator; FreeWheel ignores (P3).

## Verified matches

0xA9 live data (voltage/speed/odometer word-swapped/current signed LE ÷100/temp
signed LE ÷100/mode gate b15==0xE0/power). 18L scaler correctly absent from official
(config-gated legacy behavior). 0xB9 (trip word-swapped, ride time, top speed, light
mode b10, voice b11, fan b12, charging b13, temp2 centi-degrees). 0xF5 (fault fields,
CPU load b14, PWM = b15 alone — FreeWheel's output=b15*100 ≡ official raw percent).
0xF6 speed limit /100, BMS SOC b4, on-time, fault code. 0xBB/0xB3 identity parsing.
0xB5 alarms at b4/b6/b8/b10. F1/F2 BMS pages 0-6 (incl. deci-Kelvin temps — official
−273 sentinel confirms). 0xD0 extended BMS verified numerically identical on the real
F18P capture (remPerc raw/10 ≡ raw*100/fullCap with fullCap=1000 per-mille base).
E1/E2/E5/E6 serial+firmware assembly. Small frames: 0xA2 ride mode, 0x46 password,
0x4C lift sensor, 0x4D LED pattern, 0x3F timer, 0x5F lock. Official-only frames
FreeWheel skips match the parity doc's known P3 items (0xF9, 0x95, 0x8A, 0x6B, 0x58,
0x5C, 0x52/0x6E, 0xE7).

Official-only cosmetics not ported (recorded): 0xA9 speed clamp [0,200], current
deadband (−0.12..0.05 A → 0), mode reset when b15≠0xE0.
