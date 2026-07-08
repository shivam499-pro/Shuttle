package com.dynashuttle.repositories

/**
 * Abstraction over "find me an available vehicle near this location".
 * RoutingService depends on this interface, not on Postgres/PostGIS directly.
 * This is what makes the matching logic unit-testable without a live database,
 * and swappable later (e.g. caching layer, different spatial index) without
 * touching a single line of business logic.
 */
data class VehicleCandidate(
    val vehicleId: String,
    val tripId: String,
    val driverId: String,
    val currentLat: Double,
    val currentLng: Double,
    val availableSeats: Int
)

interface VehicleRepository {
    /**
     * Returns vehicles currently on an active trip within range of [lat]/[lng]
     * that still have at least one free seat.
     */
    fun findAvailableNear(lat: Double, lng: Double, radiusMeters: Int): List<VehicleCandidate>

    /**
     * Records the latest GPS fix for [vehicleId], overwriting the previous value.
     * Called on every position broadcast from the driver app (approximately every 5 seconds).
     * The timestamp is set server-side to [java.time.Instant.now] so the database reflects
     * the moment the server received the update, not the device's local clock.
     */
    fun updateLocation(vehicleId: String, lat: Double, lng: Double)

    /**
     * Resolves the vehicle assigned to [tripId].
     * Needed by the WebSocket GPS handler: a [com.dynashuttle.models.DriverLocationUpdate]
     * frame carries a tripId, not a vehicleId, so the handler delegates this lookup here
     * rather than reaching past the interface into Exposed/Trips directly.
     * Returns null if no trip with that id exists (caller should ignore the frame).
     */
    fun findVehicleIdByTripId(tripId: String): String?
}
