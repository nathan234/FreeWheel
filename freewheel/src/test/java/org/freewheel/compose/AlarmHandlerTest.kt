package org.freewheel.compose

import org.freewheel.core.alarm.AlarmResult
import org.freewheel.core.alarm.PreWarning
import org.freewheel.core.alarm.PreWarningType
import org.freewheel.core.alarm.TriggeredAlarm
import org.freewheel.core.alarm.VibrationPatterns
import org.freewheel.core.domain.AlarmAction
import org.freewheel.core.domain.AlarmType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AlarmHandlerTest {

    private data class ToneCall(val type: Int, val durationMs: Int)

    private val vibratePatterns = mutableListOf<LongArray>()
    private val toneCalls = mutableListOf<ToneCall>()
    private var wheelBeepCount = 0
    private var currentTime = 0L

    private val handler = AlarmHandler(
        vibrate = { vibratePatterns.add(it) },
        playTone = { type, dur -> toneCalls.add(ToneCall(type, dur)) },
        onWheelBeep = { wheelBeepCount++ },
        clock = { currentTime }
    )

    private fun singleAlarm(
        type: AlarmType = AlarmType.SPEED1,
        toneDuration: Int = 100
    ) = AlarmResult(
        triggeredAlarms = listOf(TriggeredAlarm(type, 30.0, 25.0, toneDuration))
    )

    @Test
    fun `triggered alarm fires vibrate and tone`() {
        handler.handleAlarmResult(singleAlarm(), AlarmAction.PHONE_ONLY)

        assertThat(vibratePatterns).hasSize(1)
        assertThat(vibratePatterns[0]).isEqualTo(VibrationPatterns.SPEED_PATTERN)
        assertThat(toneCalls).hasSize(1)
        assertThat(toneCalls[0]).isEqualTo(ToneCall(AlarmHandler.TONE_ALARM, 100))
    }

    @Test
    fun `multiple triggered alarms fire effects for each`() {
        val result = AlarmResult(
            triggeredAlarms = listOf(
                TriggeredAlarm(AlarmType.SPEED1, 30.0, 25.0, 100),
                TriggeredAlarm(AlarmType.CURRENT, 50.0, 40.0, 80),
                TriggeredAlarm(AlarmType.TEMPERATURE, 70.0, 60.0, 120)
            )
        )

        handler.handleAlarmResult(result, AlarmAction.PHONE_ONLY)

        assertThat(vibratePatterns).hasSize(3)
        assertThat(vibratePatterns[0]).isEqualTo(VibrationPatterns.SPEED_PATTERN)
        assertThat(vibratePatterns[1]).isEqualTo(VibrationPatterns.CURRENT_PATTERN)
        assertThat(vibratePatterns[2]).isEqualTo(VibrationPatterns.TEMPERATURE_PATTERN)
        assertThat(toneCalls).hasSize(3)
        assertThat(toneCalls[0].durationMs).isEqualTo(100)
        assertThat(toneCalls[1].durationMs).isEqualTo(80)
        assertThat(toneCalls[2].durationMs).isEqualTo(120)
    }

    @Test
    fun `PHONE_ONLY does not call wheel beep`() {
        handler.handleAlarmResult(singleAlarm(), AlarmAction.PHONE_ONLY)

        assertThat(wheelBeepCount).isEqualTo(0)
    }

    @Test
    fun `PHONE_AND_WHEEL calls wheel beep`() {
        handler.handleAlarmResult(singleAlarm(), AlarmAction.PHONE_AND_WHEEL)

        assertThat(wheelBeepCount).isEqualTo(1)
    }

    @Test
    fun `ALL calls wheel beep`() {
        handler.handleAlarmResult(singleAlarm(), AlarmAction.ALL)

        assertThat(wheelBeepCount).isEqualTo(1)
    }

    @Test
    fun `preWarning fires advisory beep and vibrate`() {
        val result = AlarmResult(
            preWarning = PreWarning(PreWarningType.PWM, 75.0)
        )

        handler.handleAlarmResult(result, AlarmAction.PHONE_ONLY)

        assertThat(vibratePatterns).hasSize(1)
        assertThat(vibratePatterns[0]).isEqualTo(AlarmHandler.PRE_WARNING_PATTERN)
        assertThat(toneCalls).hasSize(1)
        assertThat(toneCalls[0]).isEqualTo(
            ToneCall(AlarmHandler.TONE_PRE_WARNING, AlarmHandler.PRE_WARNING_DURATION_MS)
        )
    }

    @Test
    fun `preWarning with no triggered alarms still fires`() {
        val result = AlarmResult(
            triggeredAlarms = emptyList(),
            preWarning = PreWarning(PreWarningType.SPEED, 20.0)
        )

        handler.handleAlarmResult(result, AlarmAction.PHONE_ONLY)

        assertThat(vibratePatterns).hasSize(1)
        assertThat(toneCalls).hasSize(1)
        assertThat(wheelBeepCount).isEqualTo(0)
    }

    @Test
    fun `empty result does nothing`() {
        handler.handleAlarmResult(AlarmResult(), AlarmAction.PHONE_ONLY)

        assertThat(vibratePatterns).isEmpty()
        assertThat(toneCalls).isEmpty()
        assertThat(wheelBeepCount).isEqualTo(0)
    }

    @Test
    fun `throttle suppresses repeated alarm within 500ms`() {
        currentTime = 1000L
        handler.handleAlarmResult(singleAlarm(), AlarmAction.PHONE_ONLY)

        currentTime = 1400L // 400ms later — within throttle window
        handler.handleAlarmResult(singleAlarm(), AlarmAction.PHONE_ONLY)

        assertThat(vibratePatterns).hasSize(1) // only first fires
        assertThat(toneCalls).hasSize(1)
    }

    @Test
    fun `throttle allows alarm after 500ms`() {
        currentTime = 1000L
        handler.handleAlarmResult(singleAlarm(), AlarmAction.PHONE_ONLY)

        currentTime = 1500L // exactly 500ms later
        handler.handleAlarmResult(singleAlarm(), AlarmAction.PHONE_ONLY)

        assertThat(vibratePatterns).hasSize(2) // both fire
        assertThat(toneCalls).hasSize(2)
    }
}
