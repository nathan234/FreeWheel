# VeteranDecoder vs official Leaperkim + Nosfet apps — full-field audit

Audited 2026-08-16 (excludes the bytes 34-35 PWM/current/power path, fixed in
77a0373b). References: `leaperkim/jadx_out/.../BtManager.java` + `Util.java` +
`setting/*Activity.java`; Nosfet app is a rebadged copy — full diff below. Every
parse/command finding is mirrored verbatim in `rust/euc-protocols/src/veteran.rs`;
fixes must land in both to keep the iOS live shadow green.

Status legend: OPEN = not fixed; NEEDS-CAPTURE = wants hardware data before acting.

## Findings

### F1. SetAlarmSpeed adds +10; official sends km/h directly (HIGH, FIXED)
`VeteranDecoder.kt:1099` sends `speed + 10`. Official `SetAlarmSpeedActivity.java:66-68`:
wire byte == alarm speed in km/h (the app's `i + 10` is slider-progress → speed
conversion, mirrored by `kmSpeedToProgress = speed − 10`). User sets 50 km/h →
FreeWheel writes 60 — the alarm fires 10 km/h later than requested. Rust :1189.

### F2. SetPedalTilt adds spurious +80; official sends signed tenths (HIGH, FIXED)
`VeteranDecoder.kt:1107` sends `angle + 80`. Official `SetAngelActivity.java:17-19`:
`progressToSendValue(i) = i − 80`, wire = degrees×10 signed (−80..+80), no offset.
Set 0° → FreeWheel writes 80 = +8.0°; set +5° → writes 130 (signed −126). Every
pedal-tilt write is wrong. Nosfet app identical. Rust :1199.

### F3. alertSpeed/tiltBackSpeed displayed 10x too big (HIGH, FIXED)
Raw bytes 24-27 are deci-km/h (official divides by 10; legacy WheelLog agrees).
`VeteranDecoder.kt:329-330` multiplies by 10 then `:429-430` divides by 10 —
net: stores raw deci-km/h into fields consumers treat as km/h
(`DisplayUtils.tiltBackSpeedText` etc.; Begode fills the same fields with km/h).
Wheel tiltback 60 km/h → dashboard shows "600 km/h". Rust :495-496/:623-624.

### F4. ResetTrip LkAp variant uses cmd 0x0D; official is 0x0B (MEDIUM, FIXED)
Official `CMD_CLEAR_METER` byte 4 = 11 (0x0B, via the FileDownloadStatus constant);
FreeWheel `VeteranDecoder.kt:1174` sends 0x0D. The LdAp variant is correct, but
Patton-family wheels only honor LkAp (see F10) — trip reset never works there.
Rust :1276.

### F5. Subtype/BMS parsing gated on mVer >= 5; official gates only on frame length (MEDIUM, FIXED)
Official dispatches subtype/extended parsing for any binary-mode wheel (strCmdMode
decided by packet length alone); FreeWheel requires `mVer >= 5`
(`VeteranDecoder.kt:360,401`). Patton (mVer 4) and Sherman S (mVer 3) lose subtype-8
settings readback, subtype-2 battery override + fall-protection angle, roll,
battery-group currents, and all BMS cell frames. Rust :537/:581.

### F6. Continuous ride mode (byte 31 ≈ 100-210) unsupported (MEDIUM, NEEDS-CAPTURE)
On continuous-mode models (Lynx "0050", Sherman-L "0060", Patton-S "0070"), official
shows a 0-100 slider: readback `rideMode − 100`, write cmd 0x0C value `progress+10`.
FreeWheel maps byte 31 only for 1/2/3 → unknown on Lynx-class wheels, and offers only
discrete SETs/SETm/SETh. (Separate from PEDAL_HARDNESS 0x0F, which is correct.)
Capture wanted to confirm live byte-31 values before building the control.

### F7. chargingStatus reads 16-bit at 22-23; official reads byte 23 only (LOW, OPEN)
Inherited from legacy WheelLog. Latent while byte 22 stays 0.

### F8. Extended-frame length gates 4 bytes laxer than official (LOW, OPEN)
Official gates every extended read with `size−4 > offset` (CRC excluded); FreeWheel
uses `size > offset`, so short frame variants read CRC bytes as data (e.g. subtype-0
len-66 frame: roll angle read from CRC). Official also zeroes lrAngle on short
frames; FreeWheel retains the previous value.

### F9. Battery-group currents keep sign; official abs() (LOW, OPEN — decide)
FreeWheel's signed value shows regen direction (arguably better) but diverges from
both apps. Decide deliberately, then document.

### F10. Patton "004" single-emit special case missing (LOW, OPEN)
Official sends only the CRC'd LkAp write for Patton (`fullVersionCode.startsWith
("004")`), merged LkAp+LdAp otherwise. FreeWheel always emits both. Mostly harmless
except where the LkAp copy is itself wrong (F4).

### F11-F17 (LOW/INFO, recorded)
Self-reported cell count byte 52 ignored (hardcoded per-model counts identical for
known models); subtype-2 fall-protection angle unsigned + 0→null vs official signed
always-stored; chargeVoltageBase 0→145 mapping (official: only 0x80→145); strCmdMode
beep sends "b" vs official "OLDCMDb" (ver<3 only); subtype 35 wheel-name/imgUrl not
parsed (Leaperkim-only, cosmetic); subtype-8 byte 56 gyro readback missing; time-sync
timezone uses DST offset vs official raw offset (FreeWheel arguably more correct).

## Verified matches (abridged)

Voltage/speed/distance word order/phase current/temperature/shutdown timer/version
parsing (30,28,29)/discrete ride-mode mapping/pitch angle/battery-temp-mode gate —
exact. SOC tables (Sherman 100V, Patton 126V, Lynx 151V) match the embedded
Util.CAR_DATA_JSON entry-for-entry, incl. lookup boundary semantics and model→table
mapping; Oryx/Lynx-S/Nosfet tables are server-fetched, absent from both APKs (known
blocked). Battery-override latching semantics exact per brand. BMS cell/temp frame
offsets exact. Log frames (subtypes 0/4/32/33) exact — FreeWheel even fixes the
official app's entry-3 copy-paste bug. All other command bytes verified byte-for-byte
incl. password/lock (genPwdCmd layout + all action codes), CRC32, time sync. The
PWM-limit "+30" and stop-speed encodings confirmed as raw-percent/km-h passthrough
(the offsets seen in the app are slider-UI conversions, not wire encoding).

## Leaperkim vs Nosfet app diff (highlights)

1. Nosfet has NO LdAp command set (no merged dual writes, no Patton special case),
   no password/lock feature, no wheel-reported battery (always volToBattery), no
   embedded model DB (fetches `http://www.baat22.com:800/nfapp/appconfig.txt` — no
   static SOC tables, consistent with the blocked Aero capture item).
2. Subtype-8 layout truncated at byte 65 = brakePressureAlarm (Leaperkim: 65 =
   maxChargeVolBase, 66 = dynamic assist, 68 = accel limit, 69 = brake). FreeWheel's
   per-brand branch matches both.
3. Nosfet: no subtype-2 battery override, no cell-count byte, no lockState read,
   no 84-cell block, no subtype 35; time sync sent once (Leaperkim: twice).
4. Brake pressure: Nosfet cmd 0x1E value@25 min 90; Leaperkim 0x22 value@29 min 80.
   FreeWheel's split matches; shared 80..125 slider lets Nosfet users send 80-89
   (below official minimum, cosmetic).
5. FreeWheel dual-emits legacy-style settings to Nosfet wheels; the official Nosfet
   app single-emits everything (fold into F10-class behavior).
