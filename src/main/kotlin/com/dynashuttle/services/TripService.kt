package com.dynashuttle.services

import com.dynashuttle.models.RideStatus
import com.dynashuttle.models.Stop
import com.dynashuttle.models.StopListUpdate
import com.dynashuttle.repositories.TripDetails
import com.dynashuttle.repositories.TransitionResult
import com.dynashuttle.repositories.TripStopRepository
import com.dynashuttle.repositories.VehicleTripLocationLookup
import com.dynashuttle.services.routing.RouteOptimizer
import com.dynashuttle.services.routing.StopCandidate

/**
 * Orchestrates driver-facing trip operations: the live stop list, and marking
 * a stop picked up or a no-show.  Now routes all lifecycle changes through
 * [RideRequestStateMachine] so transition rules are enforced centrally.
 */
class TripService(
    private val rideRequestRepository: TripStopRepository,
    private val vehicleRepository: VehicleTripLocationLookup,
    private val routeOptimizer: RouteOptimizer
) {

    /** Result of a lifecycle operation initiated by a driver or employee. */
    sealed class LifecycleResult {
        object Success : LifecycleResult()
        object NotFound : LifecycleResult()
        object InvalidTransition : LifecycleResult()
        /** Caller is not authorised for this operation. */
        object Forbidden : LifecycleResult()
    }

    /**
     * Returns the live, ordered stop list for [tripId], or null if no such trip exists.
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

    fun getTripDetails(tripId: String): TripDetails? = vehicleRepository.findTripDetails(tripId)

    fun hasEmployeeOnTrip(tripId: String, employeeId: String): Boolean {
        return (rideRequestRepository as? TripStopRepository)?.hasActivePassenger(tripId, employeeId)
            ?: rideRequestRepository.findActiveByTripId(tripId).any { it.employeeId == employeeId }
    }

    /**
     * Marks a ride request as PICKUP_PENDING (passenger is at the stop, ready to board).
     * Valid from: MATCHED.
     * Driver-only, assigned driver required.
     */
    fun markPickupPending(
        tripId: String,
        rideRequestId: String,
        driverId: String? = null,
        companyId: String? = null
    ): LifecycleResult = performDriverTransition(
        tripId, rideRequestId, driverId, companyId,
        from = RideStatus.MATCHED,
        to = RideStatus.PICKUP_PENDING
    )

    /**
     * Marks a ride request as ONBOARD (passenger has boarded the vehicle).
     * Valid from: PICKUP_PENDING.
     * Driver-only, assigned driver required.
     *
     * Kept as [markPickedUp] for backwards-compatible route binding.
     */
    fun markPickedUp(
        tripId: String,
        rideRequestId: String,
        driverId: String? = null,
        companyId: String? = null
    ): LifecycleResult = performDriverTransition(
        tripId, rideRequestId, driverId, companyId,
        from = RideStatus.PICKUP_PENDING,
        to = RideStatus.ONBOARD
    )

    /**
     * Marks a ride request as NO_SHOW.
     * Valid from: PICKUP_PENDING.
     * Driver-only, assigned driver required.
     */
    fun markNoShow(
        tripId: String,
        rideRequestId: String,
        driverId: String? = null,
        companyId: String? = null
    ): LifecycleResult = performDriverTransition(
        tripId, rideRequestId, driverId, companyId,
        from = RideStatus.PICKUP_PENDING,
        to = RideStatus.NO_SHOW
    )

    /**
     * Marks a ride request as COMPLETED.
     * Valid from: ONBOARD.
     * Driver-only, assigned driver required.
     */
    fun markCompleted(
        tripId: String,
        rideRequestId: String,
        driverId: String? = null,
        companyId: String? = null
    ): LifecycleResult = performDriverTransition(
        tripId, rideRequestId, driverId, companyId,
        from = RideStatus.ONBOARD,
        to = RideStatus.COMPLETED
    )

    /**
     * Cancels a ride request.
     * Valid from: REQUESTED, MATCHED, WAITLISTED, PICKUP_PENDING.
     * ONBOARD, COMPLETED and NO_SHOW cannot be cancelled.
     *
     * [callerEmployeeId] / [callerCompanyId]: provided when the employee is cancelling;
     * [driverId] / [companyId]: provided when the driver is cancelling.
     * At least one of these pairs must be provided so ownership is verified.
     */
    fun cancelRideRequest(
        rideRequestId: String,
        callerEmployeeId: String? = null,
        callerCompanyId: String? = null
    ): LifecycleResult {
        val existing = rideRequestRepository.findById(rideRequestId)
            ?: return LifecycleResult.NotFound

        // Company isolation
        if (callerCompanyId != null && existing.companyId != null && existing.companyId != callerCompanyId) {
            return LifecycleResult.NotFound  // 404-as-not-found per tenant isolation policy
        }

        // Employee ownership check
        if (callerEmployeeId != null && existing.employeeId != null && existing.employeeId != callerEmployeeId) {
            return LifecycleResult.Forbidden
        }

        val currentStatus = try {
            RideStatus.fromString(existing.status)
        } catch (_: Exception) {
            return LifecycleResult.InvalidTransition
        }

        // Validate transition via state machine
        if (!RideRequestStateMachine.canTransition(currentStatus, RideStatus.CANCELLED)) {
            return LifecycleResult.InvalidTransition
        }

        val result = rideRequestRepository.transitionStatus(
            rideRequestId, currentStatus, RideStatus.CANCELLED
        )

        return when (result) {
            TransitionResult.Success -> LifecycleResult.Success
            TransitionResult.NotFound -> LifecycleResult.NotFound
            TransitionResult.StateMismatch -> LifecycleResult.InvalidTransition
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Shared implementation for all driver-initiated lifecycle transitions.
     * Verifies trip ownership, company isolation, and driver assignment before
     * delegating to the state machine and the atomic repository transition.
     */
    private fun performDriverTransition(
        tripId: String,
        rideRequestId: String,
        driverId: String?,
        companyId: String?,
        from: RideStatus,
        to: RideStatus
    ): LifecycleResult {
        // Trip ownership / authorization
        if (driverId != null || companyId != null) {
            val trip = vehicleRepository.findTripDetails(tripId)
                ?: return LifecycleResult.NotFound
            if (companyId != null && trip.companyId != companyId) return LifecycleResult.NotFound
            if (driverId != null && trip.driverId != driverId) return LifecycleResult.Forbidden
        }

        // Ride request must belong to this trip and caller's company
        val existing = rideRequestRepository.findById(rideRequestId)
            ?: return LifecycleResult.NotFound
        if (existing.tripId != tripId) return LifecycleResult.NotFound
        if (companyId != null && existing.companyId != null && existing.companyId != companyId) {
            return LifecycleResult.NotFound
        }

        // Validate via state machine before touching the DB
        if (!RideRequestStateMachine.canTransition(from, to)) {
            return LifecycleResult.InvalidTransition
        }

        val result = rideRequestRepository.transitionStatus(rideRequestId, from, to)

        return when (result) {
            TransitionResult.Success -> LifecycleResult.Success
            TransitionResult.NotFound -> LifecycleResult.NotFound
            TransitionResult.StateMismatch -> LifecycleResult.InvalidTransition
        }
    }
}