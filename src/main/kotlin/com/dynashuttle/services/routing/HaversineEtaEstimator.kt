package com.dynashuttle.services.routing

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Default ETA estimator: flat-speed haversine distance, no real traffic data.
 * This is what [GreedyNearestNeighbourOptimizer] falls back to when no real
 * routing provider is configured (see Routing.kt for the wiring) — and what
 * [TomTomEtaEstimator] itself falls back to if a live API call fails.
 *
 * Known, documented limitation (per the planning doc's "known gaps" — this was
 * the whole reason item 9 exists): assumes a constant [averageSpeedKmh]
 * regardless of actual traffic, time of day, or road type. Extracted unchanged
 * from the logic that used to live directly inside GreedyNearestNeighbourOptimizer —
 * same math, same rounding (truncate, floor of 1 minute), so nothing that already
 * depended on these exact numbers (see GreedyNearestNeighbourOptimizerTest) breaks.
 */
class HaversineEtaEstimator(
    private val averageSpeedKmh: Double = 25.0 // typical urban traffic speed for ETA estimate
) : EtaEstimator {

    override suspend fun estimateMinutes(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Int {
        val distanceMeters = haversineMeters(fromLat, fromLng, toLat, toLng)
        val hours = (distanceMeters / 1000.0) / averageSpeedKmh
        return (hours * 60).toInt().coerceAtLeast(1)
    }

    /** Straight-line distance in meters between two lat/lng points. */
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