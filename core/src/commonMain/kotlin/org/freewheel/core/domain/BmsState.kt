package org.freewheel.core.domain

/**
 * BMS (Battery Management System) state containing snapshots from up to two battery packs.
 * Updates periodically (not on every telemetry frame).
 */
data class BmsState(
    val bms1: BmsSnapshot? = null,
    val bms2: BmsSnapshot? = null
)
