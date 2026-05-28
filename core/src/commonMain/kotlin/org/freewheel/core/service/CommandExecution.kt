package org.freewheel.core.service

import org.freewheel.core.protocol.WheelCommand

/**
 * Producer-side command-execution contract for Commit 5 of
 * `KINGSONG_BLE_PARITY_PLAN.md`.
 *
 * The reducer mints one [CommandTicket] per semantic [WheelCommand] flowing
 * through [WcmEffect.DispatchCommands] and tags it with a [CommandOrigin].
 * The executor maps that ticket through [CommandExecutionState] transitions
 * and republishes them on
 * [WheelConnectionManagerPort.commandTickets]. A future UX commit will
 * collect that flow to drive Begode-style loading / success / failure
 * affordances. Commit 5 lands the contract only — no UI surface is wired
 * (Compose or SwiftUI), no iOS Swift bridge exposure, no decoder-side
 * confirmation evidence, no timeout clock.
 *
 * Transport-driven traffic ([PostConnectWarmup], [TransportKeepAlivePolicy.FixedFrame])
 * bypasses [WcmEffect.DispatchCommands] by design — it calls
 * `sendBleData` directly from
 * [WheelConnectionManager.startTransportMaintenance] — so it does NOT
 * produce tickets. [CommandOrigin] has no TRANSPORT member specifically to
 * make accidentally minting one a type error.
 *
 * ## One ticket per semantic command
 *
 * [WheelDecoder.buildCommand] may expand a single semantic command into
 * multiple raw [WheelCommand.SendBytes] / [WheelCommand.SendDelayed] writes
 * (e.g. Gotway LED W → M → digit → b). The reducer still mints exactly one
 * ticket for the semantic command, and the executor emits one terminal
 * transition for it based on the AGGREGATE outcome of every raw write:
 *
 *  - If ANY raw write returns [BleWriteResult.Failed], the ticket
 *    terminates as [Failed] and the loop aborts immediately — the remaining
 *    raw writes do NOT go out to the wheel. This prevents the wheel from
 *    receiving a half-applied semantic command after the ticket has
 *    already terminated, and prevents the ticket from lying about success
 *    when a later byte failed.
 *  - If all raw writes succeed, the terminal [Sent] (and [WriteCompleted]
 *    when WITH_RESPONSE) uses the FIRST write's latency/ack — the earliest
 *    evidence the OS accepted the semantic command's bytes.
 *
 * Consumers correlating user actions to outcomes see one ticket per
 * user-intent, never one per BLE packet, and the terminal state honestly
 * reflects whether every byte made it through.
 *
 * ## Queued is emitted at mint time
 *
 * [Queued] fires from the reducer the moment the ticket is minted — before
 * the dispatch coroutine starts. This means:
 *
 *  - A ticket sitting behind earlier work in [CommandScheduler]'s channel
 *    is visible to consumers immediately, not only when its turn arrives.
 *  - A ticket drained by [CommandScheduler.cancelAll] before its dispatch
 *    coroutine ever runs still has at least the [Queued] transition on the
 *    flow — the ticket is never "invisible," just orphaned (see below).
 *
 * ## Cancellation
 *
 * [WcmEffect.CancelCommands] (and the underlying [CommandScheduler.cancelAll])
 * cancels in-flight ticket coroutines without emitting a synthetic terminal
 * transition. That is deliberate: cancellation is not a platform failure, and
 * a `Cancelled` variant is a Commit-6+ decision. Consumers must treat a
 * ticket that has not reached [Sent] / [WriteCompleted] / [Failed] /
 * [Confirmed] / [TimedOut] within a reasonable window as orphaned until a
 * future commit wires explicit timeout firing.
 */
data class CommandTicket(
    /**
     * Monotonically increasing across the lifetime of the
     * [WheelConnectionManager] instance. Minted by the reducer
     * (single-writer event-loop boundary) so the counter needs no
     * synchronization. Not reset on disconnect — helpful for debugging and
     * for consumers correlating tickets across reconnect cycles.
     */
    val id: Long,
    /** The semantic command this ticket tracks. */
    val command: WheelCommand,
    /** Where the command originated. See [CommandOrigin] for the matrix. */
    val origin: CommandOrigin,
    /**
     * The [WcmState.currentAttemptId] in effect when the reducer minted this
     * ticket. Consumers can filter by attempt to drop transitions belonging
     * to a prior session the same way the reducer drops stale BLE events.
     */
    val attemptId: Long,
    /** Wall-clock time (`currentTimeMillis()`) at mint time. */
    val submittedAtMs: Long,
)

/**
 * Where a [CommandTicket] originated. Closed enum — transport-driven
 * warmup / heartbeat traffic does NOT produce tickets and deliberately has
 * no member here.
 */
enum class CommandOrigin {
    /** A user action — `WheelConnectionManager.sendCommand(...)` and the convenience setters that wrap it. */
    USER,

    /** A decoder init command emitted by [WheelDecoder.getInitCommands] at decoder setup. */
    INIT,

    /** A decoder keepalive tick — emitted by [WheelDecoder.getKeepAliveCommand] under [TransportKeepAlivePolicy.UseDecoder]. */
    KEEPALIVE,

    /** A follow-up command the decoder asked for in response to a received frame (`DecodedFrame.commands`). */
    RESPONSE,
}

/**
 * Lifecycle phases a [CommandTicket] passes through.
 *
 * State sequence for a successful write:
 *   - [Queued] — emitted by the reducer the moment the ticket is minted.
 *     Visible BEFORE the dispatch coroutine starts and BEFORE the ticket
 *     reaches the front of [CommandScheduler]'s queue.
 *   - [Sent] — every raw [WheelCommand.SendBytes] / [WheelCommand.SendDelayed]
 *     write the decoder produced for this semantic command was submitted to
 *     the BLE port. Carries the FIRST write's latency (earliest evidence
 *     the OS accepted the command) and the [BleWriteType] the transport
 *     profile asked for.
 *   - [WriteCompleted] — only for [BleWriteType.WITH_RESPONSE] writes: the
 *     platform delivered the write-completion callback. Comes AFTER [Sent]
 *     (the executor emits both transitions for a Completed result so
 *     consumers can render "submitted" vs "peer-acked" as separate phases).
 *
 * Terminal-failure states:
 *   - [Failed] — ANY raw write returned [BleWriteResult.Failed] (the loop
 *     aborts on the failing write, so subsequent raw writes are NOT sent),
 *     or `buildCommand` declined to materialize any raw bytes.
 *
 * Reserved states (no code emits these in Commit 5):
 *   - [Confirmed] — see [Confirmed] doc block. Reserved so the next commit
 *     can wire decoder/readback evidence without a source break.
 *   - [TimedOut] — see [TimedOut] doc block. Reserved so a future commit can
 *     fire a per-write timeout clock without a source break.
 */
sealed class CommandExecutionState {
    /**
     * Ticket has been minted by the reducer and is scheduled for dispatch
     * but has not yet hit the BLE port. Fires from `mintTicketsFor` so
     * consumers see the ticket appear immediately, independent of how many
     * other commands are ahead of it in [CommandScheduler]'s queue.
     */
    data object Queued : CommandExecutionState()

    /**
     * The OS BLE stack accepted every raw write produced for this semantic
     * command. For [BleWriteType.WITHOUT_RESPONSE] this is the terminal
     * happy-path state; for [BleWriteType.WITH_RESPONSE] it is followed by
     * [WriteCompleted]. Carries the FIRST write's latency.
     */
    data class Sent(
        /** Platform-reported latency from the first BleWriteRequest to OS acceptance. */
        val latencyMs: Long,
        /** Write mode requested by the active [WheelTransportProfile]. */
        val writeType: BleWriteType,
    ) : CommandExecutionState()

    /**
     * Platform delivered the write-completion callback for the first
     * [BleWriteType.WITH_RESPONSE] write. The peer characteristic acked the
     * bytes; this is the strongest transport-level confirmation we have
     * without decoder/readback evidence.
     */
    data class WriteCompleted(
        val latencyMs: Long,
        val ack: BleWriteAck,
    ) : CommandExecutionState()

    /**
     * RESERVED — no code emits this in Commit 5.
     *
     * Intended for the next commit's decoder/readback wiring. A command
     * reaches [Confirmed] when the decoder later observes the wheel
     * echoing the value the command asked for. Command families with a
     * plausible confirmation path:
     *  - Kingsong settings writes (max speed, alarms, light brightness) —
     *    the next 0xA4/0xA5 frame echoes the setting.
     *  - InMotion V2 settings writes — a subsequent SETTINGS frame echoes.
     *  - Veteran settings writes — a periodic state frame echoes.
     *
     * Command families with NO plausible confirmation path (terminal state
     * stops at [Sent] / [WriteCompleted]):
     *  - [WheelCommand.Beep], [WheelCommand.Calibrate], [WheelCommand.PowerOff],
     *    [WheelCommand.SetLock], [WheelCommand.ResetTrip] — write-only, no
     *    distinguishable response frame.
     *
     * TODO(commit-6): wire decoder evidence into this state. See the
     * "command execution state contract" section of
     * `KINGSONG_BLE_PARITY_PLAN.md` (Commit 5 summary, "open ambiguities").
     */
    data class Confirmed(val evidence: String) : CommandExecutionState()

    /**
     * RESERVED — no code emits this in Commit 5.
     *
     * Intended for a future per-write timeout clock. Candidates:
     *  - [BleWriteType.WITH_RESPONSE] writes that never receive a
     *    write-completion callback within a profile-supplied window.
     *  - [BleWriteType.WITHOUT_RESPONSE] writes cannot meaningfully time out
     *    at the transport layer (submission is fire-and-forget); a future
     *    decoder-side confirmation clock could fire [TimedOut] from the
     *    [Confirmed] timeout instead.
     *
     * TODO(commit-6): wire a timeout clock that emits this state. See the
     * "command execution state contract" section of
     * `KINGSONG_BLE_PARITY_PLAN.md` (Commit 5 summary, "open ambiguities").
     */
    data class TimedOut(val afterMs: Long) : CommandExecutionState()

    /**
     * Terminal failure: ANY raw write for this semantic command returned
     * [BleWriteResult.Failed], or [WheelDecoder.buildCommand] returned an
     * empty list (the decoder declined to materialize this semantic command
     * into bytes). [reason] carries the failing write's platform-supplied
     * string. When a mid-sequence write fails, the executor aborts the
     * loop and does NOT send the remaining raw writes — the wheel never
     * receives a half-applied semantic command after the ticket has
     * terminated.
     */
    data class Failed(val reason: String) : CommandExecutionState()
}

/**
 * Wire type carried on the [WheelConnectionManagerPort.commandTickets]
 * flow. Pairs a [CommandTicket] with its latest [CommandExecutionState] and
 * the wall-clock time the transition happened.
 *
 * Backed by a `MutableSharedFlow(replay=0, extraBufferCapacity=64,
 * onBufferOverflow=DROP_OLDEST)` so a sleeping subscriber cannot
 * backpressure the dispatch loop. Dropping the oldest entry is the right
 * tradeoff because the freshest transitions carry the most useful state for
 * any UI eventually attached.
 */
data class CommandTicketUpdate(
    val ticket: CommandTicket,
    val state: CommandExecutionState,
    val updatedAtMs: Long,
)
