package org.freewheel.core.domain

/**
 * Immutable set of capabilities for the connected wheel.
 * Constructed by the decoder from its capability map once firmware level is known.
 *
 * Capabilities are monotonically expanding within a session — once a command is
 * reported as supported, it is never removed. This prevents UI controls from
 * disappearing while the user is interacting with them.
 *
 * [isResolved] means the minimum identification needed to determine command
 * support has been received (e.g., mVer for Veteran, model + protoVer for IM2).
 * It does NOT mean all metadata (serial, BMS, etc.) is available.
 */
data class CapabilitySet(
    /** Commands this wheel supports. */
    val supportedCommands: Set<SettingsCommandId> = emptySet(),

    /** Per-command control replacements (e.g., different min/max for a Slider). */
    val controlOverrides: Map<SettingsCommandId, ControlSpec> = emptyMap(),

    /** Human-readable model name as detected by the decoder. */
    val detectedModel: String = "",

    /** Firmware version string. */
    val firmwareVersion: String = "",

    /** Decoder-specific firmware level (e.g., mVer for Veteran, protoVer for IM2). */
    val firmwareLevel: Int = 0,

    /** Whether capability resolution is complete. See class doc. */
    val isResolved: Boolean = false
) {
    companion object {
        /** Swift-callable factory — Kotlin default-parameter constructors aren't visible from ObjC/Swift. */
        fun empty(): CapabilitySet = CapabilitySet()
    }

    /** True if [commandId] is supported by this wheel. */
    fun supports(commandId: SettingsCommandId): Boolean =
        commandId in supportedCommands

    /**
     * Merge with a newer capability set, taking the union of supported commands.
     * Preserves monotonic expansion — commands are never removed.
     */
    fun mergeWith(newer: CapabilitySet): CapabilitySet = CapabilitySet(
        supportedCommands = supportedCommands + newer.supportedCommands,
        controlOverrides = controlOverrides + newer.controlOverrides,
        detectedModel = newer.detectedModel.ifEmpty { detectedModel },
        firmwareVersion = newer.firmwareVersion.ifEmpty { firmwareVersion },
        firmwareLevel = maxOf(firmwareLevel, newer.firmwareLevel),
        isResolved = isResolved || newer.isResolved
    )
}

/**
 * Maps a [SettingsCommandId] to the minimum firmware level that supports it.
 * Level 0 means "always supported" (no firmware gate).
 */
typealias CapabilityMap = Map<SettingsCommandId, Int>

/**
 * Build a [CapabilitySet] from a [CapabilityMap] at a given firmware level.
 */
fun CapabilityMap.resolveAt(
    firmwareLevel: Int,
    detectedModel: String = "",
    firmwareVersion: String = "",
    controlOverrides: Map<SettingsCommandId, ControlSpec> = emptyMap()
): CapabilitySet {
    val supported = filterValues { minLevel -> firmwareLevel >= minLevel }.keys
    return CapabilitySet(
        supportedCommands = supported,
        controlOverrides = controlOverrides,
        detectedModel = detectedModel,
        firmwareVersion = firmwareVersion,
        firmwareLevel = firmwareLevel,
        isResolved = true
    )
}
