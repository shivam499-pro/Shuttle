package com.dynashuttle.repositories

import com.dynashuttle.models.BatchStatus
import com.dynashuttle.models.DispatchBatch
import com.dynashuttle.models.ScheduleDirection
import java.time.Instant

/**
 * Typed result for [DispatchBatchRepository.acquireOrCreateBatch].
 *
 * Callers must handle every branch:
 *   - [Acquired]     — an existing COLLECTING batch was found and returned.
 *   - [Created]      — no eligible batch existed; a new one was created and returned.
 *   - [AlreadyLocked] — the batch for this window is READY or beyond; late requests
 *                        should be waitlisted or placed in the next window.
 *   - [CapacityFull] — the COLLECTING batch exists but has no remaining seats.
 */
sealed class BatchAcquisitionResult {
    data class Acquired(val batch: DispatchBatch) : BatchAcquisitionResult()
    data class Created(val batch: DispatchBatch) : BatchAcquisitionResult()
    /** Batch for this window is closed (READY or beyond). */
    object AlreadyLocked : BatchAcquisitionResult()
    /** Batch is still COLLECTING but has no remaining capacity. */
    object CapacityFull : BatchAcquisitionResult()
}

/**
 * Typed result for [DispatchBatchRepository.addRequestToBatch].
 */
sealed class BatchEnrollResult {
    object Success : BatchEnrollResult()
    /** Request was already enrolled in this batch (idempotent — safe to ignore). */
    object AlreadyEnrolled : BatchEnrollResult()
    /** Request is in a terminal or otherwise ineligible status (e.g., MATCHED, CANCELLED). */
    object RequestIneligible : BatchEnrollResult()
    /** The batch is closed (READY or beyond) — request cannot be added. */
    object BatchClosed : BatchEnrollResult()
    /** Batch capacity has been reached. */
    object BatchFull : BatchEnrollResult()
    /** Cross-company violation: request's company differs from batch's company. */
    object CompanyMismatch : BatchEnrollResult()
    object BatchNotFound : BatchEnrollResult()
    object RequestNotFound : BatchEnrollResult()
}

/**
 * Typed result for [DispatchBatchRepository.lockBatch].
 */
sealed class BatchLockResult {
    data class Locked(val batch: DispatchBatch) : BatchLockResult()
    /** Already locked / in READY or beyond state — safe to treat as success on retry. */
    object AlreadyLocked : BatchLockResult()
    object NotFound : BatchLockResult()
    /** Batch is in a terminal state (COMPLETED / CANCELLED). */
    object Terminal : BatchLockResult()
}

/**
 * Repository contract for [DispatchBatch] lifecycle management.
 *
 * Task 8.4 scope:
 *   - [acquireOrCreateBatch] — idempotent, concurrent-safe batch acquisition
 *   - [addRequestToBatch]    — enrolls a REQUESTED/QUEUED ride into an open batch
 *   - [lockBatch]            — COLLECTING → READY transition
 *   - [findById]             — lookup by ID
 *   - [countBatchMembers]    — how many ride requests belong to a batch
 *
 * Downstream operations (Task 8.5+):
 *   Vehicle assignment, route persistence, and ASSIGNING/DISPATCHED transitions
 *   belong to DispatchService and are NOT implemented here.
 */
interface DispatchBatchRepository {

    /**
     * Finds the currently COLLECTING batch for [companyId]/[scheduleId]/[direction]/[windowStartAt],
     * or creates one if none exists.
     *
     * Concurrent callers converge on the same batch through an INSERT-then-SELECT approach:
     * the first caller to INSERT wins; subsequent concurrent callers find the existing row
     * on conflict resolution.  The resulting batch is the single authoritative open window
     * for this company + schedule + direction + time-key combination.
     *
     * Returns [BatchAcquisitionResult.AlreadyLocked] when the matching batch is READY/beyond.
     * Returns [BatchAcquisitionResult.CapacityFull] when the matching batch is COLLECTING
     * but has no remaining seats.
     *
     * @param companyId      Tenant company UUID string.
     * @param scheduleId     Linked schedule UUID string, or null for ad-hoc batches.
     * @param direction      INBOUND or OUTBOUND.
     * @param windowStartAt  Inclusive start of the 300-second window.
     * @param windowClosesAt Inclusive end of the 300-second window.
     * @param maxCapacity    Vehicle seat capacity limit for this batch.
     */
    fun acquireOrCreateBatch(
        companyId: String,
        scheduleId: String?,
        direction: ScheduleDirection,
        windowStartAt: Instant,
        windowClosesAt: Instant,
        maxCapacity: Int
    ): BatchAcquisitionResult

    /**
     * Enrolls ride request [rideRequestId] into batch [batchId].
     *
     * Atomically (within a single transaction):
     *  1. Validates that the batch is COLLECTING and the request status is REQUESTED.
     *  2. Validates company isolation (request's company == batch's company).
     *  3. Checks that the batch has remaining capacity.
     *  4. Updates [ride_requests.batch_id], [ride_requests.queued_at], and
     *     [ride_requests.status] from REQUESTED → QUEUED.
     *  5. If the batch reaches [maxCapacity] after this enrolment, immediately
     *     transitions the batch from COLLECTING → READY (capacity-triggered close).
     *
     * Idempotent: if the request is already enrolled in this batch
     * (batch_id == batchId AND status == QUEUED), returns [BatchEnrollResult.AlreadyEnrolled].
     *
     * @param batchId       The target batch UUID string.
     * @param rideRequestId The ride request UUID string to enroll.
     * @param expiresAt     The absolute expiry timestamp for this ride request.
     */
    fun addRequestToBatch(
        batchId: String,
        rideRequestId: String,
        expiresAt: Instant
    ): BatchEnrollResult

    /**
     * Transitions batch [batchId] from COLLECTING → READY (window-close lock).
     *
     * Idempotent: if the batch is already READY (or any post-READY state),
     * returns [BatchLockResult.AlreadyLocked].  This handles the race where two
     * concurrent callers both observe the window as closed and race to lock.
     *
     * @param batchId The batch UUID string to lock.
     */
    fun lockBatch(batchId: String): BatchLockResult

    /**
     * Finds a batch by its UUID string. Returns null if not found.
     */
    fun findById(batchId: String): DispatchBatch?

    /**
     * Counts ride requests currently enrolled in batch [batchId]
     * (i.e., ride_requests WHERE batch_id = ? AND status = 'QUEUED').
     */
    fun countBatchMembers(batchId: String): Int
}
