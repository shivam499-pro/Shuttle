package com.dynashuttle.repositories

import com.dynashuttle.models.BatchStatus
import com.dynashuttle.models.DispatchBatch
import com.dynashuttle.models.DispatchBatches
import com.dynashuttle.models.Employees
import com.dynashuttle.models.RideRequests
import com.dynashuttle.models.RideStatus
import com.dynashuttle.models.ScheduleDirection
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * PostgreSQL/Exposed implementation of [DispatchBatchRepository].
 *
 * Concurrency model (Task 7.7, Section 14):
 *
 * [acquireOrCreateBatch]:
 *   Uses INSERT-on-conflict-do-nothing + SELECT under a transaction to guarantee
 *   that concurrent callers converge on the same COLLECTING batch row for a given
 *   (company_id, schedule_id, direction, window_start_at) combination.
 *   The idempotency key is (company_id, schedule_id, direction, window_start_at);
 *   for ad-hoc batches (null schedule_id) the caller owns the window key.
 *
 * [addRequestToBatch]:
 *   A single atomic transaction that:
 *     1. SELECTs the batch FOR UPDATE (row lock).
 *     2. Validates status == COLLECTING, counts current members vs maxCapacity.
 *     3. SELECTs the ride request FOR UPDATE, validates status == REQUESTED and
 *        company isolation (via JOIN to employees).
 *     4. UPDATEs ride_requests: status=QUEUED, batch_id, queued_at, expires_at.
 *     5. If the batch is now full, UPDATEs dispatch_batches: status=READY.
 *   All through raw JDBC on the same connection for correctness with Exposed.
 *
 * [lockBatch]:
 *   Single conditional UPDATE: status=READY WHERE id=? AND status='COLLECTING'.
 *   Idempotent — if already READY or beyond, reports AlreadyLocked.
 */
class PostgresDispatchBatchRepository : DispatchBatchRepository {

    private val logger = LoggerFactory.getLogger(PostgresDispatchBatchRepository::class.java)

    // -------------------------------------------------------------------------
    // Row mapping
    // -------------------------------------------------------------------------

    private fun toDispatchBatch(row: ResultRow): DispatchBatch = DispatchBatch(
        id = row[DispatchBatches.id].value.toString(),
        companyId = row[DispatchBatches.companyId].value.toString(),
        scheduleId = row[DispatchBatches.scheduleId]?.toString(),
        tripId = row[DispatchBatches.tripId]?.toString(),
        status = BatchStatus.fromString(row[DispatchBatches.status]),
        direction = ScheduleDirection.valueOf(row[DispatchBatches.direction]),
        windowStartAt = row[DispatchBatches.windowStartAt],
        windowClosesAt = row[DispatchBatches.windowClosesAt],
        maxCapacity = row[DispatchBatches.maxCapacity],
        assignedVehicleId = row[DispatchBatches.assignedVehicleId]?.toString(),
        assignedDriverId = row[DispatchBatches.assignedDriverId]?.toString(),
        workerId = row[DispatchBatches.workerId],
        lockedAt = row[DispatchBatches.lockedAt],
        retryCount = row[DispatchBatches.retryCount],
        createdAt = row[DispatchBatches.createdAt],
        updatedAt = row[DispatchBatches.updatedAt]
    )

    // -------------------------------------------------------------------------
    // findById
    // -------------------------------------------------------------------------

    override fun findById(batchId: String): DispatchBatch? = transaction {
        val uuid = runCatching { UUID.fromString(batchId) }.getOrNull() ?: return@transaction null
        DispatchBatches
            .selectAll()
            .where { DispatchBatches.id eq uuid }
            .map { toDispatchBatch(it) }
            .singleOrNull()
    }

    // -------------------------------------------------------------------------
    // countBatchMembers
    // -------------------------------------------------------------------------

    override fun countBatchMembers(batchId: String): Int = transaction {
        val uuid = runCatching { UUID.fromString(batchId) }.getOrNull() ?: return@transaction 0
        val jdbcConn = this.connection.connection as Connection
        val sql = """
            SELECT COUNT(*) FROM ride_requests
            WHERE batch_id = ? AND status = 'QUEUED'
        """.trimIndent()
        jdbcConn.prepareStatement(sql).use { ps ->
            ps.setObject(1, uuid)
            val rs = ps.executeQuery()
            if (rs.next()) rs.getInt(1) else 0
        }
    }

    // -------------------------------------------------------------------------
    // acquireOrCreateBatch
    // -------------------------------------------------------------------------

    /**
     * Finds or creates the COLLECTING batch for a given window key.
     *
     * The window key is (company_id, schedule_id, direction, window_start_at):
     *   - For schedule-linked batches: all four columns are the key.
     *   - For ad-hoc batches (scheduleId=null): (company_id, NULL, direction, window_start_at).
     *
     * Concurrency: all operations run in a single serializable-level transaction.
     * The INSERT uses ON CONFLICT DO NOTHING; the subsequent SELECT reads whatever
     * row won the INSERT race. This is deterministic and lock-free on the batch table.
     */
    override fun acquireOrCreateBatch(
        companyId: String,
        scheduleId: String?,
        direction: ScheduleDirection,
        windowStartAt: Instant,
        windowClosesAt: Instant,
        maxCapacity: Int
    ): BatchAcquisitionResult = transaction {
        val companyUuid = runCatching { UUID.fromString(companyId) }.getOrNull()
            ?: return@transaction BatchAcquisitionResult.AlreadyLocked // defensive
        val scheduleUuid = scheduleId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        val jdbcConn = this.connection.connection as Connection

        // Acquire transaction-scoped advisory lock on the unique window key.
        // This guarantees that concurrent callers for the same window serialize their
        // check-and-create operations, preventing duplicate batch rows without requiring
        // a database schema migration.
        val lockKey = "dispatch_batch:$companyId:${direction.name}:${windowStartAt.epochSecond}:${scheduleId ?: "adhoc"}"
        jdbcConn.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))").use { ps ->
            ps.setString(1, lockKey)
            ps.execute()
        }

        val selectSql = """
            SELECT id, company_id, schedule_id, trip_id, status, direction,
                   window_start_at, window_closes_at, dispatched_at, max_capacity,
                   assigned_vehicle_id, assigned_driver_id, worker_id,
                   locked_at, retry_count, created_at, updated_at
            FROM dispatch_batches
            WHERE company_id = ?
              AND direction = ?
              AND window_start_at = ?
              AND ${if (scheduleId != null) "schedule_id = ?" else "schedule_id IS NULL"}
              AND status NOT IN ('COMPLETED', 'CANCELLED')
            ORDER BY created_at ASC
            LIMIT 1
            FOR UPDATE
        """.trimIndent()

        var batch: DispatchBatch? = null

        jdbcConn.prepareStatement(selectSql).use { ps ->
            var idx = 1
            ps.setObject(idx++, companyUuid)
            ps.setString(idx++, direction.name)
            ps.setTimestamp(idx++, Timestamp.from(windowStartAt))
            if (scheduleUuid != null) ps.setObject(idx, scheduleUuid)
            val rs = ps.executeQuery()
            if (rs.next()) {
                batch = mapBatchFromResultSet(rs)
            }
        }

        var isNew = false
        if (batch == null) {
            val insertSql = """
                INSERT INTO dispatch_batches
                    (id, company_id, schedule_id, direction, status,
                     window_start_at, window_closes_at, max_capacity,
                     created_at, updated_at)
                VALUES
                    (gen_random_uuid(), ?, ?, ?, 'COLLECTING',
                     ?, ?, ?,
                     NOW(), NOW())
            """.trimIndent()

            jdbcConn.prepareStatement(insertSql).use { ps ->
                var idx = 1
                ps.setObject(idx++, companyUuid)
                if (scheduleUuid != null) ps.setObject(idx++, scheduleUuid) else ps.setNull(idx++, java.sql.Types.OTHER)
                ps.setString(idx++, direction.name)
                ps.setTimestamp(idx++, Timestamp.from(windowStartAt))
                ps.setTimestamp(idx++, Timestamp.from(windowClosesAt))
                ps.setInt(idx, maxCapacity)
                ps.executeUpdate()
            }
            isNew = true

            // Re-select the freshly inserted row under lock
            jdbcConn.prepareStatement(selectSql).use { ps ->
                var idx = 1
                ps.setObject(idx++, companyUuid)
                ps.setString(idx++, direction.name)
                ps.setTimestamp(idx++, Timestamp.from(windowStartAt))
                if (scheduleUuid != null) ps.setObject(idx, scheduleUuid)
                val rs = ps.executeQuery()
                if (rs.next()) {
                    batch = mapBatchFromResultSet(rs)
                }
            }
        }

        val resolved = batch ?: return@transaction BatchAcquisitionResult.AlreadyLocked

        return@transaction when (resolved.status) {
            BatchStatus.COLLECTING -> {
                val memberCount = countBatchMembersInConnection(jdbcConn, resolved.id)
                if (memberCount >= resolved.maxCapacity) {
                    BatchAcquisitionResult.CapacityFull
                } else if (isNew) {
                    BatchAcquisitionResult.Created(resolved)
                } else {
                    BatchAcquisitionResult.Acquired(resolved)
                }
            }
            BatchStatus.READY, BatchStatus.ASSIGNING, BatchStatus.OPTIMIZING,
            BatchStatus.DISPATCHED, BatchStatus.EXECUTING ->
                BatchAcquisitionResult.AlreadyLocked
            BatchStatus.COMPLETED, BatchStatus.CANCELLED ->
                BatchAcquisitionResult.AlreadyLocked
        }
    }

    // -------------------------------------------------------------------------
    // addRequestToBatch
    // -------------------------------------------------------------------------

    /**
     * Atomically enrolls a ride request into an open batch.
     *
     * Locking order (always consistent to prevent deadlocks):
     *   1. Lock dispatch_batches row (FOR UPDATE).
     *   2. Lock ride_requests row (FOR UPDATE).
     *
     * This consistent locking order prevents deadlocks when concurrent callers
     * add different requests to the same batch simultaneously.
     */
    override fun addRequestToBatch(
        batchId: String,
        rideRequestId: String,
        expiresAt: Instant
    ): BatchEnrollResult = transaction {
        val batchUuid = runCatching { UUID.fromString(batchId) }.getOrNull()
            ?: return@transaction BatchEnrollResult.BatchNotFound
        val requestUuid = runCatching { UUID.fromString(rideRequestId) }.getOrNull()
            ?: return@transaction BatchEnrollResult.RequestNotFound

        val jdbcConn = this.connection.connection as Connection

        // Step 1: Lock and read the batch row.
        val batchSql = """
            SELECT id, company_id, schedule_id, trip_id, status, direction,
                   window_start_at, window_closes_at, dispatched_at, max_capacity,
                   assigned_vehicle_id, assigned_driver_id, worker_id,
                   locked_at, retry_count, created_at, updated_at
            FROM dispatch_batches
            WHERE id = ?
            FOR UPDATE
        """.trimIndent()

        var batch: DispatchBatch? = null
        jdbcConn.prepareStatement(batchSql).use { ps ->
            ps.setObject(1, batchUuid)
            val rs = ps.executeQuery()
            if (rs.next()) batch = mapBatchFromResultSet(rs)
        }

        val resolvedBatch = batch ?: return@transaction BatchEnrollResult.BatchNotFound

        if (resolvedBatch.status.isTerminal) {
            return@transaction BatchEnrollResult.BatchClosed
        }
        if (!resolvedBatch.status.isOpen) {
            return@transaction BatchEnrollResult.BatchClosed
        }
        if (Instant.now().isAfter(resolvedBatch.windowClosesAt)) {
            return@transaction BatchEnrollResult.BatchClosed
        }

        // Step 2: Lock and read the ride request row (with company isolation via employees JOIN).
        val requestSql = """
            SELECT rr.id, rr.status, rr.batch_id, e.company_id AS employee_company_id
            FROM ride_requests rr
            JOIN employees e ON e.id = rr.employee_id
            WHERE rr.id = ?
            FOR UPDATE OF rr
        """.trimIndent()

        var requestStatus: String? = null
        var requestBatchId: UUID? = null
        var requestCompanyId: UUID? = null

        jdbcConn.prepareStatement(requestSql).use { ps ->
            ps.setObject(1, requestUuid)
            val rs = ps.executeQuery()
            if (rs.next()) {
                requestStatus = rs.getString("status")
                requestBatchId = rs.getObject("batch_id") as? UUID
                requestCompanyId = rs.getObject("employee_company_id") as? UUID
            }
        }

        if (requestStatus == null) {
            return@transaction BatchEnrollResult.RequestNotFound
        }

        // Tenant isolation check
        val batchCompanyUuid = runCatching { UUID.fromString(resolvedBatch.companyId) }.getOrNull()
        if (requestCompanyId != batchCompanyUuid) {
            logger.warn(
                "Cross-company batch enrolment blocked: " +
                "request $rideRequestId (company=$requestCompanyId) -> batch $batchId (company=$batchCompanyUuid)"
            )
            return@transaction BatchEnrollResult.CompanyMismatch
        }

        // Idempotency check: already enrolled in this batch
        if (requestBatchId == batchUuid && requestStatus == RideStatus.QUEUED.name) {
            return@transaction BatchEnrollResult.AlreadyEnrolled
        }

        // Status eligibility: only REQUESTED → QUEUED is permitted here
        if (requestStatus != RideStatus.REQUESTED.name) {
            return@transaction BatchEnrollResult.RequestIneligible
        }

        // Capacity check (re-count under lock to prevent TOCTOU)
        val currentCount = countBatchMembersInConnection(jdbcConn, batchId)
        if (currentCount >= resolvedBatch.maxCapacity) {
            return@transaction BatchEnrollResult.BatchFull
        }

        val now = Instant.now()

        // Step 3: Atomically enrol the ride request.
        val updateRequestSql = """
            UPDATE ride_requests
            SET status = 'QUEUED',
                batch_id = ?,
                queued_at = ?,
                expires_at = ?
            WHERE id = ? AND status = 'REQUESTED'
        """.trimIndent()

        val updated = jdbcConn.prepareStatement(updateRequestSql).use { ps ->
            ps.setObject(1, batchUuid)
            ps.setTimestamp(2, Timestamp.from(now))
            ps.setTimestamp(3, Timestamp.from(expiresAt))
            ps.setObject(4, requestUuid)
            ps.executeUpdate()
        }

        if (updated == 0) {
            // Concurrent transition already happened — treat as ineligible
            return@transaction BatchEnrollResult.RequestIneligible
        }

        // Step 4: If adding this request fills the batch, immediately lock it (capacity trigger).
        val newCount = currentCount + 1
        if (newCount >= resolvedBatch.maxCapacity) {
            val lockSql = """
                UPDATE dispatch_batches
                SET status = 'READY', updated_at = ?
                WHERE id = ? AND status = 'COLLECTING'
            """.trimIndent()
            jdbcConn.prepareStatement(lockSql).use { ps ->
                ps.setTimestamp(1, Timestamp.from(now))
                ps.setObject(2, batchUuid)
                ps.executeUpdate()
            }
            logger.info("Batch $batchId reached capacity ($newCount/${ resolvedBatch.maxCapacity}) — auto-locked to READY")
        }

        BatchEnrollResult.Success
    }

    // -------------------------------------------------------------------------
    // lockBatch
    // -------------------------------------------------------------------------

    /**
     * Transitions batch from COLLECTING → READY (window-close trigger).
     *
     * Uses a single conditional UPDATE for atomicity and idempotency.
     * If already READY or beyond, returns [BatchLockResult.AlreadyLocked].
     */
    override fun lockBatch(batchId: String): BatchLockResult = transaction {
        val batchUuid = runCatching { UUID.fromString(batchId) }.getOrNull()
            ?: return@transaction BatchLockResult.NotFound

        val jdbcConn = this.connection.connection as Connection

        // Conditional UPDATE: only transitions from COLLECTING.
        val updateSql = """
            UPDATE dispatch_batches
            SET status = 'READY', updated_at = ?
            WHERE id = ? AND status = 'COLLECTING'
        """.trimIndent()

        val updated = jdbcConn.prepareStatement(updateSql).use { ps ->
            ps.setTimestamp(1, Timestamp.from(Instant.now()))
            ps.setObject(2, batchUuid)
            ps.executeUpdate()
        }

        if (updated == 1) {
            val locked = findById(batchId) ?: return@transaction BatchLockResult.NotFound
            return@transaction BatchLockResult.Locked(locked)
        }

        // 0 rows affected — check current state
        val checkSql = "SELECT status FROM dispatch_batches WHERE id = ?"
        var currentStatus: String? = null
        jdbcConn.prepareStatement(checkSql).use { ps ->
            ps.setObject(1, batchUuid)
            val rs = ps.executeQuery()
            if (rs.next()) currentStatus = rs.getString("status")
        }

        return@transaction when {
            currentStatus == null -> BatchLockResult.NotFound
            currentStatus == "COMPLETED" || currentStatus == "CANCELLED" -> BatchLockResult.Terminal
            else -> BatchLockResult.AlreadyLocked  // READY, ASSIGNING, etc.
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Counts QUEUED members for a batch on an already-open JDBC connection. */
    private fun countBatchMembersInConnection(conn: Connection, batchId: String): Int {
        val batchUuid = runCatching { UUID.fromString(batchId) }.getOrNull() ?: return 0
        val sql = "SELECT COUNT(*) FROM ride_requests WHERE batch_id = ? AND status = 'QUEUED'"
        return conn.prepareStatement(sql).use { ps ->
            ps.setObject(1, batchUuid)
            val rs = ps.executeQuery()
            if (rs.next()) rs.getInt(1) else 0
        }
    }

    /** Maps a JDBC ResultSet row to a [DispatchBatch]. Caller must ensure columns are selected. */
    private fun mapBatchFromResultSet(rs: java.sql.ResultSet): DispatchBatch {
        val statusStr = rs.getString("status")
        val batchStatus = runCatching { BatchStatus.fromString(statusStr) }
            .getOrElse { BatchStatus.COLLECTING }

        return DispatchBatch(
            id = (rs.getObject("id") as UUID).toString(),
            companyId = (rs.getObject("company_id") as UUID).toString(),
            scheduleId = (rs.getObject("schedule_id") as? UUID)?.toString(),
            tripId = (rs.getObject("trip_id") as? UUID)?.toString(),
            status = batchStatus,
            direction = ScheduleDirection.valueOf(rs.getString("direction")),
            windowStartAt = rs.getTimestamp("window_start_at").toInstant(),
            windowClosesAt = rs.getTimestamp("window_closes_at").toInstant(),
            maxCapacity = rs.getInt("max_capacity"),
            assignedVehicleId = (rs.getObject("assigned_vehicle_id") as? UUID)?.toString(),
            assignedDriverId = (rs.getObject("assigned_driver_id") as? UUID)?.toString(),
            workerId = rs.getString("worker_id"),
            lockedAt = rs.getTimestamp("locked_at")?.toInstant(),
            retryCount = rs.getInt("retry_count"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }
}
