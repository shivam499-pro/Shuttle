package com.dynashuttle.services.routing

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MVP route optimizer: greedy nearest-neighbour.
 * From the current position, repeatedly pick the closest unvisited stop.
 * Not globally optimal, but cheap and "good enough" at MVP scale (3-5 vehicles,
 * a handful of stops per trip) — exactly per the build plan in Section 8-9.
 *
 * Swap this for a VRPTW-based implementation later by writing a class that
 * implements RouteOptimizer and changing the constructor wiring in
 * Routing.kt — RoutingService and everything calling it stays untouched.
 *
 * Ordering ("which stop is nearest") is decided using local haversine math,
 * never a network call — that stays true regardless of which [EtaEstimator] is
 * injected. Only once a stop is actually chosen does this class ask the
 * estimator for the real travel time to it. That means exactly one ETA
 * lookup per stop in the final route, not one per candidate comparison during
 * the greedy search — the difference between N calls and roughly N² calls.
 */
class GreedyNearestNeighbourOptimizer(
    private val etaEstimator: EtaEstimator = HaversineEtaEstimator()
) : RouteOptimizer {

    override suspend fun optimize(
        vehicleLat: Double,
        vehicleLng: Double,
        stops: List<StopCandidate>
    ): List<OptimizedStop> {
        val remaining = stops.toMutableList()
        val ordered = mutableListOf<OptimizedStop>()

        var currentLat = vehicleLat
        var currentLng = vehicleLng
        var cumulativeMinutes = 0

        var sequenceOrder = 1
        while (remaining.isNotEmpty()) {
            val nearest = remaining.minByOrNull { haversineMeters(currentLat, currentLng, it.lat, it.lng) }!!
            cumulativeMinutes += etaEstimator.estimateMinutes(currentLat, currentLng, nearest.lat, nearest.lng)

            ordered.add(
                OptimizedStop(
                    rideRequestId = nearest.rideRequestId,
                    lat = nearest.lat,
                    lng = nearest.lng,
                    sequenceOrder = sequenceOrder++,
                    etaMinutes = cumulativeMinutes
                )
            )

            currentLat = nearest.lat
            currentLng = nearest.lng
            remaining.remove(nearest)
        }

        return ordered
    }

    /**
     * Straight-line distance in meters between two lat/lng points — used only to
     * pick the greedy nearest candidate at each step, never to compute the ETA
     * shown to users (that's [etaEstimator]'s job now). Kept local and
     * dependency-free so ordering decisions never require a network call, even
     * when the injected estimator does.
     */
    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).let { it * it }
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMeters * c
    }
}