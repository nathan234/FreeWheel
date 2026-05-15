package org.freewheel.core.domain.profile

import org.freewheel.core.domain.KeyValueStore
import org.freewheel.core.domain.settings.PreferenceKeys

/**
 * Manages saved charger profiles using a [KeyValueStore].
 *
 * Storage layout:
 * - `saved_charger_addresses`           : Set<String> of addresses
 * - `{address}_charger_name`            : display name
 * - `{address}_charger_password`        : BLE password
 * - `{address}_charger_last_connected`  : epoch millis
 */
class ChargerProfileStore(private val store: KeyValueStore) {

    fun getSavedAddresses(): Set<String> {
        return store.getStringSet(PreferenceKeys.SAVED_CHARGER_ADDRESSES)
    }

    fun getSavedProfiles(): List<ChargerProfile> {
        return getSavedAddresses().map { address ->
            ChargerProfile(
                address = address,
                displayName = store.getString(address + PreferenceKeys.SUFFIX_CHARGER_NAME, "") ?: "",
                password = store.getString(address + PreferenceKeys.SUFFIX_CHARGER_PASSWORD, "") ?: "",
                lastConnectedMs = store.getLong(address + PreferenceKeys.SUFFIX_CHARGER_LAST_CONNECTED, 0L)
            )
        }.sortedByDescending { it.lastConnectedMs }
    }

    fun getProfile(address: String): ChargerProfile? {
        if (address !in getSavedAddresses()) return null
        return ChargerProfile(
            address = address,
            displayName = store.getString(address + PreferenceKeys.SUFFIX_CHARGER_NAME, "") ?: "",
            password = store.getString(address + PreferenceKeys.SUFFIX_CHARGER_PASSWORD, "") ?: "",
            lastConnectedMs = store.getLong(address + PreferenceKeys.SUFFIX_CHARGER_LAST_CONNECTED, 0L)
        )
    }

    fun saveProfile(profile: ChargerProfile) {
        val addresses = getSavedAddresses().toMutableSet()
        addresses.add(profile.address)
        store.putStringSet(PreferenceKeys.SAVED_CHARGER_ADDRESSES, addresses)
        store.putString(profile.address + PreferenceKeys.SUFFIX_CHARGER_NAME, profile.displayName)
        store.putString(profile.address + PreferenceKeys.SUFFIX_CHARGER_PASSWORD, profile.password)
        store.putLong(profile.address + PreferenceKeys.SUFFIX_CHARGER_LAST_CONNECTED, profile.lastConnectedMs)
    }

    fun deleteProfile(address: String) {
        val addresses = getSavedAddresses().toMutableSet()
        addresses.remove(address)
        store.putStringSet(PreferenceKeys.SAVED_CHARGER_ADDRESSES, addresses)
        store.remove(address + PreferenceKeys.SUFFIX_CHARGER_NAME)
        store.remove(address + PreferenceKeys.SUFFIX_CHARGER_PASSWORD)
        store.remove(address + PreferenceKeys.SUFFIX_CHARGER_LAST_CONNECTED)
    }
}
