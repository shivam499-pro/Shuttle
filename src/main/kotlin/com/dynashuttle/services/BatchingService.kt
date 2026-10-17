package com.dynashuttle.services

import com.dynashuttle.models.BatchStatus
import com.dynashuttle.models.DispatchBatch
import com.dynashuttle.models.RideStatus
import com.dynashuttle.models.ScheduleDirection
import com.dynashuttle.repositories.BatchAcquisitionResult
import com.dynashuttle.repositories.BatchEnrollResult
import com.dynashuttle.repositories.BatchLockResult
import com.dynashuttle.repositories.DispatchBatchRepository
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Typed result returned by [BatchingService.enqueueRequest].
 */
sealed class EnqueueResult {
    /** Request successfully transitioned REQUESTED → QUEUED and enrolled in the batch. */
    data class Queued(val batch: DispatchBatch) : EnqueueResult()
    /** Request was already QUEUED in this batch — idempotent success. */
    data class AlreadyQueued(val batch: DispatchBatch) : EnqueueResult()
    /** Batch for the given window is closed; caller should try the next window. */
    object BatchClosed : EnqueueResult()
    /** Batch has no remaining seats. */
    object BatchFull : EnqueueResult()
    /** Request's company does not match the batch's company. */
    object CompanyMismatch : EnqueueResult()
    /** Request is in a status that prevents enrolment (not REQUESTED). */
    object RequestIneligible : EnqueueResult()
    /** Batch or request row was not found. */
    object NotFound : EnqueueResult()
}

/**
 * Service responsible for the COLLECTING phase of the dispatch batch lifecycle.
 *
 * **Task 8.4 scope:** batch acquisition (find-or-create) and request enrolment
 * (REQUESTED → QUEUED).  The service does NOT:
 *  - Assign vehicles
 *  - Persist route stops
 *  - Transition batches beyond READY
 *  - Publish outbox events (reserved for Task 8.5+)
 *
 * **Batch window semantics (Task 7.7, Section 9):**
 *  - Schedule-linked batch:
 *      `window_start_at = departureTime - windowLeadSeconds`
 *      `window_closes_at = window_start_at + batchWindowSeconds`
 *  - Ad-hoc batch (no schedule):
 *      `window_start_at = NOW()`
 *      `window_closes_at = NOW() + batchWindowSeconds`
 *
 * **Concurrency:**
 *  - [acquireOrCreateBatch] and [enqueueRequest] each delegate to the repository
 *    which uses PostgreSQL row-level locking to guarantee correct behaviour
 *    under concurrent callers.  Unit tests using in-memory fakes verify
 *    logic correctness; database-backed integration tests verify real concurrency.
 *
 * @param batchRepository  Repository providing atomic batch lifecycle operations.
 * @param batchWindowSeconds   Duration of a batch collection window in seconds (default 300).
 * @param expiryMultiplier     `expires_at = now + (batchWindowSeconds * expiryMultiplier)` (default 2×).
 */
class BatchingService(
    private val batchRepository: DispatchBatchRepository,
    private val batchWindowSeconds: Long = DEFAULT_BATCH_WINDOW_SECONDS,
    private val expiryMultiplier: Long = DEFAULT_EXPIRY_MULTIPLIER
) {
    private val logger = LoggerFactory.getLogger(BatchingService::class.java)

    companion object {
        /**
         * Default 300-second batch window from the frozen research baseline
         * (Task 7.7 Section 3: dynamicBatchWindowSeconds = 300).
         */
        const val DEFAULT_BATCH_WINDOW_SECONDS = 300L

        /**
         * Default expiry multiplier: 2× the batch window (600 seconds).
         * Task 7.7 Section 9.5: absolute expiry = 2 × batch window.
         */
        const val DEFAULT_EXPIRY_MULTIPLIER = 2L
    }

    // -------------------------------------------------------------------------
    // Primary service API
    // -------------------------------------------------------------------------

    /**
     * Acquires or creates the currently open batch for [companyId]/[scheduleId]/[direction].
     *
     * Returns the [DispatchBatch] that is ready to accept new ride requests, or null if
     * no eligible batch exists (e.g., the window is closed, or capacity is exhausted).
     *
     * @param companyId      Tenant company UUID string.
     * @param scheduleId     Linked schedule UUID string, or null for ad-hoc batches.
     * @param direction      INBOUND or OUTBOUND.
     * @param windowStartAt  Inclusive start of the batch window.
     * @param maxCapacity    Maximum number of ride requests in this batch.
     */
    fun acquireOrCreateBatch(
        companyId: String,
        scheduleId: String?,
        direction: ScheduleDirection,
        windowStartAt: Instant,
        maxCapacity: Int
    ): DispatchBatch? {
        val windowClosesAt = windowStartAt.plusSeconds(batchWindowSeconds)

        return when (val result = batchRepository.acquireOrCreateBatch(
            companyId = companyId,
            scheduleId = scheduleId,
            direction = direction,
            windowStartAt = windowStartAt,
            windowClosesAt = windowClosesAt,
            maxCapacity = maxCapacity
        )) {
            is BatchAcquisitionResult.Acquired -> {
                logger.debug("Acquired existing COLLECTING batch ${result.batch.id} for company=$companyId direction=$direction")
                result.batch
            }
            is BatchAcquisitionResult.Created -> {
                logger.info("Created new COLLECTING batch ${result.batch.id} for company=$companyId schedule=$scheduleId direction=$direction window=$windowStartAt")
                result.batch
            }
            is BatchAcquisitionResult.AlreadyLocked -> {
                logger.debug("Batch for company=$companyId direction=$direction window=$windowStartAt is closed (READY or beyond)")
                null
            }
            is BatchAcquisitionResult.CapacityFull -> {
                logger.debug("Batch for company=$companyId direction=$direction window=$windowStartAt is full")
                null
            }
        }
    }

    /**
     * Finds or creates the appropriate open batch for a given window, then atomically
     * enrols [rideRequestId] into it, transitioning the request from REQUESTED → QUEUED.
     *
     * This is the primary ingestion entry point for the new-dispatch flow.
     * The caller provides the window key (company, schedule, direction, windowStartAt)
     * and vehicle capacity; this method handles everything else.
     *
     * **Idempotency:** calling this method twice for the same request returns
     * [EnqueueResult.AlreadyQueued] on the second call without corrupting state.
     *
     * **Batch auto-lock:** if adding this request fills the batch to [maxCapacity],
     * the batch is immediately transitioned to READY — no separate call needed.
     *
     * @param rideRequestId  UUID string of the ride request to enqueue.
     * @param companyId      Tenant company UUID string.
     * @param scheduleId     Linked schedule UUID string, or null for ad-hoc.
     * @param direction      INBOUND or OUTBOUND.
     * @param windowStartAt  Start of the batch window.
     * @param maxCapacity    Vehicle seat limit for this batch.
     */
    fun enqueueRequest(
        rideRequestId: String,
        companyId: String,
        scheduleId: String?,
        direction: ScheduleDirection,
        windowStartAt: Instant,
        maxCapacity: Int
    ): EnqueueResult {
        // Acquire (or create) the open batch.
        val batch = acquireOrCreateBatch(
            companyId = companyId,
            scheduleId = scheduleId,
            direction = direction,
            windowStartAt = windowStartAt,
            maxCapacity = maxCapacity
        ) ?: return EnqueueResult.BatchClosed

        // Compute expiry: batchWindowSeconds × expiryMultiplier after now.
        val expiresAt = Instant.now().plusSeconds(batchWindowSeconds * expiryMultiplier)

        return when (val enroll = batchRepository.addRequestToBatch(
            batchId = batch.id,
            rideRequestId = rideRequestId,
            expiresAt = expiresAt
        )) {
            is BatchEnrollResult.Success -> {
                logger.info("Ride request $rideRequestId QUEUED into batch ${batch.id}")
                // Reload batch to reflect any auto-lock that may have occurred
                val refreshed = batchRepository.findById(batch.id) ?: batch
                EnqueueResult.Queued(refreshed)
            }
            is BatchEnrollResult.AlreadyEnrolled -> {
                logger.debug("Ride request $rideRequestId already enrolled in batch ${batch.id} — idempotent")
                val refreshed = batchRepository.findById(batch.id) ?: batch
                EnqueueResult.AlreadyQueued(refreshed)
            }
            is BatchEnrollResult.BatchClosed  -> EnqueueResult.BatchClosed
            is BatchEnrollResult.BatchFull    -> EnqueueResult.BatchFull
            is BatchEnrollResult.CompanyMismatch -> EnqueueResult.CompanyMismatch
            is BatchEnrollResult.RequestIneligible -> EnqueueResult.RequestIneligible
            is BatchEnrollResult.BatchNotFound -> EnqueueResult.NotFound
            is BatchEnrollResult.RequestNotFound -> EnqueueResult.NotFound
        }
    }

    /**
     * Explicitly closes a COLLECTING batch by transitioning it to READY.
     *
     * Called when the 300-second wall-clock window elapses (future: by a background worker).
     * Idempotent — safe to call even if the batch is already READY.
     *
     * @param batchId UUID string of the batch to lock.
     * @return The updated [DispatchBatch] in READY state, or null if not found / terminal.
     */
    fun closeBatch(batchId: String): DispatchBatch? {
        return when (val result = batchRepository.lockBatch(batchId)) {
            is BatchLockResult.Locked -> {
                logger.info("Batch $batchId transitioned COLLECTING → READY")
                result.batch
            }
            is BatchLockResult.AlreadyLocked -> {
                logger.debug("Batch $batchId was already READY or beyond — idempotent close")
                batchRepository.findById(batchId)
            }
            is BatchLockResult.NotFound -> {
                logger.warn("closeBatch called for unknown batch $batchId")
                null
            }
            is BatchLockResult.Terminal -> {
                logger.warn("closeBatch called for terminal batch $batchId — ignoring")
                null
            }
        }
    }

    /**
     * Returns a [DispatchBatch] by ID, or null if not found.
     */
    fun findBatch(batchId: String): DispatchBatch? = batchRepository.findById(batchId)

    /**
     * Returns the current number of QUEUED members in a batch.
     */
    fun batchMemberCount(batchId: String): Int = batchRepository.countBatchMembers(batchId)
}
