package org.freewheel.core.location

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenChargeMapClientTest {

    @Test
    fun fetchNearby_requestsExpectedEndpointWithParams() = runTest {
        lateinit var capturedUrl: String
        val client = buildClient(SAMPLE_RESPONSE) { request ->
            capturedUrl = request.url.toString()
        }

        client.fetchNearby(latitude = 37.7749, longitude = -122.4194, radiusKm = 25)

        assertContains(capturedUrl, "api.openchargemap.io")
        assertContains(capturedUrl, "latitude=37.7749")
        assertContains(capturedUrl, "longitude=-122.4194")
        assertContains(capturedUrl, "distance=25")
        assertContains(capturedUrl, "distanceunit=KM")
        assertContains(capturedUrl, "key=test-api-key")
    }

    @Test
    fun fetchNearby_parsesStationsWithEucRelevantConnectors() = runTest {
        val client = buildClient(SAMPLE_RESPONSE)

        val stations = client.fetchNearby(latitude = 37.7749, longitude = -122.4194, radiusKm = 25)

        // SAMPLE_RESPONSE has 3 POIs: one Type 2 (kept), one CCS-only (dropped),
        // one with mixed Type 1 + Tesla (kept, only Type 1 retained).
        assertEquals(2, stations.size)
        val type2 = stations.first { it.id == "1001" }
        assertEquals("Downtown Public Lot", type2.name)
        assertEquals(listOf(ConnectorType.Type2Mennekes), type2.connectors)
        assertEquals(AccessType.Public, type2.accessType)

        val mixed = stations.first { it.id == "1003" }
        assertEquals(listOf(ConnectorType.Type1J1772), mixed.connectors)
    }

    @Test
    fun fetchNearby_dropsStationsWithoutCoordinates() = runTest {
        val client = buildClient(NO_COORDS_RESPONSE)

        val stations = client.fetchNearby(latitude = 37.7749, longitude = -122.4194, radiusKm = 25)

        assertTrue(stations.isEmpty(), "POIs missing lat/lng should be dropped")
    }

    @Test
    fun fetchNearby_composesAddressFromParts() = runTest {
        val client = buildClient(SAMPLE_RESPONSE)

        val stations = client.fetchNearby(latitude = 37.7749, longitude = -122.4194, radiusKm = 25)
        val station = stations.first { it.id == "1001" }

        assertNotNull(station.address)
        assertContains(station.address!!, "100 Market St")
        assertContains(station.address, "San Francisco")
    }

    @Test
    fun fetchNearby_returnsEmptyListOnEmptyPayload() = runTest {
        val client = buildClient("[]")

        val stations = client.fetchNearby(latitude = 0.0, longitude = 0.0, radiusKm = 25)

        assertTrue(stations.isEmpty())
    }

    @Test
    fun fetchNearby_throwsOnServerError() = runTest {
        val client = buildClient(
            body = """{"error":"rate limited"}""",
            status = HttpStatusCode.TooManyRequests,
        )

        assertFailsWith<ChargingStationFetchException> {
            client.fetchNearby(latitude = 0.0, longitude = 0.0, radiusKm = 25)
        }
    }

    @Test
    fun fetchNearby_skipsPoisWhoseOnlyConnectorIsUnsupported() = runTest {
        val client = buildClient(CCS_ONLY_RESPONSE)

        val stations = client.fetchNearby(latitude = 0.0, longitude = 0.0, radiusKm = 25)

        assertTrue(stations.isEmpty())
    }

    @Test
    fun fetchNearby_includesDistanceWhenProvided() = runTest {
        val client = buildClient(SAMPLE_RESPONSE)

        val stations = client.fetchNearby(latitude = 37.7749, longitude = -122.4194, radiusKm = 25)

        val withDistance = stations.first { it.id == "1001" }
        assertEquals(0.4, withDistance.distanceKm)
        val withoutDistance = stations.first { it.id == "1003" }
        assertNull(withoutDistance.distanceKm)
    }

    private fun buildClient(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        onRequest: (io.ktor.client.request.HttpRequestData) -> Unit = {},
    ): OpenChargeMapClient {
        val engine = MockEngine { request ->
            onRequest(request)
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf("Content-Type", "application/json"),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        return OpenChargeMapClient(apiKey = "test-api-key", httpClient = httpClient)
    }

    companion object {
        // Connection type IDs:
        //   1 = Type 1 (J1772)
        //   25 = Type 2 (Mennekes, socket only)
        //   32 = Type 2 (tethered)
        //   33 = CCS (Combo 2) — dropped
        //   27 = Tesla — dropped
        private val SAMPLE_RESPONSE = """
            [
              {
                "ID": 1001,
                "UUID": "a",
                "AddressInfo": {
                  "Title": "Downtown Public Lot",
                  "AddressLine1": "100 Market St",
                  "Town": "San Francisco",
                  "StateOrProvince": "CA",
                  "Latitude": 37.7749,
                  "Longitude": -122.4194,
                  "Distance": 0.4
                },
                "OperatorInfo": { "Title": "ChargePoint" },
                "UsageType": { "IsPrivateIndividuals": false, "Title": "Public" },
                "Connections": [
                  { "ConnectionTypeID": 25, "PowerKW": 7.2 }
                ]
              },
              {
                "ID": 1002,
                "AddressInfo": {
                  "Title": "Freeway Fast Charger",
                  "Latitude": 37.80,
                  "Longitude": -122.40
                },
                "Connections": [
                  { "ConnectionTypeID": 33, "PowerKW": 150 }
                ]
              },
              {
                "ID": 1003,
                "AddressInfo": {
                  "Title": "Hotel Lot",
                  "Latitude": 37.77,
                  "Longitude": -122.42
                },
                "UsageType": { "Title": "Public" },
                "Connections": [
                  { "ConnectionTypeID": 1, "PowerKW": 6.6 },
                  { "ConnectionTypeID": 27, "PowerKW": 11.5 }
                ]
              }
            ]
        """.trimIndent()

        private val NO_COORDS_RESPONSE = """
            [
              {
                "ID": 2001,
                "AddressInfo": { "Title": "Missing Coords" },
                "Connections": [ { "ConnectionTypeID": 25 } ]
              }
            ]
        """.trimIndent()

        private val CCS_ONLY_RESPONSE = """
            [
              {
                "ID": 3001,
                "AddressInfo": {
                  "Title": "CCS Only",
                  "Latitude": 37.0,
                  "Longitude": -122.0
                },
                "Connections": [ { "ConnectionTypeID": 33 } ]
              }
            ]
        """.trimIndent()
    }
}
