# Verification Pack — InMotion P6 (Lorin decoder)

A hand-off test plan for anyone who owns an InMotion P6. Executing it confirms or
refutes a set of candidate decoder bugs found by static analysis of the official
InMotion app, and produces the BLE captures we need to ship the fixes. You do **not**
need to be a developer — follow the steps and fill in the results table at the bottom.

- **Wheel:** InMotion P6 (BLE name `P6-xxxxxxxx`, service UUID `0xFFF0`).
- **Est. time:** ~30 min (plus ride time if you can safely ride).
- **What we're checking:** battery %, battery temperature, ride mode / error display,
  and three settings (standby time, charge limit, auto-headlight).
- **Source of the suspicions:** `docs/official-app-audits/inmotion-lorin.md`.

> **Safety:** For anything that changes tiltback, pedal tilt, or speed limits, put the
> wheel **on a stand with the tire clear of the ground**, or test at walking pace in a
> safe open area. Never change a ride-feel setting for the first time while riding.

---

## Part 0 — Setup

1. **Install the FreeWheel build** we send you (TestFlight for iOS, or the debug APK
   for Android). Connect it to your P6 and confirm it shows live telemetry.
2. **Keep the official InMotion app installed** on a second phone (or switch back and
   forth — only one app can hold the BLE connection at a time). We compare against it.
3. **Set up a raw BLE capture** (needed only for Part 3; Parts 1–2 are eyeball checks):
   - **iOS:** pair the iPhone to a Mac, install Apple's **PacketLogger** (part of the
     *Additional Tools for Xcode* bundle), press record, then use the app. This is the
     same tool the existing P6 captures came from. Export as text when done.
   - **Android:** enable **Developer Options → Enable Bluetooth HCI snoop log**,
     reproduce, then pull `btsnoop_hci.log` (or grab it via a bug report). Or use
     **nRF Connect** to log notifications from characteristic under service `0xFFF0`.

---

## Part 1 — Telemetry checklist (eyeball, no capture needed)

Connect FreeWheel to the P6. For each row, read the value in FreeWheel and compare to
the P6's own display and/or the official app. **The starred rows are the candidate
bugs — those are the whole point.**

| # | Field | How to read the reference | Expected if FreeWheel is correct | Record |
|---|-------|---------------------------|----------------------------------|--------|
| 1 | Voltage | official app / wheel screen | within ~1 V | |
| 2 | Speed | ride at a steady pace | matches GPS / official app | |
| 3 ⭐ | **Battery %** | official app battery %, at 2–3 different charge levels (e.g. full, ~half, low) | **within a few % of the official app at every level** | |
| 4 ⭐ | **Battery temperature** | official app battery temp (or just sanity) | **a sane °C (roughly 15–45 °C at rest); NOT ~150–255 °C and NOT stuck near 0** | |
| 5 ⭐ | **Ride mode / error text** | official app mode + any error banner | **same mode name; no phantom error; error appears only when the wheel actually faults** | |
| 6 | Mileage / trip | wheel screen | matches | |
| 7 | Temps (controller/motor) | official app if shown | sane °C, not wild | |
| 8 | Telemetry stability | just watch for ~30 s at idle | numbers **hold steady**, don't flicker to garbage and back | |

**Why these three are starred**

- **#3 Battery %** — Static analysis says the official app reads battery consumption
  from byte **30** of the 0x84 telemetry frame and *skips* byte 32, but FreeWheel reads
  byte **32**. If FreeWheel's battery % is consistently off from the official app (and
  the gap changes with charge level), that confirms the offset is wrong.
- **#4 Battery temperature** — The official BMS parser decodes temps as *signed byte +
  80*; FreeWheel reads BMS temps raw-unsigned. A raw read makes a normal ~25 °C sensor
  show as ~200 °C. If battery temp reads absurdly high (or pinned near 0), that
  confirms it.
- **#5 / #8 Mode & stability** — The P6 sends two frame types: `0x84` full telemetry
  and `0x87` keep-alive. FreeWheel may be feeding `0x87` through the full-telemetry
  layout, which would make fields flicker to nonsense whenever a keep-alive arrives.
  Also, mode/error are read from offsets 74/77, which we could not corroborate — a
  wrong mode name or a phantom error would flag it.

---

## Part 2 — Settings script (on a stand or at rest)

For each setting: **read** it in FreeWheel, compare to the official app; then **change**
it in the official app, reconnect FreeWheel, and see if FreeWheel's readback follows.
(We're validating what FreeWheel *reads back*, so changing in the official app and
reading in FreeWheel is the safe direction.)

| # | Setting | Steps | Expected | Record |
|---|---------|-------|----------|--------|
| S1 ⭐ | **Standby / auto-off time** | note official app value; does FreeWheel show the same number? | FreeWheel matches the official app | |
| S2 ⭐ | **Charge limit** | same | matches | |
| S3 ⭐ | **Auto-headlight** (headlight auto-on) | toggle it in the official app, reconnect FreeWheel | FreeWheel's auto-headlight toggle follows the change | |
| S4 | Max speed | read both | matches | |
| S5 | Speed alarm | read both | matches | |
| S6 | Pedal sensitivity / mode | read both | matches | |

**Why S1–S3 are starred:** the official settings parser appears to *read and discard*
the bytes FreeWheel uses for standby time (byte 30) and charge limit (byte 32), and
FreeWheel reads auto-headlight from bit 3 of a flag byte where the official app stores
bits 2/4. If S1/S2 show wrong or frozen values, or S3 doesn't track the official app,
those are confirmed.

---

## Part 3 — Capture requests (the payoff)

Raw BLE captures let us pin the exact byte offsets and ship fixes without guesswork.
Record each of these, and **write down the reference value at the moment of capture**
(a screenshot of the official app or the wheel screen at the same time is ideal).

1. **Full-telemetry frame at a known battery %** — connect, let it stream ~15 s while
   **stationary**, and note the official app's battery % and battery temperature at
   that moment. Repeat at **two more charge levels** (e.g. ~full, ~half, low) if you
   can. These pin battery-% offset (#3) and battery-temp encoding (#4).
2. **Idle stream ~30 s** — captures interleaved `0x84` and `0x87` frames so we can see
   whether keep-alives corrupt telemetry (#8).
3. **A settings read** — open the settings screen in the official app while capturing
   (this triggers the settings frame), and note the standby time, charge limit, and
   auto-headlight state shown. Pins S1–S3.
4. **(Optional) BMS / battery-info screen** — open the battery-detail screen in the
   official app while capturing; note the per-cell / temperature readings shown.

**Frame reference (for whoever ingests the capture):** P6 telemetry arrives as IM2
messages — `0x84` full telemetry (~85 bytes payload) and `0x87` keep-alive (~96 bytes,
lacks temps/limits/error). Settings arrive as the `0xA0`-class response. Feed the hex
into `core/.../replay/CaptureAnalyzer` (or the `ride-replay` web tool) alongside the
recorded reference values.

---

## Part 4 — Return format

Send back: (a) the filled tables above, (b) the exported capture file(s), (c) any
screenshots of the official app taken at capture time, and (d) the fields below.

```
P6 firmware version (from official app): ____________
FreeWheel build tested:                  ____________
Capture tool used:                       PacketLogger / HCI snoop / nRF Connect
Date:                                    ____________

Battery %:    FreeWheel ____  official ____  (at charge levels: __/__/__)
Battery temp: FreeWheel ____  official/plausible? ____
Ride mode:    FreeWheel ____  official ____   phantom error seen? Y/N
Idle stability: numbers held steady? Y/N   (if N, what flickered: ______)
Standby time: FreeWheel ____  official ____
Charge limit: FreeWheel ____  official ____
Auto-headlight tracks official change?   Y/N
```

## What each result unblocks

| Result | Fix it confirms |
|--------|-----------------|
| Battery % off, gap grows with charge level | `Layouts.P6.batLevel = 30` (currently 32) |
| Battery temp absurdly high / near 0 | BMS temps → `signed int8 + 80` in `processBmsStatus` |
| Telemetry flickers on idle | separate `0x87` keep-alive layout (or ignore 0x87) |
| Wrong mode / phantom error | correct `modeString`/`error` offsets (currently 74/77) |
| Standby / charge-limit wrong or frozen | re-derive P6 settings offsets 30/32 |
| Auto-headlight doesn't track | correct the flag bit (bit 3 → bit 2/4) |

All six correspond to `CANDIDATE`-tagged findings in
`docs/official-app-audits/inmotion-lorin.md`; a passing check means "no change needed",
a failing check plus the capture is enough to land the fix.
