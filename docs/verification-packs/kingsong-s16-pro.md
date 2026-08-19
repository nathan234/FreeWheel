# Verification Pack — Kingsong S16 Pro protocol captures

A capture-collection plan for anyone with access to a **Kingsong S16 Pro**. Core telemetry
already works; this pack gathers the specific BLE captures needed to finish decoding its
settings, BMS-health, and lock protocols, and to confirm one checksum fix.

- **Wheel:** Kingsong S16 Pro (BLE name `KS-S16P-____`, standard Kingsong service
  `0xFFE0` / characteristic `FFE1`).
- **What already works:** all core telemetry (0xA9/0xB9/0xF5/0xF6/0xC9), BMS cells
  (0xF1/0xF2 sub 0x00–0x06), identity frames. Verified against a 2026-03-19 capture.
- **What's missing:** meanings of several settings/BMS frames, and captures that
  correlate app actions with wire values.
- **Est. effort:** ~20 min of changing settings while capturing, plus a short ride.
- **Source of the gaps:** `project_s16pro_protocol_gaps.md`.

> **Safety:** The settings captures are done stationary. For the riding capture, a short
> low-speed stint in a safe open area is enough — no need for speed.

---

## Part 0 — Setup

1. **Install the official Kingsong app** and connect to the S16 Pro.
2. **Start a raw BLE capture** (this is the whole point — the FreeWheel app is not needed
   for capture; we want the *official* app's traffic):
   - **iOS:** pair the iPhone to a Mac, run Apple's **PacketLogger** (from *Additional
     Tools for Xcode*), record, then drive the official app. Export as text. This matches
     the format of the existing S16 captures.
   - **Android:** Developer Options → **Bluetooth HCI snoop log**, reproduce, pull
     `btsnoop_hci.log`.
3. Do **one capture per section below** (separate files are easier to analyze than one
   long recording), and note which app action you performed.

---

## Part 1 — Captures needed (each resolves a specific gap)

For each, start recording, perform the action **once**, stop, and label the file.

| # | Capture | Exact action in the KS app | Unblocks |
|---|---------|----------------------------|----------|
| C1 ⭐ | **Light-effect categories** | In Lighting Control, change **Equalizer mode**, then **Magic**, then **Torch**, then toggle **Night-light flashing** — one at a time | Maps the remaining `0x50/0x51/0x52` category bytes (we've only confirmed category `0x02` = light effects) |
| C2 ⭐ | **Riding session** | Ride ~30 s at low speed with the app connected | Confirms non-zero speed/current/PWM decode; the 2026-03 capture was all stationary |
| C3 ⭐ | **Lock / unlock** | Lock the wheel in the app, then unlock with the PIN | Decodes the `0x74/0x75` password protocol (and the `0xD1/0xD3` voltage-threshold responses) |
| C4 | **Charging session** | Plug in the charger with the app connected, capture ~1 min | Confirms `F3/F4` BMS-health field meanings (SOH? cycle count?) by watching them change |
| C5 | **Settings changes** | Change max speed, an alarm speed, pedal mode, brightness — one at a time | Correlates `0xE3/E4` / `0x50` TX values with specific settings |

**Also note (no capture needed):** the exact **firmware version** (Settings → About) and,
for C1, the **before/after UI values** of each setting you change (e.g. "Equalizer:
flowing water → breathing"). Those labels are what let us name the decoded bytes.

---

## Part 2 — The checksum fix (confirms itself from any capture)

The S16 Pro's `0xBB` name frame fails our simple byte-sum checksum
(`sum("KS-S16P-0249") = 721` vs frame value `0x2EFD`), triggering a harmless but noisy
name re-request every connection. **Any** capture from Part 1 that includes the initial
connection lets us test CRC-16 variants (CCITT / MODBUS) against the name bytes and pick
the right one — or gate the check by firmware version. No special action needed; just make
sure at least one capture includes the moment of connecting.

---

## Part 3 — Return format

Send back the labeled capture files, plus:

```
Firmware version:         ____________
Capture tool:             PacketLogger / HCI snoop
For each C1 change:       <setting>: <old UI value> -> <new UI value>
For C5 changes:           <setting>: <old> -> <new>
Riding capture (C2):      reached what speed? ______
Lock/unlock (C3):         PIN used (if willing): ______
Date:                     ____________
```

## What each capture unblocks

| Capture | Decoder work it enables |
|---------|-------------------------|
| C1 | `SetKingsongLightEffect` / category map for `0x50/0x51/0x52` beyond light effects |
| C2 | Confirms riding telemetry (no code change if it matches; a fix if it doesn't) |
| C3 | Kingsong lock/unlock via `0x74/0x75`; possibly charge/discharge voltage limits from `0xD1/0xD3` |
| C4 | `F3/F4` BMS-health parsing (SOH / cycles) into `SmartBms` |
| C5 | `0xE3/E4` config read; correlates settings writes to wire bytes |
| any (with connect) | `0xBB` checksum fix — stop the spurious name re-request |

These correspond to the "Captures Still Needed" list in `project_s16pro_protocol_gaps.md`.
The 0x50/0x51/0x52 and 0x97/0x4D protocols are already decoded from earlier captures; C1
just extends the category map, so partial results are still useful.
