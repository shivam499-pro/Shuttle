package com.dynashuttle.services

import com.dynashuttle.models.RideRequestDto
import com.dynashuttle.models.RideResponseDto
import com.dynashuttle.repositories.RideRequestRepository
import com.dynashuttle.repositories.VehicleRepository
import com.dynashuttle.services.routing.RouteOptimizer
import com.dynashuttle.services.routing.StopCandidate

/**
 * Orchestrates the match-and-route flow. Notice this class has exactly one
 * job: decide what happens when a ride request comes in. It delegates:
 *   - "find a nearby vehicle"   -> VehicleRepository   (swap for a different
 *                                   spatial index without touching this class)
 *   - "order the stops"         -> RouteOptimizer       (swap greedy for VRPTW
 *                                   later without touching this class)
 *   - "persist the request"     -> RideRequestRepository (swap Postgres for
 *                                   anything else without touching this class)
 *
 * This is Dependency Inversion in practice: RoutingService depends on three
 * interfaces, never on Exposed, Postgres, or any HTTP client directly. That
 * also means you can unit-test matchRideRequest() with simple fakes for all
 * three, no live database or API key required.
 */
class RoutingService(
    private val vehicleRepository: VehicleRepository,
    private val routeOptimizer: RouteOptimizer,
    private val rideRequestRepository: RideRequestRepository,
    private val searchRadiusMeters: Int = 2000
) {

    fun matchRideRequest(request: RideRequestDto): RideResponseDto {
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
