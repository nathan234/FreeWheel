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
`signed+80` fix.

## Open items
2. **0x84 = BMS serial routing** (the blocked P6 item): the request triad
   (real-time / cells / fixed-info+serial) structurally matches FreeWheel's
   0x81/0x82/0x84, but the literal sub-command bytes are built through trait objects
   and are not statically readable. Still needs a capture.
3. Settings (`parseSettingsV11/P6` etc.) and real-time `Layouts` offset tables
   remain verified only by the BLE captures they were derived from — the decompile
   does not provide a cheaper cross-check.
