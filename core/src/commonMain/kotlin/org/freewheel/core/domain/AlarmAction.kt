package org.freewheel.core.domain

enum class AlarmAction(val value: Int) {
    PHONE_ONLY(0),
    PHONE_AND_WHEEL(1),
    ALL(2);

    val label: String get() = when (this) {
        PHONE_ONLY -> "Phone Only"
        PHONE_AND_WHEEL -> "Phone + Wheel"
        ALL -> "All"
    }

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: PHONE_ONLY
    }
}
