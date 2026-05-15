package org.freewheel.compose.service

import org.freewheel.core.alarm.AlarmNotificationThrottler
import org.freewheel.core.alarm.AlarmResult
import org.freewheel.core.alarm.VibrationPatterns
import org.freewheel.core.domain.common.AlarmAction

class AlarmHandler(
    private val vibrate: (pattern: LongArray) -> Unit,
    private val playTone: (toneType: Int, durationMs: Int) -> Unit,
    private val onWheelBeep: () -> Unit,
    clock: () -> Long = System::currentTimeMillis
) {
    private val throttler = AlarmNotificationThrottler(clock = clock)

    fun handleAlarmResult(result: AlarmResult, action: AlarmAction) {
        for (alarm in result.triggeredAlarms) {
            if (throttler.isThrottled(alarm.type)) continue
            throttler.recordFired(alarm.type)
            vibrate(VibrationPatterns.forAlarmType(alarm.type))
            playTone(TONE_ALARM, alarm.toneDuration)
            if (action != AlarmAction.PHONE_ONLY) onWheelBeep()
        }
        result.preWarning?.let {
            vibrate(PRE_WARNING_PATTERN)
            playTone(TONE_PRE_WARNING, PRE_WARNING_DURATION_MS)
        }
    }

    companion object {
        const val TONE_ALARM = 56 // ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
        const val TONE_PRE_WARNING = 24 // ToneGenerator.TONE_PROP_BEEP
        const val PRE_WARNING_DURATION_MS = 50
        val PRE_WARNING_PATTERN = longArrayOf(0, 50)
    }
}
