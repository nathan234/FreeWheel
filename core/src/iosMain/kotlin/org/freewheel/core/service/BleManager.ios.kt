package org.freewheel.core.service

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

        data object Idle : BleSessionState()
        data class Scanning(val callback: (BleDevice) -> Unit) : BleSessionState()
        data class Connecting(override val peripheral: CBPeripheral) : BleSessionState()
        data class Discovering(
            override val peripheral: CBPeripheral,
            /** Cancelled automatically on any transition away from Discovering. */
            val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job()),
            // Track completion by event count, not unique UUID set (Fix E):
            // some peripherals expose the same service UUID twice (e.g., a primary
            // and an included service that share a UUID), and a Set<String> of UUIDs
            // would never reach expectedCount, leaving us stuck in Discovering.
            var completedCount: Int = 0,
            var expectedCount: Int = 0,
        ) : BleSessionState()
        data class Connected(override val peripheral: CBPeripheral) : BleSessionState()
        data class AwaitingReconnect(override val peripheral: CBPeripheral) : BleSessionState()
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
    private var onDataReceivedCallback: ((ByteArray) -> Unit)? = null
    private var onBleErrorCallback: (() -> Unit)? = null
    private var onBleDisconnectedCallback: ((String, String) -> Unit)? = null
    private var onServicesDiscoveredCallback: ((DiscoveredServices, String?) -> Unit)? = null

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

    private fun transitionToIdle() {
        when (val s = session) {
            is BleSessionState.Idle -> return
            is BleSessionState.Scanning -> centralManager?.stopScan()
            is BleSessionState.Connecting -> {
                centralManager?.cancelPeripheralConnection(s.peripheral)
            }
            is BleSessionState.Discovering -> {
                s.scope.cancel()
                centralManager?.cancelPeripheralConnection(s.peripheral)
            }
            is BleSessionState.Connected -> {
                centralManager?.cancelPeripheralConnection(s.peripheral)
            }
            is BleSessionState.AwaitingReconnect -> {
                centralManager?.cancelPeripheralConnection(s.peripheral)
            }
        }
        resumeContinuation(false)
        writeReadyContinuation?.cancel()
        writeReadyContinuation = null
        writeCharacteristic = null
        readCharacteristic = null
        session = BleSessionState.Idle
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun transitionToScanning(callback: (BleDevice) -> Unit) {
        when (val s = session) {
            is BleSessionState.Idle -> {}
            is BleSessionState.Scanning -> centralManager?.stopScan() // reset dedup filter
            is BleSessionState.Connecting -> {
                centralManager?.cancelPeripheralConnection(s.peripheral)
                resumeContinuation(false)
            }
            is BleSessionState.Discovering -> {
                s.scope.cancel()
                centralManager?.cancelPeripheralConnection(s.peripheral)
                resumeContinuation(false)
            }
            is BleSessionState.Connected -> {
                centralManager?.cancelPeripheralConnection(s.peripheral)
            }
            is BleSessionState.AwaitingReconnect -> {
                centralManager?.cancelPeripheralConnection(s.peripheral)
            }
        }
        writeCharacteristic = null
        readCharacteristic = null
        session = BleSessionState.Scanning(callback)
        _connectionState.value = ConnectionState.Scanning
    }

    private fun transitionToConnecting(peripheral: CBPeripheral) {
        when (val s = session) {
            is BleSessionState.Idle -> {}
            is BleSessionState.Scanning -> centralManager?.stopScan()
            is BleSessionState.Connecting -> {
                centralManager?.cancelPeripheralConnection(s.peripheral)
                resumeContinuation(false)
            }
            is BleSessionState.Discovering -> {
                s.scope.cancel()
                centralManager?.cancelPeripheralConnection(s.peripheral)
                resumeContinuation(false)
            }
            is BleSessionState.Connected -> {
                centralManager?.cancelPeripheralConnection(s.peripheral)
            }
            is BleSessionState.AwaitingReconnect -> {
                centralManager?.cancelPeripheralConnection(s.peripheral)
            }
        }
        writeCharacteristic = null
        readCharacteristic = null
        session = BleSessionState.Connecting(peripheral)
    }

    private fun transitionToDiscovering(peripheral: CBPeripheral) {
        when (val s = session) {
            is BleSessionState.Idle -> {}
            is BleSessionState.Scanning -> centralManager?.stopScan()
            is BleSessionState.Connecting -> {} // normal path: Connecting -> Discovering
            is BleSessionState.Discovering -> s.scope.cancel()
            is BleSessionState.Connected -> {}
            is BleSessionState.AwaitingReconnect -> {} // reconnect completed
        }
        session = BleSessionState.Discovering(peripheral)
    }

    private fun transitionToConnected(peripheral: CBPeripheral) {
        when (val s = session) {
            is BleSessionState.Idle -> {}
            is BleSessionState.Scanning -> centralManager?.stopScan()
            is BleSessionState.Connecting -> {}
            is BleSessionState.Discovering -> s.scope.cancel() // normal path
            is BleSessionState.Connected -> {}
            is BleSessionState.AwaitingReconnect -> {}
        }
        session = BleSessionState.Connected(peripheral)
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
     */
    fun setDataReceivedCallback(callback: (ByteArray) -> Unit) {
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
     * Called with (address, reason). Not called for user-initiated disconnects.
     */
    fun setBleDisconnectedCallback(callback: (String, String) -> Unit) {
        onBleDisconnectedCallback = callback
    }

    /**
     * Set callback for when services are discovered.
     */
    fun setServicesDiscoveredCallback(callback: (DiscoveredServices, String?) -> Unit) {
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

    actual override suspend fun connect(address: String): Boolean {
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
            _connectionState.value = ConnectionState.Failed(
                error = "Peripheral not found",
                address = address
            )
            return false
        }

        val peripheral = peripherals.first() as CBPeripheral
        peripheral.delegate = peripheralDelegate

        transitionToConnecting(peripheral)
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
        _bluetoothState.value = when (state) {
            CBManagerStatePoweredOn -> BluetoothAdapterState.POWERED_ON
            CBManagerStatePoweredOff -> BluetoothAdapterState.POWERED_OFF
            CBManagerStateUnauthorized -> BluetoothAdapterState.UNAUTHORIZED
            CBManagerStateUnsupported -> BluetoothAdapterState.UNSUPPORTED
            CBManagerStateResetting -> BluetoothAdapterState.RESETTING
            else -> BluetoothAdapterState.UNKNOWN
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
                    _connectionState.value = ConnectionState.ConnectionLost(address, "Bluetooth powered off")
                    onBleDisconnectedCallback?.invoke(address, "Bluetooth powered off")
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
                    _connectionState.value = ConnectionState.ConnectionLost(address, "Bluetooth powered off")
                    onBleDisconnectedCallback?.invoke(address, "Bluetooth powered off")
                }
                is BleSessionState.Connected -> {
                    val address = s.peripheral.identifier.UUIDString
                    writeCharacteristic = null
                    readCharacteristic = null
                    writeReadyContinuation?.cancel()
                    writeReadyContinuation = null
                    session = BleSessionState.Idle
                    _connectionState.value = ConnectionState.ConnectionLost(address, "Bluetooth powered off")
                    onBleDisconnectedCallback?.invoke(address, "Bluetooth powered off")
                }
                is BleSessionState.AwaitingReconnect -> {
                    val address = s.peripheral.identifier.UUIDString
                    writeCharacteristic = null
                    readCharacteristic = null
                    session = BleSessionState.Idle
                    _connectionState.value = ConnectionState.ConnectionLost(address, "Bluetooth powered off")
                    onBleDisconnectedCallback?.invoke(address, "Bluetooth powered off")
                }
            }
        }
    }

    internal fun onPeripheralDiscovered(peripheral: CBPeripheral, advertisementData: Map<Any?, *>, rssi: Int) {
        val scanning = session as? BleSessionState.Scanning ?: return
        val advertisedName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        val device = BleDevice(
            address = peripheral.identifier.UUIDString,
            name = peripheral.name ?: advertisedName,
            rssi = rssi
        )
        scanning.callback(device)
    }

    internal fun onPeripheralConnected(peripheral: CBPeripheral) {
        // Store peripheral UUID for state restoration
        NSUserDefaults.standardUserDefaults.setObject(
            peripheral.identifier.UUIDString,
            forKey = "FreeWheelLastPeripheralUUID"
        )

        transitionToDiscovering(peripheral)
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
            _connectionState.value = ConnectionState.Failed(
                error = "Service discovery timed out",
                address = address
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
            val uuid = expandCoreBluetoothUuid(cbService.UUID.UUIDString).lowercase()
            uuid in wheelServiceUuids
        }

        if (!hasWheelServiceWithCharacteristics) return false

        // Cache hit — complete connection immediately
        completeServiceDiscovery(peripheral)
        return true
    }

    internal fun onWillRestoreState(peripherals: List<CBPeripheral>?) {
        peripherals?.firstOrNull()?.let { peripheral ->
            session = BleSessionState.AwaitingReconnect(peripheral)
            peripheral.delegate = peripheralDelegate
            Logger.d("BleManager", "Restored peripheral: ${peripheral.identifier.UUIDString}")
        }
    }

    /**
     * Map CoreBluetooth NSError codes to deterministic, locale-independent display strings.
     * CBError.Code values from CBErrorDomain — falls back to localizedDescription for
     * CBATTError codes and other domains.
     */
    private fun NSError.toDisconnectDisplayText(): String {
        return when (code.toInt()) {
            0 -> "Unknown error"
            6 -> "Connection timed out"
            7 -> "Peripheral disconnected"
            9 -> "Invalid handle"
            10 -> "Connection failed"
            14 -> "Peer removed pairing"
            15 -> "Encryption timed out"
            else -> localizedDescription ?: "Disconnect error $code"
        }
    }

    internal fun onConnectionFailed(peripheral: CBPeripheral, error: NSError?) {
        // Cancel discovering scope if we somehow got into that state
        (session as? BleSessionState.Discovering)?.scope?.cancel()

        val errorMessage = error?.toDisconnectDisplayText() ?: "Connection failed"
        session = BleSessionState.Idle
        writeCharacteristic = null
        readCharacteristic = null
        _connectionState.value = ConnectionState.Failed(
            error = errorMessage,
            address = peripheral.identifier.UUIDString
        )
        resumeContinuation(false)
    }

    internal fun onPeripheralDisconnected(peripheral: CBPeripheral, error: NSError?) {
        // Safety net: resume any pending connect continuation
        resumeContinuation(false)

        val address = peripheral.identifier.UUIDString

        // Check session to determine if this was intentional.
        // disconnect() transitions to Idle BEFORE the OS fires this callback,
        // so Idle/Scanning means intentional. Everything else is unexpected.
        when (val s = session) {
            is BleSessionState.Idle, is BleSessionState.Scanning -> {
                // Intentional disconnect — cleanup already done by transitionToIdle/transitionToScanning
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

        if (error != null) {
            // Unexpected disconnect — initiate OS-level auto-reconnect.
            // CoreBluetooth's connect() never times out and works in background.
            val reason = error.toDisconnectDisplayText()
            session = BleSessionState.AwaitingReconnect(peripheral)
            _connectionState.value = ConnectionState.ConnectionLost(
                address = address,
                reason = reason
            )
            Logger.d("BleManager", "Starting OS auto-reconnect for $address")
            peripheral.delegate = peripheralDelegate
            centralManager?.connectPeripheral(peripheral, options = null)
            // Notify WCM immediately so it transitions to ConnectionLost
            onBleDisconnectedCallback?.invoke(address, reason)
        } else {
            // Graceful disconnect without error from an active state
            session = BleSessionState.Idle
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    internal fun onServicesDiscovered(peripheral: CBPeripheral, error: NSError?) {
        val discovering = session as? BleSessionState.Discovering
        if (discovering == null) {
            Logger.w("BleManager", "onServicesDiscovered called but not in Discovering state")
            return
        }

        if (error != null) {
            discovering.scope.cancel()
            centralManager?.cancelPeripheralConnection(peripheral)
            session = BleSessionState.Idle
            writeCharacteristic = null
            readCharacteristic = null
            _connectionState.value = ConnectionState.Failed(
                error = error.localizedDescription ?: "Service discovery failed",
                address = peripheral.identifier.UUIDString
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
            _connectionState.value = ConnectionState.Failed(
                error = "No supported services found",
                address = address
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
        discovering.scope.cancel()

        // Build complete DiscoveredServices (expand short CoreBluetooth UUIDs to 128-bit)
        val discoveredServices = peripheral.services?.mapNotNull { svc ->
            (svc as? CBService)?.let { cbService ->
                DiscoveredService(
                    uuid = expandCoreBluetoothUuid(cbService.UUID.UUIDString),
                    characteristics = cbService.characteristics?.mapNotNull { char ->
                        (char as? CBCharacteristic)?.let {
                            expandCoreBluetoothUuid(it.UUID.UUIDString)
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
            peripheral.name
        )

        // Transition to Connected
        transitionToConnected(peripheral)
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
            val serviceUuid = expandCoreBluetoothUuid(cbService.UUID.UUIDString).lowercase()

            if (serviceUuid == rServiceUuid) {
                readCharacteristic = cbService.characteristics?.firstOrNull { char ->
                    (char as? CBCharacteristic)?.let {
                        expandCoreBluetoothUuid(it.UUID.UUIDString).lowercase() == rCharUuid
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
                        expandCoreBluetoothUuid(it.UUID.UUIDString).lowercase() == wCharUuid
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

        val data = characteristic.value?.toByteArray()
        if (data != null) {
            Logger.d("BleManager", "Data received: ${data.size} bytes from ${characteristic.UUID.UUIDString}")
            onDataReceivedCallback?.invoke(data)
        }
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

/**
 * Expand CoreBluetooth short UUID strings to full 128-bit format.
 *
 * CoreBluetooth returns short UUIDs for standard Bluetooth SIG services:
 * - 4-char: "FFE0" -> "0000FFE0-0000-1000-8000-00805F9B34FB"
 * - 8-char: "0000FFE0" -> "0000FFE0-0000-1000-8000-00805F9B34FB"
 * - Full 128-bit UUIDs are returned as-is.
 */
private fun expandCoreBluetoothUuid(uuidString: String): String {
    val BLE_BASE_SUFFIX = "-0000-1000-8000-00805F9B34FB"
    return when (uuidString.length) {
        4 -> "0000$uuidString$BLE_BASE_SUFFIX"
        8 -> "$uuidString$BLE_BASE_SUFFIX"
        else -> uuidString
    }
}

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
