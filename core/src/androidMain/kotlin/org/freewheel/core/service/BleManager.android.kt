package org.freewheel.core.service

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.os.Handler
import android.os.Looper
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
    private var central: BluetoothCentralManager? = null
    private var currentPeripheral: BluetoothPeripheral? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null

    // Connection continuation (protected by continuationLock)
    private var connectionContinuation: CancellableContinuation<Boolean>? = null
    private val continuationLock = Lock()

    // Callback for data received from the wheel
    private var onDataReceivedCallback: ((ByteArray) -> Unit)? = null

    // Callback for services discovered
    private var onServicesDiscoveredCallback: ((DiscoveredServices, String?) -> Unit)? = null

    // Scan callback
    private var scanDeviceFoundCallback: ((BleDevice) -> Unit)? = null

    // Disconnect tracking
    private var disconnectRequested = false

    actual override val connectionState: StateFlow<ConnectionState>
        get() = _connectionState.asStateFlow()

    // ==================== Central Manager Callback ====================

    private val centralCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(
            peripheral: BluetoothPeripheral,
            scanResult: ScanResult
        ) {
            scanDeviceFoundCallback?.invoke(
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

            // Resume connection continuation
            continuationLock.withLock {
                connectionContinuation?.resume(true) {}
                connectionContinuation = null
            }
        }

        override fun onConnectionFailed(peripheral: BluetoothPeripheral, status: HciStatus) {
            Logger.w("BleManager", "onConnectionFailed: ${peripheral.address}, status=$status")
            _connectionState.value = ConnectionState.Failed(error = "Connection failed: $status", address = peripheral.address)

            continuationLock.withLock {
                connectionContinuation?.resume(false) {}
                connectionContinuation = null
            }
        }

        override fun onDisconnectedPeripheral(peripheral: BluetoothPeripheral, status: HciStatus) {
            val address = peripheral.address
            Logger.d("BleManager", "onDisconnectedPeripheral: $address, requested=$disconnectRequested, status=$status")

            writeCharacteristic = null
            readCharacteristic = null

            if (!disconnectRequested && address.isNotEmpty()) {
                // Unexpected disconnect — use passive auto-reconnect
                _connectionState.value = ConnectionState.ConnectionLost(
                    address = address,
                    reason = "Disconnected unexpectedly: $status"
                )
                Logger.d("BleManager", "Starting auto-reconnect for $address")
                central?.autoConnectPeripheral(peripheral, peripheralCallback)
            } else {
                // User-requested disconnect
                currentPeripheral = null
                _connectionState.value = ConnectionState.Disconnected
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
        central = BluetoothCentralManager(
            context,
            centralCallback,
            Handler(Looper.getMainLooper())
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
        currentPeripheral = peripheral
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
    fun getPeripheral(): BluetoothPeripheral? = currentPeripheral

    /**
     * Get discovered services from the current peripheral.
     */
    fun getServices(): List<BluetoothGattService>? = currentPeripheral?.services

    /**
     * Get a specific service by UUID.
     */
    fun getService(uuid: UUID): BluetoothGattService? = currentPeripheral?.getService(uuid)

    // ==================== Peripheral Callback ====================

    private val peripheralCallback = object : BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(peripheral: BluetoothPeripheral) {
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
                Logger.d("BleManager", "MTU negotiated: $mtu bytes")
            }
        }
    }

    // ==================== Connection ====================

    actual override suspend fun connect(address: String): Boolean {
        val manager = central ?: throw IllegalStateException(
            "BleManager not initialized. Call initialize() first."
        )

        disconnectRequested = false
        _connectionState.value = ConnectionState.Connecting(address)

        return suspendCancellableCoroutine { continuation ->
            continuationLock.withLock {
                connectionContinuation = continuation
            }

            val peripheral = manager.getPeripheral(address)
            currentPeripheral = peripheral

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
        disconnectRequested = false
        val peripheral = currentPeripheral ?: manager.getPeripheral(address).also {
            currentPeripheral = it
        }
        _connectionState.value = ConnectionState.Connecting(address)
        manager.autoConnectPeripheral(peripheral, peripheralCallback)
    }

    actual override suspend fun disconnect() {
        disconnectRequested = true
        currentPeripheral?.let { peripheral ->
            central?.cancelConnection(peripheral)
        }
        currentPeripheral = null
        writeCharacteristic = null
        readCharacteristic = null
        _connectionState.value = ConnectionState.Disconnected
    }

    // ==================== Write ====================

    actual override suspend fun write(data: ByteArray): Boolean {
        val peripheral = currentPeripheral ?: return false
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
    suspend fun writeChunked(data: ByteArray, chunkSize: Int = 20, delayMs: Long = 20): Boolean {
        val peripheral = currentPeripheral ?: return false
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
        val peripheral = currentPeripheral ?: return
        val service = peripheral.getService(UUID.fromString(serviceUuid)) ?: return
        writeCharacteristic = service.getCharacteristic(UUID.fromString(charUuid))
    }

    fun setReadCharacteristic(serviceUuid: String, charUuid: String) {
        val peripheral = currentPeripheral ?: return
        val service = peripheral.getService(UUID.fromString(serviceUuid)) ?: return
        readCharacteristic = service.getCharacteristic(UUID.fromString(charUuid))

        readCharacteristic?.let { char ->
            peripheral.setNotify(char, true)
        }
    }

    fun configureForWheel(
        readServiceUuid: String,
        readCharUuid: String,
        writeServiceUuid: String,
        writeCharUuid: String
    ) {
        setReadCharacteristic(readServiceUuid, readCharUuid)
        setWriteCharacteristic(writeServiceUuid, writeCharUuid)
    }

    // ==================== Scanning ====================

    actual override suspend fun startScan(onDeviceFound: (BleDevice) -> Unit) {
        val manager = central ?: return
        scanDeviceFoundCallback = onDeviceFound
        _connectionState.value = ConnectionState.Scanning
        manager.scanForPeripherals()
    }

    actual override suspend fun stopScan() {
        central?.stopScan()
        scanDeviceFoundCallback = null
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
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
