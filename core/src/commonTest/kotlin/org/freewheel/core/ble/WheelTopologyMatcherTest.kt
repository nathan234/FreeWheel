package org.freewheel.core.ble

import org.freewheel.core.domain.identity.WheelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for [WheelTopologyMatcher] and the typed fingerprint data in
 * [WheelTopologies]. The fingerprints port `bluetooth_services.json` (12
 * entries) and `bluetooth_proxy_services.json` (8 entries) from legacy
 * WheelLog into commonMain.
 *
 * The matcher compares the wheel's [DiscoveredServices] against each
 * fingerprint after UUID canonicalization, treating services and
 * characteristics as order-insensitive sets.
 */
class WheelTopologyMatcherTest {

    private val expectedAdapterMapping = mapOf(
        "gotway" to WheelType.GOTWAY,
        "kingsong" to WheelType.KINGSONG,
        "inmotion" to WheelType.INMOTION,
        "inmotion_v2" to WheelType.INMOTION_V2,
        "ninebot" to WheelType.NINEBOT,
        "ninebot_z" to WheelType.NINEBOT_Z,
    )

    // ----- Topology data integrity -----

    @Test
    fun `ALL contains 12 ported fingerprints`() {
        assertEquals(12, WheelTopologies.ALL.size)
    }

    @Test
    fun `PROXY contains 8 ported fingerprints`() {
        assertEquals(8, WheelTopologies.PROXY.size)
    }

    @Test
    fun `every adapter string in ALL maps to the expected WheelType`() {
        for ((index, topology) in WheelTopologies.ALL.withIndex()) {
            val expectedType = expectedAdapterMapping[topology.adapter]
                ?: fail("ALL[$index]: unknown adapter '${topology.adapter}'")
            assertEquals(
                expectedType,
                topology.wheelType,
                "ALL[$index] adapter='${topology.adapter}' wheelType mismatch"
            )
        }
    }

    @Test
    fun `every adapter string in PROXY maps to the expected WheelType`() {
        for ((index, topology) in WheelTopologies.PROXY.withIndex()) {
            val expectedType = expectedAdapterMapping[topology.adapter]
                ?: fail("PROXY[$index]: unknown adapter '${topology.adapter}'")
            assertEquals(
                expectedType,
                topology.wheelType,
                "PROXY[$index] adapter='${topology.adapter}' wheelType mismatch"
            )
        }
    }

    @Test
    fun `every UUID in ALL and PROXY is stored in canonical lowercase long form`() {
        for ((listName, list) in listOf("ALL" to WheelTopologies.ALL, "PROXY" to WheelTopologies.PROXY)) {
            for ((index, topology) in list.withIndex()) {
                for (svc in topology.services) {
                    assertEquals(
                        BleUuids.canonicalize(svc.uuid),
                        svc.uuid,
                        "$listName[$index] adapter='${topology.adapter}' service UUID '${svc.uuid}' is not canonical"
                    )
                    for (char in svc.characteristics) {
                        assertEquals(
                            BleUuids.canonicalize(char),
                            char,
                            "$listName[$index] adapter='${topology.adapter}' char UUID '$char' (in service '${svc.uuid}') is not canonical"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `ALL contains each of the six expected adapters at least once`() {
        val adaptersInList = WheelTopologies.ALL.map { it.adapter }.toSet()
        assertEquals(expectedAdapterMapping.keys, adaptersInList)
    }

    @Test
    fun `PROXY contains each of the six expected adapters at least once`() {
        val adaptersInList = WheelTopologies.PROXY.map { it.adapter }.toSet()
        assertEquals(expectedAdapterMapping.keys, adaptersInList)
    }

    // ----- Self-match: one positive test per ported fingerprint (loop) -----

    @Test
    fun `every ALL topology matches a DiscoveredServices built from its own data`() {
        val matcher = WheelTopologyMatcher(WheelTopologies.ALL)
        for ((index, topology) in WheelTopologies.ALL.withIndex()) {
            val services = topology.toDiscoveredServices()
            val matches = matcher.match(services)
            assertTrue(
                matches.any { it.topology === topology },
                "ALL[$index] adapter='${topology.adapter}' did not self-match. " +
                        "Services=${services.serviceUuids()}, matches=${matches.map { it.topology.adapter }}"
            )
        }
    }

    @Test
    fun `every PROXY topology matches a DiscoveredServices built from its own data`() {
        val matcher = WheelTopologyMatcher(WheelTopologies.PROXY)
        for ((index, topology) in WheelTopologies.PROXY.withIndex()) {
            val services = topology.toDiscoveredServices()
            val matches = matcher.match(services)
            assertTrue(
                matches.any { it.topology === topology },
                "PROXY[$index] adapter='${topology.adapter}' did not self-match. " +
                        "Services=${services.serviceUuids()}, matches=${matches.map { it.topology.adapter }}"
            )
        }
    }

    // ----- Canonicalization -----

    @Test
    fun `mixed-case input matches lowercase fingerprint`() {
        val matcher = WheelTopologyMatcher(WheelTopologies.ALL)
        val target = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val services = DiscoveredServices(
            target.toDiscoveredServices().services.map { svc ->
                DiscoveredService(
                    uuid = svc.uuid.uppercase(),
                    characteristics = svc.characteristics.map { it.uppercase() }
                )
            }
        )
        val matches = matcher.match(services)
        assertTrue(
            matches.any { it.topology === target },
            "Uppercase-input services failed to match lowercase fingerprint"
        )
    }

    @Test
    fun `4-char short-form UUIDs match long-form fingerprint`() {
        // Build the Ninebot fingerprint's services using only 4-char short
        // forms — equivalent to what CoreBluetooth returns for 16-bit UUIDs.
        val matcher = WheelTopologyMatcher(WheelTopologies.ALL)
        val target = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "1800",
                    characteristics = listOf("2A00", "2A01", "2A02", "2A03", "2A04")
                ),
                DiscoveredService(
                    uuid = "1801",
                    characteristics = listOf("2A05")
                ),
                DiscoveredService(
                    uuid = "FFE0",
                    characteristics = listOf("FFE1")
                ),
            )
        )
        val matches = matcher.match(services)
        assertTrue(
            matches.any { it.topology === target },
            "4-char short-form services failed to match long-form fingerprint. matches=${matches.map { it.topology.adapter }}"
        )
    }

    @Test
    fun `8-char short-form UUIDs match long-form fingerprint`() {
        val matcher = WheelTopologyMatcher(WheelTopologies.ALL)
        val target = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val services = DiscoveredServices(
            services = listOf(
                DiscoveredService(
                    uuid = "00001800",
                    characteristics = listOf("00002A00", "00002A01", "00002A02", "00002A03", "00002A04")
                ),
                DiscoveredService(
                    uuid = "00001801",
                    characteristics = listOf("00002A05")
                ),
                DiscoveredService(
                    uuid = "0000FFE0",
                    characteristics = listOf("0000FFE1")
                ),
            )
        )
        val matches = matcher.match(services)
        assertTrue(
            matches.any { it.topology === target },
            "8-char short-form services failed to match long-form fingerprint"
        )
    }

    @Test
    fun `services discovered in different order still match`() {
        val matcher = WheelTopologyMatcher(WheelTopologies.ALL)
        val target = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val services = DiscoveredServices(
            target.toDiscoveredServices().services.reversed()
        )
        val matches = matcher.match(services)
        assertTrue(
            matches.any { it.topology === target },
            "Reversed service order failed to match"
        )
    }

    @Test
    fun `characteristics in different order still match`() {
        val matcher = WheelTopologyMatcher(WheelTopologies.ALL)
        val target = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val services = DiscoveredServices(
            target.toDiscoveredServices().services.map { svc ->
                DiscoveredService(svc.uuid, svc.characteristics.reversed())
            }
        )
        val matches = matcher.match(services)
        assertTrue(
            matches.any { it.topology === target },
            "Reversed characteristic order failed to match"
        )
    }

    // ----- Negative cases -----

    @Test
    fun `does not match when one characteristic is missing`() {
        val matcher = WheelTopologyMatcher(WheelTopologies.ALL)
        val target = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val mutilated = target.toDiscoveredServices().withFirstNonEmptyServiceCharsAdjusted { it.drop(1) }

        val matches = matcher.match(mutilated)
        assertFalse(
            matches.any { it.topology === target },
            "Should not match when one characteristic was removed"
        )
    }

    @Test
    fun `does not match when an extra characteristic is added`() {
        val matcher = WheelTopologyMatcher(WheelTopologies.ALL)
        val target = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val mutilated = target.toDiscoveredServices().withFirstNonEmptyServiceCharsAdjusted {
            it + "0000beef-0000-1000-8000-00805f9b34fb"
        }

        val matches = matcher.match(mutilated)
        assertFalse(
            matches.any { it.topology === target },
            "Should not match when an extra characteristic was added"
        )
    }

    @Test
    fun `does not match when one service is missing`() {
        val matcher = WheelTopologyMatcher(WheelTopologies.ALL)
        val target = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val firstSvc = target.services.first().uuid
        val mutilated = DiscoveredServices(
            target.toDiscoveredServices().services.filter { it.uuid != firstSvc }
        )

        val matches = matcher.match(mutilated)
        assertFalse(matches.any { it.topology === target })
    }

    @Test
    fun `does not match when an extra service is added`() {
        val matcher = WheelTopologyMatcher(WheelTopologies.ALL)
        val target = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val mutilated = DiscoveredServices(
            target.toDiscoveredServices().services + DiscoveredService(
                uuid = "0000feed-0000-1000-8000-00805f9b34fb",
                characteristics = listOf("0000beef-0000-1000-8000-00805f9b34fb")
            )
        )

        val matches = matcher.match(mutilated)
        assertFalse(matches.any { it.topology === target })
    }

    @Test
    fun `does not match when one characteristic UUID differs`() {
        val matcher = WheelTopologyMatcher(WheelTopologies.ALL)
        val target = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val mutilated = target.toDiscoveredServices().withFirstNonEmptyServiceCharsAdjusted { chars ->
            listOf("0000beef-0000-1000-8000-00805f9b34fb") + chars.drop(1)
        }

        val matches = matcher.match(mutilated)
        assertFalse(matches.any { it.topology === target })
    }

    @Test
    fun `empty DiscoveredServices matches no topology`() {
        val matcher = WheelTopologyMatcher(WheelTopologies.ALL)
        val matches = matcher.match(DiscoveredServices(emptyList()))
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `does not match when a characteristic is duplicated on a service`() {
        // Legacy WheelLog compares per-service characteristic counts: a
        // discovered service exposing the same characteristic UUID twice
        // has list length > fingerprint set size and must mismatch.
        val matcher = WheelTopologyMatcher(WheelTopologies.ALL)
        val target = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val mutilated = target.toDiscoveredServices().withFirstNonEmptyServiceCharsAdjusted {
            it + it.first()
        }

        val matches = matcher.match(mutilated)
        assertFalse(
            matches.any { it.topology === target },
            "Duplicate characteristic on a service should mismatch (legacy count check)"
        )
    }

    @Test
    fun `does not match when the same service UUID appears twice in the discovered list`() {
        // Fingerprints never expose the same service UUID twice — the
        // legacy JSON used object keys and our typed data uses a
        // Set<ServiceTopology> keyed by UUID. A discovered list with
        // duplicate service UUIDs therefore cannot satisfy any
        // fingerprint, even when the total service count happens to align.
        val matcher = WheelTopologyMatcher(WheelTopologies.ALL)
        val target = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val targetServices = target.toDiscoveredServices().services
        require(targetServices.size >= 2) { "ninebot fingerprint must have at least 2 services" }
        // Replace the first service with a copy of the second one — the
        // resulting list still has the same total count as the
        // fingerprint, so the size check alone cannot reject it.
        val duplicated = DiscoveredServices(
            listOf(targetServices[1]) + targetServices.drop(1)
        )

        val matches = matcher.match(duplicated)
        assertFalse(
            matches.any { it.topology === target },
            "Discovered list with the same service UUID twice should not match any fingerprint"
        )
    }

    // ----- Ambiguity (multiple Matches) -----

    @Test
    fun `match returns a list so ambiguous fingerprints can both surface`() {
        // The two Gotway entries in PROXY are byte-identical in the legacy
        // JSON. They must both match a wheel exposing that exact topology;
        // the matcher MUST NOT silently dedupe — callers need visibility.
        val matcher = WheelTopologyMatcher(WheelTopologies.PROXY)
        val anyGotwayProxy = WheelTopologies.PROXY.first { it.adapter == "gotway" }
        val matches = matcher.match(anyGotwayProxy.toDiscoveredServices())

        val gotwayMatches = matches.filter { it.topology.adapter == "gotway" }
        assertTrue(
            gotwayMatches.size >= 2,
            "Expected the duplicated Gotway proxy fingerprint to surface " +
                    "as multiple matches; got ${gotwayMatches.size}: " +
                    matches.map { it.topology.adapter }
        )
    }

    @Test
    fun `default constructor uses ALL list`() {
        val defaultMatcher = WheelTopologyMatcher()
        val target = WheelTopologies.ALL.first { it.adapter == "ninebot" }
        val matches = defaultMatcher.match(target.toDiscoveredServices())
        assertTrue(
            matches.any { it.topology === target },
            "Default-constructed matcher should default to WheelTopologies.ALL"
        )
    }
}

// ----- helpers -----

private fun WheelTopology.toDiscoveredServices(): DiscoveredServices =
    DiscoveredServices(
        services = services.map { svc ->
            DiscoveredService(uuid = svc.uuid, characteristics = svc.characteristics.toList())
        }
    )

private fun DiscoveredServices.withFirstNonEmptyServiceCharsAdjusted(
    transform: (List<String>) -> List<String>
): DiscoveredServices {
    val target = services.firstOrNull { it.characteristics.isNotEmpty() }
        ?: error("No non-empty service available for mutation in $this")
    var done = false
    return DiscoveredServices(
        services = services.map { svc ->
            if (!done && svc.uuid == target.uuid) {
                done = true
                DiscoveredService(svc.uuid, transform(svc.characteristics))
            } else svc
        }
    )
}
