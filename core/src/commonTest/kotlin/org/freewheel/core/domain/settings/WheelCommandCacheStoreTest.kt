package org.freewheel.core.domain.settings

import org.freewheel.core.domain.FakeKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WheelCommandCacheStoreTest {

    private fun newStore(): Pair<WheelCommandCacheStore, FakeKeyValueStore> {
        val kvs = FakeKeyValueStore()
        return WheelCommandCacheStore(kvs) to kvs
    }

    private fun setMac(kvs: FakeKeyValueStore, mac: String) {
        kvs.putString(PreferenceKeys.LAST_CONNECTED_MAC, mac)
    }

    @Test
    fun `last sent command value and timestamp round trip`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")

        store.saveLastSent(SettingsCommandId.MAX_SPEED, value = 42, sentAtEpochMs = 123_456L)

        assertEquals(
            LastSentWheelCommand(value = 42, sentAtEpochMs = 123_456L),
            store.loadLastSent(SettingsCommandId.MAX_SPEED),
        )
    }

    @Test
    fun `legacy slider value loads without invented timestamp`() {
        val (store, kvs) = newStore()
        val mac = "AA:BB:CC:DD:EE:FF"
        setMac(kvs, mac)
        kvs.putInt(PreferenceKeys.wheelSliderKey(mac, SettingsCommandId.MAX_SPEED.name), 38)

        assertEquals(
            LastSentWheelCommand(value = 38, sentAtEpochMs = null),
            store.loadLastSent(SettingsCommandId.MAX_SPEED),
        )
    }

    @Test
    fun `cache is isolated by physical wheel`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        store.saveLastSent(SettingsCommandId.MAX_SPEED, value = 42, sentAtEpochMs = 100L)

        setMac(kvs, "11:22:33:44:55:66")
        assertNull(store.loadLastSent(SettingsCommandId.MAX_SPEED))
        store.saveLastSent(SettingsCommandId.MAX_SPEED, value = 35, sentAtEpochMs = 200L)

        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        assertEquals(42, store.loadLastSent(SettingsCommandId.MAX_SPEED)?.value)
    }

    @Test
    fun `cache does not create global fallback without wheel address`() {
        val (store, _) = newStore()

        store.saveLastSent(SettingsCommandId.MAX_SPEED, value = 42, sentAtEpochMs = 100L)

        assertNull(store.loadLastSent(SettingsCommandId.MAX_SPEED))
    }
}
