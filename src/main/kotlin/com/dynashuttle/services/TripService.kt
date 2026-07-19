package com.dynashuttle.services

import com.dynashuttle.models.Stop
import com.dynashuttle.models.StopListUpdate
import com.dynashuttle.repositories.TripStopRepository
import com.dynashuttle.repositories.VehicleTripLocationLookup
import com.dynashuttle.services.routing.RouteOptimizer
import com.dynashuttle.services.routing.StopCandidate

/**
 * Orchestrates driver-facing trip operations: the live stop list, and marking
 * a stop picked up or a no-show. Mirrors [RoutingService]'s shape deliberately —
 * depends only on interfaces (TripStopRepository, VehicleTripLocationLookup,
 * RouteOptimizer), never on Exposed/Postgres directly, so it's unit-testable
 * with fakes and swappable the same way.
 *
 * Interface Segregation counterpart to [RoutingService]: this class depends on
 * the narrow interfaces RoutingService doesn't use (updating a ride request's
 * status, reading a trip's pending stops, resolving a trip's vehicle position),
 * and does *not* depend on VehicleAvailabilityLookup or RideRequestWriter —
 * TripService never searches for a vehicle or creates a new ride request.
 *
 * There is no stored "stop sequence" in the schema (see Tables.kt — RideRequests
 * has no sequence_order column). The order is recomputed live from whatever ride
 * requests are currently active on the trip, the same greedy approach
 * [RoutingService] already uses for a single new request — just applied to the
 * full pending set. This is consistent with the MVP-scale tradeoff documented on
 * [com.dynashuttle.services.routing.GreedyNearestNeighbourOptimizer]: cheap and
 * "good enough" at a handful of stops per trip, not globally optimal.
 */
class TripService(
    private val rideRequestRepository: TripStopRepository,
    private val vehicleRepository: VehicleTripLocationLookup,
    private val routeOptimizer: RouteOptimizer
) {

    /**
     * Returns the live, ordered stop list for [tripId], or null if no such trip exists.
     * A trip that exists but whose vehicle hasn't reported a GPS fix yet (or that has
     * no pending stops) still returns a [StopListUpdate] — just with an empty stop list,
     * since there's nothing to compute an order against.
     */
    suspend fun getStopList(tripId: String): StopListUpdate? {
        val vehicleLocation = vehicleRepository.findVehicleLocationByTripId(tripId) ?: return null
        val activeStops = rideRequestRepository.findActiveByTripId(tripId)

        if (activeStops.isEmpty() || vehicleLocation.lat == null || vehicleLocation.lng == null) {
            return StopListUpdate(tripId = tripId, stops = emptyList())
        }

        val optimized = routeOptimizer.optimize(
            vehicleLat = vehicleLocation.lat,
            vehicleLng = vehicleLocation.lng,
            stops = activeStops.map { StopCandidate(it.rideRequestId, it.pickupLat, it.pickupLng) }
        )

        val employeeNameById = activeStops.associateBy({ it.rideRequestId }, { it.employeeName })

        val stops = optimized.map { stop ->
            Stop(
                rideRequestId = stop.rideRequestId,
                lat = stop.lat,
                lng = stop.lng,
                sequenceOrder = stop.sequenceOrder,
                employeeName = employeeNameById[stop.rideRequestId] ?: "Unknown"
            )
        }

        return StopListUpdate(tripId = tripId, stops = stops)
    }

    /**
     * Marks a ride request picked up. Returns false if the ride request doesn't exist,
     * or exists but doesn't belong to [tripId] — the second case guards against a
     * driver app accidentally (or maliciously) resolving a stop that belongs to a
     * different trip via the URL's tripId not matching the stop's actual tripId.
     */
    fun markPickedUp(tripId: String, rideRequestId: String): Boolean = updateStopStatus(tripId, rideRequestId, "PICKED_UP")

    /** Marks a ride request as a no-show. Same tripId-ownership guard as [markPickedUp]. */
    fun markNoShow(tripId: String, rideRequestId: String): Boolean = updateStopStatus(tripId, rideRequestId, "NO_SHOW")

    private fun updateStopStatus(tripId: String, rideRequestId: String, newStatus: String): Boolean {
        val existing = rideRequestRepository.findById(rideRequestId) ?: return false
        if (existing.tripId != tripId) return false
        rideRequestRepository.updateStatus(rideRequestId, newStatus)
        return true
    }
}