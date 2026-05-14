package org.freewheel.core.ble

import org.freewheel.core.domain.identity.WheelType
import org.freewheel.core.domain.identity.wheel.WheelCatalog
import org.freewheel.core.utils.Logger

/**
 * Detects wheel type from discovered BLE services and characteristics.
 *
 * Pass 3a precedence (topology-first):
 *   1. Topology fingerprint match against [WheelTopologies.ALL]; if
 *      empty, fall through to [WheelTopologies.PROXY] (legacy two-stage
 *      `bluetooth_services.json` then `bluetooth_proxy_services.json`).
 *   2. Single distinct wheel type across the surviving matches →
 *      [DetectionResult.Detected]. Multiple matches that all resolve to
 *      the same wheel type also count as a single distinct type (e.g.
 *      the byte-identical PROXY gotway entries).
 *   3. Multiple matches with different wheel types → use the device
 *      name (via [deriveTypeFromName]) to disambiguate. If the name
 *      lands on one of the candidates, return [DetectionResult.Detected];
 *      otherwise return [DetectionResult.Ambiguous] with the candidate set.
 *   4. No topology match in either stage → log a WARN and fall through
 *      to name-only detection ([detectFromName]). This is the safety net
 *      for wheels whose fingerprints are not yet in either topology set.
 *   5. No name match either → [DetectionResult.Unknown]. The legacy
 *      "ambiguous standard service silently routes to GOTWAY_VIRTUAL"
 *      branch is gone; surfacing Unknown lets callers transition to
 *      Failed (Pass 3a) or to a wheel-type picker (Pass 4) instead of
 *      silently mis-protocolling.
 */
class WheelTypeDetector(
    private val primaryMatcher: WheelTopologyMatcher = WheelTopologyMatcher(WheelTopologies.ALL),
    private val proxyMatcher: WheelTopologyMatcher = WheelTopologyMatcher(WheelTopologies.PROXY),
) {

    /**
     * Result of wheel type detection.
     */
    sealed class DetectionResult {
        /**
         * Successfully detected wheel type.
         */
        data class Detected(
            val wheelType: WheelType,
            val readServiceUuid: String,
            val readCharacteristicUuid: String,
            val writeServiceUuid: String,
            val writeCharacteristicUuid: String,
            val confidence: Confidence = Confidence.HIGH
        ) : DetectionResult()

        /**
         * Could not determine wheel type.
         */
        data class Unknown(val reason: String) : DetectionResult()

        /**
         * Multiple possible wheel types detected (ambiguous).
         */
        data class Ambiguous(
            val possibleTypes: List<WheelType>,
            val reason: String
        ) : DetectionResult()
    }

    /**
     * Confidence level of detection.
     */
    enum class Confidence {
        HIGH,   // Unique service combination, very reliable
        MEDIUM, // Common services but distinguishing characteristics present
        LOW     // May need device name or data to confirm
    }

    /**
     * Detect wheel type from discovered services.
     *
     * @param services The discovered BLE services
     * @param deviceName Optional device name for additional heuristics
     * @return Detection result with wheel type and connection info
     */
    fun detect(services: DiscoveredServices, deviceName: String? = null): DetectionResult {
        // Two-stage matching mirrors legacy WheelData.detectWheel, which
        // reads bluetooth_services.json (the direct fingerprints) and only
        // falls back to bluetooth_proxy_services.json on miss. The two sets
        // can't both fire for the same input — every fingerprint requires
        // an exact service-count match and the ALL/PROXY service sets
        // diverge — but keeping the ALL-then-PROXY order preserves legacy
        // precedence in case future fingerprint additions overlap.
        val matches = primaryMatcher.match(services)
            .ifEmpty { proxyMatcher.match(services) }

        return when {
            matches.isEmpty() -> fallbackToName(deviceName, services)
            else -> {
                val candidates = matches.map { it.topology.wheelType }.distinct()
                if (candidates.size == 1) {
                    detectedFromWheelType(candidates[0])
                } else {
                    disambiguateByName(candidates, deviceName)
                }
            }
        }
    }

    private fun detectedFromWheelType(wheelType: WheelType): DetectionResult {
        val info = WheelConnectionInfo.forType(wheelType)
            ?: return DetectionResult.Unknown(
                "Topology matched $wheelType but no WheelConnectionInfo is registered for it."
            )
        return DetectionResult.Detected(
            wheelType = info.wheelType,
            readServiceUuid = info.readServiceUuid,
            readCharacteristicUuid = info.readCharacteristicUuid,
            writeServiceUuid = info.writeServiceUuid,
            writeCharacteristicUuid = info.writeCharacteristicUuid,
            confidence = Confidence.HIGH,
        )
    }

    private fun disambiguateByName(
        candidates: List<WheelType>,
        deviceName: String?,
    ): DetectionResult {
        val nameType = deriveTypeFromName(deviceName)
        if (nameType != null && nameType in candidates) {
            return detectedFromWheelType(nameType)
        }
        return DetectionResult.Ambiguous(
            possibleTypes = candidates,
            reason = "Topology matches multiple wheel types (${candidates.joinToString()}); " +
                    "device name '$deviceName' did not disambiguate.",
        )
    }

    private fun fallbackToName(deviceName: String?, services: DiscoveredServices): DetectionResult {
        Logger.w(
            TAG,
            "No topology match for services=${services.serviceUuids()} (deviceName='$deviceName'); falling back to name detection",
        )
        val nameResult = detectFromName(deviceName)
        if (nameResult != null) return nameResult
        return DetectionResult.Unknown(
            "No topology fingerprint matched and device name '$deviceName' did not match any pattern. " +
                    "Services: ${services.serviceUuids()}"
        )
    }

    /**
     * Try to detect wheel type from device name alone.
     * Returns null if name doesn't match any known pattern.
     */
    private fun detectFromName(deviceName: String?): DetectionResult? {
        val type = deriveTypeFromName(deviceName) ?: return null
        val info = WheelConnectionInfo.forType(type) ?: return null
        return DetectionResult.Detected(
            wheelType = info.wheelType,
            readServiceUuid = info.readServiceUuid,
            readCharacteristicUuid = info.readCharacteristicUuid,
            writeServiceUuid = info.writeServiceUuid,
            writeCharacteristicUuid = info.writeCharacteristicUuid,
            confidence = Confidence.HIGH
        )
    }

    companion object {
        private const val TAG = "WheelTypeDetector"

        /**
         * Derive wheel type from device name patterns alone.
         *
         * Public so the iOS scan-time path can pass a wheel-type hint into
         * `WheelConnectionManager.connect(address, wheelType)` before service
         * discovery completes. Returns null if the name doesn't match any
         * known pattern (caller should fall through to topology-based detection).
         */
        fun deriveTypeFromName(deviceName: String?): WheelType? {
            val name = deviceName?.uppercase() ?: return null
            if (name.isEmpty()) return null

            return when {
                // Veteran/Leaperkim patterns (legacy DC 5A 5C protocol).
                // Official Leaperkim app filters by "LK" prefix; all known LK wheels
                // use the legacy Veteran protocol, not CAN-over-BLE.
                name.contains("LEAPERKIM") ||
                name.contains("LPKIM") ||
                name.startsWith("LK") ||
                name.contains("VETERAN") ||
                name.contains("SHERMAN") ||
                name.contains("LYNX") ||
                name.contains("PATTON") ||
                name.contains("ABRAMS") ||
                name.contains("ORYX") ||
                name.contains("NOSFET") ||
                name.startsWith("NF") -> WheelType.VETERAN

                // InMotion V2 patterns (before Gotway to avoid conflicts with names like "MASTER")
                name.startsWith("V11") || name.startsWith("V12") || name.startsWith("V13") ||
                name.startsWith("V14") || name.startsWith("V9") || name.startsWith("P6") ||
                name.startsWith("E20") || name.startsWith("CLIMBER") || name.startsWith("GLIDE") ||
                name.contains("INMOTION") -> WheelType.INMOTION_V2

                // Gotway/Begode patterns
                name.contains("GOTWAY") ||
                name.startsWith("GW") ||
                name.contains("BEGODE") ||
                name.contains("MCMASTER") ||
                name.contains("NIKOLA") ||
                name.contains("MONSTER") ||
                name.contains("MSP") ||
                name.contains("RSHS") ||
                name.contains("EX.N") ||
                name.contains("HERO") ||
                name.contains("MASTER") -> WheelType.GOTWAY

                // KingSong patterns. Legacy heuristics ("KS-", "KINGSONG", "KS"
                // prefix) plus catalog-driven coverage for plain model names
                // like "S22", "S22 PRO", "F22 PRO" that real-world Kingsong
                // firmware advertises without any KS prefix. Token list comes
                // from WheelCatalog so adding a new model can't silently
                // regress detection. Prefix match (NOT contains) so an
                // embedded "S16"/"S22"/"F22" segment can't beat later brand
                // checks (e.g. NINEBOT-S16 must stay Ninebot).
                name.contains("KS-") ||
                name.contains("KINGSONG") ||
                name.startsWith("KS") ||
                run {
                    val normalized = name.normalizeWheelName()
                    normalized.isNotEmpty() && kingsongTokens.any { normalized.startsWith(it) }
                } -> WheelType.KINGSONG

                // Ninebot patterns
                name.contains("NINEBOT") ||
                name.contains("NB-") -> WheelType.NINEBOT

                else -> null
            }
        }

        /**
         * Normalize a wheel name for token matching: uppercase + strip
         * non-alphanumerics. Collapses "S22 PRO", "S22Pro", "S22-PRO", and
         * "s22 pro" to "S22PRO" so a single catalog token covers every
         * real-world advertisement formatting.
         */
        private fun String.normalizeWheelName(): String =
            uppercase().filter { it.isLetterOrDigit() }

        /**
         * Catalog-derived Kingsong model tokens, normalized once at first use.
         * Source of truth: [WheelCatalog]. Adding a new Kingsong entry
         * automatically widens detection coverage; the catalog-driven test
         * loop fails fast if any catalog token doesn't resolve.
         */
        private val kingsongTokens: Set<String> by lazy {
            WheelCatalog.entries
                .filter { it.wheelType == WheelType.KINGSONG }
                .flatMap { it.nameTokens }
                .map { it.normalizeWheelName() }
                .filter { it.isNotEmpty() }
                .toSet()
        }
    }
}
