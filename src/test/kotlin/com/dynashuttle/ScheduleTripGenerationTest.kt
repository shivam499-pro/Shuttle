package com.dynashuttle

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dynashuttle.models.*
import com.dynashuttle.repositories.ShuttleScheduleRepository
import com.dynashuttle.repositories.TripRepository
import com.dynashuttle.repositories.TransitionResult
import com.dynashuttle.routes.shuttleScheduleRoutes
import com.dynashuttle.services.ShuttleScheduleService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.dynashuttle.config.Env
import com.dynashuttle.repositories.PostgresShuttleScheduleRepository
import com.dynashuttle.repositories.PostgresTripRepository
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.DriverManager
import java.time.*
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.*

class ScheduleTripGenerationTest {

    private val jwtSecret = "schedule-trip-test-secret"
    private val jwtIssuer = "dynashuttle"
    private val testZone = ZoneId.of("UTC")

    private fun generateToken(userId: String, role: String, companyId: String): String =
        JWT.create()
            .withIssuer(jwtIssuer)
            .withClaim("userId", userId)
            .withClaim("role", role)
            .withClaim("companyId", companyId)
            .sign(Algorithm.HMAC256(jwtSecret))

    // =========================================================================
    // In-memory test repositories
    // =========================================================================

    private class TestShuttleScheduleRepository : ShuttleScheduleRepository {
        val schedules = mutableMapOf<String, ShuttleSchedule>()
        private var idCounter = 1

        override fun create(
            companyId: String,
            name: String,
            direction: ScheduleDirection,
            departureTime: LocalTime,
            daysOfWeek: List<ScheduleDay>
        ): ShuttleSchedule {
            val id = "sched-${idCounter++}"
            val now = Instant.now()
            val schedule = ShuttleSchedule(
                id = id,
                companyId = companyId,
                name = name,
                direction = direction,
                departureTime = departureTime,
                daysOfWeek = daysOfWeek,
                active = true,
                createdAt = now,
                updatedAt = now
            )
            schedules[id] = schedule
            return schedule
        }

        override fun findById(id: String): ShuttleSchedule? = schedules[id]

        override fun findByCompany(companyId: String): List<ShuttleSchedule> =
            schedules.values.filter { it.companyId == companyId }

        override fun findActiveByCompany(companyId: String): List<ShuttleSchedule> =
            schedules.values.filter { it.companyId == companyId && it.active }

        override fun update(
            id: String,
            companyId: String,
            name: String?,
            direction: ScheduleDirection?,
            departureTime: LocalTime?,
            daysOfWeek: List<ScheduleDay>?,
            active: Boolean?
        ): ShuttleSchedule? {
            val existing = schedules[id] ?: return null
            if (existing.companyId != companyId) return null
            val updated = existing.copy(
                name = name ?: existing.name,
                direction = direction ?: existing.direction,
                departureTime = departureTime ?: existing.departureTime,
                daysOfWeek = daysOfWeek ?: existing.daysOfWeek,
                active = active ?: existing.active,
                updatedAt = Instant.now()
            )
            schedules[id] = updated
            return updated
        }

        override fun deactivate(id: String, companyId: String): Boolean {
            val existing = schedules[id] ?: return false
            if (existing.companyId != companyId) return false
            schedules[id] = existing.copy(active = false, updatedAt = Instant.now())
            return true
        }

        fun seed(
            companyId: String,
            name: String,
            departureTime: LocalTime = LocalTime.of(8, 0),
            days: List<ScheduleDay> = listOf(ScheduleDay.MONDAY, ScheduleDay.WEDNESDAY, ScheduleDay.FRIDAY),
            active: Boolean = true
        ): ShuttleSchedule {
            val id = "sched-${idCounter++}"
            val now = Instant.now()
            val schedule = ShuttleSchedule(
                id = id,
                companyId = companyId,
                name = name,
                direction = ScheduleDirection.INBOUND,
                departureTime = departureTime,
                daysOfWeek = days,
                active = active,
                createdAt = now,
                updatedAt = now
            )
            schedules[id] = schedule
            return schedule
        }
    }

    private class TestTripRepository : TripRepository {
        val trips = mutableMapOf<String, Trip>()
        val scheduleDateIndex = mutableMapOf<Pair<String, LocalDate>, Trip>()

        override fun findById(tripId: String): Trip? = trips[tripId]

        override fun findByScheduleAndDate(scheduleId: String, tripDate: LocalDate): Trip? =
            scheduleDateIndex[scheduleId to tripDate]

        override fun create(trip: Trip): Trip {
            val key = trip.scheduleId to trip.tripDate
            val existing = scheduleDateIndex[key]
            if (existing != null) {
                return existing
            }
            trips[trip.id] = trip
            scheduleDateIndex[key] = trip
            return trip
        }

        override fun updateStatus(tripId: String, expectedStatus: com.dynashuttle.models.TripStatus, newStatus: com.dynashuttle.models.TripStatus): TransitionResult {
            val trip = trips[tripId] ?: return TransitionResult.NotFound
            if (trip.tripStatus != expectedStatus) return TransitionResult.StateMismatch
            val updated = trip.copy(status = newStatus.name)
            trips[tripId] = updated
            scheduleDateIndex[trip.scheduleId to trip.tripDate] = updated
            return TransitionResult.Success
        }

        override fun assignVehicleAndDriver(tripId: String, vehicleId: String, driverId: String): TransitionResult {
            val trip = trips[tripId] ?: return TransitionResult.NotFound
            if (trip.tripStatus != com.dynashuttle.models.TripStatus.SCHEDULED) return TransitionResult.StateMismatch
            val updated = trip.copy(vehicleId = vehicleId, driverId = driverId, status = com.dynashuttle.models.TripStatus.ASSIGNED.name)
            trips[tripId] = updated
            scheduleDateIndex[trip.scheduleId to trip.tripDate] = updated
            return TransitionResult.Success
        }
    }

    // =========================================================================
    // Test application setup
    // =========================================================================

    private fun ApplicationTestBuilder.setupTestApp(
        scheduleRepo: ShuttleScheduleRepository,
        tripRepo: TripRepository
    ) {
        val scheduleService = ShuttleScheduleService(scheduleRepo, tripRepo, testZone)
        install(ServerContentNegotiation) { json() }
        install(Authentication) {
            jwt("admin") {
                verifier(
                    JWT.require(Algorithm.HMAC256(jwtSecret))
                        .withIssuer(jwtIssuer)
                        .build()
                )
                validate { credential ->
                    val role = credential.payload.getClaim("role").asString()
                    if (role == "admin") JWTPrincipal(credential.payload) else null
                }
            }
            jwt("app-user") {
                verifier(
                    JWT.require(Algorithm.HMAC256(jwtSecret))
                        .withIssuer(jwtIssuer)
                        .build()
                )
                validate { credential ->
                    val role = credential.payload.getClaim("role").asString()
                    if (role in listOf("employee", "driver")) JWTPrincipal(credential.payload) else null
                }
            }
        }
        routing {
            shuttleScheduleRoutes(scheduleService)
        }
    }

    private fun nextDateForDay(dayOfWeek: DayOfWeek, fromDate: LocalDate = LocalDate.now(testZone)): LocalDate {
        var d = fromDate
        while (d.dayOfWeek != dayOfWeek) {
            d = d.plusDays(1)
        }
        return d
    }

    // =========================================================================
    // 1 & 2: Happy path
    // =========================================================================

    @Test
    fun `1 - Admin can generate a trip from an active schedule on a valid scheduled day`() = testApplication {
        val scheduleRepo = TestShuttleScheduleRepository()
        val tripRepo = TestTripRepository()
        setupTestApp(scheduleRepo, tripRepo)

        val sched = scheduleRepo.seed("comp-A", "Morning Campus Run", LocalTime.of(8, 30), listOf(ScheduleDay.MONDAY, ScheduleDay.FRIDAY))
        val targetDate = nextDateForDay(DayOfWeek.MONDAY)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules/${sched.id}/trips") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(targetDate.toString())))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<GeneratedTripResponse>(response.bodyAsText())
        assertNotNull(body.tripId)
        assertEquals(sched.id, body.scheduleId)
        assertEquals(targetDate.toString(), body.tripDate)
    }

    @Test
    fun `2 - Generated trip contains correct properties, null vehicle and driver, SCHEDULED status`() = testApplication {
        val scheduleRepo = TestShuttleScheduleRepository()
        val tripRepo = TestTripRepository()
        setupTestApp(scheduleRepo, tripRepo)

        val sched = scheduleRepo.seed("comp-A", "Campus Shuttle", LocalTime.of(9, 15), listOf(ScheduleDay.WEDNESDAY))
        val targetDate = nextDateForDay(DayOfWeek.WEDNESDAY)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules/${sched.id}/trips") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(targetDate.toString())))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<GeneratedTripResponse>(response.bodyAsText())
        assertEquals(sched.id, body.scheduleId)
        assertEquals(targetDate.toString(), body.tripDate)
        assertNull(body.vehicleId)
        assertNull(body.driverId)
        assertEquals("SCHEDULED", body.status)

        // Verify startTime matches targetDate + departureTime in testZone
        val expectedInstant = LocalDateTime.of(targetDate, LocalTime.of(9, 15)).atZone(testZone).toInstant()
        assertEquals(expectedInstant.toString(), body.startTime)
    }

    // =========================================================================
    // 3, 4, 5: Day validation
    // =========================================================================

    @Test
    fun `3 - Generation succeeds when date matches schedule day`() = testApplication {
        val scheduleRepo = TestShuttleScheduleRepository()
        val tripRepo = TestTripRepository()
        setupTestApp(scheduleRepo, tripRepo)

        val sched = scheduleRepo.seed("comp-A", "Friday Express", days = listOf(ScheduleDay.FRIDAY))
        val targetDate = nextDateForDay(DayOfWeek.FRIDAY)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules/${sched.id}/trips") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(targetDate.toString())))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `4 - Generation fails when date does not match schedule day`() = testApplication {
        val scheduleRepo = TestShuttleScheduleRepository()
        val tripRepo = TestTripRepository()
        setupTestApp(scheduleRepo, tripRepo)

        // Schedule only runs on MONDAY
        val sched = scheduleRepo.seed("comp-A", "Monday Only", days = listOf(ScheduleDay.MONDAY))
        // Target a SUNDAY
        val sundayDate = nextDateForDay(DayOfWeek.SUNDAY)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules/${sched.id}/trips") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(sundayDate.toString())))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("validation_error", body.error)
        assertTrue(body.message.contains("Schedule does not operate on SUNDAY"))
    }

    @Test
    fun `5 - No trip is created after invalid day request`() = testApplication {
        val scheduleRepo = TestShuttleScheduleRepository()
        val tripRepo = TestTripRepository()
        setupTestApp(scheduleRepo, tripRepo)

        val sched = scheduleRepo.seed("comp-A", "Tuesday Only", days = listOf(ScheduleDay.TUESDAY))
        val saturdayDate = nextDateForDay(DayOfWeek.SATURDAY)

        val token = generateToken("adm-1", "admin", "comp-A")
        client.post("/shuttle-schedules/${sched.id}/trips") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(saturdayDate.toString())))
        }

        assertTrue(tripRepo.trips.isEmpty(), "No trip should be stored in repository")
    }

    // =========================================================================
    // 6 & 7: Schedule state
    // =========================================================================

    @Test
    fun `6 - Inactive schedule cannot generate a trip`() = testApplication {
        val scheduleRepo = TestShuttleScheduleRepository()
        val tripRepo = TestTripRepository()
        setupTestApp(scheduleRepo, tripRepo)

        val sched = scheduleRepo.seed("comp-A", "Retired Schedule", active = false, days = listOf(ScheduleDay.MONDAY))
        val targetDate = nextDateForDay(DayOfWeek.MONDAY)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules/${sched.id}/trips") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(targetDate.toString())))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("invalid_schedule", body.error)
        assertTrue(tripRepo.trips.isEmpty())
    }

    @Test
    fun `7 - Nonexistent schedule returns 404 not found`() = testApplication {
        val scheduleRepo = TestShuttleScheduleRepository()
        val tripRepo = TestTripRepository()
        setupTestApp(scheduleRepo, tripRepo)

        val targetDate = nextDateForDay(DayOfWeek.MONDAY)
        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules/non-existent-id/trips") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(targetDate.toString())))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("not_found", body.error)
    }

    // =========================================================================
    // 8 & 9: Date validation
    // =========================================================================

    @Test
    fun `8 - Past date is rejected`() = testApplication {
        val scheduleRepo = TestShuttleScheduleRepository()
        val tripRepo = TestTripRepository()
        setupTestApp(scheduleRepo, tripRepo)

        val sched = scheduleRepo.seed("comp-A", "Daily Run", days = ScheduleDay.entries)
        val yesterday = LocalDate.now(testZone).minusDays(1)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules/${sched.id}/trips") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(yesterday.toString())))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("validation_error", body.error)
        assertTrue(body.message.contains("past date"))
        assertTrue(tripRepo.trips.isEmpty())
    }

    @Test
    fun `9 - Valid future date works`() = testApplication {
        val scheduleRepo = TestShuttleScheduleRepository()
        val tripRepo = TestTripRepository()
        setupTestApp(scheduleRepo, tripRepo)

        val sched = scheduleRepo.seed("comp-A", "Future Shuttle", days = listOf(ScheduleDay.THURSDAY))
        val nextThursday = nextDateForDay(DayOfWeek.THURSDAY).plusWeeks(2)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules/${sched.id}/trips") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(nextThursday.toString())))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<GeneratedTripResponse>(response.bodyAsText())
        assertEquals(nextThursday.toString(), body.tripDate)
    }

    // =========================================================================
    // 10 & 11: Idempotency
    // =========================================================================

    @Test
    fun `10 & 11 - Calling generation twice for the same schedule and date creates only one trip and returns same trip consistently`() = testApplication {
        val scheduleRepo = TestShuttleScheduleRepository()
        val tripRepo = TestTripRepository()
        setupTestApp(scheduleRepo, tripRepo)

        val sched = scheduleRepo.seed("comp-A", "Idempotent Run", days = listOf(ScheduleDay.MONDAY))
        val targetDate = nextDateForDay(DayOfWeek.MONDAY)

        val token = generateToken("adm-1", "admin", "comp-A")

        // First call
        val res1 = client.post("/shuttle-schedules/${sched.id}/trips") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(targetDate.toString())))
        }
        assertEquals(HttpStatusCode.Created, res1.status)
        val trip1 = Json.decodeFromString<GeneratedTripResponse>(res1.bodyAsText())

        // Second call
        val res2 = client.post("/shuttle-schedules/${sched.id}/trips") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(targetDate.toString())))
        }
        assertEquals(HttpStatusCode.OK, res2.status)
        val trip2 = Json.decodeFromString<GeneratedTripResponse>(res2.bodyAsText())

        // Third call
        val res3 = client.post("/shuttle-schedules/${sched.id}/trips") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(targetDate.toString())))
        }
        assertEquals(HttpStatusCode.OK, res3.status)
        val trip3 = Json.decodeFromString<GeneratedTripResponse>(res3.bodyAsText())

        // Assert exactly one trip in repository
        assertEquals(1, tripRepo.trips.size)
        assertEquals(trip1.tripId, trip2.tripId)
        assertEquals(trip1.tripId, trip3.tripId)
    }

    // =========================================================================
    // 12 & 13: Tenant isolation
    // =========================================================================

    @Test
    fun `12 & 13 - Company A cannot generate a trip from Company B schedule and returns 404 without leaking existence`() = testApplication {
        val scheduleRepo = TestShuttleScheduleRepository()
        val tripRepo = TestTripRepository()
        setupTestApp(scheduleRepo, tripRepo)

        // Belongs to Company B
        val schedB = scheduleRepo.seed("comp-B", "Company B Shuttle", days = listOf(ScheduleDay.MONDAY))
        val targetDate = nextDateForDay(DayOfWeek.MONDAY)

        // Company A admin tries to generate from Company B's schedule
        val tokenA = generateToken("adm-A", "admin", "comp-A")
        val response = client.post("/shuttle-schedules/${schedB.id}/trips") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(targetDate.toString())))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("not_found", body.error)
        assertTrue(tripRepo.trips.isEmpty())
    }

    // =========================================================================
    // 14, 15, 16: Authorization
    // =========================================================================

    @Test
    fun `14 - Employee cannot generate trips`() = testApplication {
        val scheduleRepo = TestShuttleScheduleRepository()
        val tripRepo = TestTripRepository()
        setupTestApp(scheduleRepo, tripRepo)

        val sched = scheduleRepo.seed("comp-A", "Campus Shuttle", days = listOf(ScheduleDay.MONDAY))
        val targetDate = nextDateForDay(DayOfWeek.MONDAY)

        val empToken = generateToken("emp-1", "employee", "comp-A")
        val response = client.post("/shuttle-schedules/${sched.id}/trips") {
            header(HttpHeaders.Authorization, "Bearer $empToken")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(targetDate.toString())))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("forbidden", body.error)
        assertTrue(tripRepo.trips.isEmpty())
    }

    @Test
    fun `15 - Driver cannot generate trips`() = testApplication {
        val scheduleRepo = TestShuttleScheduleRepository()
        val tripRepo = TestTripRepository()
        setupTestApp(scheduleRepo, tripRepo)

        val sched = scheduleRepo.seed("comp-A", "Campus Shuttle", days = listOf(ScheduleDay.MONDAY))
        val targetDate = nextDateForDay(DayOfWeek.MONDAY)

        val drvToken = generateToken("drv-1", "driver", "comp-A")
        val response = client.post("/shuttle-schedules/${sched.id}/trips") {
            header(HttpHeaders.Authorization, "Bearer $drvToken")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(targetDate.toString())))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("forbidden", body.error)
        assertTrue(tripRepo.trips.isEmpty())
    }

    @Test
    fun `16 - Unauthenticated request is rejected`() = testApplication {
        val scheduleRepo = TestShuttleScheduleRepository()
        val tripRepo = TestTripRepository()
        setupTestApp(scheduleRepo, tripRepo)

        val sched = scheduleRepo.seed("comp-A", "Campus Shuttle", days = listOf(ScheduleDay.MONDAY))
        val targetDate = nextDateForDay(DayOfWeek.MONDAY)

        val response = client.post("/shuttle-schedules/${sched.id}/trips") {
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(GenerateTripRequest(targetDate.toString())))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(tripRepo.trips.isEmpty())
    }

    // =========================================================================
    // Concurrency Integration Test against real PostgreSQL database
    // =========================================================================

    private val host = Env.get("DATABASE_HOST", "localhost")
    private val port = 5432
    private val dbUser = Env.get("DATABASE_USER", "postgres")
    private val dbPassword = Env.get("DATABASE_PASSWORD", "postgres")
    private val testDbName = "dynashuttle_test"
    private val adminJdbcUrl = "jdbc:postgresql://$host:$port/postgres"
    private val testDbJdbcUrl = "jdbc:postgresql://$host:$port/$testDbName"

    private fun isPostgresAvailable(): Boolean {
        return try {
            DriverManager.getConnection(adminJdbcUrl, dbUser, dbPassword).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private fun recreateTestDatabase() {
        DriverManager.getConnection(adminJdbcUrl, dbUser, dbPassword).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP DATABASE IF EXISTS $testDbName")
                stmt.execute("CREATE DATABASE $testDbName")
            }
        }
    }

    private fun createTestDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = testDbJdbcUrl
            username = dbUser
            password = dbPassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 5
            isAutoCommit = true
        }
        return HikariDataSource(config)
    }

    @Test
    fun `17 - Concurrent trip generation for same schedule and date creates exactly one trip and converges without exception`() {
        if (!isPostgresAvailable()) return

        recreateTestDatabase()
        val ds = createTestDataSource()
        try {
            // Ensure schema is fully migrated through V6
            Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            Database.connect(ds)

            val scheduleRepo = PostgresShuttleScheduleRepository()
            val tripRepo = PostgresTripRepository()
            val service = ShuttleScheduleService(scheduleRepo, tripRepo, testZone)

            val compId = UUID.randomUUID().toString()
            transaction {
                Companies.insert {
                    it[id] = UUID.fromString(compId)
                    it[name] = "Concurrent Trip Corp"
                    it[city] = "Bangalore"
                    it[employeeCount] = 10
                    it[createdAt] = Instant.now()
                }
            }

            val schedule = scheduleRepo.create(
                companyId = compId,
                name = "Concurrent Express",
                direction = ScheduleDirection.INBOUND,
                departureTime = LocalTime.of(8, 30),
                daysOfWeek = listOf(ScheduleDay.MONDAY, ScheduleDay.TUESDAY, ScheduleDay.WEDNESDAY, ScheduleDay.THURSDAY, ScheduleDay.FRIDAY, ScheduleDay.SATURDAY, ScheduleDay.SUNDAY)
            )

            val targetDate = LocalDate.now(testZone).plusDays(5)
            val request = GenerateTripRequest(targetDate.toString())

            val pool = Executors.newFixedThreadPool(2)
            val barrier = CyclicBarrier(2)

            val task1 = pool.submit<ShuttleScheduleService.GenerateTripResult> {
                barrier.await()
                service.generateTrip(schedule.id, compId, request)
            }
            val task2 = pool.submit<ShuttleScheduleService.GenerateTripResult> {
                barrier.await()
                service.generateTrip(schedule.id, compId, request)
            }

            val result1 = task1.get()
            val result2 = task2.get()
            pool.shutdown()

            assertTrue(result1 is ShuttleScheduleService.GenerateTripResult.Success, "Caller 1 should succeed")
            assertTrue(result2 is ShuttleScheduleService.GenerateTripResult.Success, "Caller 2 should succeed")

            val trip1 = result1.trip
            val trip2 = result2.trip

            // Both callers must receive the EXACT same trip ID
            assertEquals(trip1.id, trip2.id, "Both concurrent callers must converge on the exact same trip")
            assertEquals(schedule.id, trip1.scheduleId)
            assertEquals(targetDate, trip1.tripDate)
            assertNull(trip1.vehicleId)
            assertNull(trip1.driverId)
            assertEquals("SCHEDULED", trip1.status)

            // Verify in PostgreSQL that exactly ONE row exists
            val persisted = tripRepo.findByScheduleAndDate(schedule.id, targetDate)
            assertNotNull(persisted, "Trip must exist in database")
            assertEquals(trip1.id, persisted.id)
        } finally {
            ds.close()
        }
    }
}
