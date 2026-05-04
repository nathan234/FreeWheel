package org.freewheel.core.domain.wheel

import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelType

/**
 * Static catalog of known wheels with their stock top speeds, used to scale the
 * speedometer red zone per-wheel.
 *
 * Resolution order for the gauge maximum (in km/h):
 *   1. user-set override (per-MAC)
 *   2. catalog match against wheel identity / advertised name
 *   3. auto-estimate from observed peak speed (× [AUTO_ESTIMATE_HEADROOM])
 *   4. wheel-type fallback default
 *   5. [ABSOLUTE_FALLBACK_KMH]
 *
 * Identity strings are scanned in priority order (version, model, brand, name,
 * btName) with longest-token-wins to disambiguate families like "COMMANDER" vs
 * "COMMANDER MAX". The advertised BLE name is already captured into
 * [WheelIdentity.btName] by [org.freewheel.core.service.WheelConnectionManager]
 * during service discovery — no separate parameter is required.
 */
object WheelCatalog {

    /** Per-model entries. Populate as wheel speeds are confirmed. */
    val entries: List<WheelCatalogEntry> = listOf(
        // Begode / Gotway
        WheelCatalogEntry("begode-mten3", "Begode MTen3", WheelType.GOTWAY, listOf("MTEN3"), 38.0),
        WheelCatalogEntry("begode-mten4", "Begode MTen4", WheelType.GOTWAY, listOf("MTEN4"), 40.0),
        WheelCatalogEntry("begode-mten5", "Begode MTen5", WheelType.GOTWAY, listOf("MTEN5", "MTEN 5"), 40.0),
        WheelCatalogEntry("begode-a2", "Begode A2", WheelType.GOTWAY, listOf("A2"), 40.0),
        WheelCatalogEntry("begode-a5", "Begode A5", WheelType.GOTWAY, listOf("A5"), 50.0),
        WheelCatalogEntry("begode-falcon", "Begode Falcon", WheelType.GOTWAY, listOf("FALCON", "FALCON PRO"), 55.0),
        WheelCatalogEntry("begode-t4", "Begode T4", WheelType.GOTWAY, listOf("T4", "T4 PRO"), 70.0),
        WheelCatalogEntry("begode-master", "Begode Master", WheelType.GOTWAY, listOf("MASTER"), 80.0),
        WheelCatalogEntry("begode-ex30", "Begode EX30", WheelType.GOTWAY, listOf("EX30"), 88.0),
        WheelCatalogEntry("begode-blitz", "Begode Blitz", WheelType.GOTWAY, listOf("BLITZ", "BLITZ PRO"), 88.0),
        WheelCatalogEntry("begode-race", "Begode Race", WheelType.GOTWAY, listOf("RACE"), 88.0),
        WheelCatalogEntry("begode-et-max", "Begode ET Max", WheelType.GOTWAY, listOf("ET MAX", "ETMAX"), 88.0),
        WheelCatalogEntry("begode-master-pro", "Begode Master Pro", WheelType.GOTWAY, listOf("MASTER PRO"), 90.0),
        WheelCatalogEntry("begode-panther", "Begode Panther", WheelType.GOTWAY, listOf("PANTHER"), 97.0),
        WheelCatalogEntry("begode-x-way", "Begode X-Way", WheelType.GOTWAY, listOf("X-WAY", "XWAY"), 97.0),
        WheelCatalogEntry("begode-x-max", "Begode X-Max", WheelType.GOTWAY, listOf("X-MAX", "XMAX"), 97.0),

        // Extreme Bull
        WheelCatalogEntry("extreme-bull-commander-mini", "Extreme Bull Commander Mini", WheelType.GOTWAY, listOf("COMMANDER MINI"), 64.0),
        WheelCatalogEntry("extreme-bull-commander", "Extreme Bull Commander", WheelType.GOTWAY, listOf("COMMANDER"), 72.0),
        WheelCatalogEntry("extreme-bull-commander-pro", "Extreme Bull Commander Pro", WheelType.GOTWAY, listOf("COMMANDER PRO"), 80.0),
        WheelCatalogEntry("extreme-bull-commander-gt", "Extreme Bull Commander GT", WheelType.GOTWAY, listOf("COMMANDER GT"), 85.0),
        WheelCatalogEntry("extreme-bull-commander-gt-pro-plus", "Extreme Bull Commander GT Pro+", WheelType.GOTWAY, listOf("COMMANDER GT PRO+", "GT PRO+", "GT PRO PLUS"), 95.0),
        WheelCatalogEntry("extreme-bull-commander-max", "Extreme Bull Commander Max", WheelType.GOTWAY, listOf("COMMANDER MAX"), 97.0),
        WheelCatalogEntry("extreme-bull-griffin", "Extreme Bull Griffin", WheelType.GOTWAY, listOf("GRIFFIN"), 70.0),
        WheelCatalogEntry("extreme-bull-rocket", "Extreme Bull Rocket", WheelType.GOTWAY, listOf("ROCKET"), 55.0),
        WheelCatalogEntry("extreme-bull-x-men", "Extreme Bull X-Men", WheelType.GOTWAY, listOf("X-MEN", "XMEN"), 80.0),

        // LeaperKim / Veteran
        WheelCatalogEntry("veteran-sherman", "Veteran Sherman", WheelType.VETERAN, listOf("SHERMAN", "VETERAN SHERMAN"), 72.0),
        WheelCatalogEntry("veteran-sherman-max", "Veteran Sherman Max", WheelType.VETERAN, listOf("SHERMAN MAX"), 72.0),
        WheelCatalogEntry("veteran-sherman-s", "Veteran Sherman-S", WheelType.VETERAN, listOf("SHERMAN S", "SHERMAN-S"), 72.0),
        WheelCatalogEntry("veteran-patton", "Veteran Patton", WheelType.VETERAN, listOf("PATTON"), 80.0),
        WheelCatalogEntry("veteran-patton-s", "Veteran Patton-S", WheelType.LEAPERKIM, listOf("PATTON S", "PATTON-S"), 68.0),
        WheelCatalogEntry("veteran-lynx", "Veteran Lynx", WheelType.VETERAN, listOf("LYNX"), 88.0),
        WheelCatalogEntry("veteran-lynx-s", "Veteran Lynx-S", WheelType.LEAPERKIM, listOf("LYNX S", "LYNX-S"), 90.0),
        WheelCatalogEntry("veteran-sherman-l", "Veteran Sherman-L", WheelType.LEAPERKIM, listOf("SHERMAN L", "SHERMAN-L"), 88.0),
        WheelCatalogEntry("veteran-oryx", "Veteran Oryx", WheelType.LEAPERKIM, listOf("ORYX"), 97.0),

        // NOSFET
        WheelCatalogEntry("nosfet-aero", "NOSFET Aero", WheelType.VETERAN, listOf("AERO"), 55.0),
        WheelCatalogEntry("nosfet-aeon", "NOSFET Aeon", WheelType.VETERAN, listOf("AEON"), 64.0),
        WheelCatalogEntry("nosfet-apex", "NOSFET Apex", WheelType.VETERAN, listOf("APEX", "APEX-01", "APEX 01"), 90.0),

        // KingSong
        WheelCatalogEntry("kingsong-16x", "KingSong 16X", WheelType.KINGSONG, listOf("16X", "KS16X", "KS-16X"), 50.0),
        WheelCatalogEntry("kingsong-18xl", "KingSong 18XL", WheelType.KINGSONG, listOf("18XL", "KS18XL", "KS-18XL"), 50.0),
        WheelCatalogEntry("kingsong-s18", "KingSong S18", WheelType.KINGSONG, listOf("S18", "KS S18", "KS-S18"), 50.0),
        WheelCatalogEntry("kingsong-s19", "KingSong S19", WheelType.KINGSONG, listOf("S19", "KS S19", "KS-S19"), 55.0),
        WheelCatalogEntry("kingsong-s22", "KingSong S22", WheelType.KINGSONG, listOf("S22", "S22 PRO", "KS S22", "KS-S22"), 70.0),
        WheelCatalogEntry("kingsong-s16", "KingSong S16", WheelType.KINGSONG, listOf("S16", "S16 PRO", "KS S16", "KS-S16"), 75.0),
        WheelCatalogEntry("kingsong-f18", "KingSong F18", WheelType.KINGSONG, listOf("F18", "KS F18", "KS-F18", "KINGSONG F18"), 88.0),
        WheelCatalogEntry("kingsong-f22-pro", "KingSong F22 Pro", WheelType.KINGSONG, listOf("F22 PRO", "F22", "KS F22", "KS-F22"), 80.0),

        // InMotion
        WheelCatalogEntry("inmotion-v10f", "InMotion V10F", WheelType.INMOTION, listOf("V10F"), 40.0),
        WheelCatalogEntry("inmotion-v11", "InMotion V11", WheelType.INMOTION, listOf("V11"), 45.0),
        WheelCatalogEntry("inmotion-v11y", "InMotion V11Y", WheelType.INMOTION, listOf("V11Y"), 45.0),
        WheelCatalogEntry("inmotion-v12", "InMotion V12", WheelType.INMOTION_V2, listOf("V12", "V12 PRO"), 60.0),
        WheelCatalogEntry("inmotion-v13", "InMotion V13 Challenger", WheelType.INMOTION_V2, listOf("V13", "V13 CHALLENGER"), 90.0),
        WheelCatalogEntry("inmotion-v14", "InMotion V14 Adventure", WheelType.INMOTION_V2, listOf("V14", "V14 ADVENTURE"), 70.0),
        WheelCatalogEntry("inmotion-p6", "InMotion P6", WheelType.INMOTION_V2, listOf("P6"), 95.0),
    )

    /** Wheel-type fallbacks when no per-model entry matches. */
    val typeDefaults: Map<WheelType, Double> = emptyMap()

    /** Last-resort fallback when neither catalog match nor type default exists. */
    const val ABSOLUTE_FALLBACK_KMH: Double = 50.0

    /**
     * Multiplier applied to observed peak speed to derive an auto-estimated gauge max.
     * 1.20 places the observed peak around the start of the red zone (since
     * [org.freewheel.core.domain.dashboard.DashboardMetric.SPEED] uses redAbove = 0.75:
     * observed / 1.20 ≈ 0.83 of gauge, well into red).
     */
    const val AUTO_ESTIMATE_HEADROOM: Double = 1.20

    fun match(
        wheelType: WheelType,
        identity: WheelIdentity = WheelIdentity(),
    ): WheelCatalogEntry? = matchIn(entries, wheelType, identity)

    fun resolveTopSpeedKmh(
        userOverrideKmh: Double? = null,
        wheelType: WheelType = WheelType.Unknown,
        identity: WheelIdentity = WheelIdentity(),
        observedMaxKmh: Double = 0.0,
    ): Double {
        userOverrideKmh?.takeIf { it > 0.0 }?.let { return it }
        match(wheelType, identity)?.let { return it.topSpeedKmh }
        if (observedMaxKmh > 0.0) return observedMaxKmh * AUTO_ESTIMATE_HEADROOM
        typeDefaults[wheelType]?.takeIf { it > 0.0 }?.let { return it }
        return ABSOLUTE_FALLBACK_KMH
    }
}

/**
 * Pure matching helper used by [WheelCatalog.match] and tests.
 * Identity fields are tried in priority order: version → model → brand → name →
 * btName. Longest-token match wins among entries filtered by [wheelType].
 */
internal fun matchIn(
    entries: List<WheelCatalogEntry>,
    wheelType: WheelType,
    identity: WheelIdentity,
): WheelCatalogEntry? {
    val candidates = listOfNotNull(
        identity.version.takeIf { it.isNotEmpty() },
        identity.model.takeIf { it.isNotEmpty() },
        identity.brand.takeIf { it.isNotEmpty() },
        identity.name.takeIf { it.isNotEmpty() },
        identity.btName.takeIf { it.isNotEmpty() },
    ).map { it.uppercase() }
    if (candidates.isEmpty()) return null

    var best: WheelCatalogEntry? = null
    var bestLen = 0
    for (entry in entries) {
        if (entry.wheelType != wheelType) continue
        for (token in entry.nameTokens) {
            val upToken = token.uppercase()
            if (upToken.length > bestLen && candidates.any { it.contains(upToken) }) {
                best = entry
                bestLen = upToken.length
            }
        }
    }
    return best
}
