package com.dynashuttle

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dynashuttle.models.CreateShuttleScheduleRequest
import com.dynashuttle.models.ScheduleDay
import com.dynashuttle.models.ScheduleDirection
import com.dynashuttle.models.ShuttleSchedule
import com.dynashuttle.models.ShuttleScheduleResponse
import com.dynashuttle.models.UpdateShuttleScheduleRequest
import com.dynashuttle.repositories.ShuttleScheduleRepository
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
import java.time.Instant
import java.time.LocalTime
import kotlin.test.*

class ShuttleScheduleTest {

    private val jwtSecret = "schedule-test-secret"
    private val jwtIssuer = "dynashuttle"

    private fun generateToken(userId: String, role: String, companyId: String): String =
        JWT.create()
            .withIssuer(jwtIssuer)
            .withClaim("userId", userId)
            .withClaim("role", role)
            .withClaim("companyId", companyId)
            .sign(Algorithm.HMAC256(jwtSecret))

    // =========================================================================
    // In-memory test repository — mirrors TestPickupZoneRepository pattern
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
    }

    // =========================================================================
    // Test application builder — mirrors PickupZoneTest's setup pattern
    // =========================================================================

    private fun ApplicationTestBuilder.setupTestApp(repo: TestShuttleScheduleRepository) {
        val service = ShuttleScheduleService(repo)

        application {
            install(ServerContentNegotiation) { json() }
            install(Authentication) {
                jwt("app-user") {
                    verifier(
                        JWT.require(Algorithm.HMAC256(jwtSecret))
                            .withIssuer(jwtIssuer)
                            .withClaim("role") { value, _ -> value.asString() in listOf("employee", "driver") }
                            .build()
                    )
                    validate { credential -> JWTPrincipal(credential.payload) }
                }
                jwt("admin") {
                    verifier(
                        JWT.require(Algorithm.HMAC256(jwtSecret))
                            .withIssuer(jwtIssuer)
                            .withClaim("role", "admin")
                            .build()
                    )
                    validate { credential -> JWTPrincipal(credential.payload) }
                }
            }
            routing {
                shuttleScheduleRoutes(service)
            }
        }
    }

    // Convenience: create a schedule directly in the repo
    private fun TestShuttleScheduleRepository.seed(
        companyId: String,
        name: String = "Morning Run",
        direction: ScheduleDirection = ScheduleDirection.INBOUND,
        departureTime: LocalTime = LocalTime.of(7, 30),
        daysOfWeek: List<ScheduleDay> = listOf(ScheduleDay.MONDAY, ScheduleDay.FRIDAY)
    ) = create(companyId, name, direction, departureTime, daysOfWeek)

    private val validCreateBody = CreateShuttleScheduleRequest(
        name = "Morning Whitefield to Campus",
        direction = "INBOUND",
        departureTime = "07:30",
        daysOfWeek = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY")
    )

    // =========================================================================
    // CREATION TESTS
    // =========================================================================

    @Test
    fun `Admin can create a valid shuttle schedule`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validCreateBody))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<ShuttleScheduleResponse>(response.bodyAsText())
        assertEquals("Morning Whitefield to Campus", body.name)
        assertEquals("INBOUND", body.direction)
        assertEquals("07:30", body.departureTime)
        assertEquals(listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"), body.daysOfWeek)
        assertTrue(body.active)
    }

    @Test
    fun `Created schedule belongs to the JWT company, not a client-supplied value`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validCreateBody))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<ShuttleScheduleResponse>(response.bodyAsText())
        // companyId must come from JWT, not from any client field
        assertEquals("comp-A", body.companyId)
        val stored = repo.schedules.values.first()
        assertEquals("comp-A", stored.companyId)
    }

    // =========================================================================
    // RETRIEVAL TESTS
    // =========================================================================

    @Test
    fun `Admin can retrieve own-company schedule by ID`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val schedule = repo.seed("comp-A")
        val token = generateToken("adm-1", "admin", "comp-A")

        val response = client.get("/shuttle-schedules/${schedule.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<ShuttleScheduleResponse>(response.bodyAsText())
        assertEquals(schedule.id, body.id)
        assertEquals("Morning Run", body.name)
    }

    @Test
    fun `Employee can retrieve own-company schedule`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val schedule = repo.seed("comp-A")
        val token = generateToken("emp-1", "employee", "comp-A")

        val response = client.get("/shuttle-schedules/${schedule.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `Cross-company schedule returns 404`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val scheduleB = repo.seed("comp-B")
        val tokenA = generateToken("adm-A", "admin", "comp-A")

        val response = client.get("/shuttle-schedules/${scheduleB.id}") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `Unknown schedule ID returns 404`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.get("/shuttle-schedules/nonexistent-id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // =========================================================================
    // LISTING TESTS
    // =========================================================================

    @Test
    fun `Company A sees only Company A schedules`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        repo.seed("comp-A", "A Morning")
        repo.seed("comp-A", "A Evening")
        repo.seed("comp-B", "B Morning")

        val tokenA = generateToken("adm-A", "admin", "comp-A")
        val response = client.get("/shuttle-schedules") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<List<ShuttleScheduleResponse>>(response.bodyAsText())
        assertEquals(2, body.size)
        assertTrue(body.all { it.companyId == "comp-A" })
    }

    @Test
    fun `Company B sees only Company B schedules`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        repo.seed("comp-A", "A Morning")
        repo.seed("comp-B", "B Morning")
        repo.seed("comp-B", "B Evening")

        val tokenB = generateToken("adm-B", "admin", "comp-B")
        val response = client.get("/shuttle-schedules") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<List<ShuttleScheduleResponse>>(response.bodyAsText())
        assertEquals(2, body.size)
        assertTrue(body.all { it.companyId == "comp-B" })
    }

    @Test
    fun `Inactive schedules are excluded from activeOnly listing`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val active = repo.seed("comp-A", "Active Schedule")
        repo.deactivate("sched-1", "comp-A") // deactivate the first schedule
        repo.seed("comp-A", "Another Active")

        val token = generateToken("adm-A", "admin", "comp-A")
        val response = client.get("/shuttle-schedules?activeOnly=true") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<List<ShuttleScheduleResponse>>(response.bodyAsText())
        // Only the second seed (still active) should appear
        assertTrue(body.all { it.active })
        assertFalse(body.any { it.id == active.id })
    }

    // =========================================================================
    // UPDATE TESTS
    // =========================================================================

    @Test
    fun `Admin can update own-company schedule`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val schedule = repo.seed("comp-A")
        val token = generateToken("adm-1", "admin", "comp-A")

        val response = client.put("/shuttle-schedules/${schedule.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(UpdateShuttleScheduleRequest(name = "Updated Name", departureTime = "08:00")))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<ShuttleScheduleResponse>(response.bodyAsText())
        assertEquals("Updated Name", body.name)
        assertEquals("08:00", body.departureTime)
    }

    @Test
    fun `Cross-company update is rejected with 404`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val scheduleB = repo.seed("comp-B")
        val tokenA = generateToken("adm-A", "admin", "comp-A")

        val response = client.put("/shuttle-schedules/${scheduleB.id}") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(UpdateShuttleScheduleRequest(name = "Hacked")))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        // Verify the schedule was untouched
        assertEquals("Morning Run", repo.schedules[scheduleB.id]!!.name)
    }

    @Test
    fun `Employee cannot update a shuttle schedule`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val schedule = repo.seed("comp-A")
        val empToken = generateToken("emp-1", "employee", "comp-A")

        val response = client.put("/shuttle-schedules/${schedule.id}") {
            header(HttpHeaders.Authorization, "Bearer $empToken")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(UpdateShuttleScheduleRequest(name = "Employee Update")))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // =========================================================================
    // DEACTIVATION TESTS
    // =========================================================================

    @Test
    fun `Admin can deactivate a schedule`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val schedule = repo.seed("comp-A")
        val token = generateToken("adm-1", "admin", "comp-A")

        val response = client.delete("/shuttle-schedules/${schedule.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `Deactivation is soft — record still exists with active=false`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val schedule = repo.seed("comp-A")
        val token = generateToken("adm-1", "admin", "comp-A")

        client.delete("/shuttle-schedules/${schedule.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        val stored = repo.schedules[schedule.id]
        assertNotNull(stored)
        assertFalse(stored.active)
    }

    @Test
    fun `Deactivated schedule is excluded from activeOnly listing`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val schedule = repo.seed("comp-A")
        val token = generateToken("adm-1", "admin", "comp-A")

        client.delete("/shuttle-schedules/${schedule.id}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        val listRes = client.get("/shuttle-schedules?activeOnly=true") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        val body = Json.decodeFromString<List<ShuttleScheduleResponse>>(listRes.bodyAsText())
        assertFalse(body.any { it.id == schedule.id })
    }

    @Test
    fun `Employee cannot deactivate a schedule`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val schedule = repo.seed("comp-A")
        val empToken = generateToken("emp-1", "employee", "comp-A")

        val response = client.delete("/shuttle-schedules/${schedule.id}") {
            header(HttpHeaders.Authorization, "Bearer $empToken")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        // Verify still active
        assertTrue(repo.schedules[schedule.id]!!.active)
    }

    // =========================================================================
    // VALIDATION TESTS
    // =========================================================================

    @Test
    fun `Blank name is rejected`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validCreateBody.copy(name = "   ")))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `Invalid direction is rejected`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validCreateBody.copy(direction = "SIDEWAYS")))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `Invalid departure time format is rejected`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validCreateBody.copy(departureTime = "7:30 AM")))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `Empty days list is rejected`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validCreateBody.copy(daysOfWeek = emptyList())))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `Duplicate days in list are rejected`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validCreateBody.copy(daysOfWeek = listOf("MONDAY", "MONDAY"))))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `Invalid day name is rejected`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val token = generateToken("adm-1", "admin", "comp-A")
        val response = client.post("/shuttle-schedules") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validCreateBody.copy(daysOfWeek = listOf("FUNDAY"))))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // =========================================================================
    // AUTHENTICATION TESTS
    // =========================================================================

    @Test
    fun `Unauthenticated request returns 401`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val response = client.get("/shuttle-schedules")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `Employee cannot create a schedule`() = testApplication {
        val repo = TestShuttleScheduleRepository()
        setupTestApp(repo)

        val empToken = generateToken("emp-1", "employee", "comp-A")
        val response = client.post("/shuttle-schedules") {
            header(HttpHeaders.Authorization, "Bearer $empToken")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(validCreateBody))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
