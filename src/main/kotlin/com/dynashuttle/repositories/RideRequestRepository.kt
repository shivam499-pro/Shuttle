package com.dynashuttle.repositories

import com.dynashuttle.models.RideRequestDto

data class StoredRideRequest(
    val id: String,
    val tripId: String?,
    val status: String,
    val pickupLat: Double,
    val pickupLng: Double
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
    val pickupLng: Double
)

/** Persists a new ride request. */
interface RideRequestWriter {
    fun save(request: RideRequestDto, tripId: String?, status: String): StoredRideRequest
}

/** Looks up a single ride request by id. */
interface RideRequestLookup {
    fun findById(id: String): StoredRideRequest?
}

/** Updates a ride request's status (and optionally assigns it to a trip). */
interface RideRequestStatusUpdater {
    fun updateStatus(id: String, status: String, tripId: String? = null)
}

/** Looks up the pending stops on a trip. */
interface ActiveTripStopsRepository {
    /**
     * Ride requests on [tripId] that are still awaiting pickup (status MATCHED —
     * excludes REQUESTED/QUEUED requests not yet assigned to this trip, and
     * excludes PICKED_UP/COMPLETED/NO_SHOW requests already resolved).
     * Used to build the driver's live stop list.
     */
    fun findActiveByTripId(tripId: String): List<ActiveTripStop>
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