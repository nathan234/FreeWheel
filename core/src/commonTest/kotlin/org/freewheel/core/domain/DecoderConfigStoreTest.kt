package org.freewheel.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class DecoderConfigStoreTest {

    private fun newStore(): Pair<DecoderConfigStore, FakeKeyValueStore> {
        val kvs = FakeKeyValueStore()
        return DecoderConfigStore(kvs) to kvs
    }

    private fun setMac(kvs: FakeKeyValueStore, mac: String) {
        kvs.putString(PreferenceKeys.LAST_MAC, mac)
    }

    @Test
    fun `defaults returned when keys absent`() {
        val (store, _) = newStore()
        assertEquals(PreferenceDefaults.CUSTOM_PERCENTS, store.getCustomPercents())
        assertEquals(PreferenceDefaults.CELL_VOLTAGE_TILTBACK, store.getCellVoltageTiltback())
        assertEquals(PreferenceDefaults.ROTATION_SPEED, store.getRotationSpeed())
        assertEquals(PreferenceDefaults.ROTATION_VOLTAGE, store.getRotationVoltage())
        assertEquals(PreferenceDefaults.POWER_FACTOR, store.getPowerFactor())
        assertEquals(PreferenceDefaults.BATTERY_CAPACITY, store.getBatteryCapacity())
        assertEquals(PreferenceDefaults.USE_RATIO, store.getUseRatio())
        assertEquals(PreferenceDefaults.HW_PWM, store.getHwPwm())
        assertEquals(PreferenceDefaults.AUTO_VOLTAGE, store.getAutoVoltage())
        assertEquals(PreferenceDefaults.KS18L_SCALER, store.getKs18LScaler())
        assertEquals(PreferenceDefaults.GOTWAY_NEGATIVE.toInt(), store.getGotwayNegative())
        assertEquals(PreferenceDefaults.GOTWAY_VOLTAGE.toInt(), store.getGotwayVoltage())
        assertEquals("", store.getWheelPassword())
    }

    @Test
    fun `customPercents is global - same value regardless of MAC`() {
        val (store, kvs) = newStore()
        kvs.putBool(PreferenceKeys.CUSTOM_PERCENTS, true)
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        assertEquals(true, store.getCustomPercents())
        setMac(kvs, "11:22:33:44:55:66")
        assertEquals(true, store.getCustomPercents())
    }

    @Test
    fun `per-wheel int value reads from MAC-prefixed key`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        kvs.putInt("AA:BB:CC:DD:EE:FF_${PreferenceKeys.CELL_VOLTAGE_TILTBACK}", 320)
        assertEquals(320, store.getCellVoltageTiltback())
    }

    @Test
    fun `per-wheel bool value reads from MAC-prefixed key`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        kvs.putBool("AA:BB:CC:DD:EE:FF_${PreferenceKeys.HW_PWM}", true)
        assertEquals(true, store.getHwPwm())
    }

    @Test
    fun `per-wheel reads switch with MAC change`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        kvs.putInt("AA:BB:CC:DD:EE:FF_${PreferenceKeys.POWER_FACTOR}", 95)
        kvs.putInt("11:22:33:44:55:66_${PreferenceKeys.POWER_FACTOR}", 85)

        assertEquals(95, store.getPowerFactor())
        setMac(kvs, "11:22:33:44:55:66")
        assertEquals(85, store.getPowerFactor())
    }

    @Test
    fun `gotwayNegative parses string storage to int`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        kvs.putString("AA:BB:CC:DD:EE:FF_${PreferenceKeys.GOTWAY_NEGATIVE}", "1")
        assertEquals(1, store.getGotwayNegative())
    }

    @Test
    fun `gotwayVoltage parses string storage to int`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        kvs.putString("AA:BB:CC:DD:EE:FF_${PreferenceKeys.GOTWAY_VOLTAGE}", "2")
        assertEquals(2, store.getGotwayVoltage())
    }

    @Test
    fun `gotway int parsing falls back to 0 on malformed string`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        kvs.putString("AA:BB:CC:DD:EE:FF_${PreferenceKeys.GOTWAY_VOLTAGE}", "not-a-number")
        assertEquals(0, store.getGotwayVoltage())
    }

    @Test
    fun `wheelPassword reads legacy key format`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        // Legacy AppConfig stored at "wheel_password_$mac" (mac AFTER the underscore)
        kvs.putString("wheel_password_AA:BB:CC:DD:EE:FF", "123456")
        assertEquals("123456", store.getWheelPassword())
    }

    @Test
    fun `wheelPassword returns empty when no MAC connected`() {
        val (store, _) = newStore()
        assertEquals("", store.getWheelPassword())
    }
}
