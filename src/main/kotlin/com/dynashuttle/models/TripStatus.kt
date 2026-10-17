package com.dynashuttle.models

/**
 * Production Trip lifecycle states for DynaShuttle.
 *
 * Lifecycle flow:
 *   SCHEDULED -> ASSIGNED -> BOARDING -> IN_PROGRESS -> COMPLETED
 *
 * Terminal states:
 *   COMPLETED, CANCELLED, ABORTED
 */
enum class TripStatus {
    SCHEDULED,
    ASSIGNED,
    BOARDING,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    ABORTED;

    val isTerminal: Boolean
        get() = this in setOf(COMPLETED, CANCELLED, ABORTED)

    companion object {
        fun fromString(value: String): TripStatus = valueOf(value.trim().uppercase())
    }
}
