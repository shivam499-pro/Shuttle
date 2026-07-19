package com.dynashuttle.services.routing

/**
 * Estimates real travel time between two points, in whole minutes (rounded up
 * to at least 1). This is the one thing that gets swapped when moving from a
 * flat-speed guess to a real traffic-aware provider — see master plan Section 5,
 * item 9 ("replace flat-speed ETA estimate with real traffic-aware routing").
 *
 * Deliberately a separate interface from [RouteOptimizer], not a parameter on it:
 * "which stop to visit next" (ordering) and "how long will this leg take" (ETA)
 * are different concerns that change for different reasons and on different
 * schedules. [GreedyNearestNeighbourOptimizer] still decides ordering using cheap
 * local haversine math and calls this interface exactly once per resolved leg —
 * to get the number actually shown to the driver and employee — never once per
 * candidate comparison during the greedy search itself. That keeps external API
 * usage to one call per stop in the final route, not one per comparison.
 *
 * suspend because a real implementation (e.g. one calling a routing API over
 * HTTP) needs to. [HaversineEtaEstimator] doesn't need to suspend for anything,
 * but still satisfies the interface — a pure function is a valid suspend function.
 */
interface EtaEstimator {
    suspend fun estimateMinutes(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Int
}