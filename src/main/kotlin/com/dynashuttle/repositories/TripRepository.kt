package com.dynashuttle.repositories

import com.dynashuttle.models.Trip
import com.dynashuttle.models.TripStatus
import java.time.LocalDate

/**
 * Narrow interface for Trip persistence operations.
 *
 * Scoped specifically to operational trip creation, status transitions,
 * driver/vehicle assignment, and schedule-date lookups.
 * Implementation details (Exposed/PostgreSQL) are confined to [PostgresTripRepository].
 */
interface TripRepository {
    fun findById(tripId: String): Trip?
    fun findByScheduleAndDate(scheduleId: String, tripDate: LocalDate): Trip?
    fun create(trip: Trip): Trip
    fun findCompanyId(tripId: String): String? = null
    fun updateStatus(tripId: String, expectedStatus: TripStatus, newStatus: TripStatus): TransitionResult =
        TransitionResult.NotFound
    fun assignVehicleAndDriver(tripId: String, vehicleId: String, driverId: String): TransitionResult =
        TransitionResult.NotFound
}
