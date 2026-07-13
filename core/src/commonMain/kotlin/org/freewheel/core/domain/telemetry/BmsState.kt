package org.freewheel.core.domain.telemetry

/**
 * BMS (Battery Management System) state containing snapshots from up to four battery packs.
 * Updates periodically (not on every telemetry frame).
 */
data class BmsState(
    val bms1: BmsSnapshot? = null,
    val bms2: BmsSnapshot? = null,
    val bms3: BmsSnapshot? = null,
    val bms4: BmsSnapshot? = null,
) {
    companion object {
        /** Swift-callable factory — Kotlin default-parameter constructors aren't visible from ObjC/Swift. */
        fun empty() = BmsState()
    }
}
