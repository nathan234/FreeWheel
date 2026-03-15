package org.freewheel.compose.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.freewheel.R
import org.freewheel.core.charger.ChargerConnectionManager
import org.freewheel.core.protocol.DefaultWheelDecoderFactory
import org.freewheel.core.protocol.WheelDecoderFactory
import org.freewheel.core.service.BleManager
import org.freewheel.core.service.ConnectionState
import org.freewheel.core.service.WheelConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.freewheel.compose.ComposeActivity
import org.freewheel.compose.di.AppModule

class WheelService : Service() {

    lateinit var bleManager: BleManager
        private set
    lateinit var connectionManager: WheelConnectionManager
        private set

    // Charger BLE (separate connection)
    lateinit var chargerBleManager: BleManager
        private set
    lateinit var chargerConnectionManager: ChargerConnectionManager
        private set

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var locationManager: LocationManager? = null
    private var notificationManager: NotificationManager? = null

    var onLightToggleRequested: (() -> Unit)? = null
    var onLogToggleRequested: (() -> Unit)? = null
    var onGpsLocationUpdate: ((android.location.Location) -> Unit)? = null

    private var locationListener: LocationListener? = null

    inner class LocalBinder : Binder() {
        val service get() = this@WheelService
    }

    private val binder = LocalBinder()

    fun initializeDependencies(
        ble: BleManager = BleManager(),
        decoderFactory: WheelDecoderFactory = DefaultWheelDecoderFactory(),
        cm: WheelConnectionManager? = null,
        chargerBle: BleManager = BleManager(),
        chargerCm: ChargerConnectionManager? = null,
        locManager: LocationManager? = AppModule.locationManager,
        notifManager: NotificationManager? = AppModule.notificationManager,
    ) {
        bleManager = ble
        connectionManager = cm ?: WheelConnectionManager(
            bleManager = ble,
            decoderFactory = decoderFactory,
            scope = serviceScope
        )
        chargerBleManager = chargerBle
        chargerConnectionManager = chargerCm ?: ChargerConnectionManager(
            bleManager = chargerBle,
            scope = serviceScope
        )
        locationManager = locManager
        notificationManager = notifManager
    }

    override fun onCreate() {
        super.onCreate()

        if (!::bleManager.isInitialized) {
            initializeDependencies()
        }

        bleManager.initialize(this)
        createNotificationChannel()

        // Wire BLE data to connection manager (mirrors WheelManager.swift)
        bleManager.setDataReceivedCallback { data ->
            try {
                connectionManager.onDataReceived(data)
            } catch (e: Exception) {
                org.freewheel.core.utils.Logger.e("WheelService", "Error in onDataReceived", e)
            }
        }
        bleManager.setServicesDiscoveredCallback { services, deviceName ->
            try {
                connectionManager.onServicesDiscovered(services, deviceName)
            } catch (e: Exception) {
                org.freewheel.core.utils.Logger.e("WheelService", "Error in onServicesDiscovered", e)
            }
        }
        bleManager.setBleErrorCallback {
            connectionManager.onBleError()
        }

        // Wire charger BLE data to charger connection manager
        chargerBleManager.initialize(this)
        chargerBleManager.setDataReceivedCallback { data ->
            try {
                chargerConnectionManager.onDataReceived(data)
            } catch (e: Exception) {
                org.freewheel.core.utils.Logger.e("WheelService", "Error in charger onDataReceived", e)
            }
        }
        chargerBleManager.setServicesDiscoveredCallback { _, _ ->
            try {
                chargerConnectionManager.onServicesDiscovered()
            } catch (e: Exception) {
                org.freewheel.core.utils.Logger.e("WheelService", "Error in charger onServicesDiscovered", e)
            }
        }
        chargerBleManager.setBleErrorCallback {
            chargerConnectionManager.onBleError()
        }

        // Monitor connection state for notification updates
        serviceScope.launch {
            connectionManager.connectionState.collect { state ->
                updateNotification(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BEEP -> serviceScope.launch { connectionManager.wheelBeep() }
            ACTION_LIGHT -> onLightToggleRequested?.invoke()
            ACTION_LOG -> onLogToggleRequested?.invoke()
            else -> startForeground(NOTIFICATION_ID, createNotification("Disconnected"))
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopLocationTracking()
        // Drain the event loop: sends DisconnectRequested, closes the channel,
        // and waits for the event loop to finish — ensuring bleManager.disconnect()
        // actually runs before the scope is cancelled.
        // runBlocking is safe here: shutdown() runs on Dispatchers.Default (the
        // event loop dispatcher), not Main, so no deadlock.
        runBlocking {
            connectionManager.shutdown()
            chargerConnectionManager.shutdown()
        }
        serviceScope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    fun startLocationTracking() {
        if (locationListener != null) return // already tracking
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val lm = locationManager ?: return
        val listener = LocationListener { location ->
            onGpsLocationUpdate?.invoke(location)
        }
        lm.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,  // 1 second
            1f,     // 1 meter
            listener
        )
        locationListener = listener
    }

    fun stopLocationTracking() {
        val listener = locationListener ?: return
        val lm = locationManager ?: return
        lm.removeUpdates(listener)
        locationListener = null
    }

    fun shutdown() {
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FreeWheel Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows wheel connection status"
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, ComposeActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("FreeWheel")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_horn_32_gray, "Beep", actionPendingIntent(ACTION_BEEP))
            .addAction(R.drawable.ic_sun_32_gray, "Light", actionPendingIntent(ACTION_LIGHT))
            .addAction(R.drawable.ic_baseline_magic_log_24, "Log", actionPendingIntent(ACTION_LOG))
            .build()
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, WheelService::class.java).apply { this.action = action }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    private fun updateNotification(state: ConnectionState) {
        val text = when (state) {
            is ConnectionState.Disconnected -> "Disconnected"
            is ConnectionState.Scanning -> "Scanning..."
            is ConnectionState.Connecting -> "Connecting..."
            is ConnectionState.DiscoveringServices -> "Discovering services..."
            is ConnectionState.Connected -> "Connected to ${state.wheelName}"
            is ConnectionState.ConnectionLost -> "Reconnecting..."
            is ConnectionState.Failed -> "Connection failed"
        }
        notificationManager?.notify(NOTIFICATION_ID, createNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "wheellog_compose"
        private const val NOTIFICATION_ID = 423412
        const val ACTION_BEEP = "org.freewheel.compose.ACTION_BEEP"
        const val ACTION_LIGHT = "org.freewheel.compose.ACTION_LIGHT"
        const val ACTION_LOG = "org.freewheel.compose.ACTION_LOG"
    }
}
