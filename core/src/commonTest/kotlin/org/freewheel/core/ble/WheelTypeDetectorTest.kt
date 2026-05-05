package org.freewheel.core.ble

import org.freewheel.core.domain.WheelType
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

    // ==================== WheelConnectionInfo.forType ====================

    @Test
    fun `forType returns correct info for KingSong`() {
        val info = WheelConnectionInfo.forType(WheelType.KINGSONG)

        assertNotNull(info)
        assertEquals(WheelType.KINGSONG, info.wheelType)
        assertEquals(BleUuids.Kingsong.SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.Kingsong.READ_CHARACTERISTIC, info.readCharacteristicUuid)
    }

    @Test
    fun `forType returns correct info for InMotion V1`() {
        val info = WheelConnectionInfo.forType(WheelType.INMOTION)

        assertNotNull(info)
        assertEquals(WheelType.INMOTION, info.wheelType)
        assertEquals(BleUuids.InMotion.READ_SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.InMotion.READ_CHARACTERISTIC, info.readCharacteristicUuid)
        assertEquals(BleUuids.InMotion.WRITE_SERVICE, info.writeServiceUuid)
        assertEquals(BleUuids.InMotion.WRITE_CHARACTERISTIC, info.writeCharacteristicUuid)
    }

    @Test
    fun `forType returns correct info for InMotion V2`() {
        val info = WheelConnectionInfo.forType(WheelType.INMOTION_V2)

        assertNotNull(info)
        assertEquals(WheelType.INMOTION_V2, info.wheelType)
        assertEquals(BleUuids.InMotionV2.SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.InMotionV2.READ_CHARACTERISTIC, info.readCharacteristicUuid)
    }

    @Test
    fun `forType returns correct info for Ninebot Z`() {
        val info = WheelConnectionInfo.forType(WheelType.NINEBOT_Z)

        assertNotNull(info)
        assertEquals(WheelType.NINEBOT_Z, info.wheelType)
        assertEquals(BleUuids.NinebotZ.SERVICE, info.readServiceUuid)
    }

    @Test
    fun `forType returns correct info for Veteran`() {
        val info = WheelConnectionInfo.forType(WheelType.VETERAN)

        assertNotNull(info)
        assertEquals(WheelType.VETERAN, info.wheelType)
        // Veteran uses Gotway UUIDs
        assertEquals(BleUuids.Gotway.SERVICE, info.readServiceUuid)
    }

    @Test
    fun `forType returns null for Unknown`() {
        val info = WheelConnectionInfo.forType(WheelType.Unknown)
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

    // ==================== Real Device Name Tests ====================
    // These test against actual BLE advertisement names observed in the wild,
    // from DarknessBot device lists and user reports.

    private val ffe0Services = DiscoveredServices(
        services = listOf(
            DiscoveredService(
                uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
            )
        )
    )

    private fun assertDetectedAs(deviceName: String, expectedType: WheelType) {
        val result = detector.detect(ffe0Services, deviceName)
        assertTrue(
            result is WheelTypeDetector.DetectionResult.Detected &&
                result.wheelType == expectedType,
            "Expected $expectedType for name '$deviceName' but got $result"
        )
    }

    // --- Leaperkim/Veteran LK prefix (from official app + DarknessBot screenshot) ---
    // The official Leaperkim app v1.4.8 uses the legacy Veteran protocol (DC 5A 5C)
    // for all LK-prefixed wheels. Route to VETERAN, not LEAPERKIM (CAN).

    @Test
    fun `detect Veteran from real LK device names`() {
        // Real names from DarknessBot device list screenshot
        val lkNames = listOf("LK15724", "LK18412", "LK16350")
        for (name in lkNames) {
            assertDetectedAs(name, WheelType.VETERAN)
        }
    }

    @Test
    fun `LK prefix uses Gotway BLE UUIDs`() {
        val result = detector.detect(ffe0Services, "LK15724")
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(BleUuids.Gotway.SERVICE, detected.readServiceUuid)
        assertEquals(BleUuids.Gotway.READ_CHARACTERISTIC, detected.readCharacteristicUuid)
    }

    @Test
    fun `LEAPERKIM keyword detected as Veteran`() {
        assertDetectedAs("LEAPERKIM-V2", WheelType.VETERAN)
    }

    // --- Veteran (NF prefix for Nosfet, from user report) ---

    @Test
    fun `detect Veteran from NF prefix real device name`() {
        // Real Nosfet Apex device name from user BLE scan
        assertDetectedAs("NF2790", WheelType.VETERAN)
    }

    @Test
    fun `Veteran NF prefix uses Gotway BLE UUIDs`() {
        val result = detector.detect(ffe0Services, "NF2790")
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(BleUuids.Gotway.SERVICE, detected.readServiceUuid)
    }

    @Test
    fun `detect Veteran from various real patterns`() {
        val veteranNames = listOf(
            "VETERAN-S",     // Veteran keyword
            "SHERMAN-MAX",   // Sherman
            "Lynx",          // Lynx
            "PATTON-S",      // Patton
            "ABRAMS",        // Abrams
            "Oryx-1",        // Oryx (new)
            "NOSFET-APEX",   // Nosfet keyword (new)
            "NF2790",        // NF prefix (new, real device name)
            "NF1234"         // NF prefix variant
        )
        for (name in veteranNames) {
            assertDetectedAs(name, WheelType.VETERAN)
        }
    }

    // --- Gotway (GotWay_ prefix, from DarknessBot screenshot) ---

    @Test
    fun `detect Gotway from real device names`() {
        // Real names from DarknessBot device list screenshot
        val gotwayNames = listOf("GotWay_75007", "GotWay_59380", "GotWay_005741", "GotWay_002633", "GotWay_73335")
        for (name in gotwayNames) {
            assertDetectedAs(name, WheelType.GOTWAY)
        }
    }

    // --- InMotion V2 (P6 prefix, from DarknessBot screenshot) ---

    @Test
    fun `detect InMotion V2 from real P6 device name`() {
        // Real name from DarknessBot device list screenshot
        assertDetectedAs("P6-60032721", WheelType.INMOTION_V2)
    }

    @Test
    fun `detect InMotion V2 from new model names`() {
        val imNames = listOf("E20-123", "CLIMBER-456", "GLIDE-789")
        for (name in imNames) {
            assertDetectedAs(name, WheelType.INMOTION_V2)
        }
    }

    @Test
    fun `InMotion V2 P6 uses Nordic UART UUIDs`() {
        val result = detector.detect(ffe0Services, "P6-60032721")
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(BleUuids.InMotionV2.SERVICE, detected.readServiceUuid)
        assertEquals(BleUuids.InMotionV2.READ_CHARACTERISTIC, detected.readCharacteristicUuid)
    }

    // ==================== deriveTypeFromName ====================
    //
    // Public API used by the iOS scan-time hint path: given just an advertised
    // device name, return the wheel type — or null if the name doesn't match
    // any known pattern. Mirrors `detectFromName` but exposes only the wheel
    // type (no UUIDs), since the caller will look those up via
    // WheelConnectionInfo.forType once detection in WCM completes.

    @Test
    fun `deriveTypeFromName returns null for null name`() {
        assertNull(WheelTypeDetector.deriveTypeFromName(null))
    }

    @Test
    fun `deriveTypeFromName returns null for blank name`() {
        assertNull(WheelTypeDetector.deriveTypeFromName(""))
    }

    @Test
    fun `deriveTypeFromName returns null for unknown name`() {
        assertNull(WheelTypeDetector.deriveTypeFromName("UNKNOWN-WHEEL"))
    }

    @Test
    fun `deriveTypeFromName returns Kingsong for KS- name`() {
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("KS-S18"))
    }

    @Test
    fun `deriveTypeFromName returns Kingsong for KINGSONG keyword`() {
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("Kingsong-14D"))
    }

    @Test
    fun `deriveTypeFromName returns Gotway for various names`() {
        val gotwayNames = listOf("GotWay_75007", "GW-Test", "BEGODE-Hero", "Nikola+", "Monster-Pro")
        for (name in gotwayNames) {
            assertEquals(
                WheelType.GOTWAY,
                WheelTypeDetector.deriveTypeFromName(name),
                "Expected GOTWAY for '$name'"
            )
        }
    }

    @Test
    fun `deriveTypeFromName returns Veteran for LK NF and Sherman names`() {
        val veteranNames = listOf("LK15724", "NF2790", "Sherman-Max", "Lynx", "PATTON-S", "VETERAN-S")
        for (name in veteranNames) {
            assertEquals(
                WheelType.VETERAN,
                WheelTypeDetector.deriveTypeFromName(name),
                "Expected VETERAN for '$name'"
            )
        }
    }

    @Test
    fun `deriveTypeFromName returns InMotion V2 for V11 V12 P6 names`() {
        val imNames = listOf("V11Y-001", "V12HS", "P6-60032721", "INMOTION-P6")
        for (name in imNames) {
            assertEquals(
                WheelType.INMOTION_V2,
                WheelTypeDetector.deriveTypeFromName(name),
                "Expected INMOTION_V2 for '$name'"
            )
        }
    }

    @Test
    fun `deriveTypeFromName returns Ninebot for NINEBOT keyword`() {
        assertEquals(WheelType.NINEBOT, WheelTypeDetector.deriveTypeFromName("NINEBOT-S2"))
    }

    @Test
    fun `deriveTypeFromName is case-insensitive`() {
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("ks-s18"))
        assertEquals(WheelType.GOTWAY, WheelTypeDetector.deriveTypeFromName("gotway_123"))
        assertEquals(WheelType.VETERAN, WheelTypeDetector.deriveTypeFromName("sherman"))
    }
}
