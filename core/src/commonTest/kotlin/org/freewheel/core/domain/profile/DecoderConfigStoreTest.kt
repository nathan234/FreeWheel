package org.freewheel.core.domain.profile

import org.freewheel.core.domain.FakeKeyValueStore
import org.freewheel.core.domain.identity.WheelIdentity
import org.freewheel.core.domain.identity.WheelType
import org.freewheel.core.domain.settings.PreferenceDefaults
import org.freewheel.core.domain.settings.PreferenceKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecoderConfigStoreTest {

    private fun newStore(): Pair<DecoderConfigStore, FakeKeyValueStore> {
        val kvs = FakeKeyValueStore()
        return DecoderConfigStore(kvs) to kvs
    }

    private fun setMac(kvs: FakeKeyValueStore, mac: String) {
        // DecoderConfigStore reads from LAST_CONNECTED_MAC for per-wheel scoping.
        // LAST_MAC is the auto-reconnect target — set in lockstep here so tests cover
        // the realistic state where both anchors point at the same wheel.
        kvs.putString(PreferenceKeys.LAST_MAC, mac)
        kvs.putString(PreferenceKeys.LAST_CONNECTED_MAC, mac)
    }

    @Test
    fun `defaults returned when keys absent`() {
        val (store, _) = newStore()
        assertEquals(WheelCalibration(), store.getCalibration())
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
    fun `legacy global custom percents is fallback but scoped value wins`() {
        val (store, kvs) = newStore()
        kvs.putBool(PreferenceKeys.CUSTOM_PERCENTS, true)
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        assertTrue(store.getCalibration().customBatteryPercentEnabled)

        kvs.putBool("AA:BB:CC:DD:EE:FF_${PreferenceKeys.CUSTOM_PERCENTS}", false)
        assertFalse(store.getCalibration().customBatteryPercentEnabled)

        setMac(kvs, "11:22:33:44:55:66")
        assertTrue(store.getCalibration().customBatteryPercentEnabled)
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
    fun `gotway voltage parsing falls back to automatic on malformed string`() {
        val (store, kvs) = newStore()
        setMac(kvs, "AA:BB:CC:DD:EE:FF")
        kvs.putString("AA:BB:CC:DD:EE:FF_${PreferenceKeys.GOTWAY_VOLTAGE}", "not-a-number")
        assertEquals(-1, store.getGotwayVoltage())
    }

    @Test
    fun `calibration reads legacy keys into typed values`() {
        val (store, kvs) = newStore()
        val address = "AA:BB:CC:DD:EE:FF"
        setMac(kvs, address)
        kvs.putBool("${address}_${PreferenceKeys.CUSTOM_PERCENTS}", true)
        kvs.putInt("${address}_${PreferenceKeys.CELL_VOLTAGE_TILTBACK}", 315)
        kvs.putInt("${address}_${PreferenceKeys.ROTATION_SPEED}", 650)
        kvs.putInt("${address}_${PreferenceKeys.ROTATION_VOLTAGE}", 1260)
        kvs.putInt("${address}_${PreferenceKeys.POWER_FACTOR}", 95)
        kvs.putInt("${address}_${PreferenceKeys.BATTERY_CAPACITY}", 3600)
        kvs.putString("${address}_${PreferenceKeys.GOTWAY_NEGATIVE}", "-1")
        kvs.putString("${address}_${PreferenceKeys.GOTWAY_VOLTAGE}", "5")
        kvs.putBool("${address}_${PreferenceKeys.USE_RATIO}", true)
        kvs.putBool("${address}_${PreferenceKeys.HW_PWM}", true)
        kvs.putBool("${address}_${PreferenceKeys.AUTO_VOLTAGE}", false)
        kvs.putBool("${address}_${PreferenceKeys.KS18L_SCALER}", true)

        assertEquals(
            WheelCalibration(
                customBatteryPercentEnabled = true,
                emptyCellVoltageHundredths = 315,
                rotationSpeedTenthsKmh = 650,
                rotationVoltageTenthsVolts = 1260,
                powerFactorPercent = 95,
                batteryCapacityWh = 3600,
                currentPolarity = WheelCurrentPolarity.INVERTED,
                begodeVoltageClass = BegodeVoltageClass.V168,
                gotwayDistanceRatioEnabled = true,
                hardwarePwmEnabled = true,
                autoVoltageEnabled = false,
                ks18LDistanceScalerEnabled = true,
            ),
            store.getCalibration(),
        )
    }

    @Test
    fun `saving calibration uses legacy scoped keys and isolates wheels`() {
        val (store, kvs) = newStore()
        val first = "AA:BB:CC:DD:EE:FF"
        val second = "11:22:33:44:55:66"
        val calibration = WheelCalibration(
            customBatteryPercentEnabled = true,
            emptyCellVoltageHundredths = 320,
            currentPolarity = WheelCurrentPolarity.ORIGINAL,
            begodeVoltageClass = BegodeVoltageClass.V151_2,
        )

        store.saveCalibration(first, calibration)

        assertEquals(calibration, store.getCalibration(first))
        assertEquals(WheelCalibration(), store.getCalibration(second))
        assertTrue(kvs.getBool("${first}_${PreferenceKeys.CUSTOM_PERCENTS}", false))
        assertEquals("1", kvs.getString("${first}_${PreferenceKeys.GOTWAY_NEGATIVE}", null))
        assertEquals("6", kvs.getString("${first}_${PreferenceKeys.GOTWAY_VOLTAGE}", null))
    }

    @Test
    fun `typed calibration safely defaults malformed enum values`() {
        val (store, kvs) = newStore()
        val address = "AA:BB:CC:DD:EE:FF"
        kvs.putString("${address}_${PreferenceKeys.GOTWAY_NEGATIVE}", "not-a-number")
        kvs.putString("${address}_${PreferenceKeys.GOTWAY_VOLTAGE}", "not-a-number")

        val calibration = store.getCalibration(address)

        assertEquals(WheelCurrentPolarity.ABSOLUTE, calibration.currentPolarity)
        assertEquals(BegodeVoltageClass.AUTO, calibration.begodeVoltageClass)
    }

    @Test
    fun `reset calibration restores per-wheel defaults without reviving global legacy value`() {
        val (store, kvs) = newStore()
        val address = "AA:BB:CC:DD:EE:FF"
        kvs.putBool(PreferenceKeys.CUSTOM_PERCENTS, true)
        store.saveCalibration(
            address,
            WheelCalibration(
                customBatteryPercentEnabled = true,
                rotationSpeedTenthsKmh = 700,
                begodeVoltageClass = BegodeVoltageClass.V168,
            ),
        )

        store.resetCalibration(address)

        assertEquals(WheelCalibration(), store.getCalibration(address))
        val resolved = store.getResolvedCalibration(address, WheelIdentity())
        assertEquals(
            WheelCalibrationSource.DEFAULT,
            resolved.sourceFor(WheelCalibrationField.CUSTOM_BATTERY_PERCENT),
        )
        assertFalse(resolved.hasUserOverrides)
    }

    @Test
    fun `model catalog supplies effective Begode calibration with provenance`() {
        val (store, _) = newStore()
        val resolved = store.getResolvedCalibration(
            address = "AA:BB:CC:DD:EE:FF",
            identity = WheelIdentity(
                wheelType = WheelType.GOTWAY,
                model = "Commander Max",
                brand = "Extreme Bull",
            ),
        )

        assertEquals("Commander Max", resolved.matchedModelName)
        assertEquals(BegodeVoltageClass.V168, resolved.calibration.begodeVoltageClass)
        assertEquals(1700, resolved.calibration.rotationSpeedTenthsKmh)
        assertEquals(1680, resolved.calibration.rotationVoltageTenthsVolts)
        assertEquals(100, resolved.calibration.powerFactorPercent)
        assertEquals(
            WheelCalibrationSource.MODEL_CATALOG,
            resolved.sourceFor(WheelCalibrationField.BEGODE_VOLTAGE_CLASS),
        )
        assertEquals(
            WheelCalibrationSource.MODEL_CATALOG,
            resolved.sourceFor(WheelCalibrationField.ROTATION_SPEED),
        )
        assertEquals(
            WheelCalibrationSource.DEFAULT,
            resolved.sourceFor(WheelCalibrationField.BATTERY_CAPACITY),
        )
        assertFalse(resolved.hasUserOverrides)
    }

    @Test
    fun `scoped override wins only its calibration field`() {
        val (store, kvs) = newStore()
        val address = "AA:BB:CC:DD:EE:FF"
        kvs.putInt("${address}_${PreferenceKeys.ROTATION_SPEED}", 1600)
        kvs.putString("${address}_${PreferenceKeys.GOTWAY_VOLTAGE}", "4")

        val resolved = store.getResolvedCalibration(
            address = address,
            identity = WheelIdentity(wheelType = WheelType.GOTWAY, model = "Commander Max"),
        )

        assertEquals(1600, resolved.calibration.rotationSpeedTenthsKmh)
        assertEquals(BegodeVoltageClass.V134_4, resolved.calibration.begodeVoltageClass)
        assertEquals(1680, resolved.calibration.rotationVoltageTenthsVolts)
        assertEquals(
            WheelCalibrationSource.USER_OVERRIDE,
            resolved.sourceFor(WheelCalibrationField.ROTATION_SPEED),
        )
        assertEquals(
            WheelCalibrationSource.USER_OVERRIDE,
            resolved.sourceFor(WheelCalibrationField.BEGODE_VOLTAGE_CLASS),
        )
        assertEquals(
            WheelCalibrationSource.MODEL_CATALOG,
            resolved.sourceFor(WheelCalibrationField.ROTATION_VOLTAGE),
        )
        assertTrue(resolved.hasUserOverrides)
    }

    @Test
    fun `automatic voltage selection remains catalog derived when explicitly stored`() {
        val (store, kvs) = newStore()
        val address = "AA:BB:CC:DD:EE:FF"
        kvs.putString("${address}_${PreferenceKeys.GOTWAY_VOLTAGE}", "-1")

        val resolved = store.getResolvedCalibration(
            address = address,
            identity = WheelIdentity(wheelType = WheelType.GOTWAY, model = "Commander Max"),
        )

        assertEquals(BegodeVoltageClass.V168, resolved.calibration.begodeVoltageClass)
        assertEquals(
            WheelCalibrationSource.MODEL_CATALOG,
            resolved.sourceFor(WheelCalibrationField.BEGODE_VOLTAGE_CLASS),
        )
    }

    @Test
    fun `legacy global custom percentage fallback is identified`() {
        val (store, kvs) = newStore()
        val address = "AA:BB:CC:DD:EE:FF"
        kvs.putBool(PreferenceKeys.CUSTOM_PERCENTS, true)

        val resolved = store.getResolvedCalibration(address, WheelIdentity())

        assertTrue(resolved.calibration.customBatteryPercentEnabled)
        assertEquals(
            WheelCalibrationSource.LEGACY_GLOBAL,
            resolved.sourceFor(WheelCalibrationField.CUSTOM_BATTERY_PERCENT),
        )
    }

    @Test
    fun `decoder updates are limited to calibration address and credential keys`() {
        val (store, _) = newStore()

        assertTrue(store.affectsDecoderConfig(null))
        assertTrue(store.affectsDecoderConfig(PreferenceKeys.LAST_CONNECTED_MAC))
        assertTrue(store.affectsDecoderConfig(PreferenceKeys.CUSTOM_PERCENTS))
        assertTrue(store.affectsDecoderConfig("AA:BB_${PreferenceKeys.ROTATION_SPEED}"))
        assertTrue(store.affectsDecoderConfig("wheel_password_AA:BB"))
        assertFalse(store.affectsDecoderConfig(PreferenceKeys.USE_MPH))
        assertFalse(store.affectsDecoderConfig(PreferenceKeys.ALARMS_ENABLED))
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
