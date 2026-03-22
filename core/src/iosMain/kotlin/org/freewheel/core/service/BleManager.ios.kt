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

    // CoreBluetooth objects
    private var centralManager: CBCentralManager? = null
    private var currentPeripheral: CBPeripheral? = null
    private var writeCharacteristic: CBCharacteristic? = null
    private var readCharacteristic: CBCharacteristic? = null

    // UUID configuration
    private var readServiceUuid: String? = null
    private var readCharUuid: String? = null
    private var writeServiceUuid: String? = null
    private var writeCharUuid: String? = null

    // Callbacks
    private var onDataReceivedCallback: ((ByteArray) -> Unit)? = null
    private var onBleErrorCallback: (() -> Unit)? = null
    private var onBleDisconnectedCallback: ((String, String) -> Unit)? = null
    private var onServicesDiscoveredCallback: ((DiscoveredServices, String?) -> Unit)? = null
    private var scanCallback: ((BleDevice) -> Unit)? = null

    // Disconnect tracking — prevents auto-reconnect on user-initiated disconnect
    private var disconnectRequested = false

    // Connection continuation for suspend function (protected by continuationLock)
    private var connectionContinuation: CancellableContinuation<Boolean>? = null
    private val continuationLock = Lock()

    // Track which services have completed characteristic discovery
    private val discoveredServiceUuids = mutableSetOf<String>()
    // Number of services we kicked off characteristic discovery for
    private var expectedServiceCount: Int = 0
    // Timeout job for service/characteristic discovery phase
    private var serviceDiscoveryTimeoutJob: Job? = null
    // Guard against completeServiceDiscovery being called twice (timeout + callback race)
    private var serviceDiscoveryCompleted: Boolean = false

    // Delegate instances (prevent garbage collection)
    private var centralDelegate: CBCentralManagerDelegateImpl? = null
    private var peripheralDelegate: CBPeripheralDelegateImpl? = null

    private val _bluetoothState = MutableStateFlow(BluetoothAdapterState.UNKNOWN)
    actual override val bluetoothState: StateFlow<BluetoothAdapterState> = _bluetoothState.asStateFlow()

    actual override val connectionState: StateFlow<ConnectionState>
        get() = _connectionState.asStateFlow()

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
     */
    override fun configureForWheel(
        readServiceUuid: String,
        readCharUuid: String,
        writeServiceUuid: String,
        writeCharUuid: String
    ) {
        this.readServiceUuid = readServiceUuid
        this.readCharUuid = readCharUuid
        this.writeServiceUuid = writeServiceUuid
        this.writeCharUuid = writeCharUuid
        // Set up characteristics now that UUIDs are configured
        currentPeripheral?.let { setupCharacteristics(it) }
    }

    actual override suspend fun connect(address: String): Boolean {
        val central = centralManager ?: run {
            initialize()
            centralManager!!
        }

        // Check if Bluetooth is powered on
        if (central.state != CBManagerStatePoweredOn) {
            throw IllegalStateException("Bluetooth is not powered on. State: ${central.state}")
        }

        disconnectRequested = false
        _connectionState.value = ConnectionState.Connecting(address)

        serviceDiscoveryCompleted = false

        return suspendCancellableCoroutine { continuation ->
            continuationLock.withLock {
                connectionContinuation = continuation
            }

            // Try to retrieve peripheral by UUID
            val uuid = NSUUID(uUIDString = address)
            val peripherals = central.retrievePeripheralsWithIdentifiers(listOf(uuid))

            if (peripherals.isNotEmpty()) {
                val peripheral = peripherals.first() as CBPeripheral
                currentPeripheral = peripheral
                peripheral.delegate = peripheralDelegate
                central.connectPeripheral(peripheral, options = null)
            } else {
                // Peripheral not found, need to scan
                _connectionState.value = ConnectionState.Failed(
                    error = "Peripheral not found",
                    address = address
                )
                continuationLock.withLock {
                    connectionContinuation?.resume(false) {}
                    connectionContinuation = null
                }
            }

            continuation.invokeOnCancellation {
                currentPeripheral?.let { central.cancelPeripheralConnection(it) }
                continuationLock.withLock {
                    connectionContinuation = null
                }
            }
        }
    }

    actual override suspend fun disconnect() {
        disconnectRequested = true
        serviceDiscoveryTimeoutJob?.cancel()
        serviceDiscoveryTimeoutJob = null
        // Unblock any pending connect() coroutine BEFORE cancelling BLE
        continuationLock.withLock {
            connectionContinuation?.resume(false) {}
            connectionContinuation = null
        }
        currentPeripheral?.let { peripheral ->
            centralManager?.cancelPeripheralConnection(peripheral)
        }
        currentPeripheral = null
        writeCharacteristic = null
        readCharacteristic = null
        discoveredServiceUuids.clear()
        expectedServiceCount = 0
        serviceDiscoveryCompleted = false
        _connectionState.value = ConnectionState.Disconnected
    }

    actual override suspend fun write(data: ByteArray): Boolean {
        val peripheral = currentPeripheral ?: return false
        val characteristic = writeCharacteristic ?: return false

        if (peripheral.state != CBPeripheralStateConnected) {
            return false
        }

        val nsData = data.toNSData()
        peripheral.writeValue(nsData, characteristic, CBCharacteristicWriteWithoutResponse)
        return true
    }

    /**
     * Write data with chunking for protocols that need it (e.g., InMotion V1).
     */
    suspend fun writeChunked(data: ByteArray, chunkSize: Int = 20, delayMs: Long = 20): Boolean {
        val peripheral = currentPeripheral ?: return false
        val characteristic = writeCharacteristic ?: return false

        if (peripheral.state != CBPeripheralStateConnected) {
            return false
        }

        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            val nsData = chunk.toNSData()
            peripheral.writeValue(nsData, characteristic, CBCharacteristicWriteWithoutResponse)

            offset += chunkSize
            if (offset < data.size) {
                delay(delayMs)
            }
        }
        return true
    }

    override fun destroy() {
        scope.cancel()
        centralManager = null
        centralDelegate = null
        peripheralDelegate = null
    }

    actual override suspend fun startScan(onDeviceFound: (BleDevice) -> Unit) {
        val central = centralManager ?: run {
            initialize()
            centralManager!!
        }

        if (central.state != CBManagerStatePoweredOn) {
            return
        }

        // Stop any existing scan to reset CoreBluetooth's deduplication filter.
        // Without this, peripherals discovered in a previous scan session
        // won't be reported again (CoreBluetooth dedup is per-session).
        central.stopScan()

        scanCallback = onDeviceFound
        _connectionState.value = ConnectionState.Scanning

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

        scanCallback = onDeviceFound
        _connectionState.value = ConnectionState.Scanning

        central.scanForPeripheralsWithServices(
            serviceUUIDs = listOf(CBUUID.UUIDWithString(serviceUuid)),
            options = null
        )
    }

    actual override suspend fun stopScan() {
        centralManager?.stopScan()
        scanCallback = null
        if (_connectionState.value is ConnectionState.Scanning) {
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
        // BT adapter state (powered off, unauthorized, unsupported) is surfaced
        // exclusively via bluetoothState. Connection lifecycle (connecting, connected,
        // disconnected, failed) is a separate concern in connectionState.
        // When BT powers off mid-connection, CoreBluetooth fires didDisconnectPeripheral
        // which sets ConnectionLost — no need to duplicate that here.
    }

    internal fun onPeripheralDiscovered(peripheral: CBPeripheral, advertisementData: Map<Any?, *>, rssi: Int) {
        val advertisedName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        val device = BleDevice(
            address = peripheral.identifier.UUIDString,
            name = peripheral.name ?: advertisedName,
            rssi = rssi
        )
        scanCallback?.invoke(device)
    }

    internal fun onPeripheralConnected(peripheral: CBPeripheral) {
        // Store peripheral UUID for state restoration
        NSUserDefaults.standardUserDefaults.setObject(
            peripheral.identifier.UUIDString,
            forKey = "FreeWheelLastPeripheralUUID"
        )

        _connectionState.value = ConnectionState.DiscoveringServices(
            peripheral.identifier.UUIDString
        )
        discoveredServiceUuids.clear()
        expectedServiceCount = 0

        // Fast path: CoreBluetooth caches services + characteristics for previously
        // connected peripherals. If the cache is populated, skip discovery entirely.
        if (tryUseCachedServices(peripheral)) {
            Logger.d("BleManager", "Using cached services — skipping discovery")
            return
        }

        // Cache miss — discover from scratch
        peripheral.discoverServices(serviceUUIDs = wheelServiceUUIDs())

        // Safety timeout — if service/characteristic discovery doesn't complete
        // within 15 seconds, fail the connection instead of hanging forever.
        startDiscoveryTimeout(peripheral)
    }

    /**
     * Check if CoreBluetooth has cached services and characteristics from a previous connection.
     * If so, skip discovery and go directly to the completion path.
     */
    private fun tryUseCachedServices(peripheral: CBPeripheral): Boolean {
        val cachedServices = peripheral.services ?: return false
        if (cachedServices.isEmpty()) return false

        val targetUuids = wheelServiceUUIDs().map { it.UUIDString.lowercase() }.toSet()

        // Check that at least one target service is cached WITH its characteristics
        val hasMatchWithCharacteristics = cachedServices.any { svc ->
            val cbService = svc as? CBService ?: return@any false
            cbService.UUID.UUIDString.lowercase() in targetUuids &&
                !cbService.characteristics.isNullOrEmpty()
        }

        if (!hasMatchWithCharacteristics) return false

        // Cache hit — complete connection immediately
        completeServiceDiscovery(peripheral)
        return true
    }

    private fun startDiscoveryTimeout(peripheral: CBPeripheral) {
        serviceDiscoveryTimeoutJob?.cancel()
        serviceDiscoveryTimeoutJob = scope.launch {
            delay(15_000)
            // Check under lock — completeServiceDiscovery may have won the race
            val alreadyCompleted = continuationLock.withLock { serviceDiscoveryCompleted }
            if (alreadyCompleted) return@launch
            val address = peripheral.identifier.UUIDString
            Logger.w("BleManager", "Service discovery timed out after 15s for $address")
            _connectionState.value = ConnectionState.Failed(
                error = "Service discovery timed out",
                address = address
            )
            centralManager?.cancelPeripheralConnection(peripheral)
            continuationLock.withLock {
                connectionContinuation?.resume(false) {}
                connectionContinuation = null
            }
        }
    }

    internal fun onWillRestoreState(peripherals: List<CBPeripheral>?) {
        peripherals?.firstOrNull()?.let { peripheral ->
            currentPeripheral = peripheral
            peripheral.delegate = peripheralDelegate
            Logger.d("BleManager", "Restored peripheral: ${peripheral.identifier.UUIDString}")
        }
    }

    internal fun onConnectionFailed(peripheral: CBPeripheral, error: NSError?) {
        serviceDiscoveryTimeoutJob?.cancel()
        serviceDiscoveryTimeoutJob = null
        val errorMessage = error?.localizedDescription ?: "Connection failed"
        _connectionState.value = ConnectionState.Failed(error = errorMessage, address = peripheral.identifier.UUIDString)

        continuationLock.withLock {
            connectionContinuation?.resume(false) {}
            connectionContinuation = null
        }
    }

    internal fun onPeripheralDisconnected(peripheral: CBPeripheral, error: NSError?) {
        serviceDiscoveryTimeoutJob?.cancel()
        serviceDiscoveryTimeoutJob = null
        // Safety net: resume any pending connect continuation
        continuationLock.withLock {
            connectionContinuation?.resume(false) {}
            connectionContinuation = null
        }
        val address = peripheral.identifier.UUIDString

        // Clear characteristic references (will be re-set on reconnect via configureForWheel)
        writeCharacteristic = null
        readCharacteristic = null
        discoveredServiceUuids.clear()
        expectedServiceCount = 0
        serviceDiscoveryCompleted = false

        if (!disconnectRequested && error != null) {
            // Unexpected disconnect — initiate OS-level auto-reconnect.
            // CoreBluetooth's connect() never times out and works in background.
            // Keep the peripheral reference alive so CoreBluetooth can reconnect.
            val reason = error.localizedDescription ?: "Unknown error"
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
            // User-initiated disconnect — clean up fully
            currentPeripheral = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    internal fun onServicesDiscovered(peripheral: CBPeripheral, error: NSError?) {
        if (error != null) {
            serviceDiscoveryTimeoutJob?.cancel()
            serviceDiscoveryTimeoutJob = null
            _connectionState.value = ConnectionState.Failed(
                error = error.localizedDescription ?: "Service discovery failed",
                address = peripheral.identifier.UUIDString
            )
            continuationLock.withLock {
                connectionContinuation?.resume(false) {}
                connectionContinuation = null
            }
            return
        }

        // Capture count BEFORE kicking off characteristic discovery.
        // peripheral.services here contains exactly the services matching our
        // discoverServices(serviceUUIDs:) filter — not all services on the device.
        val services = peripheral.services ?: emptyList<Any>()
        expectedServiceCount = services.size

        if (expectedServiceCount == 0) {
            serviceDiscoveryTimeoutJob?.cancel()
            serviceDiscoveryTimeoutJob = null
            val address = peripheral.identifier.UUIDString
            Logger.w("BleManager", "No matching services found on $address")
            _connectionState.value = ConnectionState.Failed(
                error = "No supported services found",
                address = address
            )
            continuationLock.withLock {
                connectionContinuation?.resume(false) {}
                connectionContinuation = null
            }
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
        val serviceUuid = service.UUID.UUIDString

        if (error != null) {
            Logger.w("BleManager", "Characteristic discovery error for service $serviceUuid: ${error.localizedDescription}")
        }

        // Track this service as having completed characteristic discovery
        discoveredServiceUuids.add(serviceUuid.lowercase())

        // Check if all services we kicked off discovery for have reported back
        val allServicesDiscovered = expectedServiceCount > 0 &&
            discoveredServiceUuids.size >= expectedServiceCount

        if (allServicesDiscovered) {
            completeServiceDiscovery(peripheral)
        }
    }

    /**
     * Shared completion path for service/characteristic discovery.
     * Called from both the cache-hit fast path and the normal discovery callback path.
     */
    private fun completeServiceDiscovery(peripheral: CBPeripheral) {
        // Guard: timeout and callback can race — use lock to prevent double-completion
        val alreadyCompleted = continuationLock.withLock {
            if (serviceDiscoveryCompleted) return@withLock true
            serviceDiscoveryCompleted = true
            false
        }
        if (alreadyCompleted) return

        serviceDiscoveryTimeoutJob?.cancel()
        serviceDiscoveryTimeoutJob = null

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

        // Connection complete
        val address = peripheral.identifier.UUIDString
        _connectionState.value = ConnectionState.Connected(
            address = address,
            wheelName = peripheral.name ?: "Unknown"
        )

        continuationLock.withLock {
            connectionContinuation?.resume(true) {}
            connectionContinuation = null
        }
    }

    /**
     * Match read/write characteristics against the configured UUIDs and enable notifications.
     * Must be called after configureForWheel() has set the UUID fields.
     */
    private fun setupCharacteristics(peripheral: CBPeripheral) {
        val rServiceUuid = readServiceUuid?.lowercase()
        val rCharUuid = readCharUuid?.lowercase()
        val wServiceUuid = writeServiceUuid?.lowercase()
        val wCharUuid = writeCharUuid?.lowercase()

        if (rServiceUuid == null || rCharUuid == null) {
            Logger.w("BleManager", "Read UUIDs not configured, cannot set up characteristics")
            return
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

        if (readCharacteristic == null) {
            Logger.w("BleManager", "Read characteristic not found: service=$rServiceUuid char=$rCharUuid")
        }
        if (writeCharacteristic == null && wCharUuid != null) {
            Logger.w("BleManager", "Write characteristic not found: service=$wServiceUuid char=$wCharUuid")
        }
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
 * - 4-char: "FFE0" → "0000FFE0-0000-1000-8000-00805F9B34FB"
 * - 8-char: "0000FFE0" → "0000FFE0-0000-1000-8000-00805F9B34FB"
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
