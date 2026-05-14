package org.freewheel.core.domain.identity

/**
 * Wheel identity information that is set once per connection.
 * Set once per connection — avoids triggering UI updates on every telemetry frame.
 */
data class WheelIdentity(
    val wheelType: WheelType = WheelType.Unknown,
    val name: String = "",
    val model: String = "",
    val modeStr: String = "",
    val version: String = "",
    val serialNumber: String = "",
    val btName: String = "",
    /** Firmware-derived brand override (e.g. "Extreme Bull" for JN-prefix Gotway firmware). */
    val brand: String = ""
) {
    companion object {
        /** Swift-callable factory — Kotlin default-parameter constructors aren't visible from ObjC/Swift. */
        fun empty() = WheelIdentity()
    }

    val displayName: String get() {
        val effectiveBrand = brand.ifEmpty { wheelType.displayName }
        val label = model.ifEmpty { name }.ifEmpty { btName }
        if (label.isEmpty()) return effectiveBrand.ifEmpty { "Dashboard" }
        if (effectiveBrand.isEmpty() || label.startsWith(effectiveBrand, ignoreCase = true)) return label
        return "$effectiveBrand $label"
    }
}
