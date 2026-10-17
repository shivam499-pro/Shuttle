package com.dynashuttle.models

import java.time.Instant
import java.time.LocalTime

/**
 * Direction of travel for a shuttle schedule.
 *
 * INBOUND  = employee pickup locations → corporate campus/destination
 * OUTBOUND = corporate campus/destination → employee pickup locations
 */
enum class ScheduleDirection {
    INBOUND,
    OUTBOUND
}

/**
 * Day-of-week enum kept separate from java.time.DayOfWeek to give us full
 * control over the serialized string form in the API and the database.
 */
enum class ScheduleDay {
    MONDAY,
    TUESDAY,
    WEDNESDAY,
    THURSDAY,
    FRIDAY,
    SATURDAY,
    SUNDAY
}

/**
 * Domain model for a ShuttleSchedule template.
 *
 * A schedule is a recurring definition, NOT an operational run.
 * Trips will be generated from schedules in a future task.
 *
 * [departureTime] is a local time-of-day (no date, no timezone) because the
 * schedule recurs on each of [daysOfWeek] at the same local time.
 */
data class ShuttleSchedule(
    val id: String,
    val companyId: String,
    val name: String,
    val direction: ScheduleDirection,
    val departureTime: LocalTime,
    val daysOfWeek: List<ScheduleDay>,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)
