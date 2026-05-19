# Kingsong BLE parity plan

## Context

A comparison between the official KingSong Android app and FreeWheel found that
our main gaps are not in the packet decoder itself, but in transport behavior:

- All writes currently go through `BleManager.write()` on both platforms with
  `WITHOUT_RESPONSE` and no wheel-specific pacing.
- `CommandScheduler` serializes command sequences, but it does not enforce BLE
  transport policy such as write spacing, retry, or transport-specific warmups.
- FreeWheel currently treats KingSong as having no keepalive.
- The official app has a separate KS-E1/E3 KSE transport using `AD00/AD01/AD02`.
- Android already exposes `onCharacteristicWrite`, but we do not consume it.
- iOS does not currently implement a write-completion callback path for
  `WITH_RESPONSE`.

The core conclusion is:

- Packet meaning stays in decoders.
- BLE transport behavior moves into a transport profile.

This plan adopts that split and sequences the work as small, bisectable
commits.

## What changes now

In scope:

1. A transport-profile layer for wheel-specific BLE behavior.
2. A BLE-ready / notify-ready event so post-connect traffic starts at the right
   time.
3. A `WriteCoordinator` separate from `CommandScheduler`.
4. KingSong classic parity work: paced writes, post-connect `0x5E`, optional
   recurring blank heartbeat.
5. KSE scaffolding with the correct `AD00/AD01/AD02` direction.
6. A command-execution state contract that future loading/success UI can build
   on.

Out of scope for now:

- Refactoring `writeChunked` into the new transport profile.
- Broad MTU/chunking work beyond adding a `requestMaxMtu` profile flag.
- Porting the CRC-backed KingSong command family until we have a concrete
  setting gap that requires it.
- Full Begode-style UI work in this document. We only land the producer-side
  contract that enables it.

## Design principles

### 1. Transport concerns do not live on the decoder

Write mode, pacing, retry, MTU requests, post-connect warmups, and keepalive
behavior are transport concerns. They should not be modeled as decoder
properties.

The decoder still owns:

- frame parsing
- protocol-specific command bytes
- decoder-driven keepalive for protocols that truly work that way

The transport profile owns:

- which BLE write mode to use
- whether MTU should be requested
- minimum spacing between writes
- retry policy
- transport-driven post-connect traffic
- transport-driven heartbeat behavior

### 2. `CommandScheduler` and `WriteCoordinator` solve different problems

`CommandScheduler` remains the semantic queue for ordered command sequences.

Examples:

- Gotway LED sequences
- decoder-emitted follow-up commands
- multi-step init blocks

`WriteCoordinator` is a separate layer underneath that queue. It enforces BLE
transport rules for each actual packet write:

- spacing
- retry
- write type
- transport timers

This separation keeps semantic command ordering stable while letting BLE policy
vary by wheel family.

### 3. Post-connect KingSong traffic must start from notify success

The official app starts its KingSong follow-up traffic from notify success, not
from "decoder init started". That matters because our current init queue runs
through `CommandScheduler`, and delayed commands inside that queue are
cumulative.

So:

- do not model the one-shot `0x5E` as just another delayed init command
- do not start the recurring KingSong blank heartbeat before BLE notify is
  actually active

Both belong on a transport-side timer started by an explicit BLE-ready event.

## Target architecture

### A. Transport profile on `WheelConnectionInfo`

Keep `WheelType` semantic and attach transport behavior to the connection info
that already owns UUID selection.

Conceptually:

```kotlin
enum class BleWriteType {
    WITHOUT_RESPONSE,
    WITH_RESPONSE,
}

data class RetryPolicy(
    val maxRetries: Int = 0,
    val retryBackoffMs: Long = 0,
)

sealed class KeepAlivePolicy {
    data object None : KeepAlivePolicy()
    data object DecoderDriven : KeepAlivePolicy()
    data class FixedFrame(val intervalMs: Long, val frame: ByteArray) : KeepAlivePolicy()
}

data class PostConnectWarmup(
    val delayMs: Long,
    val frame: ByteArray,
    val annotation: String = "",
)

data class WheelTransportProfile(
    val writeType: BleWriteType = BleWriteType.WITHOUT_RESPONSE,
    val requestMaxMtu: Boolean = true,
    val interWriteSpacingMs: Long = 0,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val keepAlivePolicy: KeepAlivePolicy = KeepAlivePolicy.DecoderDriven,
    val postConnectWarmups: List<PostConnectWarmup> = emptyList(),
)
```

Notes:

- `requestMaxMtu` stays in scope because the official app already differs here
  between classic KingSong and KSE.
- No chunking policy yet. `writeChunked` remains as-is for now.
- The default profile must remain byte-equivalent to current behavior.

### B. Richer BLE write contract

Replace a bare Boolean write contract with typed request/result objects.

Conceptually:

```kotlin
data class BleWriteRequest(
    val data: ByteArray,
    val writeType: BleWriteType,
    val annotation: String = "",
)

sealed class BleWriteResult {
    data class Submitted(val latencyMs: Long) : BleWriteResult()
    data class Completed(val latencyMs: Long) : BleWriteResult()
    data class Failed(val reason: String, val latencyMs: Long) : BleWriteResult()
}
```

Important distinction:

- `Submitted` means the OS accepted the write request.
- `Completed` means the BLE stack delivered a write-completion callback.
- Neither one alone means the wheel accepted the setting. That later "real
  confirmation" layer comes from decoder/readback state and is what the UI
  should eventually treat as success when available.

### C. New BLE-ready event

Add an explicit event fired when the platform BLE layer has successfully enabled
notifications for the configured read characteristic.

This event starts:

- transport warmup timers
- transport heartbeats

It should not be inferred from:

- service discovery finishing
- decoder init being scheduled
- UUID configuration having been requested

### D. Common `WriteCoordinator`

`WriteCoordinator` lives in common code so the behavior is testable without
depending on platform manager internals.

Responsibilities:

- accept `BleWriteRequest`
- enforce per-profile spacing
- retry failed writes
- call platform `BleManagerPort.write(...)`
- emit structured write results

Platform `BleManager` responsibilities become smaller:

- perform the actual write using the requested write type
- surface write-completion callbacks
- surface notify-ready

## Commit sequence

### Commit 1: BLE-ready and write-completion plumbing

Goal:

- make notify success and write completion observable before adding any new
  transport behavior

Changes:

1. Add a BLE-ready callback/event from both platform managers into
   `WheelConnectionManager`.
2. Android:
   - consume `onCharacteristicWrite`
   - surface completion when `WITH_RESPONSE` is used
3. iOS:
   - add `didWriteValueForCharacteristic`
   - keep existing `peripheralIsReadyToSendWriteWithoutResponse` behavior for
     no-response buffering
4. Add the corresponding WCM event/effect path for BLE-ready.
5. Add tests proving:
   - notify-ready arrives only after notifications are enabled
   - write completion events can be observed on both platforms

Why first:

- the moment we add KingSong post-connect traffic, notify readiness becomes
  load-bearing
- this commit reduces risk before any parity behavior ships

### Commit 2: Transport profile and `WriteCoordinator`

Goal:

- introduce wheel-specific write behavior without changing defaults

Changes:

1. Add `WheelTransportProfile` and attach it to `WheelConnectionInfo`.
2. Extend `BleManagerPort` to accept typed write requests and return typed
   write results.
3. Add common `WriteCoordinator` underneath `CommandScheduler`.
4. Keep `CommandScheduler` responsible only for semantic ordering.
5. Add `requestMaxMtu` handling through transport profile selection.
6. Leave `writeChunked` unchanged.
7. Add common tests for:
   - default profile remains current behavior
   - spacing is enforced
   - retries happen only when opted in

Initial defaults:

- all existing wheels stay on current behavior
- no profile should change behavior yet

### Commit 3: KingSong classic transport parity

Goal:

- add the official app's classic KingSong transport behavior in a controlled
  way

Changes:

1. Add a `KingsongClassic` transport profile:
   - `requestMaxMtu = true`
   - `interWriteSpacingMs = 50`
   - conservative retry policy
   - keep current write type unless capture evidence proves
     `WITH_RESPONSE` is required
2. Add a post-notify one-shot warmup:
   - `0x5E`
   - 2.5 s after BLE-ready
3. Add an optional recurring blank KingSong heartbeat:
   - classic only
   - feature-flagged OFF for first ship
4. Tag transport-generated packets in BLE capture so they are filterable.
5. Add tests proving:
   - one-shot timing is measured from BLE-ready
   - recurring heartbeat stops on disconnect
   - heartbeat does not block semantic command sequences

Important boundary:

- do not model the `0x5E` as a delayed init command
- do not assume the recurring blank is safe for every KingSong-family transport

### Commit 4: KSE transport scaffolding

Goal:

- support the alternate KS-E1/E3 KSE transport without borrowing classic
  assumptions

Changes:

1. Add KSE UUIDs with the correct direction:
   - service `AD00`
   - write `AD01`
   - notify/read `AD02`
2. Add `WheelConnectionInfo.forKingsongKse()`.
3. Extend topology/name detection so KSE resolves to KingSong with the KSE
   transport profile.
4. Keep the same decoder for now.
5. Add a distinct `KingsongKse` transport profile:
   - no classic warmup by default
   - no classic heartbeat by default
   - MTU behavior set from what we observed in the official app
6. Add unit coverage for topology selection and name-based detection.

Important boundary:

- do not assume classic `0x5E` or classic 1 Hz blank applies to KSE until we
  have a capture proving it

### Commit 5: command execution state contract

Goal:

- land the producer-side contract for later loading/success UI work

Changes:

1. Add a `CommandExecutionState` / `CommandTicket` model in common code.
2. Expose a WCM flow that reports command lifecycle transitions:
   - `Queued`
   - `Sent`
   - `WriteCompleted`
   - `Confirmed`
   - `TimedOut`
   - `Failed`
3. Map raw `BleWriteResult` into these higher-level command states.
4. Keep UI usage minimal for now.
5. Document which commands can reach `Confirmed` from decoder/readback evidence
   and which are write-only for now.

This commit is the handoff point for future Begode-style loading and success
feedback in Compose and SwiftUI.

## Why commit order matters

This order is deliberate:

1. observe notify-ready and write completion first
2. add transport infrastructure second
3. add classic KingSong parity behavior third
4. add KSE scaffolding fourth
5. expose generalized command state fifth

If we reverse that order, we risk adding KingSong post-connect traffic before we
can prove the BLE layer is ready for it.

## Testing strategy

### Common tests

- `WriteCoordinator` spacing
- retry behavior
- heartbeat start/stop timing
- warmup timing relative to BLE-ready
- command-state transitions for success, timeout, and failure

### Platform tests

- Android write completion callback wiring
- iOS write completion callback wiring
- notify-ready callback emission

### Hardware validation

Before enabling the recurring classic KingSong blank heartbeat by default:

1. validate on a currently-supported classic KingSong wheel
2. compare BLE captures against the official app
3. confirm no regressions in command responsiveness

KSE remains blocked on real hardware validation after scaffolding lands.

## Deferred work

### CRC-backed KingSong command family

Do not start until a concrete missing setting is identified.

When triggered:

- port only the command-builder pieces we need
- keep the fixed-footer path intact for commands already known to work

### Begode-style loading and success UX

This plan only lands the core execution-state contract.

Future UI work can then consume it to show:

- loading
- success
- timeout
- failure

without refactoring the transport path again.

## Remaining open questions

1. Whether classic KingSong should eventually switch to `WITH_RESPONSE`, or if
   official-app callback chaining is simply the library's default behavior.
2. Whether KSE has any protocol-level differences beyond transport wiring.
3. Which KingSong settings, if any, truly require the CRC-backed command
   family.
4. Whether later Begode analysis should add a non-default transport profile for
   Gotway/Begode at the same abstraction layer.

## Working rule

Ship the architecture first, then the parity behavior, then the UX contract.

That keeps the work easy to bisect and prevents us from solving the right
problem in the wrong layer.
