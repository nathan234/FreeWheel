package org.freewheel.core.domain.identity.wheel

import org.freewheel.core.ble.ServiceTopology
import org.freewheel.core.domain.identity.WheelIdentity
import org.freewheel.core.domain.identity.WheelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WheelReportTest {

    @Test
    fun urlPointsToCorrectRepo() {
        val url = WheelReport.buildGitHubIssueUrl(
            WheelIdentity(wheelType = WheelType.GOTWAY, btName = "X")
        )
        assertTrue(
            url.startsWith("https://github.com/nathan234/FreeWheel/issues/new?"),
            "url=$url"
        )
    }

    @Test
    fun urlIncludesTitleLabelAndBody() {
        val url = WheelReport.buildGitHubIssueUrl(
            WheelIdentity(wheelType = WheelType.GOTWAY, btName = "MASTER")
        )
        assertTrue(url.contains("title="))
        assertTrue(url.contains("labels=wheel-fingerprint"))
        assertTrue(url.contains("body="))
    }

    @Test
    fun titleIncludesWheelTypeAndBtName() {
        val url = WheelReport.buildGitHubIssueUrl(
            WheelIdentity(wheelType = WheelType.GOTWAY, btName = "MASTER-1234")
        )
        assertTrue(url.contains("title=Wheel%20fingerprint%3A%20GOTWAY%20MASTER-1234"))
    }

    @Test
    fun bodyContainsKeyIdentityFields() {
        val text = WheelReport.buildShareText(
            WheelIdentity(
                wheelType = WheelType.GOTWAY,
                btName = "EB-COMMANDER",
                version = "MasterPro v1.5.0",
                brand = "Extreme Bull",
            ),
            observedMaxKmh = 65.5,
        )
        assertTrue(text.contains("EB-COMMANDER"))
        assertTrue(text.contains("MasterPro v1.5.0"))
        assertTrue(text.contains("Extreme Bull"))
        assertTrue(text.contains("65.5"))
    }

    @Test
    fun bodySkipsEmptyFields() {
        val text = WheelReport.buildShareText(
            WheelIdentity(wheelType = WheelType.GOTWAY, btName = "X")
        )
        assertFalse(text.contains("Firmware version"))
        assertFalse(text.contains("Serial number"))
    }

    @Test
    fun observedMaxKmhOnlyIncludedWhenPositive() {
        val zero = WheelReport.buildShareText(
            WheelIdentity(wheelType = WheelType.GOTWAY, btName = "X")
        )
        assertFalse(zero.contains("Observed peak"))

        val nonZero = WheelReport.buildShareText(
            WheelIdentity(wheelType = WheelType.GOTWAY, btName = "X"),
            observedMaxKmh = 42.0,
        )
        assertTrue(nonZero.contains("Observed peak"))
    }

    @Test
    fun percentEncodeUnreservedCharsPassThrough() {
        assertEquals("Hello-_.~AZaz09", WheelReport.percentEncode("Hello-_.~AZaz09"))
    }

    @Test
    fun percentEncodeSpacesAndAmpersand() {
        assertEquals("a%20b%26c", WheelReport.percentEncode("a b&c"))
    }

    @Test
    fun percentEncodeNewlines() {
        assertEquals("a%0Ab", WheelReport.percentEncode("a\nb"))
    }

    @Test
    fun percentEncodeUtf8Multibyte() {
        // U+00E9 (é) in UTF-8 is 0xC3 0xA9
        assertEquals("%C3%A9", WheelReport.percentEncode("é"))
    }

    @Test
    fun shareTextBeginsWithFingerprintHeader() {
        val text = WheelReport.buildShareText(
            WheelIdentity(wheelType = WheelType.GOTWAY, btName = "X")
        )
        assertTrue(text.startsWith("Wheel fingerprint: "))
    }

    @Test
    fun appVersionAndPlatformIncludedWhenProvided() {
        val text = WheelReport.buildShareText(
            WheelIdentity(wheelType = WheelType.GOTWAY, btName = "X"),
            appVersion = "1.2.3",
            appPlatform = "android",
        )
        assertTrue(text.contains("1.2.3"))
        assertTrue(text.contains("android"))
    }

    // ==================== Pass 3b: full topology in report ====================
    //
    // The unrecognized-wheel report now includes the complete service
    // topology (services + characteristics) so a maintainer can paste it
    // directly into WheelTopologies.ALL with minimal editing. Per-service
    // line format: `- {service-uuid}: [{c1}, {c2}, ...]`. Empty-char
    // services render as `[]`. The section is omitted entirely when the
    // caller passes an empty topology (legacy callers, plumbing not yet
    // wired up).

    private val ffe0 = "0000ffe0-0000-1000-8000-00805f9b34fb"
    private val ffe1 = "0000ffe1-0000-1000-8000-00805f9b34fb"
    private val fff0 = "0000fff0-0000-1000-8000-00805f9b34fb"
    private val fff1 = "0000fff1-0000-1000-8000-00805f9b34fb"
    private val genericAttribute = "00001801-0000-1000-8000-00805f9b34fb"

    @Test
    fun emptyTopologyOmitsTheSection() {
        val text = WheelReport.buildShareText(
            WheelIdentity(wheelType = WheelType.GOTWAY, btName = "X"),
            services = emptyList(),
        )
        assertFalse(text.contains("Service topology"), "Empty topology should not render a section header; got:\n$text")
    }

    @Test
    fun singleServiceRendersInParseableForm() {
        val text = WheelReport.buildShareText(
            WheelIdentity(wheelType = WheelType.GOTWAY, btName = "X"),
            services = listOf(ServiceTopology(ffe0, setOf(ffe1))),
        )
        assertTrue(
            text.contains("- $ffe0: [$ffe1]"),
            "Expected per-service line; got:\n$text"
        )
    }

    @Test
    fun multipleServicesRenderInListOrder() {
        // Service rendering preserves the order of the input list so a
        // maintainer pasting the dump into WheelTopologies.ALL gets the
        // services in the same order the wheel exposed them — easier to
        // visually compare against existing fingerprints.
        val text = WheelReport.buildShareText(
            WheelIdentity(wheelType = WheelType.GOTWAY, btName = "X"),
            services = listOf(
                ServiceTopology(fff0, setOf(fff1)),
                ServiceTopology(ffe0, setOf(ffe1)),
            ),
        )
        val firstIdx = text.indexOf("- $fff0:")
        val secondIdx = text.indexOf("- $ffe0:")
        assertTrue(firstIdx in 0 until secondIdx, "Services rendered out of order; got:\n$text")
    }

    @Test
    fun serviceWithEmptyCharacteristicsRendersAsEmptyBrackets() {
        // The Generic Attribute service often has no characteristics in
        // legacy fingerprints; rendering it as `[]` keeps the dump
        // visually consistent with the other service lines.
        val text = WheelReport.buildShareText(
            WheelIdentity(wheelType = WheelType.GOTWAY, btName = "X"),
            services = listOf(ServiceTopology(genericAttribute, emptySet())),
        )
        assertTrue(
            text.contains("- $genericAttribute: []"),
            "Empty-char service should render with []; got:\n$text"
        )
    }

    @Test
    fun multipleCharacteristicsRenderCommaSeparated() {
        val text = WheelReport.buildShareText(
            WheelIdentity(wheelType = WheelType.GOTWAY, btName = "X"),
            services = listOf(
                ServiceTopology(fff0, setOf(fff1, "0000fff2-0000-1000-8000-00805f9b34fb")),
            ),
        )
        assertTrue(
            text.contains("- $fff0: [$fff1, 0000fff2-0000-1000-8000-00805f9b34fb]"),
            "Multi-char service should render comma-separated; got:\n$text"
        )
    }

    @Test
    fun topologySectionAppearsInGitHubIssueUrl() {
        // End-to-end check: the URL-encoded body must include the
        // service-topology section so a maintainer opening the issue can
        // copy-paste the topology straight into WheelTopologies.ALL.
        val url = WheelReport.buildGitHubIssueUrl(
            WheelIdentity(wheelType = WheelType.GOTWAY, btName = "X"),
            services = listOf(ServiceTopology(ffe0, setOf(ffe1))),
        )
        // "Service topology" → URL-encoded space is %20, so look for a
        // substring that's stable across encoding.
        assertTrue(
            url.contains("Service%20topology"),
            "URL body should contain the encoded section header; got:\n$url"
        )
    }
}
