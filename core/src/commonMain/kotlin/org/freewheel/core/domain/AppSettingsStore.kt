package org.freewheel.core.domain

/**
 * Typed reader/writer for app-level settings keyed by [AppSettingId] (and remembered
 * slider values keyed by [SettingsCommandId]).
 *
 * Wraps a [KeyValueStore], handling [SettingScope] and per-wheel MAC prefixing so
 * callers do not have to. The MAC is read fresh on each call from
 * [PreferenceKeys.LAST_MAC], the same key [DecoderConfigStore] reads.
 */
class AppSettingsStore(private val store: KeyValueStore) {

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

    /** Returns the MAC of the last-connected wheel, or empty string if none. */
    fun getLastMac(): String = currentMac()

    /** Records the MAC of the connected wheel; pass empty string to clear. */
    fun setLastMac(mac: String) {
        store.putString(PreferenceKeys.LAST_MAC, mac)
    }

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

    private fun currentMac(): String = store.getString(PreferenceKeys.LAST_MAC, "") ?: ""
}
