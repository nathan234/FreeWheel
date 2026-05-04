package org.freewheel.core.logging

import org.freewheel.core.diagnostics.Diagnostics
import org.freewheel.core.utils.currentTimeMillis

/**
 * Bidirectional reconciler between the rides directory (source of truth)
 * and the per-platform index. Designed to be called once at app launch.
 *
 * Two passes:
 *  1. **Recover orphans** — every CSV file that isn't in the index gets
 *     parsed via [RideRecovery.deriveMetadata] and inserted (subject to
 *     a sanity threshold).
 *  2. **Drop phantoms** — every index entry whose CSV file is gone gets
 *     removed.
 *
 * Each step emits a [Diagnostics] event so the developer can see exactly
 * what happened on every boot.
 *
 * The reconciler delegates I/O to platform adapters; it owns no file or
 * database handles itself, which keeps it pure-KMP and easy to unit-test.
 */
class RideReconciler(
    private val index: RideIndex,
    private val files: RideFileSystem,
    private val sanitySamples: Int = 5,
    private val sanityDurationSec: Long = 10,
) {
    data class Result(
        val csvCount: Int,
        val indexCount: Int,
        val recovered: Int,
        val phantom: Int,
        val skipped: Int,
        val corrupt: Int,
        val elapsedMs: Long,
    )

    fun reconcile(): Result {
        val started = currentTimeMillis()
        Diagnostics.recoveryPassStart()

        val csvFiles = files.listCsvFiles()
        val indexEntries = index.list()
        val knownInIndex = indexEntries.map { it.fileName }.toSet()
        val csvFileNames = csvFiles.map { it.fileName }.toSet()

        var recovered = 0
        var skipped = 0
        var corrupt = 0
        for (csv in csvFiles) {
            if (csv.fileName in knownInIndex) continue

            val content = files.readContent(csv.fileName)
            if (content == null || content.isBlank()) {
                Diagnostics.corrupt(csv.fileName, "empty or unreadable")
                corrupt++
                continue
            }
            val meta = RideRecovery.deriveMetadata(content, csv.fileName)
            if (meta == null) {
                Diagnostics.corrupt(csv.fileName, "no usable header/rows")
                corrupt++
                continue
            }
            if (meta.sampleCount < sanitySamples || meta.durationSeconds < sanityDurationSec) {
                Diagnostics.skipped(
                    csv.fileName,
                    "below threshold",
                    meta.sampleCount,
                    meta.durationSeconds,
                )
                skipped++
                continue
            }

            try {
                index.add(meta)
            } catch (e: Throwable) {
                Diagnostics.corrupt(csv.fileName, "index insert failed: ${e.message}")
                corrupt++
                continue
            }
            Diagnostics.recovered(
                csv.fileName,
                meta.sampleCount,
                meta.durationSeconds,
                meta.distanceMeters,
            )
            recovered++
        }

        var phantom = 0
        for (entry in indexEntries) {
            if (entry.fileName !in csvFileNames) {
                try {
                    index.removeByFileName(entry.fileName)
                    Diagnostics.phantom(entry.fileName, "file-missing")
                    phantom++
                } catch (e: Throwable) {
                    Diagnostics.phantom(entry.fileName, "remove-failed: ${e.message}")
                }
            }
        }

        val elapsed = currentTimeMillis() - started
        Diagnostics.recoveryPassEnd(recovered, phantom, skipped, corrupt, elapsed)

        return Result(
            csvCount = csvFiles.size,
            indexCount = indexEntries.size,
            recovered = recovered,
            phantom = phantom,
            skipped = skipped,
            corrupt = corrupt,
            elapsedMs = elapsed,
        )
    }
}

/**
 * Read/write side of the per-platform ride index. iOS implements this on
 * top of [RideStore]; Android implements it on top of [TripDao].
 */
interface RideIndex {
    fun list(): List<IndexEntry>
    fun add(metadata: RideMetadata)
    fun removeByFileName(fileName: String)
}

data class IndexEntry(val fileName: String)

/**
 * Read side of the rides directory. Implementations list .csv files and
 * provide content on demand. Implementations should not throw on missing
 * files — return null/empty list instead.
 */
interface RideFileSystem {
    fun listCsvFiles(): List<RideFile>
    fun readContent(fileName: String): String?
}

data class RideFile(val fileName: String, val sizeBytes: Long)
