# Verification Pack — HW Charger (Roger V4SC / Pidzoom HW170P)

A hardware bring-up plan for anyone with a **BLE-enabled EUC charger** (Roger V4SC or
Pidzoom HW170P). The full charger feature is implemented and committed but has **never run
against real hardware**. This pack confirms the connection path and telemetry, and gathers
the data to finish the unmapped fields.

- **Hardware:** Roger V4SC or Pidzoom HW170P (BLE service `0000FFE1`, characteristic
  `0000FFE2`, uses **INDICATION** not notification).
- **Status:** all 5 implementation phases + BLE discovery complete (commits `8eb158de` …
  `0e8cd4fb`); awaiting hardware.
- **Est. effort:** one charging session (~30–60 min) with FreeWheel connected.
- **Source:** `project_hw_charger_plan.md`.

> **Safety:** You're connecting to a live mains charger handling battery voltages. Don't
> open the charger, don't rely on the app to prevent an over-charge, and keep the physical
> charger controls as your safety fallback. If output enable/disable from the app behaves
> unexpectedly, unplug at the wall.

---

## Part 0 — Setup

1. **Install the FreeWheel build** we send (it has the Charger tab).
2. Power the charger, connect the battery/wheel as you normally would to charge.
3. In FreeWheel, open the **Charger** tab and scan — the charger should appear (filtered on
   `FFE1` service). Note whether it shows up at all.

---

## Part 1 — Connection & auth (the first unknowns)

| # | Check | Expected | Record |
|---|-------|----------|--------|
| 1 ⭐ | Charger appears in the Charger-tab scan | listed by name | |
| 2 ⭐ | Connects and starts showing **live status** (AC volts, DC volts, current, temp) | values update every ~1 s | |
| 3 ⭐ | Auth succeeds (if the charger is password-protected) | connects without an auth error | |
| 4 | Values look sane vs the charger's own display / the Roger app | within tolerance | |

**Why these are starred:** the protocol uses **BLE indication** (CCC `0x0200`), not the
notification path most wheels use — item 2 confirms FreeWheel actually enabled indications
on `FFE2`. And auth (item 3) sends a ~36-byte MD5 frame that must be **chunked into 20-byte
pieces with ~25 ms gaps**; item 3 confirms that chunking works on real hardware. If status
never populates, indications aren't being enabled; if it connects but auth fails, the
chunking/timing is the suspect.

---

## Part 2 — Live telemetry checklist

With a charge in progress, compare FreeWheel's Charger view against the charger's own
readout (or the official Roger app):

| Field | Reference | Expected | Record |
|-------|-----------|----------|--------|
| AC voltage | mains ~110/230 V | matches | |
| AC current | Roger app | matches | |
| DC (output) voltage | charger display | matches | |
| DC (output) current | charger display | matches | |
| Temperature(s) | Roger app | sane °C | |
| Output enable/disable | toggle in app | **wheel starts/stops charging** (note: Huawei-style inverted — 0 = ON) | |
| Battery % | — | **currently always 0 in FreeWheel** — expected, see below | |

---

## Part 3 — Capture request (finishes the unmapped fields)

Record a raw BLE capture (PacketLogger / HCI snoop) of a full session: **connect → auth →
a few minutes of charging → toggle output off/on → disconnect.** This one capture lets us:

- **Map the Setpoints frame (cmd `0x05`, 102 bytes)** — only voltage/current are decoded;
  ~91 bytes (power limit, two-stage settings, …) are unmapped.
- **Implement battery %** — the Roger app computes SOC from per-cell voltage via piecewise
  interpolation; the field exists in `ChargerState` but is always 0.
- **Implement accumulated Ah/Wh** — fields exist but need integration over the session;
  a real charging trace gives us the reference to validate against.

If you can, also note the charger's own displayed **final voltage, total Ah, and total Wh**
at the end — those anchor the energy-accumulation math.

---

## Part 4 — Return format

Send back the capture file plus:

```
Charger model:            Roger V4SC / Pidzoom HW170P
FreeWheel build:          ____________
Appeared in scan?         Y/N
Connected + live status?  Y/N   (if N: did it connect but stay blank? ____)
Auth required / succeeded? ____ / ____
Output on/off from app worked?  Y/N  (which value = ON? ____)
Field accuracy vs charger display:  AC __  DC-V __  DC-A __  temp __
End-of-session: voltage __ V, total __ Ah, __ Wh (from charger display)
Date:                     ____________
```

## What each result unblocks

| Result | Work it confirms/enables |
|--------|--------------------------|
| No live status after connect | fix indication enabling (set CCC `0x0200` on `FFE2` explicitly) |
| Auth fails | fix the 20-byte MTU chunking / 25 ms timing for the auth frame |
| Output toggle inverted/confused | confirm the Huawei-inverted output-enable semantics |
| Full charging capture | map the remaining Setpoints bytes; implement SOC + Ah/Wh |

These correspond to the "Post-Testing TODOs" in `project_hw_charger_plan.md`. Items 1–3
are the ones that would block the feature from working at all; the rest are enhancements
the capture lets us finish.
