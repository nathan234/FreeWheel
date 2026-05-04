package org.freewheel.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSettingsStoreTest {

    private fun newStore(): Pair<AppSettingsStore, FakeKeyValueStore> {
        val kvs = FakeKeyValueStore()
        return AppSettingsStore(kvs) to kvs
    }

    private fun setMac(kvs: FakeKeyValueStore, mac: String) {
        kvs.putString("last_mac", mac)
    }

    @Test
    fun `bool default returned when key absent`() {
        val (store, _) = newStore()
        assertEquals(AppSettingId.USE_MPH.defaultBool, store.getBool(AppSettingId.USE_MPH))
    }

    @Test
    fun `int default returned when key absent`() {
        val (store, _) = newStore()
        assertEquals(AppSettingId.ALARM_1_SPEED.defaultInt, store.getInt(AppSettingId.ALARM_1_SPEED))
    }

    @Test
    fun `speed display mode round-trips`() {
        val (store, _) = newStore()
        assertEquals(SpeedDisplayMode.entries[0], store.getSpeedDisplayMode())
        store.setSpeedDisplayMode(SpeedDisplayMode.entries[1])
        assertEquals(SpeedDisplayMode.entries[1], store.getSpeedDisplayMode())
    }

    @Test
    fun `speed display mode clamps out-of-range stored ordinal`() {
        val (store, kvs) = newStore()
        kvs.putInt(PreferenceKeys.SPEED_DISPLAY_MODE, 99)
        assertEquals(SpeedDisplayMode.entries.last(), store.getSpeedDisplayMode())
    }

    @Test
    fun `global scope writes to plain pref key`() {
        val (store, kvs) = newStore()
        store.setBool(AppSettingId.USE_MPH, true)
        assertEquals(true, kvs.getBool(PreferenceKeys.USE_MPH, false))
        assertEquals(true, store.getBool(AppSettingId.USE_MPH))
    }

    @Test
    fun `global scope is independent of MAC`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        store.setBool(AppSettingId.USE_FAHRENHEIT, true)
        // Stored under bare key, not MAC-prefixed
        assertEquals(true, kvs.getBool(PreferenceKeys.USE_FAHRENHEIT, false))
        assertEquals(false, kvs.getBool("AA:BB:CC:DD:EE:FF_${PreferenceKeys.USE_FAHRENHEIT}", false))
    }

    @Test
    fun `per-wheel scope writes under MAC prefix`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        store.setBool(AppSettingId.ALARMS_ENABLED, true)
        assertEquals(
            true,
            kvs.getBool("AA:BB:CC:DD:EE:FF_${PreferenceKeys.ALARMS_ENABLED}", false)
        )
        assertEquals(true, store.getBool(AppSettingId.ALARMS_ENABLED))
    }

    @Test
    fun `per-wheel reads from new MAC after switch`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        store.setInt(AppSettingId.ALARM_1_SPEED, 25)

        setMac(kvs, "11:22:33:44:55:66")
        // Different wheel — no value yet, returns default
        assertEquals(AppSettingId.ALARM_1_SPEED.defaultInt, store.getInt(AppSettingId.ALARM_1_SPEED))

        store.setInt(AppSettingId.ALARM_1_SPEED, 30)
        assertEquals(30, store.getInt(AppSettingId.ALARM_1_SPEED))

        // Original wheel still has its own value
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        assertEquals(25, store.getInt(AppSettingId.ALARM_1_SPEED))
    }

    @Test
    fun `slider save and load round-trip with MAC`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        store.saveSliderValue(SettingsCommandId.MAX_SPEED, 42)
        assertEquals(42, store.loadSliderValue(SettingsCommandId.MAX_SPEED))
    }

    @Test
    fun `slider save is no-op when no MAC`() {
        val (store, kvs) = newStore()
        // No MAC set — save should silently no-op rather than write a bare-prefix key
        store.saveSliderValue(SettingsCommandId.MAX_SPEED, 42)
        assertTrue(kvs.getStringSet("any").isEmpty())
        assertNull(store.loadSliderValue(SettingsCommandId.MAX_SPEED))
    }

    @Test
    fun `slider load returns null when key absent`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        assertNull(store.loadSliderValue(SettingsCommandId.MAX_SPEED))
    }

    @Test
    fun `slider values are isolated per wheel`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        store.saveSliderValue(SettingsCommandId.MAX_SPEED, 42)

        setMac(kvs, "11:22:33:44:55:66")
        assertNull(store.loadSliderValue(SettingsCommandId.MAX_SPEED))
        store.saveSliderValue(SettingsCommandId.MAX_SPEED, 35)

        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        assertEquals(42, store.loadSliderValue(SettingsCommandId.MAX_SPEED))
    }

    @Test
    fun `getLastMac returns empty when unset`() {
        val (store, _) = newStore()
        assertEquals("", store.getLastMac())
    }

    @Test
    fun `getLastMac returns stored value`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:CC:DD:EE:FF", store.getLastMac())
    }

    @Test
    fun `setLastMac writes the last_mac key`() {
        val (store, kvs) = newStore()
        store.setLastMac("AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:CC:DD:EE:FF", kvs.getString("last_mac", null))
        assertEquals("AA:BB:CC:DD:EE:FF", store.getLastMac())
    }

    @Test
    fun `setLastMac empty string clears the wheel binding`() {
        val (store, kvs) = newStore()
        store.setLastMac("AA:BB:CC:DD:EE:FF")
        store.setLastMac("")
        assertEquals("", store.getLastMac())
        // After clearing, per-wheel reads fall back to the unscoped default
        kvs.putBool("AA:BB:CC:DD:EE:FF_${PreferenceKeys.ALARMS_ENABLED}", true)
        assertEquals(false, store.getBool(AppSettingId.ALARMS_ENABLED))
    }

}
