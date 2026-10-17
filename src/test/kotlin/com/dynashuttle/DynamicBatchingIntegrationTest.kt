package com.dynashuttle

import com.dynashuttle.config.Env
import com.dynashuttle.models.*
import com.dynashuttle.repositories.BatchAcquisitionResult
import com.dynashuttle.repositories.BatchEnrollResult
import com.dynashuttle.repositories.BatchLockResult
import com.dynashuttle.repositories.PostgresDispatchBatchRepository
import com.dynashuttle.services.BatchingService
import com.dynashuttle.services.EnqueueResult
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.*

/**
 * Task 8.4 — PostgreSQL-backed Integration Tests for Dynamic Batching.
 *
 * Verifies real database semantics:
 * - Real row locking (`SELECT ... FOR UPDATE`)
 * - Concurrent batch acquisition convergence on a single database row via advisory locks
 * - Real transaction isolation and atomic enrolment (`UPDATE ride_requests SET status = 'QUEUED'`)
 * - Cross-company isolation at the SQL/foreign-key level
 * - Capacity auto-locking under real PostgreSQL concurrency
 * - Idempotency of batch locking
 * - Window expiry enforcement (rejection of requests past windowClosesAt)
 *
 * Skips via [assumeTrue] (marked as SKIPPED by JUnit/Gradle) if PostgreSQL is not reachable locally.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DynamicBatchingIntegrationTest {

    companion object {
        /**
         * Strict fail-closed allowlist of databases permitted to be dropped/recreated
         * by integration test setup. Never permits production or staging databases.
         */
        private val ALLOWED_TEST_DB_NAMES = setOf("dynashuttle_migration_test")
    }

    private val testDbName = "dynashuttle_migration_test"
    private val host = "localhost"
    private val port = 5432
    private val user = Env.get("DATABASE_USER", "postgres")
    private val password = Env.get("DATABASE_PASSWORD", "postgres")

    private val adminJdbcUrl = "jdbc:postgresql://$host:$port/postgres"
    private val testDbJdbcUrl = "jdbc:postgresql://$host:$port/$testDbName"

    private fun isPostgresAvailable(): Boolean {
        return try {
            DriverManager.getConnection(adminJdbcUrl, user, password).use { true }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Hardened database recreation guard:
     * - Refuses to drop any database not in [ALLOWED_TEST_DB_NAMES].
     * - Fails closed on any name containing forbidden environment tokens.
     * - Uses static, non-interpolated DDL to drop/create the dedicated test database.
     */
    private fun recreateTestDatabase() {
        check(testDbName in ALLOWED_TEST_DB_NAMES) {
            "Refusing to drop database '$testDbName'; only explicitly allowlisted test databases can be recreated: $ALLOWED_TEST_DB_NAMES"
        }
        check(!testDbName.contains("prod", ignoreCase = true) && !testDbName.contains("staging", ignoreCase = true) && !testDbName.contains("dev", ignoreCase = true)) {
            "Safety check failed: test database name '$testDbName' contains forbidden keyword"
        }

        DriverManager.getConnection(adminJdbcUrl, user, password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP DATABASE IF EXISTS dynashuttle_migration_test")
                stmt.execute("CREATE DATABASE dynashuttle_migration_test")
            }
        }
    }

    private fun createTestDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = testDbJdbcUrl
            username = user
            password = this@DynamicBatchingIntegrationTest.password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            isAutoCommit = true
        }
        return HikariDataSource(config)
    }

    /**
     * Initializes the live PostgreSQL test database.
     * Uses [assumeTrue] to ensure that if PostgreSQL is unreachable, JUnit and Gradle
     * mark the test as SKIPPED rather than silently passing.
     */
    private fun setupTestDb(): HikariDataSource {
        assumeTrue(
            "PostgreSQL not available on $host:$port; skipping live integration test.",
            isPostgresAvailable()
        )
        recreateTestDatabase()
        val ds = createTestDataSource()
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        Database.connect(ds)
        return ds
    }

    private fun insertCompany(name: String = "Acme Corp"): String {
        val cid = UUID.randomUUID()
        transaction {
            Companies.insert {
                it[id] = cid
                it[this.name] = name
                it[city] = "Bangalore"
                it[employeeCount] = 100
                it[createdAt] = Instant.now()
            }
        }
        return cid.toString()
    }

    private fun insertEmployee(companyId: String, email: String): String {
        val eid = UUID.randomUUID()
        transaction {
            Employees.insert {
                it[id] = eid
                it[this.companyId] = UUID.fromString(companyId)
                it[this.email] = email
                it[name] = "Test Employee $email"
                it[homeLat] = 12.9716
                it[homeLng] = 77.5946
                it[createdAt] = Instant.now()
            }
        }
        return eid.toString()
    }

    private fun insertRideRequest(
        employeeId: String,
        status: String = "REQUESTED"
    ): String {
        val rid = UUID.randomUUID()
        transaction {
            RideRequests.insert {
                it[id] = rid
                it[this.employeeId] = UUID.fromString(employeeId)
                it[pickupLat] = 12.9716
                it[pickupLng] = 77.5946
                it[destinationLat] = 12.9352
                it[destinationLng] = 77.6245
                it[this.status] = status
                it[requestedAt] = Instant.now()
            }
        }
        return rid.toString()
    }

    // -------------------------------------------------------------------------
    // Test 1: acquireOrCreateBatch idempotency on PostgreSQL
    // -------------------------------------------------------------------------
    @Test
    fun `Test 1 - acquireOrCreateBatch returns existing batch for identical window key`() {
        val ds = setupTestDb()
        try {
            val repo = PostgresDispatchBatchRepository()
            val companyId = insertCompany()
            val windowStart = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            val windowCloses = windowStart.plusSeconds(300)

            val res1 = repo.acquireOrCreateBatch(
                companyId = companyId,
                scheduleId = null,
                direction = ScheduleDirection.INBOUND,
                windowStartAt = windowStart,
                windowClosesAt = windowCloses,
                maxCapacity = 4
            )
            assertTrue(res1 is BatchAcquisitionResult.Created, "First creation call must return BatchAcquisitionResult.Created; got $res1")
            val batch1 = res1.batch

            val res2 = repo.acquireOrCreateBatch(
                companyId = companyId,
                scheduleId = null,
                direction = ScheduleDirection.INBOUND,
                windowStartAt = windowStart,
                windowClosesAt = windowCloses,
                maxCapacity = 4
            )
            assertTrue(res2 is BatchAcquisitionResult.Acquired, "Second call for same window must return BatchAcquisitionResult.Acquired; got $res2")
            val batch2 = res2.batch

            assertEquals(batch1.id, batch2.id, "Second acquisition for identical window must return the existing batch ID")
            assertEquals(BatchStatus.COLLECTING, batch2.status)
        } finally {
            ds.close()
        }
    }

    // -------------------------------------------------------------------------
    // Test 2: Concurrent acquireOrCreateBatch converges to exactly one row
    // -------------------------------------------------------------------------
    @Test
    fun `Test 2 - concurrent acquireOrCreateBatch converges to single batch row`() {
        val ds = setupTestDb()
        try {
            val repo = PostgresDispatchBatchRepository()
            val companyId = insertCompany()
            val windowStart = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            val windowCloses = windowStart.plusSeconds(300)

            val threadCount = 6
            val barrier = CyclicBarrier(threadCount)
            val executor = Executors.newFixedThreadPool(threadCount)
            val acquiredIds = mutableListOf<String>()

            val futures = (1..threadCount).map {
                executor.submit {
                    barrier.await()
                    val res = repo.acquireOrCreateBatch(
                        companyId = companyId,
                        scheduleId = null,
                        direction = ScheduleDirection.OUTBOUND,
                        windowStartAt = windowStart,
                        windowClosesAt = windowCloses,
                        maxCapacity = 6
                    )
                    synchronized(acquiredIds) {
                        when (res) {
                            is BatchAcquisitionResult.Acquired -> acquiredIds.add(res.batch.id)
                            is BatchAcquisitionResult.Created -> acquiredIds.add(res.batch.id)
                            else -> {}
                        }
                    }
                }
            }
            futures.forEach { it.get() }
            executor.shutdown()

            assertEquals(threadCount, acquiredIds.size)
            val uniqueIds = acquiredIds.toSet()
            assertEquals(1, uniqueIds.size, "All concurrent callers must acquire the exact same batch ID; got: $uniqueIds")

            // Verify in DB that only 1 row exists
            val countInDb = transaction {
                DispatchBatches.selectAll().where {
                    DispatchBatches.companyId eq UUID.fromString(companyId)
                }.count()
            }
            assertEquals(1L, countInDb, "Exactly one batch row must exist in the database")
        } finally {
            ds.close()
        }
    }

    // -------------------------------------------------------------------------
    // Test 3: Enqueue REQUESTED ride request updates DB fields correctly
    // -------------------------------------------------------------------------
    @Test
    fun `Test 3 - enqueueRequest transitions REQUESTED to QUEUED and populates batch_id and timestamps`() {
        val ds = setupTestDb()
        try {
            val repo = PostgresDispatchBatchRepository()
            val service = BatchingService(repo)
            val companyId = insertCompany()
            val empId = insertEmployee(companyId, "alice@acme.com")
            val reqId = insertRideRequest(empId)
            val windowStart = Instant.now().truncatedTo(ChronoUnit.SECONDS)

            val result = service.enqueueRequest(
                rideRequestId = reqId,
                companyId = companyId,
                scheduleId = null,
                direction = ScheduleDirection.INBOUND,
                windowStartAt = windowStart,
                maxCapacity = 4
            )

            assertTrue(result is EnqueueResult.Queued)
            val batch = result.batch
            assertEquals(BatchStatus.COLLECTING, batch.status)

            // Verify DB state of ride_requests
            transaction {
                val row = RideRequests.selectAll().where { RideRequests.id eq UUID.fromString(reqId) }.single()
                assertEquals("QUEUED", row[RideRequests.status])
                assertEquals(UUID.fromString(batch.id), row[RideRequests.batchId])
                assertNotNull(row[RideRequests.queuedAt])
                assertNotNull(row[RideRequests.expiresAt])
            }

            assertEquals(1, repo.countBatchMembers(batch.id))
        } finally {
            ds.close()
        }
    }

    // -------------------------------------------------------------------------
    // Test 4: Capacity auto-lock at DB level
    // -------------------------------------------------------------------------
    @Test
    fun `Test 4 - batch auto-locks to READY when capacity is reached`() {
        val ds = setupTestDb()
        try {
            val repo = PostgresDispatchBatchRepository()
            val service = BatchingService(repo)
            val companyId = insertCompany()
            val emp1 = insertEmployee(companyId, "emp1@acme.com")
            val emp2 = insertEmployee(companyId, "emp2@acme.com")
            val req1 = insertRideRequest(emp1)
            val req2 = insertRideRequest(emp2)
            val windowStart = Instant.now().truncatedTo(ChronoUnit.SECONDS)

            // Capacity = 2
            val res1 = service.enqueueRequest(
                rideRequestId = req1,
                companyId = companyId,
                scheduleId = null,
                direction = ScheduleDirection.INBOUND,
                windowStartAt = windowStart,
                maxCapacity = 2
            )
            assertTrue(res1 is EnqueueResult.Queued)
            assertEquals(BatchStatus.COLLECTING, res1.batch.status)

            val res2 = service.enqueueRequest(
                rideRequestId = req2,
                companyId = companyId,
                scheduleId = null,
                direction = ScheduleDirection.INBOUND,
                windowStartAt = windowStart,
                maxCapacity = 2
            )
            assertTrue(res2 is EnqueueResult.Queued)

            // The batch should now be READY in the DB
            val updatedBatch = repo.findById(res1.batch.id)
            assertNotNull(updatedBatch)
            assertEquals(BatchStatus.READY, updatedBatch.status, "Batch must auto-lock to READY upon reaching capacity")

            // Third request for same window should now fail or not be admitted to the full batch
            val emp3 = insertEmployee(companyId, "emp3@acme.com")
            val req3 = insertRideRequest(emp3)
            val res3 = service.enqueueRequest(
                rideRequestId = req3,
                companyId = companyId,
                scheduleId = null,
                direction = ScheduleDirection.INBOUND,
                windowStartAt = windowStart,
                maxCapacity = 2
            )
            assertTrue(res3 is EnqueueResult.BatchClosed || res3 is EnqueueResult.BatchFull,
                "Request should be rejected once batch is closed/full; got $res3")
        } finally {
            ds.close()
        }
    }

    // -------------------------------------------------------------------------
    // Test 5: Tenant isolation prevents cross-company enrollment
    // -------------------------------------------------------------------------
    @Test
    fun `Test 5 - tenant isolation prevents enrolling employee into another company's batch`() {
        val ds = setupTestDb()
        try {
            val repo = PostgresDispatchBatchRepository()
            val service = BatchingService(repo)
            val companyA = insertCompany("Company A")
            val companyB = insertCompany("Company B")

            val empA = insertEmployee(companyA, "emp@companya.com")
            val reqA = insertRideRequest(empA)

            val windowStart = Instant.now().truncatedTo(ChronoUnit.SECONDS)

            // Attempt to enqueue company A's ride request into company B's batch
            val result = service.enqueueRequest(
                rideRequestId = reqA,
                companyId = companyB, // Wrong company
                scheduleId = null,
                direction = ScheduleDirection.INBOUND,
                windowStartAt = windowStart,
                maxCapacity = 4
            )

            assertTrue(result is EnqueueResult.CompanyMismatch, "Cross-company enrollment must return CompanyMismatch; got $result")

            // Verify request status is untouched
            transaction {
                val row = RideRequests.selectAll().where { RideRequests.id eq UUID.fromString(reqA) }.single()
                assertEquals("REQUESTED", row[RideRequests.status])
                assertNull(row[RideRequests.batchId])
            }
        } finally {
            ds.close()
        }
    }

    // -------------------------------------------------------------------------
    // Test 6: Concurrent enrollment does not exceed capacity (with DB state assertions)
    // -------------------------------------------------------------------------
    @Test
    fun `Test 6 - concurrent enrollment respects maxCapacity under PostgreSQL row locking`() {
        val ds = setupTestDb()
        try {
            val repo = PostgresDispatchBatchRepository()
            val service = BatchingService(repo)
            val companyId = insertCompany()

            val maxCap = 3
            val requestCount = 6
            val reqIds = (1..requestCount).map { i ->
                val emp = insertEmployee(companyId, "user$i@acme.com")
                insertRideRequest(emp)
            }

            val windowStart = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            val barrier = CyclicBarrier(requestCount)
            val executor = Executors.newFixedThreadPool(requestCount)
            val results = mutableListOf<EnqueueResult>()

            val futures = reqIds.map { rid ->
                executor.submit {
                    barrier.await()
                    val res = service.enqueueRequest(
                        rideRequestId = rid,
                        companyId = companyId,
                        scheduleId = null,
                        direction = ScheduleDirection.INBOUND,
                        windowStartAt = windowStart,
                        maxCapacity = maxCap
                    )
                    synchronized(results) { results.add(res) }
                }
            }
            futures.forEach { it.get() }
            executor.shutdown()

            // 1. Service return value assertions
            val queuedCount = results.count { it is EnqueueResult.Queued }
            assertTrue(queuedCount <= maxCap, "Total enrolled requests ($queuedCount) must not exceed maxCapacity ($maxCap)")
            assertEquals(maxCap, queuedCount, "Exactly maxCapacity ($maxCap) requests should be successfully enrolled")

            val rejectedCount = results.count { it is EnqueueResult.BatchFull || it is EnqueueResult.BatchClosed }
            assertEquals(requestCount - maxCap, rejectedCount, "Overflow requests must receive BatchFull or BatchClosed")

            // 2. Identify the single target batch
            val enrolledBatchIds = results.filterIsInstance<EnqueueResult.Queued>().map { it.batch.id }.toSet()
            assertEquals(1, enrolledBatchIds.size, "All successfully queued requests must belong to the exact same batch")
            val batchId = enrolledBatchIds.single()

            // 3. PostgreSQL Persisted-State Assertions
            transaction {
                // A. Target batch must be persisted as READY in PostgreSQL
                val batchRow = DispatchBatches.selectAll().where { DispatchBatches.id eq UUID.fromString(batchId) }.single()
                assertEquals(BatchStatus.READY.name, batchRow[DispatchBatches.status], "Batch in DB must be auto-locked to READY")

                // B. Query all 6 requests directly from PostgreSQL
                val allRequests = reqIds.map { rid ->
                    RideRequests.selectAll().where { RideRequests.id eq UUID.fromString(rid) }.single()
                }

                // C. Exactly three requests are persisted as QUEUED in the target batch
                val queuedRows = allRequests.filter { it[RideRequests.status] == "QUEUED" }
                assertEquals(maxCap, queuedRows.size, "Exactly 3 requests must be persisted as QUEUED in DB")
                queuedRows.forEach { row ->
                    assertEquals(UUID.fromString(batchId), row[RideRequests.batchId], "Queued request must be assigned to batchId")
                    assertNotNull(row[RideRequests.queuedAt], "Queued request must have queuedAt timestamp")
                    assertNotNull(row[RideRequests.expiresAt], "Queued request must have expiresAt timestamp")
                }

                // D. The three rejected requests remain REQUESTED and have no batch assignment
                val rejectedRows = allRequests.filter { it[RideRequests.status] == "REQUESTED" }
                assertEquals(requestCount - maxCap, rejectedRows.size, "Exactly 3 requests must remain REQUESTED in DB")
                rejectedRows.forEach { row ->
                    assertNull(row[RideRequests.batchId], "Rejected request must have null batchId")
                    assertNull(row[RideRequests.queuedAt], "Rejected request must have null queuedAt")
                }

                // E. No request has been enrolled twice or assigned inconsistently
                val distinctRequestIds = allRequests.map { it[RideRequests.id] }.toSet()
                assertEquals(requestCount, distinctRequestIds.size, "All 6 request IDs must remain distinct")
            }
        } finally {
            ds.close()
        }
    }

    // -------------------------------------------------------------------------
    // Test 7: lockBatch idempotency
    // -------------------------------------------------------------------------
    @Test
    fun `Test 7 - lockBatch transitions COLLECTING to READY and is idempotent`() {
        val ds = setupTestDb()
        try {
            val repo = PostgresDispatchBatchRepository()
            val companyId = insertCompany()
            val windowStart = Instant.now().truncatedTo(ChronoUnit.SECONDS)
            val windowCloses = windowStart.plusSeconds(300)

            val acq = repo.acquireOrCreateBatch(
                companyId = companyId,
                scheduleId = null,
                direction = ScheduleDirection.INBOUND,
                windowStartAt = windowStart,
                windowClosesAt = windowCloses,
                maxCapacity = 4
            )
            assertTrue(acq is BatchAcquisitionResult.Created, "Initial batch creation must return Created; got $acq")
            val batchId = acq.batch.id

            // First lock: succeeds
            val lock1 = repo.lockBatch(batchId)
            assertTrue(lock1 is BatchLockResult.Locked)
            assertEquals(BatchStatus.READY, lock1.batch.status)

            // Second lock: already locked
            val lock2 = repo.lockBatch(batchId)
            assertTrue(lock2 is BatchLockResult.AlreadyLocked)
        } finally {
            ds.close()
        }
    }

    // -------------------------------------------------------------------------
    // Test 8: Elapsed-window enrollment rejection (deterministic past timestamp)
    // -------------------------------------------------------------------------
    @Test
    fun `Test 8 - enrollment into batch past windowClosesAt is rejected with BatchClosed`() {
        val ds = setupTestDb()
        try {
            val repo = PostgresDispatchBatchRepository()
            val companyId = insertCompany()
            val emp = insertEmployee(companyId, "latecomer@acme.com")
            val req = insertRideRequest(emp)

            // Deterministic past timestamps: window closed 300 seconds ago
            val pastStart = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.SECONDS)
            val pastCloses = Instant.now().minusSeconds(300).truncatedTo(ChronoUnit.SECONDS)

            val acq = repo.acquireOrCreateBatch(
                companyId = companyId,
                scheduleId = null,
                direction = ScheduleDirection.INBOUND,
                windowStartAt = pastStart,
                windowClosesAt = pastCloses,
                maxCapacity = 4
            )
            val batchId = when (acq) {
                is BatchAcquisitionResult.Created -> acq.batch.id
                is BatchAcquisitionResult.Acquired -> acq.batch.id
                else -> error("Expected batch acquisition or creation; got $acq")
            }

            // Attempt to enroll into the expired batch
            val enrollResult = repo.addRequestToBatch(
                batchId = batchId,
                rideRequestId = req,
                expiresAt = Instant.now().plusSeconds(300)
            )
            assertEquals(BatchEnrollResult.BatchClosed, enrollResult, "Enrolling into an expired window must return BatchClosed")

            // Verify in PostgreSQL that the request remains REQUESTED with null batch_id
            transaction {
                val row = RideRequests.selectAll().where { RideRequests.id eq UUID.fromString(req) }.single()
                assertEquals("REQUESTED", row[RideRequests.status])
                assertNull(row[RideRequests.batchId])
            }
        } finally {
            ds.close()
        }
    }
}
