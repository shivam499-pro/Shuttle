package com.dynashuttle.models

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.javatime.time
import org.jetbrains.exposed.sql.javatime.date

/**
 * Table definitions — these mirror the ERD from the system design phase:
 * Company -> Employees / Drivers / Vehicles
 * Vehicle + Driver -> Trip
 * Trip + Employee -> RideRequest
 */

object Companies : UUIDTable("companies") {
    val name = varchar("name", 255)
    val city = varchar("city", 100)
    val employeeCount = integer("employee_count").default(0)
    val createdAt = timestamp("created_at")
}

object PickupZones : UUIDTable("pickup_zones") {
    val companyId = reference("company_id", Companies)
    val name = varchar("name", 255)
    val latitude = double("latitude")
    val longitude = double("longitude")
    val radius = integer("radius")
    val address = varchar("address", 500).nullable()
    val active = bool("active").default(true)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object Employees : UUIDTable("employees") {
    val companyId = reference("company_id", Companies)
    val email = varchar("email", 255).uniqueIndex()
    val name = varchar("name", 255)
    val homeLat = double("home_lat")
    val homeLng = double("home_lng")
    val pickupZoneId = reference("pickup_zone_id", PickupZones).nullable()
    val createdAt = timestamp("created_at")
}

object Drivers : UUIDTable("drivers") {
    val companyId = reference("company_id", Companies)
    val name = varchar("name", 255)
    val phone = varchar("phone", 20)
    val createdAt = timestamp("created_at")
}

object Vehicles : UUIDTable("vehicles") {
    val companyId = reference("company_id", Companies)
    val plateNumber = varchar("plate_number", 20)
    val seatCapacity = integer("seat_capacity")
    val createdAt = timestamp("created_at")

    // Live-location tracking — intentionally a single mutable row, not a history table.
    // The only current consumer is ride-matching ("find vehicles near this point right now"),
    // which only ever needs the latest position. A separate location-history table would add
    // write amplification and query complexity for zero current benefit. If trip-replay or
    // analytics are ever required, a history table can be introduced at that point without
    // breaking this contract. Nullable because a vehicle that has never reported a GPS fix
    // has no meaningful last-known location; callers must handle the null case explicitly.
    val lastKnownLat = double("last_known_lat").nullable()
    val lastKnownLng = double("last_known_lng").nullable()
    val lastLocationUpdatedAt = timestamp("last_location_updated_at").nullable()
}

/**
 * Shuttle schedule templates.  A schedule is a recurring definition — NOT an
 * operational trip.  Trips are generated from schedules for specific operational dates.
 *
 * [daysOfWeek] stores the PostgreSQL TEXT column as a comma-separated string
 * at the Exposed column level (varchar); the repository converts it to and
 * from List<ScheduleDay>.
 *
 * [departureTime] uses the Exposed javatime time() column, which maps to
 * TIME WITHOUT TIME ZONE in PostgreSQL and to java.time.LocalTime in Kotlin.
 */
object ShuttleSchedules : UUIDTable("shuttle_schedules") {
    val companyId = reference("company_id", Companies)
    val name = varchar("name", 255)
    val direction = varchar("direction", 20)
    val departureTime = time("departure_time")
    /** Stored as a PostgreSQL-compatible comma-separated string; see repository. */
    val daysOfWeek = varchar("days_of_week", 255)
    val active = bool("active").default(true)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object Trips : UUIDTable("trips") {
    val scheduleId = reference("schedule_id", ShuttleSchedules)
    val tripDate = date("trip_date")
    val vehicleId = reference("vehicle_id", Vehicles).nullable()
    val driverId = reference("driver_id", Drivers).nullable()
    val startTime = timestamp("start_time")
    // status: SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED
    val status = varchar("status", 20).default("SCHEDULED")
}

/**
 * Dispatch batch windows.
 *
 * Batch status values (defined by chk_dispatch_batches_status in V7):
 *   COLLECTING  – open and accepting QUEUED ride requests
 *   READY       – window closed; waiting for a dispatch worker to process
 *   ASSIGNING   – a dispatch worker holds the lock and is selecting a vehicle
 *   OPTIMIZING  – vehicle assigned; route optimizer running
 *   DISPATCHED  – stops persisted; driver notified; trip → ASSIGNED
 *   EXECUTING   – trip actively running
 *   COMPLETED   – all passengers dropped off
 *   CANCELLED   – empty batch, no vehicle, or admin cancel
 *
 * Additional legacy values allowed by DB constraint: OPEN, LOCKED, CLOSED.
 */
object DispatchBatches : UUIDTable("dispatch_batches") {
    val companyId      = reference("company_id", Companies)
    val scheduleId     = uuid("schedule_id").nullable()
    val tripId         = uuid("trip_id").nullable()
    val status         = varchar("status", 20).default("COLLECTING")
    val direction      = varchar("direction", 10)
    val windowStartAt  = timestamp("window_start_at")
    val windowClosesAt = timestamp("window_closes_at")
    val dispatchedAt   = timestamp("dispatched_at").nullable()
    val maxCapacity    = integer("max_capacity")
    val assignedVehicleId = uuid("assigned_vehicle_id").nullable()
    val assignedDriverId  = uuid("assigned_driver_id").nullable()
    val workerId       = varchar("worker_id", 255).nullable()
    val lockedAt       = timestamp("locked_at").nullable()
    val retryCount     = integer("retry_count").default(0)
    val createdAt      = timestamp("created_at")
    val updatedAt      = timestamp("updated_at")
}

object RideRequests : UUIDTable("ride_requests") {
    val tripId = reference("trip_id", Trips).nullable()
    val employeeId = reference("employee_id", Employees)
    val pickupLat = double("pickup_lat")
    val pickupLng = double("pickup_lng")
    val destinationLat = double("destination_lat")
    val destinationLng = double("destination_lng")
    val pickupZoneId = reference("pickup_zone_id", PickupZones).nullable()
    // status: REQUESTED, QUEUED, MATCHED, WAITLISTED, PICKUP_PENDING, ONBOARD, COMPLETED, CANCELLED, NO_SHOW, EXPIRED
    val status = varchar("status", 20).default("REQUESTED")
    val requestedAt = timestamp("requested_at")
    // V7 batching columns — nullable; populated when request enters an open batch window
    val batchId   = uuid("batch_id").nullable()
    val queuedAt  = timestamp("queued_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
}

object Admins : UUIDTable("admins") {
    val companyId = reference("company_id", Companies)
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val createdAt = timestamp("created_at")
}

object TripStops : UUIDTable("trip_stops") {
    val tripId = reference("trip_id", Trips)
    val batchId = uuid("batch_id").nullable()
    val rideRequestId = reference("ride_request_id", RideRequests)
    val companyId = reference("company_id", Companies)
    val stopType = varchar("stop_type", 10)
    val sequenceOrder = integer("sequence_order")
    val latitude = double("latitude")
    val longitude = double("longitude")
    val status = varchar("status", 20).default("PLANNED")
    val plannedEta = timestamp("planned_eta").nullable()
    val actualArrival = timestamp("actual_arrival").nullable()
    val actualDeparture = timestamp("actual_departure").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
