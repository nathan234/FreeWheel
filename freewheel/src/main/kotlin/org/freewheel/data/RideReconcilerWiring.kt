package org.freewheel.data

import java.io.File
import org.freewheel.core.logging.IndexEntry
import org.freewheel.core.logging.RideFile
import org.freewheel.core.logging.RideFileSystem
import org.freewheel.core.logging.RideIndex
import org.freewheel.core.logging.RideMetadata
import org.freewheel.core.logging.RideReconciler

/**
 * [RideIndex] backed by Room. All methods are blocking — the reconciler is
 * driven from a coroutine context that wraps it in Dispatchers.IO.
 */
class TripDaoRideIndex(private val tripDao: TripDao) : RideIndex {

    override fun list(): List<IndexEntry> =
        tripDao.getAll().map { IndexEntry(it.fileName) }

    override fun add(metadata: RideMetadata) {
        tripDao.insert(
            TripDataDbEntry(
                fileName = metadata.fileName,
                start = (metadata.startTimeMillis / 1000).toInt(),
                duration = (metadata.durationSeconds / 60).toInt(),
                maxSpeed = metadata.maxSpeedKmh.toFloat(),
                avgSpeed = metadata.avgSpeedKmh.toFloat(),
                maxCurrent = metadata.maxCurrentA.toFloat(),
                maxPower = metadata.maxPowerW.toFloat(),
                maxPwm = metadata.maxPwmPercent.toFloat(),
                distance = metadata.distanceMeters.toInt(),
                consumptionTotal = metadata.consumptionWh.toFloat(),
                consumptionByKm = metadata.consumptionWhPerKm.toFloat(),
            )
        )
    }

    override fun removeByFileName(fileName: String) {
        val existing = tripDao.getTripByFileName(fileName) ?: return
        tripDao.deleteDataById(existing.id.toLong())
    }
}

/**
 * [RideFileSystem] backed by a local directory of CSV files.
 */
class DirectoryRideFileSystem(private val dir: File) : RideFileSystem {

    override fun listCsvFiles(): List<RideFile> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.extension.equals("csv", ignoreCase = true) }
            ?.map { RideFile(it.name, it.length()) }
            ?: emptyList()
    }

    override fun readContent(fileName: String): String? {
        val f = File(dir, fileName)
        return if (f.exists()) runCatching { f.readText(Charsets.UTF_8) }.getOrNull() else null
    }
}

/** Convenience builder used at app startup. */
fun createRideReconciler(tripDao: TripDao, ridesDir: File): RideReconciler =
    RideReconciler(
        index = TripDaoRideIndex(tripDao),
        files = DirectoryRideFileSystem(ridesDir),
    )
