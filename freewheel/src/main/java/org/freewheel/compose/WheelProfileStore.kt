package org.freewheel.compose

import android.content.SharedPreferences
import org.freewheel.core.domain.PreferenceKeys
import org.freewheel.core.domain.WheelProfile

/**
 * Persists saved wheel profiles in SharedPreferences.
 *
 * Storage layout:
 * - `saved_wheel_addresses` : Set<String> of MAC addresses
 * - `{mac}_profile_name`    : display name (shared with AppConfig's existing key)
 * - `{mac}_wheel_type_name` : WheelType enum name
 * - `{mac}_last_connected`  : epoch millis
 */
class WheelProfileStore(private val prefs: SharedPreferences) {

    fun getSavedAddresses(): Set<String> {
        return prefs.getStringSet(PreferenceKeys.SAVED_WHEEL_ADDRESSES, emptySet()) ?: emptySet()
    }

    fun getSavedProfiles(): List<WheelProfile> {
        return getSavedAddresses().map { address ->
            WheelProfile(
                address = address,
                displayName = prefs.getString(address + PreferenceKeys.SUFFIX_PROFILE_NAME, "") ?: "",
                wheelTypeName = prefs.getString(address + PreferenceKeys.SUFFIX_WHEEL_TYPE, "") ?: "",
                lastConnectedMs = prefs.getLong(address + PreferenceKeys.SUFFIX_LAST_CONNECTED, 0L)
            )
        }.sortedByDescending { it.lastConnectedMs }
    }

    fun getDisplayName(address: String): String? {
        val name = prefs.getString(address + PreferenceKeys.SUFFIX_PROFILE_NAME, null)
        return if (name.isNullOrEmpty()) null else name
    }

    fun saveProfile(profile: WheelProfile) {
        val addresses = getSavedAddresses().toMutableSet()
        addresses.add(profile.address)
        prefs.edit()
            .putStringSet(PreferenceKeys.SAVED_WHEEL_ADDRESSES, addresses)
            .putString(profile.address + PreferenceKeys.SUFFIX_PROFILE_NAME, profile.displayName)
            .putString(profile.address + PreferenceKeys.SUFFIX_WHEEL_TYPE, profile.wheelTypeName)
            .putLong(profile.address + PreferenceKeys.SUFFIX_LAST_CONNECTED, profile.lastConnectedMs)
            .apply()
    }

    fun deleteProfile(address: String) {
        val addresses = getSavedAddresses().toMutableSet()
        addresses.remove(address)
        prefs.edit()
            .putStringSet(PreferenceKeys.SAVED_WHEEL_ADDRESSES, addresses)
            .remove(address + PreferenceKeys.SUFFIX_WHEEL_TYPE)
            .remove(address + PreferenceKeys.SUFFIX_LAST_CONNECTED)
            // Keep profile_name — it's shared with AppConfig's per-wheel settings
            .apply()
    }
}
