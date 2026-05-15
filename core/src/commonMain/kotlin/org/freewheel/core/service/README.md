# Service Package Guide

## Purpose

`service/` is the session/runtime layer.

This package owns the live connection lifecycle and coordinates BLE, decoder
state, command dispatch, and app-facing flows.

Key anchor files:

- `WheelConnectionManager.kt`
- `WcmState.kt`
- `WheelEvent.kt`
- `ConnectionState.kt`
- `AutoConnectManager.kt`
- `BleManagerPort.kt`

## What Belongs Here

- connection lifecycle state
- reducer/event-loop orchestration
- decoder ownership for the active session
- command dispatch
- retry, reconnect, and keep-alive policies
- app-facing StateFlow surfaces

## What Should Not Belong Here

- manufacturer-specific packet parsing details that belong in `protocol/`
- UI-only presentation logic
- long-lived value-type definitions that belong in `domain/`

## Dependency Rule

`service/` is allowed to depend on:

- `ble/`
- `protocol/`
- `domain/`
- selected feature/support packages such as `logging/`
- `utils/`

App layers should generally come into this package through
`WheelConnectionManager` rather than reaching down into decoder internals.

## Reading Tip

If you want to understand a user-visible action, read this package as the
middle layer between app code and protocol code:

`WheelViewModel` or Swift bridge -> `WheelConnectionManager` -> decoder/ble
