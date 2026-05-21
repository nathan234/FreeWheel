package org.freewheel.core.ble

import org.freewheel.core.domain.identity.WheelType
import org.freewheel.core.service.WheelTransportProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `detect InMotion V1 from full topology fingerprint`() {
        // Pass 3a: the topology matcher needs the full InMotion V1 service
        // tree (12 services per legacy bluetooth_services.json), not the
        // minimal ffe0/ffe5 subset the old service-heuristic detector
        // accepted.
        val inmotion = WheelTopologies.ALL.first { it.adapter == "inmotion" }
        val services = inmotion.toDiscoveredServices()

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
    fun `detect InMotion V2 from full topology fingerprint`() {
        // Pass 3a: use the actual InMotion V2 fingerprint (Generic Access +
        // Generic Attribute + Nordic UART), not just Nordic UART alone.
        val imv2 = WheelTopologies.ALL.first { it.adapter == "inmotion_v2" }
        val services = imv2.toDiscoveredServices()

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
    fun `detect Ninebot Z from full topology fingerprint`() {
        // Pass 3a: use the actual Ninebot Z fingerprint (Generic Access +
        // empty Generic Attribute + Nordic UART), not just Nordic UART
        // plus a partial 1800 service.
        val ninebotZ = WheelTopologies.ALL.first { it.adapter == "ninebot_z" }
        val services = ninebotZ.toDiscoveredServices()

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
    fun `partial fff0 services without name returns Unknown (Pass 3a)`() {
        // Pre Pass 3a: a partial fff0+ffe0 service tree was reported as
        // Ambiguous(Gotway/Kingsong/Ninebot) and silently routed to
        // GOTWAY_VIRTUAL by WCM. Pass 3a refuses to guess: this partial
        // tree doesn't match any topology fingerprint and there's no
        // device name, so the detector returns Unknown.
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

        assertTrue(result is WheelTypeDetector.DetectionResult.Unknown, "got $result")
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
    fun `name fallback resolves wheel type when partial services don't match any fingerprint`() {
        // Pass 3a: topology takes precedence over name, but a partial
        // service tree that matches no fingerprint falls through to name
        // detection. Here the lone Nordic UART service doesn't match
        // any topology — without name fallback this would be Unknown,
        // but "Sherman-S" matches the Veteran name pattern.
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
    fun `bare ffe0 service without name returns Unknown (Pass 3a)`() {
        // Pre Pass 3a this returned Ambiguous(Gotway, Kingsong, Ninebot)
        // and WCM silently fell through to GOTWAY_VIRTUAL — the silent
        // wrong-protocol path that wedged S22 wheels. Now: no topology
        // match, no name → Unknown, surfaced to the user as Failed.
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, null)

        assertTrue(result is WheelTypeDetector.DetectionResult.Unknown, "got $result")
    }

    @Test
    fun `bare ffe0 service with unrecognized name returns Unknown (Pass 3a)`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
                    characteristics = listOf("0000ffe1-0000-1000-8000-00805f9b34fb")
                )
            )
        )

        val result = detector.detect(services, "UNKNOWN-WHEEL")

        assertTrue(result is WheelTypeDetector.DetectionResult.Unknown, "got $result")
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
        // Build the Ninebot Z fingerprint with all UUIDs uppercased; the
        // matcher canonicalizes via BleUuids.canonicalize() so this still
        // resolves to Detected(NINEBOT_Z). Pinned at the detector level
        // as an integration check on top of WheelTopologyMatcherTest's
        // direct case-insensitivity coverage.
        val ninebotZ = WheelTopologies.ALL.first { it.adapter == "ninebot_z" }
        val services = DiscoveredServices(
            services = ninebotZ.toDiscoveredServices().services.map { svc ->
                DiscoveredService(
                    uuid = svc.uuid.uppercase(),
                    characteristics = svc.characteristics.map { it.uppercase() }
                )
            }
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

    // ==================== Catalog-driven Kingsong coverage ====================
    //
    // Pass 1.5 Substep 1: every Kingsong token from WheelCatalog must derive
    // to KINGSONG. Catalog-driven so adding a new Kingsong model to the
    // catalog can never silently regress the detector.

    @Test
    fun `every Kingsong catalog token derives to KINGSONG`() {
        val tokens = org.freewheel.core.domain.identity.wheel.WheelCatalog.entries
            .filter { it.wheelType == WheelType.KINGSONG }
            .flatMap { it.nameTokens }
        assertTrue(tokens.isNotEmpty(), "WheelCatalog has no KINGSONG entries — test data invariant broken")
        for (token in tokens) {
            assertEquals(
                WheelType.KINGSONG,
                WheelTypeDetector.deriveTypeFromName(token),
                "Catalog token '$token' should derive to KINGSONG",
            )
        }
    }

    @Test
    fun `deriveTypeFromName normalizes spaces and hyphens`() {
        // S22 PRO catalog token must match all common scan-name formattings.
        for (variant in listOf("S22 PRO", "S22Pro", "S22-PRO", "s22 pro", "S22  PRO", " S22 PRO ")) {
            assertEquals(
                WheelType.KINGSONG,
                WheelTypeDetector.deriveTypeFromName(variant),
                "Variant '$variant' should normalize to KINGSONG",
            )
        }
    }

    @Test
    fun `deriveTypeFromName matches plain S-series Kingsong models without KS prefix`() {
        // Real-world S22 advertisements often appear as "S22" or "S22 PRO" with
        // no "KS-" prefix. Pass 1 wired up the hint flow assuming the detector
        // would catch these; this test pins that contract.
        for (name in listOf("S18", "S19", "S22", "S16", "S22 PRO", "S16 PRO", "F22 PRO")) {
            assertEquals(
                WheelType.KINGSONG,
                WheelTypeDetector.deriveTypeFromName(name),
                "Plain Kingsong model name '$name' should derive to KINGSONG",
            )
        }
    }

    @Test
    fun `deriveTypeFromName preserves InMotion precedence over new Kingsong patterns`() {
        // V-series InMotion names must not be hijacked by widened Kingsong matching.
        for (name in listOf("V11Y-001", "V12HS", "V13", "V14")) {
            assertEquals(
                WheelType.INMOTION_V2,
                WheelTypeDetector.deriveTypeFromName(name),
                "InMotion '$name' must not regress to KINGSONG",
            )
        }
    }

    @Test
    fun `deriveTypeFromName preserves Gotway precedence over new Kingsong patterns`() {
        // MASTER and MSP must stay Gotway even after Kingsong widening.
        assertEquals(WheelType.GOTWAY, WheelTypeDetector.deriveTypeFromName("MASTER"))
        assertEquals(WheelType.GOTWAY, WheelTypeDetector.deriveTypeFromName("MSP-1500"))
    }

    @Test
    fun `deriveTypeFromName rejects too-short ambiguous prefixes`() {
        // "S2" is shorter than any catalog Kingsong token and could be
        // anything. Don't false-positive.
        assertNull(WheelTypeDetector.deriveTypeFromName("S2"))
    }

    @Test
    fun `deriveTypeFromName does not misclassify Ninebot names that embed Kingsong tokens`() {
        // Codex regression catch (Pass 1.5 Substep 1 review): NINEBOT-S16
        // normalizes to NINEBOTS16 which contains the catalog token "S16".
        // A naive `contains()` match would route this to KINGSONG before the
        // Ninebot branch fires. Catalog-driven matching must use prefix
        // semantics so embedded model numbers don't beat later brand checks.
        assertEquals(WheelType.NINEBOT, WheelTypeDetector.deriveTypeFromName("NINEBOT-S16"))
        assertEquals(WheelType.NINEBOT, WheelTypeDetector.deriveTypeFromName("NB-F22"))
        assertEquals(WheelType.NINEBOT, WheelTypeDetector.deriveTypeFromName("NINEBOT-S22"))
    }

    @Test
    fun `deriveTypeFromName does not misclassify Gotway names that embed Kingsong tokens`() {
        // Same class of bug as the Ninebot case — a Gotway-named wheel that
        // happens to embed an S/F-series token. The Gotway branch already
        // runs before Kingsong, so the brand keywords protect these names,
        // but pin the contract anyway.
        assertEquals(WheelType.GOTWAY, WheelTypeDetector.deriveTypeFromName("BEGODE-S22"))
        assertEquals(WheelType.GOTWAY, WheelTypeDetector.deriveTypeFromName("MASTER-F22"))
    }

    @Test
    fun `deriveTypeFromName returns KINGSONG family for KSE names`() {
        // Commit 4: KSE names now resolve to [WheelType.KINGSONG] — the
        // classic-vs-KSE split lives at the [WheelConnectionInfo] layer.
        // Returning KINGSONG keeps the iOS scan-time `deriveTypeFromName ->
        // ProtocolFamily.fromWheelType` hint contract intact (replacing the
        // Commit 3 fail-closed stopgap, which surfaced null).
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("KS-E1"))
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("KS-E1-something"))
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("KS-E3"))
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("KS-E3-something"))
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("KSE"))
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("KSE-1234"))
        // Case-insensitive — `deriveTypeFromName` uppercases first.
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("ks-e1-foo"))
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("kse-foo"))
    }

    @Test
    fun `deriveTypeFromName resolves classic Kingsong S-series alongside KSE names`() {
        // Guard against KSE-branch regressions: KS-S* / KS-F* / KS-14*
        // classic-model names must keep resolving to KINGSONG family. The
        // classic-vs-KSE distinction is made at the connection-info layer
        // (see `isKingsongKseName` / `detectFromName`), not here.
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("KS-S18"))
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("KS-S22"))
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("KS-F22"))
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("KS-14S"))
        assertEquals(WheelType.KINGSONG, WheelTypeDetector.deriveTypeFromName("Kingsong-S20"))
    }

    @Test
    fun `isKingsongKseName matches KSE prefixes only`() {
        // Positive: every KSE-family prefix the connection-info-layer
        // branch in `detectFromName` reacts to.
        assertTrue(WheelTypeDetector.isKingsongKseName("KS-E1"))
        assertTrue(WheelTypeDetector.isKingsongKseName("KS-E1-foo"))
        assertTrue(WheelTypeDetector.isKingsongKseName("KS-E3"))
        assertTrue(WheelTypeDetector.isKingsongKseName("KS-E3-foo"))
        assertTrue(WheelTypeDetector.isKingsongKseName("KSE"))
        assertTrue(WheelTypeDetector.isKingsongKseName("KSE-1234"))
        assertTrue(WheelTypeDetector.isKingsongKseName("ks-e1-foo"))
        // Negative: classic Kingsong names must not be misclassified.
        assertFalse(WheelTypeDetector.isKingsongKseName("KS-S18"))
        assertFalse(WheelTypeDetector.isKingsongKseName("KS-S22"))
        assertFalse(WheelTypeDetector.isKingsongKseName("KS-F22"))
        assertFalse(WheelTypeDetector.isKingsongKseName("KS-14S"))
        assertFalse(WheelTypeDetector.isKingsongKseName("Kingsong-S20"))
        assertFalse(WheelTypeDetector.isKingsongKseName(null))
        assertFalse(WheelTypeDetector.isKingsongKseName(""))
    }

    // ==================== Pass 3a: topology-first precedence ====================
    //
    // Pass 3a inverts the legacy name-first detection: the topology
    // matcher runs first against WheelTopologies.ALL, and device-name
    // patterns are used only as a tiebreaker for ambiguous topology and
    // as a fallback for wheels not yet in the fingerprint list. The
    // silent GOTWAY_VIRTUAL fallback for "standard ffe0/ffe1 service +
    // unknown name" is gone — those cases now return Unknown.

    @Test
    fun `default-constructed detector recognizes a PROXY topology fingerprint`() {
        // Codex P1 (Pass 3a review): the production detector must consult
        // both ALL and PROXY by default, mirroring legacy WheelLog's
        // bluetooth_services.json → bluetooth_proxy_services.json
        // fallback. Otherwise PROXY wheels regress to Unknown/Failed.
        val gotwayProxy = WheelTopologies.PROXY.first { it.adapter == "gotway" }
        val services = gotwayProxy.toDiscoveredServices()

        // Default-constructed detector — no custom matcher injected.
        val result = detector.detect(services)

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected, "got $result")
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.GOTWAY, detected.wheelType)
    }

    @Test
    fun `default-constructed detector recognizes every PROXY topology variant`() {
        // Lock the legacy coverage: every PROXY topology variant — not
        // just every adapter — must resolve to its expected wheel type
        // via the default detector. Iterating with .withIndex() instead
        // of .first { adapter == ... } catches byte-identical duplicates
        // (e.g. PROXY[0] and PROXY[1], both gotway) that would otherwise
        // be untested through the production entry point.
        val expectedAdapterToType = mapOf(
            "gotway" to WheelType.GOTWAY,
            "kingsong" to WheelType.KINGSONG,
            "inmotion" to WheelType.INMOTION,
            "inmotion_v2" to WheelType.INMOTION_V2,
            "ninebot" to WheelType.NINEBOT,
            "ninebot_z" to WheelType.NINEBOT_Z,
        )
        for ((index, proxy) in WheelTopologies.PROXY.withIndex()) {
            val expected = expectedAdapterToType[proxy.adapter]
                ?: error("PROXY[$index] has unknown adapter '${proxy.adapter}'")
            val result = detector.detect(proxy.toDiscoveredServices())
            assertTrue(
                result is WheelTypeDetector.DetectionResult.Detected,
                "PROXY[$index] adapter='${proxy.adapter}' did not resolve via default detector; got $result"
            )
            assertEquals(
                expected,
                (result as WheelTypeDetector.DetectionResult.Detected).wheelType,
                "PROXY[$index] adapter='${proxy.adapter}' resolved to wrong wheel type"
            )
        }
    }

    @Test
    fun `topology fingerprint match returns Detected with the fingerprint's wheel type`() {
        // Synthesize the actual Ninebot fingerprint topology and verify
        // it lands as Detected(NINEBOT) without needing a device name.
        val ninebot = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val services = ninebot.toDiscoveredServices()

        val result = detector.detect(services)

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected, "got $result")
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.NINEBOT, detected.wheelType)
        assertEquals(BleUuids.Ninebot.SERVICE, detected.readServiceUuid)
        assertEquals(BleUuids.Ninebot.READ_CHARACTERISTIC, detected.readCharacteristicUuid)
    }

    @Test
    fun `topology match wins over a conflicting device name`() {
        // Topology-first contract: even if the name suggests something
        // else, the fingerprint takes priority.
        val ninebot = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val services = ninebot.toDiscoveredServices()

        val result = detector.detect(services, "GOTWAY-MONSTER")

        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(
            WheelType.NINEBOT,
            detected.wheelType,
            "Topology fingerprint must win over device-name pattern"
        )
    }

    @Test
    fun `kingsong fingerprint match returns Detected without needing a name`() {
        val kingsong = WheelTopologies.ALL.first { it.adapter == "kingsong" }
        val services = kingsong.toDiscoveredServices()

        val result = detector.detect(services)

        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.KINGSONG, detected.wheelType)
    }

    @Test
    fun `inmotion v2 fingerprint match returns Detected without needing a name`() {
        val imv2 = WheelTopologies.ALL.first { it.adapter == "inmotion_v2" }
        val services = imv2.toDiscoveredServices()

        val result = detector.detect(services)

        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.INMOTION_V2, detected.wheelType)
    }

    @Test
    fun `multiple matches that resolve to one wheel type return Detected (not Ambiguous)`() {
        // PROXY[0] and PROXY[1] are byte-identical Gotway fingerprints —
        // both will match a wheel exposing that exact topology, but they
        // resolve to the same wheel type so there is no actual ambiguity.
        val matcher = WheelTopologyMatcher(WheelTopologies.PROXY)
        val proxyDetector = WheelTypeDetector(matcher)
        val gotwayProxy = WheelTopologies.PROXY.first { it.adapter == "gotway" }

        val result = proxyDetector.detect(gotwayProxy.toDiscoveredServices())

        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.GOTWAY, detected.wheelType)
    }

    @Test
    fun `multiple matches with different wheel types use device name to disambiguate`() {
        // Synthetic case: two fingerprints sharing a topology that
        // resolve to different wheel types. Pinned via a custom matcher
        // because no real ALL/PROXY pair currently exhibits this.
        val sharedServices = setOf(
            ServiceTopology(BleUuids.Gotway.SERVICE, setOf(BleUuids.Gotway.READ_CHARACTERISTIC))
        )
        val matcher = WheelTopologyMatcher(listOf(
            WheelTopology("ninebot", WheelType.NINEBOT, sharedServices),
            WheelTopology("kingsong", WheelType.KINGSONG, sharedServices),
        ))
        val syntheticDetector = WheelTypeDetector(matcher)
        val services = DiscoveredServices(listOf(
            DiscoveredService(BleUuids.Gotway.SERVICE, listOf(BleUuids.Gotway.READ_CHARACTERISTIC))
        ))

        val result = syntheticDetector.detect(services, "KS-S18")

        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.KINGSONG, detected.wheelType)
    }

    @Test
    fun `multiple matches with no usable name return Ambiguous with the candidate wheel types`() {
        val sharedServices = setOf(
            ServiceTopology(BleUuids.Gotway.SERVICE, setOf(BleUuids.Gotway.READ_CHARACTERISTIC))
        )
        val matcher = WheelTopologyMatcher(listOf(
            WheelTopology("ninebot", WheelType.NINEBOT, sharedServices),
            WheelTopology("kingsong", WheelType.KINGSONG, sharedServices),
        ))
        val syntheticDetector = WheelTypeDetector(matcher)
        val services = DiscoveredServices(listOf(
            DiscoveredService(BleUuids.Gotway.SERVICE, listOf(BleUuids.Gotway.READ_CHARACTERISTIC))
        ))

        val result = syntheticDetector.detect(services, null)

        val ambiguous = result as WheelTypeDetector.DetectionResult.Ambiguous
        assertTrue(WheelType.KINGSONG in ambiguous.possibleTypes)
        assertTrue(WheelType.NINEBOT in ambiguous.possibleTypes)
    }

    @Test
    fun `multiple matches with name not in candidate set return Ambiguous`() {
        val sharedServices = setOf(
            ServiceTopology(BleUuids.Gotway.SERVICE, setOf(BleUuids.Gotway.READ_CHARACTERISTIC))
        )
        val matcher = WheelTopologyMatcher(listOf(
            WheelTopology("ninebot", WheelType.NINEBOT, sharedServices),
            WheelTopology("kingsong", WheelType.KINGSONG, sharedServices),
        ))
        val syntheticDetector = WheelTypeDetector(matcher)
        val services = DiscoveredServices(listOf(
            DiscoveredService(BleUuids.Gotway.SERVICE, listOf(BleUuids.Gotway.READ_CHARACTERISTIC))
        ))

        // Name → INMOTION_V2 is not in the candidate set {NINEBOT, KINGSONG}.
        val result = syntheticDetector.detect(services, "V11Y-001")

        assertTrue(
            result is WheelTypeDetector.DetectionResult.Ambiguous,
            "Name not in candidate set must not silently widen the choice; got $result"
        )
    }

    @Test
    fun `no topology match falls through to name detection`() {
        // Minimal services that don't match any fingerprint, but the
        // device name resolves cleanly → Detected via name fallback.
        val services = DiscoveredServices(listOf(
            DiscoveredService(BleUuids.Gotway.SERVICE, listOf(BleUuids.Gotway.READ_CHARACTERISTIC))
        ))

        val result = detector.detect(services, "KS-S18")

        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelType.KINGSONG, detected.wheelType)
    }

    @Test
    fun `no topology match and no device name returns Unknown (no silent default)`() {
        // The exact case that the legacy WheelTypeDetector silently
        // routed to GOTWAY_VIRTUAL via the Ambiguous branch — the
        // root cause of the S22 stuck-on-Discovering-Services bug.
        val services = DiscoveredServices(listOf(
            DiscoveredService(BleUuids.Gotway.SERVICE, listOf(BleUuids.Gotway.READ_CHARACTERISTIC))
        ))

        val result = detector.detect(services, null)

        assertTrue(
            result is WheelTypeDetector.DetectionResult.Unknown,
            "No topology match + no name must NOT silently default to anything; got $result"
        )
    }

    @Test
    fun `no topology match and unrecognized name returns Unknown`() {
        val services = DiscoveredServices(listOf(
            DiscoveredService(BleUuids.Gotway.SERVICE, listOf(BleUuids.Gotway.READ_CHARACTERISTIC))
        ))

        val result = detector.detect(services, "TOTALLY-RANDOM-DEVICE")

        assertTrue(
            result is WheelTypeDetector.DetectionResult.Unknown,
            "No topology match + unrecognized name should still return Unknown; got $result"
        )
    }

    // ==================== Commit 4: Kingsong KSE detection ====================
    //
    // KSE wheels share `WheelType.KINGSONG` with classic Kingsong but live
    // on the AD00 transport surface (AD01 write, AD02 notify/read) and
    // carry `WheelTransportProfile.KingsongKse`. Detection must resolve the
    // KSE connection info — UUIDs *and* profile — so WCM can hand it to
    // the platform layer without re-deriving from `forType(KINGSONG)`
    // (which would collapse KSE back onto classic).

    @Test
    fun `detect KSE via AD00 service fast path returns KSE connection info`() {
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = BleUuids.KingsongKse.SERVICE,
                    characteristics = listOf(
                        BleUuids.KingsongKse.WRITE_CHARACTERISTIC,
                        BleUuids.KingsongKse.READ_CHARACTERISTIC,
                    )
                )
            )
        )

        // Deliberately no deviceName — the service fast path must fire
        // before name detection so unfingerprinted KSE wheels still resolve.
        val result = detector.detect(services, deviceName = null)

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected, "got $result")
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelConnectionInfo.forKingsongKse(), detected.connectionInfo)
        assertEquals(WheelType.KINGSONG, detected.connectionInfo.wheelType)
        assertEquals(BleUuids.KingsongKse.SERVICE, detected.connectionInfo.readServiceUuid)
        assertEquals(BleUuids.KingsongKse.READ_CHARACTERISTIC, detected.connectionInfo.readCharacteristicUuid)
        assertEquals(BleUuids.KingsongKse.WRITE_CHARACTERISTIC, detected.connectionInfo.writeCharacteristicUuid)
        assertEquals(WheelTransportProfile.KingsongKse, detected.connectionInfo.transportProfile)
        assertEquals(WheelTypeDetector.Confidence.HIGH, detected.confidence)
    }

    @Test
    fun `detect KSE via AD00 service even when device name is missing characteristics`() {
        // The fast path checks the service tree itself; partial topologies
        // (just AD00 without both characteristics) must NOT trigger it.
        val partial = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = BleUuids.KingsongKse.SERVICE,
                    characteristics = listOf(BleUuids.KingsongKse.WRITE_CHARACTERISTIC),
                )
            )
        )

        val result = detector.detect(partial, deviceName = null)

        assertTrue(
            result is WheelTypeDetector.DetectionResult.Unknown,
            "Partial KSE topology (missing AD02) must NOT be auto-resolved; got $result",
        )
    }

    @Test
    fun `detect KSE via name when AD00 service is absent`() {
        // Real-world fallback: a wheel that names itself "KSE-…" but whose
        // services haven't been observed yet (or got dropped in advertising)
        // should still route to the KSE connection info via the
        // name-detection branch.
        val services = DiscoveredServices(services = emptyList())

        val result = detector.detect(services, deviceName = "KS-E1-9876")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected, "got $result")
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelConnectionInfo.forKingsongKse(), detected.connectionInfo)
        assertEquals(WheelTransportProfile.KingsongKse, detected.connectionInfo.transportProfile)
    }

    @Test
    fun `detect KSE via name for KSE prefix`() {
        val services = DiscoveredServices(services = emptyList())

        val result = detector.detect(services, deviceName = "KSE-1234")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected, "got $result")
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelTransportProfile.KingsongKse, detected.connectionInfo.transportProfile)
    }

    @Test
    fun `classic Kingsong names still resolve to classic connection info`() {
        // Regression guard: the KSE name branch must run BEFORE the generic
        // Kingsong path, but classic-model names must still flow through the
        // generic branch and land on `forKingsong()` (classic FFE0 +
        // KingsongClassic profile).
        val services = DiscoveredServices(services = emptyList())

        val result = detector.detect(services, deviceName = "KS-S18-1234")

        assertTrue(result is WheelTypeDetector.DetectionResult.Detected, "got $result")
        val detected = result as WheelTypeDetector.DetectionResult.Detected
        assertEquals(WheelConnectionInfo.forKingsong(), detected.connectionInfo)
        assertEquals(WheelTransportProfile.KingsongClassic, detected.connectionInfo.transportProfile)
        assertEquals(BleUuids.Kingsong.SERVICE, detected.connectionInfo.readServiceUuid)
    }

    @Test
    fun `forKingsongKse factory carries KSE UUIDs and KingsongKse profile`() {
        val info = WheelConnectionInfo.forKingsongKse()
        assertEquals(WheelType.KINGSONG, info.wheelType)
        assertEquals(BleUuids.KingsongKse.SERVICE, info.readServiceUuid)
        assertEquals(BleUuids.KingsongKse.SERVICE, info.writeServiceUuid)
        assertEquals(BleUuids.KingsongKse.READ_CHARACTERISTIC, info.readCharacteristicUuid)
        assertEquals(BleUuids.KingsongKse.WRITE_CHARACTERISTIC, info.writeCharacteristicUuid)
        assertEquals(BleUuids.KingsongKse.DESCRIPTOR, info.descriptorUuid)
        assertEquals(WheelTransportProfile.KingsongKse, info.transportProfile)
    }

    @Test
    fun `forType KINGSONG keeps returning classic Kingsong`() {
        // Commit 4 intentionally leaves the `forType` shortcut on classic
        // Kingsong because saved-profile hints can't distinguish classic
        // vs KSE today (`ProtocolFamily.KINGSONG.toWheelType()` →
        // `WheelType.KINGSONG`). The variant split is enforced only at
        // live detection time. Pinning this so a Commit 5 widening is
        // a conscious decision rather than an accidental drift.
        val info = WheelConnectionInfo.forType(WheelType.KINGSONG)
        assertNotNull(info)
        assertEquals(WheelConnectionInfo.forKingsong(), info)
        assertEquals(WheelTransportProfile.KingsongClassic, info.transportProfile)
    }

    @Test
    fun `KingsongKse profile values are conservative for Commit 4`() {
        // Pinned: until real-hardware captures justify otherwise, KSE runs a
        // vanilla WITHOUT_RESPONSE transport — no MTU bump, no spacing, no
        // retries, no warmup, no heartbeat. A later commit can promote
        // values once captures land.
        val profile = WheelTransportProfile.KingsongKse
        assertEquals(org.freewheel.core.service.BleWriteType.WITHOUT_RESPONSE, profile.writeType)
        assertFalse(profile.requestMaxMtu)
        assertEquals(0L, profile.interWriteSpacingMs)
        assertEquals(0, profile.retryPolicy.maxRetries)
        assertEquals(0L, profile.retryPolicy.retryBackoffMs)
        assertEquals(org.freewheel.core.service.TransportKeepAlivePolicy.None, profile.keepAlivePolicy)
        assertTrue(profile.postConnectWarmups.isEmpty())
    }
}

// ----- helpers -----

/**
 * Convert a typed [WheelTopology] back into the [DiscoveredServices] shape
 * a real BLE peripheral would emit. Handy for "self-match" tests that
 * assert a fingerprint resolves to the expected wheel type.
 */
private fun WheelTopology.toDiscoveredServices(): DiscoveredServices =
    DiscoveredServices(
        services = services.map { svc ->
            DiscoveredService(uuid = svc.uuid, characteristics = svc.characteristics.toList())
        }
    )
