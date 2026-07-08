package com.dynashuttle.models

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.javatime.timestamp

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

object Employees : UUIDTable("employees") {
    val companyId = reference("company_id", Companies)
    val email = varchar("email", 255).uniqueIndex()
    val name = varchar("name", 255)
    val homeLat = double("home_lat")
    val homeLng = double("home_lng")
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

object Trips : UUIDTable("trips") {
    val vehicleId = reference("vehicle_id", Vehicles)
    val driverId = reference("driver_id", Drivers)
    val startTime = timestamp("start_time")
    // status: SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED
    val status = varchar("status", 20).default("SCHEDULED")
}

object RideRequests : UUIDTable("ride_requests") {
    val tripId = reference("trip_id", Trips).nullable()
    val employeeId = reference("employee_id", Employees)
    val pickupLat = double("pickup_lat")
    val pickupLng = double("pickup_lng")
    val destinationLat = double("destination_lat")
    val destinationLng = double("destination_lng")
    // status: REQUESTED, MATCHED, PICKED_UP, COMPLETED, NO_SHOW, QUEUED
    val status = varchar("status", 20).default("REQUESTED")
    val requestedAt = timestamp("requested_at")
}

object Admins : UUIDTable("admins") {
    val companyId = reference("company_id", Companies)
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val createdAt = timestamp("created_at")
}

