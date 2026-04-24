package org.freewheel.core.location

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChargingStationRepositoryTest {

    @Test
    fun refreshNearby_firstCallFetches() = runTest {
        val source = FakeSource()
        val repo = ChargingStationRepository(source)

        repo.refreshNearby(37.7749, -122.4194)

        assertEquals(1, source.callCount)
        assertEquals(source.stations, repo.stations.value)
    }

    @Test
    fun refreshNearby_smallPanReusesCache() = runTest {
        val source = FakeSource()
        val repo = ChargingStationRepository(source)

        repo.refreshNearby(37.7749, -122.4194)
        // ~1.1 km north — under the refresh threshold
        repo.refreshNearby(37.7849, -122.4194)

        assertEquals(1, source.callCount)
    }

    @Test
    fun refreshNearby_largePanRefetches() = runTest {
        val source = FakeSource()
        val repo = ChargingStationRepository(source, refetchThresholdKm = 5.0)

        repo.refreshNearby(37.7749, -122.4194)
        // ~11 km north — over the 5 km threshold
        repo.refreshNearby(37.8749, -122.4194)

        assertEquals(2, source.callCount)
    }

    @Test
    fun refreshNearby_swallowsFetchFailures() = runTest {
        val source = FakeSource(failOnce = true)
        val repo = ChargingStationRepository(source)

        // Should not throw
        repo.refreshNearby(37.7749, -122.4194)
        assertTrue(repo.stations.value.isEmpty())

        repo.refreshNearby(37.7749, -122.4194)
        assertEquals(source.stations, repo.stations.value)
    }

    private class FakeSource(
        var stations: List<ChargingStation> = listOf(
            ChargingStation(
                id = "1",
                name = "Fake",
                latitude = 37.7749,
                longitude = -122.4194,
                address = null,
                operator = null,
                accessType = AccessType.Public,
                connectors = listOf(ConnectorType.Type2Mennekes),
            ),
        ),
        private var failOnce: Boolean = false,
    ) : ChargingStationSource {
        var callCount: Int = 0

        override suspend fun fetchNearby(latitude: Double, longitude: Double): List<ChargingStation> {
            callCount++
            if (failOnce) {
                failOnce = false
                throw ChargingStationFetchException("boom")
            }
            return stations
        }
    }
}
