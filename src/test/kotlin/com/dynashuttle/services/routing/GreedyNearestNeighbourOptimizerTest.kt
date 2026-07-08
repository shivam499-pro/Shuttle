package com.dynashuttle.services.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [GreedyNearestNeighbourOptimizer] to verify nearest-first ordering,
 * cumulative ETA calculations, and boundary conditions such as zero-distance inputs
 * and empty candidates.
 *
 * Coordinates used throughout are loosely centered on Bangalore (~12.97 N, 77.59 E)
 * just to keep numbers readable; the actual city doesn't matter to the haversine math.
 */
class GreedyNearestNeighbourOptimizerTest {

    private val optimizer = GreedyNearestNeighbourOptimizer()

    @Test
    fun `visits stops in nearest-first order`() {
        val vehicleLat = 12.9000
        val vehicleLng = 77.5000

        // 3 stops at increasing distances
        val stop1 = StopCandidate("req-1", 12.9010, 77.5010) // ~155 meters
        val stop2 = StopCandidate("req-2", 12.9020, 77.5020) // ~310 meters
        val stop3 = StopCandidate("req-3", 12.9030, 77.5030) // ~465 meters

        // Shuffled input to verify sorting/greedy algorithm behavior
        val stops = listOf(stop3, stop1, stop2)

        val result = optimizer.optimize(vehicleLat, vehicleLng, stops)

        assertEquals(3, result.size)

        assertEquals("req-1", result[0].rideRequestId)
        assertEquals(1, result[0].sequenceOrder)

        assertEquals("req-2", result[1].rideRequestId)
        assertEquals(2, result[1].sequenceOrder)

        assertEquals("req-3", result[2].rideRequestId)
        assertEquals(3, result[2].sequenceOrder)
    }

    @Test
    fun `visits the nearest stop first regardless of input order`() {
        val vehicleLat = 12.9716
        val vehicleLng = 77.5946

        // "far" is listed FIRST in the input specifically to prove ordering
        // is driven by distance, not by input order.
        val far = StopCandidate(rideRequestId = "far", lat = 13.0500, lng = 77.6500)
        val near = StopCandidate(rideRequestId = "near", lat = 12.9750, lng = 77.5980)

        val result = optimizer.optimize(vehicleLat, vehicleLng, listOf(far, near))

        assertEquals(listOf("near", "far"), result.map { it.rideRequestId })
        assertEquals(listOf(1, 2), result.map { it.sequenceOrder })
    }

    @Test
    fun `etaMinutes is strictly non-decreasing`() {
        val vehicleLat = 12.9000
        val vehicleLng = 77.5000

        val stops = listOf(
            StopCandidate("req-1", 12.9010, 77.5010),
            StopCandidate("req-2", 12.9020, 77.5020),
            StopCandidate("req-3", 12.9030, 77.5030)
        )

        val result = optimizer.optimize(vehicleLat, vehicleLng, stops)

        assertEquals(3, result.size)
        var prevEta = 0
        for (stop in result) {
            assertTrue(stop.etaMinutes >= prevEta, "ETA minutes should be non-decreasing: ${stop.etaMinutes} >= $prevEta")
            prevEta = stop.etaMinutes
        }
    }

    @Test
    fun `eta is monotonically non-decreasing along the route`() {
        val vehicleLat = 12.9716
        val vehicleLng = 77.5946

        val stops = listOf(
            StopCandidate("a", 12.9800, 77.6050),
            StopCandidate("b", 13.0200, 77.6400),
            StopCandidate("c", 12.9750, 77.5990),
            StopCandidate("d", 13.0600, 77.6700)
        )

        val result = optimizer.optimize(vehicleLat, vehicleLng, stops)
        val etas = result.map { it.etaMinutes }

        for (i in 1 until etas.size) {
            assertTrue(etas[i] >= etas[i - 1], "ETA should never decrease along the route: $etas")
        }
    }

    @Test
    fun `single stop returns list of size 1 with sequenceOrder 1`() {
        val vehicleLat = 12.9000
        val vehicleLng = 77.5000
        val stops = listOf(StopCandidate("req-1", 12.9010, 77.5010))

        val result = optimizer.optimize(vehicleLat, vehicleLng, stops)

        assertEquals(1, result.size)
        assertEquals("req-1", result[0].rideRequestId)
        assertEquals(1, result[0].sequenceOrder)
    }

    @Test
    fun `returns empty list when there are no stops`() {
        val result = optimizer.optimize(vehicleLat = 12.9716, vehicleLng = 77.5946, stops = emptyList())
        assertEquals(emptyList(), result)
    }

    @Test
    fun `zero distance stops resolve to sensible order and respect floor ETA`() {
        val vehicleLat = 12.9000
        val vehicleLng = 77.5000

        val stops = listOf(
            StopCandidate("req-same-1", vehicleLat, vehicleLng),
            StopCandidate("req-same-2", vehicleLat, vehicleLng)
        )

        val result = optimizer.optimize(vehicleLat, vehicleLng, stops)

        assertEquals(2, result.size)

        assertEquals(1, result[0].sequenceOrder)
        assertEquals(2, result[1].sequenceOrder)

        // The estimateMinutes floor is 1, so the cumulative ETAs should be at least 1 and 2
        assertTrue(result[0].etaMinutes >= 1, "First stop ETA should be at least 1 minute floor")
        assertTrue(result[1].etaMinutes >= 2, "Second stop ETA should be cumulative, at least 2 minutes")
    }

    @Test
    fun `slower average speed produces a longer ETA for the same distance`() {
        // Due north of the vehicle, roughly 11.1 km away (0.1 degree of latitude).
        val stop = StopCandidate("r1", 13.0716, 77.5946)

        val fastOptimizer = GreedyNearestNeighbourOptimizer(averageSpeedKmh = 50.0)
        val slowOptimizer = GreedyNearestNeighbourOptimizer(averageSpeedKmh = 10.0)

        val fastEta = fastOptimizer.optimize(12.9716, 77.5946, listOf(stop)).first().etaMinutes
        val slowEta = slowOptimizer.optimize(12.9716, 77.5946, listOf(stop)).first().etaMinutes

        assertTrue(slowEta > fastEta, "Lower average speed should yield a higher ETA: slow=$slowEta fast=$fastEta")
    }

    @Test
    fun `does not mutate the caller's input stops list`() {
        val stops = mutableListOf(
            StopCandidate("a", 12.9800, 77.6050),
            StopCandidate("b", 12.9900, 77.6150)
        )
        val originalSize = stops.size

        optimizer.optimize(12.9716, 77.5946, stops)

        assertEquals(originalSize, stops.size, "optimize() should not mutate the caller's input list")
    }

    @Test
    fun `real world Bangalore coordinates sanity check`() {
        // Whitefield coordinates
        val vehicleLat = 12.9600
        val vehicleLng = 77.7400

        // Electronic City coordinates
        val stop = StopCandidate("req-ecity", 12.8500, 77.6600)

        val result = optimizer.optimize(vehicleLat, vehicleLng, listOf(stop))

        assertEquals(1, result.size)
        val eta = result[0].etaMinutes

        // Asserting the travel time is a plausible positive duration (e.g. between 20 and 60 minutes)
        assertTrue(eta in 20..60, "ETA minutes ($eta) should be a plausible travel time (between 20 and 60 minutes)")
    }

    @Test
    fun `sequence numbers are assigned in visiting order starting at 1`() {
        val stops = listOf(
            StopCandidate("a", 12.9800, 77.6050),
            StopCandidate("b", 12.9900, 77.6150),
            StopCandidate("c", 13.0000, 77.6250)
        )

        val result = optimizer.optimize(12.9716, 77.5946, stops)

        assertEquals(listOf(1, 2, 3), result.map { it.sequenceOrder })
    }
}
