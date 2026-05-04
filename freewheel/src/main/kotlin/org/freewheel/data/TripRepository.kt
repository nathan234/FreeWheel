package org.freewheel.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TripRepository (val tripDao: TripDao) {

    suspend fun insertNewData(statisticDbEntity: TripDataDbEntry) {
        withContext(Dispatchers.IO) {
            tripDao.insert(statisticDbEntity)
        }
    }

    suspend fun getAllData(): List<TripDataDbEntry> {
        return withContext(Dispatchers.IO) {
            return@withContext tripDao.getAll()
        }
    }

    suspend fun getTripByFileName(fileName: String): TripDataDbEntry? {
        return withContext(Dispatchers.IO) {
            return@withContext tripDao.getTripByFileName(fileName)
        }
    }

    suspend fun getTripByRideId(rideId: String): TripDataDbEntry? {
        return withContext(Dispatchers.IO) {
            return@withContext tripDao.getTripByRideId(rideId)
        }
    }

    suspend fun upsertByRideId(entry: TripDataDbEntry) {
        withContext(Dispatchers.IO) {
            val existing = tripDao.getTripByRideId(entry.rideId)
            if (existing == null) {
                tripDao.insert(entry)
            } else {
                tripDao.update(entry.copy(id = existing.id))
            }
        }
    }

    suspend fun removeDataById(id: Long) {
        withContext(Dispatchers.IO) {
            tripDao.deleteDataById(id)
        }
    }
}