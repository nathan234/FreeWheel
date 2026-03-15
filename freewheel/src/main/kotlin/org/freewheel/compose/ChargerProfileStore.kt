package org.freewheel.compose

import android.content.SharedPreferences
import org.freewheel.core.domain.PreferenceKeys

data class ChargerProfile(
    val address: String,
    val displayName: String = "",
    val password: String = "",
    val lastConnectedMs: Long = 0
)

/**
 * Persists saved charger profiles in SharedPreferences.
 *
 * Storage layout:
 * - `saved_charger_addresses`       : Set<String> of MAC addresses
 * - `{mac}_charger_name`            : display name
 * - `{mac}_charger_password`        : BLE password
 * - `{mac}_charger_last_connected`  : epoch millis
 */
class ChargerProfileStore(private val prefs: SharedPreferences) {

    fun getSavedAddresses(): Set<String> {
        return prefs.getStringSet(PreferenceKeys.SAVED_CHARGER_ADDRESSES, emptySet()) ?: emptySet()
    }

    fun getSavedProfiles(): List<ChargerProfile> {
        return getSavedAddresses().map { address ->
            ChargerProfile(
                address = address,
                displayName = prefs.getString(address + PreferenceKeys.SUFFIX_CHARGER_NAME, "") ?: "",
                password = prefs.getString(address + PreferenceKeys.SUFFIX_CHARGER_PASSWORD, "") ?: "",
                lastConnectedMs = prefs.getLong(address + PreferenceKeys.SUFFIX_CHARGER_LAST_CONNECTED, 0L)
            )
        }.sortedByDescending { it.lastConnectedMs }
    }

    fun getProfile(address: String): ChargerProfile? {
        if (address !in getSavedAddresses()) return null
        return ChargerProfile(
            address = address,
            displayName = prefs.getString(address + PreferenceKeys.SUFFIX_CHARGER_NAME, "") ?: "",
            password = prefs.getString(address + PreferenceKeys.SUFFIX_CHARGER_PASSWORD, "") ?: "",
            lastConnectedMs = prefs.getLong(address + PreferenceKeys.SUFFIX_CHARGER_LAST_CONNECTED, 0L)
        )
    }

    fun saveProfile(profile: ChargerProfile) {
        val addresses = getSavedAddresses().toMutableSet()
        addresses.add(profile.address)
        prefs.edit()
            .putStringSet(PreferenceKeys.SAVED_CHARGER_ADDRESSES, addresses)
            .putString(profile.address + PreferenceKeys.SUFFIX_CHARGER_NAME, profile.displayName)
            .putString(profile.address + PreferenceKeys.SUFFIX_CHARGER_PASSWORD, profile.password)
            .putLong(profile.address + PreferenceKeys.SUFFIX_CHARGER_LAST_CONNECTED, profile.lastConnectedMs)
            .apply()
    }

    fun deleteProfile(address: String) {
        val addresses = getSavedAddresses().toMutableSet()
        addresses.remove(address)
        prefs.edit()
            .putStringSet(PreferenceKeys.SAVED_CHARGER_ADDRESSES, addresses)
            .remove(address + PreferenceKeys.SUFFIX_CHARGER_NAME)
            .remove(address + PreferenceKeys.SUFFIX_CHARGER_PASSWORD)
            .remove(address + PreferenceKeys.SUFFIX_CHARGER_LAST_CONNECTED)
            .apply()
    }
}
