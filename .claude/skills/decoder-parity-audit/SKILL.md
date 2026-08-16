---
name: decoder-parity-audit
description: Audit a FreeWheel protocol decoder against the decompiled official manufacturer app to find semantic gaps (offsets, scaling, signedness, gating). Use when verifying or fixing decoder behavior for Veteran/Leaperkim, Nosfet, Begode/Gotway, Kingsong, or InMotion wheels.
---

# Decoder Parity Audit

Compare a decoder in `core/src/commonMain/.../protocol/` field-by-field against the
decompiled official manufacturer app. Official apps are the **source of truth**;
EUC World is the tiebreaker; legacy WheelLog is a last resort (it has its own bugs
and historical workarounds).

## Reference locations

All under `/Users/nathanretta/Code/freewheel-data/euc-reference-apps/`:

| Decoder | Primary reference | Notes |
|---|---|---|
| VeteranDecoder | `leaperkim/jadx_out/sources/com/laoniao/leaperkim/utils/BtManager.java` | Telemetry parse starts at the `staticBegin1/2/3` (DC 5A 5C) check. `Util.java` has SOC tables, model DB, CRC32 |
| VeteranDecoder (Nosfet) | `nosfet/jadx_out/sources/com/laoniao/...` | Rebadged Leaperkim app — same package, near-identical parsing. Diff against the Leaperkim copy to find Nosfet-specific behavior |
| GotwayDecoder | `begode/jadx_official_1.0.52/sources/` (official v1.0.52); also `begode/jadx_out/sources/` | Find the frame parser by searching for `55` `AA` header constants or the BLE characteristic UUID `0000ffe1` |
| KingsongDecoder | `kingsong/jadx_out/sources/` | App code likely under `cn/` — locate via frame-type bytes (0xA9 header, 0xB9/0xF5 frame types) or UUID `0000ffe1` |
| LorinDecoder (InMotion V2) | `inmotion_out/` (Blutter Dart AOT output: `asm/`, `objs.txt`, `pp.txt`) | Dart assembly, not Java — expect low yield; search `pp.txt`/`objs.txt` for symbol names first, bail out if only raw asm |
| Any (tiebreaker) | `eucworld/jadx_out_v2/sources/ug/e0.java` (Veteran), `.../ug/u.java` (Gotway) | Obfuscated field names; locate logic via byte offsets and scale constants |
| Any (secondary) | `darknessbot/` | Cross-check only |

Legacy WheelLog (last resort): `/Users/nathanretta/Code/euc/Wheellog.Android/app/src/main/java/com/cooper/wheellog/`.

## Method

1. **Find the parse site** in the reference app. jadx output is obfuscated —
   grep for protocol constants (frame headers like `DC 5A 5C` / `55 AA`, BLE UUIDs,
   scale factors like `10000.0f`, `/ 100.0f`, `65536`), not for field names.
2. **Build an offset table** from the reference: for each byte range → meaning,
   signedness (look for `> 32768` / `& 255` corrections), scale factor, and any
   gating conditions (version checks, nonzero checks, frame-length checks).
3. **Compare against the decoder** line-by-line: offsets, signed vs unsigned reads,
   scale factors, gates, and **derived values** (current/power computed from other
   fields — a wrong input propagates).
4. **Mind FreeWheel unit conventions**: speed/voltage/current/temp are internal
   `Int × 100`; distance in meters; `calculatedPwm` is a 0–1 fraction. Official apps
   use display units (km/h, V, A) — normalize before comparing. Config values:
   `rotationSpeed`/`rotationVoltage` in tenths, `powerFactor` in percent.
5. **Verify a suspected gap is real** before reporting: re-derive both sides with a
   concrete example frame and show the numeric divergence.
6. **Check the Rust port** (`rust/euc-protocols/src/`) for any confirmed gap —
   GotwayDecoder and VeteranDecoder are mirrored there and must stay in parity
   (iOS live shadow compares them).

## Known context — don't re-report

- `docs/decoder-parity.md` — per-decoder checklist of already-known legacy gaps.
- Blocked-on-hardware items (memory): Nosfet Aero/Oryx SOC tables, Kingsong S16 Pro
  settings/BMS, InMotion P6 flag bytes — these need captures, not code reading.
- Veteran PWM/current/power vs official apps: fixed in commit 77a0373b.

## Report format

One finding per gap: decoder `file:line`, reference `file:line`, what the official
app does, what FreeWheel does, concrete example showing the divergence, and severity
(user-visible telemetry error > settings mismatch > cosmetic). If a decoder matches,
say so explicitly per field group.
