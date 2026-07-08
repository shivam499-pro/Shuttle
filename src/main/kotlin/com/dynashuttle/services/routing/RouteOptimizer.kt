package com.dynashuttle.services.routing

/**
 * Abstraction over "given a vehicle's current position and a set of pending
 * stops, what order should it visit them in".
 *
 * This is the Open/Closed seam: the planning doc calls for a cheap greedy
 * nearest-neighbour heuristic at MVP, upgraded to a proper VRPTW solver once
 * there's real trip data to tune against. With this interface, that upgrade
 * means writing a new class and changing one constructor argument —
 * RoutingService itself never changes.
 */
data class StopCandidate(
    val rideRequestId: String,
    val lat: Double,
    val lng: Double
)

data class OptimizedStop(
    val rideRequestId: String,
    val lat: Double,
    val lng: Double,
    val sequenceOrder: Int,
    val etaMinutes: Int
)

interface RouteOptimizer {
    fun optimize(
        vehicleLat: Double,
        vehicleLng: Double,
        stops: List<StopCandidate>
    ): List<OptimizedStop>
}
