package org.freewheel.compose

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.freewheel.AppConfig
import org.freewheel.core.domain.AlarmType
import org.freewheel.core.domain.WheelState
import org.freewheel.shared.Constants
import org.freewheel.shared.WearPage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Manages WearOS DataClient/MessageClient communication from the phone side.
 * Pushes wheel telemetry to the watch and handles horn/light messages from it.
 */
class WearOsManager(
    private val context: Context,
    private val wheelStateFlow: StateFlow<WheelState>,
    private val activeAlarmsFlow: StateFlow<Set<AlarmType>>,
    private val appConfig: AppConfig,
    private val onHornRequested: () -> Unit,
    private val onLightToggleRequested: () -> Unit
) : MessageClient.OnMessageReceivedListener {

    private var isConnected = false
    private var scope: CoroutineScope? = null
    private var collectionJob: Job? = null
    private var pingJob: Job? = null

    // Session max/min tracking
    private var maxSpeed = 0.0
    private var maxPhaseCurrent = 0.0
    private var maxPower = 0.0
    private var maxPwm = 0.0
    private var maxTemp = 0.0
    private var minBattery = 101

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Constants.wearPages && isConnected) {
            sendUpdatePages()
        }
    }

    fun start(scope: CoroutineScope) {
        this.scope = scope
        resetSessionMaxes()
        isConnected = false

        Wearable.getMessageClient(context).addListener(this)
        PreferenceManager.getDefaultSharedPreferences(context)
            .registerOnSharedPreferenceChangeListener(prefListener)

        // Send ping, then auto-launch watch app if no pong received
        sendMessage(Constants.wearOsPingMessage)
        pingJob = scope.launch {
            delay(500)
            if (!isConnected) {
                sendMessage(Constants.wearOsStartPath, useAsPath = true)
            }
        }

        // Collect wheel state and push to watch
        collectionJob = scope.launch {
            wheelStateFlow.collect { state ->
                if (isConnected) {
                    sendUpdateData(state)
                }
            }
        }
    }

    fun stop() {
        if (isConnected) {
            sendMessage(Constants.wearOsFinishMessage)
        }
        collectionJob?.cancel()
        collectionJob = null
        pingJob?.cancel()
        pingJob = null
        isConnected = false

        try {
            Wearable.getMessageClient(context).removeListener(this)
        } catch (_: Exception) {}
        try {
            PreferenceManager.getDefaultSharedPreferences(context)
                .unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Exception) {}
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Constants.wearOsDataMessagePath) return
        when (event.data.toString(Charsets.UTF_8)) {
            Constants.wearOsPongMessage -> {
                isConnected = true
                sendUpdatePages()
            }
            Constants.wearOsHornMessage -> onHornRequested()
            Constants.wearOsLightMessage -> onLightToggleRequested()
        }
    }

    private fun sendUpdateData(state: WheelState) {
        val speedKmh = state.speedKmh
        val phaseCurrentAbs = abs(state.phaseCurrentA)
        val powerAbs = abs(state.powerW)
        val pwmPercent = state.calculatedPwm * 100.0
        val tempC = state.temperatureC.toDouble()
        val battery = state.batteryLevel

        // Update session maxes/mins
        if (speedKmh > maxSpeed) maxSpeed = speedKmh
        if (phaseCurrentAbs > maxPhaseCurrent) maxPhaseCurrent = phaseCurrentAbs
        if (powerAbs > maxPower) maxPower = powerAbs
        if (pwmPercent > maxPwm) maxPwm = pwmPercent
        if (tempC > maxTemp) maxTemp = tempC
        if (battery in 1 until minBattery) minBattery = battery

        // Build alarm bitmask (bit0=speed, bit1=current, bit2=temp)
        val alarms = activeAlarmsFlow.value
        var alarmBits = 0
        if (alarms.any { it == AlarmType.SPEED1 || it == AlarmType.SPEED2 || it == AlarmType.SPEED3 }) {
            alarmBits = alarmBits or 1
        }
        if (AlarmType.CURRENT in alarms) alarmBits = alarmBits or 2
        if (AlarmType.TEMPERATURE in alarms) alarmBits = alarmBits or 4

        val distance = if (appConfig.useMph) {
            state.wheelDistanceKm * 0.621371
        } else {
            state.wheelDistanceKm
        }

        val request = PutDataMapRequest.create(Constants.wearOsDataItemPath).apply {
            dataMap.putDouble(Constants.wearOsSpeedData, speedKmh)
            dataMap.putDouble(Constants.wearOsMaxSpeedData, maxSpeed)
            dataMap.putDouble(Constants.wearOsVoltageData, state.voltageV)
            dataMap.putDouble(Constants.wearOsCurrentData, state.currentA)
            dataMap.putDouble(Constants.wearOsMaxCurrentData, maxPhaseCurrent)
            dataMap.putDouble(Constants.wearOsPowerData, state.powerW)
            dataMap.putDouble(Constants.wearOsMaxPowerData, maxPower)
            dataMap.putDouble(Constants.wearOsPWMData, pwmPercent)
            dataMap.putDouble(Constants.wearOsMaxPWMData, maxPwm)
            dataMap.putDouble(Constants.wearOsTemperatureData, tempC)
            dataMap.putDouble(Constants.wearOsMaxTemperatureData, maxTemp)
            dataMap.putInt(Constants.wearOsBatteryData, battery)
            dataMap.putInt(Constants.wearOsBatteryLowData, if (minBattery > 100) 0 else minBattery)
            dataMap.putDouble(Constants.wearOsDistanceData, distance)
            dataMap.putString(Constants.wearOsUnitData, if (appConfig.useMph) "mph" else "kmh")
            dataMap.putBoolean(Constants.wearOsCurrentOnDialData, appConfig.valueOnDial == "1")
            dataMap.putInt(Constants.wearOsAlarmData, alarmBits)
            dataMap.putLong(Constants.wearOsTimestampData, System.currentTimeMillis())
            dataMap.putString(Constants.wearOsTimeStringData, timeFormat.format(Date()))
            dataMap.putInt(Constants.wearOsAlarmFactor1Data, appConfig.alarmFactor1)
            dataMap.putInt(Constants.wearOsAlarmFactor2Data, appConfig.alarmFactor2)
        }
        request.setUrgent()
        Wearable.getDataClient(context).putDataItem(request.asPutDataRequest())
    }

    private fun sendUpdatePages() {
        val pagesString = WearPage.serialize(appConfig.wearOsPages)
        val request = PutDataMapRequest.create(Constants.wearOsPagesItemPath).apply {
            dataMap.putString(Constants.wearOsPagesData, pagesString)
        }
        request.setUrgent()
        Wearable.getDataClient(context).putDataItem(request.asPutDataRequest())
    }

    private fun sendMessage(message: String, useAsPath: Boolean = false) {
        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { nodes ->
                nodes.forEach { node ->
                    if (node.isNearby) {
                        if (useAsPath) {
                            Wearable.getMessageClient(context)
                                .sendMessage(node.id, message, byteArrayOf())
                        } else {
                            Wearable.getMessageClient(context)
                                .sendMessage(
                                    node.id,
                                    Constants.wearOsDataMessagePath,
                                    message.toByteArray(Charsets.UTF_8)
                                )
                        }
                    }
                }
            }
    }

    private fun resetSessionMaxes() {
        maxSpeed = 0.0
        maxPhaseCurrent = 0.0
        maxPower = 0.0
        maxPwm = 0.0
        maxTemp = 0.0
        minBattery = 101
    }
}
