package com.dynashuttle.models

import java.time.Instant
import java.time.LocalDate

/**
 * Domain model representing an operational Trip generated from a ShuttleSchedule template.
 *
 * A ShuttleSchedule is a recurring definition; a Trip is a concrete instance for one
 * specific operational calendar date ([tripDate]).
 *
 * Generated trips begin in the SCHEDULED state with vehicleId and driverId as null,
 * awaiting operational assignment in a subsequent phase.
 */
data class Trip(
    val id: String,
    val scheduleId: String,
    val tripDate: LocalDate,
    val vehicleId: String? = null,
    val driverId: String? = null,
    val startTime: Instant,
    val status: String = "SCHEDULED"
) {
    val tripStatus: TripStatus
        get() = TripStatus.fromString(status)
}
