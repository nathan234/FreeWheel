package org.freewheel.core.domain.dashboard

/**
 * Specifies how a metric's maximum value is determined for gauge rendering.
 *
 * Replaces the ambiguous `maxValue = 0.0` sentinel:
 * - [Fixed]: a known maximum (e.g., Battery → 100%, Speed → 50 km/h)
 * - [Dynamic]: no known max; track the highest seen value at runtime
 * - [None]: not applicable (e.g., distance, limits — never shown as a gauge arc)
 */
sealed class MaxValueSpec {
    /** A fixed maximum value for gauge rendering. */
    data class Fixed(val value: Double) : MaxValueSpec() {
        init {
            require(value > 0) { "Fixed max value must be positive, got $value" }
        }
    }

    /** Dynamic maximum — gauge grows with observed values. */
    data class Dynamic(val minimumDefault: Double = 1000.0) : MaxValueSpec()

    /** No maximum — metric is not rendered as a gauge arc. */
    data object None : MaxValueSpec()

    /** Returns the fixed value if [Fixed], null otherwise. */
    fun fixedValueOrNull(): Double? = when (this) {
        is Fixed -> value
        is Dynamic -> null
        is None -> null
    }
}
