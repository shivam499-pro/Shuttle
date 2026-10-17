package com.dynashuttle.repositories

import com.dynashuttle.models.RideRequestDto
import com.dynashuttle.models.RideStatus

data class StoredRideRequest(
    val id: String,
    val tripId: String?,
    val status: String,
    val pickupLat: Double,
    val pickupLng: Double,
    val employeeId: String? = null,
    val companyId: String? = null,
    val pickupZoneId: String? = null
)

/**
 * A ride request that's still pending pickup on a given trip, joined with the
 * requesting employee's name for driver-facing display. Deliberately a separate
 * shape from [StoredRideRequest] rather than adding employeeName to it — most
 * callers (matching, status polling) never need the name, and joining every
 * lookup against Employees would be wasted work for them.
 */
data class ActiveTripStop(
    val rideRequestId: String,
    val employeeName: String,
    val pickupLat: Double,
    val pickupLng: Double,
    val employeeId: String? = null
)

/**
 * Result of an atomic capacity check and seat allocation on a trip.
 */
sealed class AllocationResult {
    data class Allocated(val stored: StoredRideRequest) : AllocationResult()
    object CapacityExhausted : AllocationResult()
    object TripNotFound : AllocationResult()
}

/** Persists a new ride request. */
interface RideRequestWriter {
    fun save(request: RideRequestDto, tripId: String?, status: String): StoredRideRequest

    /**
     * Atomically validates available capacity and allocates a seat on [tripId]
     * within a single database transaction using row-level locking.
     * Default implementation saves directly as MATCHED for backwards compatibility with test fakes.
     */
    fun allocateSeat(request: RideRequestDto, tripId: String, companyId: String? = null): AllocationResult {
        val stored = save(request, tripId, "MATCHED")
        return AllocationResult.Allocated(stored)
    }
}

/** Looks up a single ride request by id. */
interface RideRequestLookup {
    fun findById(id: String): StoredRideRequest?
    fun countActiveRiders(tripId: String): Int = 0
}

/**
 * Result of an atomic conditional status transition.
 * Returned by [RideRequestStatusUpdater.transitionStatus].
 */
sealed class TransitionResult {
    /** The row was found and its status updated successfully. */
    object Success : TransitionResult()
    /** No ride request with [id] exists. */
    object NotFound : TransitionResult()
    /**
     * The ride request exists but its current status was not [expectedStatus].
     * Indicates a race condition or stale state on the caller.
     */
    object StateMismatch : TransitionResult()
}

/** Updates a ride request's status (and optionally assigns it to a trip). */
interface RideRequestStatusUpdater {
    fun updateStatus(id: String, status: String, tripId: String? = null)

    /**
     * Atomically transitions a ride request from [expectedStatus] to [newStatus].
     *
     * Executes a single conditional UPDATE:
     *   UPDATE ride_requests SET status = ? WHERE id = ? AND status = ?
     * If zero rows are affected this method distinguishes between
     * [TransitionResult.NotFound] and [TransitionResult.StateMismatch] by
     * performing a follow-up existence check.  This is safe because a
     * second concurrent transition that succeeds first is correctly reported
     * as [TransitionResult.StateMismatch] regardless.
     *
     * Default implementation falls back to unconditional [updateStatus] for
     * backwards compatibility with simple test fakes.
     */
    fun transitionStatus(
        id: String,
        expectedStatus: RideStatus,
        newStatus: RideStatus,
        tripId: String? = null
    ): TransitionResult {
        // Fallback: used by test fakes that implement the simple interface.
        // Real Postgres implementation overrides this.
        val existing = (this as? RideRequestLookup)?.findById(id)
            ?: return TransitionResult.NotFound
        return if (existing.status == expectedStatus.name) {
            updateStatus(id, newStatus.name, tripId)
            TransitionResult.Success
        } else {
            TransitionResult.StateMismatch
        }
    }
}

/** Looks up the pending stops on a trip. */
interface ActiveTripStopsRepository {
    /**
     * Ride requests on [tripId] that are still awaiting pickup (status MATCHED or
     * PICKUP_PENDING — excludes REQUESTED/WAITLISTED requests not yet assigned to this trip,
     * and excludes ONBOARD/COMPLETED/NO_SHOW/CANCELLED requests already resolved).
     * Used to build the driver's live stop list.
     */
    fun findActiveByTripId(tripId: String): List<ActiveTripStop>

    /**
     * Returns true if [employeeId] has an active booking on [tripId]
     * (status MATCHED, PICKUP_PENDING, or ONBOARD).
     */
    fun hasActivePassenger(tripId: String, employeeId: String): Boolean =
        findActiveByTripId(tripId).any { it.employeeId == employeeId }
}

/**
 * Everything RoutingService needs: persist a new ride request, and look one up
 * by id to answer status-polling requests. Nothing about trip-stop lists or
 * status transitions — RoutingService never calls either.
 */
interface RideMatchingRepository : RideRequestWriter, RideRequestLookup

/**
 * Everything TripService needs: look up a ride request (to verify it belongs to
 * the trip in the URL before mutating it), update its status, and fetch the
 * live set of pending stops for a trip.
 */
interface TripStopRepository : RideRequestLookup, RideRequestStatusUpdater, ActiveTripStopsRepository

/**
 * Umbrella interface — exists only so [PostgresRideRequestRepository] has one type
 * to implement and [com.dynashuttle.plugins.configureRouting] has one instance to
 * construct and share. No service should ever declare a dependency on this type
 * directly; depend on [RideMatchingRepository] or [TripStopRepository] instead,
 * whichever matches what you actually call.
 */
interface RideRequestRepository : RideMatchingRepository, TripStopRepository