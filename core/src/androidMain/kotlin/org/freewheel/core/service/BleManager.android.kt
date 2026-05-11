package org.freewheel.core.service

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.welie.blessed.ConnectionPriority
import org.freewheel.core.ble.BleAdvertisement
import org.freewheel.core.ble.BleAdvertisementCache
import org.freewheel.core.ble.BleUuids
import org.freewheel.core.ble.DiscoveredService
import org.freewheel.core.ble.DiscoveredServices
import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.Logger
import org.freewheel.core.utils.withLock
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import com.welie.blessed.BluetoothPeripheralCallback
import com.welie.blessed.GattStatus
import com.welie.blessed.HciStatus
import com.welie.blessed.WriteType
import android.bluetooth.le.ScanResult
import com.welie.blessed.ConnectionState as BlessedConnectionState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Android implementation of BleManager using the blessed-android library.
 *
 * This manager handles:
 * - BLE scanning for wheel devices
 * - Connection management with continuation-based connect
 * - Service discovery
 * - Characteristic read/write operations
 * - Connection state tracking
 * - Auto-reconnect on unexpected disconnect
 *
 * ## Session State Machine
 *
 * Internal BLE state is tracked via a `BleSessionState` sealed class that bundles
 * peripheral references and scan callbacks with the state variants that own them.
 * This makes illegal states unrepresentable at compile time:
 * - `Idle` and `Scanning` have no peripheral — can't accidentally use a stale one
 * - `disconnectRequested` is eliminated — intent is encoded by state
 *
 * The `session` var is `@Volatile` because it's read from the BLE HandlerThread
 * callbacks and written from the coroutine dispatcher.
 *
 * ## Usage
 *
 * ```
 * val bleManager = BleManager()
 * bleManager.initialize(context)
 * bleManager.connect(address)
 * ```
 */
actual class BleManager : BleManagerPort {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _bluetoothState = MutableStateFlow(BluetoothAdapterState.UNKNOWN)
    actual override val bluetoothState: StateFlow<BluetoothAdapterState> = _bluetoothState.asStateFlow()

    /**
     * Update the Bluetooth adapter state.
     * Called from ComposeActivity's BroadcastReceiver when adapter state changes.
     *
     * On any transition that invalidates the OS BLE stack's notion of the
     * prior session (power off, unauthorized, unsupported), wipe the teardown
     * tracker. Otherwise a quarantined same-address teardown — pending only
     * because the OS callback for that session never arrived — would persist
     * across the adapter cycle, leaving the user stuck on
     * "Previous connection still draining" until process restart.
     */
    override fun setBluetoothAdapterState(state: BluetoothAdapterState) {
        val previous = _bluetoothState.value
        _bluetoothState.value = state
        if (previous != state && state != BluetoothAdapterState.POWERED_ON) {
            // Adapter went off / unauthorized / unsupported — old teardowns are
            // meaningless. Resetting also unblocks any awaiter still suspended
            // on a deferred from before the power cycle.
            teardownTracker.reset()
        } else if (previous == BluetoothAdapterState.POWERED_OFF && state == BluetoothAdapterState.POWERED_ON) {
            // Adapter just came back on — fresh hardware state, prior session
            // signals will never arrive.
            teardownTracker.reset()
        }
    }

    // ==================== Session State Machine ====================

    /**
     * Internal BLE session state. Each variant holds only the data relevant to that
     * state, making illegal combinations unrepresentable at compile time.
     *
     * No `Discovering` variant — Blessed handles service discovery atomically.
     */
    private sealed class BleSessionState {
        /** Null for states that don't hold a peripheral (Idle, Scanning). */
        open val peripheral: BluetoothPeripheral? get() = null
        /**
         * Session id stamped at [connect()]; forwarded to the WCM reducer so it
         * can drop events from a prior session. 0L for non-active states.
         *
         * Storing it on the session (instead of a process-wide var) means a
         * late callback that arrives AFTER a transition cannot accidentally
         * inherit the new session's id — if the peripheral mismatches the
         * current session, it is dropped at the platform layer (see callbacks
         * below).
         */
        open val attemptId: Long get() = 0L

        data object Idle : BleSessionState()
        data class Scanning(val callback: (BleDevice) -> Unit) : BleSessionState()
        data class Connecting(
            override val peripheral: BluetoothPeripheral,
            override val attemptId: Long,
        ) : BleSessionState()
        data class Connected(
            override val peripheral: BluetoothPeripheral,
            override val attemptId: Long,
        ) : BleSessionState()
        data class AwaitingReconnect(
            override val peripheral: BluetoothPeripheral,
            override val attemptId: Long,
        ) : BleSessionState()
    }

    @Volatile
    private var session: BleSessionState = BleSessionState.Idle

    private var central: BluetoothCentralManager? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null

    // MTU-aware chunk size — updated after MTU negotiation
    private var maxWriteLength: Int = 20

    // Connection continuation (protected by continuationLock)
    private var connectionContinuation: CancellableContinuation<Boolean>? = null
    private val continuationLock = Lock()

    // Callbacks (set once at initialization, not session-related). The
    // attemptId stamped at connect() is forwarded so the WCM reducer can drop
    // events from a prior session that the OS BLE stack is still flushing.
    private var onDataReceivedCallback: ((ByteArray, Long) -> Unit)? = null
    private var onBleErrorCallback: (() -> Unit)? = null
    private var onBleDisconnectedCallback: ((String, ConnectionIssue, Long) -> Unit)? = null
    private var onServicesDiscoveredCallback: ((DiscoveredServices, String?, Long) -> Unit)? = null

    // Dedicated BLE thread — stored for cleanup in destroy()
    private var bleThread: HandlerThread? = null

    // Scan-time advertisement cache (LRU + TTL). Survives across disconnects so
    // scan→connect-A→disconnect→connect-B from the same scan list still yields
    // evidence for B's connect path.
    private val advertisementCache = BleAdvertisementCache()

    override fun getAdvertisement(address: String): BleAdvertisement? =
        advertisementCache.get(address)

    // Serializes same-peripheral reconnects: a new connect to address X waits
    // for the OS to drain X's prior session before issuing connectPeripheral,
    // so a late callback from the old attempt cannot pass the peripheral-
    // identity guard with X's instance and corrupt the new session.
    private val teardownTracker = BleTeardownTracker()

    actual override val connectionState: StateFlow<ConnectionState>
        get() = _connectionState.asStateFlow()

    // ==================== Transition Functions ====================
    //
    // Each transition has an exhaustive `when` over BleSessionState.
    // Adding a new state variant produces a compile error until all
    // transitions handle it.

    private fun resumeContinuation(result: Boolean) {
        continuationLock.withLock {
            connectionContinuation?.resume(result) {}
            connectionContinuation = null
        }
    }

    /**
     * Tear down any active session synchronously: call cancelConnection if
     * a peripheral is held, register the pending teardown for callback-side
     * serialization, clear local characteristic state, and transition session
     * to Idle. Does **not** touch [_connectionState] — callers update it as
     * appropriate for the destination transition.
     */
    private fun cancelActiveSession() {
        when (val s = session) {
            is BleSessionState.Idle -> return
            is BleSessionState.Scanning -> {
                central?.stopScan()
            }
            is BleSessionState.Connecting -> {
                central?.cancelConnection(s.peripheral)
                teardownTracker.startTeardown(s.peripheral.address)
            }
            is BleSessionState.Connected -> {
                central?.cancelConnection(s.peripheral)
                teardownTracker.startTeardown(s.peripheral.address)
            }
            is BleSessionState.AwaitingReconnect -> {
                central?.cancelConnection(s.peripheral)
                teardownTracker.startTeardown(s.peripheral.address)
            }
        }
        resumeContinuation(false)
        writeCharacteristic = null
        readCharacteristic = null
        session = BleSessionState.Idle
    }

    private fun transitionToIdle() {
        cancelActiveSession()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun transitionToScanning(callback: (BleDevice) -> Unit) {
        cancelActiveSession()
        session = BleSessionState.Scanning(callback)
        _connectionState.value = ConnectionState.Scanning
    }

    // ==================== Central Manager Callback ====================

    private val centralCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(
            peripheral: BluetoothPeripheral,
            scanResult: ScanResult
        ) {
            val scanning = session as? BleSessionState.Scanning ?: return
            advertisementCache.put(buildAdvertisement(peripheral, scanResult))
            scanning.callback(
                BleDevice(
                    address = peripheral.address,
                    name = peripheral.name ?: scanResult.scanRecord?.deviceName,
                    rssi = scanResult.rssi
                )
            )
        }

        override fun onConnectedPeripheral(peripheral: BluetoothPeripheral) {
            Logger.d("BleManager", "onConnectedPeripheral: ${peripheral.address}")

            val s = session
            if (s.peripheral != peripheral) {
                // Late callback for a peripheral we've moved past. Drop at the
                // platform layer so a stale id can't slip through to WCM.
                Logger.w(
                    "BleManager",
                    "Stale onConnectedPeripheral for ${peripheral.address}; current session=${s::class.simpleName} peripheral=${s.peripheral?.address}"
                )
                return
            }

            // Request MTU for extended frames
            peripheral.requestMtu(BluetoothPeripheral.MAX_MTU)

            session = BleSessionState.Connected(peripheral, s.attemptId)

            // Resume connection continuation
            resumeContinuation(true)
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            Logger.w("BleManager", "onConnectionFailed: ${peripheral.address}, status=$status")

            // Always release any same-address waiter BEFORE the peripheral
            // guard. Even a stale callback signals that the OS has finished
            // tearing down its side, which is exactly what awaitTeardownDrain
            // is blocking on. The guard below still prevents stale state
            // mutations from leaking into the new session.
            teardownTracker.completeTeardown(peripheral.address)

            // Reject anything whose callback peripheral is not exactly the
            // current session's peripheral, including the null case (Idle /
            // Scanning). Without the strict gate, a late failure for an
            // already-abandoned attempt would publish ConnectionState.Failed
            // even though the user has already disconnected or moved on.
            val s = session
            if (s.peripheral != peripheral) {
                Logger.w(
                    "BleManager",
                    "Stale onConnectionFailed for ${peripheral.address}; current session=${s::class.simpleName} peripheral=${s.peripheral?.address}"
                )
                return
            }

            val issue = hciStatusToIssue(status)
            session = BleSessionState.Idle
            writeCharacteristic = null
            readCharacteristic = null
            _connectionState.value = ConnectionState.Failed(
                error = issue.message,
                address = peripheral.address,
                issue = issue,
            )

            resumeContinuation(false)
        }

        override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: HciStatus) {
            val address = peripheral.address
            val s = session
            Logger.d("BleManager", "onDisconnectedPeripheral: $address, session=${s::class.simpleName}, status=$status")

            // Always release any same-address waiter BEFORE the peripheral
            // guard. Even a stale callback signals OS-side teardown completion,
            // which is what awaitTeardownDrain blocks on. The guard below
            // still prevents stale state mutations from leaking through.
            teardownTracker.completeTeardown(address)

            // Reject late disconnect callbacks for a peripheral we've moved
            // past BEFORE touching the connect continuation. Earlier the
            // unconditional resumeContinuation(false) would fail the NEW
            // session's pending connect when a stale disconnect arrived from
            // an abandoned attempt. Strict gate (no null short-circuit) so
            // Idle/Scanning also drops here without touching state.
            if (s.peripheral != peripheral) {
                Logger.w(
                    "BleManager",
                    "Stale onDisconnectedPeripheral for $address; current session=${s::class.simpleName} peripheral=${s.peripheral?.address}"
                )
                return
            }

            // Safety net: resume any pending connect continuation owned by
            // THIS session. Now safe to call because we've proven the callback
            // belongs to the current session.
            resumeContinuation(false)

            writeCharacteristic = null
            readCharacteristic = null

            // Check session to determine if this was intentional. Idle/Scanning
            // is unreachable here because the strict guard above already
            // returned (s.peripheral would be null in those states). Kept for
            // exhaustive matching.
            when (s) {
                is BleSessionState.Idle, is BleSessionState.Scanning -> {
                    return
                }
                is BleSessionState.Connecting, is BleSessionState.Connected,
                is BleSessionState.AwaitingReconnect -> {
                    if (address.isNotEmpty()) {
                        // Unexpected disconnect — use passive OS-level auto-reconnect.
                        // autoConnectPeripheral adds the device to the OS whitelist and
                        // reconnects in the background when the peripheral is found again.
                        val issue = hciStatusToIssue(status)
                        val sessionAttemptId = s.attemptId
                        session = BleSessionState.AwaitingReconnect(peripheral, sessionAttemptId)
                        _connectionState.value = ConnectionState.ConnectionLost(
                            address = address,
                            reason = issue.message,
                            issue = issue,
                        )
                        Logger.d("BleManager", "Starting OS auto-reconnect for $address")
                        central?.autoConnectPeripheral(peripheral, peripheralCallback)
                        // Notify WCM immediately so it transitions to ConnectionLost
                        // without waiting for data timeout (which could take 30+ seconds)
                        onBleDisconnectedCallback?.invoke(address, issue, sessionAttemptId)
                    } else {
                        session = BleSessionState.Idle
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        }
    }

    // ==================== Initialization ====================

    /**
     * Initialize with a Context. Creates BluetoothCentralManager internally.
     * Must be called before connect() or startScan() in standalone mode.
     */
    fun initialize(context: Context) {
        if (central != null) return
        // Dedicated BLE thread — keeps all Blessed callbacks off the main thread
        // so UI rendering, GC pauses, and Compose recomposition can't block
        // BLE notification processing (critical at 40+ notifications/sec)
        val thread = HandlerThread("FreeWheel-BLE").apply { start() }
        bleThread = thread
        central = BluetoothCentralManager(
            context,
            centralCallback,
            Handler(thread.looper)
        )
    }

    /**
     * Set callback for when data is received from the wheel.
     * Receives the active session's [Long] attemptId so the WCM reducer can
     * drop frames from a prior session.
     */
    fun setDataReceivedCallback(callback: (ByteArray, Long) -> Unit) {
        onDataReceivedCallback = callback
    }

    /**
     * Set callback for when a BLE characteristic update fails (GATT error).
     */
    fun setBleErrorCallback(callback: () -> Unit) {
        onBleErrorCallback = callback
    }

    /**
     * Set callback for when the OS disconnects unexpectedly.
     * Called with (address, issue, attemptId). Not called for user-initiated disconnects.
     */
    fun setBleDisconnectedCallback(callback: (String, ConnectionIssue, Long) -> Unit) {
        onBleDisconnectedCallback = callback
    }

    /**
     * Set callback for when services are discovered.
     */
    fun setServicesDiscoveredCallback(callback: (DiscoveredServices, String?, Long) -> Unit) {
        onServicesDiscoveredCallback = callback
    }

    /**
     * Get the current peripheral.
     */
    fun getPeripheral(): BluetoothPeripheral? = session.peripheral

    /**
     * Get discovered services from the current peripheral.
     */
    fun getServices(): List<BluetoothGattService>? = session.peripheral?.services

    /**
     * Get a specific service by UUID.
     */
    fun getService(uuid: UUID): BluetoothGattService? = session.peripheral?.getService(uuid)

    // ==================== Peripheral Callback ====================

    private val peripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
            // Forward only when this callback's peripheral matches the current
            // session's peripheral. A late callback for a peripheral we've
            // already moved past would otherwise inherit the new session's id.
            val s = session
            if (s.peripheral != peripheral) {
                Logger.w(
                    "BleManager",
                    "Stale onServicesDiscovered for ${peripheral.address}; current session peripheral=${s.peripheral?.address}"
                )
                return
            }

            // Request high connection priority for low-latency telemetry (~7.5ms interval
            // vs default balanced ~30ms). Critical for protocols with 25ms keep-alive.
            peripheral.requestConnectionPriority(ConnectionPriority.HIGH)

            _connectionState.value = ConnectionState.DiscoveringServices(peripheral.address)

            val discoveredServices = peripheral.services.map { service ->
                DiscoveredService(
                    uuid = service.uuid.toString(),
                    characteristics = service.characteristics.map { it.uuid.toString() }
                )
            }

            onServicesDiscoveredCallback?.invoke(
                DiscoveredServices(discoveredServices),
                peripheral.name,
                s.attemptId,
            )
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            val s = session
            if (s.peripheral != peripheral) {
                // Stale notification from a peripheral we've moved past. Drop
                // at the platform layer so a stale id can't slip through to WCM.
                return
            }
            if (status == GattStatus.SUCCESS) {
                onDataReceivedCallback?.invoke(value, s.attemptId)
            } else {
                Logger.w("BleManager", "Characteristic update failed: $status (${characteristic.uuid})")
                onBleErrorCallback?.invoke()
            }
        }

        override fun onCharacteristicWrite(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            // Write completed
        }

        override fun onMtuChanged(
            peripheral: BluetoothPeripheral,
            mtu: Int,
            status: GattStatus
        ) {
            if (status == GattStatus.SUCCESS) {
                maxWriteLength = mtu - 3  // ATT header overhead
                Logger.d("BleManager", "MTU negotiated: $mtu bytes, max write: $maxWriteLength")
            }
        }
    }

    // ==================== Connection ====================

    actual override suspend fun connect(address: String, attemptId: Long): Boolean {
        val manager = central ?: throw IllegalStateException(
            "BleManager not initialized. Call initialize() first."
        )

        val peripheral = manager.getPeripheral(address)

        // Phase 1 (sync): cancel any active session. cancelActiveSession
        // registers a teardown for the prior peripheral's address so the
        // upcoming await can serialize against it if it's the same address.
        cancelActiveSession()

        // Phase 2 (suspend): wait for any pending OS teardown of THIS address
        // to drain. Cross-peripheral reconnects find no entry and skip the
        // wait entirely; same-peripheral reconnects block until the prior
        // session's onDisconnectedPeripheral / onConnectionFailed callback
        // has fired. The helper sets _connectionState to a Failed value with
        // the appropriate user-facing reason on timeout vs invalidation.
        if (!awaitTeardownDrain(address)) {
            return false
        }

        // Phase 3 (sync): bind the new attemptId to the session so callbacks
        // read the stamp from the session that owns them, not from a
        // process-wide field that could be overwritten by a subsequent
        // reconnect before late callbacks drain.
        session = BleSessionState.Connecting(peripheral, attemptId)
        _connectionState.value = ConnectionState.Connecting(address)

        return suspendCancellableCoroutine { continuation ->
            continuationLock.withLock {
                connectionContinuation = continuation
            }

            manager.connectPeripheral(peripheral, peripheralCallback)

            continuation.invokeOnCancellation {
                central?.cancelConnection(peripheral)
                continuationLock.withLock {
                    connectionContinuation = null
                }
            }
        }
    }

    /**
     * Wait for any in-flight OS teardown for [address] to drain.
     *
     * Returns true only if the OS authoritatively confirmed drain
     * ([TeardownDrainResult.DRAINED]) or there was nothing to wait for.
     * Returns false on timeout OR when the tracker was reset
     * ([TeardownDrainResult.INVALIDATED]) — the latter signals that the BLE
     * stack itself was torn down (adapter cycled, manager destroyed) while
     * we were suspended; proceeding would issue connectPeripheral against
     * an invalidated stack.
     */
    private suspend fun awaitTeardownDrain(address: String): Boolean {
        val deferred = teardownTracker.pendingTeardownDeferredFor(address) ?: return true
        val result = kotlinx.coroutines.withTimeoutOrNull(TEARDOWN_DRAIN_TIMEOUT_MS) {
            deferred.await()
        }
        return when (result) {
            TeardownDrainResult.DRAINED -> true
            TeardownDrainResult.INVALIDATED -> {
                Logger.w(
                    "BleManager",
                    "Teardown wait for $address resumed via tracker reset (BLE stack " +
                    "invalidated). Failing this connect — proceeding would call " +
                    "connectPeripheral against a torn-down stack."
                )
                _connectionState.value = ConnectionState.Failed(
                    error = "Bluetooth state changed; please retry",
                    address = address,
                    issue = ConnectionIssue.terminal(
                        ConnectionIssueCode.BLUETOOTH_STATE_CHANGED,
                        "Bluetooth state changed; please retry",
                    ),
                )
                false
            }
            null -> {
                Logger.w(
                    "BleManager",
                    "Teardown drain timeout for $address after ${TEARDOWN_DRAIN_TIMEOUT_MS}ms; " +
                    "failing this connect to avoid acting on a stale-vs-fresh callback that " +
                    "we couldn't classify. The deferred remains pending; the next OS-confirmed " +
                    "drain (or BleManager reset) will clear it."
                )
                _connectionState.value = ConnectionState.Failed(
                    error = "Previous connection still draining; please retry",
                    address = address,
                    issue = ConnectionIssue.terminal(
                        ConnectionIssueCode.PREVIOUS_CONNECTION_STILL_DRAINING,
                        "Previous connection still draining; please retry",
                    ),
                )
                false
            }
        }
    }

    /**
     * Start passive auto-reconnect for a previously connected peripheral.
     * Uses OS-level reconnection that is power-efficient and has no timeout.
     * The OS will reconnect when the peripheral comes back in range.
     */
    fun autoReconnect(address: String) {
        val manager = central ?: return
        val peripheral = session.peripheral ?: manager.getPeripheral(address)
        // Preserve the existing session's attemptId — auto-reconnect is the
        // same logical session, not a new one.
        session = BleSessionState.AwaitingReconnect(peripheral, session.attemptId)
        _connectionState.value = ConnectionState.Connecting(address)
        manager.autoConnectPeripheral(peripheral, peripheralCallback)
    }

    actual override suspend fun disconnect() {
        transitionToIdle()
    }

    // ==================== Write ====================

    actual override suspend fun write(data: ByteArray): Boolean {
        val peripheral = session.peripheral ?: return false
        val characteristic = writeCharacteristic ?: return false

        if (peripheral.state != BlessedConnectionState.CONNECTED) {
            return false
        }

        return peripheral.writeCharacteristic(
            characteristic,
            data,
            WriteType.WITHOUT_RESPONSE
        )
    }

    /**
     * Write data with chunking for protocols that need it (e.g., InMotion V1).
     */
    suspend fun writeChunked(data: ByteArray, chunkSize: Int = maxWriteLength, delayMs: Long = 20): Boolean {
        val peripheral = session.peripheral ?: return false
        val characteristic = writeCharacteristic ?: return false

        if (peripheral.state != BlessedConnectionState.CONNECTED) {
            return false
        }

        var offset = 0
        while (offset < data.size) {
            val chunk = data.copyOfRange(offset, minOf(offset + chunkSize, data.size))
            val success = peripheral.writeCharacteristic(
                characteristic,
                chunk,
                WriteType.WITHOUT_RESPONSE
            )
            if (!success) return false

            offset += chunkSize
            if (offset < data.size) {
                kotlinx.coroutines.delay(delayMs)
            }
        }
        return true
    }

    // ==================== Characteristic Configuration ====================

    fun setWriteCharacteristic(serviceUuid: String, charUuid: String): Boolean {
        val peripheral = session.peripheral ?: return false
        val service = peripheral.getService(UUID.fromString(serviceUuid)) ?: return false
        writeCharacteristic = service.getCharacteristic(UUID.fromString(charUuid))
        return writeCharacteristic != null
    }

    fun setReadCharacteristic(serviceUuid: String, charUuid: String): Boolean {
        val peripheral = session.peripheral ?: return false
        val service = peripheral.getService(UUID.fromString(serviceUuid)) ?: run {
            Logger.w("BleManager", "Service $serviceUuid not found on peripheral")
            return false
        }
        readCharacteristic = service.getCharacteristic(UUID.fromString(charUuid))

        readCharacteristic?.let { char ->
            peripheral.setNotify(char, true)
            return true
        }
        Logger.w("BleManager", "Read characteristic $charUuid not found in service $serviceUuid")
        return false
    }

    /**
     * Returns true if the read characteristic was bound (notifications enabled).
     * Returns false when the read service or characteristic is missing — the
     * caller surfaces this as a connection failure. Write is best-effort.
     */
    override fun configureForWheel(
        readServiceUuid: String,
        readCharUuid: String,
        writeServiceUuid: String,
        writeCharUuid: String
    ): Boolean {
        val readOk = setReadCharacteristic(readServiceUuid, readCharUuid)
        setWriteCharacteristic(writeServiceUuid, writeCharUuid)
        return readOk
    }

    // ==================== Lifecycle ====================

    override fun destroy() {
        when (val s = session) {
            is BleSessionState.Idle -> {}
            is BleSessionState.Scanning -> central?.stopScan()
            is BleSessionState.Connecting -> central?.cancelConnection(s.peripheral)
            is BleSessionState.Connected -> central?.cancelConnection(s.peripheral)
            is BleSessionState.AwaitingReconnect -> central?.cancelConnection(s.peripheral)
        }
        session = BleSessionState.Idle
        writeCharacteristic = null
        readCharacteristic = null
        // Wipe quarantined teardowns — manager is being torn down, so no
        // future OS callback will be observed even if the adapter delivers
        // one. Anyone still awaiting a deferred resumes immediately.
        teardownTracker.reset()
        central?.close()
        central = null
        bleThread?.quitSafely()
        bleThread = null
    }

    // ==================== Scanning ====================

    actual override suspend fun startScan(onDeviceFound: (BleDevice) -> Unit) {
        val manager = central ?: return
        transitionToScanning(onDeviceFound)
        manager.scanForPeripherals()
    }

    /**
     * Start scanning for devices advertising a specific BLE service UUID.
     * Used for charger discovery (FFE1 service).
     */
    override suspend fun startScanForService(serviceUuid: String, onDeviceFound: (BleDevice) -> Unit) {
        val manager = central ?: return
        transitionToScanning(onDeviceFound)
        manager.scanForPeripheralsWithServices(arrayOf(UUID.fromString(serviceUuid)))
    }

    actual override suspend fun stopScan() {
        central?.stopScan()
        if (session is BleSessionState.Scanning) {
            session = BleSessionState.Idle
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    // ==================== Helpers ====================

    private fun buildAdvertisement(
        peripheral: BluetoothPeripheral,
        scanResult: ScanResult
    ): BleAdvertisement {
        val record = scanResult.scanRecord
        val advertisedServiceUuids: Set<String> = record?.serviceUuids
            ?.map { BleUuids.canonicalize(it.uuid.toString()) }
            ?.toSet() ?: emptySet()

        val manufacturer: Map<Int, ByteArray> = record?.manufacturerSpecificData
            ?.let { sa ->
                buildMap {
                    for (i in 0 until sa.size()) {
                        val key = sa.keyAt(i)
                        val value = sa.valueAt(i)
                        if (value != null) put(key, value)
                    }
                }
            } ?: emptyMap()

        val serviceData: Map<String, ByteArray> = record?.serviceData
            ?.mapKeys { (puuid, _) -> BleUuids.canonicalize(puuid.uuid.toString()) }
            ?: emptyMap()

        val connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            scanResult.isConnectable
        } else {
            true
        }

        return BleAdvertisement(
            address = peripheral.address,
            advertisedName = record?.deviceName,
            peripheralName = peripheral.name,
            rssi = scanResult.rssi,
            advertisedServiceUuids = advertisedServiceUuids,
            manufacturerData = manufacturer,
            serviceData = serviceData,
            connectable = connectable,
            lastSeenMs = System.currentTimeMillis(),
        )
    }

    private fun hciStatusToIssue(status: HciStatus): ConnectionIssue = when (status) {
        HciStatus.SUCCESS -> ConnectionIssue.recoverable(
            ConnectionIssueCode.PERIPHERAL_DISCONNECTED,
            "Disconnected"
        )
        HciStatus.CONNECTION_TIMEOUT -> ConnectionIssue.recoverable(
            ConnectionIssueCode.CONNECTION_TIMED_OUT,
            "Connection timed out"
        )
        HciStatus.REMOTE_USER_TERMINATED_CONNECTION -> ConnectionIssue.recoverable(
            ConnectionIssueCode.PERIPHERAL_DISCONNECTED,
            "Wheel disconnected"
        )
        HciStatus.CONNECTION_TERMINATED_BY_LOCAL_HOST -> ConnectionIssue.recoverable(
            ConnectionIssueCode.CONNECTION_FAILED,
            "Connection cancelled"
        )
        HciStatus.CONNECTION_FAILED_ESTABLISHMENT -> ConnectionIssue.recoverable(
            ConnectionIssueCode.CONNECTION_FAILED,
            "Connection failed"
        )
        HciStatus.ERROR -> ConnectionIssue.recoverable(
            ConnectionIssueCode.CONNECTION_FAILED,
            "GATT error"
        )
        else -> ConnectionIssue.unknownRecoverable("Disconnected: ${status.name}")
    }

    private companion object {
        /**
         * How long [awaitTeardownDrain] blocks waiting for the OS to confirm
         * a prior session's teardown for the same peripheral. The OS BLE
         * stack typically delivers the drain callback within ~50ms; 2s gives
         * a comfortable margin while keeping connect() interactive.
         */
        const val TEARDOWN_DRAIN_TIMEOUT_MS = 2_000L
    }
}

/**
 * Android BLE connection wrapper.
 * Wraps a BluetoothPeripheral for type-safe connection handling.
 */
actual class BleConnection(
    val peripheral: BluetoothPeripheral
) {
    val address: String get() = peripheral.address
    val name: String? get() = peripheral.name
    val isConnected: Boolean get() = peripheral.state == BlessedConnectionState.CONNECTED
}
