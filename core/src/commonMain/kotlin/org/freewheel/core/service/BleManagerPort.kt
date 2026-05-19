package org.freewheel.core.service

import org.freewheel.core.ble.BleAdvertisement
import org.freewheel.core.ble.WheelConnectionInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic interface for BLE communication.
 * Extracted from [BleManager] to enable testing with fakes in commonTest.
 *
 * Platform implementations ([BleManager]) implement this interface.
 * Test implementations provide controllable behavior for lifecycle tests.
 */
interface BleManagerPort {
    val connectionState: StateFlow<ConnectionState>

    /** Bluetooth adapter state (power, permission). Separate from connection lifecycle. */
    val bluetoothState: StateFlow<BluetoothAdapterState>
        get() = kotlinx.coroutines.flow.MutableStateFlow(BluetoothAdapterState.POWERED_ON)

    /**
     * Connect to a BLE device at the given address.
     *
     * @param attemptId Stamped by the WCM reducer when minting this connect
     *                  attempt. Implementations should hold this value as the
     *                  active session id and stamp every event they emit
     *                  (BleConnectResult, ServicesDiscovered, BleDisconnected,
     *                  DataReceived, BleConfigureFailed) with it. The reducer
     *                  drops events whose attemptId doesn't match the current
     *                  session — see [WcmState.currentAttemptId].
     * @return true if the connection was established successfully
     */
    suspend fun connect(address: String, attemptId: Long): Boolean

    /**
     * Disconnect from the current device.
     */
    suspend fun disconnect()

    /**
     * Write a single packet to the connected device using the BLE write mode
     * carried by [request].
     *
     * Commit 2 of the Kingsong BLE parity plan: the bare-boolean contract is
     * replaced with the typed request/result pair so [WriteCoordinator] (and
     * future per-command UX) can distinguish OS-accepted submissions from
     * peer-acknowledged completions and surface failure reasons without
     * platform-specific log scraping.
     *
     * For [BleWriteType.WITHOUT_RESPONSE] (today's behavior) the result is
     * [BleWriteResult.Submitted] on successful OS submission, or
     * [BleWriteResult.Failed] otherwise. For [BleWriteType.WITH_RESPONSE] the
     * call suspends until the platform delivers a write-completion callback
     * and returns [BleWriteResult.Completed] / [BleWriteResult.Failed]
     * accordingly. No wheel issues WITH_RESPONSE writes in Commit 2; the path
     * exists so a later commit can opt in without further platform changes.
     */
    suspend fun write(request: BleWriteRequest): BleWriteResult

    /**
     * Start scanning for BLE devices.
     */
    suspend fun startScan(onDeviceFound: (BleDevice) -> Unit)

    /**
     * Stop scanning for BLE devices.
     */
    suspend fun stopScan()

    /**
     * Configure characteristics and transport policy for the detected wheel.
     *
     * Called after wheel type detection to bind the read/write characteristics
     * (enabling notifications on the read side) and to surface the wheel-family
     * [WheelConnectionInfo.transportProfile] to the platform layer. The whole
     * [WheelConnectionInfo] is passed so a future commit can act on
     * [WheelTransportProfile.requestMaxMtu] (or other profile fields) without a
     * second plumbing pass. In Commit 2 every profile is
     * [WheelTransportProfile.Default], so the platform layer keeps its current
     * unconditional MTU behavior to preserve byte-equivalence.
     *
     * @return true if the read characteristic was bound (notifications enabled).
     *         false if the underlying service or characteristic was missing —
     *         the caller should treat the connection as Failed rather than wait
     *         indefinitely for data that will never arrive.
     */
    fun configureForWheel(connectionInfo: WheelConnectionInfo): Boolean = true

    /**
     * Start scanning for BLE devices advertising a specific service UUID.
     * Default delegates to [startScan] (ignoring the filter).
     */
    suspend fun startScanForService(serviceUuid: String, onDeviceFound: (BleDevice) -> Unit) {
        startScan(onDeviceFound)
    }

    /**
     * Update the adapter-level Bluetooth state (power, permissions).
     * Default is a no-op; platform implementations track this for reconnect logic.
     */
    fun setBluetoothAdapterState(state: BluetoothAdapterState) {}

    /**
     * Look up the most recently observed advertisement for [address] from the
     * scan-time cache, or null if the address was never seen, the entry expired,
     * or this implementation does not maintain a cache.
     *
     * Used by [WheelConnectionManager.connect] to attach scan evidence to the
     * connect event so the reducer can pass it to topology fingerprinting.
     */
    fun getAdvertisement(address: String): BleAdvertisement? = null

    /**
     * Release platform resources (threads, broadcast receivers, coroutine scopes).
     * Called once after the event loop has drained. After this call the instance
     * must not be reused.
     */
    fun destroy() {}
}
