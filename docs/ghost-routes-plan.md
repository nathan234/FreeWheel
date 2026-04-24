# Saved Routes, Ride Sharing, and Ghost Replay — Plan

## Goals

Turn the live-ride Map tab into a social surface:

1. **Saved routes** — import/export rides, list them, view a saved ride on the map (speed-colored polyline, chart-map scrubbing).
2. **Ride sharing** — AirDrop / Messages / email a ride to a friend; opening the file in FreeWheel imports it into their saved routes.
3. **Ghost replay** — ride a friend's route while their dot moves along the track at their original pace (or at matched distance), with their telemetry visible. Think "ghost lap" in Mario Kart, applied to EUC rides.

All three are unlocked by one format decision.

## The Share Artifact: GPX 1.1 with FreeWheel Extensions

**Use plain `.gpx`.** Put telemetry in `<extensions>` under each `<trkpt>`, under a namespaced element tree (`xmlns:fw="https://freewheel.app/gpx/v1"`). This is how Garmin / Wahoo / Coros / SRM store cadence / heart rate / power today — we're following the paved path.

Why GPX-with-extensions over a custom zipped bundle:

- **One file, no zip handling.** KMP has no good pure-KMP zip library; expect/actual on `java.util.zip` + iOS compression APIs is avoidable work.
- **Interoperable.** Strava, Google Earth, Garmin Connect, RideWithGPS all import GPX and silently ignore unknown extensions. A friend on Strava gets value from your ride without installing FreeWheel.
- **Standard MIME / UTI.** iOS share sheets already route `application/gpx+xml` via UTType.gpx. No custom UTI, no entitlements.
- **Human-readable** for debugging.

### Bundle schema

```xml
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="FreeWheel 0.1"
     xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:fw="https://freewheel.app/gpx/v1">
  <metadata>
    <name>Evening loop</name>
    <time>2026-04-24T18:30:00Z</time>
    <fw:rideId>550e8400-e29b-41d4-a716-446655440000</fw:rideId>
    <fw:wheelType>KINGSONG</fw:wheelType>
    <fw:wheelName>KS-S22</fw:wheelName>
    <fw:wheelAddress>AA:BB:CC:DD:EE:FF</fw:wheelAddress>
    <fw:distanceMeters>12450</fw:distanceMeters>
    <fw:durationMs>2580000</fw:durationMs>
    <fw:appVersion>0.1.0</fw:appVersion>
    <fw:schemaVersion>1</fw:schemaVersion>
  </metadata>
  <trk>
    <name>Evening loop</name>
    <trkseg>
      <trkpt lat="37.7749" lon="-122.4194">
        <ele>82.3</ele>
        <time>2026-04-24T18:30:01Z</time>
        <extensions>
          <fw:speedKmh>32.5</fw:speedKmh>
          <fw:batteryPct>78.0</fw:batteryPct>
          <fw:pwmPct>42.0</fw:pwmPct>
          <fw:powerW>1230</fw:powerW>
          <fw:voltageV>83.4</fw:voltageV>
          <fw:currentA>14.8</fw:currentA>
          <fw:motorTempC>55</fw:motorTempC>
          <fw:boardTempC>48</fw:boardTempC>
        </extensions>
      </trkpt>
      <!-- ... -->
    </trkseg>
  </trk>
</gpx>
```

### Field rules

- **`fw:rideId`** is a UUID. Primary dedup key on import. Importing the same file twice refreshes; it never duplicates.
- **`fw:schemaVersion`** starts at `1`. Reader must gracefully degrade on unknown versions — unknown elements are ignored (GPX default), but a bumped major schema may require an app update.
- **Sample cadence**: 1 Hz for v1 (one `<trkpt>` per second). Good enough for ghost-chase UX, keeps files small (~50 KB per 10-minute ride).
- **Extension completeness**: all fields optional except `fw:speedKmh`. A minimal-telemetry import still renders the polyline and lets the ghost move.

## KMP Core Changes

New package: `core/src/commonMain/.../ride/`

| File | Responsibility |
|---|---|
| `RideBundle.kt` | Immutable value type: manifest fields + `List<RideSample>`. |
| `RideSample.kt` | `time`, `lat`, `lng`, `elevation?`, `speedKmh`, `batteryPct?`, `pwmPct?`, `powerW?`, `voltageV?`, `currentA?`, `motorTempC?`, `boardTempC?`. |
| `GpxWriter.kt` | `RideBundle → String`. Streaming XML, no DOM. |
| `GpxReader.kt` | `String → RideBundle?`. Lenient parser: unknown extensions → ignored; malformed file → null. |
| `RideGhostIndex.kt` | Prebuilt per-route lookup: cumulative-distance array → `RideSample` at that arc length. Enables O(log n) "what were they doing at distance X?" queries. |

**Platform-specific**: file I/O for reading/writing bundles to `Documents/` (or equivalent). Use the existing `TelemetryFileIO` expect/actual pattern.

### Tests (`core/src/commonTest/.../ride/`)

Following the test-first policy:

1. `GpxWriter` emits a parseable XML with all extensions for a 3-sample fixture bundle.
2. `GpxReader` round-trips the fixture exactly.
3. `GpxReader` handles a Strava-exported GPX (no fw: extensions) — returns a `RideBundle` with empty telemetry per sample.
4. `GpxReader` rejects malformed XML (returns null, doesn't throw).
5. `RideGhostIndex` returns the right sample for distance queries at boundaries (0, midpoint, end).
6. Dedup: two `GpxReader.parse(...)` calls on the same file produce bundles with identical `rideId`.

## Phase 1 — Saved Routes

**Data model.** Extend `TripDatabase` (Android Room) / `RideStore` (iOS) to persist imported rides alongside logged ones. Add a `source: RideSource` enum (`OWN_LOG`, `IMPORTED`) and a `rideId` UUID column. Existing rides get `OWN_LOG` + a generated UUID on migration.

**Storage.** Imported GPX files live in `Documents/rides/imported/<rideId>.gpx`. The DB row is the index, the file is the source of truth.

**UI entry points.**
- Trip detail already shows a speed-colored map — it's the render target for saved routes too. No new view, just a different data source.
- Rides list gets an "Import" button (top-right) → system file picker filtered to `.gpx` → `GpxReader.parse` → dedup by `rideId` → write to DB + filesystem.
- Each ride row gets a "Share" action → `GpxWriter.emit` → system share sheet.

**Dedup.** On import, if `rideId` already exists, prompt *"Replace, keep both, cancel?"*. Default to Replace (user just re-imported an updated version).

**Platform work:**
- Android: `ActivityResultContracts.OpenDocument` for picker, `Intent.ACTION_SEND` for share.
- iOS: `DocumentPicker` for import; `ActivityViewController` wrapping a `UIActivityViewController` for share. Register `com.freewheel.ride.gpx` UTI? No — stick to public.xml.gpx so Strava etc. can share to us too.

**Scope boundary for phase 1:** route list + import + export + render existing trip detail view. No ghost, no live overlay.

## Phase 2 — Ghost Replay

Built entirely on top of phase 1. Zero new format work.

**Data flow.**
1. User selects a saved ride from the Rides tab, taps "Ride as Ghost".
2. App navigates to the live Map tab with a ghost context.
3. `RideGhostIndex` is built once on entry (cumulative-distance array).
4. Two rendering modes (toggle in overlay):

**Mode A — Chase (default):** Ghost dot animates along their track at their original pace (ride start = press "Start chase"). Your dot shows your real position. Useful for "keep up with the faster rider" solo practice.

**Mode B — Match:** Ghost dot is always at *your* current arc-length along the route. You see their telemetry (speed / PWM / battery) at the same distance you're at. Useful for "how did they handle this hill compared to me".

**Visual layer:**
- Ghost polyline: speed-colored (same gradient as live trail), lower opacity or dashed.
- Ghost dot: distinct color (purple), slightly translucent.
- Overlay chip: "Ghost +3s ahead" / "You +1.2 km/h faster" derived from current index.

**KMP core additions:** `GhostState` (current arc-length, current sample, time offset) + updater that takes a `GpsLocation` and returns the latest `GhostState`. Pure function, fully testable.

**Scope boundary for phase 2:** one saved ride as ghost, Chase and Match modes. No multi-ghost, no leaderboards. Ghost selection happens from the Rides tab only — no "ride with friend from here" UX yet.

## Phasing & Gates

| Phase | Deliverable | Gate to next phase |
|---|---|---|
| P1.0 | KMP core: `RideBundle`, `GpxReader`/`GpxWriter`, tests | All tests green; round-trip fidelity on a 10-sample fixture |
| P1.1 | DB schema + migration (Android + iOS) | Existing rides still load; `source`+`rideId` present |
| P1.2 | Android import/share UI | Can import my own exported ride; can share to AirDropped iOS |
| P1.3 | iOS import/share UI | Cross-platform share works both directions |
| P2.0 | `RideGhostIndex` + `GhostState` | Distance queries correct to ±1 m on fixture |
| P2.1 | Ghost polyline + dot rendering (both platforms) | Renders a saved ride as ghost on Map tab |
| P2.2 | Chase vs Match mode toggle | Mode switch cleanly, no visual glitches |

## Open Questions (need answers before P1.0)

1. **Where does the ride-id live on existing rides?** Generate on migration (DB), or lazy-generate on first export? I'd say eager — one column, filled during migration, simpler queries.
2. **Trip detail on an imported ride** — does it show the same set of charts we show for logged rides, or a subset? Imported rides might only have speed + battery; power/PWM may be absent. Plan: charts gracefully hide when a field is null across all samples.
3. **What happens if an imported ride has no GPS (CSV-era logs)?** Reject the import, or accept and just not render the map? Reject.
4. **Sample rate on phase 2 ghosts**: 1 Hz telemetry may feel choppy when the ghost dot animates. Interpolate between samples, or accept 1 Hz stepping? I'd start with linear interpolation — it's cheap and reads smoother.
5. **Ghost file size at 1 Hz × 1 hour = 3600 trkpts** ≈ 200 KB GPX. Fine for AirDrop/Messages. No compression needed.
6. **Naming**: `.gpx` or introduce `.fwgpx`? Stick to `.gpx` — Strava interop is worth more than a branded extension.

## Not in scope (later)

- Community route discovery / server-backed sharing (phase 3+).
- Hazard / POI pins on shared routes (separate feature, same artifact — add `<wpt>` elements).
- Group-ride live presence (requires realtime infra).
- Leaderboards / segments / timing.

## Related

- `docs/claude-reference.md` — existing KMP orientation.
- `core/src/commonMain/.../logging/CsvParser.kt` — existing parser has `parseRoute()` already; source of truth for field conventions when mapping CSV → `RideBundle` for imports of own rides.
- `freewheel/src/main/.../data/TripRepository.kt` — where the new `source` / `rideId` columns land.
