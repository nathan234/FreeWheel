package com.cooper.wheellog.core.ble

import com.cooper.wheellog.core.domain.WheelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for WheelTypeDetector.
 *
 * These tests verify that wheel type detection matches the behavior
 * of the original Android bluetooth_services.json matching logic.
 */
class WheelTypeDetectorTest {

    private val detector = WheelTypeDetector()

    // ==================== InMotion V1 Detection ====================

    @Test
    fun `detect InMotion V1 from unique service combination`() {
        // InMotion V1 has separate read (ffe0/ffe4) and write (ffe5/ffe9) services
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe4-0000-1000-8000-00805f9b34fb")
                ),
                DiscoveredService(
                    uuid = "0000ffe5-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe9-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services)

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.INMOTION, detected.wheelType)
        assertEquals(BleUuids.InMotion.READ_SERVICE, detected.readServiceUuid)
        assertEquals(BleUuids.InMotion.READ_CHARACTERISTIC, detected.readCharacteristicUuid)
        assertEquals(BleUuids.InMotion.WRITE_SERVICE, detected.writeServiceUuid)
        assertEquals(BleUuids.InMotion.WRITE_CHARACTERISTIC, detected.writeCharacteristicUuid)
        assertEquals(WheelTypeDetector.Confidence.HIGH, detected.confidence)
    }

    // ==================== InMotion V2 Detection ====================

    @Test
    fun `detect InMotion V2 with Nordic UART and ffe4 characteristic`() {
        // InMotion V2 has Nordic UART AND the ffe0/ffe4 combination
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
                    characteristics = listOf(
                        "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
                        "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
                    )
                ),
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe4-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services)

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.INMOTION_V2, detected.wheelType)
        assertEquals(BleUuids.InMotionV2.SERVICE, detected.readServiceUuid)
        assertEquals(BleUuids.InMotionV2.READ_CHARACTERISTIC, detected.readCharacteristicUuid)
        assertEquals(WheelTypeDetector.Confidence.HIGH, detected.confidence)
    }

    // ==================== Ninebot Z Detection ====================

    @Test
    fun `detect Ninebot Z with Nordic UART only`() {
        // Ninebot Z has Nordic UART but NOT the ffe4 characteristic
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
                    characteristics = listOf(
                        "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
                        "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
                    )
                ),
                DiscoveredService(
                    uuid = "00001800-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("00002a00-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services)

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.NINEBOT_Z, detected.wheelType)
        assertEquals(BleUuids.NinebotZ.SERVICE, detected.readServiceUuid)
        assertEquals(BleUuids.NinebotZ.READ_CHARACTERISTIC, detected.readCharacteristicUuid)
        assertEquals(WheelTypeDetector.Confidence.HIGH, detected.confidence)
    }

    // ==================== KingSong Detection ====================

    @Test
    fun `detect KingSong from device name with fff0 service`() {
        // KingSong has the fff0 service and a KS- name
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000fff0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf(
                        "0000fff1-0000-1000-8000-00805f9b34fb",
                        "0000fff2-0000-1000-8000-00805f9b34fb"
                    )
                ),
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, "KS-S18")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.KINGSONG, detected.wheelType)
        assertEquals(WheelTypeDetector.Confidence.HIGH, detected.confidence)
    }

    @Test
    fun `fff0 service without name returns ambiguous`() {
        // fff0 service is shared by both Gotway and KingSong, so without
        // a device name it should be ambiguous
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000fff0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000fff1-0000-1000-8000-00805f9b34fb")
                ),
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services)

        assertTrue(result is WheelTypeDetector.DetectionResult.Ambiguous)
    }

    @Test
    fun `detect KingSong from device name`() {
        // Standard services but KingSong name pattern
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, "KS-S18")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.KINGSONG, detected.wheelType)
        assertEquals(WheelTypeDetector.Confidence.HIGH, detected.confidence)
    }

    // ==================== Gotway Detection ====================

    @Test
    fun `detect Gotway from device name`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, "GW-Monster")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.GOTWAY, detected.wheelType)
        assertEquals(WheelTypeDetector.Confidence.HIGH, detected.confidence)
    }

    @Test
    fun `detect Begode from device name`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, "BEGODE-HERO")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.GOTWAY, detected.wheelType)
    }

    // ==================== Veteran Detection ====================

    @Test
    fun `detect Veteran from device name Sherman`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, "SHERMAN-MAX")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.VETERAN, detected.wheelType)
        assertEquals(WheelTypeDetector.Confidence.HIGH, detected.confidence)
    }

    @Test
    fun `detect Veteran from device name Lynx`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, "Lynx")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.VETERAN, detected.wheelType)
    }

    // ==================== Ninebot Detection ====================

    @Test
    fun `detect Ninebot from device name`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, "NINEBOT-S2")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.NINEBOT, detected.wheelType)
    }

    // ==================== Name Priority Over Service Heuristics ====================

    @Test
    fun `Gotway with fff0 service detected by name not as KingSong`() {
        // Real Gotway wheel profile: has fff0 service (shared with KingSong)
        // but device name says "GotWay_008977" — name should take priority
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000180a-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("00002a23-0000-1000-8000-00805f9b34fb")
                ),
                DiscoveredService(
                    uuid = "0000fff0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000fff1-0000-1000-8000-00805f9b34fb")
                ),
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                ),
                DiscoveredService(
                    uuid = "1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0",
                    characteristics = listOf("f7bf3564-fb6d-4e53-88a4-5e37e0326063")
                )
            )
        )

        val result = detector.detect(services, "GotWay_008977")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.GOTWAY, detected.wheelType)
        assertEquals(BleUuids.Gotway.SERVICE, detected.readServiceUuid)
    }

    @Test
    fun `KingSong with fff0 service detected by name`() {
        // Real KingSong wheel profile: has fff0 service and KS- name
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000fff0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf(
                        "0000fff1-0000-1000-8000-00805f9b34fb",
                        "0000fff2-0000-1000-8000-00805f9b34fb",
                        "0000fff3-0000-1000-8000-00805f9b34fb"
                    )
                ),
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, "KS-14S")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.KINGSONG, detected.wheelType)
    }

    @Test
    fun `name detection takes priority over service detection`() {
        // Nordic UART service would normally trigger Ninebot Z detection,
        // but a Veteran name should take priority
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
                    characteristics = listOf(
                        "6e400002-b5a3-f393-e0a9-e50e24dcca9e",
                        "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
                    )
                )
            )
        )

        val result = detector.detect(services, "Sherman-S")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.VETERAN, detected.wheelType)
    }

    @Test
    fun `Gotway detected with various name patterns`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val gotwayNames = listOf("GotWay_123", "GW-Test", "BEGODE-Hero", "Nikola+", "Monster Pro", "MSP-HT")
        for (name in gotwayNames) {
            val result = detector.detect(services, name)
            assertTrue(
                result is WheelTypeDetector.DetectionResult.Detected &&
                    result.wheelType == WheelType.GOTWAY,
                "Expected GOTWAY for name '$name' but got $result"
            )
        }
    }

    // ==================== Ambiguous Detection ====================

    @Test
    fun `return ambiguous for standard services without name`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, null)

        assertTrue(result is WheelTypeDetector.DetectionResult.Ambiguous)
        val ambiguous = result as WheelTypeDetector.DetectionResult.Ambiguous
        assertTrue(WheelType.GOTWAY in ambiguous.possibleTypes)
        assertTrue(WheelType.KINGSONG in ambiguous.possibleTypes)
        assertTrue(WheelType.NINEBOT in ambiguous.possibleTypes)
    }

    @Test
    fun `return ambiguous for unknown device name`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, "UNKNOWN-WHEEL")

        assertTrue(result is WheelTypeDetector.DetectionResult.Ambiguous)
    }

    // ==================== Unknown Detection ====================

    @Test
    fun `return unknown for no recognized services`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "00001800-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("00002a00-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services)

        assertTrue(result is WheelTypeDetector.DetectionResult.Unknown)
    }

    @Test
    fun `return unknown for empty services`() {
        val services = DiscoveredServices(services = emptyList())

        val result = detector.detect(services)

        assertTrue(result is WheelTypeDetector.DetectionResult.Unknown)
    }

    // ==================== getUuidsForType ====================

    @Test
    fun `getUuidsForType returns correct info for KingSong`() {
        val info = detector.getUuidsForType(WheelType.KINGSONG)

        assertNotNull(info)
        assertEquals(WheelType.KINGSONG, info.wheelType)
        assertEquals(BleUuids.Kingsong.SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.Kingsong.READ_CHARACTERISTIC, info.readCharacteristicUuid)
    }

    @Test
    fun `getUuidsForType returns correct info for InMotion V1`() {
        val info = detector.getUuidsForType(WheelType.INMOTION)

        assertNotNull(info)
        assertEquals(WheelType.INMOTION, info.wheelType)
        assertEquals(BleUuids.InMotion.READ_SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.InMotion.READ_CHARACTERISTIC, info.readCharacteristicUuid)
        assertEquals(BleUuids.InMotion.WRITE_SERVICE, info.writeServiceUuid)
        assertEquals(BleUuids.InMotion.WRITE_CHARACTERISTIC, info.writeCharacteristicUuid)
    }

    @Test
    fun `getUuidsForType returns correct info for InMotion V2`() {
        val info = detector.getUuidsForType(WheelType.INMOTION_V2)

        assertNotNull(info)
        assertEquals(WheelType.INMOTION_V2, info.wheelType)
        assertEquals(BleUuids.InMotionV2.SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.InMotionV2.READ_CHARACTERISTIC, info.readCharacteristicUuid)
    }

    @Test
    fun `getUuidsForType returns correct info for Ninebot Z`() {
        val info = detector.getUuidsForType(WheelType.NINEBOT_Z)

        assertNotNull(info)
        assertEquals(WheelType.NINEBOT_Z, info.wheelType)
        assertEquals(BleUuids.NinebotZ.SERVICE, info.readServiceUuid)
    }

    @Test
    fun `getUuidsForType returns correct info for Veteran`() {
        val info = detector.getUuidsForType(WheelType.VETERAN)

        assertNotNull(info)
        assertEquals(WheelType.VETERAN, info.wheelType)
        // Veteran uses Gotway UUIDs
        assertEquals(BleUuids.Gotway.SERVICE, info.readServiceUuid)
    }

    @Test
    fun `getUuidsForType returns null for Unknown`() {
        val info = detector.getUuidsForType(WheelType.Unknown)
        assertNull(info)
    }

    // ==================== Case Sensitivity ====================

    @Test
    fun `detection is case-insensitive for UUIDs`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E", // uppercase
                    characteristics = listOf(
                        "6E400002-B5A3-F393-E0A9-E50E24DCCA9E",
                        "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
                    )
                )
            )
        )

        val result = detector.detect(services)

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.NINEBOT_Z, detected.wheelType)
    }

    // ==================== InMotion V2 Name Detection ====================

    @Test
    fun `detect InMotion V2 from V11 device name`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, "V11-ABC123")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.INMOTION_V2, detected.wheelType)
        assertEquals(BleUuids.InMotionV2.SERVICE, detected.readServiceUuid)
        assertEquals(WheelTypeDetector.Confidence.HIGH, detected.confidence)
    }

    @Test
    fun `detect InMotion V2 from various model names`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val inmotionNames = listOf("V11Y-001", "V12HS", "V13-PRO", "V14-50S", "V9-MyWheel", "P6-Test", "InMotion-V99")
        for (name in inmotionNames) {
            val result = detector.detect(services, name)
            assertTrue(
                result is WheelTypeDetector.DetectionResult.Detected &&
                    result.wheelType == WheelType.INMOTION_V2,
                "Expected INMOTION_V2 for name '$name' but got $result"
            )
        }
    }

    @Test
    fun `detect InMotion V2 from INMOTION keyword in name`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, "INMOTION-P6")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.INMOTION_V2, detected.wheelType)
    }

    @Test
    fun `device name matching is case-insensitive`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, "ks-s18") // lowercase

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected)
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.KINGSONG, detected.wheelType)
    }
}
