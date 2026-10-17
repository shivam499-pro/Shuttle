package com.dynashuttle

import com.dynashuttle.models.BatchStatus
import com.dynashuttle.models.DispatchBatch
import com.dynashuttle.models.ScheduleDirection
import com.dynashuttle.repositories.BatchAcquisitionResult
import com.dynashuttle.repositories.BatchEnrollResult
import com.dynashuttle.repositories.BatchLockResult
import com.dynashuttle.repositories.DispatchBatchRepository
import com.dynashuttle.services.BatchingService
import com.dynashuttle.services.EnqueueResult
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

// =============================================================================
// In-memory fake repository for unit tests
// =============================================================================

/**
 * Thread-safe in-memory fake for [DispatchBatchRepository].
 *
 * Provides enough behaviour to verify [BatchingService] logic without a database.
 * Declared limitations:
 *   - SELECT FOR UPDATE locking is not simulated — concurrency tests here use
 *     @Synchronized to approximate mutual exclusion, not real DB isolation.
 *   - Real PostgreSQL duplicate-key / SKIP-LOCKED behaviour is NOT verified here.
 *     See [DynamicBatchingIntegrationTest] for DB-backed concurrency verification.
 */
private class InMemoryDispatchBatchRepository : DispatchBatchRepository {

    // batchId -> batch
    val batches = mutableMapOf<String, DispatchBatch>()
    // batchId -> set of enrolledRideRequestIds
    val members = mutableMapOf<String, MutableSet<String>>()
    // rideRequestId -> (status, batchId?)
    val requests = mutableMapOf<String, Pair<String, String?>>() // status, batchId

    @Synchronized
    override fun acquireOrCreateBatch(
        companyId: String,
        scheduleId: String?,
        direction: ScheduleDirection,
        windowStartAt: Instant,
        windowClosesAt: Instant,
        maxCapacity: Int
    ): BatchAcquisitionResult {
        val existing = batches.values.firstOrNull { b ->
            b.companyId == companyId &&
            b.scheduleId == scheduleId &&
            b.direction == direction &&
            b.windowStartAt == windowStartAt &&
            !b.status.isTerminal
        }

        if (existing != null) {
            return when {
                existing.status.isOpen -> {
                    val count = members[existing.id]?.size ?: 0
                    if (count >= existing.maxCapacity) BatchAcquisitionResult.CapacityFull
                    else BatchAcquisitionResult.Acquired(existing)
                }
                else -> BatchAcquisitionResult.AlreadyLocked
            }
        }

        val now = Instant.now()
        val newId = UUID.randomUUID().toString()
        val batch = DispatchBatch(
            id = newId,
            companyId = companyId,
            scheduleId = scheduleId,
            tripId = null,
            status = BatchStatus.COLLECTING,
            direction = direction,
            windowStartAt = windowStartAt,
            windowClosesAt = windowClosesAt,
            maxCapacity = maxCapacity,
            createdAt = now,
            updatedAt = now
        )
        batches[newId] = batch
        members[newId] = mutableSetOf()
        return BatchAcquisitionResult.Acquired(batch)
    }

    @Synchronized
    override fun addRequestToBatch(
        batchId: String,
        rideRequestId: String,
        expiresAt: Instant
    ): BatchEnrollResult {
        val batch = batches[batchId] ?: return BatchEnrollResult.BatchNotFound

        if (!batch.status.isOpen) return BatchEnrollResult.BatchClosed
        if (Instant.now().isAfter(batch.windowClosesAt)) return BatchEnrollResult.BatchClosed

        val (reqStatus, reqBatchId) = requests[rideRequestId]
            ?: return BatchEnrollResult.RequestNotFound

        // Idempotency
        if (reqBatchId == batchId && reqStatus == "QUEUED") return BatchEnrollResult.AlreadyEnrolled

        if (reqStatus != "REQUESTED") return BatchEnrollResult.RequestIneligible

        val memberSet = members.getOrPut(batchId) { mutableSetOf() }
        if (memberSet.size >= batch.maxCapacity) return BatchEnrollResult.BatchFull

        memberSet.add(rideRequestId)
        requests[rideRequestId] = Pair("QUEUED", batchId)

        // Auto-lock if capacity reached
        if (memberSet.size >= batch.maxCapacity) {
            batches[batchId] = batch.copy(status = BatchStatus.READY, updatedAt = Instant.now())
        }

        return BatchEnrollResult.Success
    }

    @Synchronized
    override fun lockBatch(batchId: String): BatchLockResult {
        val batch = batches[batchId] ?: return BatchLockResult.NotFound
        if (batch.status.isTerminal) return BatchLockResult.Terminal
        if (!batch.status.isOpen) return BatchLockResult.AlreadyLocked
        val locked = batch.copy(status = BatchStatus.READY, updatedAt = Instant.now())
        batches[batchId] = locked
        return BatchLockResult.Locked(locked)
    }

    override fun findById(batchId: String): DispatchBatch? = batches[batchId]

    override fun countBatchMembers(batchId: String): Int = members[batchId]?.size ?: 0
}

// =============================================================================
// Unit tests
// =============================================================================

class DynamicBatchingServiceUnitTest {

    private val companyA = "company-a-${UUID.randomUUID()}"
    private val companyB = "company-b-${UUID.randomUUID()}"
    private val scheduleId = UUID.randomUUID().toString()
    private val window = Instant.now()

    private fun makeRepo(): InMemoryDispatchBatchRepository {
        val repo = InMemoryDispatchBatchRepository()
        // Pre-populate some ride requests in REQUESTED state
        repo.requests["req-1"] = Pair("REQUESTED", null)
        repo.requests["req-2"] = Pair("REQUESTED", null)
        repo.requests["req-3"] = Pair("REQUESTED", null)
        repo.requests["req-4"] = Pair("REQUESTED", null)
        repo.requests["req-5"] = Pair("REQUESTED", null)
        // req-matched is already MATCHED — ineligible
        repo.requests["req-matched"] = Pair("MATCHED", null)
        // req-cancelled is CANCELLED — ineligible
        repo.requests["req-cancelled"] = Pair("CANCELLED", null)
        return repo
    }

    private fun makeService(repo: InMemoryDispatchBatchRepository) =
        BatchingService(batchRepository = repo, batchWindowSeconds = 300L, expiryMultiplier = 2L)

    // -------------------------------------------------------------------------
    // Test A: Basic enqueue — happy path
    // -------------------------------------------------------------------------
    @Test
    fun `A - enqueueRequest transitions REQUESTED to QUEUED and returns Queued`() {
        val repo = makeRepo()
        val service = makeService(repo)

        val result = service.enqueueRequest(
            rideRequestId = "req-1",
            companyId = companyA,
            scheduleId = scheduleId,
            direction = ScheduleDirection.INBOUND,
            windowStartAt = window,
            maxCapacity = 4
        )

        assertIs<EnqueueResult.Queued>(result)
        val batch = result.batch
        assertEquals(BatchStatus.COLLECTING, batch.status, "Batch should still be COLLECTING (1/4 filled)")
        assertEquals("QUEUED", repo.requests["req-1"]?.first, "Request status should be QUEUED")
        assertEquals(1, repo.countBatchMembers(batch.id))
    }

    // -------------------------------------------------------------------------
    // Test B: Multiple requests join the same batch
    // -------------------------------------------------------------------------
    @Test
    fun `B - multiple requests join the same open batch`() {
        val repo = makeRepo()
        val service = makeService(repo)

        val r1 = service.enqueueRequest("req-1", companyA, scheduleId, ScheduleDirection.INBOUND, window, 4)
        val r2 = service.enqueueRequest("req-2", companyA, scheduleId, ScheduleDirection.INBOUND, window, 4)
        val r3 = service.enqueueRequest("req-3", companyA, scheduleId, ScheduleDirection.INBOUND, window, 4)

        assertIs<EnqueueResult.Queued>(r1)
        assertIs<EnqueueResult.Queued>(r2)
        assertIs<EnqueueResult.Queued>(r3)

        val batchId1 = r1.batch.id
        val batchId2 = r2.batch.id
        val batchId3 = r3.batch.id

        assertEquals(batchId1, batchId2, "All requests should join the same batch")
        assertEquals(batchId2, batchId3, "All requests should join the same batch")
        assertEquals(3, repo.countBatchMembers(batchId1))
    }

    // -------------------------------------------------------------------------
    // Test C: Idempotent enqueue — same request twice
    // -------------------------------------------------------------------------
    @Test
    fun `C - enqueueRequest twice for same request returns AlreadyQueued on second call`() {
        val repo = makeRepo()
        val service = makeService(repo)

        val first = service.enqueueRequest("req-1", companyA, scheduleId, ScheduleDirection.INBOUND, window, 4)
        val second = service.enqueueRequest("req-1", companyA, scheduleId, ScheduleDirection.INBOUND, window, 4)

        assertIs<EnqueueResult.Queued>(first)
        assertIs<EnqueueResult.AlreadyQueued>(second)
        // Member count must not double-count
        val batchId = first.batch.id
        assertEquals(1, repo.countBatchMembers(batchId), "Idempotent enroll must not duplicate membership")
    }

    // -------------------------------------------------------------------------
    // Test D: Capacity-triggered auto-lock
    // -------------------------------------------------------------------------
    @Test
    fun `D - batch auto-locks to READY when capacity is reached`() {
        val repo = makeRepo()
        val service = makeService(repo)
        val capacity = 3

        service.enqueueRequest("req-1", companyA, scheduleId, ScheduleDirection.INBOUND, window, capacity)
        service.enqueueRequest("req-2", companyA, scheduleId, ScheduleDirection.INBOUND, window, capacity)
        val last = service.enqueueRequest("req-3", companyA, scheduleId, ScheduleDirection.INBOUND, window, capacity)

        assertIs<EnqueueResult.Queued>(last)
        val batchId = last.batch.id
        val batch = repo.findById(batchId)!!
        assertEquals(BatchStatus.READY, batch.status, "Batch should be auto-locked to READY after reaching capacity")
    }

    // -------------------------------------------------------------------------
    // Test E: Locked batch rejects new requests
    // -------------------------------------------------------------------------
    @Test
    fun `E - READY batch rejects new requests`() {
        val repo = makeRepo()
        val service = makeService(repo)

        // Fill and auto-lock
        service.enqueueRequest("req-1", companyA, scheduleId, ScheduleDirection.INBOUND, window, 2)
        service.enqueueRequest("req-2", companyA, scheduleId, ScheduleDirection.INBOUND, window, 2)

        // Now try adding a 3rd request — batch is locked
        val result = service.enqueueRequest("req-3", companyA, scheduleId, ScheduleDirection.INBOUND, window, 2)

        assertTrue(
            result is EnqueueResult.BatchClosed || result is EnqueueResult.BatchFull,
            "READY batch must reject new requests, got: $result"
        )
    }

    // -------------------------------------------------------------------------
    // Test F: Ineligible request status rejected
    // -------------------------------------------------------------------------
    @Test
    fun `F - MATCHED request rejected as ineligible`() {
        val repo = makeRepo()
        val service = makeService(repo)

        val result = service.enqueueRequest("req-matched", companyA, scheduleId, ScheduleDirection.INBOUND, window, 4)
        assertIs<EnqueueResult.RequestIneligible>(result)
    }

    @Test
    fun `F2 - CANCELLED request rejected as ineligible`() {
        val repo = makeRepo()
        val service = makeService(repo)

        val result = service.enqueueRequest("req-cancelled", companyA, scheduleId, ScheduleDirection.INBOUND, window, 4)
        assertIs<EnqueueResult.RequestIneligible>(result)
    }

    // -------------------------------------------------------------------------
    // Test G: Unknown request rejected
    // -------------------------------------------------------------------------
    @Test
    fun `G - unknown rideRequestId returns NotFound`() {
        val repo = makeRepo()
        val service = makeService(repo)

        // First, get a batch created
        service.enqueueRequest("req-1", companyA, scheduleId, ScheduleDirection.INBOUND, window, 4)
        // Now try a nonexistent request ID by interacting with the repo directly
        repo.requests.remove("req-1") // simulate it not existing in this batch
        val batchId = repo.batches.values.first().id
        val directResult = repo.addRequestToBatch(batchId, "nonexistent-req", Instant.now().plusSeconds(600))
        assertIs<BatchEnrollResult.RequestNotFound>(directResult)
    }

    // -------------------------------------------------------------------------
    // Test H: Explicit batch close (window-elapsed lock)
    // -------------------------------------------------------------------------
    @Test
    fun `H - closeBatch transitions COLLECTING to READY`() {
        val repo = makeRepo()
        val service = makeService(repo)

        val r = service.enqueueRequest("req-1", companyA, scheduleId, ScheduleDirection.INBOUND, window, 4)
        val batchId = (r as EnqueueResult.Queued).batch.id

        val closed = service.closeBatch(batchId)
        assertNotNull(closed)
        assertEquals(BatchStatus.READY, closed.status)
    }

    // -------------------------------------------------------------------------
    // Test I: Closing already-locked batch is idempotent
    // -------------------------------------------------------------------------
    @Test
    fun `I - closeBatch on already-READY batch is idempotent`() {
        val repo = makeRepo()
        val service = makeService(repo)

        service.enqueueRequest("req-1", companyA, scheduleId, ScheduleDirection.INBOUND, window, 1)
        // Capacity=1 → auto-locked
        val batchId = repo.batches.values.first().id
        assertEquals(BatchStatus.READY, repo.batches[batchId]?.status)

        // Second close must not throw or corrupt state
        val result = service.closeBatch(batchId)
        assertNotNull(result)
        assertEquals(BatchStatus.READY, result.status)
    }

    // -------------------------------------------------------------------------
    // Test J: Requests from different companies never share a batch
    // -------------------------------------------------------------------------
    @Test
    fun `J - requests from different companies go into separate batches`() {
        val repo = makeRepo()
        // req-5 belongs to companyB (simulated)
        repo.requests["req-5"] = Pair("REQUESTED", null)
        val service = makeService(repo)

        val rA = service.enqueueRequest("req-1", companyA, scheduleId, ScheduleDirection.INBOUND, window, 4)
        val rB = service.enqueueRequest("req-5", companyB, scheduleId, ScheduleDirection.INBOUND, window, 4)

        assertIs<EnqueueResult.Queued>(rA)
        assertIs<EnqueueResult.Queued>(rB)

        val batchIdA = rA.batch.id
        val batchIdB = rB.batch.id

        assertNotEquals(batchIdA, batchIdB, "Requests from different companies must be in separate batches")
        assertEquals(companyA, repo.batches[batchIdA]?.companyId)
        assertEquals(companyB, repo.batches[batchIdB]?.companyId)
    }

    // -------------------------------------------------------------------------
    // Test K: INBOUND and OUTBOUND batches are separate per company
    // -------------------------------------------------------------------------
    @Test
    fun `K - INBOUND and OUTBOUND direction creates separate batches for same company`() {
        val repo = makeRepo()
        val service = makeService(repo)

        val rIn = service.enqueueRequest("req-1", companyA, scheduleId, ScheduleDirection.INBOUND, window, 4)
        val rOut = service.enqueueRequest("req-2", companyA, scheduleId, ScheduleDirection.OUTBOUND, window, 4)

        assertIs<EnqueueResult.Queued>(rIn)
        assertIs<EnqueueResult.Queued>(rOut)

        val batchIdIn = rIn.batch.id
        val batchIdOut = rOut.batch.id

        assertNotEquals(batchIdIn, batchIdOut, "INBOUND and OUTBOUND must produce separate batches")
    }

    // -------------------------------------------------------------------------
    // Test L: lockBatch — direct repository call
    // -------------------------------------------------------------------------
    @Test
    fun `L - lockBatch transitions COLLECTING to READY`() {
        val repo = makeRepo()
        val batchId = UUID.randomUUID().toString()
        val now = Instant.now()
        repo.batches[batchId] = DispatchBatch(
            id = batchId,
            companyId = companyA,
            scheduleId = null,
            tripId = null,
            status = BatchStatus.COLLECTING,
            direction = ScheduleDirection.INBOUND,
            windowStartAt = now,
            windowClosesAt = now.plusSeconds(300),
            maxCapacity = 4,
            createdAt = now,
            updatedAt = now
        )

        val result = repo.lockBatch(batchId)
        assertIs<BatchLockResult.Locked>(result)
        assertEquals(BatchStatus.READY, result.batch.status)
    }

    @Test
    fun `L2 - lockBatch on READY batch returns AlreadyLocked`() {
        val repo = makeRepo()
        val batchId = UUID.randomUUID().toString()
        val now = Instant.now()
        repo.batches[batchId] = DispatchBatch(
            id = batchId, companyId = companyA, scheduleId = null, tripId = null,
            status = BatchStatus.READY, direction = ScheduleDirection.INBOUND,
            windowStartAt = now, windowClosesAt = now.plusSeconds(300), maxCapacity = 4,
            createdAt = now, updatedAt = now
        )

        val result = repo.lockBatch(batchId)
        assertIs<BatchLockResult.AlreadyLocked>(result)
    }

    @Test
    fun `L3 - lockBatch on CANCELLED batch returns Terminal`() {
        val repo = makeRepo()
        val batchId = UUID.randomUUID().toString()
        val now = Instant.now()
        repo.batches[batchId] = DispatchBatch(
            id = batchId, companyId = companyA, scheduleId = null, tripId = null,
            status = BatchStatus.CANCELLED, direction = ScheduleDirection.INBOUND,
            windowStartAt = now, windowClosesAt = now.plusSeconds(300), maxCapacity = 4,
            createdAt = now, updatedAt = now
        )

        val result = repo.lockBatch(batchId)
        assertIs<BatchLockResult.Terminal>(result)
    }

    // -------------------------------------------------------------------------
    // Test M: Member count consistency
    // -------------------------------------------------------------------------
    @Test
    fun `M - member count is consistent after multiple enrolments`() {
        val repo = makeRepo()
        val service = makeService(repo)

        repeat(3) { i ->
            service.enqueueRequest("req-${i + 1}", companyA, scheduleId, ScheduleDirection.INBOUND, window, 4)
        }

        val batchId = repo.batches.values.first().id
        assertEquals(3, service.batchMemberCount(batchId))
    }

    // -------------------------------------------------------------------------
    // Test N: Concurrent enqueue (unit-level with synchronized fake)
    // Note: this test verifies logic correctness; real DB concurrency is verified
    // in DynamicBatchingIntegrationTest (if PostgreSQL is available).
    // -------------------------------------------------------------------------
    @Test
    fun `N - concurrent enqueue of same request produces exactly one QUEUED outcome`() {
        val repo = makeRepo()
        val service = makeService(repo)

        val threadCount = 8
        val barrier = CyclicBarrier(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val successCount = AtomicInteger(0)
        val alreadyQueuedCount = AtomicInteger(0)

        val futures = (1..threadCount).map {
            executor.submit {
                barrier.await() // start simultaneously
                val result = service.enqueueRequest(
                    "req-1", companyA, scheduleId, ScheduleDirection.INBOUND, window, 4
                )
                when (result) {
                    is EnqueueResult.Queued -> successCount.incrementAndGet()
                    is EnqueueResult.AlreadyQueued -> alreadyQueuedCount.incrementAndGet()
                    else -> {}
                }
            }
        }
        futures.forEach { it.get() }
        executor.shutdown()

        assertEquals(1, successCount.get(), "Exactly 1 enrolment must succeed; got success=${successCount.get()}")
        assertEquals(threadCount - 1, alreadyQueuedCount.get(), "All other concurrent calls must report AlreadyQueued; got alreadyQueued=${alreadyQueuedCount.get()}")
        assertEquals("QUEUED", repo.requests["req-1"]?.first)
    }

    // -------------------------------------------------------------------------
    // Test O: BatchStatus helpers
    // -------------------------------------------------------------------------
    @Test
    fun `O - BatchStatus isOpen and isTerminal flags are correct`() {
        assertTrue(BatchStatus.COLLECTING.isOpen)
        assertFalse(BatchStatus.READY.isOpen)
        assertFalse(BatchStatus.COMPLETED.isOpen)
        assertFalse(BatchStatus.CANCELLED.isOpen)

        assertFalse(BatchStatus.COLLECTING.isTerminal)
        assertFalse(BatchStatus.READY.isTerminal)
        assertTrue(BatchStatus.COMPLETED.isTerminal)
        assertTrue(BatchStatus.CANCELLED.isTerminal)
    }

    // -------------------------------------------------------------------------
    // Test P: Ad-hoc batch (no scheduleId)
    // -------------------------------------------------------------------------
    @Test
    fun `P - ad-hoc batch (null scheduleId) is created and accepts requests`() {
        val repo = makeRepo()
        val service = makeService(repo)

        val result = service.enqueueRequest(
            rideRequestId = "req-1",
            companyId = companyA,
            scheduleId = null,
            direction = ScheduleDirection.INBOUND,
            windowStartAt = window,
            maxCapacity = 4
        )
        assertIs<EnqueueResult.Queued>(result)
        val batch = result.batch
        assertNull(batch.scheduleId)
    }

    // -------------------------------------------------------------------------
    // Test Q: BatchStatus.fromString roundtrip
    // -------------------------------------------------------------------------
    @Test
    fun `Q - BatchStatus fromString is case-insensitive and roundtrips cleanly`() {
        assertEquals(BatchStatus.COLLECTING, BatchStatus.fromString("COLLECTING"))
        assertEquals(BatchStatus.READY, BatchStatus.fromString("ready"))
        assertEquals(BatchStatus.CANCELLED, BatchStatus.fromString("Cancelled"))
    }

    // -------------------------------------------------------------------------
    // Test R: findBatch returns null for unknown id
    // -------------------------------------------------------------------------
    @Test
    fun `R - findBatch returns null for unknown batchId`() {
        val repo = makeRepo()
        val service = makeService(repo)
        assertNull(service.findBatch(UUID.randomUUID().toString()))
    }

    // -------------------------------------------------------------------------
    // Test S: Two windows create two separate batches for same company/schedule
    // -------------------------------------------------------------------------
    @Test
    fun `S - different windowStartAt produces separate batches for same company and schedule`() {
        val repo = makeRepo()
        val service = makeService(repo)
        val window2 = window.plusSeconds(300)

        val r1 = service.enqueueRequest("req-1", companyA, scheduleId, ScheduleDirection.INBOUND, window, 4)
        val r2 = service.enqueueRequest("req-2", companyA, scheduleId, ScheduleDirection.INBOUND, window2, 4)

        assertIs<EnqueueResult.Queued>(r1)
        assertIs<EnqueueResult.Queued>(r2)
        val b1 = r1.batch.id
        val b2 = r2.batch.id
        assertNotEquals(b1, b2, "Different windows must produce separate batches")
    }

    // -------------------------------------------------------------------------
    // Test T: Batch capacity of 1 auto-locks on first enrolment
    // -------------------------------------------------------------------------
    @Test
    fun `T - capacity-1 batch auto-locks to READY on first enrolment`() {
        val repo = makeRepo()
        val service = makeService(repo)

        val result = service.enqueueRequest("req-1", companyA, scheduleId, ScheduleDirection.INBOUND, window, 1)
        assertIs<EnqueueResult.Queued>(result)

        val batchId = result.batch.id
        val batch = repo.findById(batchId)!!
        assertEquals(BatchStatus.READY, batch.status, "Capacity-1 batch must auto-lock after first enrolment")
    }

    // -------------------------------------------------------------------------
    // Test U: Enrollment into expired batch is rejected with BatchClosed
    // -------------------------------------------------------------------------
    @Test
    fun `U - enrollment into expired batch is rejected with BatchClosed`() {
        val repo = makeRepo()
        val expiredBatchId = UUID.randomUUID().toString()
        val past = Instant.now().minusSeconds(600)
        repo.batches[expiredBatchId] = DispatchBatch(
            id = expiredBatchId,
            companyId = companyA,
            scheduleId = scheduleId,
            tripId = null,
            status = BatchStatus.COLLECTING,
            direction = ScheduleDirection.INBOUND,
            windowStartAt = past,
            windowClosesAt = past.plusSeconds(300), // closed 300 seconds ago
            maxCapacity = 4,
            createdAt = past,
            updatedAt = past
        )

        val result = repo.addRequestToBatch(expiredBatchId, "req-1", Instant.now().plusSeconds(300))
        assertEquals(BatchEnrollResult.BatchClosed, result, "Enrollment into expired window must return BatchClosed")
    }
}
