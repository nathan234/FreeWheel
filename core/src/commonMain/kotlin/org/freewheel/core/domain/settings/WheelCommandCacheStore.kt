package org.freewheel.core.domain.settings

import org.freewheel.core.domain.KeyValueStore

/**
 * A value the app sent to a wheel but has not necessarily read back.
 *
 * This must never be treated as confirmed wheel state. [sentAtEpochMs] is null for
 * values migrated from the older slider cache, which did not retain timestamps.
 */
data class LastSentWheelCommand(
    val value: Int,
    val sentAtEpochMs: Long?,
)

/** Stores write-only wheel-command history separately from application preferences. */
class WheelCommandCacheStore(private val store: KeyValueStore) {

    fun saveLastSent(commandId: SettingsCommandId, value: Int, sentAtEpochMs: Long) {
        val mac = currentMac().takeIf { it.isNotBlank() } ?: return
        val valueKey = PreferenceKeys.wheelSliderKey(mac, commandId.name)
        store.putInt(valueKey, value)
        store.putLong(PreferenceKeys.wheelSliderSentAtKey(mac, commandId.name), sentAtEpochMs)
    }

    fun loadLastSent(commandId: SettingsCommandId): LastSentWheelCommand? {
        val mac = currentMac().takeIf { it.isNotBlank() } ?: return null
        val valueKey = PreferenceKeys.wheelSliderKey(mac, commandId.name)
        if (!store.contains(valueKey)) return null

        val sentAtKey = PreferenceKeys.wheelSliderSentAtKey(mac, commandId.name)
        return LastSentWheelCommand(
            value = store.getInt(valueKey, 0),
            sentAtEpochMs = if (store.contains(sentAtKey)) store.getLong(sentAtKey, 0L) else null,
        )
    }

    private fun currentMac(): String =
        store.getString(PreferenceKeys.LAST_CONNECTED_MAC, "") ?: ""
}
