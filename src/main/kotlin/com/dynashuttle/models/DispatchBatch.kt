package com.dynashuttle.models

import java.time.Instant
import java.util.UUID

/**
 * Canonical batch lifecycle states.
 *
 * Design reference: Task 7.7, Section 8.3.
 *
 * Task 8.4 scope:
 *   COLLECTING → (add requests) → COLLECTING (still open)
 *   COLLECTING → READY          (window closed / capacity reached — locks the batch)
 *
 * Downstream states (Task 8.5+):
 *   READY → ASSIGNING → OPTIMIZING → DISPATCHED → EXECUTING → COMPLETED
 *   Any non-terminal → CANCELLED
 */
enum class BatchStatus {
    /** Open batch window; accepting QUEUED ride requests. */
    COLLECTING,
    /** Window closed; fully formed; ready for dispatch worker to process. */
    READY,
    /** Dispatch worker holds the lock and is selecting a vehicle (Task 8.5+). */
    ASSIGNING,
    /** Vehicle assigned; route optimizer running (Task 8.5+). */
    OPTIMIZING,
    /** Stops persisted; driver notified; trip → ASSIGNED (Task 8.5+). */
    DISPATCHED,
    /** Trip actively running (Task 8.5+). */
    EXECUTING,
    /** All passengers dropped off (terminal). */
    COMPLETED,
    /** Cancelled (empty, no vehicle, admin) (terminal). */
    CANCELLED;

    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELLED

    /** A batch is still open (accepting new requests) only while COLLECTING. */
    val isOpen: Boolean get() = this == COLLECTING

    companion object {
        fun fromString(value: String): BatchStatus = valueOf(value.trim().uppercase())
    }
}

/**
 * Domain model for a dispatch batch window.
 *
 * A batch groups [QUEUED] ride requests for the same company, schedule, and direction
 * that arrive within a 300-second window. When the window closes (or capacity is
 * reached), the batch transitions to [BatchStatus.READY] for dispatch processing.
 *
 * See Task 7.7 Section 8 and Section 9 for full batch window semantics.
 */
data class DispatchBatch(
    val id: String,
    val companyId: String,
    /** Nullable for ad-hoc (non-schedule-linked) batches. */
    val scheduleId: String?,
    /** Nullable until the batch is DISPATCHED and a trip is created/assigned. */
    val tripId: String?,
    val status: BatchStatus,
    val direction: ScheduleDirection,
    val windowStartAt: Instant,
    val windowClosesAt: Instant,
    val maxCapacity: Int,
    val assignedVehicleId: String? = null,
    val assignedDriverId: String? = null,
    val workerId: String? = null,
    val lockedAt: Instant? = null,
    val retryCount: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant
)
