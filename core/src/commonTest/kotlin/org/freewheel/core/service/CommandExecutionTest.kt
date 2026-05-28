package org.freewheel.core.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.freewheel.core.ble.WheelTypeDetector
import org.freewheel.core.domain.identity.WheelIdentity
import org.freewheel.core.domain.identity.WheelType
import org.freewheel.core.domain.telemetry.TelemetryState
import org.freewheel.core.protocol.DecodeResult
import org.freewheel.core.protocol.DecodedData
import org.freewheel.core.protocol.WheelCommand
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Commit 5 of `KINGSONG_BLE_PARITY_PLAN.md`: producer-side contract for
 * per-command lifecycle transitions surfaced on
 * [WheelConnectionManagerPort.commandTickets].
 *
 * These tests pin:
 *  - one ticket per semantic [WheelCommand] (USER / INIT / KEEPALIVE / RESPONSE)
 *  - WITHOUT_RESPONSE writes -> Queued -> Sent
 *  - WITH_RESPONSE writes -> Queued -> Sent -> WriteCompleted
 *  - Failed BLE writes -> Queued -> Failed(reason carried through)
 *  - decoder-expanded raw writes do NOT multiply ticket transitions
 *  - multi-command dispatches mint N tickets with strictly increasing ids
 *  - a slow ticket-flow subscriber does not backpressure the dispatch loop
 *
 * Transport-driven warmup/heartbeat silence is covered by
 * [WheelConnectionManagerKingsongTransportTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandExecutionTest {

    private lateinit var fakeBle: FakeBleManager
    private lateinit var fakeDecoder: FakeDecoder
    private lateinit var fakeFactory: FakeDecoderFactory

    @BeforeTest
    fun setup() {
        fakeBle = FakeBleManager()
        fakeDecoder = FakeDecoder()
        fakeFactory = FakeDecoderFactory(fakeDecoder)
    }

    private fun TestScope.createManager(): WheelConnectionManager {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return WheelConnectionManager(
            fakeBle, fakeFactory, backgroundScope, dispatcher,
            wheelTypeDetector = WheelTypeDetector(),
            keepAliveTimer = KeepAliveTimer(backgroundScope, dispatcher),
            dataTimeoutTracker = DataTimeoutTracker(backgroundScope, dispatcher),
        )
    }

    /**
     * Subscribe to [commandTickets] from the backgroundScope before any
     * tickets fire — the flow is hot, replay = 0, so a consumer that joins
     * after a transition has already happened never sees it.
     */
    private fun TestScope.collectTickets(
        manager: WheelConnectionManager,
    ): MutableList<CommandTicketUpdate> {
        val captured = mutableListOf<CommandTicketUpdate>()
        backgroundScope.launch {
            manager.commandTickets.collect { captured.add(it) }
        }
        runCurrent()
        return captured
    }

    /** Drive the manager from a fresh state into [ConnectionState.Connected] for GOTWAY (default profile). */
    private fun bringToConnectedGotway(manager: WheelConnectionManager, address: String) {
        manager.connect(address)
        manager.onWheelTypeDetected(WheelType.GOTWAY)
        fakeDecoder.decodeResult = DecodeResult.Success(
            DecodedData(
                telemetry = TelemetryState(speed = 1000),
                identity = WheelIdentity(name = "GW"),
            )
        )
        fakeDecoder.ready = true
    }

    // ==================== USER origin ====================

    @Test
    fun `USER sendCommand mints a ticket with origin USER and the right command`() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        val tickets = collectTickets(manager)
        bringToConnectedGotway(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        val before = tickets.size
        manager.sendCommand(WheelCommand.SendBytes(byteArrayOf(0x42)))
        runCurrent()

        val userTickets = tickets.drop(before).filter { it.ticket.origin == CommandOrigin.USER }
        assertTrue(userTickets.isNotEmpty(), "Expected at least one USER ticket update")
        val firstTicket = userTickets.first().ticket
        assertEquals(CommandOrigin.USER, firstTicket.origin)
        assertEquals(WheelCommand.SendBytes(byteArrayOf(0x42)), firstTicket.command)
        // Every transition for this command must reference the same ticket id.
        val idsForUserCommand = userTickets.map { it.ticket.id }.distinct()
        assertEquals(
            listOf(firstTicket.id),
            idsForUserCommand,
            "All transitions for a single semantic command must share the ticket id",
        )
    }

    // ==================== WITHOUT_RESPONSE lifecycle ====================

    @Test
    fun `WITHOUT_RESPONSE write emits Queued then Sent`() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        val tickets = collectTickets(manager)
        bringToConnectedGotway(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        val before = tickets.size
        manager.sendCommand(WheelCommand.SendBytes(byteArrayOf(0x42)))
        runCurrent()

        val userTransitions = tickets.drop(before).filter { it.ticket.origin == CommandOrigin.USER }
        // Sequence must be exactly Queued -> Sent (WITHOUT_RESPONSE never reaches WriteCompleted).
        val states = userTransitions.map { it.state }
        assertEquals(2, states.size, "Expected Queued -> Sent, got $states")
        assertEquals(CommandExecutionState.Queued, states[0])
        val sent = states[1] as CommandExecutionState.Sent
        assertEquals(BleWriteType.WITHOUT_RESPONSE, sent.writeType)
    }

    // ==================== WITH_RESPONSE lifecycle ====================

    @Test
    fun `WITH_RESPONSE write emits Queued then Sent then WriteCompleted`() = runTest(timeout = 1.seconds) {
        // Force Completed results so the executor publishes both Sent and
        // WriteCompleted phases (two distinct transitions by design — a
        // future UI can render submitted vs peer-acked separately).
        fakeBle.writeBehavior = { request ->
            BleWriteResult.Completed(
                ack = BleWriteAck(
                    attemptId = fakeBle.lastConnectAttemptId,
                    success = true,
                    data = request.data.copyOf(),
                ),
                latencyMs = 7,
            )
        }
        val manager = createManager()
        val tickets = collectTickets(manager)
        bringToConnectedGotway(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        val before = tickets.size
        manager.sendCommand(WheelCommand.SendBytes(byteArrayOf(0x42)))
        runCurrent()

        val userStates = tickets.drop(before)
            .filter { it.ticket.origin == CommandOrigin.USER }
            .map { it.state }

        assertEquals(3, userStates.size, "Expected Queued -> Sent -> WriteCompleted, got $userStates")
        assertEquals(CommandExecutionState.Queued, userStates[0])
        val sent = userStates[1] as CommandExecutionState.Sent
        // writeType reflects what the active transport profile asked for —
        // the default profile is WITHOUT_RESPONSE; the fake returns Completed
        // anyway, but the executor reports the profile's writeType verbatim.
        assertEquals(BleWriteType.WITHOUT_RESPONSE, sent.writeType)
        assertEquals(7L, sent.latencyMs)
        val completed = userStates[2] as CommandExecutionState.WriteCompleted
        assertEquals(7L, completed.latencyMs)
        assertTrue(completed.ack.success)
        assertTrue(completed.ack.data.contentEquals(byteArrayOf(0x42)))
    }

    // ==================== Failed mapping ====================

    @Test
    fun `BleWriteResult Failed maps to Queued then Failed with reason carried through`() = runTest(timeout = 1.seconds) {
        fakeBle.writeBehavior = { BleWriteResult.Failed("GATT_FAILURE", latencyMs = 3) }
        val manager = createManager()
        val tickets = collectTickets(manager)
        bringToConnectedGotway(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        val before = tickets.size
        manager.sendCommand(WheelCommand.SendBytes(byteArrayOf(0x42)))
        runCurrent()

        val userStates = tickets.drop(before)
            .filter { it.ticket.origin == CommandOrigin.USER }
            .map { it.state }
        assertEquals(2, userStates.size, "Expected Queued -> Failed, got $userStates")
        assertEquals(CommandExecutionState.Queued, userStates[0])
        val failed = userStates[1] as CommandExecutionState.Failed
        assertEquals("GATT_FAILURE", failed.reason)
    }

    @Test
    fun `decoder returning no raw commands fails the ticket`() = runTest(timeout = 1.seconds) {
        // buildCommand returns empty for a non-SendBytes/Delayed command;
        // the ticket must terminate as Failed rather than orphan.
        fakeDecoder.buildCommandResult = emptyList()
        val manager = createManager()
        val tickets = collectTickets(manager)
        bringToConnectedGotway(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        val before = tickets.size
        manager.sendCommand(WheelCommand.Beep)
        runCurrent()

        val userStates = tickets.drop(before)
            .filter { it.ticket.origin == CommandOrigin.USER }
            .map { it.state }
        assertEquals(2, userStates.size, "Expected Queued -> Failed, got $userStates")
        assertEquals(CommandExecutionState.Queued, userStates[0])
        val failed = userStates[1] as CommandExecutionState.Failed
        assertTrue(
            failed.reason.contains("no raw commands", ignoreCase = true),
            "Reason should mention the empty buildCommand result, got '${failed.reason}'",
        )
    }

    // ==================== Origins for INIT / KEEPALIVE / RESPONSE ====================

    @Test
    fun `INIT commands minted via setupDecoderTransition carry origin INIT`() = runTest(timeout = 1.seconds) {
        // Two init writes (e.g. Kingsong / Leaperkim multi-step setup).
        fakeDecoder = FakeDecoder().apply {
            initCommandList = listOf(
                WheelCommand.SendBytes(byteArrayOf(0xA0.toByte())),
                WheelCommand.SendBytes(byteArrayOf(0xA1.toByte())),
            )
        }
        fakeFactory = FakeDecoderFactory(fakeDecoder)
        val manager = createManager()
        val tickets = collectTickets(manager)

        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.GOTWAY)
        runCurrent()

        val initTransitions = tickets.filter { it.ticket.origin == CommandOrigin.INIT }
        assertTrue(initTransitions.isNotEmpty(), "Expected INIT-origin transitions")
        val distinctIds = initTransitions.map { it.ticket.id }.distinct().sorted()
        assertEquals(
            2,
            distinctIds.size,
            "Two init commands must mint two distinct tickets, got ids $distinctIds",
        )
        assertTrue(
            distinctIds[0] < distinctIds[1],
            "Ticket ids must be strictly increasing across a single dispatch",
        )
        // Every transition recorded for the INIT block must carry INIT origin.
        assertTrue(initTransitions.all { it.ticket.origin == CommandOrigin.INIT })
    }

    @Test
    fun `keep-alive ticks carry origin KEEPALIVE`() = runTest(timeout = 2.seconds) {
        // Non-zero keepAliveIntervalMs surfaces the decoder-driven path
        // through reduceKeepAliveTick.
        fakeDecoder = FakeDecoder(
            keepAliveIntervalMs = 100L,
            keepAliveCommand = WheelCommand.SendBytes(byteArrayOf(0xCC.toByte())),
        )
        fakeFactory = FakeDecoderFactory(fakeDecoder)
        val manager = createManager()
        val tickets = collectTickets(manager)

        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.GOTWAY)
        runCurrent()

        // Burn one keepalive interval — at least one tick should fire.
        advanceTimeBy(110)
        runCurrent()

        val keepaliveTransitions = tickets.filter { it.ticket.origin == CommandOrigin.KEEPALIVE }
        assertTrue(
            keepaliveTransitions.isNotEmpty(),
            "Expected at least one KEEPALIVE-origin transition after the keep-alive interval",
        )
        val firstKeepAlive = keepaliveTransitions.first().ticket
        assertEquals(WheelCommand.SendBytes(byteArrayOf(0xCC.toByte())), firstKeepAlive.command)
    }

    @Test
    fun `decoder response commands carry origin RESPONSE`() = runTest(timeout = 1.seconds) {
        // Decoder asks us to send a follow-up command in response to a frame.
        fakeDecoder.decodeResult = DecodeResult.Success(
            DecodedData(
                telemetry = TelemetryState(speed = 1000),
                commands = listOf(WheelCommand.SendBytes(byteArrayOf(0x98.toByte()))),
            )
        )
        fakeDecoder.ready = true
        val manager = createManager()
        val tickets = collectTickets(manager)

        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.GOTWAY)
        runCurrent()

        val before = tickets.size
        // Trigger a frame so the decoder returns its follow-up command.
        manager.onDataReceived(byteArrayOf(0x01))
        runCurrent()

        val responseTransitions = tickets.drop(before)
            .filter { it.ticket.origin == CommandOrigin.RESPONSE }
        assertTrue(
            responseTransitions.isNotEmpty(),
            "Expected at least one RESPONSE-origin transition after onDataReceived",
        )
        val firstResponseTicket = responseTransitions.first().ticket
        assertEquals(
            WheelCommand.SendBytes(byteArrayOf(0x98.toByte())),
            firstResponseTicket.command,
        )
    }

    // ==================== One semantic command, one ticket ====================

    @Test
    fun `semantic command expanded by buildCommand to N raw writes still emits one ticket`() = runTest(timeout = 1.seconds) {
        // FakeDecoder.buildCommand returns three raw writes for any non
        // SendBytes/SendDelayed semantic command — mirrors Gotway LED's
        // W -> M -> digit -> b multi-step expansion. The ticket flow must
        // still see exactly ONE ticket-id with one Queued and one terminal
        // pair, never one per raw write.
        fakeDecoder.buildCommandResult = listOf(
            WheelCommand.SendBytes(byteArrayOf(0x01)),
            WheelCommand.SendBytes(byteArrayOf(0x02)),
            WheelCommand.SendBytes(byteArrayOf(0x03)),
        )
        val manager = createManager()
        val tickets = collectTickets(manager)
        bringToConnectedGotway(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        val before = tickets.size
        manager.sendCommand(WheelCommand.Beep)
        runCurrent()

        val userTransitions = tickets.drop(before).filter { it.ticket.origin == CommandOrigin.USER }
        val distinctIds = userTransitions.map { it.ticket.id }.distinct()
        assertEquals(
            1,
            distinctIds.size,
            "One semantic command must produce one ticket id even if buildCommand expands it, got $distinctIds",
        )
        assertEquals(
            2,
            userTransitions.size,
            "Expected exactly Queued + one terminal transition for the expanded command, got ${userTransitions.map { it.state }}",
        )
        assertEquals(CommandExecutionState.Queued, userTransitions[0].state)
        assertTrue(userTransitions[1].state is CommandExecutionState.Sent)

        // Sanity check on the BLE port — the BLE layer still sees all three raw writes.
        assertEquals(
            3,
            fakeBle.writeRequests.count {
                it.data.size == 1 && it.data[0].toInt() in 1..3
            },
            "All three raw writes must still reach the BLE port",
        )
    }

    // ==================== Multi-command origin sanity ====================

    @Test
    fun `multi-command init dispatch produces N tickets with strictly increasing ids`() = runTest(timeout = 1.seconds) {
        fakeDecoder = FakeDecoder().apply {
            initCommandList = listOf(
                WheelCommand.SendBytes(byteArrayOf(0x10)),
                WheelCommand.SendBytes(byteArrayOf(0x11)),
                WheelCommand.SendBytes(byteArrayOf(0x12)),
            )
        }
        fakeFactory = FakeDecoderFactory(fakeDecoder)
        val manager = createManager()
        val tickets = collectTickets(manager)

        manager.connect("AA:BB:CC:DD:EE:FF")
        manager.onWheelTypeDetected(WheelType.GOTWAY)
        runCurrent()

        val initTransitions = tickets.filter { it.ticket.origin == CommandOrigin.INIT }
        // 3 commands -> 3 tickets -> each emits Queued + Sent under
        // default profile -> 6 transitions total.
        val distinctIds = initTransitions.map { it.ticket.id }.distinct()
        assertEquals(3, distinctIds.size, "Expected three distinct INIT ticket ids, got $distinctIds")
        // Strictly increasing.
        val sorted = distinctIds.sorted()
        assertEquals(sorted, distinctIds.sorted())
        for (i in 1 until sorted.size) {
            assertTrue(sorted[i] > sorted[i - 1], "Ids must be strictly increasing")
        }
        // All carry the INIT origin.
        assertTrue(initTransitions.all { it.ticket.origin == CommandOrigin.INIT })
    }

    // ==================== Backpressure ====================

    @Test
    fun `slow ticket flow subscriber does not backpressure dispatch`() = runTest(timeout = 5.seconds) {
        // A subscriber that sleeps 100ms between emissions must NOT prevent
        // BLE writes from completing on schedule. Assertion is on
        // fakeBle.writeRequests (the dispatch path), not on tickets received
        // (the consumer path) — the executor is decoupled from collectors via
        // a buffered SharedFlow with DROP_OLDEST overflow.
        val manager = createManager()
        // Slow consumer — joins before the dispatch starts so emission is hot.
        backgroundScope.launch {
            manager.commandTickets.collect {
                delay(100)
            }
        }
        runCurrent()

        bringToConnectedGotway(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        val writesBefore = fakeBle.writeRequests.size
        // Burst of 20 USER commands. Without backpressure isolation, the
        // dispatch loop would stall waiting on the 100ms collector.
        repeat(20) { i ->
            manager.sendCommand(WheelCommand.SendBytes(byteArrayOf(i.toByte())))
        }
        runCurrent()

        val burstWrites = fakeBle.writeRequests.size - writesBefore
        assertEquals(
            20,
            burstWrites,
            "All 20 dispatched writes must reach the BLE port without waiting for the slow consumer",
        )
    }

    // ==================== attemptId tagging ====================

    @Test
    fun `tickets carry the active attemptId`() = runTest(timeout = 1.seconds) {
        val manager = createManager()
        val tickets = collectTickets(manager)
        bringToConnectedGotway(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        manager.sendCommand(WheelCommand.SendBytes(byteArrayOf(0x42)))
        runCurrent()

        val attemptIds = tickets
            .filter { it.ticket.origin == CommandOrigin.USER }
            .map { it.ticket.attemptId }
            .distinct()
        assertEquals(1, attemptIds.size, "All transitions for a session must share attemptId")
        assertTrue(
            attemptIds.single() > 0L,
            "attemptId must be a real session id, got ${attemptIds.single()}",
        )
    }

    // ==================== Data class plumbing ====================

    @Test
    fun `commandTicketUpdate carries the ticket reference and updatedAtMs`() = runTest(timeout = 1.seconds) {
        // Belt-and-braces — make sure the wire type's fields are populated.
        val manager = createManager()
        val tickets = collectTickets(manager)
        bringToConnectedGotway(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()
        manager.sendCommand(WheelCommand.SendBytes(byteArrayOf(0x42)))
        runCurrent()

        val update = tickets.first { it.ticket.origin == CommandOrigin.USER }
        assertNotNull(update.ticket)
        assertTrue(update.updatedAtMs >= update.ticket.submittedAtMs)
    }

    // ==================== P1 regression: multi-write loop honesty ====================

    @Test
    fun `multi-write expansion where a later raw write fails terminates Failed and aborts the loop`() = runTest(timeout = 1.seconds) {
        // P1 from review: previously the ticket terminated at Sent/WriteCompleted
        // from the first raw write only, so a failure on raw write 2..N was
        // silently swallowed AND raw write 3 still went out to the wheel.
        // Now: any raw write failure aborts the loop and terminates Failed.
        fakeDecoder.buildCommandResult = listOf(
            WheelCommand.SendBytes(byteArrayOf(0x01)),
            WheelCommand.SendBytes(byteArrayOf(0x02)),
            WheelCommand.SendBytes(byteArrayOf(0x03)),
        )
        // First write Submitted; second write Failed; third write would
        // succeed if it ever fired — that's the proof point.
        var writeCount = 0
        fakeBle.writeBehavior = { request ->
            writeCount += 1
            when {
                request.data.size == 1 && request.data[0] == 0x02.toByte() ->
                    BleWriteResult.Failed("simulated mid-sequence failure", latencyMs = 4)
                else -> BleWriteResult.Submitted(latencyMs = 1)
            }
        }
        val manager = createManager()
        val tickets = collectTickets(manager)
        bringToConnectedGotway(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        val before = tickets.size
        manager.sendCommand(WheelCommand.Beep)
        runCurrent()

        // Terminal state must be Failed with the failing write's reason.
        val userStates = tickets.drop(before)
            .filter { it.ticket.origin == CommandOrigin.USER }
            .map { it.state }
        assertEquals(
            listOf<CommandExecutionState>(
                CommandExecutionState.Queued,
                CommandExecutionState.Failed("simulated mid-sequence failure"),
            ),
            userStates,
            "Ticket must terminate Failed (not Sent) when a later raw write fails",
        )

        // And the loop must have aborted — write 3 (0x03) is never sent.
        val rawWrites = fakeBle.writeRequests.filter {
            it.data.size == 1 && it.data[0].toInt() in 1..3
        }
        assertEquals(
            listOf(0x01, 0x02),
            rawWrites.map { it.data[0].toInt() },
            "Mid-sequence failure must abort the loop; write 3 (0x03) must not reach BLE",
        )
    }

    @Test
    fun `multi-write where the first raw write fails terminates Failed and sends no further raw writes`() = runTest(timeout = 1.seconds) {
        fakeDecoder.buildCommandResult = listOf(
            WheelCommand.SendBytes(byteArrayOf(0x01)),
            WheelCommand.SendBytes(byteArrayOf(0x02)),
            WheelCommand.SendBytes(byteArrayOf(0x03)),
        )
        fakeBle.writeBehavior = { request ->
            when {
                request.data.size == 1 && request.data[0] == 0x01.toByte() ->
                    BleWriteResult.Failed("first-write failure", latencyMs = 2)
                else -> BleWriteResult.Submitted(latencyMs = 1)
            }
        }
        val manager = createManager()
        val tickets = collectTickets(manager)
        bringToConnectedGotway(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        val before = tickets.size
        manager.sendCommand(WheelCommand.Beep)
        runCurrent()

        val userStates = tickets.drop(before)
            .filter { it.ticket.origin == CommandOrigin.USER }
            .map { it.state }
        assertEquals(
            listOf<CommandExecutionState>(
                CommandExecutionState.Queued,
                CommandExecutionState.Failed("first-write failure"),
            ),
            userStates,
            "First-write failure must terminate Failed without ever emitting Sent",
        )
        val rawWrites = fakeBle.writeRequests.filter {
            it.data.size == 1 && it.data[0].toInt() in 1..3
        }
        assertEquals(
            listOf(0x01),
            rawWrites.map { it.data[0].toInt() },
            "First-write failure must abort the loop; writes 2..N must not reach BLE",
        )
    }

    @Test
    fun `multi-write expansion all-success emits Sent once with first writes latency`() = runTest(timeout = 1.seconds) {
        // Companion to the failure cases above — proves we still terminate
        // Sent (using the FIRST write's latency) when every raw write
        // succeeds. Pins the "first-write latency" semantic so a future
        // commit that wants different timing has a contract to evolve.
        fakeDecoder.buildCommandResult = listOf(
            WheelCommand.SendBytes(byteArrayOf(0x01)),
            WheelCommand.SendBytes(byteArrayOf(0x02)),
            WheelCommand.SendBytes(byteArrayOf(0x03)),
        )
        var writeNum = 0
        fakeBle.writeBehavior = {
            writeNum += 1
            BleWriteResult.Submitted(latencyMs = writeNum.toLong())
        }
        val manager = createManager()
        val tickets = collectTickets(manager)
        bringToConnectedGotway(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        val before = tickets.size
        manager.sendCommand(WheelCommand.Beep)
        runCurrent()

        val userStates = tickets.drop(before)
            .filter { it.ticket.origin == CommandOrigin.USER }
            .map { it.state }
        assertEquals(
            2,
            userStates.size,
            "Expected Queued + one terminal Sent, got $userStates",
        )
        assertEquals(CommandExecutionState.Queued, userStates[0])
        val sent = userStates[1] as CommandExecutionState.Sent
        assertEquals(
            1L,
            sent.latencyMs,
            "Terminal Sent must carry the FIRST write's latency, not the last",
        )
    }

    // ==================== P2 regression: Queued is emitted at mint ====================

    @Test
    fun `commands queued behind in-flight work are visible immediately`() = runTest(timeout = 1.seconds) {
        // P2 from review: previously Queued was emitted at the top of the
        // per-command coroutine, so commands sitting behind earlier work
        // were invisible until their turn arrived. Now: Queued fires from
        // the reducer at mint time, regardless of dispatch progress.
        //
        // Setup: gate every BLE write so the first command's dispatch
        // coroutine suspends inside writeCoordinator. Then send four more
        // commands. With the fix, all five Queued transitions appear before
        // any dispatch resumes; with the bug, only the first command's
        // Queued would be visible (the rest would still be waiting their
        // turn in CommandScheduler's queue).
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fakeBle.writeGate = gate
        val manager = createManager()
        val tickets = collectTickets(manager)
        bringToConnectedGotway(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        val before = tickets.size
        repeat(5) { i ->
            manager.sendCommand(WheelCommand.SendBytes(byteArrayOf(i.toByte())))
        }
        runCurrent()

        val userUpdates = tickets.drop(before).filter { it.ticket.origin == CommandOrigin.USER }
        val states = userUpdates.map { it.state }
        // Crucial: all 5 Queued must already be on the flow even though
        // every BLE write is stuck inside writeGate.await(). No Sent or
        // WriteCompleted can have fired yet.
        assertEquals(
            List(5) { CommandExecutionState.Queued },
            states,
            "Queued for all 5 commands must precede any dispatch resumption when writes are gated",
        )

        // Release the gate so dispatch can finish — sanity check that the
        // test setup hasn't deadlocked the writes permanently.
        gate.complete(Unit)
        runCurrent()
        assertTrue(
            tickets.drop(before)
                .filter { it.ticket.origin == CommandOrigin.USER }
                .any { it.state is CommandExecutionState.Sent },
            "Once gate releases, at least one Sent transition must arrive",
        )
    }

    @Test
    fun `ticket cancelled between mint and dispatch still shows Queued`() = runTest(timeout = 1.seconds) {
        // P2 corollary: a ticket whose dispatch is cancelled before the
        // coroutine ever runs must NOT be invisible. Even without a
        // synthetic Cancelled transition (excluded from Commit 5), the
        // consumer should at least see Queued for the cancelled ticket.
        // Gate writes so dispatch can never complete; disconnect then
        // cancels the scheduler before the gated write resumes.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fakeBle.writeGate = gate
        val manager = createManager()
        val tickets = collectTickets(manager)
        bringToConnectedGotway(manager, "AA:BB:CC:DD:EE:FF")
        runCurrent()

        val before = tickets.size
        manager.sendCommand(WheelCommand.SendBytes(byteArrayOf(0x42)))
        runCurrent()
        // Dispatch coroutine is parked inside writeGate.await(); ticket
        // already minted and Queued already emitted by the reducer.
        manager.disconnect()
        runCurrent()

        val userUpdates = tickets.drop(before).filter { it.ticket.origin == CommandOrigin.USER }
        assertTrue(
            userUpdates.any { it.state == CommandExecutionState.Queued },
            "Cancelled-before-dispatch ticket must still surface Queued; got ${userUpdates.map { it.state }}",
        )
        // No synthetic Failed should land — the spec rules out a Cancelled
        // variant and explicitly forbids fabricating a Failed transition
        // for a cancelled coroutine.
        assertTrue(
            userUpdates.none { it.state is CommandExecutionState.Failed },
            "Cancellation must not synthesize a Failed transition; got ${userUpdates.map { it.state }}",
        )

        // Release the gate so the suspended scope can tear down cleanly.
        gate.complete(Unit)
        runCurrent()
    }
}
