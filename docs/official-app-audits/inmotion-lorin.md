# LorinDecoder vs official InMotion app (Blutter Dart AOT) — audit

Audited 2026-08-16 against `freewheel-data/euc-reference-apps/inmotion_out/` (Blutter
output of the official Flutter app; `asm/inmotion_protocol/lorin/` mirrors FreeWheel's
decoder nearly 1:1: `codec.dart`, `util.dart`, `lorin_message.dart`, `protocol.dart`,
per-model beans under `bean/euc/`).

**Recoverability:** class/method names, scale constants, and routing logic are
readable; byte-offset→field maps in the bean `fromBytes` parsers were initially
thought unrecoverable, but the Ghidra decompile pass (2026-08-18, see below) plus
cursor-tracking of the sequential parsers **did** recover the full offset tables.

> **Note on parser structure:** the official app reads each frame **sequentially
> through a cursor** (read field, advance by its width), not from a fixed-offset
> table like FreeWheel's `Layouts`. Absolute offsets below were reconstructed by
> tracking cursor advances in the Ghidra asm/pseudocode. Semantic field *names* are
> inferred from FreeWheel's known mapping + offset alignment (Dart slots are opaque);
> the *offsets, widths, and signedness* are asm-confirmed. Every gap below is a
> static-analysis candidate — confirm against a live capture before shipping a fix.

## Confirmed matches (T1 evidence)

- **Framing**: escape 0xA5, header 0xAA (`LorinEncoder.escape`, 0x128e640) — matches
  `LorinUnpacker.kt`.
- **Checksum selector**: `(flags & 0x30) == 0x10` → XOR ("even parity"),
  `== 0x20` → CRC16 (`util.dart` `isEvenParityCheck` 0x8dcbf0 / `isCRC16` 0x8dc540).
  FreeWheel's flags 0x11/0x14/0x16 all fall in the XOR class → `verifyAndParse`
  XOR is correct. FreeWheel implements only XOR; any 0x2X-flag frame family would be
  CRC16-guarded and silently rejected — no such frame in the current command set
  (completeness note, not a bug).
- **Response bit 0x80** masking convention confirmed (`genMsgIdentity`, 0x129c2c0).
- **Vehicle temperature** decode `signed byte + 80` confirmed — matches
  `decodeTemperature` (LorinDecoder.kt:1420).
- **Encryption** (AES `Cryptor`) is gated to specific message types (OTA/auth), not
  telemetry — consistent with FreeWheel's plaintext decode.

## Ghidra decompile pass (2026-08-18)

Static offsets were recovered after all — Ghidra headless decompiles the Dart AOT
`libapp.so` where raw Blutter asm couldn't. Recipe and gotchas are in the
`decoder-parity-audit` skill; the decompiled pseudocode for 12 parser functions is
saved at `ghidra-pseudocode/inmotion-lorin-parsers.txt` (with the Ghidra script).

### CONFIRMED GAP — BMS general-info temperatures are `signed int8 + 80`
`DefaultBMSGeneralInfoCompact::fromBytes` (@ file VA 0xf4acc8) decodes four
consecutive temperature fields as: read byte → `if (b > 0x7f) b -= 0x100` →
`b + 0x50` (i.e. **signed int8 + 80**, InMotion's standard temp bias — the same one
FreeWheel already uses correctly for vehicle temps in `decodeTemperature`).
FreeWheel's `processBmsStatus` (LorinDecoder.kt:620-624) reads BMS temps
**raw-unsigned** (`data[26] and 0xFF`), no sign-extension, no +80.

Example: a 25 °C sensor is encoded as byte 0xC9 (25 − 80 = −55). Official → −55 + 80
= 25 °C. FreeWheel → 201 °C. (For small positive bytes FreeWheel happens to look
saner, which is likely why this was never caught — but any normal reading is wrong.)

**Remaining nuance before a code fix:** the decompile confirms the *transform*
(signed+80) unambiguously, but the parser walks the payload with a running index, so
it does not by itself pin the absolute BLE byte offset. FreeWheel's data[26]/data[27]
offsets came from EUC World. Confirm the offset against a capture (or the friend's
symptom — a wildly-wrong battery temperature would be the tell) before shipping the
`signed+80` fix. The four real-time parsers (below) all use the same signed+80 temp
convention, corroborating the transform independently.

## Offset-table extraction (2026-08-18) — 4 parsers, cursor-tracked

Four parsers were decompiled and cursor-tracked to absolute offsets, then diffed
field-by-field against FreeWheel. Full per-field evidence citations are in the agent
reports; the actionable results:

### CANDIDATE BUG #1 (HIGH) — P6 battery level read 2 bytes too high
`DefaultP6RealTimeInfo::fromBytes` reads `uint16 @28` (mileage — MATCH) then
`uint16 @30`, then **skips offset 32** (cursor jumps 30→34, asm-confirmed:
`add x1,x0,#2; add x3,x1,#2`). FreeWheel's `Layouts.P6` (LorinDecoder.kt:873) reads
`batLevel @32` — the offset the official app never reads. The battery-consumption
field the official app actually uses is at **offset 30**. So P6 battery percentage is
almost certainly computed from the wrong 2 bytes. Highest-impact finding; the likely
explanation for "InMotion works but the numbers look off." Fix candidate:
`Layouts.P6.batLevel = 30` (keep `INVERTED_SHORT_DIV_100`). **Verify with a capture
first** — a known battery % on the wheel vs what FreeWheel shows.

### CANDIDATE BUG #2 (UNCERTAIN) — P6 modeString@74 / error@77 unverified
The P6 real-time telemetry parser (`DefaultP6RealTimeInfo`) consumes only bytes
0–69; mode/charge/error live in a **separate** parser (`DefaultP6RealTimeState`,
whose own cursor starts at 0). FreeWheel reads `modeString @74` and `error @77` from
the *same* real-time buffer (Layouts.P6:875). Not corroborated by the official
telemetry parser — either P6 delivers a combined Info+State frame FreeWheel slices by
absolute offset, or these offsets are speculative. Needs a live-capture check.

### CANDIDATE CONFLICT #3 (P6 settings) — standbyTime@30 / chargeLimit@32
`P6SettingsDefault::fromBytes` **reads and discards** the bytes at data[30] and
data[32] (asm-confirmed: `bytes2Uint8Le` with no following `StoreField`, unlike the
neighboring bytes that do store). FreeWheel's `parseSettingsP6` treats data[30] as
`standbyTime` and data[32] as `chargeLimit`. Either FreeWheel's offsets are off, or
these settings arrive via a different InMotion message. Re-check against captures.

### CANDIDATE CONFLICT #4 (P6 settings, UNCERTAIN) — autoHeadlight bit
FreeWheel reads `autoHeadlight` from data[46] bit3; the official parser **discards**
bit3 and instead stores bit2 and bit4. FreeWheel may be reading the wrong bit.

### MATCHES (reassuring — no change needed)
- **P6 real-time core**: voltage@0, current@2, speed@8, torque@12, pwm@14,
  batPower@16, motPower@18, pitch@20, roll@22, mileage@28, speedLimit@40, and all
  four temps (mosTemp@58, temp2@59, cpuTemp@62, imuTemp@63, each signed int8 + 80) —
  all MATCH offset and transform.
- **P6 settings core**: maxSpeed@9, speedAlarm@11, pwmTiltBackLimit@15, pwmAlarm1@17,
  pwmAlarm2@19, balanceAngle@21 (signed), pedalSensitivity@26/27, minTirePressure@39,
  logoLightBrightness@41, cutoutAngle@42, chargingCurrent 220V@44/110V@45, and most
  flag bits (mute, transportMode, autoScreenOff, DRL, autoLock, etc.) — MATCH.
- **V11 settings**: every field FreeWheel reads (maxSpeed@0, pedalTilt@2 signed,
  ride-mode@4, sensitivity@5/6, volume@7, brightness@17, and all flag bits) — MATCH.

### Non-correctness observations
- **Voltage signedness**: official reads voltage as signed int16; FreeWheel unsigned.
  Harmless at real pack voltages (fit in positive int16 range). Applies to P6 and the
  common base parser.
- **V11 flag-bit width**: official packs 1-bit flags at even bit positions (0,2,4,6);
  FreeWheel reads 2-bit fields (`& 0x03`). Boolean results agree because odd bits are
  unused — functionally correct, masks just wider than the wire format.
- **Completeness gaps**: both P6 and V11 settings parsers, and the P6 real-time
  parser, read several more fields than FreeWheel surfaces (e.g. V11: a u32@8, u16@12,
  and byte i+23 that FreeWheel's length guard can't reach; P6 settings: two leading
  u32s @1 and @5; P6 real-time: five additional temperatures @60/61/64/65/66). Offsets
  are certain; meanings are not recoverable from these parsers alone. Not bugs — just
  telemetry/settings the official app exposes and FreeWheel doesn't.
- The `DefaultCommonVehicleRealTimeInfo` base parser (80-byte generic structure) does
  not map onto any single FreeWheel `Layouts` entry; the concrete per-model parsers
  (P6, V11, …) are the ones that matter, and those are handled above.

## Open items
2. **0x84 = BMS serial routing** (the blocked P6 item): the request triad
   (real-time / cells / fixed-info+serial) structurally matches FreeWheel's
   0x81/0x82/0x84, but the literal sub-command bytes are built through trait objects
   and are not statically readable. Still needs a capture.
3. Settings (`parseSettingsV11/P6` etc.) and real-time `Layouts` offset tables
   remain verified only by the BLE captures they were derived from — the decompile
   does not provide a cheaper cross-check.
