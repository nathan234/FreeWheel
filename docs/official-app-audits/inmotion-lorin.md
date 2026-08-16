# LorinDecoder vs official InMotion app (Blutter Dart AOT) — audit

Audited 2026-08-16 against `freewheel-data/euc-reference-apps/inmotion_out/` (Blutter
output of the official Flutter app; `asm/inmotion_protocol/lorin/` mirrors FreeWheel's
decoder nearly 1:1: `codec.dart`, `util.dart`, `lorin_message.dart`, `protocol.dart`,
per-model beans under `bean/euc/`).

**Recoverability:** class/method names, scale constants, and routing logic are
readable; exact byte-offset→field maps in the bean `fromBytes` parsers are NOT
(offsets pass through ByteData accessor calls in registers — per-field asm tracing
required). Settings and real-time layout offsets therefore could not be compared.

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

## Open items

1. **CANDIDATE GAP — P6 extended-BMS temperatures.** The app's BMS general-info
   compact parse applies `signed byte + 80` to three consecutive temp fields;
   FreeWheel's `processBmsStatus` (LorinDecoder.kt:620-624) reads BMS status temps
   raw-unsigned (a third convention besides vehicle `+80` and battery-summary `+40`).
   If BMS status temps are `signed+80`, wire 0xF0 should be 64 °C but FreeWheel
   yields 240. Not confirmed — needs either a targeted asm trace of
   `P6BMSGeneralInfo.fromBytes` or a hardware capture.
2. **0x84 = BMS serial routing** (the blocked P6 item): the request triad
   (real-time / cells / fixed-info+serial) structurally matches FreeWheel's
   0x81/0x82/0x84, but the literal sub-command bytes are built through trait objects
   and are not statically readable. Still needs a capture.
3. Settings (`parseSettingsV11/P6` etc.) and real-time `Layouts` offset tables
   remain verified only by the BLE captures they were derived from — the decompile
   does not provide a cheaper cross-check.
