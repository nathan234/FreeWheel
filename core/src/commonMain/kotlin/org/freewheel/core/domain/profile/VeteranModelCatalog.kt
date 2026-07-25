package org.freewheel.core.domain.profile

/** Strength of the evidence behind a Veteran-family state-of-charge curve. */
enum class WheelSocSource {
    MANUFACTURER_TABLE,
    MODEL_CLASS_FALLBACK;

    val displayName: String
        get() = when (this) {
            MANUFACTURER_TABLE -> "Manufacturer table"
            MODEL_CLASS_FALLBACK -> "Model-class fallback"
        }
}

/** App-owned identity and battery metadata for Leaperkim and Nosfet wheels. */
data class VeteranModelProfile(
    val modelVersion: Int,
    val displayName: String,
    val fullVoltageV: Double,
    val seriesCellCount: Int,
    val socSource: WheelSocSource,
    val aliases: Set<String>,
)

/**
 * Shared Veteran-family model metadata.
 *
 * Nosfet model/version identities are retained for compatibility, but the archived
 * manufacturer APK does not embed their downloaded SOC tables. Their voltage-class
 * curves therefore remain explicit model-class fallbacks. The 504/Xeno mapping is
 * additionally a provisional sequential-family inference.
 */
object VeteranModelCatalog {
    private fun profile(
        modelVersion: Int,
        displayName: String,
        fullVoltageV: Double,
        seriesCellCount: Int,
        socSource: WheelSocSource = WheelSocSource.MANUFACTURER_TABLE,
        vararg aliases: String,
    ) = VeteranModelProfile(
        modelVersion = modelVersion,
        displayName = displayName,
        fullVoltageV = fullVoltageV,
        seriesCellCount = seriesCellCount,
        socSource = socSource,
        aliases = (aliases.toSet() + displayName).mapTo(mutableSetOf(), ::normalize),
    )

    private val profiles = listOf(
        profile(1, "Leaperkim Sherman", 100.8, 24, aliases = arrayOf("Veteran Sherman")),
        profile(2, "Leaperkim Abrams", 100.8, 24, aliases = arrayOf("Veteran Abrams")),
        profile(3, "Leaperkim Sherman S", 100.8, 24, aliases = arrayOf("Veteran Sherman S")),
        profile(4, "Leaperkim Patton", 126.0, 30, aliases = arrayOf("Veteran Patton")),
        profile(5, "Leaperkim Lynx", 151.2, 36, aliases = arrayOf("Veteran Lynx")),
        profile(6, "Leaperkim Sherman L", 151.2, 36, aliases = arrayOf("Veteran Sherman L")),
        profile(7, "Leaperkim Patton S", 126.0, 30, aliases = arrayOf("Veteran Patton S")),
        profile(
            8,
            "Leaperkim Oryx",
            176.4,
            42,
            WheelSocSource.MODEL_CLASS_FALLBACK,
            "Veteran Oryx",
        ),
        profile(9, "Leaperkim Lynx S", 151.2, 36, aliases = arrayOf("Veteran Lynx S")),
        profile(42, "Nosfet Apex", 151.2, 36, WheelSocSource.MODEL_CLASS_FALLBACK),
        profile(43, "Nosfet Aero", 126.0, 30, WheelSocSource.MODEL_CLASS_FALLBACK),
        profile(
            44,
            "Nosfet Aeon",
            151.2,
            36,
            WheelSocSource.MODEL_CLASS_FALLBACK,
        ),
        profile(
            45,
            "Nosfet Xeno",
            126.0,
            30,
            WheelSocSource.MODEL_CLASS_FALLBACK,
        ),
    )

    fun matchModelVersion(modelVersion: Int): VeteranModelProfile? =
        profiles.firstOrNull { it.modelVersion == modelVersion }

    fun matchManufacturerVersion(manufacturerVersion: Int): VeteranModelProfile? =
        matchModelVersion(
            when (manufacturerVersion) {
                501 -> 42
                502 -> 43
                503 -> 44
                504 -> 45 // provisional sequential-family inference
                else -> manufacturerVersion
            },
        )

    fun matchName(name: String): VeteranModelProfile? {
        val normalized = normalize(name)
        return profiles.firstOrNull { normalized in it.aliases }
    }

    private fun normalize(value: String): String = value
        .uppercase()
        .filter(Char::isLetterOrDigit)
}
