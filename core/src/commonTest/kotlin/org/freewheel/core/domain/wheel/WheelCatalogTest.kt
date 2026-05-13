package org.freewheel.core.domain.wheel

import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WheelCatalogTest {

    // -- Resolver tier order -----------------------------------------------

    @Test
    fun resolveFallsBackToAbsoluteWhenNothingKnown() {
        assertEquals(
            WheelCatalog.ABSOLUTE_FALLBACK_KMH,
            WheelCatalog.resolveTopSpeedKmh()
        )
    }

    @Test
    fun resolveUsesUserOverrideOverEverythingElse() {
        val resolved = WheelCatalog.resolveTopSpeedKmh(
            userOverrideKmh = 75.0,
            wheelType = WheelType.GOTWAY,
            observedMaxKmh = 50.0,
        )
        assertEquals(75.0, resolved)
    }

    @Test
    fun resolveIgnoresNonPositiveOverride() {
        val resolved = WheelCatalog.resolveTopSpeedKmh(
            userOverrideKmh = 0.0,
            observedMaxKmh = 60.0,
        )
        assertEquals(60.0 * WheelCatalog.AUTO_ESTIMATE_HEADROOM, resolved)
    }

    @Test
    fun resolveUsesAutoEstimateBeforeAbsoluteFallback() {
        val resolved = WheelCatalog.resolveTopSpeedKmh(observedMaxKmh = 40.0)
        assertEquals(40.0 * WheelCatalog.AUTO_ESTIMATE_HEADROOM, resolved)
    }

    @Test
    fun resolveAutoEstimateIgnoresZeroObserved() {
        assertEquals(
            WheelCatalog.ABSOLUTE_FALLBACK_KMH,
            WheelCatalog.resolveTopSpeedKmh(observedMaxKmh = 0.0)
        )
    }

    @Test
    fun populatedCatalogMatchesKnownWheel() {
        val matched = WheelCatalog.match(
            wheelType = WheelType.GOTWAY,
            identity = WheelIdentity(version = "Master Pro v1.5"),
        )
        assertNotNull(matched)
        assertEquals("begode-master-pro", matched.id)
    }

    @Test
    fun populatedCatalogReturnsNullForUnknownTokens() {
        val matched = WheelCatalog.match(
            wheelType = WheelType.GOTWAY,
            identity = WheelIdentity(btName = "ASTROLABE-X1"),
        )
        assertNull(matched)
    }

    // -- Matcher behaviour (uses internal matchIn with controlled entries) -

    @Test
    fun matchesByVersionString() {
        val entries = listOf(
            entry("a", WheelType.GOTWAY, listOf("MASTER PRO"), 80.0),
        )
        val matched = matchIn(
            entries,
            wheelType = WheelType.GOTWAY,
            identity = WheelIdentity(version = "Master Pro v1.5.0"),
        )
        assertNotNull(matched)
        assertEquals("a", matched.id)
    }

    @Test
    fun matchesByModelString() {
        val entries = listOf(
            entry("a", WheelType.INMOTION_V2, listOf("V12"), 60.0),
        )
        val matched = matchIn(
            entries,
            wheelType = WheelType.INMOTION_V2,
            identity = WheelIdentity(model = "V12 Pro"),
        )
        assertNotNull(matched)
        assertEquals("a", matched.id)
    }

    @Test
    fun matchesByBrandFromGotwayJnPrefix() {
        val entries = listOf(
            entry("eb", WheelType.GOTWAY, listOf("EXTREME BULL"), 80.0),
        )
        val matched = matchIn(
            entries,
            wheelType = WheelType.GOTWAY,
            identity = WheelIdentity(brand = "Extreme Bull"),
        )
        assertNotNull(matched)
        assertEquals("eb", matched.id)
    }

    @Test
    fun matchesByBtNameWhenOtherIdentityEmpty() {
        val entries = listOf(
            entry("a", WheelType.GOTWAY, listOf("MSP"), 80.0),
        )
        val matched = matchIn(
            entries,
            wheelType = WheelType.GOTWAY,
            identity = WheelIdentity(btName = "MSP-1234"),
        )
        assertNotNull(matched)
        assertEquals("a", matched.id)
    }

    @Test
    fun longestMatchingTokenWinsAcrossEntries() {
        val entries = listOf(
            entry("commander", WheelType.GOTWAY, listOf("COMMANDER"), 60.0),
            entry("commander_max", WheelType.GOTWAY, listOf("COMMANDER MAX"), 80.0),
        )
        val matched = matchIn(
            entries,
            wheelType = WheelType.GOTWAY,
            identity = WheelIdentity(btName = "EB Commander Max v1"),
        )
        assertNotNull(matched)
        assertEquals("commander_max", matched.id)
    }

    @Test
    fun longestTokenWithinSingleEntryWins() {
        val entries = listOf(
            entry("a", WheelType.KINGSONG, listOf("KS", "KS-S22"), 70.0),
        )
        val matched = matchIn(
            entries,
            wheelType = WheelType.KINGSONG,
            identity = WheelIdentity(model = "KS-S22"),
        )
        assertNotNull(matched)
        assertEquals("a", matched.id)
    }

    @Test
    fun filtersByWheelType() {
        val entries = listOf(
            entry("v12", WheelType.INMOTION_V2, listOf("V12"), 60.0),
        )
        val matched = matchIn(
            entries,
            wheelType = WheelType.GOTWAY,
            identity = WheelIdentity(version = "V12-anything"),
        )
        assertNull(matched)
    }

    @Test
    fun matchIsCaseInsensitive() {
        val entries = listOf(
            entry("a", WheelType.VETERAN, listOf("sherman s"), 55.0),
        )
        val matched = matchIn(
            entries,
            wheelType = WheelType.VETERAN,
            identity = WheelIdentity(model = "SHERMAN S"),
        )
        assertNotNull(matched)
    }

    @Test
    fun emptyIdentityFieldsReturnNull() {
        val entries = listOf(
            entry("a", WheelType.GOTWAY, listOf("MSP"), 80.0),
        )
        val matched = matchIn(
            entries,
            wheelType = WheelType.GOTWAY,
            identity = WheelIdentity(),
        )
        assertNull(matched)
    }

    // -- Leaperkim correctness-plan invariant ------------------------------
    // docs/leaperkim-correctness-plan.md: every Leaperkim-family model in the
    // production catalog must route to VETERAN. CAN promotion is reserved for
    // capture-backed evidence and is never the catalog default.

    @Test
    fun noLeaperkimFamilyEntryRoutesToCanByDefault() {
        val leaperkimFamilyTokens = setOf(
            "SHERMAN", "PATTON", "LYNX", "ABRAMS", "ORYX",
            "AERO", "AEON", "APEX",
        )

        val offenders = WheelCatalog.entries.filter { entry ->
            entry.wheelType == WheelType.LEAPERKIM &&
                entry.nameTokens.any { token ->
                    leaperkimFamilyTokens.any { family -> token.uppercase().contains(family) }
                }
        }

        assertEquals(
            emptyList(),
            offenders.map { it.id },
            "Catalog entries '${offenders.map { it.id }}' silently route to CAN — " +
                "see docs/leaperkim-correctness-plan.md (no Leaperkim-family CAN default).",
        )
    }

    @Test
    fun officialAppBackedModelsResolveViaVeteranLane() {
        // The four entries fixed in Phase 1 of leaperkim-app-parity-plan.md:
        // each must match against its name when the runtime session is
        // VETERAN (i.e., what VeteranDecoder actually reports).
        val cases = listOf(
            "veteran-patton-s" to "PATTON-S",
            "veteran-sherman-l" to "SHERMAN-L",
            "veteran-lynx-s" to "LYNX-S",
            "veteran-oryx" to "ORYX",
        )
        for ((expectedId, btName) in cases) {
            val matched = WheelCatalog.match(
                wheelType = WheelType.VETERAN,
                identity = WheelIdentity(btName = btName),
            )
            assertNotNull(matched, "VETERAN session named '$btName' must match the catalog")
            assertEquals(expectedId, matched.id, "Expected catalog id for '$btName'")
        }
    }

    private fun entry(
        id: String,
        wheelType: WheelType,
        tokens: List<String>,
        topSpeedKmh: Double,
    ) = WheelCatalogEntry(
        id = id,
        displayName = id,
        wheelType = wheelType,
        nameTokens = tokens,
        topSpeedKmh = topSpeedKmh,
    )
}
