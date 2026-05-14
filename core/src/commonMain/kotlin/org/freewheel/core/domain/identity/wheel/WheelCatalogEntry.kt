package org.freewheel.core.domain.identity.wheel

import org.freewheel.core.domain.identity.WheelType

/**
 * One entry in [WheelCatalog]: a known wheel with the gauge top speed used to scale
 * the speedometer red zone.
 *
 * [nameTokens] are uppercased substrings searched against the wheel's identity strings
 * (firmware version, decoded model, brand, advertised BLE name). Longest-token-wins
 * within the same [wheelType] disambiguates families like "COMMANDER" vs "COMMANDER MAX".
 */
data class WheelCatalogEntry(
    val id: String,
    val displayName: String,
    val wheelType: WheelType,
    val nameTokens: List<String>,
    val topSpeedKmh: Double,
)
