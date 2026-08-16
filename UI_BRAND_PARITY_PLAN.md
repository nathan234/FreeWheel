# UI Brand Parity Plan

Design proposal for making FreeWheel's UI track the official manufacturer apps —
correct per wheel, with a clear wheel-settings/app-settings split, and a per-wheel
choice between a Brand-style and FreeWheel-style presentation.

Status: **proposal — not yet approved for implementation.**

## Decisions already made

1. **Per-wheel UI style choice: Brand vs FreeWheel.** Once connected (or as a
   preference), the rider picks between a Brand presentation that emulates the
   manufacturer's official app and the FreeWheel presentation. Default on first
   connect: FreeWheel style (tentative).
2. **Settings split: wheel settings live on the device screen; everything else in
   the Settings tab.** Anything written to the wheel is presented where the wheel
   is; per-wheel app config and global prefs live in Settings.
3. **Correctness is non-negotiable.** The settings surface offers *only* what is
   actually available for that manufacturer/model/firmware. Today we are wrong most
   of the time; the redesign is fail-closed.
4. Priority brands (where the current UI feels wrong): Veteran/Leaperkim/Nosfet,
   Kingsong, InMotion.

## Why the current UI is wrong

Three distinct failures, often conflated:

1. **Fail-open capability filtering.** `WheelSettingsConfig.sections()` renders the
   *entire* per-wheel-type superset whenever `CapabilitySet` is null or unresolved
   ("Show all controls until capabilities are resolved"). A rider connecting to a
   wheel we haven't fully identified sees every control we know for the brand,
   most of which their wheel ignores or misinterprets.
2. **WheelLog-derived supersets and vocabulary.** The sections/labels/ranges came
   from legacy WheelLog reverse engineering, not from the official apps. The
   Veteran PWM bug (fixed in 77a0373b) showed how that lineage drifts from what
   manufacturer firmware actually means. The decoder-parity audits (in progress,
   see `.claude/skills/decoder-parity-audit/`) are producing the official-app
   ground truth per brand.
3. **Taxonomy blur.** On-wheel settings (pedal mode, tiltback), per-wheel app
   config (SOC calibration, power factor, alarms), and global app prefs (units,
   theme) are mixed across screens. "PWM factor" sits near "Pedals Mode" with
   nothing telling the rider one is a local interpretation knob and the other
   writes to the wheel.

## Architecture: three independent layers

The style choice (Brand vs FreeWheel) must not fork the data model. Both styles
render from the same two lower layers.

### Layer 1 — Capability truth (fail-closed)

A per-model capability matrix, sourced from the official apps, replacing the
per-brand superset + fail-open filter:

- Each `SettingsCommandId` for a given (brand, model, firmware) is one of
  **Supported / Unsupported / Unknown**.
- **Unknown renders as absent.** No control appears until we affirmatively know
  the wheel supports it (from model identification + the decoder's
  `isSupportedAt`-style firmware gates). This inverts today's default.
- The matrix is data (extend `CapabilitySet` / `WheelSettingsConfig`), not code
  branches, and each entry cites its evidence tier:
  - **T1 — official app source**: behavior read directly from the decompiled
    manufacturer app (the parity audits produce these).
  - **T2 — hardware verified**: confirmed on a real wheel via a verification pack
    (below), ideally with a BLE capture attached.
  - **T3 — WheelLog/EUC World derived, unverified**: treated as Unknown → hidden.
    "It happened to work" is not evidence; controls at this tier are removed
    until promoted.
  Writes ideally need T2 (a wrong write is worse than a missing control); reads
  can ship at T1.
- Vocabulary is part of correctness: mode names, ranges, units, and step sizes
  come from the official app for that brand (e.g., whatever Leaperkim calls its
  ride modes is what we call them — in both styles).

### Layer 2 — Settings taxonomy

Three kinds of settings, two homes:

| Kind | Examples | Home |
|---|---|---|
| On-wheel | pedal mode, tiltback, PWM limit, lock, lights | Device screen (with the connected wheel) |
| Per-wheel app config | alarms, SOC calibration, power factor, profiles | Settings tab → "This wheel" section (per-MAC) |
| Global app | units, theme, logging, auto-connect | Settings tab |

Writes to the device screen go over BLE and read back; writes in Settings never
touch the wheel. That behavioral contract is the clarity the current UI lacks.

### Layer 3 — Style (Brand vs FreeWheel)

Two renderers over the same capability + taxonomy model:

- **FreeWheel style (default):** our design language, evolving toward the
  DarknessBot direction already identified (tile-first dashboard, quick actions
  in the app bar, color thresholds, cell-grid BMS).
- **Brand style:** emulates the official app's *organization, terminology, and
  information hierarchy* for the connected brand — Leaperkim's dashboard layout,
  cell-voltage grid and control panel for a Lynx; Kingsong's oversized speed
  number and quick-action grid; InMotion's model-tailored panes. We emulate
  structure and vocabulary, **not** logos, icons, fonts, or brand assets
  (trademark hygiene, and it keeps the skins maintainable).
- Choice is stored per wheel profile (per-MAC, `WheelProfileStore` /
  `DecoderConfigStore` sibling), offered on first successful connect, changeable
  later. FreeWheel chrome (tab bar, Rides, Map, Settings) is never skinned —
  the Brand style governs the device dashboard + wheel-settings pane only.
- Brands without a usable official reference (Begode's sparse app, Ninebot)
  simply don't offer a Brand style; the toggle is absent, not disabled.

## Verification packs (per-wheel, hand-off-able)

The capability matrix will always have holes we can't fill without the physical
wheel. For each such model, we produce a **verification pack**: a self-contained
test plan (`docs/verification-packs/<model>.md`) that a rider who owns that wheel
can execute without being a developer.

Each pack contains:

1. **Setup**: app build to install, how to enable BLE capture (iOS PacketLogger /
   Android HCI snoop / nRF Connect), safety notes (wheel on stand for anything
   that moves pedals or changes tiltback).
2. **Telemetry checklist**: for each field, a side-by-side observation — FreeWheel
   vs the wheel's own display and/or the official app — with expected agreement
   and a place to record actual values ("ride to ~30 km/h: does PWM roughly match
   the official app? does current look like tens of amps, not tenths?").
3. **Settings script**: for each control, exact steps — read the current value,
   change it in FreeWheel, observe the physical effect, cross-check in the
   official app, restore. Each result promotes that entry to T2 or files a bug.
4. **Capture requests**: the specific frames we need recorded (e.g. miles-mode
   frame 0x00 for Begode, S16 Pro settings frames, Nosfet Aero SOC curve points).
5. **Return format**: a fill-in results table they send back; we ingest it as
   matrix evidence with a date and firmware version.

The existing blocked items (Nosfet Aero/Oryx SOC, Kingsong S16 Pro, InMotion P6)
become the first packs, plus the NEEDS-CAPTURE items surfaced by the official-app
audits (Begode miles-mode speed semantics, frame 0x07 current sign). Packs are
also the regression net for decoder changes: re-run the telemetry checklist after
any decoder edit for that brand.

## Phasing (proposal)

- **Phase 0 — Ground truth.** Finish the official-app parity audits; encode
  results as the capability matrix + verified vocabulary per brand. No UI change.
- **Phase 1 — Fail-closed + taxonomy split.** Flip the Unknown default to hidden;
  move on-wheel settings to the device screen and per-wheel app config into
  Settings. This alone fixes "we are wrong most of the time," in FreeWheel style,
  on both platforms.
- **Phase 2 — Brand style: Veteran first.** Richest reference (Leaperkim +
  rebadged Nosfet app, fully decompiled), and the user's primary hardware for
  validation. Dashboard + wheel-settings pane.
- **Phase 3 — Brand styles: Kingsong, then InMotion.** InMotion last: the Blutter
  extraction is still resolving what the official app actually offers per model.

Each phase lands on Android and iOS together (existing cross-platform parity is
a core asset — both platforms already render settings from the shared config).

## Open questions

1. First-connect UX: silently default to FreeWheel style, or a one-time "How
   should this wheel look?" chooser with previews?
2. Does Brand style also restyle *telemetry semantics* (e.g., Leaperkim shows
   battery from its own SOC tables) or only presentation? Proposal: presentation
   only — telemetry values stay identical across styles.
3. Where do FreeWheel-only features (alarms, recording indicator) surface inside
   a Brand-style dashboard — hidden, or minimally overlaid?
4. ~~Is Phase 1's fail-closed flip acceptable before the matrix is fully
   populated?~~ **Resolved: yes, unconditionally.** Correctness wins; unverified
   controls are removed until proven (T1/T2 evidence). Verification packs are the
   path to restoring anything the flip hides.
