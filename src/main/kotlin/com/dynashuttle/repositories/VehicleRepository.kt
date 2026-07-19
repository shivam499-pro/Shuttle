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

/**
 * A vehicle's current position for a given trip. [lat]/[lng] are nullable
 * because a vehicle may be assigned to a trip but not yet have reported a GPS
 * fix (see Vehicles.lastKnownLat/Lng in Tables.kt) — callers must handle that
 * case explicitly rather than assuming a position always exists.
 */
data class VehicleLocation(
    val vehicleId: String,
    val lat: Double?,
    val lng: Double?
)

/**
 * Everything RoutingService needs from vehicle data: nothing more.
 * Deliberately excludes GPS-write and trip-lookup operations that RoutingService
 * never calls — depending on this narrow interface instead of the full
 * [VehicleRepository] means RoutingService's constructor signature is an honest
 * statement of what it actually uses, and a fake for testing it only needs to
 * implement one method instead of four.
 */
interface VehicleAvailabilityLookup {
    /**
     * Returns vehicles currently on an active trip within range of [lat]/[lng]
     * that still have at least one free seat.
     */
    fun findAvailableNear(lat: Double, lng: Double, radiusMeters: Int): List<VehicleCandidate>
}

/** Records the latest GPS fix for a vehicle. */
interface VehicleLocationUpdater {
    /**
     * Records the latest GPS fix for [vehicleId], overwriting the previous value.
     * Called on every position broadcast from the driver app (approximately every 5 seconds).
     * The timestamp is set server-side to [java.time.Instant.now] so the database reflects
     * the moment the server received the update, not the device's local clock.
     */
    fun updateLocation(vehicleId: String, lat: Double, lng: Double)
}

/** Resolves which vehicle is assigned to a trip. */
interface VehicleTripLookup {
    /**
     * Resolves the vehicle assigned to [tripId].
     * Needed by the WebSocket GPS handler: a [com.dynashuttle.models.DriverLocationUpdate]
     * frame carries a tripId, not a vehicleId, so the handler delegates this lookup here
     * rather than reaching past the interface into Exposed/Trips directly.
     * Returns null if no trip with that id exists (caller should ignore the frame).
     */
    fun findVehicleIdByTripId(tripId: String): String?
}

/** Resolves a trip's assigned vehicle's current position. */
interface VehicleTripLocationLookup {
    /**
     * Resolves the current position of the vehicle assigned to [tripId].
     * Returns null only if [tripId] itself doesn't exist — distinct from the vehicle
     * existing but not having reported GPS yet, which is represented by a non-null
     * [VehicleLocation] with null lat/lng. Callers (e.g. computing a live stop order)
     * need to tell those two cases apart: one means "no such trip" (404), the other
     * means "trip is real, just no position yet" (valid, just nothing to order against).
     */
    fun findVehicleLocationByTripId(tripId: String): VehicleLocation?
}

/**
 * Everything the WebSocket GPS handler needs: recording a driver's position update,
 * and resolving which vehicle a trip belongs to so the update can be attached to it.
 * A named role interface (rather than passing two separate constructor params) since
 * both operations are always needed together by exactly one consumer (websocketRoutes).
 */
interface DriverLocationChannel : VehicleLocationUpdater, VehicleTripLookup

/**
 * Umbrella interface — exists only so [PostgresVehicleRepository] has one type to
 * implement and [com.dynashuttle.plugins.configureRouting] has one instance to
 * construct and share. No service should ever declare a dependency on this type
 * directly; depend on the narrowest interface above that covers what you actually
 * call (VehicleAvailabilityLookup, DriverLocationChannel, or VehicleTripLocationLookup).
 */
interface VehicleRepository : VehicleAvailabilityLookup, DriverLocationChannel, VehicleTripLocationLookup