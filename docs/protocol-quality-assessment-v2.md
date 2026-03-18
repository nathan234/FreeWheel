# EUC Protocol Quality Assessment (v2)

An opinionated comparison of the eight manufacturer protocols implemented in
`core/protocol/`, based on what the decoder code reveals about each manufacturer's
firmware engineering. Revised from v1 after studying multiple implementations of each
protocol and building a full decoder debugging infrastructure (replay engine, error
classification, unpacker counters, frame distribution analysis).

See also [decoder-parity.md](decoder-parity.md) for legacy parity status and
[reference-protocol.md](reference-protocol.md) for the open protocol spec this
assessment motivates.

## Brand / Protocol Relationships

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

**Leaperkim CAN** is a genuinely different protocol from the same company that makes
Veteran wheels, and it's meaningfully better engineered.

## Summary

| Protocol | Quality | Integrity | Multiplexing | Key Strength |
|---|---|---|---|---|
| InMotion V2 | Best | XOR checksum | Command + sub-type | Response correlation, richest features |
| Leaperkim CAN | Good | Sum-mod-256 + escape | CAN IDs | Self-identifying models, defined init |
| Veteran | Good | CRC32 + byte validation | 5 sub-types + dual format | Strongest integrity of any protocol |
| Kingsong | Good | None | 22 frame types | Most multiplexed, stable over time |
| NinebotZ | Decent | CRC16 + XOR encryption | 14-state machine | Most rigorous init sequence |
| InMotion V1 | Decent | Checksum | Command-based | Authentication |
| Ninebot | Decent | CRC16 | Structured packets | Clean unpacker |
| Gotway/Begode | Worst | Footer bytes only | 7 frame types | Nothing |

### v1 → v2 Changes

The biggest revision is **Veteran: "Mediocre" → "Good."** The v1 assessment described
Veteran as "Gotway with slightly better engineering discipline" and claimed it had no
checksums. Both were wrong. After studying the official Leaperkim app and comparing
across multiple implementations, the Veteran protocol is substantially more
capable than initially assessed:

- **CRC32 validation** in the unpacker for new-format frames — stronger integrity
  checking than InMotion V2 (XOR), Kingsong (none), or Ninebot (CRC16)
- **Structured byte pattern validation** at specific offsets before accepting frames
- **5 sub-types** (0, 2, 4, 5, 8) with dedicated parsers — structured multiplexing,
  not flat positional data
- **Dual command formats** (LkAp legacy + LdAp new) showing active protocol evolution
- **19 configurable settings** in sub-type 8 alone, with a 0x80 "not supported" sentinel
- **Time sync**, **event log download** (sub-types 32/33/35), **BMS pack currents** —
  more protocol depth than initially visible

The v1 assessment was written before we'd studied other implementations and discovered
how much of the protocol surface area we hadn't mapped yet. What looked like a thin
positional format turned out to have real structure underneath.

## Gotway/Begode: Still the Worst

Begode's protocol remains the most primitive. Every design shortcut that the reference
protocol spec was written to eliminate shows up here.

### No real integrity checking

Gotway frames have a 4-byte footer (`5A 5A 5A 5A`) that serves as a frame delimiter, and
the unpacker has garbage pattern recovery for when frames get corrupted. But there's no
CRC, no checksum, no integrity verification of the payload. A single bit flip in the
telemetry bytes produces silently corrupt data. The footer catches gross corruption
(missing or mangled frames) but not subtle corruption (wrong speed value, wrong voltage).

### No versioning

When Begode changed firmware behavior, they didn't add a version field — they just
changed what the same byte positions mean. This produced the `gotwayNegative` config
flag:

- `0` = take absolute value of speed/current (old firmware)
- `1` = keep sign as-is (newer firmware)
- `-1` = invert sign (yet another firmware variant)

The app has no way to detect which behavior the wheel uses. The user has to know and
configure it manually. A single version byte in the frame header would eliminate this.

### No self-description

Begode sells wheels with 16S, 20S, 24S, 30S, and 40S battery configurations. The
protocol doesn't include the series count. The `gotwayVoltage` decoder config exists
because the app has to guess battery configuration from voltage ranges. Get it wrong
and the battery indicator is meaningless.

Other apps work around this with per-model calibration databases — regex matching wheel
names and firmware strings to the correct configuration. That's the app compensating for
the protocol's failure to self-identify.

### Undocumented fields

Frame byte 17 contains beeper volume (0-9). We discovered this via BLE PacketLogger
capture — the ATT summary truncates notifications to 16 bytes, hiding the field. It
only appears in the full L2CAP/ACL hex dump. If a 20-byte frame has undiscovered fields
after years of community use, the documentation process is nonexistent.

### The `useRatio` flag

Begode changed speed/distance scaling factors without versioning. The decoder has a
`useRatio` boolean that applies a 0.875 multiplier. The user has to know which firmware
variant they have.

### Frame 0x07 field packing

EUC World revealed that byte 5 of frame 0x07 is actually two values packed into one
byte: lateral tilt angle (lower 4 bits) and field weakening (upper 4 bits). This kind
of bit-packing without documentation is characteristic of a protocol that was never
designed to be implemented by anyone outside the original firmware team.

### No request/response model

Gotway wheels stream data continuously with no way to request specific information.
Commands are fire-and-forget with no acknowledgment. The decoder has an `infoAttempt`
counter that retries requests up to 50 times with no way to know if any were received.

## What Other Manufacturers Do Better

### InMotion V2 (Best)

InMotion V2 is what a well-engineered EUC protocol looks like:

- **Structured commands with sub-types**: Command `0x02` (MAIN_INFO) uses `data[0]` as
  a sub-type selector (`0x01`=car type, `0x02`=serial, `0x06`=versions). Clean
  multiplexing within a command space.
- **Response correlation**: Response frames have the command byte OR'd with `0x80`
  (e.g., settings `0x20` → response `0xA0`). The app knows which request each response
  corresponds to.
- **11+ command handlers** across initial and default flag protocols, including P6
  extended protocol with BMS IDs (`0x24`-`0x27`).
- **Richest feature set**: Fan control, DRL, tail light brightness, transport mode,
  fancier mode, go-home mode — more configurable features than any other protocol.
- **The V2 unpacker** handles escape sequences (`0xA5` byte-stuffing), frame reassembly,
  and passes data through for XOR checksum validation in the decoder.

The V1 → V2 transition shows intentional redesign. V1 already had authentication and
proper framing. V2 didn't just add features — it restructured the protocol with command
multiplexing and response correlation. This is a company that treats protocol design as
a first-class engineering problem.

What keeps InMotion V2 from perfect: XOR is a weak checksum (single-bit errors in
paired positions cancel out), and the escape byte handling adds complexity that CRC32
would avoid. But the protocol *design* — the command taxonomy, the response correlation,
the feature breadth — is unmatched.

### Veteran (Good — significantly underrated in v1)

The biggest correction from v1. After studying the official Leaperkim app and comparing
across three implementations (Leaperkim, other third-party apps, FreeWheel), Veteran is
a genuinely good protocol, not "Gotway with guardrails."

**CRC32 integrity checking**: The VeteranUnpacker validates new-format frames with
CRC32 — the strongest integrity check of any EUC protocol. It also validates specific
byte patterns at offsets 22, 23, and 30 before accepting a frame. This is more robust
than InMotion V2's XOR checksum.

**Sub-type multiplexing**: Five sub-types (0, 2, 4, 5, 8) dispatched via `when (pNum)`,
each with dedicated parsing logic:

| Sub-type | Data | Fields |
|----------|------|--------|
| 0 | Telemetry extension | Roll angle, BMS pack currents (L/R) |
| 2 | Configuration | Fall protection angle, battery SOC, cell count |
| 4 | Telemetry alt | Roll angle (alternative format) |
| 5 | Security | Lock state |
| 8 | Settings | 19 configurable fields with 0x80 sentinel |

This is structured multiplexing — closer to InMotion V2's command system than to
Gotway's flat format.

**Dual command formats**: LkAp (legacy, `0x4C 0x6B 0x41 0x70`) and LdAp (new,
`0x4C 0x64 0x41 0x70`) with an additional byte6 parameter for control toggles. The
official app sends both formats for maximum compatibility. This shows active protocol
evolution without breaking backward compatibility.

**0x80 "not supported" sentinel**: Sub-type 8 settings fields use `0x80` to indicate
"this wheel doesn't support this setting." This is primitive capability negotiation —
the wheel tells the app what it can and can't configure. Neither Gotway nor Kingsong
has anything equivalent.

**Where Veteran still falls short**: Still positional-offset (byte N means field X),
no sequence numbers for request/response correlation, no self-describing field IDs.
The sub-type system helps, but it's still fragile to firmware changes within a
sub-type. And the fact that different implementations of the Veteran protocol interpret
the same bytes differently (voltageCorrection encoding, stopSpeed offsets, field names
for bytes 58, 60, and 69-72) shows that positional formats without documentation breed
divergence. When three apps assign three different labels to the same byte, the protocol
has a documentation problem even if the engineering is sound.

### Kingsong (Good)

- **22 distinct frame types**: The most multiplexed protocol — more frame types than
  any other, each with a dedicated handler. Frame type byte at position 16 provides
  clean dispatch.
- **Request/response for BMS**: The `0xA4` → `0x98` acknowledgment pattern.
- **Multiple alarm speeds**: Configurable per-speed alarms with dedicated request
  commands.
- **Password-protected lock**: More security than most protocols.
- **Stable over time**: Kingsong has been conservative about protocol changes, which
  is a virtue when third-party apps depend on the frame format.

**No checksums**: This is the notable gap. Kingsong has clean frame boundaries (`AA 55`
header) and extensive multiplexing, but zero integrity checking on the payload. For a
protocol with 22 frame types and some of the best structure, the absence of checksums
is conspicuous. It's "good enough" in practice — BLE's own CRC catches most corruption
— but it means the decoder trusts every byte unconditionally.

### Leaperkim CAN (Good)

The most interesting protocol because it shows what the Veteran team built when they
started fresh:

- **Sum-mod-256 checksums**: First Leaperkim protocol with integrity checking (though
  weaker than their own Veteran CRC32 — an odd regression).
- **CAN IDs as message types**: 10 distinct CAN IDs for different operations. Structured
  multiplexing at the transport layer.
- **Proper escape framing**: `0xA5` byte-stuffing with `AA AA` / `55 55` delimiters.
- **Defined 3-step init handshake**: PASSWORD → INIT_COMM → INIT_STATUS, each waiting
  for a response before proceeding.
- **Model ID table**: Status response includes a numeric ID mapping to specific wheel
  models. Self-identifying.
- **Multi-command multiplexing**: `CAN_WRITE_MULTI` uses sub-types (3=lock_on, 4=lock_off,
  5=power_off, 0x11=horn, etc.) for operations that share a CAN ID.

**Notable**: Neither EUC World 2.62.0 nor the official Leaperkim app v1.4.8 has CAN-over-BLE
support. FreeWheel is the only known app implementing this protocol. This means our
implementation is unverifiable against other apps — it works with real hardware, but
there's no reference implementation to cross-check against.

**The Veteran → CAN progression**: The CAN protocol has better framing and self-identification
but weaker integrity checking (sum-mod-256 vs CRC32). This suggests different teams or
different priorities — the CAN protocol invested in structure and transport, while the
Veteran protocol invested in validation and error detection. Neither has everything.

### NinebotZ (Decent, with caveats)

- **14 ordered connection states**: The most rigorous init sequence. States must be
  traversed in exact order — skip one and the wheel stops responding.
- **CRC16 + XOR encryption**: Payload encryption is unique among EUC protocols.
- **Conditional BMS states**: State machine branches based on `bmsReadingMode` config,
  showing awareness of optional features.
- **Rich command set**: Alarms, speed limits, pedal sensitivity, LED control, lock.

The rigidity is a double-edged sword. It's reliable when everything follows the spec,
catastrophic when anything deviates. No graceful degradation — a single missed state
transition means full connection failure with no recovery path.

### Ninebot (Decent)

- **CRC16 in decoder**: Proper integrity checking.
- **Structured packets**: Length-prefixed, header-delimited.
- **Simple unpacker**: State machine for reassembly, clean handoff to decoder.
- Simpler and more forgiving than NinebotZ.

## Unpacker Complexity Hierarchy

The unpackers reveal a lot about protocol engineering because they handle the messiest
part of BLE communication: reassembling variable-length frames from a stream of
20-byte notifications.

| Unpacker | Integrity | Escape Handling | Error Recovery | Sophistication |
|----------|-----------|-----------------|----------------|----------------|
| VeteranUnpacker | CRC32 + byte patterns | No | Error counters, reset tracking | Highest |
| LeaperkimCanUnpacker | Checksum (in decoder) | 0xA5 dedup | State machine reset | High |
| InMotionUnpacker | Checksum (in decoder) | 0xA5 dedup | Error counters, extended length | High |
| InMotionV2Unpacker | XOR (in decoder) | 0xA5 dedup | Basic reset | Medium |
| GotwayUnpacker | Footer validation | No | Garbage pattern recovery, error counters | Medium |
| NinebotZUnpacker | CRC16 (in decoder) | No | Basic reset | Low |
| NinebotUnpacker | CRC16 (in decoder) | No | Basic reset | Low |
| Kingsong | Internal (no unpacker) | No | Header sync | Low |

The VeteranUnpacker doing CRC32 in the unpacker itself (not just passing to the decoder)
is notable — it rejects bad frames before they reach the decoder, which means the decoder
never has to worry about corrupt data. Most other protocols defer integrity checking to
the decoder, which means corrupt frames consume decoder processing before being rejected.

## Where My Assessment Changed (and Why)

Before getting into the cultural speculation, it's worth being explicit about what I got
wrong in v1 and what changed my mind. The v1 assessment was written from reading the
decoders in isolation. v2 comes after studying multiple implementations of each protocol,
building a capture replay engine that exercises every decoder end-to-end, and adding error
classification and unpacker counters that reveal where each protocol actually fails in
practice.

### The Veteran Surprise

In v1, I dismissed Veteran as "Gotway with slightly better engineering discipline" and
claimed it had "no checksums, no versioning." I was flatly wrong about the checksums —
the VeteranUnpacker has CRC32 validation, which is *stronger* than what InMotion V2
uses. That's not a minor detail. CRC32 catches multi-bit errors that XOR misses entirely.
The protocol I ranked second-worst has the best integrity checking of any EUC protocol.

How did I miss it? The CRC32 is in the unpacker, not the decoder. When I wrote v1, I was
focused on the decoder's `decode()` method — the frame parsing, the byte offsets, the
state mutations. The unpacker was just "the thing that reassembles frames." I didn't look
hard enough at what happens *before* data reaches the decoder. Building the unpacker error
counters forced me to trace the full pipeline, and that's when the CRC32 jumped out.

The sub-type system was the other miss. From the decoder's main `decode()` method, Veteran
looks flat — one big frame, positional offsets, done. But `parseSubTypeData()` dispatches
across 5 sub-types, each with dedicated parsing logic and a `0x80` "not supported"
sentinel. That's capability negotiation. The wheel tells you what it can and can't do.
Neither Gotway nor Kingsong has anything like it. I only discovered the full scope of
this after studying the official Leaperkim app and finding settings fields (dynamicAssist,
accelerationLimit, chargeVoltageBase) that our decoder hadn't mapped yet.

### The Interpretation Divergence Problem

Comparing multiple implementations of the Veteran protocol was the most illuminating
exercise. Different apps assign different field names to the same byte offsets. Bytes
69-72 are labeled as temperatures in one implementation and currents in another. Byte 58
is variously called "displayMode" or "unit." Byte 60 is "lateralTiltLimit" or
"lowVolMode."

We can't be certain every implementation is wrong — including our own. Each app is
working from incomplete information, and nobody has a full spec. This is the fundamental
problem with positional protocols: without field IDs or documentation, independent
implementers reach different conclusions, and there's no authoritative source to settle
disagreements.

The raw numbers are often plausible either way. A value of 15.20 could be amps or
degrees. Without ground truth, you can ship any label for years and nobody notices because
the UI looks reasonable. We could be doing the same thing ourselves for fields we're less
sure about.

### What Didn't Change

InMotion V2 is still the best-designed protocol. Gotway is still the worst. Kingsong's
22 frame types are still impressive. NinebotZ's state machine is still brittle. These
assessments all held up under deeper scrutiny.

## Engineering Cultures Behind the Protocols

*Opinion and inference, stated more boldly than v1. After eight decoders, four unpackers,
multiple reference implementations, and a capture analysis pipeline that has processed
thousands of real-world frames — the code tells a very specific story about each
organization.*

### Begode: The Hardware Company That Ships BLE as an Afterthought

Begode's protocol was written by someone who builds motor controllers for a living and
got told to add phone connectivity. The result is a raw memory dump over BLE — literally
take the telemetry struct from the firmware, serialize it as 20 bytes, blast it over a
BLE characteristic. Done. Ship it.

This isn't speculation. The frame format *is* the in-memory layout. Fixed offsets, raw
integer encoding, no framing overhead, no metadata. The same engineer who wrote the FOC
loop probably added the BLE output, and they optimized for the thing they care about
(motor performance) while treating communication as a solved problem that doesn't need
architecture.

The `gotwayNegative` flag is the smoking gun. At some point, someone at Begode changed
whether speed values are signed or unsigned. They didn't add a version byte. They didn't
announce it. They just pushed a firmware update. Then another firmware revision changed
the sign convention *again*. Three different behaviors, no way to detect which one you're
talking to. The user has to know.

This is what happens when there's no software team reviewing protocol changes, no
regression testing against app compatibility, no concept of "breaking change." The
firmware engineer changed a line of code, reflashed the production boards, and moved on
to the next motor tuning task.

Other apps have had to build per-model calibration databases — regex matching wheel names
and firmware strings to figure out what a given Begode wheel actually means by its byte
values. That's the app compensating for the protocol's total failure to self-identify.
Every app in the ecosystem pays the cost of maintaining this database because Begode
never added a model ID byte.

The undocumented beeper volume field (byte 17) is my favorite piece of evidence. This is
a 20-byte frame. Twenty bytes. Thousands of users have been receiving it for years.
Community projects had been decoding it for years. And nobody had documented what byte 17
does. We found it by comparing BLE captures before and after volume writes in
PacketLogger — and only because PacketLogger's ATT summary view truncates at 16 bytes,
which means every casual packet inspector *literally can't see* this field. It's hidden
by the debugging tools.

If your 20-byte protocol has undiscovered fields, you don't have a documentation process.
You don't have a protocol spec. You have a firmware engineer who remembers what the bytes
mean, and when they leave or forget, the knowledge is gone.

Extreme Bull inheriting this protocol unchanged tells you everything about their
engineering investment: there isn't any. Same firmware, same protocol, same problems,
different sticker on the shell.

### Leaperkim/Veteran: Growing Up in Public

Leaperkim is the most interesting story because you can watch them become a real software
organization across their protocol generations.

**The Veteran era** is not what I thought it was. In v1, I called it "Gotway with slightly
better engineering discipline." After studying their protocol in more depth and comparing
across multiple implementations, I think that undersells it badly. The Veteran protocol
has CRC32 (stronger than anyone else), structured sub-type multiplexing, capability
negotiation via the 0x80 sentinel, and a dual command format for backward compatibility.
These are software engineering decisions. Someone at Leaperkim thought about frame
integrity, data organization, and forward compatibility. They didn't get everything right
— still positional-offset, still no field IDs — but the gap between Veteran and Gotway is
larger than I initially appreciated. It's not a minor improvement. It's a philosophical
shift from "dump memory over BLE" to "design a protocol."

The sub-type 8 settings parser is the clearest evidence. Nineteen configurable fields,
each with a 0x80 "not supported" sentinel, parsed with dedicated `readUnsigned()`/
`readSigned()` helpers. This is someone who anticipated that different wheel models would
support different features, and built a mechanism for the wheel to communicate its
capabilities. Gotway's approach to the same problem? The app guesses based on firmware
version strings.

**The CAN era** is a different team. I'm fairly confident of this now. The CAN protocol
has *weaker* integrity checking than Veteran (sum-mod-256 vs CRC32) but *better*
structural design (CAN IDs, escape framing, model ID table, defined init handshake).
These are different engineering priorities. The Veteran team cared about "don't trust
corrupt data." The CAN team cared about "make the protocol self-describing and
structured." It's unlikely the same engineer who chose CRC32 for Veteran would downgrade
to sum-mod-256 for a newer protocol — unless they didn't know about it, which means
different people.

The CAN protocol also borrows its BLE UUIDs from Gotway (the exact same service and
characteristic UUIDs), which is a bizarre choice for an original protocol design. It
suggests someone looked at existing EUC BLE implementations for reference and reused
what they saw, without understanding that these UUIDs were Gotway-specific. Or it was
deliberate for compatibility with existing BLE scanning apps. Either way, it's a
different design sensibility than the Veteran protocol.

**The dual-format command story.** The official Leaperkim app sends both LkAp and LdAp
command formats simultaneously — old and new, concatenated, every time. This is the
behavior of a team that can't be sure which firmware version is on the other end and has
decided the safest approach is to just send everything. It's pragmatic and wasteful in
equal measure, and it tells you their firmware fleet is fragmented enough that they can't
trust version detection. Sound familiar? It's the same problem Begode has with
`gotwayNegative`, except Leaperkim's solution is "send both" instead of "make the user
configure it." A better solution, but same root cause.

**The FreeWheel angle.** We're the only app implementing the CAN protocol — no other
known implementation exists. This means our interpretation is unverifiable against other
apps. It works with real hardware, but there's no reference to cross-check against.
We could have the same kind of field interpretation divergence that affects other
protocols and not know it yet.

### InMotion: The One That Gets It

InMotion's V2 protocol is what happens when a real software team designs a real protocol.
I know that sounds like a low bar, but in the EUC industry, it genuinely is.

The `0x80` response bit is the single clearest indicator of engineering maturity in any
EUC protocol. When you send a command with byte `0x20` and get back `0xA0`, you know
*exactly* which request this response corresponds to. This is first-semester networking.
It's also something that no other EUC manufacturer bothered to implement.

The V1 → V2 transition reveals something about the organization. V1 already had
password-based authentication and proper framing — already better than Gotway. They
didn't stop there. V2 restructured the entire command space with sub-type multiplexing
and response correlation. Then the P6 extended protocol added BMS IDs (`0x24`-`0x27`)
within the existing command taxonomy, proving the V2 design could accommodate new
hardware without breaking the frame format.

This is iterative protocol design done right. Each generation builds on the previous one
instead of replacing it. The command space has room to grow. New features slot into the
existing taxonomy. The protocol has enough structure to be largely self-documenting.

InMotion also invests in their protocol because they want ecosystem control. A
well-designed protocol that their app can fully exploit creates competitive advantage.
Their app *is* a platform — social feeds, GPS tracking, cloud sync, marketplace. The
protocol is the foundation. But the quality is real regardless of the motivation. If
every manufacturer shipped InMotion V2-quality protocols, building a third-party EUC app
would be dramatically simpler.

### Kingsong: The Pragmatist's Paradox

Kingsong is the most paradoxical protocol. Twenty-two frame types — more than InMotion
V2, more than anyone. Password-protected lock, per-speed alarm configuration, lift sensor
readback, headlight mode readback, LED mode readback, turn-off timer, BMS
request/response. The feature breadth is genuinely impressive.

Zero checksums.

Twenty-two frame types, every one trusted unconditionally. The most feature-rich protocol
structure in the ecosystem, with no integrity checking whatsoever. It's like building a
22-room house with no foundation.

I think this tells you Kingsong's protocol was designed by someone who understood
application-level features (what do riders want to configure?) but didn't come from a
networking or embedded communications background (how do we ensure the data is correct?).
The command taxonomy reflects product thinking. The absence of checksums reflects a gap
in protocol engineering knowledge. Different skills, same codebase.

Their saving grace is stability. Kingsong doesn't change their protocol. The frame format
from years ago works with today's firmware. In the EUC landscape, where Begode breaks
app compatibility with firmware releases, this conservative approach is more valuable
than any checksum. You can't corrupt what you never change.

### Ninebot: Design by Committee

The NinebotZ protocol feels like it was specified in a document before anyone wrote code.
Fourteen connection states, strict ordering, conditional BMS branches, encrypted payloads.
Every requirement has a defined state. Every edge case has a defined response. It's
comprehensive, thorough, and brittle in the way that over-specified systems always are.

Skip state 4 (PARAMS1) and jump to state 5 (PARAMS2)? Connection dead. No retry, no
fallback, no "close enough." The state machine doesn't degrade gracefully because
graceful degradation wasn't in the spec.

Segway-Ninebot is a large corporation, and their protocol reads like it. The base Ninebot
protocol is simpler and more forgiving — it was probably designed by the original team
before the corporate engineering process took over. NinebotZ added complexity proportional
to the wheel's capability without proportional gains in robustness. The XOR encryption on
payloads is particularly telling — it serves no real security purpose (XOR with a known
key is trivially reversible) but looks like a checkbox item on a requirements doc:
"encrypt BLE payloads."

## What This All Means

The v1 conclusion was that protocol quality tracks with company engineering culture, and
that the reference protocol exists to raise the floor. I still believe that, but the v2
deep dive added important nuance.

The biggest surprise was that **the ecosystem's collective understanding of its own
protocols is shakier than anyone assumes.** Different apps assign different names to the
same bytes. The numbers are often plausible either way, so wrong interpretations can
persist for years without anyone noticing. We're not immune to this — our Leaperkim CAN
implementation has no other app to cross-check against. The problem isn't that any
particular app is careless; it's that positional protocols without specs force everyone
to work from incomplete information.

The Veteran upgrade from "Mediocre" to "Good" matters because it changes the narrative
about Leaperkim. In v1, the story was "hardware company slowly learning software." The
real story is that their Veteran protocol was already pretty good — CRC32, sub-type
multiplexing, capability negotiation — and I underestimated it because I was comparing
surface features (no escape framing, no CAN IDs) rather than looking at what the
protocol actually *does well* (integrity checking, structured data organization, forward
compatibility).

The real problem remains the same: eight incompatible protocols, each requiring a
dedicated decoder, each with its own quirks and undocumented behaviors. The most
primitive protocol (Gotway) requires the most implementation effort despite offering the
least functionality. And even good protocols produce interpretation divergence when the
only shared documentation is the protocol itself.

The reference protocol exists because the right answer isn't "use InMotion V2" or "use
Veteran's CRC32" — it's an open standard with self-describing fields, proper versioning,
and real documentation. Whether any manufacturer has the incentive to adopt it remains
the open question. The ones with good protocols (InMotion, Leaperkim) have less reason
to switch. The ones with weak protocols (Begode) have less capacity to switch. And the
one with the most stable protocol (Kingsong) has the least motivation to change anything
at all.
