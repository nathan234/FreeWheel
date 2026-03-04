# EUC Protocol Quality Assessment

An opinionated comparison of the eight manufacturer protocols implemented in
`core/protocol/`, based on what the decoder code reveals about each manufacturer's
firmware engineering. This is the perspective of someone who has read and worked on
every decoder in detail — not a product review. See also [decoder-parity.md](decoder-parity.md) for
legacy parity status and [reference-protocol.md](reference-protocol.md) for the open protocol spec
this assessment motivates.

## Brand / Protocol Relationships

Before the comparison, it helps to understand which brands share which protocols:

| Brand | Protocol Used | Decoder |
|---|---|---|
| Begode (Gotway) | Gotway BLE | `GotwayDecoder` |
| Extreme Bull | Gotway BLE (firmware prefix "JN") | `GotwayDecoder` |
| Veteran (Leaperkim) | Veteran BLE (legacy) | `VeteranDecoder` |
| Nosfet | Veteran BLE (model IDs 42/43) | `VeteranDecoder` |
| Leaperkim (newer FW) | CAN-over-BLE | `LeaperkimCanDecoder` |
| Kingsong | Kingsong BLE | `KingsongDecoder` |
| InMotion | InMotion V1 / V2 BLE | `InMotionDecoder` / `InMotionV2Decoder` |
| Ninebot | Ninebot / NinebotZ BLE | `NinebotDecoder` / `NinebotZDecoder` |

**Extreme Bull** is a rebrand — identical Gotway protocol, same frame format, same
decoder. The only difference is the firmware version string starts with "JN" instead
of "GW". From a protocol quality perspective, Extreme Bull inherits all of Begode's
problems.

**Nosfet** (Apex, Aero) uses the Veteran protocol with unique model IDs (42, 43) but
identical framing and telemetry layout. Same decoder, same protocol quality.

**Leaperkim CAN** is the interesting case — it's a genuinely different protocol from
the same company that makes Veteran wheels, and it's meaningfully better engineered.

## Summary

| Protocol | Quality | Notes |
|---|---|---|
| InMotion V2 | Best | Structured, versioned, richest feature set |
| Leaperkim CAN | Good | Checksums, escape framing, structured init, CAN IDs |
| Kingsong | Good | Clean framing, request/response model |
| NinebotZ | Decent | Complex but well-defined state machine |
| InMotion V1 | Decent | Authentication, proper framing |
| Ninebot | Decent | Structured with unpacker |
| Veteran | Mediocre | Improved Gotway roots, still basic |
| Gotway/Begode | Worst | No checksums, no versioning, undocumented fields |

## Gotway/Begode: What's Wrong

Begode's protocol is the most primitive of the seven. Every design shortcut that the
reference protocol spec was written to eliminate shows up here first.

### No checksums

Gotway frames have no CRC, no checksum, no integrity verification of any kind. The
decoder trusts every byte it receives. A single bit flip from BLE noise produces
silently corrupt telemetry. Every other manufacturer includes at least a basic checksum.

### No versioning

When Begode changed firmware behavior, they didn't add a version field or change the
frame format — they just changed what the same byte positions mean. This produced the
`gotwayNegative` config flag in the decoder:

- `0` = take absolute value of speed/current (old firmware)
- `1` = keep sign as-is (newer firmware)
- `-1` = invert sign (yet another firmware variant)

The app has no way to detect which behavior the wheel uses. The user has to know and
configure it manually. This is the kind of problem that a single version byte in the
frame header would eliminate.

### No self-description of battery configuration

Begode sells wheels with 16S, 20S, 24S, 30S, and 40S battery configurations. The
protocol doesn't include the series count anywhere. The `gotwayVoltage` decoder config
exists solely because the app has to guess the battery configuration from voltage
ranges to calculate battery percentage. Get it wrong and the battery indicator is
meaningless.

### Undocumented fields

Frame byte 17 contains beeper volume (0-9). Neither Begode's documentation, the legacy
FreeWheel codebase, nor any upstream community project had this documented. We discovered
it via BLE PacketLogger capture by comparing frames before and after volume writes. The
ATT summary view in PacketLogger truncates notifications to 16 bytes — the field only
appears in the full L2CAP/ACL hex dump, which may explain why it went unnoticed.

If a manufacturer's own protocol has undiscovered fields in a 20-byte frame that
thousands of users have been receiving for years, the documentation and specification
process is essentially nonexistent.

### The `useRatio` flag

At some point Begode changed the scaling factor for speed and distance calculations.
Rather than versioning the protocol, the decoder has a `useRatio` boolean that applies
a 0.875 multiplier. Again — the user has to know which firmware variant they have.

### No request/response model

Gotway wheels stream data continuously with no way for the app to request specific
information or control the data rate. You get everything, always, whether you need it
or not. Commands are fire-and-forget with no acknowledgment. Compare this to InMotion V2
where every command has a sequence-numbered response.

### Frame format is just raw bytes at fixed offsets

The entire protocol is "byte N means field X." No tags, no lengths, no field IDs. Every
firmware change risks breaking every app. This is exactly the anti-pattern that TLV
formats solve.

## What Other Manufacturers Do Better

### InMotion V2 (Best)

The InMotion V2 protocol is what a well-engineered EUC protocol looks like:

- **Structured commands with sub-types**: Command `0x02` (MAIN_INFO) uses `data[0]` as
  a sub-type selector (`0x01`=car type, `0x02`=serial, `0x06`=versions). Clean
  multiplexing within a command space.
- **Response correlation**: Response frames have the command byte OR'd with `0x80`
  (e.g., settings `0x20` → response `0xA0`). The app knows exactly which request each
  response corresponds to.
- **Escaping + checksum**: Proper frame integrity with byte escaping for reserved values.
- **Richest feature set**: 23 configurable features (fan control, DRL, tail light
  brightness, transport mode, fancier mode, go-home mode, etc.) — more than any other
  protocol. Each is a defined command with a defined response.
- **The V2 unpacker** is the most sophisticated: handles escape sequences, validates
  checksums, manages partial frame reassembly cleanly.

The gap between InMotion V2 and Gotway is enormous. V2 is a designed protocol; Gotway
is a raw memory dump over BLE.

### Kingsong (Good)

- **Header bytes** (`0xAA 0x55`): Clean frame delineation, handled internally without
  a separate unpacker.
- **Request/response for BMS**: The `0xA4` → `0x98` acknowledgment pattern shows at
  least some bidirectional protocol design.
- **Multiple alarm speeds**: Configurable per-speed alarms with a dedicated request
  command (`RequestAlarmSettings`).
- **Cleaner than Gotway** but still positional-offset based. No checksums either, but
  at least the frame boundaries are unambiguous.

### NinebotZ (Decent, with caveats)

- **14 ordered connection states**: This is simultaneously the most rigorous and most
  fragile init sequence. States must be traversed in exact order — skip one and the
  wheel stops responding. It's well-defined but brittle.
- **Rich command set**: Alarms, speed limits, pedal sensitivity, LED control, lock —
  close to InMotion V2's breadth.
- **BMS reading modes**: Conditional state transitions based on configuration. Shows
  awareness of optional features.
- The rigidity of the state machine is a double-edged sword — reliable when it works,
  catastrophic when anything deviates.

### Veteran (Mediocre)

Veteran forked from Gotway and improved incrementally:
- Internal framing (no separate unpacker needed)
- `WAITING_TIME` constant for frame reassembly timing — shows awareness of BLE timing
- But still fundamentally positional-offset, no checksums, no versioning
- The `currentTimeMillis()` usage for frame timing is a workaround for the lack of
  sequence numbers or frame IDs

Veteran is Gotway with slightly better engineering discipline. The foundation is the same.

### Leaperkim CAN (Good — a notable upgrade)

The most interesting data point in the landscape. Leaperkim makes the Veteran wheels
(Sherman, Patton, Lynx, Abrams) which use the mediocre Veteran BLE protocol above. But
their newer CAN-over-BLE protocol (`LeaperkimCanDecoder`) is a significant step up:

- **Checksums**: Sum of payload bytes mod 256, verified on receive. First Leaperkim
  protocol with any integrity checking.
- **CAN IDs as message types**: Each message has a 4-byte CAN ID (e.g., `0x0F58B703`
  for READ_VALUES, `0x0F58B704` for READ_STATUS). This is structured multiplexing —
  much cleaner than Gotway/Veteran's "guess the frame type from context."
- **Proper escape framing**: `0xA5` byte-stuffing with `AA AA` header and `55 55`
  trailer. Complete frame delineation.
- **Defined init handshake**: PASSWORD → INIT_COMM → INIT_STATUS → POLLING. Three
  steps, each waiting for a response. Not as complex as NinebotZ's 14 states, but
  properly sequenced.
- **Model ID table**: The status response includes a numeric model ID that maps to
  a comprehensive model name table (Sherman variants, Abrams variants, V-series, etc.).
  Self-identifying — the app doesn't have to guess.
- **Rich command set**: Headlight, handle button, pedal tilt/sensitivity, ride mode,
  max speed, speaker volume, transport mode, LED, lock, power off, horn — comparable
  breadth to InMotion V2.

What keeps it from "Best": still positional-offset payload parsing (byte N means
field X), no sequence numbers for request/response correlation, and the model ID table
is in the app rather than self-described by the wheel. But compared to the same
company's Veteran protocol, it's a generational improvement.

This progression — Gotway → Veteran → Leaperkim CAN — is the clearest evidence that
the manufacturer is capable of better engineering when they invest in it. The Veteran
protocol wasn't bad because Leaperkim can't do better; it was bad because they hadn't
prioritized it yet.

## What This Means

The protocol quality roughly tracks with company engineering culture:

- **InMotion** designs protocols like a software company — structured, versioned,
  feature-rich, with proper request/response semantics.
- **Leaperkim** is improving — their CAN protocol is a real step forward from the
  Veteran legacy, with checksums, structured framing, and self-identification. Still
  positional-offset, but the trajectory is positive.
- **Kingsong** and **Ninebot** are somewhere in the middle — structured enough to
  work reliably, with some bidirectional communication.
- **Begode** designs protocols like a hardware company that treats BLE as an
  afterthought — dump raw telemetry bytes over the air, ship it, figure out the
  rest later. When something needs to change, change it in place without versioning.
  Extreme Bull inherits this wholesale.

This matters beyond protocol aesthetics. A manufacturer that doesn't checksum their
BLE frames or version their protocol is likely applying the same engineering rigor to
their motor controllers, BMS safety logic, and firmware update process. The protocol
is a window into the firmware culture.

The reference protocol exists because the right answer isn't "use InMotion V2" — it's
"define an open standard that any manufacturer can implement, with the engineering
quality of the best existing protocols and none of the legacy baggage."

## Speculative: Engineering Cultures Behind the Protocols

*This section is opinion and inference. I've never worked at any of these companies.
But after spending significant time inside eight decoders, unpackers, and command
builders — tracing every byte offset, every state machine transition, every undocumented
field — the code tells a story about the organizations that produced it.*

### Begode: Move Fast, Document Nothing

Begode's protocol reads like it was written by a hardware engineer who needed telemetry
on a phone and knocked it out in an afternoon. Twenty bytes, fixed positions, raw values,
no framing overhead, no wasted cycles on checksums. It works. Ship it.

The problem is that this approach scales terribly. When the next firmware revision changes
the sign convention on speed, there's no version field to increment — because nobody
planned for a second revision when the first one was written. The `gotwayNegative` config
flag, the `useRatio` multiplier, the `gotwayVoltage` guessing game — these aren't design
decisions, they're scar tissue. Each one represents a firmware change that broke every
third-party app, followed by app developers reverse-engineering the fix.

This suggests an organization where firmware is written by the people building the
hardware, with no dedicated software team reviewing BLE protocol design. The person
writing motor control code is probably also the person who added the BLE telemetry
output, and they optimized for the thing they care about (motor performance) while
treating communication as a solved problem that doesn't need architecture.

Extreme Bull inheriting this protocol unchanged reinforces the picture. They're a
rebrand, not a fork — same firmware, same protocol, same "JN" prefix instead of "GW"
and that's the only difference the decoder can detect. There's no evidence of any
software investment beyond the label.

### Leaperkim/Veteran: Growing Into It

Leaperkim is the most interesting case because you can see the evolution happening in
real time across their protocol generations.

The Veteran protocol (Sherman, early models) is Gotway with guardrails. Internal framing
instead of a separate unpacker, timing-based frame reassembly, slightly cleaner structure.
It reads like the work of someone who understood Gotway's protocol well enough to see its
problems but didn't have the mandate or time to redesign from scratch. Incremental
improvement within the existing paradigm.

The CAN protocol (newer firmware) is a different animal. Checksums, escape framing, CAN
IDs as structured message types, a defined init handshake, a model ID table. This wasn't
evolved from Veteran — it was designed. Someone sat down and thought about framing,
integrity, and message multiplexing before writing code.

The gap between these two protocols from the same company suggests one of two things:
either they hired new firmware talent who brought proper protocol design experience, or
the original team learned from the pain of maintaining the Veteran protocol and invested
in doing it right the second time. Either way, the trajectory is encouraging. If the CAN
protocol had sequence numbers and self-describing fields, it would be competitive with
InMotion V2.

The fact that Nosfet wheels use the Veteran protocol (not CAN) suggests they're licensing
older Leaperkim hardware/firmware — they're getting the pre-improvement version.

### InMotion: The Software Company

InMotion's V2 protocol is what you'd expect from a team with embedded software
engineering discipline. Command/response correlation via the `0x80` bit is textbook
protocol design. Sub-type multiplexing within command codes is clean namespace
management. The breadth of configurable features (23 distinct commands for everything
from fan quiet mode to fancier mode to go-home mode) suggests a product team that
thinks about the app experience as part of the product, not an afterthought.

The V1 → V2 transition is also telling. V1 has authentication (password-based), proper
framing, and an unpacker — already better than Gotway. V2 didn't just add features to
V1; it redesigned the protocol with structured commands and response correlation. This
is what it looks like when a company treats protocol design as a first-class engineering
problem and invests in getting it right for the next generation.

The cynical read is that InMotion's protocol quality correlates with their more locked-
down ecosystem — they want tight control over the app experience, so they invest in the
protocol that enables it. The generous read is that they simply have better firmware
engineering practices. Both can be true simultaneously.

### Kingsong: Good Enough

Kingsong's protocol sits in a comfortable middle ground. Header bytes, frame type
identifiers, bidirectional communication for BMS data and alarm settings. No checksums,
but clean enough frame boundaries that corruption is rare in practice.

The `0xA4` → `0x98` acknowledgment pattern is interesting because it shows awareness
that some protocol interactions need to be bidirectional, but it's ad hoc — a specific
fix for a specific need rather than a general request/response framework. This reads
like pragmatic engineering: solve the problems you actually have, don't over-architect
for problems you don't.

Kingsong's protocol has been stable for a long time. Not because it's perfectly
designed, but because they've been conservative about changing it. In the EUC protocol
landscape, that's actually a virtue — predictability matters more than elegance when
third-party apps depend on your frame format.

### Ninebot: Enterprise Engineering (for Better and Worse)

Ninebot (Segway-Ninebot) is backed by a large corporation, and the protocol reflects
it. The NinebotZ 14-state init sequence is the most rigidly specified interaction of
any decoder. Every state must be traversed in exact order. BMS reading modes add
conditional branches. It's comprehensive, well-defined, and brittle.

This reads like a protocol designed by committee or by spec — every requirement is
addressed, every edge case has a defined state, but the resulting complexity makes
implementation fragile. One skipped state and the wheel stops responding entirely,
with no graceful degradation.

It's the enterprise approach to embedded protocol design: thorough, documented (at
least internally), but over-specified in ways that make third-party implementation
painful. The base Ninebot protocol is simpler and more forgiving — the Z variant
added complexity proportional to the wheel's capability without proportional gains
in robustness.

### What This All Suggests

The EUC industry is in a phase where hardware innovation massively outpaces software
maturity. Companies that can push 100+ km/h and 150V are still shipping BLE protocols
that would fail a sophomore networking class. This isn't because the engineers are bad
— it's because the competitive pressure is entirely on motor power, battery capacity,
and ride feel. The BLE protocol is invisible to 99% of buyers.

The companies that do invest in protocol quality (InMotion, Leaperkim's CAN) tend to
be the ones thinking about ecosystem lock-in or long-term platform plays. The ones
that don't (Begode, Extreme Bull) are competing purely on hardware specs and price.

This is exactly the gap the reference protocol is designed to fill. If good protocol
design were free — if manufacturers could drop in an open-source ESP32 firmware that
handles BLE communication properly — the competitive disadvantage of having a bad
protocol disappears. The question is whether any manufacturer has the incentive to
adopt it when their current approach, however crude, is already working well enough
to sell wheels.
