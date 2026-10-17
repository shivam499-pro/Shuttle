package com.dynashuttle

import com.dynashuttle.config.Env
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DatabaseMigrationTest {

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

    private fun recreateTestDatabase() {
        DriverManager.getConnection(adminJdbcUrl, user, password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP DATABASE IF EXISTS $testDbName")
                stmt.execute("CREATE DATABASE $testDbName")
            }
        }
    }

    private fun createTestDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = testDbJdbcUrl
            username = user
            password = this@DatabaseMigrationTest.password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 5
            isAutoCommit = true
        }
        return HikariDataSource(config)
    }

    private fun ensureMigratedV3(ds: HikariDataSource) {
        val flyway = Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .target("3")
            .load()
        flyway.migrate()
    }

    private fun executeSqlScript(conn: Connection, file: File) {
        val lines = file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("--") }
            .joinToString(" ")
        val statements = lines.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        for (sql in statements) {
            conn.createStatement().use { it.execute(sql) }
        }
    }


    @Test
    fun `Test 1 - Flyway initializes clean database through V1, V2, and V3 migrations`() {
        if (!isPostgresAvailable()) {
            println("PostgreSQL not available on $host:$port; skipping live migration test.")
            return
        }

        recreateTestDatabase()

        val ds = createTestDataSource()
        try {
            val flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()

            val result = flyway.migrate()

            // V1 through V8 should execute on a clean database
            assertTrue(result.success, "Flyway migration should succeed")
            assertEquals(8, result.migrationsExecuted, "V1 through V8 should execute on a clean database")
            assertEquals("8", result.targetSchemaVersion, "Target schema version should be 8")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test 2 - Migrated database contains all 7 core tables and PostGIS extension`() {
        if (!isPostgresAvailable()) return

        val ds = createTestDataSource()
        try {
            ds.connection.use { conn ->
                val tables = getTables(conn)
                val expectedTables = listOf(
                    "companies",
                    "employees",
                    "drivers",
                    "vehicles",
                    "trips",
                    "ride_requests",
                    "admins",
                    "pickup_zones",
                    "shuttle_schedules",
                    "dispatch_batches",
                    "trip_stops",
                    "vehicle_operational_state",
                    "outbox_events"
                )

                for (expected in expectedTables) {
                    assertTrue(tables.contains(expected), "Table '$expected' must exist in migrated schema")
                }

                // Verify PostGIS extension
                val extensions = getExtensions(conn)
                assertTrue(extensions.contains("postgis"), "PostGIS extension must be active in migrated schema")
            }
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test 3 - Required foreign key, composite, and spatial indexes exist`() {
        if (!isPostgresAvailable()) return

        val ds = createTestDataSource()
        try {
            ds.connection.use { conn ->
                val indexes = getIndexes(conn)

                // Foreign key indexes
                val expectedFkIndexes = listOf(
                    "idx_employees_company_id",
                    "idx_drivers_company_id",
                    "idx_vehicles_company_id",
                    "idx_admins_company_id",
                    "idx_trips_vehicle_id",
                    "idx_trips_driver_id",
                    "idx_ride_requests_trip_id",
                    "idx_ride_requests_employee_id",
                    "idx_pickup_zones_company_id",
                    "idx_employees_pickup_zone_id",
                    "idx_ride_requests_pickup_zone_id",
                    "idx_shuttle_schedules_company_id",
                    "idx_trips_schedule_id",
                    "idx_trips_trip_date",
                    "idx_dispatch_batches_company_id",
                    "idx_dispatch_batches_company_status_window",
                    "idx_dispatch_batches_schedule_status",
                    "idx_dispatch_batches_assigned_vehicle_id",
                    "idx_dispatch_batches_trip_id",
                    "idx_dispatch_batches_status",
                    "idx_trip_stops_trip_id",
                    "idx_trip_stops_trip_sequence",
                    "idx_trip_stops_ride_request_id",
                    "idx_trip_stops_company_id",
                    "idx_trip_stops_company_status",
                    "idx_trip_stops_batch_id",
                    "idx_vehicle_operational_state_company_status",
                    "idx_vehicle_operational_state_active_trip_id",
                    "idx_vehicle_operational_state_active_batch_id",
                    "idx_outbox_events_published_created",
                    "idx_outbox_events_company_id",
                    "idx_outbox_events_trip_id",
                    "idx_ride_requests_batch_id"
                )
                for (idx in expectedFkIndexes) {
                    assertTrue(indexes.contains(idx), "Foreign key index '$idx' must exist")
                }

                // Composite & multi-tenant indexes
                val expectedCompositeIndexes = listOf(
                    "idx_trips_vehicle_status",
                    "idx_ride_requests_trip_status",
                    "idx_ride_requests_employee_status",
                    "idx_pickup_zones_company_active"
                )
                for (idx in expectedCompositeIndexes) {
                    assertTrue(indexes.contains(idx), "Composite index '$idx' must exist")
                }

                // Spatial GiST index
                assertTrue(indexes.contains("idx_vehicles_location_gist"), "Spatial GiST index 'idx_vehicles_location_gist' must exist")
            }
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test 4 - Domain integrity check constraints exist on tables`() {
        if (!isPostgresAvailable()) return

        val ds = createTestDataSource()
        try {
            ds.connection.use { conn ->
                val constraints = getCheckConstraints(conn)

                val expectedConstraints = listOf(
                    "chk_companies_employee_count",
                    "chk_employees_home_lat",
                    "chk_employees_home_lng",
                    "chk_vehicles_seat_capacity",
                    "chk_vehicles_last_known_lat",
                    "chk_vehicles_last_known_lng",
                    "chk_trips_status",
                    "chk_ride_requests_pickup_lat",
                    "chk_ride_requests_pickup_lng",
                    "chk_ride_requests_dest_lat",
                    "chk_ride_requests_dest_lng",
                    "chk_ride_requests_status",
                    "chk_pickup_zones_latitude",
                    "chk_pickup_zones_longitude",
                    "chk_pickup_zones_radius",
                    "chk_pickup_zones_name",
                    "chk_dispatch_batches_status",
                    "chk_dispatch_batches_direction",
                    "chk_dispatch_batches_max_capacity",
                    "chk_dispatch_batches_retry_count",
                    "chk_dispatch_batches_window",
                    "chk_trip_stops_stop_type",
                    "chk_trip_stops_sequence_order",
                    "chk_trip_stops_latitude",
                    "chk_trip_stops_longitude",
                    "chk_trip_stops_status",
                    "chk_vehicle_operational_state_status",
                    "chk_outbox_events_event_type",
                    "chk_outbox_events_retry_count"
                )

                for (chk in expectedConstraints) {
                    assertTrue(constraints.contains(chk), "Check constraint '$chk' must exist in schema")
                }
            }
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test 5 - Seed test data script executes cleanly against migrated schema`() {
        if (!isPostgresAvailable()) return

        val ds = createTestDataSource()
        try {
            ensureMigratedV3(ds)
            val seedFile = File("scripts/seed_test_data.sql")
            assertTrue(seedFile.exists(), "scripts/seed_test_data.sql must exist")

            ds.connection.use { conn ->
                executeSqlScript(conn, seedFile)
            }
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test 6 - Flyway migration is idempotent on re-run`() {
        if (!isPostgresAvailable()) return

        val ds = createTestDataSource()
        try {
            val flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
            flyway.migrate()

            val result = flyway.migrate()
            // When already up to date, 0 migrations are executed
            assertEquals(0, result.migrationsExecuted, "Subsequent migration run should execute 0 migrations")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test S - V3 migration executes cleanly and leaves schema at version 3`() {
        if (!isPostgresAvailable()) return

        recreateTestDatabase()
        val ds = createTestDataSource()
        try {
            val flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("3")
                .load()

            val result = flyway.migrate()
            assertTrue(result.success, "Flyway migration should succeed")
            val info = flyway.info()
            val current = info.current()
            assertNotNull(current, "Current migration must not be null")
            assertEquals("3", current.version.version, "Target schema version should be 3")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test X - V4 migration executes cleanly and leaves schema at version 4`() {
        if (!isPostgresAvailable()) return

        recreateTestDatabase()
        val ds = createTestDataSource()
        try {
            val flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("4")
                .load()

            val result = flyway.migrate()
            assertTrue(result.success, "Flyway migration should succeed")
            val info = flyway.info()
            val current = info.current()
            assertNotNull(current, "Current migration must not be null")
            assertEquals("4", current.version.version, "Target schema version should be 4")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test Y - V5 migration executes cleanly and leaves schema at version 5`() {
        if (!isPostgresAvailable()) return

        recreateTestDatabase()
        val ds = createTestDataSource()
        try {
            val flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("5")
                .load()

            val result = flyway.migrate()
            assertTrue(result.success, "V5 Flyway migration should succeed")
            val info = flyway.info()
            val current = info.current()
            assertNotNull(current, "Current migration must not be null")
            assertEquals("5", current.version.version, "Target schema version should be 5")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test Z - V6 migration executes cleanly and leaves schema at version 6`() {
        if (!isPostgresAvailable()) return

        recreateTestDatabase()
        val ds = createTestDataSource()
        try {
            val flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("6")
                .load()

            val result = flyway.migrate()
            assertTrue(result.success, "V6 Flyway migration should succeed")
            val info = flyway.info()
            val current = info.current()
            assertNotNull(current, "Current migration must not be null")
            assertEquals("6", current.version.version, "Target schema version should be 6")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test V6 - V6 schema constraints and nullability on trips table`() {
        if (!isPostgresAvailable()) return

        recreateTestDatabase()
        val ds = createTestDataSource()
        try {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()

            ds.connection.use { conn ->
                conn.prepareStatement(
                    """
                    SELECT column_name, is_nullable
                    FROM information_schema.columns
                    WHERE table_name = 'trips'
                    """.trimIndent()
                ).use { ps ->
                    val rs = ps.executeQuery()
                    val nullability = mutableMapOf<String, String>()
                    while (rs.next()) {
                        nullability[rs.getString("column_name")] = rs.getString("is_nullable")
                    }
                    assertEquals("NO", nullability["schedule_id"], "trips.schedule_id must be NOT NULL")
                    assertEquals("NO", nullability["trip_date"], "trips.trip_date must be NOT NULL")
                    assertEquals("YES", nullability["vehicle_id"], "trips.vehicle_id must be nullable")
                    assertEquals("YES", nullability["driver_id"], "trips.driver_id must be nullable")
                }

                conn.prepareStatement(
                    "SELECT conname FROM pg_constraint WHERE conname = 'uq_trips_schedule_date'"
                ).use { ps ->
                    val rs = ps.executeQuery()
                    assertTrue(rs.next(), "Constraint 'uq_trips_schedule_date' must exist")
                }
            }
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test ZA - V7 migration executes cleanly and leaves schema at version 7`() {
        if (!isPostgresAvailable()) return

        recreateTestDatabase()
        val ds = createTestDataSource()
        try {
            val flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("7")
                .load()

            val result = flyway.migrate()
            assertTrue(result.success, "V7 Flyway migration should succeed")
            val info = flyway.info()
            val current = info.current()
            assertNotNull(current, "Current migration must not be null")
            assertEquals("7", current.version.version, "Target schema version should be 7")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test V7 - V7 schema constraints, dispatch batches, trip stops, vehicle state, outbox`() {
        if (!isPostgresAvailable()) return

        recreateTestDatabase()
        val ds = createTestDataSource()
        try {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()

            val companyId = UUID.fromString("11111111-1111-1111-1111-111111111111")
            val employeeId = UUID.fromString("22222222-2222-2222-2222-222222222222")
            val vehicleId = UUID.fromString("44444444-4444-4444-4444-444444444444")
            val tripId = UUID.fromString("55555555-5555-5555-5555-555555555555")

            ds.connection.use { conn ->
                executeSqlScript(conn, File("scripts/seed_test_data.sql"))

                // 1. Dispatch Batches: valid status accepted
                val batchId = UUID.randomUUID()
                conn.createStatement().use { stmt ->
                    stmt.execute("""
                        INSERT INTO dispatch_batches (id, company_id, status, direction, window_start_at, window_closes_at, max_capacity, created_at, updated_at)
                        VALUES ('$batchId', '$companyId', 'OPEN', 'INBOUND', NOW(), NOW() + interval '5 minutes', 4, NOW(), NOW())
                    """.trimIndent())
                }

                // 2. Dispatch Batches: invalid status rejected
                assertFailsWith<java.sql.SQLException> {
                    conn.createStatement().use { stmt ->
                        stmt.execute("""
                            INSERT INTO dispatch_batches (id, company_id, status, direction, window_start_at, window_closes_at, max_capacity, created_at, updated_at)
                            VALUES ('${UUID.randomUUID()}', '$companyId', 'INVALID_STATUS', 'INBOUND', NOW(), NOW() + interval '5 minutes', 4, NOW(), NOW())
                        """.trimIndent())
                    }
                }

                // 3. Ride Requests: QUEUED and EXPIRED accepted under V7
                val rideReqId = UUID.randomUUID()
                conn.createStatement().use { stmt ->
                    stmt.execute("""
                        INSERT INTO ride_requests (id, trip_id, employee_id, batch_id, pickup_lat, pickup_lng, destination_lat, destination_lng, status, requested_at, queued_at)
                        VALUES ('$rideReqId', '$tripId', '$employeeId', '$batchId', 12.97, 77.59, 12.98, 77.60, 'QUEUED', NOW(), NOW())
                    """.trimIndent())
                }

                // 4. Trip Stops: valid PICKUP and DROPOFF accepted
                val stop1Id = UUID.randomUUID()
                val stop2Id = UUID.randomUUID()
                conn.createStatement().use { stmt ->
                    stmt.execute("""
                        INSERT INTO trip_stops (id, trip_id, batch_id, ride_request_id, company_id, stop_type, sequence_order, latitude, longitude, status, created_at, updated_at)
                        VALUES ('$stop1Id', '$tripId', '$batchId', '$rideReqId', '$companyId', 'PICKUP', 1, 12.97, 77.59, 'PLANNED', NOW(), NOW())
                    """.trimIndent())
                    stmt.execute("""
                        INSERT INTO trip_stops (id, trip_id, batch_id, ride_request_id, company_id, stop_type, sequence_order, latitude, longitude, status, created_at, updated_at)
                        VALUES ('$stop2Id', '$tripId', '$batchId', '$rideReqId', '$companyId', 'DROPOFF', 2, 12.98, 77.60, 'PLANNED', NOW(), NOW())
                    """.trimIndent())
                }

                // 5. Trip Stops: invalid stop_type rejected
                assertFailsWith<java.sql.SQLException> {
                    conn.createStatement().use { stmt ->
                        stmt.execute("""
                            INSERT INTO trip_stops (id, trip_id, batch_id, ride_request_id, company_id, stop_type, sequence_order, latitude, longitude, status, created_at, updated_at)
                            VALUES ('${UUID.randomUUID()}', '$tripId', '$batchId', '$rideReqId', '$companyId', 'INVALID_TYPE', 3, 12.97, 77.59, 'PLANNED', NOW(), NOW())
                        """.trimIndent())
                    }
                }

                // 6. Trip Stops: duplicate sequence rejected
                assertFailsWith<java.sql.SQLException> {
                    conn.createStatement().use { stmt ->
                        stmt.execute("""
                            INSERT INTO trip_stops (id, trip_id, batch_id, ride_request_id, company_id, stop_type, sequence_order, latitude, longitude, status, created_at, updated_at)
                            VALUES ('${UUID.randomUUID()}', '$tripId', '$batchId', '$rideReqId', '$companyId', 'PICKUP', 1, 12.97, 77.59, 'PLANNED', NOW(), NOW())
                        """.trimIndent())
                    }
                }

                // 7. Vehicle Operational State: valid status accepted
                conn.createStatement().use { stmt ->
                    stmt.execute("""
                        INSERT INTO vehicle_operational_state (vehicle_id, company_id, status, active_trip_id, active_batch_id, updated_at)
                        VALUES ('$vehicleId', '$companyId', 'ASSIGNED', '$tripId', '$batchId', NOW())
                        ON CONFLICT (vehicle_id) DO UPDATE
                        SET status = 'ASSIGNED', active_trip_id = '$tripId', active_batch_id = '$batchId', updated_at = NOW()
                    """.trimIndent())
                }

                // 8. Vehicle Operational State: invalid status rejected
                assertFailsWith<java.sql.SQLException> {
                    conn.createStatement().use { stmt ->
                        stmt.execute("""
                            UPDATE vehicle_operational_state
                            SET status = 'FLYING', updated_at = NOW()
                            WHERE vehicle_id = '$vehicleId'
                        """.trimIndent())
                    }
                }

                // 9. Outbox Events: valid event accepted
                val outboxId = UUID.randomUUID()
                conn.createStatement().use { stmt ->
                    stmt.execute("""
                        INSERT INTO outbox_events (id, company_id, event_type, aggregate_type, aggregate_id, payload, published, created_at)
                        VALUES ('$outboxId', '$companyId', 'VEHICLE_ASSIGNED', 'TRIP', '$tripId', '{"tripId": "$tripId"}'::jsonb, false, NOW())
                    """.trimIndent())
                }

                // 10. Outbox Events: empty event_type rejected
                assertFailsWith<java.sql.SQLException> {
                    conn.createStatement().use { stmt ->
                        stmt.execute("""
                            INSERT INTO outbox_events (id, company_id, event_type, aggregate_type, aggregate_id, payload, published, created_at)
                            VALUES ('${UUID.randomUUID()}', '$companyId', '   ', 'TRIP', '$tripId', '{}'::jsonb, false, NOW())
                        """.trimIndent())
                    }
                }

                // Cleanup to prevent foreign key violations in subsequent tests
                conn.createStatement().use { stmt ->
                    stmt.execute("DELETE FROM trip_stops")
                    stmt.execute("DELETE FROM ride_requests")
                    stmt.execute("DELETE FROM vehicle_operational_state")
                    stmt.execute("DELETE FROM dispatch_batches")
                    stmt.execute("DELETE FROM outbox_events")
                }
            }
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test ZC - V8 migration executes cleanly and leaves schema at version 8`() {
        if (!isPostgresAvailable()) return

        recreateTestDatabase()
        val ds = createTestDataSource()
        try {
            val flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("8")
                .load()

            val result = flyway.migrate()
            assertTrue(result.success, "V8 Flyway migration should succeed")
            val info = flyway.info()
            val current = info.current()
            assertNotNull(current, "Current migration must not be null")
            assertEquals("8", current.version.version, "Target schema version should be 8")
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test V8 - V8 trip status check constraint accepts ASSIGNED, BOARDING, and ABORTED`() {
        if (!isPostgresAvailable()) return

        recreateTestDatabase()
        val ds = createTestDataSource()
        try {
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()

            val tripId = UUID.fromString("55555555-5555-5555-5555-555555555555")

            ds.connection.use { conn ->
                executeSqlScript(conn, File("scripts/seed_test_data.sql"))

                // Verify ASSIGNED, BOARDING, and ABORTED are accepted
                val newStatuses = listOf("ASSIGNED", "BOARDING", "IN_PROGRESS", "COMPLETED", "CANCELLED", "ABORTED")
                for (status in newStatuses) {
                    conn.createStatement().use { stmt ->
                        stmt.execute("UPDATE trips SET status = '$status' WHERE id = '$tripId'")
                    }
                }

                // Verify invalid status is rejected
                assertFailsWith<java.sql.SQLException> {
                    conn.createStatement().use { stmt ->
                        stmt.execute("UPDATE trips SET status = 'FLYING' WHERE id = '$tripId'")
                    }
                }

                // Reset back to SCHEDULED
                conn.createStatement().use { stmt ->
                    stmt.execute("UPDATE trips SET status = 'SCHEDULED' WHERE id = '$tripId'")
                }
            }
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test T - V3 normalises existing QUEUED and PICKED_UP rows`() {
        if (!isPostgresAvailable()) return

        recreateTestDatabase()
        val ds = createTestDataSource()
        try {
            // Step 1: Migrate only up to V2
            val flywayV2 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("2")
                .load()
            val resV2 = flywayV2.migrate()
            assertTrue(resV2.success, "Migration to V2 must succeed")

            // Step 2: Seed base data so foreign keys exist
            ds.connection.use { conn ->
                executeSqlScript(conn, File("scripts/seed_test_data.sql"))

                // Insert a QUEUED row and a PICKED_UP row under V2
                val empId = "22222222-2222-2222-2222-222222222222"
                val tripId = "55555555-5555-5555-5555-555555555555"
                conn.createStatement().use { stmt ->
                    stmt.execute("""
                        INSERT INTO ride_requests (id, trip_id, employee_id, pickup_lat, pickup_lng, destination_lat, destination_lng, status, requested_at)
                        VALUES 
                        ('${UUID.randomUUID()}', '$tripId', '$empId', 12.97, 77.59, 12.98, 77.60, 'QUEUED', NOW()),
                        ('${UUID.randomUUID()}', '$tripId', '$empId', 12.97, 77.59, 12.98, 77.60, 'PICKED_UP', NOW())
                    """.trimIndent())
                }
            }

            // Step 3: Run V3 migration
            val flywayV3 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("3")
                .load()
            val result = flywayV3.migrate()
            assertTrue(result.success, "Migration to V3 must succeed")

            // Step 4: Verify that QUEUED became WAITLISTED and PICKED_UP became PICKUP_PENDING
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("SELECT status, count(*) FROM ride_requests GROUP BY status")
                    val statusCounts = mutableMapOf<String, Int>()
                    while (rs.next()) {
                        statusCounts[rs.getString("status")] = rs.getInt("count")
                    }
                    assertFalse(statusCounts.containsKey("QUEUED"), "No rows should remain in QUEUED status")
                    assertFalse(statusCounts.containsKey("PICKED_UP"), "No rows should remain in PICKED_UP status")
                    assertEquals(1, statusCounts["WAITLISTED"], "1 row should have been migrated to WAITLISTED")
                    assertEquals(1, statusCounts["PICKUP_PENDING"], "1 row should have been migrated to PICKUP_PENDING")
                }
            }
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test U - check constraint rejects QUEUED and PICKED_UP`() {
        if (!isPostgresAvailable()) return

        val ds = createTestDataSource()
        try {
            ensureMigratedV3(ds)
            val empId = "22222222-2222-2222-2222-222222222222"
            val tripId = "55555555-5555-5555-5555-555555555555"

            ds.connection.use { conn ->
                executeSqlScript(conn, File("scripts/seed_test_data.sql"))

                assertFailsWith<java.sql.SQLException> {
                    conn.createStatement().use { stmt ->
                        stmt.execute("""
                            INSERT INTO ride_requests (id, trip_id, employee_id, pickup_lat, pickup_lng, destination_lat, destination_lng, status, requested_at)
                            VALUES ('${UUID.randomUUID()}', '$tripId', '$empId', 12.97, 77.59, 12.98, 77.60, 'QUEUED', NOW())
                        """.trimIndent())
                    }
                }

                assertFailsWith<java.sql.SQLException> {
                    conn.createStatement().use { stmt ->
                        stmt.execute("""
                            INSERT INTO ride_requests (id, trip_id, employee_id, pickup_lat, pickup_lng, destination_lat, destination_lng, status, requested_at)
                            VALUES ('${UUID.randomUUID()}', '$tripId', '$empId', 12.97, 77.59, 12.98, 77.60, 'PICKED_UP', NOW())
                        """.trimIndent())
                    }
                }
            }
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test V - check constraint accepts WAITLISTED, PICKUP_PENDING, ONBOARD, CANCELLED`() {
        if (!isPostgresAvailable()) return

        val ds = createTestDataSource()
        try {
            ensureMigratedV3(ds)
            val empId = "22222222-2222-2222-2222-222222222222"
            val tripId = "55555555-5555-5555-5555-555555555555"

            ds.connection.use { conn ->
                executeSqlScript(conn, File("scripts/seed_test_data.sql"))
                val newStatuses = listOf("WAITLISTED", "PICKUP_PENDING", "ONBOARD", "CANCELLED")
                for (status in newStatuses) {
                    conn.createStatement().use { stmt ->
                        val id = UUID.randomUUID()
                        stmt.execute("""
                            INSERT INTO ride_requests (id, trip_id, employee_id, pickup_lat, pickup_lng, destination_lat, destination_lng, status, requested_at)
                            VALUES ('$id', '$tripId', '$empId', 12.97, 77.59, 12.98, 77.60, '$status', NOW())
                        """.trimIndent())
                        stmt.execute("DELETE FROM ride_requests WHERE id = '$id'")
                    }
                }
            }
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test W - all 8 canonical states are accepted by check constraint`() {
        if (!isPostgresAvailable()) return

        val ds = createTestDataSource()
        try {
            ensureMigratedV3(ds)
            val empId = "22222222-2222-2222-2222-222222222222"
            val tripId = "55555555-5555-5555-5555-555555555555"
            val canonicalStates = listOf(
                "REQUESTED",
                "MATCHED",
                "WAITLISTED",
                "PICKUP_PENDING",
                "ONBOARD",
                "COMPLETED",
                "CANCELLED",
                "NO_SHOW"
            )

            ds.connection.use { conn ->
                executeSqlScript(conn, File("scripts/seed_test_data.sql"))
                for (status in canonicalStates) {
                    val id = UUID.randomUUID()
                    conn.createStatement().use { stmt ->
                        stmt.execute("""
                            INSERT INTO ride_requests (id, trip_id, employee_id, pickup_lat, pickup_lng, destination_lat, destination_lng, status, requested_at)
                            VALUES ('$id', '$tripId', '$empId', 12.97, 77.59, 12.98, 77.60, '$status', NOW())
                        """.trimIndent())
                        stmt.execute("DELETE FROM ride_requests WHERE id = '$id'")
                    }
                }
            }
        } finally {
            ds.close()
        }
    }

    @Test
    fun `Test ZB - dynashuttle development database migrates cleanly to V7`() {
        if (!isPostgresAvailable()) return

        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:postgresql://$host:$port/dynashuttle"
            username = user
            password = this@DatabaseMigrationTest.password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 2
            isAutoCommit = true
        }
        val ds = HikariDataSource(config)
        try {
            val flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load()

            val result = flyway.migrate()
            assertTrue(result.success, "Migration on existing dynashuttle database must succeed")
        } finally {
            ds.close()
        }
    }


    // Helper functions to query information_schema / pg_catalog
    private fun getTables(conn: Connection): Set<String> {
        val tables = mutableSetOf<String>()
        conn.prepareStatement(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
        ).use { ps ->
            val rs = ps.executeQuery()
            while (rs.next()) {
                tables.add(rs.getString("table_name"))
            }
        }
        return tables
    }

    private fun getExtensions(conn: Connection): Set<String> {
        val extensions = mutableSetOf<String>()
        conn.prepareStatement("SELECT extname FROM pg_extension").use { ps ->
            val rs = ps.executeQuery()
            while (rs.next()) {
                extensions.add(rs.getString("extname"))
            }
        }
        return extensions
    }

    private fun getIndexes(conn: Connection): Set<String> {
        val indexes = mutableSetOf<String>()
        conn.prepareStatement(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'"
        ).use { ps ->
            val rs = ps.executeQuery()
            while (rs.next()) {
                indexes.add(rs.getString("indexname"))
            }
        }
        return indexes
    }

    private fun getCheckConstraints(conn: Connection): Set<String> {
        val constraints = mutableSetOf<String>()
        conn.prepareStatement(
            "SELECT conname FROM pg_constraint WHERE contype = 'c'"
        ).use { ps ->
            val rs = ps.executeQuery()
            while (rs.next()) {
                constraints.add(rs.getString("conname"))
            }
        }
        return constraints
    }
}
