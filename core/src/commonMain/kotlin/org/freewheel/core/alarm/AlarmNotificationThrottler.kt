package org.freewheel.core.alarm

import org.freewheel.core.domain.common.AlarmType

/**
 * Throttles alarm notification firing to prevent rapid-fire vibration/sound.
 *
 * This is separate from [AlarmChecker]'s per-type trigger cooldowns —
 * AlarmChecker decides *when to trigger*, this decides *when to notify the user*.
 * Both platforms (Android/iOS) previously duplicated identical 500ms throttle logic.
 */
class AlarmNotificationThrottler(
    private val throttleMs: Long = 500L,
    private val clock: () -> Long
) {
    private val lastFiredAt = mutableMapOf<AlarmType, Long>()

    /**
     * Returns true if the alarm should be suppressed (fired too recently).
     */
    fun isThrottled(type: AlarmType): Boolean {
        val last = lastFiredAt[type] ?: return false
        return (clock() - last) < throttleMs
    }

    /**
     * Record that an alarm was fired at the current time.
     */
    fun recordFired(type: AlarmType) {
        lastFiredAt[type] = clock()
    }

    fun reset() {
        lastFiredAt.clear()
    }
}
