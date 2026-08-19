# Verification Packs

Hand-off-able test plans for work that's **blocked on real hardware or captures**. Each
pack is self-contained enough to give to someone who owns the relevant wheel or charger —
no dev knowledge required — and produces exactly the data we need to unblock a fix or
feature. See the "verification packs" section of [`../../UI_BRAND_PARITY_PLAN.md`](../../UI_BRAND_PARITY_PLAN.md)
for the concept.

| Pack | Wheel / device | Blocked on | Unblocks |
|------|----------------|------------|----------|
| [inmotion-p6.md](inmotion-p6.md) | InMotion P6 | a P6 owner + BLE capture | 6 candidate decoder bugs (battery %, BMS temp, mode/error, 3 settings) |
| [veteran-soc-tables.md](veteran-soc-tables.md) | Nosfet Aero, Leaperkim Oryx | a full-discharge ride log | accurate battery-% SOC curves (both use a piecewise fallback today) |
| [kingsong-s16-pro.md](kingsong-s16-pro.md) | Kingsong S16 Pro | settings/riding/lock/charge captures | remaining settings + BMS-health protocols, name-checksum fix |
| [hw-charger.md](hw-charger.md) | Roger V4SC / Pidzoom HW170P | real charger hardware | first hardware bring-up; SOC, energy, Setpoints fields |

Each pack ends with a **result-to-fix mapping** so an ingested result lands as either "no
change needed" or a concrete decoder change plus the capture to justify it. Findings behind
these packs live in [`../official-app-audits/`](../official-app-audits/) and the project
memory.
