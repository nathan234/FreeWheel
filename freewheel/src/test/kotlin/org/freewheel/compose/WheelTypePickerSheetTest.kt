package org.freewheel.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.freewheel.compose.components.WheelTypePickerSheet
import org.freewheel.core.domain.identity.WheelType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke tests for [WheelTypePickerSheet]. Pinned per Pass 4 plan acceptance:
 *  - All non-sentinel WheelType options render (excluding GOTWAY_VIRTUAL/Unknown).
 *  - The "Likely" badge appears for the
 *    [org.freewheel.core.ble.WheelTypeDetector.deriveTypeFromName] match.
 *  - No badge appears when deviceName doesn't match any pattern.
 *  - Tapping a row forwards the chosen [WheelType].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WheelTypePickerSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun assertNodeExists(text: String) {
        // ModalBottomSheet content can layout off-screen in Robolectric without
        // scrolling, so assertIsDisplayed() is too strict. We assert the node
        // exists in the merged semantics tree (sufficient for the "renders all
        // options" smoke contract).
        val nodes = composeTestRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
        assert(nodes.fetchSemanticsNodes().isNotEmpty()) {
            "Expected node with text containing '$text' to exist"
        }
    }

    private fun assertNodeAbsent(text: String) {
        val nodes = composeTestRule.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
        assert(nodes.fetchSemanticsNodes().isEmpty()) {
            "Did not expect a node with text containing '$text'"
        }
    }

    @Test
    fun `picker shows every selectable wheel type`() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTypePickerSheet(
                    deviceName = null,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        // Sanity: each pickable label appears at least once. Substring match
        // tolerates the "(...)" suffixes the picker uses to disambiguate
        // protocol pairs (e.g. "Ninebot (legacy)" vs "Ninebot Z (Z10+)").
        val expectedLabels = listOf(
            "KingSong",
            "Begode",
            "Veteran",
            "Leaperkim",
            "InMotion V1",
            "InMotion V2",
            "Ninebot (legacy)",
            "Ninebot Z",
        )
        for (label in expectedLabels) {
            assertNodeExists(label)
        }
    }

    @Test
    fun `Likely badge appears when name matches a known pattern`() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTypePickerSheet(
                    deviceName = "S22-3A0F", // catalog → KingSong
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        assertNodeExists("Likely")
    }

    @Test
    fun `no Likely badge when name doesn't match any pattern`() {
        composeTestRule.setContent {
            MaterialTheme {
                WheelTypePickerSheet(
                    deviceName = "MysteryWheel-9000",
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        assertNodeAbsent("Likely")
    }

    @Test
    fun `tapping a row forwards the chosen wheel type`() {
        var picked: WheelType? = null
        composeTestRule.setContent {
            MaterialTheme {
                WheelTypePickerSheet(
                    deviceName = null,
                    onConfirm = { picked = it },
                    onDismiss = {},
                )
            }
        }

        // KingSong is the first option and is reliably on-screen even without
        // scrolling. performClick() needs the node to be displayed, so we
        // pick the safest option for tap testing.
        composeTestRule.onNodeWithText("KingSong", substring = true).performClick()
        composeTestRule.runOnIdle {
            assert(picked == WheelType.KINGSONG) { "Expected KINGSONG, got $picked" }
        }
    }
}
