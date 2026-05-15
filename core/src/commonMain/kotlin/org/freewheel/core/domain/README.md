# Domain Package Guide

## Purpose

`domain/` holds the shared vocabulary of the app.

This package should answer:

- What do we know about the wheel?
- What settings and profiles exist?
- What value types move between decoders, services, and apps?

Key anchor files:

- `TelemetryState.kt`
- `WheelIdentity.kt`
- `WheelType.kt`
- `WheelSettings.kt`
- `WheelSettingsConfig.kt`
- `ControlSpec.kt`

Suggested reading order inside `domain/`:

1. `TelemetryState.kt`
2. `WheelIdentity.kt`
3. `WheelType.kt`
4. `WheelSettings.kt`
5. `WheelSettingsConfig.kt`
6. `ControlSpec.kt`

## What Belongs Here

- immutable or mostly-value-like shared models
- settings/config descriptions
- profile and preference abstractions
- dashboard layout/config value types
- small enums and labels tied to shared business concepts

## What Should Not Belong Here

- BLE orchestration
- decoder event loops
- platform UI code
- persistence wiring that only exists to satisfy one app layer

## Intended Future Split

This package is currently too broad. The intended sub-areas are:

- `identity`
- `telemetry`
- `settings`
- `profile`
- `dashboard`
- `common`
- `events`

The existing `wheel/` catalog area is intended to become
`identity/wheel/` during the package cleanup rather than being flattened.

See `docs/core-structure-plan.md` for the staged move plan.

## Dependency Rule

`domain/` should avoid depending on `service/`.

Lightweight dependence on `utils/` is fine when it supports formatting or simple
shared helpers, but `domain/` should not turn into a back door for service or
protocol behavior.
