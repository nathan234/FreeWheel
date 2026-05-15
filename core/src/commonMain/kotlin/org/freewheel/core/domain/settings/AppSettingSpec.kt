package org.freewheel.core.domain.settings

import org.freewheel.core.domain.dashboard.UnitCategory
import org.freewheel.core.domain.identity.WheelType
import org.freewheel.core.utils.DisplayUtils

// ---------------------------------------------------------------------------
// Visibility conditions
// ---------------------------------------------------------------------------

/**
 * Data-driven visibility conditions for app settings.
 * Evaluable on both platforms without closures crossing the KMP-Swift boundary.
 */
sealed class AppSettingVisibility {
    /** Always visible. */
    data object Always : AppSettingVisibility()

    /** Visible when the given boolean setting is true. */
    data class WhenEnabled(val settingId: AppSettingId) : AppSettingVisibility()

    /** Visible when the given boolean setting is false. */
    data class WhenDisabled(val settingId: AppSettingId) : AppSettingVisibility()

    /** Visible when ALL conditions are true. */
    data class AllOf(val conditions: List<AppSettingVisibility>) : AppSettingVisibility()

    /** Visible when ANY of the listed int settings is greater than zero. */
    data class WhenAnyNonZero(val settingIds: List<AppSettingId>) : AppSettingVisibility()

    /** Visible when connected to a wheel of the given types. */
    data class WhenWheelType(val types: Set<WheelType>) : AppSettingVisibility()

    /** Visible when connected to any wheel. */
    data object WhenConnected : AppSettingVisibility()
}

// ---------------------------------------------------------------------------
// Visibility evaluator
// ---------------------------------------------------------------------------

/**
 * Current app state needed to evaluate [AppSettingVisibility] conditions.
 * Platform layers build this from their current preference values and connection state.
 */
data class AppSettingsState(
    val boolValues: Map<AppSettingId, Boolean>,
    val intValues: Map<AppSettingId, Int>,
    val isConnected: Boolean,
    val wheelType: WheelType
)

/**
 * Evaluates [AppSettingVisibility] conditions against current [AppSettingsState].
 */
object AppSettingVisibilityEvaluator {
    fun isVisible(condition: AppSettingVisibility, state: AppSettingsState): Boolean =
        when (condition) {
            is AppSettingVisibility.Always -> true
            is AppSettingVisibility.WhenEnabled ->
                state.boolValues[condition.settingId] ?: condition.settingId.defaultBool
            is AppSettingVisibility.WhenDisabled ->
                !(state.boolValues[condition.settingId] ?: condition.settingId.defaultBool)
            is AppSettingVisibility.AllOf ->
                condition.conditions.all { isVisible(it, state) }
            is AppSettingVisibility.WhenAnyNonZero ->
                condition.settingIds.any { id ->
                    (state.intValues[id] ?: id.defaultInt) > 0
                }
            is AppSettingVisibility.WhenWheelType ->
                state.wheelType in condition.types
            is AppSettingVisibility.WhenConnected ->
                state.isConnected
        }
}

// ---------------------------------------------------------------------------
// Setting spec (UI control descriptors)
// ---------------------------------------------------------------------------

/**
 * Describes a single UI control on the app settings screen.
 * Parallel to [ControlSpec] but keyed by [AppSettingId] instead of [SettingsCommandId].
 *
 * Both Android (Compose) and iOS (SwiftUI) render from these specs, ensuring
 * identical settings structure across platforms.
 */
sealed class AppSettingSpec {
    abstract val settingId: AppSettingId?
    abstract val visibility: AppSettingVisibility

    /** Boolean toggle for a preference. */
    data class Toggle(
        val label: String,
        override val settingId: AppSettingId,
        override val visibility: AppSettingVisibility = AppSettingVisibility.Always
    ) : AppSettingSpec()

    /** Picker from a list of options (stored as int index). */
    data class Picker(
        val label: String,
        val options: List<String>,
        override val settingId: AppSettingId,
        override val visibility: AppSettingVisibility = AppSettingVisibility.Always
    ) : AppSettingSpec()

    /** Slider for an integer range. */
    data class Slider(
        val label: String,
        val min: Int,
        val max: Int,
        /** Base unit string (e.g., "%", "A"). Ignored when [unitCategory] handles display. */
        val unit: String,
        override val settingId: AppSettingId,
        /** When non-NONE, display value and unit adapt to user's unit preference. */
        val unitCategory: UnitCategory = UnitCategory.NONE,
        override val visibility: AppSettingVisibility = AppSettingVisibility.Always
    ) : AppSettingSpec()

    /**
     * Navigation link to a platform-specific destination.
     * [destinationId] is a contract string from [AppSettingsDestinations] that each platform
     * maps to its own navigation action.
     */
    data class NavLink(
        val label: String,
        val destinationId: String,
        override val visibility: AppSettingVisibility = AppSettingVisibility.Always
    ) : AppSettingSpec() {
        override val settingId: AppSettingId? get() = null
    }

    /** Static informational row (read-only label + value resolved by platform). */
    data class StaticInfo(
        val label: String,
        val valueId: String,
        override val visibility: AppSettingVisibility = AppSettingVisibility.Always
    ) : AppSettingSpec() {
        override val settingId: AppSettingId? get() = null
    }

    /** External link opened in the system browser. */
    data class ExternalLink(
        val label: String,
        val url: String,
        override val visibility: AppSettingVisibility = AppSettingVisibility.Always
    ) : AppSettingSpec() {
        override val settingId: AppSettingId? get() = null
    }

    /** Action button (e.g., "Close App"). Platform maps [actionId] to its handler. */
    data class ActionButton(
        val label: String,
        val actionId: String,
        val isDestructive: Boolean = false,
        override val visibility: AppSettingVisibility = AppSettingVisibility.Always
    ) : AppSettingSpec() {
        override val settingId: AppSettingId? get() = null
    }
}

// ---------------------------------------------------------------------------
// Section
// ---------------------------------------------------------------------------

/**
 * A section of app settings with optional footer text.
 */
data class AppSettingsSection(
    val title: String,
    val controls: List<AppSettingSpec>,
    val footer: String? = null,
    val visibility: AppSettingVisibility = AppSettingVisibility.Always
)

// ---------------------------------------------------------------------------
// Display helpers for unit-adaptive sliders
// ---------------------------------------------------------------------------

/** Returns the display value for a slider, converting units if needed. */
fun AppSettingSpec.Slider.displayValue(storedValue: Int, useMph: Boolean, useFahrenheit: Boolean): Int =
    when (unitCategory) {
        UnitCategory.SPEED -> DisplayUtils.convertSpeed(storedValue.toDouble(), useMph).toInt()
        UnitCategory.TEMPERATURE -> DisplayUtils.convertTemp(storedValue.toDouble(), useFahrenheit).toInt()
        else -> storedValue
    }

/** Returns the display unit string for a slider, adapting to user preference. */
fun AppSettingSpec.Slider.displayUnit(useMph: Boolean, useFahrenheit: Boolean): String =
    when (unitCategory) {
        UnitCategory.SPEED -> DisplayUtils.speedUnit(useMph)
        UnitCategory.TEMPERATURE -> DisplayUtils.temperatureUnit(useFahrenheit)
        else -> unit
    }
