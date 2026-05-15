package org.freewheel.core.domain.settings

import org.freewheel.core.domain.identity.WheelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSettingsConfigTest {

    @Test
    fun sectionsReturnsExpectedCount() {
        val sections = AppSettingsConfig.sections()
        assertEquals(14, sections.size)
    }

    @Test
    fun sectionsAreInExpectedOrder() {
        val titles = AppSettingsConfig.sections().map { it.title }
        assertEquals(
            listOf(
                SettingsLabels.SECTION_UNITS,
                SettingsLabels.SECTION_ALARMS,
                SettingsLabels.SECTION_PWM_THRESHOLDS,
                SettingsLabels.SECTION_PRE_WARNINGS,
                SettingsLabels.SECTION_SPEED_ALARMS,
                SettingsLabels.SECTION_OTHER_ALARMS,
                SettingsLabels.SECTION_CONNECTION,
                SettingsLabels.SECTION_LOGGING,
                SettingsLabels.SECTION_AUTO_TORCH,
                AppSettingsConfig.WHEEL_SETTINGS_TITLE,
                "Interface",
                "Developer",
                SettingsLabels.SECTION_ABOUT,
                "" // Close App has empty title
            ),
            titles
        )
    }

    @Test
    fun everyAppSettingIdAppearsInAtLeastOneSection() {
        val allControls = AppSettingsConfig.sections().flatMap { it.controls }
        val usedIds = allControls.mapNotNull { it.settingId }.toSet()
        val allIds = AppSettingId.entries.toSet()
        assertEquals(allIds, usedIds, "Missing AppSettingIds: ${allIds - usedIds}")
    }

    // -- Visibility evaluator tests --

    private fun stateWith(
        boolValues: Map<AppSettingId, Boolean> = emptyMap(),
        intValues: Map<AppSettingId, Int> = emptyMap(),
        isConnected: Boolean = false,
        wheelType: WheelType = WheelType.Unknown
    ) = AppSettingsState(boolValues, intValues, isConnected, wheelType)

    @Test
    fun alwaysIsVisible() {
        assertTrue(
            AppSettingVisibilityEvaluator.isVisible(
                AppSettingVisibility.Always, stateWith()
            )
        )
    }

    @Test
    fun whenEnabledChecksBoolean() {
        val condition = AppSettingVisibility.WhenEnabled(AppSettingId.ALARMS_ENABLED)
        assertFalse(
            AppSettingVisibilityEvaluator.isVisible(condition, stateWith())
        )
        assertTrue(
            AppSettingVisibilityEvaluator.isVisible(
                condition,
                stateWith(boolValues = mapOf(AppSettingId.ALARMS_ENABLED to true))
            )
        )
    }

    @Test
    fun whenDisabledChecksBoolean() {
        val condition = AppSettingVisibility.WhenDisabled(AppSettingId.ALARMS_ENABLED)
        assertTrue(
            AppSettingVisibilityEvaluator.isVisible(condition, stateWith())
        )
        assertFalse(
            AppSettingVisibilityEvaluator.isVisible(
                condition,
                stateWith(boolValues = mapOf(AppSettingId.ALARMS_ENABLED to true))
            )
        )
    }

    @Test
    fun allOfRequiresAllConditions() {
        val condition = AppSettingVisibility.AllOf(
            listOf(
                AppSettingVisibility.WhenEnabled(AppSettingId.ALARMS_ENABLED),
                AppSettingVisibility.WhenEnabled(AppSettingId.PWM_BASED_ALARMS)
            )
        )
        // Only alarms enabled, PWM explicitly off → false
        assertFalse(
            AppSettingVisibilityEvaluator.isVisible(
                condition,
                stateWith(
                    boolValues = mapOf(
                        AppSettingId.ALARMS_ENABLED to true,
                        AppSettingId.PWM_BASED_ALARMS to false
                    )
                )
            )
        )
        // Both enabled → true
        assertTrue(
            AppSettingVisibilityEvaluator.isVisible(
                condition,
                stateWith(
                    boolValues = mapOf(
                        AppSettingId.ALARMS_ENABLED to true,
                        AppSettingId.PWM_BASED_ALARMS to true
                    )
                )
            )
        )
    }

    @Test
    fun whenAnyNonZeroChecksMultipleInts() {
        val condition = AppSettingVisibility.WhenAnyNonZero(
            listOf(AppSettingId.WARNING_SPEED, AppSettingId.WARNING_PWM)
        )
        assertFalse(
            AppSettingVisibilityEvaluator.isVisible(condition, stateWith())
        )
        assertTrue(
            AppSettingVisibilityEvaluator.isVisible(
                condition,
                stateWith(intValues = mapOf(AppSettingId.WARNING_SPEED to 5))
            )
        )
        assertTrue(
            AppSettingVisibilityEvaluator.isVisible(
                condition,
                stateWith(intValues = mapOf(AppSettingId.WARNING_PWM to 10))
            )
        )
    }

    @Test
    fun whenWheelTypeMatchesTypes() {
        val condition = AppSettingVisibility.WhenWheelType(
            setOf(WheelType.VETERAN, WheelType.LEAPERKIM)
        )
        assertFalse(
            AppSettingVisibilityEvaluator.isVisible(
                condition, stateWith(wheelType = WheelType.KINGSONG)
            )
        )
        assertTrue(
            AppSettingVisibilityEvaluator.isVisible(
                condition, stateWith(wheelType = WheelType.VETERAN)
            )
        )
    }

    @Test
    fun whenConnectedChecksConnectionState() {
        assertFalse(
            AppSettingVisibilityEvaluator.isVisible(
                AppSettingVisibility.WhenConnected, stateWith(isConnected = false)
            )
        )
        assertTrue(
            AppSettingVisibilityEvaluator.isVisible(
                AppSettingVisibility.WhenConnected, stateWith(isConnected = true)
            )
        )
    }

    // -- Display helper tests --

    @Test
    fun sliderDisplayValueConvertsSpeed() {
        val slider = AppSettingSpec.Slider(
            label = "Test", min = 0, max = 100, unit = "",
            settingId = AppSettingId.ALARM_1_SPEED,
            unitCategory = org.freewheel.core.domain.dashboard.UnitCategory.SPEED
        )
        assertEquals(29, slider.displayValue(29, useMph = false, useFahrenheit = false))
        assertEquals(18, slider.displayValue(29, useMph = true, useFahrenheit = false))
    }

    @Test
    fun sliderDisplayValueConvertsTemperature() {
        val slider = AppSettingSpec.Slider(
            label = "Test", min = 0, max = 200, unit = "",
            settingId = AppSettingId.ALARM_TEMPERATURE,
            unitCategory = org.freewheel.core.domain.dashboard.UnitCategory.TEMPERATURE
        )
        assertEquals(80, slider.displayValue(80, useMph = false, useFahrenheit = false))
        assertEquals(176, slider.displayValue(80, useMph = false, useFahrenheit = true))
    }

    @Test
    fun sliderDisplayUnitAdaptsToPreference() {
        val speedSlider = AppSettingSpec.Slider(
            label = "Test", min = 0, max = 100, unit = "",
            settingId = AppSettingId.ALARM_1_SPEED,
            unitCategory = org.freewheel.core.domain.dashboard.UnitCategory.SPEED
        )
        assertEquals("km/h", speedSlider.displayUnit(useMph = false, useFahrenheit = false))
        assertEquals("mph", speedSlider.displayUnit(useMph = true, useFahrenheit = false))

        val tempSlider = AppSettingSpec.Slider(
            label = "Test", min = 0, max = 80, unit = "",
            settingId = AppSettingId.ALARM_TEMPERATURE,
            unitCategory = org.freewheel.core.domain.dashboard.UnitCategory.TEMPERATURE
        )
        assertEquals("\u00B0C", tempSlider.displayUnit(useMph = false, useFahrenheit = false))
        assertEquals("\u00B0F", tempSlider.displayUnit(useMph = false, useFahrenheit = true))
    }

    @Test
    fun sliderWithNoUnitCategoryReturnRawUnit() {
        val slider = AppSettingSpec.Slider(
            label = "Test", min = 0, max = 100, unit = "%",
            settingId = AppSettingId.ALARM_FACTOR_1
        )
        assertEquals(50, slider.displayValue(50, useMph = true, useFahrenheit = true))
        assertEquals("%", slider.displayUnit(useMph = true, useFahrenheit = true))
    }
}
