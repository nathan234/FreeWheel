# Verification Pack — Veteran SOC tables (Nosfet Aero, Leaperkim Oryx)

A data-collection plan for owners of a **Nosfet Aero** or **Leaperkim Oryx**. Unlike a
bug-confirmation pack, this one gathers the real-world data we need to build an accurate
battery-percentage curve. Both wheels currently fall back to a piecewise-linear SOC
estimate because no official voltage→SOC table exists for them.

- **Targets:** Nosfet Aero (mVer 43, 126V / 2P — BLE name `NF____`, service `0xFFF0`) and
  Leaperkim Oryx (mVer 8, 176V / 42S). Same procedure; only the wheel differs.
- **What's already confirmed:** voltage, BMS cells, and BMS temps all decode correctly
  (verified against the official Nosfet app). **Only the SOC curve is missing.**
- **What we need from you:** one full-discharge ride log, so we can map resting/loaded
  voltage to the wheel's own displayed battery %.
- **Est. effort:** one normal ride from full charge to low battery, with logging on.
- **Source of the gap:** `leaperkim-todos.md`, `project_nosfet_aero_capture.md`.

> **Safety:** This is normal riding — no settings changes. Just don't chase the last few
> percent; stop while the wheel still has comfortable headroom. Riding an EUC to cutoff
> is dangerous.

---

## Why the wheel's own display is the ground truth

The manufacturer's firmware contains the real SOC curve, and it drives the **percentage
shown on the wheel's own screen / in the official app**. We can't read that curve
directly, but we can *reconstruct* it: log the raw voltage FreeWheel sees, alongside the
percentage the wheel itself reports, across a full discharge. That gives us voltage→%
pairs spanning the whole range, which we fit into a table.

---

## Part 0 — Setup

1. **Charge the wheel to 100%** and let it rest ~15 min (so the first reading is a true
   resting full-charge voltage).
2. **Install the FreeWheel build** we send, connect to the wheel, confirm live voltage.
3. **Turn on ride logging** in FreeWheel (the CSV recorder) — this timestamps voltage,
   current, speed, and FreeWheel's *estimated* battery % for the whole ride.
4. Have a way to note the **wheel's own displayed %** periodically (the app's headline
   battery number, or the wheel's screen). A few photos work.

---

## Part 1 — Discharge log (the main deliverable)

Ride normally from full to low battery with logging on. At roughly every **10% drop on
the wheel's own display** (100 → 90 → 80 … down to ~15–20%), record a checkpoint:

| Wheel's displayed % | FreeWheel voltage (V) | Riding or stopped? | Time / note |
|---------------------|-----------------------|--------------------|-------------|
| 100 (rested) | | stopped | start |
| 90 | | | |
| 80 | | | |
| … | | | |
| 20 | | | stop here |

Two things make the data much stronger:

- **A few rested readings.** At two or three checkpoints, stop and let the wheel sit ~2
  min, then note voltage — resting voltage maps to SOC far more cleanly than loaded
  voltage (which sags under throttle). Note which rows are rested vs riding.
- **Consistent riding.** A steady cruise gives a cleaner loaded curve than stop-and-go.

The CSV log captures the continuous voltage trace automatically; your checkpoint table
anchors it to the wheel's ground-truth %.

---

## Part 2 — Return format

Send back: (a) the FreeWheel **CSV ride log** file, (b) the checkpoint table above,
(c) any photos of the wheel/app battery % at checkpoints, and (d):

```
Wheel:               Nosfet Aero / Leaperkim Oryx
Firmware version:    ____________  (from the official app)
FreeWheel build:     ____________
Full-charge rested voltage:  ______ V
Lowest voltage logged:       ______ V  (at wheel-displayed __%)
Rested checkpoints (V @ %):  ____________________
Date:                ____________
```

## What this unblocks

A voltage→SOC table for the wheel, replacing the piecewise-linear fallback:

- **Nosfet Aero** → a dedicated `VeteranSocTables` entry for mVer 43 (126V/2P; its higher
  internal resistance vs the Patton 126V/4P is exactly why the shared curve is off under
  load).
- **Oryx** → a dedicated table for mVer 8 (176V/42S), which has no official table in the
  Leaperkim app.

One clean full-discharge log per wheel is enough to fit the curve. Two logs (e.g. summer
vs winter, or two riders) tightens it but isn't required.

## Alternative (no ride needed)

If an official table surfaces — a future Nosfet/Leaperkim app or firmware update that adds
these wheels — we can transcribe it directly instead. Worth checking app updates; the
Aero/Oryx tables are server-fetched in the Nosfet app (`appconfig.txt`), so a capture of
that config download would also contain the curve.
