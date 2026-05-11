package org.freewheel.core.service

import org.freewheel.core.ble.BleAdvertisement
import org.freewheel.core.ble.BleAdvertisementCache
import org.freewheel.core.ble.BleUuids
import org.freewheel.core.ble.DiscoveredService
import org.freewheel.core.ble.DiscoveredServices
import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.Logger
import org.freewheel.core.utils.withLock
import kotlinx.cinterop.*
import kotlin.experimental.ExperimentalObjCName
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock as mutexWithLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreBluetooth.*
import platform.Foundation.*
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iOS implementation of BleManager using CoreBluetooth.
 *
 * This implementation uses Kotlin/Native interop with CoreBluetooth framework.
 *
 * ## Architecture
 *
 * The iOS BLE stack uses delegate-based callbacks:
 * - `CBCentralManagerDelegate` for connection management
 * - `CBPeripheralDelegate` for data and service discovery
 *
 * This class bridges the delegate pattern to Kotlin coroutines and StateFlow.
 *
 * ## Session State Machine
 *
 * Internal BLE state is tracked via a `BleSessionState` sealed class that bundles
 * peripheral references, scan callbacks, and discovery tracking with the state
 * variants that own them. This makes illegal states unrepresentable at compile time:
 * - `Idle` and `Scanning` have no peripheral — can't accidentally use a stale one
 * - `Discovering` owns a CoroutineScope that's cancelled on any transition away
 * - `disconnectRequested` is eliminated — intent is encoded by state
 *
 * ## Required Capabilities
 *
 * Add to Info.plist:
 * - `NSBluetoothAlwaysUsageDescription` - Permission description
 * - `UIBackgroundModes` with `bluetooth-central` for background operation
 */
@OptIn(ExperimentalForeignApi::class)
actual class BleManager : BleManagerPort {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ==================== Session State Machine ====================

    /**
     * Internal BLE session state. Each variant holds only the data relevant to that
     * state, making illegal combinations unrepresentable at compile time.
     */
    private sealed class BleSessionState {
        /** Null for states that don't hold a peripheral (Idle, Scanning). */
        open val peripheral: CBPeripheral? get() = null
        /**
         * Session id stamped at [connect()]; forwarded to the WCM reducer so
         * it can drop events from a prior session. 0L for non-active states.
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
            override val peripheral: CBPeripheral,
            override val attemptId: Long,
        ) : BleSessionState()
        data class Discovering(
            override val peripheral: CBPeripheral,
            override val attemptId: Long,
            /** Cancelled automatically on any transition away from Discovering. */
            val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job()),
            // Track completion by event count, not unique UUID set (Fix E):
            // some peripherals expose the same service UUID twice (e.g., a primary
            // and an included service that share a UUID), and a Set<String> of UUIDs
            // would never reach expectedCount, leaving us stuck in Discovering.
            var completedCount: Int = 0,
            var expectedCount: Int = 0,
        ) : BleSessionState()
        data class Connected(
            override val peripheral: CBPeripheral,
            override val attemptId: Long,
        ) : BleSessionState()
        data class AwaitingReconnect(
            override val peripheral: CBPeripheral,
            override val attemptId: Long,
        ) : BleSessionState()
    }

    private var session: BleSessionState = BleSessionState.Idle

    // CoreBluetooth infrastructure
    private var centralManager: CBCentralManager? = null
    private var writeCharacteristic: CBCharacteristic? = null
    private var readCharacteristic: CBCharacteristic? = null

    // UUID configuration (persists across reconnects, set by configureForWheel)
    private var readServiceUuid: String? = null
    private var readCharUuid: String? = null
    private var writeServiceUuid: String? = null
    private var writeCharUuid: String? = null

    // Callbacks (set once at initialization, not session-related)
    private var onDataReceivedCallback: ((ByteArray, Long) -> Unit)? = null
    private var onBleErrorCallback: (() -> Unit)? = null
    private var onBleDisconnectedCallback: ((String, ConnectionIssue, Long) -> Unit)? = null
    private var onServicesDiscoveredCallback: ((DiscoveredServices, String?, Long) -> Unit)? = null

    // Connection continuation for suspend function (cross-thread lifecycle)
    private var connectionContinuation: CancellableContinuation<Boolean>? = null
    private val continuationLock = Lock()

    // Write flow control — CoreBluetooth silently drops writes when buffer is full
    private val writeMutex = Mutex()
    private var writeReadyContinuation: CancellableContinuation<Unit>? = null

    // MTU-aware chunk size — updated after connection from negotiated value
    private var maxWriteLength: Int = 20

    // Delegate instances (prevent garbage collection)
    private var centralDelegate: CBCentralManagerDelegateImpl? = null
    private var peripheralDelegate: CBPeripheralDelegateImpl? = null

    private val _bluetoothState = MutableStateFlow(BluetoothAdapterState.UNKNOWN)
    actual override val bluetoothState: StateFlow<BluetoothAdapterState> = _bluetoothState.asStateFlow()

    actual override val connectionState: StateFlow<ConnectionState>
        get() = _connectionState.asStateFlow()

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
     * Suspend until CoreBluetooth's write buffer has space.
     * Returns immediately if the peripheral can accept a write now.
     */
    private suspend fun awaitWriteReady(peripheral: CBPeripheral) {
        if (peripheral.canSendWriteWithoutResponse) return
        Logger.d("BleManager", "Write buffer full, waiting for ready callback")
        suspendCancellableCoroutine { cont ->
            writeReadyContinuation = cont
            cont.invokeOnCancellation { writeReadyContinuation = null }
        }
    }

    /**
     * Called from the peripheral delegate when CoreBluetooth's write buffer drains.
     */
    internal fun onReadyToWrite() {
        writeReadyContinuation?.resume(Unit) {}
        writeReadyContinuation = null
    }

    /**
     * Tear down any active session synchronously: cancel the OS connection
     * if a peripheral is held, register the pending teardown for callback-
     * side serialization, clear local characteristic state, and transition
     * session to Idle. Does **not** touch [_connectionState] — callers update
     * it as appropriate for the destination transition.
     */
    private fun cancelActiveSession() {
        when (val s = session) {
            is BleSessionState.Idle -> return
            is BleSessionState.Scanning -> {
                centralManager?.stopScan()
            }
            is BleSessionState.Connecting -> {
                centralManager?.cancelPeripheralConnection(s.peripheral)
                teardownTracker.startTeardown(s.peripheral.identifier.UUIDString)
            }
            is BleSessionState.Discovering -> {
                s.scope.cancel()
                centralManager?.cancelPeripheralConnection(s.peripheral)
                teardownTracker.startTeardown(s.peripheral.identifier.UUIDString)
            }
            is BleSessionState.Connected -> {
                centralManager?.cancelPeripheralConnection(s.peripheral)
                teardownTracker.startTeardown(s.peripheral.identifier.UUIDString)
            }
            is BleSessionState.AwaitingReconnect -> {
                centralManager?.cancelPeripheralConnection(s.peripheral)
                teardownTracker.startTeardown(s.peripheral.identifier.UUIDString)
            }
        }
        resumeContinuation(false)
        writeReadyContinuation?.cancel()
        writeReadyContinuation = null
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

    /**
     * Move to Discovering, carrying forward the existing session's attemptId
     * (this is the same logical session, just a deeper phase). The caller is
     * the central-manager `didConnect` callback, which has already validated
     * peripheral identity against the current session.
     */
    private fun transitionToDiscovering(peripheral: CBPeripheral, attemptId: Long) {
        when (val s = session) {
            is BleSessionState.Idle -> {}
            is BleSessionState.Scanning -> centralManager?.stopScan()
            is BleSessionState.Connecting -> {} // normal path: Connecting -> Discovering
            is BleSessionState.Discovering -> s.scope.cancel()
            is BleSessionState.Connected -> {}
            is BleSessionState.AwaitingReconnect -> {} // reconnect completed
        }
        session = BleSessionState.Discovering(peripheral, attemptId)
    }

    private fun transitionToConnected(peripheral: CBPeripheral, attemptId: Long) {
        when (val s = session) {
            is BleSessionState.Idle -> {}
            is BleSessionState.Scanning -> centralManager?.stopScan()
            is BleSessionState.Connecting -> {}
            is BleSessionState.Discovering -> s.scope.cancel() // normal path
            is BleSessionState.Connected -> {}
            is BleSessionState.AwaitingReconnect -> {}
        }
        session = BleSessionState.Connected(peripheral, attemptId)
        _connectionState.value = ConnectionState.Connected(
            address = peripheral.identifier.UUIDString,
            wheelName = peripheral.name ?: "Unknown"
        )
    }

    // ==================== Initialization ====================

    /**
     * Initialize the CBCentralManager.
     * Must be called before any BLE operations.
     *
     * @param restoreIdentifier CoreBluetooth restore identifier. Each concurrent
     *   CBCentralManager must use a unique identifier. Pass null to disable state restoration.
     */
    fun initialize(restoreIdentifier: String? = "FreeWheelBLE") {
        if (centralManager == null) {
            centralDelegate = CBCentralManagerDelegateImpl(this)
            peripheralDelegate = CBPeripheralDelegateImpl(this)
            val options: Map<Any?, Any>? = if (restoreIdentifier != null) {
                mapOf(CBCentralManagerOptionRestoreIdentifierKey to restoreIdentifier)
            } else {
                null
            }
            centralManager = CBCentralManager(
                delegate = centralDelegate,
                queue = null,
                options = options
            )
        }
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
     * Set callback for when a BLE characteristic update fails (CoreBluetooth error).
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
     * Configure characteristics based on WheelConnectionInfo.
     *
     * Returns true if the read characteristic was bound (notifications enabled),
     * false if the underlying service or read characteristic was missing — the
     * caller surfaces the failure as a connection error rather than waiting
     * indefinitely for data.
     */
    override fun configureForWheel(
        readServiceUuid: String,
        readCharUuid: String,
        writeServiceUuid: String,
        writeCharUuid: String
    ): Boolean {
        this.readServiceUuid = readServiceUuid
        this.readCharUuid = readCharUuid
        this.writeServiceUuid = writeServiceUuid
        this.writeCharUuid = writeCharUuid
        // Set up characteristics now that UUIDs are configured. If the peripheral
        // hasn't been bound yet (e.g., called before service discovery completed),
        // treat it as a configuration failure — there's no characteristic to bind.
        val peripheral = session.peripheral ?: return false
        return setupCharacteristics(peripheral)
    }

    // ==================== Connection ====================

    actual override suspend fun connect(address: String, attemptId: Long): Boolean {
        val central = centralManager ?: run {
            initialize()
            centralManager!!
        }

        // Check if Bluetooth is powered on
        if (central.state != CBManagerStatePoweredOn) {
            throw IllegalStateException("Bluetooth is not powered on. State: ${central.state}")
        }

        // Try to retrieve peripheral by UUID
        val uuid = NSUUID(uUIDString = address)
        val peripherals = central.retrievePeripheralsWithIdentifiers(listOf(uuid))

        if (peripherals.isEmpty()) {
            val issue = ConnectionIssue.terminal(
                ConnectionIssueCode.PERIPHERAL_NOT_FOUND,
                "Peripheral not found"
            )
            _connectionState.value = ConnectionState.Failed(
                error = issue.message,
                address = address,
                issue = issue,
            )
            return false
        }

        val peripheral = peripherals.first() as CBPeripheral
        peripheral.delegate = peripheralDelegate

        // Phase 1 (sync): cancel any active session. cancelActiveSession
        // registers a teardown for the prior peripheral's address so the
        // upcoming await can serialize against it if it's the same address.
        cancelActiveSession()

        // Phase 2 (suspend): wait for any pending OS teardown of THIS address
        // to drain. Cross-peripheral reconnects find no entry and skip the
        // wait entirely; same-peripheral reconnects block until the prior
        // session's didFailToConnect / didDisconnectPeripheral has fired.
        // The helper sets _connectionState to a Failed value with the
        // appropriate user-facing reason on timeout vs invalidation.
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

            central.connectPeripheral(peripheral, options = null)

            continuation.invokeOnCancellation {
                centralManager?.cancelPeripheralConnection(peripheral)
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
     * stack itself was torn down (Bluetooth power-cycled, manager destroyed)
     * while we were suspended; proceeding would issue connectPeripheral
     * against an invalidated stack.
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

    actual override suspend fun disconnect() {
        transitionToIdle()
    }

    actual override suspend fun write(data: ByteArray): Boolean {
        val peripheral = session.peripheral ?: return false
        val characteristic = writeCharacteristic ?: return false

        if (peripheral.state != CBPeripheralStateConnected) {
            return false
        }

        val nsData = data.toNSData()
        writeMutex.mutexWithLock {
            awaitWriteReady(peripheral)
            peripheral.writeValue(nsData, characteristic, CBCharacteristicWriteWithoutResponse)
        }
        return true
    }

    /**
     * Write data with chunking for protocols that need it (e.g., InMotion V1).
     */
    suspend fun writeChunked(data: ByteArray, chunkSize: Int = maxWriteLength, delayMs: Long = 20): Boolean {
        val peripheral = session.peripheral ?: return false
        val characteristic = writeCharacteristic ?: return false

        if (peripheral.state != CBPeripheralStateConnected) {
            return false
        }

        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            val nsData = chunk.toNSData()
            awaitWriteReady(peripheral)
            peripheral.writeValue(nsData, characteristic, CBCharacteristicWriteWithoutResponse)

            offset += chunkSize
            if (offset < data.size) {
                delay(delayMs)
            }
        }
        return true
    }

    override fun destroy() {
        // Clean up any active session state
        when (val s = session) {
            is BleSessionState.Idle -> {}
            is BleSessionState.Scanning -> centralManager?.stopScan()
            is BleSessionState.Connecting -> centralManager?.cancelPeripheralConnection(s.peripheral)
            is BleSessionState.Discovering -> {
                s.scope.cancel()
                centralManager?.cancelPeripheralConnection(s.peripheral)
            }
            is BleSessionState.Connected -> centralManager?.cancelPeripheralConnection(s.peripheral)
            is BleSessionState.AwaitingReconnect -> centralManager?.cancelPeripheralConnection(s.peripheral)
        }
        session = BleSessionState.Idle
        writeReadyContinuation?.cancel()
        writeReadyContinuation = null
        writeCharacteristic = null
        readCharacteristic = null
        // Wipe quarantined teardowns — manager is being torn down, so no
        // future OS callback will be observed even if CoreBluetooth delivers
        // one. Anyone still awaiting a deferred resumes immediately.
        teardownTracker.reset()
        scope.cancel()
        onDataReceivedCallback = null
        onBleErrorCallback = null
        onBleDisconnectedCallback = null
        onServicesDiscoveredCallback = null
        centralManager = null
        centralDelegate = null
        peripheralDelegate = null
    }

    // ==================== Scanning ====================

    actual override suspend fun startScan(onDeviceFound: (BleDevice) -> Unit) {
        val central = centralManager ?: run {
            initialize()
            centralManager!!
        }

        if (central.state != CBManagerStatePoweredOn) {
            return
        }

        // transitionToScanning handles:
        // - Cancelling any pending OS auto-reconnect (AwaitingReconnect peripheral)
        // - Stopping any existing scan to reset CoreBluetooth's deduplication filter
        // - Cleaning up Connecting/Discovering/Connected state
        transitionToScanning(onDeviceFound)

        central.scanForPeripheralsWithServices(serviceUUIDs = null, options = null)
    }

    /**
     * Start scanning for devices advertising a specific BLE service UUID.
     * Used for charger discovery (FFE1 service).
     */
    override suspend fun startScanForService(serviceUuid: String, onDeviceFound: (BleDevice) -> Unit) {
        val central = centralManager ?: run {
            initialize()
            centralManager!!
        }

        if (central.state != CBManagerStatePoweredOn) {
            return
        }

        transitionToScanning(onDeviceFound)

        central.scanForPeripheralsWithServices(
            serviceUUIDs = listOf(CBUUID.UUIDWithString(serviceUuid)),
            options = null
        )
    }

    actual override suspend fun stopScan() {
        centralManager?.stopScan()
        if (session is BleSessionState.Scanning) {
            session = BleSessionState.Idle
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    // ==================== Internal Callback Methods ====================

    internal fun onStateUpdated(state: Long) {
        val previous = _bluetoothState.value
        _bluetoothState.value = when (state) {
            CBManagerStatePoweredOn -> BluetoothAdapterState.POWERED_ON
            CBManagerStatePoweredOff -> BluetoothAdapterState.POWERED_OFF
            CBManagerStateUnauthorized -> BluetoothAdapterState.UNAUTHORIZED
            CBManagerStateUnsupported -> BluetoothAdapterState.UNSUPPORTED
            CBManagerStateResetting -> BluetoothAdapterState.RESETTING
            else -> BluetoothAdapterState.UNKNOWN
        }

        // Any transition that invalidates CoreBluetooth's notion of the prior
        // session — power off, unauthorized, unsupported, resetting — also
        // invalidates pending teardowns. Wipe so a quarantined same-address
        // teardown cannot persist past a power cycle. Also wipe on the
        // off→on transition: fresh hardware state, prior session signals
        // will never arrive.
        val invalidates = state != CBManagerStatePoweredOn ||
            previous == BluetoothAdapterState.POWERED_OFF
        if (invalidates && previous != _bluetoothState.value) {
            teardownTracker.reset()
        }

        // When Bluetooth powers off, didDisconnectPeripheral is unreliable on some
        // iOS versions (particularly Control Center toggle). Clean up immediately.
        if (state == CBManagerStatePoweredOff) {
            when (val s = session) {
                is BleSessionState.Idle -> { /* nothing to clean up */ }
                is BleSessionState.Scanning -> {
                    session = BleSessionState.Idle
                    _connectionState.value = ConnectionState.Disconnected
                }
                is BleSessionState.Connecting -> {
                    val address = s.peripheral.identifier.UUIDString
                    writeCharacteristic = null
                    readCharacteristic = null
                    writeReadyContinuation?.cancel()
                    writeReadyContinuation = null
                    resumeContinuation(false)
                    session = BleSessionState.Idle
                    val issue = ConnectionIssue.recoverable(
                        ConnectionIssueCode.BLUETOOTH_POWERED_OFF,
                        "Bluetooth powered off"
                    )
                    _connectionState.value = ConnectionState.ConnectionLost(address, issue.message, issue)
                    onBleDisconnectedCallback?.invoke(address, issue, s.attemptId)
                }
                is BleSessionState.Discovering -> {
                    val address = s.peripheral.identifier.UUIDString
                    s.scope.cancel()
                    writeCharacteristic = null
                    readCharacteristic = null
                    writeReadyContinuation?.cancel()
                    writeReadyContinuation = null
                    resumeContinuation(false)
                    session = BleSessionState.Idle
                    val issue = ConnectionIssue.recoverable(
                        ConnectionIssueCode.BLUETOOTH_POWERED_OFF,
                        "Bluetooth powered off"
                    )
                    _connectionState.value = ConnectionState.ConnectionLost(address, issue.message, issue)
                    onBleDisconnectedCallback?.invoke(address, issue, s.attemptId)
                }
                is BleSessionState.Connected -> {
                    val address = s.peripheral.identifier.UUIDString
                    writeCharacteristic = null
                    readCharacteristic = null
                    writeReadyContinuation?.cancel()
                    writeReadyContinuation = null
                    session = BleSessionState.Idle
                    val issue = ConnectionIssue.recoverable(
                        ConnectionIssueCode.BLUETOOTH_POWERED_OFF,
                        "Bluetooth powered off"
                    )
                    _connectionState.value = ConnectionState.ConnectionLost(address, issue.message, issue)
                    onBleDisconnectedCallback?.invoke(address, issue, s.attemptId)
                }
                is BleSessionState.AwaitingReconnect -> {
                    val address = s.peripheral.identifier.UUIDString
                    writeCharacteristic = null
                    readCharacteristic = null
                    session = BleSessionState.Idle
                    val issue = ConnectionIssue.recoverable(
                        ConnectionIssueCode.BLUETOOTH_POWERED_OFF,
                        "Bluetooth powered off"
                    )
                    _connectionState.value = ConnectionState.ConnectionLost(address, issue.message, issue)
                    onBleDisconnectedCallback?.invoke(address, issue, s.attemptId)
                }
            }
        }
    }

    internal fun onPeripheralDiscovered(peripheral: CBPeripheral, advertisementData: Map<Any?, *>, rssi: Int) {
        val scanning = session as? BleSessionState.Scanning ?: return
        val advertisedName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        advertisementCache.put(buildAdvertisement(peripheral, advertisementData, advertisedName, rssi))
        val device = BleDevice(
            address = peripheral.identifier.UUIDString,
            name = peripheral.name ?: advertisedName,
            rssi = rssi
        )
        scanning.callback(device)
    }

    private fun buildAdvertisement(
        peripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        advertisedName: String?,
        rssi: Int,
    ): BleAdvertisement {
        @Suppress("UNCHECKED_CAST")
        val serviceUuids = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<CBUUID>)
            ?.map { BleUuids.canonicalize(it.UUIDString) }
            ?.toSet() ?: emptySet()

        val manufacturer: Map<Int, ByteArray> = (advertisementData[CBAdvertisementDataManufacturerDataKey] as? NSData)
            ?.let { data ->
                val bytes = data.toByteArray()
                if (bytes.size >= 2) {
                    val companyId = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
                    mapOf(companyId to bytes.copyOfRange(2, bytes.size))
                } else {
                    emptyMap()
                }
            } ?: emptyMap()

        @Suppress("UNCHECKED_CAST")
        val serviceData: Map<String, ByteArray> = (advertisementData[CBAdvertisementDataServiceDataKey] as? Map<CBUUID, NSData>)
            ?.mapKeys { (uuid, _) -> BleUuids.canonicalize(uuid.UUIDString) }
            ?.mapValues { (_, data) -> data.toByteArray() }
            ?: emptyMap()

        val connectable = (advertisementData[CBAdvertisementDataIsConnectable] as? NSNumber)?.boolValue ?: true

        return BleAdvertisement(
            address = peripheral.identifier.UUIDString,
            advertisedName = advertisedName,
            peripheralName = peripheral.name,
            rssi = rssi,
            advertisedServiceUuids = serviceUuids,
            manufacturerData = manufacturer,
            serviceData = serviceData,
            connectable = connectable,
            lastSeenMs = (NSDate().timeIntervalSince1970 * 1000.0).toLong(),
        )
    }

    internal fun onPeripheralConnected(peripheral: CBPeripheral) {
        // Forward only when this callback's peripheral matches the current
        // session's peripheral. A late callback for a peripheral we've already
        // moved past would otherwise inherit the new session's id.
        val s = session
        if (s.peripheral != peripheral) {
            Logger.w(
                "BleManager",
                "Stale onPeripheralConnected for ${peripheral.identifier.UUIDString}; current session peripheral=${s.peripheral?.identifier?.UUIDString}"
            )
            return
        }
        val sessionAttemptId = s.attemptId

        // Store peripheral UUID for state restoration
        NSUserDefaults.standardUserDefaults.setObject(
            peripheral.identifier.UUIDString,
            forKey = "FreeWheelLastPeripheralUUID"
        )

        transitionToDiscovering(peripheral, sessionAttemptId)
        _connectionState.value = ConnectionState.DiscoveringServices(
            peripheral.identifier.UUIDString
        )

        // Fast path: CoreBluetooth caches services + characteristics for previously
        // connected peripherals. If the cache is populated, skip discovery entirely.
        if (tryUseCachedServices(peripheral)) {
            Logger.d("BleManager", "Using cached services — skipping discovery")
            return
        }

        // Cache miss — discover all services (no filter).
        // Both DarknessBot and EUC World discover all services then identify
        // the wheel by name + available services. Filtering caused connection
        // failures on wheels whose services didn't match the hardcoded list.
        peripheral.discoverServices(serviceUUIDs = null)

        // Safety timeout — launched in the Discovering scope, so it's automatically
        // cancelled if we transition away from Discovering (e.g., discovery completes
        // normally or the connection drops).
        val discovering = session as? BleSessionState.Discovering ?: return
        val address = peripheral.identifier.UUIDString
        discovering.scope.launch {
            delay(15_000)
            // If we've transitioned away from Discovering, this scope is cancelled
            // and we never reach here. Safety check anyway:
            if (session !is BleSessionState.Discovering) return@launch
            Logger.w("BleManager", "Service discovery timed out after 15s for $address")
            val issue = ConnectionIssue.recoverable(
                ConnectionIssueCode.SERVICE_DISCOVERY_TIMED_OUT,
                "Service discovery timed out"
            )
            _connectionState.value = ConnectionState.Failed(
                error = issue.message,
                address = address,
                issue = issue,
            )
            centralManager?.cancelPeripheralConnection(peripheral)
            resumeContinuation(false)
        }
    }

    /**
     * Check if CoreBluetooth has cached services and characteristics from a previous connection.
     * If so, skip discovery and go directly to the completion path.
     *
     * Only treats the cache as valid if at least one **wheel-specific** service
     * (not a generic service like Battery or Device Info) has its characteristics
     * populated. Without this check, a generic cached service could satisfy the
     * predicate, causing completeServiceDiscovery() to fire before the actual
     * wheel service has been discovered.
     */
    private fun tryUseCachedServices(peripheral: CBPeripheral): Boolean {
        val cachedServices = peripheral.services ?: return false
        if (cachedServices.isEmpty()) return false

        // All wheel types use one of these service UUIDs
        val wheelServiceUuids = setOf(
            BleUuids.Kingsong.SERVICE.lowercase(),          // ffe0 (KS/GW/Vet/Leaperkim/IM-read/NB)
            BleUuids.InMotionV2.SERVICE.lowercase(),        // Nordic UART (IMv2/NBZ)
            BleUuids.InMotion.WRITE_SERVICE.lowercase(),    // ffe5 (IM write)
        )

        // Validate that at least one wheel-specific service has cached characteristics
        val hasWheelServiceWithCharacteristics = cachedServices.any { svc ->
            val cbService = svc as? CBService ?: return@any false
            if (cbService.characteristics.isNullOrEmpty()) return@any false
            val uuid = BleUuids.canonicalize(cbService.UUID.UUIDString)
            uuid in wheelServiceUuids
        }

        if (!hasWheelServiceWithCharacteristics) return false

        // Cache hit — complete connection immediately
        completeServiceDiscovery(peripheral)
        return true
    }

    internal fun onWillRestoreState(peripherals: List<CBPeripheral>?) {
        peripherals?.firstOrNull()?.let { peripheral ->
            // State restoration on iOS happens before any user-initiated
            // connect — there's no in-flight WCM session yet, so 0L is the
            // correct placeholder. The next user-driven connect will mint a
            // fresh attemptId and replace this session.
            session = BleSessionState.AwaitingReconnect(peripheral, attemptId = 0L)
            peripheral.delegate = peripheralDelegate
            Logger.d("BleManager", "Restored peripheral: ${peripheral.identifier.UUIDString}")
        }
    }

    /**
     * Map CoreBluetooth NSError codes to deterministic, locale-independent display strings.
     * CBError.Code values from CBErrorDomain — falls back to localizedDescription for
     * CBATTError codes and other domains.
     */
    private fun NSError.toDisconnectIssue(): ConnectionIssue {
        return when (code.toInt()) {
            0 -> ConnectionIssue.unknownRecoverable("Unknown error")
            6 -> ConnectionIssue.recoverable(ConnectionIssueCode.CONNECTION_TIMED_OUT, "Connection timed out")
            7 -> ConnectionIssue.recoverable(ConnectionIssueCode.PERIPHERAL_DISCONNECTED, "Peripheral disconnected")
            9 -> ConnectionIssue.recoverable(ConnectionIssueCode.INVALID_HANDLE, "Invalid handle")
            10 -> ConnectionIssue.recoverable(ConnectionIssueCode.CONNECTION_FAILED, "Connection failed")
            14 -> ConnectionIssue.terminal(ConnectionIssueCode.PEER_REMOVED_PAIRING, "Peer removed pairing")
            15 -> ConnectionIssue.terminal(ConnectionIssueCode.ENCRYPTION_TIMED_OUT, "Encryption timed out")
            else -> ConnectionIssue.unknownRecoverable(localizedDescription ?: "Disconnect error $code")
        }
    }

    internal fun onConnectionFailed(peripheral: CBPeripheral, error: NSError?) {
        val address = peripheral.identifier.UUIDString

        // Always release any same-address waiter BEFORE the peripheral guard.
        // Even a stale callback signals that the OS has finished its side of
        // the prior teardown, which is what awaitTeardownDrain blocks on. The
        // guard below still prevents stale state mutations from leaking into
        // the new session.
        teardownTracker.completeTeardown(address)

        // Reject anything whose callback peripheral is not exactly the
        // current session's peripheral, including the null case (Idle /
        // Scanning). Without the strict gate, a delayed didFailToConnect from
        // an abandoned attempt would still mark the connection as Failed
        // after the user has disconnected or started a new flow.
        val s = session
        if (s.peripheral != peripheral) {
            Logger.w(
                "BleManager",
                "Stale onConnectionFailed for $address; current session=${s::class.simpleName} peripheral=${s.peripheral?.identifier?.UUIDString}"
            )
            return
        }

        // Cancel discovering scope if we somehow got into that state
        (s as? BleSessionState.Discovering)?.scope?.cancel()

        val issue = error?.toDisconnectIssue()
            ?: ConnectionIssue.recoverable(ConnectionIssueCode.CONNECTION_FAILED, "Connection failed")
        session = BleSessionState.Idle
        writeCharacteristic = null
        readCharacteristic = null
        _connectionState.value = ConnectionState.Failed(
            error = issue.message,
            address = peripheral.identifier.UUIDString,
            issue = issue,
        )
        resumeContinuation(false)
    }

    internal fun onPeripheralDisconnected(peripheral: CBPeripheral, error: NSError?) {
        val address = peripheral.identifier.UUIDString
        val s = session

        // Always release any same-address waiter BEFORE the peripheral guard.
        // Even a stale callback signals OS-side teardown completion, which is
        // what awaitTeardownDrain blocks on. The guard below still prevents
        // stale state mutations from leaking through.
        teardownTracker.completeTeardown(address)

        // Reject late disconnect callbacks for a peripheral we've moved past
        // BEFORE touching the connect continuation. Earlier the unconditional
        // resumeContinuation(false) would fail the NEW session's pending
        // connect when a stale disconnect arrived from an abandoned attempt.
        // Strict gate (no null short-circuit) so Idle/Scanning also drops
        // here without touching state.
        if (s.peripheral != peripheral) {
            Logger.w(
                "BleManager",
                "Stale onPeripheralDisconnected for $address; current session=${s::class.simpleName} peripheral=${s.peripheral?.identifier?.UUIDString}"
            )
            return
        }

        // Safety net: resume any pending connect continuation owned by THIS
        // session. Now safe to call because we've proven the callback belongs
        // to the current session.
        resumeContinuation(false)

        // Check session for cleanup. Idle/Scanning is unreachable here because
        // the strict guard above already returned (s.peripheral would be null
        // in those states). Kept for exhaustive matching.
        when (s) {
            is BleSessionState.Idle, is BleSessionState.Scanning -> {
                return
            }
            is BleSessionState.Connecting -> {}
            is BleSessionState.Discovering -> s.scope.cancel()
            is BleSessionState.Connected -> {}
            is BleSessionState.AwaitingReconnect -> {}
        }

        // Clear characteristic references (will be re-set on reconnect via configureForWheel)
        writeReadyContinuation?.cancel()
        writeReadyContinuation = null
        writeCharacteristic = null
        readCharacteristic = null

        val issue = error?.toDisconnectIssue()
            ?: ConnectionIssue.recoverable(
                ConnectionIssueCode.PERIPHERAL_DISCONNECTED,
                "Peripheral disconnected"
            )
        // Unexpected disconnect — initiate OS-level auto-reconnect.
        // CoreBluetooth's connect() never times out and works in background.
        val sessionAttemptId = s.attemptId
        session = BleSessionState.AwaitingReconnect(peripheral, sessionAttemptId)
        _connectionState.value = ConnectionState.ConnectionLost(
            address = address,
            reason = issue.message,
            issue = issue,
        )
        Logger.d("BleManager", "Starting OS auto-reconnect for $address")
        peripheral.delegate = peripheralDelegate
        centralManager?.connectPeripheral(peripheral, options = null)
        // Notify WCM immediately so it transitions to ConnectionLost
        onBleDisconnectedCallback?.invoke(address, issue, sessionAttemptId)
    }

    internal fun onServicesDiscovered(peripheral: CBPeripheral, error: NSError?) {
        val discovering = session as? BleSessionState.Discovering
        if (discovering == null) {
            Logger.w("BleManager", "onServicesDiscovered called but not in Discovering state")
            return
        }
        // Reject late callbacks for a peripheral we've moved past BEFORE
        // touching counters or kicking off characteristic discovery — the
        // guard inside completeServiceDiscovery() is too late since the
        // counters would already be polluted.
        if (discovering.peripheral != peripheral) {
            Logger.w(
                "BleManager",
                "Stale onServicesDiscovered for ${peripheral.identifier.UUIDString}; current discovering peripheral=${discovering.peripheral.identifier.UUIDString}"
            )
            return
        }

        if (error != null) {
            discovering.scope.cancel()
            centralManager?.cancelPeripheralConnection(peripheral)
            session = BleSessionState.Idle
            writeCharacteristic = null
            readCharacteristic = null
            val issue = ConnectionIssue.recoverable(
                ConnectionIssueCode.SERVICE_DISCOVERY_FAILED,
                error.localizedDescription ?: "Service discovery failed"
            )
            _connectionState.value = ConnectionState.Failed(
                error = issue.message,
                address = peripheral.identifier.UUIDString,
                issue = issue,
            )
            resumeContinuation(false)
            return
        }

        // Capture count BEFORE kicking off characteristic discovery.
        // peripheral.services here contains exactly the services matching our
        // discoverServices(serviceUUIDs:) filter — not all services on the device.
        val services = peripheral.services ?: emptyList<Any>()
        discovering.expectedCount = services.size

        if (discovering.expectedCount == 0) {
            discovering.scope.cancel()
            centralManager?.cancelPeripheralConnection(peripheral)
            session = BleSessionState.Idle
            writeCharacteristic = null
            readCharacteristic = null
            val address = peripheral.identifier.UUIDString
            Logger.w("BleManager", "No matching services found on $address")
            val issue = ConnectionIssue.terminal(
                ConnectionIssueCode.NO_SUPPORTED_SERVICES,
                "No supported services found"
            )
            _connectionState.value = ConnectionState.Failed(
                error = issue.message,
                address = address,
                issue = issue,
            )
            resumeContinuation(false)
            return
        }

        // Discover characteristics for each service
        services.forEach { service ->
            (service as? CBService)?.let { cbService ->
                peripheral.discoverCharacteristics(null, cbService)
            }
        }
    }

    internal fun onCharacteristicsDiscovered(peripheral: CBPeripheral, service: CBService, error: NSError?) {
        val discovering = session as? BleSessionState.Discovering ?: return
        // Reject late characteristic-discovery callbacks for a peripheral
        // we've moved past BEFORE incrementing completedCount — otherwise an
        // old session's straggler can satisfy the new session's expectedCount
        // and trigger completeServiceDiscovery() with the wrong characteristics.
        if (discovering.peripheral != peripheral) {
            Logger.w(
                "BleManager",
                "Stale onCharacteristicsDiscovered for ${peripheral.identifier.UUIDString}; current discovering peripheral=${discovering.peripheral.identifier.UUIDString}"
            )
            return
        }

        val serviceUuid = service.UUID.UUIDString

        if (error != null) {
            Logger.w("BleManager", "Characteristic discovery error for service $serviceUuid: ${error.localizedDescription}")
        }

        // Increment per-event (Fix E). Counting events instead of unique UUIDs
        // means peripherals with duplicate service UUIDs still complete discovery
        // once every kicked-off discoverCharacteristics call has reported back.
        discovering.completedCount += 1

        val allServicesDiscovered = discovering.expectedCount > 0 &&
            discovering.completedCount >= discovering.expectedCount

        if (allServicesDiscovered) {
            completeServiceDiscovery(peripheral)
        }
    }

    /**
     * Shared completion path for service/characteristic discovery.
     * Called from both the cache-hit fast path and the normal discovery callback path.
     *
     * Guard: only completes if still in Discovering state. The transition from
     * Discovering to Connected is one-shot — a second call sees Connected and returns.
     * This replaces the old `serviceDiscoveryCompleted` boolean guard.
     */
    private fun completeServiceDiscovery(peripheral: CBPeripheral) {
        val discovering = session as? BleSessionState.Discovering ?: return
        // Guard against a stale completion for a peripheral we've moved past.
        if (discovering.peripheral != peripheral) {
            Logger.w(
                "BleManager",
                "Stale completeServiceDiscovery for ${peripheral.identifier.UUIDString}; current discovering peripheral=${discovering.peripheral.identifier.UUIDString}"
            )
            return
        }
        val sessionAttemptId = discovering.attemptId
        discovering.scope.cancel()

        // Build complete DiscoveredServices (canonicalize short CoreBluetooth UUIDs to 128-bit)
        val discoveredServices = peripheral.services?.mapNotNull { svc ->
            (svc as? CBService)?.let { cbService ->
                DiscoveredService(
                    uuid = BleUuids.canonicalize(cbService.UUID.UUIDString),
                    characteristics = cbService.characteristics?.mapNotNull { char ->
                        (char as? CBCharacteristic)?.let {
                            BleUuids.canonicalize(it.UUID.UUIDString)
                        }
                    } ?: emptyList()
                )
            }
        } ?: emptyList()

        // Invoke callback — this triggers wheel type detection in WCM.
        // configureForWheel() is called later by the WCM effect executor,
        // which calls setupCharacteristics() to enable notifications.
        onServicesDiscoveredCallback?.invoke(
            DiscoveredServices(discoveredServices),
            peripheral.name,
            sessionAttemptId,
        )

        // Transition to Connected
        transitionToConnected(peripheral, sessionAttemptId)
        resumeContinuation(true)
    }

    /**
     * Match read/write characteristics against the configured UUIDs and enable notifications.
     * Must be called after configureForWheel() has set the UUID fields.
     *
     * Returns true if the read characteristic was bound. Returns false when
     * either the read service or read characteristic is missing — the caller
     * surfaces this as a connection failure. Write characteristic is best-effort:
     * a missing write isn't fatal (some flows are read-only).
     */
    private fun setupCharacteristics(peripheral: CBPeripheral): Boolean {
        val rServiceUuid = readServiceUuid?.lowercase()
        val rCharUuid = readCharUuid?.lowercase()
        val wServiceUuid = writeServiceUuid?.lowercase()
        val wCharUuid = writeCharUuid?.lowercase()

        if (rServiceUuid == null || rCharUuid == null) {
            Logger.w("BleManager", "Read UUIDs not configured, cannot set up characteristics")
            return false
        }

        peripheral.services?.forEach { svc ->
            val cbService = svc as? CBService ?: return@forEach
            val serviceUuid = BleUuids.canonicalize(cbService.UUID.UUIDString)

            if (serviceUuid == rServiceUuid) {
                readCharacteristic = cbService.characteristics?.firstOrNull { char ->
                    (char as? CBCharacteristic)?.let {
                        BleUuids.canonicalize(it.UUID.UUIDString) == rCharUuid
                    } ?: false
                } as? CBCharacteristic

                readCharacteristic?.let { char ->
                    peripheral.setNotifyValue(true, char)
                    Logger.d("BleManager", "Subscribed to read characteristic: $rCharUuid")
                }
            }

            if (wServiceUuid != null && wCharUuid != null && serviceUuid == wServiceUuid) {
                writeCharacteristic = cbService.characteristics?.firstOrNull { char ->
                    (char as? CBCharacteristic)?.let {
                        BleUuids.canonicalize(it.UUID.UUIDString) == wCharUuid
                    } ?: false
                } as? CBCharacteristic

                if (writeCharacteristic != null) {
                    Logger.d("BleManager", "Found write characteristic: $wCharUuid")
                }
            }
        }

        // Query negotiated MTU for chunk sizing
        maxWriteLength = peripheral.maximumWriteValueLengthForType(
            CBCharacteristicWriteWithoutResponse
        ).toInt()
        Logger.d("BleManager", "Max write length: $maxWriteLength bytes")

        if (readCharacteristic == null) {
            Logger.w("BleManager", "Read characteristic not found: service=$rServiceUuid char=$rCharUuid")
        }
        if (writeCharacteristic == null && wCharUuid != null) {
            Logger.w("BleManager", "Write characteristic not found: service=$wServiceUuid char=$wCharUuid")
        }

        return readCharacteristic != null
    }

    internal fun onCharacteristicValueUpdated(characteristic: CBCharacteristic, error: NSError?) {
        if (error != null) {
            Logger.w("BleManager", "Characteristic update error: ${error.localizedDescription}")
            onBleErrorCallback?.invoke()
            return
        }

        // Forward only when the characteristic belongs to the current session's
        // peripheral. CoreBluetooth can buffer notifications from a previously-
        // subscribed characteristic of an old session; without this check, those
        // late frames would inherit the new session's id and slip past the WCM
        // staleness guard.
        val s = session
        val sessionPeripheral = s.peripheral
        val charPeripheral = characteristic.service?.peripheral
        if (sessionPeripheral == null || charPeripheral == null || sessionPeripheral != charPeripheral) {
            return
        }

        val data = characteristic.value?.toByteArray()
        if (data != null) {
            Logger.d("BleManager", "Data received: ${data.size} bytes from ${characteristic.UUID.UUIDString}")
            onDataReceivedCallback?.invoke(data, s.attemptId)
        }
    }

    private companion object {
        /**
         * How long [awaitTeardownDrain] blocks waiting for the OS to confirm
         * a prior session's teardown for the same peripheral. CoreBluetooth
         * typically delivers the drain callback within ~50ms; 2s gives a
         * comfortable margin while keeping connect() interactive.
         */
        const val TEARDOWN_DRAIN_TIMEOUT_MS = 2_000L
    }
}

// ==================== Central Manager Delegate ====================

@OptIn(ExperimentalForeignApi::class)
private class CBCentralManagerDelegateImpl(
    private val manager: BleManager
) : NSObject(), CBCentralManagerDelegateProtocol {

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        manager.onStateUpdated(central.state)
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber
    ) {
        manager.onPeripheralDiscovered(didDiscoverPeripheral, advertisementData, RSSI.intValue)
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        manager.onPeripheralConnected(didConnectPeripheral)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didFailToConnectPeripheral: CBPeripheral,
        error: NSError?
    ) {
        manager.onConnectionFailed(didFailToConnectPeripheral, error)
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?
    ) {
        manager.onPeripheralDisconnected(didDisconnectPeripheral, error)
    }

    override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) {
        @Suppress("UNCHECKED_CAST")
        val peripherals = willRestoreState[CBCentralManagerRestoredStatePeripheralsKey] as? List<CBPeripheral>
        manager.onWillRestoreState(peripherals)
    }
}

// ==================== Peripheral Delegate ====================

@OptIn(ExperimentalForeignApi::class)
private class CBPeripheralDelegateImpl(
    private val manager: BleManager
) : NSObject(), CBPeripheralDelegateProtocol {

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
        manager.onServicesDiscovered(peripheral, didDiscoverServices)
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?
    ) {
        manager.onCharacteristicsDiscovered(peripheral, didDiscoverCharacteristicsForService, error)
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?
    ) {
        manager.onCharacteristicValueUpdated(didUpdateValueForCharacteristic, error)
    }

    override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
        manager.onReadyToWrite()
    }
}

/**
 * iOS BLE connection wrapper.
 * Wraps CBPeripheral for type-safe connection handling.
 */
@OptIn(ExperimentalForeignApi::class)
actual class BleConnection(
    private val peripheral: CBPeripheral
) {
    val address: String get() = peripheral.identifier.UUIDString
    val name: String? get() = peripheral.name
    val isConnected: Boolean get() = peripheral.state == CBPeripheralStateConnected
}

// ==================== Service UUID Filter ====================

/**
 * Returns the 3 BLE service UUIDs used by all supported wheel types.
 * Passing these to discoverServices() skips standard services (Generic Access,
 * Device Information, Battery, etc.) that add ~50-100ms each to discovery.
 */
private fun wheelServiceUUIDs(): List<CBUUID> = listOf(
    // Kingsong, Gotway/Veteran, Ninebot, InMotion V1 read
    CBUUID.UUIDWithString(BleUuids.Kingsong.SERVICE),
    // InMotion V1 write
    CBUUID.UUIDWithString(BleUuids.InMotion.WRITE_SERVICE),
    // Nordic UART — InMotionV2, NinebotZ
    CBUUID.UUIDWithString(BleUuids.InMotionV2.SERVICE),
)

// ==================== UUID Normalization ====================
//
// Single source of truth for UUID canonicalization lives in
// [BleUuids.canonicalize] (commonMain) so the same logic is exercised by
// Android scanRecord parsing, iOS CoreBluetooth strings, and tests.

// ==================== Extension Functions ====================

/**
 * Convert ByteArray to NSData.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    if (this.isEmpty()) return NSData()
    return this.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = this.size.toULong())
    }
}

/**
 * Convert NSData to ByteArray.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = this.length.toInt()
    if (size == 0) return ByteArray(0)

    return ByteArray(size).apply {
        usePinned { pinned ->
            memcpy(pinned.addressOf(0), this@toByteArray.bytes, this@toByteArray.length)
        }
    }
}
