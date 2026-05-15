# Protocol Package Guide

## Purpose

`protocol/` turns raw wheel traffic into shared state updates and outbound
commands.

This package should answer:

- How do bytes become telemetry, identity, BMS, and settings updates?
- Which decoder handles a given wheel type?
- How are wheel commands encoded back onto the wire?

Key anchor files:

- `WheelDecoder.kt`
- `DefaultWheelDecoderFactory.kt`
- `AutoDetectDecoder.kt`

After those, read one concrete manufacturer decoder end-to-end.

## What Belongs Here

- decoder interfaces
- per-manufacturer decoders
- unpackers and frame reassembly helpers
- command encoding
- decode result and decoder state types

## What Should Not Belong Here

- connection lifecycle policy
- app-specific persistence
- UI state and presentation logic

## Dependency Rule

`protocol/` may depend on shared model/value types from `domain/` and helpers
from `utils/`.

It should not need to know about `WheelViewModel`, Room, SwiftUI, or other
platform/app orchestration details.

## Reading Tip

When debugging behavior, start at the entry point in `WheelDecoder.kt`, then
jump to the concrete decoder for the target wheel, then come back up to
`WheelConnectionManager` to see how decode results are consumed.
