package org.freewheel.core.ble

import org.freewheel.core.domain.identity.WheelType

/**
 * BLE service topology for a single GATT service: a service UUID plus the
 * set of characteristic UUIDs it exposes. Set semantics are intentional —
 * matching is order-insensitive and de-duplicating after canonicalization.
 */
data class ServiceTopology(
    val uuid: String,
    val characteristics: Set<String>,
)

/**
 * Service-topology fingerprint for a manufacturer's BLE GATT layout. Matches
 * a discovered peripheral when the entire service+characteristic set is
 * equal after canonicalization (see [WheelTopologyMatcher]).
 *
 * Ported from legacy WheelLog `app/src/main/res/raw/bluetooth_services.json`
 * and `bluetooth_proxy_services.json`. The legacy JSON used the loose
 * adapter strings `"gotway"`, `"kingsong"`, `"inmotion"`, `"inmotion_v2"`,
 * `"ninebot"`, `"ninebot_z"`; we preserve that string for traceability while
 * surfacing the typed [WheelType] for the rest of the codebase.
 */
data class WheelTopology(
    val adapter: String,
    val wheelType: WheelType,
    val services: Set<ServiceTopology>,
)

/**
 * Service-topology fingerprints ported from legacy WheelLog.
 *
 * Pass 2 of the S22 fingerprinting plan introduces this typed dataset. No
 * production code consumes it yet — Pass 3a wires
 * [WheelTopologyMatcher] into `WheelTypeDetector.detect`.
 *
 * All UUIDs are stored in canonical lowercase 128-bit form. The matcher
 * canonicalizes incoming UUIDs at compare time so short forms (e.g. `FFE0`
 * from CoreBluetooth) and uppercase forms still match.
 */
object WheelTopologies {

    private const val SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /** Standard 16-bit Bluetooth SIG UUID. `s("FFE0")` → `0000ffe0-...-34fb`. */
    private fun s(short: String): String = "0000${short.lowercase()}$SUFFIX"

    /** Pre-canonicalized 128-bit UUID literal (already lowercase). */
    private fun u(uuid: String): String = uuid

    /** Convenience for the trio of read-only standard chars on Generic Access. */
    private fun service(uuid: String, vararg chars: String): ServiceTopology =
        ServiceTopology(uuid, chars.toSet())

    private val GENERIC_ACCESS = s("1800")
    private val GENERIC_ATTRIBUTE = s("1801")
    private val DEVICE_INFORMATION = s("180a")
    private val BATTERY_SERVICE = s("180f")
    private val NORDIC_UART_SERVICE = u("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val NORDIC_UART_TX = u("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val NORDIC_UART_RX = u("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    // ----- ALL: ported from bluetooth_services.json (12 entries) -----

    /** Faithful port of `bluetooth_services.json` — order preserved. */
    val ALL: List<WheelTopology> = listOf(
        // [0] gotway #1
        WheelTopology(
            adapter = "gotway", wheelType = WheelType.GOTWAY,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a02"), s("2a03"), s("2a04")),
                service(GENERIC_ATTRIBUTE, s("2a05")),
                service(DEVICE_INFORMATION,
                    s("2a23"), s("2a24"), s("2a25"), s("2a26"), s("2a27"),
                    s("2a28"), s("2a29"), s("2a2a"), s("2a50")),
                service(s("ffe0"), s("ffe1")),
            )
        ),
        // [1] gotway #2
        WheelTopology(
            adapter = "gotway", wheelType = WheelType.GOTWAY,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01")),
                service(GENERIC_ATTRIBUTE, s("2a05"), s("2b2a"), s("2b29")),
                service(DEVICE_INFORMATION,
                    s("2a23"), s("2a24"), s("2a25"), s("2a26"), s("2a27"),
                    s("2a28"), s("2a29"), s("2a50")),
                service(s("ffe0"), s("ffe1")),
                service(s("fff0"), s("fff1")),
                service(u("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0"),
                    u("f7bf3564-fb6d-4e53-88a4-5e37e0326063")),
            )
        ),
        // [2] inmotion #1
        WheelTopology(
            adapter = "inmotion", wheelType = WheelType.INMOTION,
            services = setOf(
                service(DEVICE_INFORMATION, s("2a23"), s("2a26"), s("2a29")),
                service(BATTERY_SERVICE, s("2a19")),
                service(s("ffe0"), s("ffe4")),
                service(s("ffe5"), s("ffe9")),
                service(s("fff0"),
                    s("fff1"), s("fff2"), s("fff3"), s("fff4"),
                    s("fff5"), s("fff6"), s("fff7"), s("fff8"), s("fff9")),
                service(s("ffd0"), s("ffd1"), s("ffd2"), s("ffd3"), s("ffd4")),
                service(s("ffc0"), s("ffc1"), s("ffc2")),
                service(s("ffb0"), s("ffb1"), s("ffb2"), s("ffb3"), s("ffb4")),
                service(s("ffa0"), s("ffa2"), s("ffa1")),
                service(s("ff90"),
                    s("ff91"), s("ff92"), s("ff93"), s("ff94"), s("ff95"),
                    s("ff96"), s("ff97"), s("ff98"), s("ff99"), s("ff9a")),
                service(s("fc60"), s("fc64")),
                service(s("fe00"),
                    s("fe01"), s("fe02"), s("fe03"), s("fe04"), s("fe05"), s("fe06")),
            )
        ),
        // [3] inmotion_v2 #1
        WheelTopology(
            adapter = "inmotion_v2", wheelType = WheelType.LORIN,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a04"), s("2aa6")),
                service(GENERIC_ATTRIBUTE, s("2a05")),
                service(NORDIC_UART_SERVICE, NORDIC_UART_TX, NORDIC_UART_RX),
            )
        ),
        // [4] inmotion_v2 #2
        WheelTopology(
            adapter = "inmotion_v2", wheelType = WheelType.LORIN,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a04"), s("2aa6")),
                service(GENERIC_ATTRIBUTE, s("2a05")),
                service(NORDIC_UART_SERVICE, NORDIC_UART_TX, NORDIC_UART_RX),
                service(s("ffe5"), s("ffe9")),
                service(s("ffe0"), s("ffe4")),
            )
        ),
        // [5] inmotion_v2 #3 (1801 has no characteristics)
        WheelTopology(
            adapter = "inmotion_v2", wheelType = WheelType.LORIN,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a04"), s("2aa6")),
                service(GENERIC_ATTRIBUTE),
                service(NORDIC_UART_SERVICE, NORDIC_UART_TX, NORDIC_UART_RX),
            )
        ),
        // [6] kingsong #1
        WheelTopology(
            adapter = "kingsong", wheelType = WheelType.KINGSONG,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a02"), s("2a03"), s("2a04")),
                service(GENERIC_ATTRIBUTE, s("2a05")),
                service(DEVICE_INFORMATION,
                    s("2a23"), s("2a24"), s("2a25"), s("2a26"), s("2a27"),
                    s("2a28"), s("2a29"), s("2a2a"), s("2a50")),
                service(s("fff0"), s("fff1"), s("fff2"), s("fff3"), s("fff4"), s("fff5")),
                service(s("ffe0"), s("ffe1")),
            )
        ),
        // [7] kingsong #2
        WheelTopology(
            adapter = "kingsong", wheelType = WheelType.KINGSONG,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a04"), s("2ac9")),
                service(GENERIC_ATTRIBUTE, s("2a05")),
                service(DEVICE_INFORMATION,
                    s("2a23"), s("2a24"), s("2a25"), s("2a26"), s("2a27"),
                    s("2a28"), s("2a29"), s("2a50")),
                service(u("02f00000-0000-0000-0000-00000000fe00"),
                    u("02f00000-0000-0000-0000-00000000ff03"),
                    u("02f00000-0000-0000-0000-00000000ff02"),
                    u("02f00000-0000-0000-0000-00000000ff00"),
                    u("02f00000-0000-0000-0000-00000000ff01")),
                service(s("ffe0"),
                    s("ffe1"), s("fff3"), s("fff5"),
                    u("0783b03e-8535-b5a0-7140-a304d2495cba"),
                    u("0783b03e-8535-b5a0-7140-a304d2495cb8")),
            )
        ),
        // [8] kingsong #3
        WheelTopology(
            adapter = "kingsong", wheelType = WheelType.KINGSONG,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01")),
                service(GENERIC_ATTRIBUTE, s("2a05"), s("2b29"), s("2b2a")),
                service(s("ffe0"), s("ffe2"), s("ffe1")),
                service(DEVICE_INFORMATION,
                    s("2a29"), s("2a24"), s("2a25"), s("2a27"), s("2a26"),
                    s("2a28"), s("2a23"), s("2a2a"), s("2a50")),
            )
        ),
        // [9] ninebot #1
        WheelTopology(
            adapter = "ninebot", wheelType = WheelType.NINEBOT,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a02"), s("2a03"), s("2a04")),
                service(GENERIC_ATTRIBUTE, s("2a05")),
                service(s("ffe0"), s("ffe1")),
            )
        ),
        // [10] ninebot_z #1 (1801 empty)
        WheelTopology(
            adapter = "ninebot_z", wheelType = WheelType.NINEBOT_Z,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a04")),
                service(GENERIC_ATTRIBUTE),
                service(NORDIC_UART_SERVICE, NORDIC_UART_RX, NORDIC_UART_TX),
            )
        ),
        // [11] ninebot_z #2 (1801 empty + extra fee7)
        WheelTopology(
            adapter = "ninebot_z", wheelType = WheelType.NINEBOT_Z,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a04")),
                service(GENERIC_ATTRIBUTE),
                service(NORDIC_UART_SERVICE, NORDIC_UART_RX, NORDIC_UART_TX),
                service(s("fee7"), s("fec8"), s("fec7"), s("fec9")),
            )
        ),
    )

    // ----- PROXY: ported from bluetooth_proxy_services.json (8 entries) -----

    /**
     * Faithful port of `bluetooth_proxy_services.json` — order preserved.
     *
     * The first two entries are byte-identical in the legacy JSON; we keep
     * both so the matcher returning `List<Match>` (not `Match?`) makes the
     * residual ambiguity visible to callers.
     */
    val PROXY: List<WheelTopology> = listOf(
        // [0] gotway #1
        WheelTopology(
            adapter = "gotway", wheelType = WheelType.GOTWAY,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a04"), s("2aa6")),
                service(GENERIC_ATTRIBUTE),
                service(s("ffa0"), s("ffa1"), s("ffa7")),
                service(s("ffe0"), s("ffe1")),
            )
        ),
        // [1] gotway #2 (byte-identical to [0] in the legacy file)
        WheelTopology(
            adapter = "gotway", wheelType = WheelType.GOTWAY,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a04"), s("2aa6")),
                service(GENERIC_ATTRIBUTE),
                service(s("ffa0"), s("ffa1"), s("ffa7")),
                service(s("ffe0"), s("ffe1")),
            )
        ),
        // [2] inmotion
        WheelTopology(
            adapter = "inmotion", wheelType = WheelType.INMOTION,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a04"), s("2aa6")),
                service(GENERIC_ATTRIBUTE),
                service(s("ffe0"), s("ffe4")),
                service(s("ffe5"), s("ffe9")),
                service(s("ffa0"), s("ffa1"), s("ffa5")),
                service(s("fe00"),
                    s("fe01"), s("fe02"), s("fe03"), s("fe04"), s("fe05"), s("fe06")),
            )
        ),
        // [3] inmotion_v2
        WheelTopology(
            adapter = "inmotion_v2", wheelType = WheelType.LORIN,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a04"), s("2aa6")),
                service(GENERIC_ATTRIBUTE),
                service(s("ffa0"), s("ffa1"), s("ffa6")),
                service(NORDIC_UART_SERVICE, NORDIC_UART_TX, NORDIC_UART_RX),
            )
        ),
        // [4] kingsong
        WheelTopology(
            adapter = "kingsong", wheelType = WheelType.KINGSONG,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a04"), s("2aa6")),
                service(GENERIC_ATTRIBUTE),
                service(s("ffa0"), s("ffa1"), s("ffa9")),
                service(s("ffe0"), s("ffe1")),
                service(s("fff0"), s("fff1")),
            )
        ),
        // [5] ninebot
        WheelTopology(
            adapter = "ninebot", wheelType = WheelType.NINEBOT,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a04"), s("2aa6")),
                service(GENERIC_ATTRIBUTE),
                service(s("ffa0"), s("ffa1"), s("ffa2")),
                service(s("ffe0"), s("ffe1")),
            )
        ),
        // [6] ninebot_z #1
        WheelTopology(
            adapter = "ninebot_z", wheelType = WheelType.NINEBOT_Z,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a04"), s("2aa6")),
                service(GENERIC_ATTRIBUTE),
                service(s("ffa0"), s("ffa1"), s("ffa3")),
                service(NORDIC_UART_SERVICE, NORDIC_UART_RX, NORDIC_UART_TX),
            )
        ),
        // [7] ninebot_z #2
        WheelTopology(
            adapter = "ninebot_z", wheelType = WheelType.NINEBOT_Z,
            services = setOf(
                service(GENERIC_ACCESS, s("2a00"), s("2a01"), s("2a04"), s("2aa6")),
                service(GENERIC_ATTRIBUTE),
                service(s("ffa0"), s("ffa1"), s("ffa4")),
                service(NORDIC_UART_SERVICE, NORDIC_UART_RX, NORDIC_UART_TX),
            )
        ),
    )
}
