package com.dynashuttle.models

import java.time.Instant

enum class StopType {
    PICKUP,
    DROPOFF;

    companion object {
        fun fromString(value: String): StopType = valueOf(value.trim().uppercase())
    }
}

enum class StopStatus {
    PLANNED,
    EN_ROUTE,
    ARRIVED,
    COMPLETED,
    SKIPPED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this in setOf(COMPLETED, SKIPPED, CANCELLED)

    companion object {
        fun fromString(value: String): StopStatus = valueOf(value.trim().uppercase())
    }
}

data class StoredTripStop(
    val id: String,
    val tripId: String,
    val batchId: String? = null,
    val rideRequestId: String,
    val companyId: String,
    val stopType: StopType,
    val sequenceOrder: Int,
    val latitude: Double,
    val longitude: Double,
    val status: StopStatus = StopStatus.PLANNED,
    val plannedEta: Instant? = null,
    val actualArrival: Instant? = null,
    val actualDeparture: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)
