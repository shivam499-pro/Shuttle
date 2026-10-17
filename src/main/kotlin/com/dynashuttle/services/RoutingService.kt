package com.dynashuttle.services

import com.dynashuttle.models.RideRequestDto
import com.dynashuttle.models.RideResponseDto
import com.dynashuttle.models.RideStatus
import com.dynashuttle.repositories.AllocationResult
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

    suspend fun matchRideRequest(request: RideRequestDto, companyId: String? = null): RideResponseDto {
        val candidates = vehicleRepository.findAvailableNear(
            lat = request.pickupLat,
            lng = request.pickupLng,
            radiusMeters = searchRadiusMeters,
            companyId = companyId
        )

        for (candidate in candidates) {
            val allocation = rideRequestRepository.allocateSeat(request, candidate.tripId, companyId)
            if (allocation is AllocationResult.Allocated) {
                val stored = allocation.stored
                val optimizedStops = routeOptimizer.optimize(
                    vehicleLat = candidate.currentLat,
                    vehicleLng = candidate.currentLng,
                    stops = listOf(StopCandidate(stored.id, request.pickupLat, request.pickupLng))
                )

                val etaForThisRequest = optimizedStops.firstOrNull { it.rideRequestId == stored.id }?.etaMinutes

                return RideResponseDto(
                    rideRequestId = stored.id,
                    status = "MATCHED",
                    assignedVehicleId = candidate.vehicleId,
                    etaMinutes = etaForThisRequest
                )
            }
        }

        val stored = rideRequestRepository.save(request, tripId = null, status = RideStatus.WAITLISTED.name)
        return RideResponseDto(rideRequestId = stored.id, status = RideStatus.WAITLISTED.name)
    }

    fun getRideStatus(
        rideRequestId: String,
        callerUserId: String? = null,
        callerCompanyId: String? = null
    ): RideResponseDto? {
        val stored = rideRequestRepository.findById(rideRequestId) ?: return null
        if (callerUserId != null && stored.employeeId != null && stored.employeeId != callerUserId) {
            return null
        }
        if (callerCompanyId != null && stored.companyId != null && stored.companyId != callerCompanyId) {
            return null
        }
        return RideResponseDto(rideRequestId = stored.id, status = stored.status)
    }
}