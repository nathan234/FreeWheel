package org.freewheel.core.service

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.welie.blessed.ConnectionPriority
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
 * ### Standalone Mode (Recommended for new code)
 * ```
 * val bleManager = BleManager()
 * bleManager.initialize(context)
 * bleManager.connect(address)
 * ```
 *
 * ### Bridge Mode (For incremental migration)
 * ```
 * val bleManager = BleManager()
 * bleManager.setPeripheral(existingPeripheral)
 * ```
 */
actual class BleManager : BleManagerPort {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val _bluetoothState = MutableStateFlow(BluetoothAdapterState.UNKNOWN)
    actual override val bluetoothState: StateFlow<BluetoothAdapterState> = _bluetoothState.asStateFlow()

    /**
     * Update the Bluetooth adapter state.
     * Called from ComposeActivity's BroadcastReceiver when adapter state changes.
     */
    override fun setBluetoothAdapterState(state: BluetoothAdapterState) {
        _bluetoothState.value = state
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

        data object Idle : BleSessionState()
        data class Scanning(val callback: (BleDevice) -> Unit) : BleSessionState()
        data class Connecting(override val peripheral: BluetoothPeripheral) : BleSessionState()
        data class Connected(override val peripheral: BluetoothPeripheral) : BleSessionState()
        data class AwaitingReconnect(override val peripheral: BluetoothPeripheral) : BleSessionState()
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

    // Callbacks (set once at initialization, not session-related)
    private var onDataReceivedCallback: ((ByteArray) -> Unit)? = null
    private var onBleErrorCallback: (() -> Unit)? = null
    private var onBleDisconnectedCallback: ((String, String) -> Unit)? = null
    private var onServicesDiscoveredCallback: ((DiscoveredServices, String?) -> Unit)? = null

    // Dedicated BLE thread — stored for cleanup in destroy()
    private var bleThread: HandlerThread? = null

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

    private fun transitionToIdle() {
        when (val s = session) {
            is BleSessionState.Idle -> return
            is BleSessionState.Scanning -> central?.stopScan()
            is BleSessionState.Connecting -> central?.cancelConnection(s.peripheral)
            is BleSessionState.Connected -> central?.cancelConnection(s.peripheral)
            is BleSessionState.AwaitingReconnect -> central?.cancelConnection(s.peripheral)
        }
        resumeContinuation(false)
        writeCharacteristic = null
        readCharacteristic = null
        session = BleSessionState.Idle
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun transitionToScanning(callback: (BleDevice) -> Unit) {
        when (val s = session) {
            is BleSessionState.Idle -> {}
            is BleSessionState.Scanning -> central?.stopScan()
            is BleSessionState.Connecting -> central?.cancelConnection(s.peripheral)
            is BleSessionState.Connected -> central?.cancelConnection(s.peripheral)
            is BleSessionState.AwaitingReconnect -> central?.cancelConnection(s.peripheral)
        }
        resumeContinuation(false)
        writeCharacteristic = null
        readCharacteristic = null
        session = BleSessionState.Scanning(callback)
        _connectionState.value = ConnectionState.Scanning
    }

    private fun transitionToConnecting(peripheral: BluetoothPeripheral) {
        when (val s = session) {
            is BleSessionState.Idle -> {}
            is BleSessionState.Scanning -> central?.stopScan()
            is BleSessionState.Connecting -> central?.cancelConnection(s.peripheral)
            is BleSessionState.Connected -> central?.cancelConnection(s.peripheral)
            is BleSessionState.AwaitingReconnect -> central?.cancelConnection(s.peripheral)
        }
        resumeContinuation(false)
        writeCharacteristic = null
        readCharacteristic = null
        session = BleSessionState.Connecting(peripheral)
    }

    // ==================== Central Manager Callback ====================

    private val centralCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(
            peripheral: BluetoothPeripheral,
            scanResult: ScanResult
        ) {
            val scanning = session as? BleSessionState.Scanning ?: return
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

            // Request MTU for extended frames
            peripheral.requestMtu(BluetoothPeripheral.MAX_MTU)

            session = BleSessionState.Connected(peripheral)

            // Resume connection continuation
            resumeContinuation(true)
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            Logger.w("BleManager", "onConnectionFailed: ${peripheral.address}, status=$status")

            val reason = hciStatusToDisplayText(status)
            session = BleSessionState.Idle
            writeCharacteristic = null
            readCharacteristic = null
            _connectionState.value = ConnectionState.Failed(
                error = reason,
                address = peripheral.address
            )

            resumeContinuation(false)
        }

        override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: HciStatus) {
            // Safety net: resume any pending connect continuation
            resumeContinuation(false)

            val address = peripheral.address
            Logger.d("BleManager", "onDisconnectedPeripheral: $address, session=${session::class.simpleName}, status=$status")

            writeCharacteristic = null
            readCharacteristic = null

            // Check session to determine if this was intentional.
            // disconnect() transitions to Idle BEFORE the OS fires this callback,
            // so Idle/Scanning means intentional. Everything else is unexpected.
            when (session) {
                is BleSessionState.Idle, is BleSessionState.Scanning -> {
                    // Intentional disconnect — cleanup already done
                    return
                }
                is BleSessionState.Connecting, is BleSessionState.Connected,
                is BleSessionState.AwaitingReconnect -> {
                    if (address.isNotEmpty()) {
                        // Unexpected disconnect — use passive OS-level auto-reconnect.
                        // autoConnectPeripheral adds the device to the OS whitelist and
                        // reconnects in the background when the peripheral is found again.
                        val reason = hciStatusToDisplayText(status)
                        session = BleSessionState.AwaitingReconnect(peripheral)
                        _connectionState.value = ConnectionState.ConnectionLost(
                            address = address,
                            reason = reason
                        )
                        Logger.d("BleManager", "Starting OS auto-reconnect for $address")
                        central?.autoConnectPeripheral(peripheral, peripheralCallback)
                        // Notify WCM immediately so it transitions to ConnectionLost
                        // without waiting for data timeout (which could take 30+ seconds)
                        onBleDisconnectedCallback?.invoke(address, reason)
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
     * Initialize with an existing BluetoothCentralManager (bridge mode).
     * Used by legacy BluetoothService path.
     */
    fun initialize(centralManager: BluetoothCentralManager) {
        this.central = centralManager
    }

    /**
     * Set callback for when data is received from the wheel.
     */
    fun setDataReceivedCallback(callback: (ByteArray) -> Unit) {
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
     * Bridge mode: Set an existing peripheral for use with legacy code.
     */
    fun setPeripheral(
        peripheral: BluetoothPeripheral,
        writeChar: BluetoothGattCharacteristic? = null,
        readChar: BluetoothGattCharacteristic? = null
    ) {
        session = when (peripheral.state) {
            BlessedConnectionState.CONNECTED -> BleSessionState.Connected(peripheral)
            BlessedConnectionState.CONNECTING -> BleSessionState.Connecting(peripheral)
            BlessedConnectionState.DISCONNECTING, BlessedConnectionState.DISCONNECTED -> BleSessionState.Idle
        }
        writeCharacteristic = writeChar
        readCharacteristic = readChar

        _connectionState.value = when (peripheral.state) {
            BlessedConnectionState.CONNECTED -> ConnectionState.Connected(
                address = peripheral.address,
                wheelName = peripheral.name ?: "Unknown"
            )
            BlessedConnectionState.CONNECTING -> ConnectionState.Connecting(peripheral.address)
            BlessedConnectionState.DISCONNECTING -> ConnectionState.Disconnected
            BlessedConnectionState.DISCONNECTED -> ConnectionState.Disconnected
        }
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
                peripheral.name
            )
        }

        override fun onCharacteristicUpdate(
            peripheral: BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: GattStatus
        ) {
            if (status == GattStatus.SUCCESS) {
                onDataReceivedCallback?.invoke(value)
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

    actual override suspend fun connect(address: String): Boolean {
        val manager = central ?: throw IllegalStateException(
            "BleManager not initialized. Call initialize() first."
        )

        val peripheral = manager.getPeripheral(address)
        transitionToConnecting(peripheral)
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
     * Start passive auto-reconnect for a previously connected peripheral.
     * Uses OS-level reconnection that is power-efficient and has no timeout.
     * The OS will reconnect when the peripheral comes back in range.
     */
    fun autoReconnect(address: String) {
        val manager = central ?: return
        val peripheral = session.peripheral ?: manager.getPeripheral(address)
        session = BleSessionState.AwaitingReconnect(peripheral)
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

    fun setWriteCharacteristic(serviceUuid: String, charUuid: String) {
        val peripheral = session.peripheral ?: return
        val service = peripheral.getService(UUID.fromString(serviceUuid)) ?: return
        writeCharacteristic = service.getCharacteristic(UUID.fromString(charUuid))
    }

    fun setReadCharacteristic(serviceUuid: String, charUuid: String) {
        val peripheral = session.peripheral ?: return
        val service = peripheral.getService(UUID.fromString(serviceUuid)) ?: return
        readCharacteristic = service.getCharacteristic(UUID.fromString(charUuid))

        readCharacteristic?.let { char ->
            peripheral.setNotify(char, true)
        }
    }

    override fun configureForWheel(
        readServiceUuid: String,
        readCharUuid: String,
        writeServiceUuid: String,
        writeCharUuid: String
    ) {
        setReadCharacteristic(readServiceUuid, readCharUuid)
        setWriteCharacteristic(writeServiceUuid, writeCharUuid)
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

    private fun hciStatusToDisplayText(status: HciStatus): String = when (status) {
        HciStatus.SUCCESS -> "Disconnected"
        HciStatus.CONNECTION_TIMEOUT -> "Connection timed out"
        HciStatus.REMOTE_USER_TERMINATED_CONNECTION -> "Wheel disconnected"
        HciStatus.CONNECTION_TERMINATED_BY_LOCAL_HOST -> "Connection cancelled"
        HciStatus.CONNECTION_FAILED_ESTABLISHMENT -> "Connection failed"
        HciStatus.ERROR -> "GATT error"
        else -> "Disconnected: ${status.name}"
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
