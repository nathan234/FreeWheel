package org.freewheel.core.ble

import org.freewheel.core.domain.WheelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for BLE UUID constants and helper functions.
 */
class BleUuidsTest {

    @Test
    fun `Kingsong UUIDs are correct`() {
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", BleUuids.Kingsong.SERVICE)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", BleUuids.Kingsong.READ_CHARACTERISTIC)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", BleUuids.Kingsong.WRITE_CHARACTERISTIC)
    }

    @Test
    fun `Gotway UUIDs are correct`() {
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", BleUuids.Gotway.SERVICE)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", BleUuids.Gotway.READ_CHARACTERISTIC)
    }

    @Test
    fun `InMotion V1 UUIDs are correct`() {
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", BleUuids.InMotion.READ_SERVICE)
        assertEquals("0000ffe4-0000-1000-8000-00805f9b34fb", BleUuids.InMotion.READ_CHARACTERISTIC)
        assertEquals("0000ffe5-0000-1000-8000-00805f9b34fb", BleUuids.InMotion.WRITE_SERVICE)
        assertEquals("0000ffe9-0000-1000-8000-00805f9b34fb", BleUuids.InMotion.WRITE_CHARACTERISTIC)
    }

    @Test
    fun `InMotion V2 UUIDs use Nordic UART`() {
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", BleUuids.InMotionV2.SERVICE)
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", BleUuids.InMotionV2.WRITE_CHARACTERISTIC)
        assertEquals("6e400003-b5a3-f393-e0a9-e50e24dcca9e", BleUuids.InMotionV2.READ_CHARACTERISTIC)
    }

    @Test
    fun `Ninebot Z UUIDs use Nordic UART`() {
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", BleUuids.NinebotZ.SERVICE)
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", BleUuids.NinebotZ.WRITE_CHARACTERISTIC)
        assertEquals("6e400003-b5a3-f393-e0a9-e50e24dcca9e", BleUuids.NinebotZ.READ_CHARACTERISTIC)
    }

    @Test
    fun `InMotion V2 and Ninebot Z share same Nordic UART service`() {
        assertEquals(BleUuids.InMotionV2.SERVICE, BleUuids.NinebotZ.SERVICE)
        assertEquals(BleUuids.InMotionV2.READ_CHARACTERISTIC, BleUuids.NinebotZ.READ_CHARACTERISTIC)
        assertEquals(BleUuids.InMotionV2.WRITE_CHARACTERISTIC, BleUuids.NinebotZ.WRITE_CHARACTERISTIC)
    }

    @Test
    fun `Ninebot UUIDs are correct`() {
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", BleUuids.Ninebot.SERVICE)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", BleUuids.Ninebot.READ_CHARACTERISTIC)
    }

    @Test
    fun `normalize converts to lowercase`() {
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", BleUuids.normalize("0000FFE0-0000-1000-8000-00805F9B34FB"))
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", BleUuids.normalize("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"))
    }

    @Test
    fun `matches compares case-insensitively`() {
        assertTrue(BleUuids.matches("0000FFE0-0000-1000-8000-00805F9B34FB", "0000ffe0-0000-1000-8000-00805f9b34fb"))
        assertTrue(BleUuids.matches("6E400001-B5A3-F393-E0A9-E50E24DCCA9E", "6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
        assertFalse(BleUuids.matches("0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun `client characteristic config descriptor is correct`() {
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", BleUuids.CLIENT_CHARACTERISTIC_CONFIG)
        assertEquals(BleUuids.CLIENT_CHARACTERISTIC_CONFIG, BleUuids.Kingsong.DESCRIPTOR)
        assertEquals(BleUuids.CLIENT_CHARACTERISTIC_CONFIG, BleUuids.InMotion.DESCRIPTOR)
    }

    // ----- canonicalize -----

    @Test
    fun `canonicalize expands 4-char short UUID`() {
        assertEquals(
            "0000ffe0-0000-1000-8000-00805f9b34fb",
            BleUuids.canonicalize("FFE0")
        )
    }

    @Test
    fun `canonicalize expands 8-char short UUID`() {
        assertEquals(
            "0000ffe1-0000-1000-8000-00805f9b34fb",
            BleUuids.canonicalize("0000FFE1")
        )
    }

    @Test
    fun `canonicalize lowercases full 36-char UUID`() {
        assertEquals(
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            BleUuids.canonicalize("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        )
    }

    @Test
    fun `canonicalize is idempotent for already-canonical input`() {
        val canonical = "0000ffe0-0000-1000-8000-00805f9b34fb"
        assertEquals(canonical, BleUuids.canonicalize(canonical))
    }

    @Test
    fun `matches treats 16-bit short form as equal to full form`() {
        assertTrue(BleUuids.matches("FFE0", BleUuids.Gotway.SERVICE))
    }

    @Test
    fun `matches treats 32-bit short form as equal to full form`() {
        assertTrue(BleUuids.matches("0000FFE1", BleUuids.Kingsong.READ_CHARACTERISTIC))
    }
}

/**
 * Tests for CoreBluetooth short UUID expansion.
 *
 * CoreBluetooth returns short UUID strings for standard Bluetooth SIG services:
 * - 4-char: "FFE0" instead of "0000FFE0-0000-1000-8000-00805F9B34FB"
 * - 8-char: "0000FFE0" instead of "0000FFE0-0000-1000-8000-00805F9B34FB"
 *
 * These tests verify that DiscoveredServices built from expanded short UUIDs
 * correctly match against the full 128-bit UUIDs used in BleUuids.
 */
class CoreBluetoothUuidExpansionTest {

    /** Now uses the shared canonicalization helper. */
    private fun expandCoreBluetoothUuid(uuidString: String): String =
        BleUuids.canonicalize(uuidString)

    @Test
    fun `expand 4-char short UUID to full 128-bit`() {
        val expanded = expandCoreBluetoothUuid("FFE0")
        assertTrue(BleUuids.matches(expanded, BleUuids.Gotway.SERVICE))
    }

    @Test
    fun `expand 8-char short UUID to full 128-bit`() {
        val expanded = expandCoreBluetoothUuid("0000FFE1")
        assertTrue(BleUuids.matches(expanded, BleUuids.Gotway.READ_CHARACTERISTIC))
    }

    @Test
    fun `full 128-bit UUID passes through unchanged`() {
        val uuid = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        assertEquals(uuid, expandCoreBluetoothUuid(uuid))
    }

    @Test
    fun `expanded UUIDs match in DiscoveredServices`() {
        // Simulate building DiscoveredServices from CoreBluetooth short UUIDs
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = expandCoreBluetoothUuid("FFE0"),
                    characteristics = listOf(expandCoreBluetoothUuid("FFE1"))
                ),
                DiscoveredService(
                    uuid = expandCoreBluetoothUuid("FFF0"),
                    characteristics = listOf(expandCoreBluetoothUuid("FFF1"))
                )
            )
        )

        assertTrue(services.hasService(BleUuids.Gotway.SERVICE))
        assertTrue(services.hasCharacteristic(BleUuids.Gotway.SERVICE, BleUuids.Gotway.READ_CHARACTERISTIC))
        assertTrue(services.hasService("0000fff0-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun `expanded Nordic UART UUIDs match`() {
        // Nordic UART uses full 128-bit UUIDs, not short ones
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = expandCoreBluetoothUuid("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
                    characteristics = listOf(
                        expandCoreBluetoothUuid("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
                        expandCoreBluetoothUuid("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
                    )
                )
            )
        )

        assertTrue(services.hasService(BleUuids.InMotionV2.SERVICE))
        assertTrue(services.hasCharacteristic(BleUuids.InMotionV2.SERVICE, BleUuids.InMotionV2.READ_CHARACTERISTIC))
    }

    @Test
    fun `WheelTypeDetector works with expanded CoreBluetooth UUIDs`() {
        val detector = WheelTypeDetector()

        // Simulate a real Gotway wheel as seen by CoreBluetooth
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = expandCoreBluetoothUuid("180A"),
                    characteristics = listOf(expandCoreBluetoothUuid("2A23"))
                ),
                DiscoveredService(
                    uuid = expandCoreBluetoothUuid("FFF0"),
                    characteristics = listOf(expandCoreBluetoothUuid("FFF1"))
                ),
                DiscoveredService(
                    uuid = expandCoreBluetoothUuid("FFE0"),
                    characteristics = listOf(expandCoreBluetoothUuid("FFE1"))
                )
            )
        )

        val result = detector.detect(services, "GotWay_008977")
        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        assertEquals(WheelType.GOTWAY, (result as WheelTypeDetector.DetectionResult.Detected).wheelType)
    }
}

/**
 * Tests for DiscoveredService and DiscoveredServices data classes.
 */
class DiscoveredServicesTest {

    @Test
    fun `DiscoveredService hasCharacteristic works`() {
        val service = DiscoveredService(
            uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
            characteristics = listOf(
                "0000ffe1-0000-1000-8000-00805f9b34fb",
                "0000ffe4-0000-1000-8000-00805f9b34fb"
            )
        )

        assertTrue(service.hasCharacteristic("0000ffe1-0000-1000-8000-00805f9b34fb"))
        assertTrue(service.hasCharacteristic("0000FFE4-0000-1000-8000-00805F9B34FB")) // case-insensitive
        assertFalse(service.hasCharacteristic("0000ffe9-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun `DiscoveredService matchesService works case-insensitively`() {
        val service = DiscoveredService(
            uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
            characteristics = emptyList()
        )

        assertTrue(service.matchesService("0000ffe0-0000-1000-8000-00805f9b34fb"))
        assertTrue(service.matchesService("0000FFE0-0000-1000-8000-00805F9B34FB"))
        assertFalse(service.matchesService("0000ffe5-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun `DiscoveredServices findService returns correct service`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService("0000ffe0-0000-1000-8000-00805f9b34fb", listOf("0000ffe1-0000-1000-8000-00805f9b34fb")),
                DiscoveredService("0000ffe5-0000-1000-8000-00805f9b34fb", listOf("0000ffe9-0000-1000-8000-00805f9b34fb"))
            )
        )

        val found = services.findService("0000ffe5-0000-1000-8000-00805f9b34fb")
        assertEquals("0000ffe5-0000-1000-8000-00805f9b34fb", found?.uuid)
        assertTrue(found?.hasCharacteristic("0000ffe9-0000-1000-8000-00805f9b34fb") == true)
    }

    @Test
    fun `DiscoveredServices hasService works`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService("0000ffe0-0000-1000-8000-00805f9b34fb", emptyList())
            )
        )

        assertTrue(services.hasService("0000ffe0-0000-1000-8000-00805f9b34fb"))
        assertFalse(services.hasService("0000ffe5-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun `DiscoveredServices hasCharacteristic works`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    "0000ffe0-0000-1000-8000-00805f9b34fb",
                    listOf("0000ffe4-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        assertTrue(services.hasCharacteristic("0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe4-0000-1000-8000-00805f9b34fb"))
        assertFalse(services.hasCharacteristic("0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb"))
        assertFalse(services.hasCharacteristic("0000ffe5-0000-1000-8000-00805f9b34fb", "0000ffe4-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun `DiscoveredServices serviceUuids returns all UUIDs`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService("service1", emptyList()),
                DiscoveredService("service2", emptyList()),
                DiscoveredService("service3", emptyList())
            )
        )

        assertEquals(listOf("service1", "service2", "service3"), services.serviceUuids())
    }
}
