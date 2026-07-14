package org.freewheel.core.domain.profile

/**
 * Model-specific defaults for the Gotway/Begode protocol family.
 *
 * Begode controllers report frame-0 voltage on a 67.2 V base scale regardless of
 * pack voltage. A matched profile therefore lets the decoder scale voltage and
 * calculate a speed-based PWM fallback without requiring per-wheel setup.
 *
 * Name aliases are controller-reported names, not marketing-only names. Firmware
 * signatures use the two-letter prefix plus the first five version digits; the
 * final two digits are patch/build revisions.
 */
data class BegodeModelProfile(
    val displayName: String,
    val brand: String = "Begode",
    val fullVoltageV: Double,
    val lowVoltageV: Double? = null,
    val emptyVoltageV: Double? = null,
    val noLoadSpeedKmh: Double? = null,
    val smartBmsCount: Int = 0,
) {
    val voltageClass: BegodeVoltageClass?
        get() = BegodeVoltageClass.fromFullVoltage(fullVoltageV)
}

object BegodeModelCatalog {
    private data class Entry(
        val profile: BegodeModelProfile,
        val aliases: Set<String>,
        val firmwareSignatures: Set<String> = emptySet(),
    )

    private fun entry(
        displayName: String,
        fullVoltageV: Double,
        vararg aliases: String,
        brand: String = "Begode",
        lowVoltageV: Double? = null,
        emptyVoltageV: Double? = null,
        noLoadSpeedKmh: Double? = null,
        smartBmsCount: Int = 0,
        firmwareSignatures: Set<String> = emptySet(),
    ) = Entry(
        profile = BegodeModelProfile(
            displayName = displayName,
            brand = brand,
            fullVoltageV = fullVoltageV,
            lowVoltageV = lowVoltageV,
            emptyVoltageV = emptyVoltageV,
            noLoadSpeedKmh = noLoadSpeedKmh,
            smartBmsCount = smartBmsCount,
        ),
        aliases = (aliases.toSet() + displayName).mapTo(mutableSetOf()) { normalize(it) },
        firmwareSignatures = firmwareSignatures,
    )

    private val entries = listOf(
        entry("A1", 42.0, emptyVoltageV = 32.0),
        entry("A2", 84.0, emptyVoltageV = 62.0, noLoadSpeedKmh = 52.0, firmwareSignatures = gwCf("15110")),
        entry("A5", 84.0, emptyVoltageV = 62.0, noLoadSpeedKmh = 63.0),
        entry("ACM 16", 67.2),
        entry("ACM S+", 84.0),
        entry("Blitz", 134.4, lowVoltageV = 106.0, emptyVoltageV = 99.0, noLoadSpeedKmh = 150.0, smartBmsCount = 2, firmwareSignatures = gwCf("20351")),
        entry("Blitz Pro", 168.0, "Blitz PRO", lowVoltageV = 132.0, emptyVoltageV = 124.0, noLoadSpeedKmh = 155.0, smartBmsCount = 2),
        entry("C8", 84.0, emptyVoltageV = 62.0, noLoadSpeedKmh = 52.0),
        entry("Commander C30", 100.8, brand = "Extreme Bull", emptyVoltageV = 74.0, noLoadSpeedKmh = 97.0),
        entry("Commander C38", 100.8, brand = "Extreme Bull", emptyVoltageV = 74.0, noLoadSpeedKmh = 79.0),
        entry("Commander GT", 134.4, brand = "Extreme Bull", emptyVoltageV = 97.6, noLoadSpeedKmh = 112.0),
        entry("Commander Max", 168.0, "MAX", brand = "Extreme Bull", lowVoltageV = 120.0, emptyVoltageV = 116.0, noLoadSpeedKmh = 170.0),
        entry("Commander Mini", 134.4, "Commander mini", brand = "Extreme Bull", emptyVoltageV = 100.0, noLoadSpeedKmh = 107.0),
        entry("Commander Mini Pro", 134.4, "Commander Mini PRO", brand = "Extreme Bull", emptyVoltageV = 100.0, noLoadSpeedKmh = 107.0),
        entry("Commander Pro", 134.4, brand = "Extreme Bull", emptyVoltageV = 97.6, noLoadSpeedKmh = 112.0, firmwareSignatures = setOf("JN:20122")),
        entry("Commander Pro 50S", 134.4, "CommanderPRO50s", brand = "Extreme Bull", emptyVoltageV = 97.6, noLoadSpeedKmh = 120.0),
        entry("EX", 100.8, emptyVoltageV = 78.0, noLoadSpeedKmh = 83.0),
        entry("EX.N C30", 100.8, emptyVoltageV = 72.0, noLoadSpeedKmh = 97.0, firmwareSignatures = gwCf("20020")),
        entry("EX.N C38", 100.8, emptyVoltageV = 72.0, noLoadSpeedKmh = 80.0, firmwareSignatures = gwCf("20120")),
        entry("EX20S C30", 100.8, lowVoltageV = 78.0, emptyVoltageV = 76.0, noLoadSpeedKmh = 86.0, firmwareSignatures = gwCf("20030")),
        entry("EX20S C38", 100.8, lowVoltageV = 78.0, emptyVoltageV = 76.0, noLoadSpeedKmh = 79.0, firmwareSignatures = gwCf("20130")),
        entry("EX30 C40", 134.4, "EX30", emptyVoltageV = 99.2, noLoadSpeedKmh = 120.0, firmwareSignatures = gwCf("20250")),
        entry("Extreme", 134.4, "EXTREME", emptyVoltageV = 99.0, noLoadSpeedKmh = 108.0, smartBmsCount = 2, firmwareSignatures = gwCf("18250")),
        entry("ET Max", 168.0, "ET MAX", emptyVoltageV = 124.0, noLoadSpeedKmh = 180.0, smartBmsCount = 2, firmwareSignatures = gwCf("20260")),
        entry("Falcon", 100.8, emptyVoltageV = 72.0, noLoadSpeedKmh = 67.0, firmwareSignatures = gwCf("16210")),
        entry("Griffin", 151.2, brand = "Extreme Bull", lowVoltageV = 118.8, emptyVoltageV = 111.6, noLoadSpeedKmh = 147.0, smartBmsCount = 2),
        entry("GT Pro", 168.0, "GT PRO", brand = "Extreme Bull", emptyVoltageV = 124.0, noLoadSpeedKmh = 180.0, firmwareSignatures = setOf("JN:20260")),
        entry("Master", 134.4, lowVoltageV = 106.0, emptyVoltageV = 104.0, noLoadSpeedKmh = 112.0, firmwareSignatures = gwCf("20140", "20145", "20148", "20149", "20150", "20151")),
        entry("Master Pro", 134.4, "Master PRO", "Master pro 3", "Master PRO 3", emptyVoltageV = 99.2, noLoadSpeedKmh = 122.0, firmwareSignatures = gwCf("23040", "23250")),
        entry("Master X", 134.4, emptyVoltageV = 99.2, noLoadSpeedKmh = 122.0, firmwareSignatures = gwCf("23041")),
        entry("Monster Pro", 100.8, emptyVoltageV = 72.0, noLoadSpeedKmh = 106.0, firmwareSignatures = gwCf("24020")),
        entry("Mten 4", 84.0, "MTEN4", emptyVoltageV = 62.0, noLoadSpeedKmh = 56.0, firmwareSignatures = gwCf("10110")),
        entry("Mten 5", 84.0, "MTEN5", emptyVoltageV = 62.0, noLoadSpeedKmh = 71.0, firmwareSignatures = gwCf("12110")),
        entry("Mten Mini", 42.0, "Mten mini", emptyVoltageV = 31.0, noLoadSpeedKmh = 30.0, firmwareSignatures = gwCf("11210")),
        entry("Nikola Plus", 100.8, emptyVoltageV = 72.0, noLoadSpeedKmh = 82.0, firmwareSignatures = gwCf("17020")),
        entry("Panther", 168.0, lowVoltageV = 120.0, emptyVoltageV = 116.0, noLoadSpeedKmh = 170.0, smartBmsCount = 2),
        entry("RACE", 210.0, lowVoltageV = 165.0, emptyVoltageV = 155.0, noLoadSpeedKmh = 165.0, smartBmsCount = 2),
        entry("Rocket", 168.0, "ROCKET", brand = "Extreme Bull", lowVoltageV = 124.0, emptyVoltageV = 120.0),
        entry("RS C30", 100.8, emptyVoltageV = 78.0, noLoadSpeedKmh = 97.0, firmwareSignatures = gwCf("19020", "19030", "19040")),
        entry("RS C38", 100.8, emptyVoltageV = 78.0, noLoadSpeedKmh = 79.0, firmwareSignatures = gwCf("19120", "19130")),
        entry("T4", 100.8, lowVoltageV = 79.0, emptyVoltageV = 72.0, noLoadSpeedKmh = 78.0, firmwareSignatures = gwCf("16121", "16122")),
        entry("T4 Pro", 100.8, emptyVoltageV = 72.0, noLoadSpeedKmh = 78.0, firmwareSignatures = gwCf("16125")),
        entry("Tesla T3", 84.0, emptyVoltageV = 65.0, noLoadSpeedKmh = 68.0, firmwareSignatures = gwCf("16010", "16110")),
        entry("X-Men C30", 100.8, brand = "Extreme Bull", emptyVoltageV = 78.0, noLoadSpeedKmh = 97.0),
        entry("X-Men C38", 100.8, brand = "Extreme Bull", emptyVoltageV = 78.0, noLoadSpeedKmh = 79.0),
        entry("X-Way (134 V)", 134.4, "XWAY-134", lowVoltageV = 105.6, emptyVoltageV = 99.2, smartBmsCount = 2),
        entry("X-Way (168 V)", 168.0, "XWAY-168", lowVoltageV = 132.0, emptyVoltageV = 124.0, smartBmsCount = 2),
    )

    fun match(model: String, firmware: String): BegodeModelProfile? {
        val normalizedModel = normalize(model)
        if (normalizedModel.isNotEmpty()) {
            entries.firstOrNull { normalizedModel in it.aliases }?.let { return it.profile }
        }

        val signature = firmwareSignature(firmware) ?: return null
        return entries.firstOrNull { signature in it.firmwareSignatures }?.profile
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun firmwareSignature(firmware: String): String? {
        val value = firmware.trim().uppercase()
        if (value.length < 7) return null
        val prefix = value.take(2)
        if (prefix !in setOf("GW", "JL", "JN", "CF", "BF")) return null
        val code = value.drop(2).take(5)
        if (code.length != 5 || code.any { !it.isDigit() }) return null
        return "$prefix:$code"
    }

    private fun gwCf(vararg codes: String): Set<String> = buildSet {
        for (code in codes) {
            add("GW:$code")
            add("JL:$code")
            add("CF:$code")
        }
    }
}
