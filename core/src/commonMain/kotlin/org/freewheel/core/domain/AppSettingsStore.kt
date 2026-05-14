package org.freewheel.core.domain

import org.freewheel.core.domain.telemetry.SpeedDisplayMode

/**
 * Typed reader/writer for app-level settings keyed by [AppSettingId] (and remembered
 * slider values keyed by [SettingsCommandId]).
 *
 * Wraps a [KeyValueStore], handling [SettingScope] and per-wheel MAC prefixing so
 * callers do not have to. The MAC is read fresh on each call from
 * [PreferenceKeys.LAST_CONNECTED_MAC] — the stable per-wheel anchor that survives
 * explicit disconnects, distinct from [PreferenceKeys.LAST_MAC] (the auto-reconnect
 * target, cleared on disconnect).
 */
class AppSettingsStore(private val store: KeyValueStore) {

    init {
        // Upgrade path: LAST_CONNECTED_MAC was introduced after LAST_MAC. Existing
        // installs only have LAST_MAC set, so per-wheel reads would fall back to an
        // empty MAC prefix until the next connect. Backfill once from LAST_MAC so
        // existing users keep their scoped settings without waiting to reconnect.
        val anchor = store.getString(PreferenceKeys.LAST_CONNECTED_MAC, "") ?: ""
        if (anchor.isBlank()) {
            val legacy = store.getString(PreferenceKeys.LAST_MAC, "") ?: ""
            if (legacy.isNotBlank()) {
                store.putString(PreferenceKeys.LAST_CONNECTED_MAC, legacy)
            }
        }
    }

    fun getBool(id: AppSettingId): Boolean =
        store.getBool(scopedKey(id), id.defaultBool)

    fun setBool(id: AppSettingId, value: Boolean) {
        store.putBool(scopedKey(id), value)
    }

    fun getInt(id: AppSettingId): Int =
        store.getInt(scopedKey(id), id.defaultInt)

    fun setInt(id: AppSettingId, value: Int) {
        store.putInt(scopedKey(id), value)
    }

    /**
     * Returns the auto-reconnect target MAC, or empty string if none.
     * Use [getLastConnectedMac] for the per-wheel scoping anchor that survives
     * explicit disconnects.
     */
    fun getLastMac(): String = store.getString(PreferenceKeys.LAST_MAC, "") ?: ""

    /**
     * Records the connected wheel. Pass empty string to clear the auto-reconnect
     * target on explicit disconnect; the per-wheel scoping anchor
     * ([LAST_CONNECTED_MAC]) is preserved so disconnected edits still bind to the
     * wheel the user was just on.
     */
    fun setLastMac(mac: String) {
        store.putString(PreferenceKeys.LAST_MAC, mac)
        if (mac.isNotBlank()) {
            store.putString(PreferenceKeys.LAST_CONNECTED_MAC, mac)
        }
    }

    /** Returns the most recently connected MAC, or empty if no wheel has ever connected. */
    fun getLastConnectedMac(): String =
        store.getString(PreferenceKeys.LAST_CONNECTED_MAC, "") ?: ""

    /**
     * Speed display mode is global but lives outside [AppSettingId] because it is a
     * dashboard-screen choice (radio buttons), not a Settings-screen control.
     */
    fun getSpeedDisplayMode(): SpeedDisplayMode {
        val ordinal = store.getInt(PreferenceKeys.SPEED_DISPLAY_MODE, 0)
            .coerceIn(0, SpeedDisplayMode.entries.lastIndex)
        return SpeedDisplayMode.entries[ordinal]
    }

    fun setSpeedDisplayMode(mode: SpeedDisplayMode) {
        store.putInt(PreferenceKeys.SPEED_DISPLAY_MODE, mode.ordinal)
    }

    /**
     * Persist the last-set value for a write-only wheel command (slider position).
     * No-op when no wheel is connected — sliders are intentionally per-wheel only,
     * so a global fallback would let one wheel's value leak into another's UI.
     */
    fun saveSliderValue(commandId: SettingsCommandId, value: Int) {
        val mac = currentMac().takeIf { it.isNotBlank() } ?: return
        store.putInt(PreferenceKeys.wheelSliderKey(mac, commandId.name), value)
    }

    /** Returns null when no wheel is connected or no value has been stored for it. */
    fun loadSliderValue(commandId: SettingsCommandId): Int? {
        val mac = currentMac().takeIf { it.isNotBlank() } ?: return null
        val key = PreferenceKeys.wheelSliderKey(mac, commandId.name)
        return if (store.contains(key)) store.getInt(key, 0) else null
    }

    private fun scopedKey(id: AppSettingId): String = when (id.scope) {
        SettingScope.GLOBAL -> id.prefKey
        SettingScope.PER_WHEEL -> "${currentMac()}_${id.prefKey}"
    }

    private fun currentMac(): String =
        store.getString(PreferenceKeys.LAST_CONNECTED_MAC, "") ?: ""
}
