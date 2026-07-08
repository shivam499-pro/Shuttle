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
 * Abstraction over persisting and retrieving ride requests.
 * RoutingService never touches Exposed/SQL directly — it asks this interface
 * to save or fetch a ride request, and a Postgres-backed implementation
 * handles the actual table writes.
 */
interface RideRequestRepository {
    fun save(request: RideRequestDto, tripId: String?, status: String): StoredRideRequest
    fun findById(id: String): StoredRideRequest?
    fun updateStatus(id: String, status: String, tripId: String? = null)
}
