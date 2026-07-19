package com.dynashuttle.services.routing

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.math.ceil

@Serializable
private data class TomTomRouteResponse(val routes: List<TomTomRoute> = emptyList())

@Serializable
private data class TomTomRoute(val summary: TomTomRouteSummary)

@Serializable
private data class TomTomRouteSummary(val travelTimeInSeconds: Int)

/**
 * Real traffic-aware ETA via TomTom's Routing API (calculateRoute, v1) —
 * see master plan Section 5, item 9: "replace flat-speed ETA estimate with
 * real traffic-aware routing." Originally scoped around Google Routes API in
 * the tech stack section; switched to TomTom because its free tier needs no
 * billing/credit card upfront, which mattered more at this pilot's stage than
 * Google's slightly larger free quota.
 *
 * API contract (per TomTom's documentation): GET request to
 * `/routing/1/calculateRoute/{fromLat},{fromLng}:{toLat},{toLng}/json`
 * with `key` and `traffic=true` query params. `traffic=true` is what makes
 * this "real traffic-aware routing" rather than just "a different flat-speed
 * guess" — the response's travelTimeInSeconds already factors in current
 * conditions. Response shape has many more fields than modeled here
 * (legs, sections, geometry, etc.) — only [TomTomRouteResponse] and its two
 * nested classes are declared, and Json is configured to ignore anything else,
 * since nothing else is needed for an ETA number.
 *
 * Falls back to [fallback] (a [HaversineEtaEstimator] by default) on ANY
 * failure — network error, timeout, non-2xx response, unexpected JSON shape,
 * missing/invalid API key. Ride matching and the driver's stop list must never
 * go down because a third-party routing API had a bad moment; a
 * slightly-wrong flat-speed ETA is a far smaller problem than a 500 error on
 * /rides or /trips. Every fallback is logged at WARN so a pattern of failures
 * is visible in the logs without surfacing as a user-facing error.
 */
class TomTomEtaEstimator(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val fallback: EtaEstimator = HaversineEtaEstimator()
) : EtaEstimator {

    private val logger = LoggerFactory.getLogger(TomTomEtaEstimator::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun estimateMinutes(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Int {
        return try {
            val url = "https://api.tomtom.com/routing/1/calculateRoute/$fromLat,$fromLng:$toLat,$toLng/json"

            val response = httpClient.get(url) {
                parameter("key", apiKey)
                parameter("traffic", "true")
            }

            if (!response.status.isSuccess()) {
                throw IllegalStateException("TomTom Routing API returned HTTP ${response.status.value}")
            }

            val parsed = json.decodeFromString<TomTomRouteResponse>(response.bodyAsText())
            val travelTimeSeconds = parsed.routes.firstOrNull()?.summary?.travelTimeInSeconds
                ?: throw IllegalStateException("TomTom response contained no routes")

            ceil(travelTimeSeconds / 60.0).toInt().coerceAtLeast(1)
        } catch (e: Exception) {
            logger.warn(
                "TomTom Routing API call failed for ({}, {}) -> ({}, {}): {} — falling back to flat-speed estimate",
                fromLat, fromLng, toLat, toLng, e.message
            )
            fallback.estimateMinutes(fromLat, fromLng, toLat, toLng)
        }
    }
}