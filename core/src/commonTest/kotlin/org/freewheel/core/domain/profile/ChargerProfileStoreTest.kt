package org.freewheel.core.domain.profile

import org.freewheel.core.domain.FakeKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargerProfileStoreTest {

    private fun createStore(): ChargerProfileStore {
        return ChargerProfileStore(FakeKeyValueStore())
    }

    @Test
    fun emptySavedAddresses() {
        val store = createStore()
        assertTrue(store.getSavedAddresses().isEmpty())
    }

    @Test
    fun saveAndRetrieveProfile() {
        val store = createStore()
        val profile = ChargerProfile("AA:BB:CC", "My Charger", "1234", 1000L)
        store.saveProfile(profile)

        assertEquals(setOf("AA:BB:CC"), store.getSavedAddresses())
        val profiles = store.getSavedProfiles()
        assertEquals(1, profiles.size)
        assertEquals("My Charger", profiles[0].displayName)
        assertEquals("1234", profiles[0].password)
        assertEquals(1000L, profiles[0].lastConnectedMs)
    }

    @Test
    fun getProfileReturnsNullForUnknown() {
        val store = createStore()
        assertNull(store.getProfile("AA:BB:CC"))
    }

    @Test
    fun getProfileReturnsExisting() {
        val store = createStore()
        store.saveProfile(ChargerProfile("AA:BB:CC", "Charger", "pw", 500L))
        val profile = store.getProfile("AA:BB:CC")
        assertNotNull(profile)
        assertEquals("Charger", profile.displayName)
        assertEquals("pw", profile.password)
    }

    @Test
    fun deleteProfileRemovesAllKeys() {
        val store = createStore()
        store.saveProfile(ChargerProfile("AA:BB:CC", "Charger", "pw", 500L))
        store.deleteProfile("AA:BB:CC")

        assertTrue(store.getSavedAddresses().isEmpty())
        assertNull(store.getProfile("AA:BB:CC"))
    }

    @Test
    fun multipleProfilesSortedByRecency() {
        val store = createStore()
        store.saveProfile(ChargerProfile("A", "Older", "", 100L))
        store.saveProfile(ChargerProfile("B", "Newer", "", 200L))

        val profiles = store.getSavedProfiles()
        assertEquals(2, profiles.size)
        assertEquals("Newer", profiles[0].displayName)
        assertEquals("Older", profiles[1].displayName)
    }
}
