package com.dynashuttle.services

import com.dynashuttle.models.RideRequestDto
import com.dynashuttle.models.RideResponseDto
import com.dynashuttle.repositories.RideMatchingRepository
import com.dynashuttle.repositories.VehicleAvailabilityLookup
import com.dynashuttle.services.routing.RouteOptimizer
import com.dynashuttle.services.routing.StopCandidate

/**
 * Orchestrates the match-and-route flow. Notice this class has exactly one
 * job: decide what happens when a ride request comes in. It delegates:
 *   - "find a nearby vehicle"   -> VehicleAvailabilityLookup (swap for a different
 *                                   spatial index without touching this class)
 *   - "order the stops"         -> RouteOptimizer       (swap greedy for VRPTW
 *                                   later without touching this class)
 *   - "persist the request"     -> RideMatchingRepository (swap Postgres for
 *                                   anything else without touching this class)
 *
 * This is Dependency Inversion in practice: RoutingService depends on three
 * interfaces, never on Exposed, Postgres, or any HTTP client directly. That
 * also means you can unit-test matchRideRequest() with simple fakes for all
 * three, no live database or API key required.
 *
 * Notice also these are narrow, single-purpose interfaces (Interface
 * Segregation) rather than the full VehicleRepository/RideRequestRepository —
 * RoutingService never writes GPS updates, never resolves a trip's vehicle,
 * never updates a ride request's status or reads a trip's stop list, so it
 * doesn't declare a dependency on any of that. See TripService for the
 * counterpart that depends on the *other* narrow interfaces this class doesn't use.
 */
class RoutingService(
    private val vehicleRepository: VehicleAvailabilityLookup,
    private val routeOptimizer: RouteOptimizer,
    private val rideRequestRepository: RideMatchingRepository,
    private val searchRadiusMeters: Int = 2000
) {

    suspend fun matchRideRequest(request: RideRequestDto): RideResponseDto {
        val candidates = vehicleRepository.findAvailableNear(
            lat = request.pickupLat,
            lng = request.pickupLng,
            radiusMeters = searchRadiusMeters
        )

        val bestVehicle = candidates.firstOrNull()
            ?: run {
                val stored = rideRequestRepository.save(request, tripId = null, status = "QUEUED")
                return RideResponseDto(rideRequestId = stored.id, status = "QUEUED")
            }

        val stored = rideRequestRepository.save(request, tripId = bestVehicle.tripId, status = "MATCHED")

        val optimizedStops = routeOptimizer.optimize(
            vehicleLat = bestVehicle.currentLat,
            vehicleLng = bestVehicle.currentLng,
            stops = listOf(StopCandidate(stored.id, request.pickupLat, request.pickupLng))
        )

        val etaForThisRequest = optimizedStops.firstOrNull { it.rideRequestId == stored.id }?.etaMinutes

        return RideResponseDto(
            rideRequestId = stored.id,
            status = "MATCHED",
            assignedVehicleId = bestVehicle.vehicleId,
            etaMinutes = etaForThisRequest
        )
    }

    fun getRideStatus(rideRequestId: String): RideResponseDto? {
        val stored = rideRequestRepository.findById(rideRequestId) ?: return null
        return RideResponseDto(rideRequestId = stored.id, status = stored.status)
    }
}