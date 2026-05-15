package org.freewheel.core.domain.profile

import org.freewheel.core.domain.FakeKeyValueStore
import org.freewheel.core.domain.settings.PreferenceKeys
import org.freewheel.core.service.toSavedHint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WheelProfileStoreTest {

    private fun createStore(): Pair<WheelProfileStore, FakeKeyValueStore> {
        val kvs = FakeKeyValueStore()
        return WheelProfileStore(kvs) to kvs
    }

    @Test
    fun emptySavedAddresses() {
        val (store, _) = createStore()
        assertTrue(store.getSavedAddresses().isEmpty())
    }

    @Test
    fun saveAndRetrieveProfile() {
        val (store, _) = createStore()
        val profile = WheelProfile("AA:BB:CC", "My Wheel", "KINGSONG", 1000L)
        store.saveProfile(profile)

        assertEquals(setOf("AA:BB:CC"), store.getSavedAddresses())
        val profiles = store.getSavedProfiles()
        assertEquals(1, profiles.size)
        assertEquals("My Wheel", profiles[0].displayName)
        assertEquals("KINGSONG", profiles[0].wheelTypeName)
        assertEquals(1000L, profiles[0].lastConnectedMs)
    }

    @Test
    fun getDisplayName() {
        val (store, _) = createStore()
        assertNull(store.getDisplayName("AA:BB:CC"))

        store.saveProfile(WheelProfile("AA:BB:CC", "My Wheel", "KINGSONG", 1000L))
        assertEquals("My Wheel", store.getDisplayName("AA:BB:CC"))
    }

    @Test
    fun getDisplayNameReturnsNullForEmpty() {
        val (store, _) = createStore()
        store.saveProfile(WheelProfile("AA:BB:CC", "", "KINGSONG", 1000L))
        assertNull(store.getDisplayName("AA:BB:CC"))
    }

    @Test
    fun deleteProfileRemovesFromSetButKeepsName() {
        val (store, kvs) = createStore()
        store.saveProfile(WheelProfile("AA:BB:CC", "My Wheel", "KINGSONG", 1000L))
        store.deleteProfile("AA:BB:CC")

        assertTrue(store.getSavedAddresses().isEmpty())
        // profile_name is preserved for per-wheel settings
        assertEquals("My Wheel", kvs.getString("AA:BB:CC${PreferenceKeys.SUFFIX_PROFILE_NAME}", null))
        // type and timestamp are removed
        assertNull(kvs.getString("AA:BB:CC${PreferenceKeys.SUFFIX_WHEEL_TYPE}", null))
    }

    @Test
    fun multipleProfilesSortedByRecency() {
        val (store, _) = createStore()
        store.saveProfile(WheelProfile("A", "Older", "GOTWAY", 100L))
        store.saveProfile(WheelProfile("B", "Newer", "KINGSONG", 200L))

        val profiles = store.getSavedProfiles()
        assertEquals(2, profiles.size)
        assertEquals("Newer", profiles[0].displayName)
        assertEquals("Older", profiles[1].displayName)
    }

    @Test
    fun updateExistingProfile() {
        val (store, _) = createStore()
        store.saveProfile(WheelProfile("A", "Old Name", "GOTWAY", 100L))
        store.saveProfile(WheelProfile("A", "New Name", "GOTWAY", 200L))

        assertEquals(1, store.getSavedAddresses().size)
        assertEquals("New Name", store.getDisplayName("A"))
    }

    @Test
    fun topSpeedOverrideDefaultsToNull() {
        val (store, _) = createStore()
        store.saveProfile(WheelProfile("AA:BB:CC", "My Wheel", "GOTWAY", 100L))
        assertNull(store.getTopSpeedOverrideKmh("AA:BB:CC"))
    }

    @Test
    fun setAndGetTopSpeedOverride() {
        val (store, _) = createStore()
        store.saveProfile(WheelProfile("AA:BB:CC", "My Wheel", "GOTWAY", 100L))
        store.setTopSpeedOverrideKmh("AA:BB:CC", 80.0)
        assertEquals(80.0, store.getTopSpeedOverrideKmh("AA:BB:CC"))
    }

    @Test
    fun clearTopSpeedOverrideWithNull() {
        val (store, _) = createStore()
        store.saveProfile(WheelProfile("AA:BB:CC", "My Wheel", "GOTWAY", 100L))
        store.setTopSpeedOverrideKmh("AA:BB:CC", 80.0)
        store.setTopSpeedOverrideKmh("AA:BB:CC", null)
        assertNull(store.getTopSpeedOverrideKmh("AA:BB:CC"))
    }

    @Test
    fun nonPositiveOverrideIsTreatedAsUnset() {
        val (store, _) = createStore()
        store.setTopSpeedOverrideKmh("AA:BB:CC", 0.0)
        assertNull(store.getTopSpeedOverrideKmh("AA:BB:CC"))
        store.setTopSpeedOverrideKmh("AA:BB:CC", -5.0)
        assertNull(store.getTopSpeedOverrideKmh("AA:BB:CC"))
    }

    @Test
    fun saveProfilePersistsOverride() {
        val (store, _) = createStore()
        store.saveProfile(WheelProfile("X", "Name", "GOTWAY", 100L, topSpeedOverrideKmh = 75.0))
        val loaded = store.getSavedProfiles().single { it.address == "X" }
        assertEquals(75.0, loaded.topSpeedOverrideKmh)
    }

    @Test
    fun saveProfileWithNullOverridePreservesExisting() {
        val (store, _) = createStore()
        // Start with an explicit override.
        store.setTopSpeedOverrideKmh("X", 80.0)
        // Auto-save-on-connect re-saves the basic profile fields, with null override.
        store.saveProfile(WheelProfile("X", "Name", "GOTWAY", 200L, topSpeedOverrideKmh = null))
        // The override must survive — saveProfile is non-destructive when override is null.
        assertEquals(80.0, store.getTopSpeedOverrideKmh("X"))
    }

    @Test
    fun deleteProfileRemovesOverride() {
        val (store, _) = createStore()
        store.saveProfile(WheelProfile("X", "Name", "GOTWAY", 100L, topSpeedOverrideKmh = 75.0))
        store.deleteProfile("X")
        assertNull(store.getTopSpeedOverrideKmh("X"))
    }

    @Test
    fun clearWheelTypeRemovesTypeButKeepsRestOfProfile() {
        // Pass 4 "Reset wheel type" surface: clear only the saved
        // wheelTypeName so the next connect re-runs detection (and falls
        // into the WheelTypeRequired picker if topology + name both miss).
        // Display name, last-connected, address-set membership, and
        // top-speed override must be preserved.
        val (store, kvs) = createStore()
        store.saveProfile(WheelProfile("AA:BB:CC", "My Wheel", "KINGSONG", 1000L, topSpeedOverrideKmh = 60.0))

        store.clearWheelType("AA:BB:CC")

        // Address still saved
        assertEquals(setOf("AA:BB:CC"), store.getSavedAddresses())
        // Profile still readable, with empty wheelTypeName
        val profile = store.getProfile("AA:BB:CC")
        assertEquals("AA:BB:CC", profile?.address)
        assertEquals("My Wheel", profile?.displayName)
        assertEquals("", profile?.wheelTypeName)
        assertEquals(1000L, profile?.lastConnectedMs)
        assertEquals(60.0, profile?.topSpeedOverrideKmh)
        // Underlying wheel-type key is gone
        assertNull(kvs.getString("AA:BB:CC${PreferenceKeys.SUFFIX_WHEEL_TYPE}", null))
        // toSavedHint should now return null because wheelTypeName is blank
        assertNull(profile?.toSavedHint())
    }
}
