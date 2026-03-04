package org.freewheel.core.ble

import org.freewheel.core.domain.WheelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for WheelConnectionInfo factory methods and data class.
 */
class WheelConnectionInfoTest {

    @Test
    fun `forKingsong returns correct UUIDs`() {
        val info = WheelConnectionInfo.forKingsong()

        assertEquals(WheelType.KINGSONG, info.wheelType)
        assertEquals(BleUuids.Kingsong.SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.Kingsong.READ_CHARACTERISTIC, info.readCharacteristicUuid)
        assertEquals(BleUuids.Kingsong.SERVICE, info.writeServiceUuid)
        assertEquals(BleUuids.Kingsong.WRITE_CHARACTERISTIC, info.writeCharacteristicUuid)
    }

    @Test
    fun `forGotway returns correct UUIDs`() {
        val info = WheelConnectionInfo.forGotway()

        assertEquals(WheelType.GOTWAY, info.wheelType)
        assertEquals(BleUuids.Gotway.SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.Gotway.READ_CHARACTERISTIC, info.readCharacteristicUuid)
        assertEquals(BleUuids.Gotway.SERVICE, info.writeServiceUuid)
        assertEquals(BleUuids.Gotway.WRITE_CHARACTERISTIC, info.writeCharacteristicUuid)
    }

    @Test
    fun `forVeteran returns correct UUIDs using Gotway profile`() {
        val info = WheelConnectionInfo.forVeteran()

        assertEquals(WheelType.VETERAN, info.wheelType)
        // Veteran uses same UUIDs as Gotway
        assertEquals(BleUuids.Gotway.SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.Gotway.READ_CHARACTERISTIC, info.readCharacteristicUuid)
    }

    @Test
    fun `forInMotion returns correct separate read and write services`() {
        val info = WheelConnectionInfo.forInMotion()

        assertEquals(WheelType.INMOTION, info.wheelType)
        assertEquals(BleUuids.InMotion.READ_SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.InMotion.READ_CHARACTERISTIC, info.readCharacteristicUuid)
        assertEquals(BleUuids.InMotion.WRITE_SERVICE, info.writeServiceUuid)
        assertEquals(BleUuids.InMotion.WRITE_CHARACTERISTIC, info.writeCharacteristicUuid)

        // InMotion V1 has different read and write services
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", info.readServiceUuid)
        assertEquals("0000ffe5-0000-1000-8000-00805f9b34fb", info.writeServiceUuid)
    }

    @Test
    fun `forInMotionV2 returns Nordic UART UUIDs`() {
        val info = WheelConnectionInfo.forInMotionV2()

        assertEquals(WheelType.INMOTION_V2, info.wheelType)
        assertEquals(BleUuids.InMotionV2.SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.InMotionV2.READ_CHARACTERISTIC, info.readCharacteristicUuid)
        assertEquals(BleUuids.InMotionV2.SERVICE, info.writeServiceUuid)
        assertEquals(BleUuids.InMotionV2.WRITE_CHARACTERISTIC, info.writeCharacteristicUuid)

        // Nordic UART service
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", info.readServiceUuid)
    }

    @Test
    fun `forNinebot returns correct UUIDs`() {
        val info = WheelConnectionInfo.forNinebot()

        assertEquals(WheelType.NINEBOT, info.wheelType)
        assertEquals(BleUuids.Ninebot.SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.Ninebot.READ_CHARACTERISTIC, info.readCharacteristicUuid)
    }

    @Test
    fun `forNinebotZ returns Nordic UART UUIDs`() {
        val info = WheelConnectionInfo.forNinebotZ()

        assertEquals(WheelType.NINEBOT_Z, info.wheelType)
        assertEquals(BleUuids.NinebotZ.SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.NinebotZ.READ_CHARACTERISTIC, info.readCharacteristicUuid)

        // Same as InMotionV2 - both use Nordic UART
        assertEquals(BleUuids.InMotionV2.SERVICE, info.readServiceUuid)
    }

    @Test
    fun `forType returns correct info for all supported types`() {
        // Test all wheel types
        assertNotNull(WheelConnectionInfo.forType(WheelType.KINGSONG))
        assertNotNull(WheelConnectionInfo.forType(WheelType.GOTWAY))
        assertNotNull(WheelConnectionInfo.forType(WheelType.GOTWAY_VIRTUAL))
        assertNotNull(WheelConnectionInfo.forType(WheelType.VETERAN))
        assertNotNull(WheelConnectionInfo.forType(WheelType.INMOTION))
        assertNotNull(WheelConnectionInfo.forType(WheelType.INMOTION_V2))
        assertNotNull(WheelConnectionInfo.forType(WheelType.NINEBOT))
        assertNotNull(WheelConnectionInfo.forType(WheelType.NINEBOT_Z))

        // Unknown should return null
        assertNull(WheelConnectionInfo.forType(WheelType.Unknown))
    }

    @Test
    fun `forType GOTWAY_VIRTUAL returns Gotway UUIDs`() {
        val info = WheelConnectionInfo.forType(WheelType.GOTWAY_VIRTUAL)

        assertNotNull(info)
        assertEquals(BleUuids.Gotway.SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.Gotway.READ_CHARACTERISTIC, info.readCharacteristicUuid)
    }

    @Test
    fun `descriptor UUID defaults to CLIENT_CHARACTERISTIC_CONFIG`() {
        val info = WheelConnectionInfo.forKingsong()
        assertEquals(BleUuids.CLIENT_CHARACTERISTIC_CONFIG, info.descriptorUuid)
    }

    @Test
    fun `data class equality works correctly`() {
        val info1 = WheelConnectionInfo.forKingsong()
        val info2 = WheelConnectionInfo.forKingsong()
        val info3 = WheelConnectionInfo.forGotway()

        assertEquals(info1, info2)
        assertEquals(info1.hashCode(), info2.hashCode())
        assertTrue { info1 != info3 }
    }

    @Test
    fun `copy creates modified instance`() {
        val original = WheelConnectionInfo.forKingsong()
        val modified = original.copy(wheelType = WheelType.VETERAN)

        assertEquals(WheelType.KINGSONG, original.wheelType)
        assertEquals(WheelType.VETERAN, modified.wheelType)
        assertEquals(original.readServiceUuid, modified.readServiceUuid)
    }
}
