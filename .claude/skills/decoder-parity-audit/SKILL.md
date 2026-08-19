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
| LorinDecoder (InMotion V2) | `inmotion_out/` (Blutter Dart AOT output: `asm/`, `objs.txt`, `pp.txt`) | Dart AOT, not Java — names/constants/routing are readable; bean `fromBytes` byte offsets are NOT (register-passed ByteData accessor args). See escalation paths below |
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

## Blutter (Dart AOT) escalation paths

Flutter apps compile Dart straight to ARM64 — no bytecode, so method bodies don't
decompile like jadx output. What survives: class/method names, string literals,
immediate constants, call structure. What doesn't: byte-offset→field maps in
`fromBytes`-style parsers (offsets pass through registers into ByteData accessor
calls; field slots correlate to names only via `toString()` load order).
Escalate in this order:

1. **Targeted per-field asm trace** — feasible when a symptom names the field:
   find the class in `objs.txt`, locate the parse body in `asm/`, trace each
   `bl` call's `mov`-loaded offset argument, correlate `StoreField` slots with
   the `toString` field order. Budget one field at a time, never a sweep.
2. **Decompiler pseudocode (WORKS — Ghidra headless recipe below).** The
   decompiler's data-flow analysis renders offsets inline as C-like pseudocode,
   recovering what the raw asm hides. Confirmed 2026-08 on `libapp.so`.

   **Recipe** (all under `freewheel-data/euc-reference-apps/inmotion_out/`):
   - Binary: `inmotion_extracted/lib/arm64-v8a/libapp.so` (~19MB).
   - Symbols: `ida_script/addNames.py` — a flat list of
     `ida_funcs.add_func(start,end)` + `set_name(addr, "class::method_addr")`.
     Grep it for the target class to get each function's **start and end** VA.
     (These file VAs; Ghidra loads the `.so` at **image base 0x100000**, so the
     Ghidra address is `imageBase.add(fileVA)`, NOT `getNewAddress(fileVA)`.)
   - Ghidra 12.x needs **JDK 21+**; set `JAVA_HOME` to a Corretto/JBR 21 (a
     SDKMAN `current` pointing elsewhere breaks headless). Launcher:
     `/opt/homebrew/opt/ghidra/libexec/support/analyzeHeadless`.
   - Ghidra auto-analysis finds **0 functions** (Dart AOT has no standard
     prologues / no symbols) and loads `.text` **as data**. So a post-script
     must, per target: `clearListing(range)` (else disassembly errors with
     "requires a start which is an undefined code unit") → `DisassembleCommand`
     over the range → `CreateFunctionCmd(name, start, bodyAddrSet, USER_DEFINED)`
     with the body set from addNames' start/end → `DecompInterface.decompile`.
   - Two-pass is fastest: first run `-import ... -postScript X.java` (no
     `-deleteProject`) to analyze+save once, then iterate with
     `-process libapp.so -noanalysis -postScript X.java`.
   - Working scripts from the 2026-08 run: see the session scratchpad
     (`DecompileTargets.java`) — reproduce from this recipe if gone.

   **Reading Dart AOT pseudocode:** ignore the noise — `func_0x0140e634`/
   `func_0x0140fde0` are GC write/stack barriers, `func_0x009dcd48`/`...cde8`
   are int/double boxing, `+ 0x17` on a pointer is the Dart ByteData payload
   header offset (so `buf[uVar7 + 0x17]` = BLE byte `uVar7`). The arithmetic is
   what matters: `read byte; if (0x7f < b) b -= 0x100; b + 0x50` is a **signed
   int8 + 80** field (InMotion's temperature bias). Confirmed the BMS
   general-info temps decode this way — see `docs/official-app-audits/inmotion-lorin.md`.
3. **Dynamic hooks** — `inmotion_out/blutter_frida.js` + a device running the
   official app against a real or replayed wheel; hook ByteData accessors to
   dump offsets empirically.
4. **Pre-Flutter APK** — InMotion's app was native Java before the Flutter
   rewrite; an old APK decompiles cleanly with jadx and wire formats rarely
   change across app rewrites. Won't cover newer models (e.g. P6).

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
