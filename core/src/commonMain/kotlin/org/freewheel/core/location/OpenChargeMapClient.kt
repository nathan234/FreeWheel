package org.freewheel.core.location

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class ChargingStationFetchException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class OpenChargeMapClient(
    private val apiKey: String,
    private val httpClient: HttpClient = defaultClient(),
    private val baseUrl: String = "https://api.openchargemap.io/v3/poi",
) {

    suspend fun fetchNearby(
        latitude: Double,
        longitude: Double,
        radiusKm: Int = DEFAULT_RADIUS_KM,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): List<ChargingStation> {
        val response: HttpResponse = try {
            httpClient.get(baseUrl) {
                parameter("key", apiKey)
                parameter("output", "json")
                parameter("latitude", latitude)
                parameter("longitude", longitude)
                parameter("distance", radiusKm)
                parameter("distanceunit", "KM")
                parameter("maxresults", maxResults)
                parameter("compact", true)
                parameter("verbose", false)
            }
        } catch (t: Throwable) {
            throw ChargingStationFetchException("OpenChargeMap request failed", t)
        }

        if (!response.status.isSuccess()) {
            throw ChargingStationFetchException(
                "OpenChargeMap returned ${response.status.value}",
            )
        }

        val pois: List<OcmPoi> = try {
            response.body()
        } catch (t: Throwable) {
            throw ChargingStationFetchException("Failed to parse OpenChargeMap response", t)
        }

        return pois.mapNotNull { it.toChargingStation() }
    }

    companion object {
        const val DEFAULT_RADIUS_KM: Int = 25
        const val DEFAULT_MAX_RESULTS: Int = 100

        private fun defaultClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }
}
