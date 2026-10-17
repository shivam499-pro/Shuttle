package com.dynashuttle

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dynashuttle.models.*
import com.dynashuttle.repositories.PickupZoneRepository
import com.dynashuttle.routes.pickupZoneRoutes
import com.dynashuttle.services.PickupZoneService
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
import kotlin.test.*

class PickupZoneTest {

    private val jwtSecret = "pickup-zone-test-secret"
    private val jwtIssuer = "dynashuttle"

    private fun generateToken(userId: String, role: String, companyId: String): String =
        JWT.create()
            .withIssuer(jwtIssuer)
            .withClaim("userId", userId)
            .withClaim("role", role)
            .withClaim("companyId", companyId)
            .sign(Algorithm.HMAC256(jwtSecret))

    private class TestPickupZoneRepository : PickupZoneRepository {
        val zones = mutableMapOf<String, PickupZone>()
        val employeeCompanies = mutableMapOf<String, String>()
        val employeeZones = mutableMapOf<String, String?>()
        private var idCounter = 1

        override fun create(
            companyId: String,
            name: String,
            latitude: Double,
            longitude: Double,
            radius: Int,
            address: String?
        ): PickupZone {
            val id = "zone-${idCounter++}"
            val now = Instant.now()
            val zone = PickupZone(
                id = id,
                companyId = companyId,
                name = name,
                latitude = latitude,
                longitude = longitude,
                radius = radius,
                address = address,
                active = true,
                createdAt = now,
                updatedAt = now
            )
            zones[id] = zone
            return zone
        }

        override fun findById(id: String): PickupZone? = zones[id]

        override fun findByCompany(companyId: String): List<PickupZone> =
            zones.values.filter { it.companyId == companyId }

        override fun findActiveByCompany(companyId: String): List<PickupZone> =
            zones.values.filter { it.companyId == companyId && it.active }

        override fun update(
            id: String,
            companyId: String,
            name: String?,
            latitude: Double?,
            longitude: Double?,
            radius: Int?,
            address: String?,
            active: Boolean?
        ): PickupZone? {
            val existing = zones[id] ?: return null
            if (existing.companyId != companyId) return null
            val updated = existing.copy(
                name = name ?: existing.name,
                latitude = latitude ?: existing.latitude,
                longitude = longitude ?: existing.longitude,
                radius = radius ?: existing.radius,
                address = address ?: existing.address,
                active = active ?: existing.active,
                updatedAt = Instant.now()
            )
            zones[id] = updated
            return updated
        }

        override fun deactivate(id: String, companyId: String): Boolean {
            val existing = zones[id] ?: return false
            if (existing.companyId != companyId) return false
            zones[id] = existing.copy(active = false, updatedAt = Instant.now())
            return true
        }

        override fun findEmployeeCompanyId(employeeId: String): String? = employeeCompanies[employeeId]

        override fun assignEmployeeToZone(employeeId: String, zoneId: String?): Boolean {
            if (!employeeCompanies.containsKey(employeeId)) return false
            employeeZones[employeeId] = zoneId
            return true
        }

        override fun findEmployeePickupZoneId(employeeId: String): String? = employeeZones[employeeId]
    }

    private fun ApplicationTestBuilder.setupTestApp(pickupZoneRepo: TestPickupZoneRepository) {
        val pickupZoneService = PickupZoneService(pickupZoneRepo)

        application {
            install(ServerContentNegotiation) {
                json()
            }
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
                pickupZoneRoutes(pickupZoneService)
            }
        }
    }

    // =========================================================================
    // CREATION & READ TESTS
    // =========================================================================
    @Test
    fun `Admin can successfully create a pickup zone for their company`() = testApplication {
        val repo = TestPickupZoneRepository()
        setupTestApp(repo)

        val adminToken = generateToken("adm-1", "admin", "comp-A")
        val req = CreatePickupZoneRequest(
            name = "Whitefield Gate",
            latitude = 12.9698,
            longitude = 77.7499,
            radius = 150,
            address = "Whitefield Gate, Bengaluru"
        )

        val response = client.post("/pickup-zones") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(req))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<PickupZoneResponse>(response.bodyAsText())
        assertEquals("Whitefield Gate", body.name)
        assertEquals(12.9698, body.latitude)
        assertEquals(77.7499, body.longitude)
        assertEquals(150, body.radius)
        assertEquals("Whitefield Gate, Bengaluru", body.address)
        assertEquals("comp-A", body.companyId)
        assertTrue(body.active)
    }

    @Test
    fun `Admin can retrieve pickup zone by ID`() = testApplication {
        val repo = TestPickupZoneRepository()
        setupTestApp(repo)

        val zone = repo.create("comp-A", "Tech Park Zone", 12.9352, 77.6245, 100, "Main Gate")
        val adminToken = generateToken("adm-1", "admin", "comp-A")

        val response = client.get("/pickup-zones/${zone.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<PickupZoneResponse>(response.bodyAsText())
        assertEquals(zone.id, body.id)
        assertEquals("Tech Park Zone", body.name)
    }

    // =========================================================================
    // TENANT ISOLATION TESTS
    // =========================================================================
    @Test
    fun `Company A cannot access Company B pickup zone`() = testApplication {
        val repo = TestPickupZoneRepository()
        setupTestApp(repo)

        val zoneB = repo.create("comp-B", "Company B Gate", 12.9352, 77.6245, 100, "B Gate")
        val adminTokenA = generateToken("adm-A", "admin", "comp-A")

        // GET zone of another company returns 404
        val getRes = client.get("/pickup-zones/${zoneB.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminTokenA")
        }
        assertEquals(HttpStatusCode.NotFound, getRes.status)

        // PUT zone of another company returns 404
        val putRes = client.put("/pickup-zones/${zoneB.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminTokenA")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(UpdatePickupZoneRequest(name = "Hacked Zone")))
        }
        assertEquals(HttpStatusCode.NotFound, putRes.status)

        // DELETE zone of another company returns 404
        val delRes = client.delete("/pickup-zones/${zoneB.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminTokenA")
        }
        assertEquals(HttpStatusCode.NotFound, delRes.status)
        assertTrue(repo.findById(zoneB.id)!!.active) // Remains untouched
    }

    @Test
    fun `Listing pickup zones returns only the caller's company zones`() = testApplication {
        val repo = TestPickupZoneRepository()
        setupTestApp(repo)

        repo.create("comp-A", "Zone A1", 12.90, 77.50, 100)
        repo.create("comp-A", "Zone A2", 12.91, 77.51, 150)
        repo.create("comp-B", "Zone B1", 12.92, 77.52, 200)

        val adminTokenA = generateToken("adm-A", "admin", "comp-A")
        val response = client.get("/pickup-zones") {
            header(HttpHeaders.Authorization, "Bearer $adminTokenA")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val zones = Json.decodeFromString<List<PickupZoneResponse>>(response.bodyAsText())
        assertEquals(2, zones.size)
        assertTrue(zones.all { it.companyId == "comp-A" })
    }

    // =========================================================================
    // VALIDATION TESTS
    // =========================================================================
    @Test
    fun `Validation rejects invalid latitude, longitude, radius, and blank name`() = testApplication {
        val repo = TestPickupZoneRepository()
        setupTestApp(repo)

        val adminToken = generateToken("adm-1", "admin", "comp-A")

        // Invalid latitude (> 90)
        val badLatRes = client.post("/pickup-zones") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreatePickupZoneRequest("Gate", 95.0, 77.0, 100)))
        }
        assertEquals(HttpStatusCode.BadRequest, badLatRes.status)

        // Invalid longitude (< -180)
        val badLngRes = client.post("/pickup-zones") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreatePickupZoneRequest("Gate", 12.0, -185.0, 100)))
        }
        assertEquals(HttpStatusCode.BadRequest, badLngRes.status)

        // Non-positive radius (0)
        val badRadiusRes = client.post("/pickup-zones") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreatePickupZoneRequest("Gate", 12.0, 77.0, 0)))
        }
        assertEquals(HttpStatusCode.BadRequest, badRadiusRes.status)

        // Blank name
        val blankNameRes = client.post("/pickup-zones") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreatePickupZoneRequest("   ", 12.0, 77.0, 100)))
        }
        assertEquals(HttpStatusCode.BadRequest, blankNameRes.status)
    }

    // =========================================================================
    // AUTHORIZATION / ROLE TESTS
    // =========================================================================
    @Test
    fun `Non-admin employee cannot create, update, or delete pickup zones`() = testApplication {
        val repo = TestPickupZoneRepository()
        setupTestApp(repo)

        val zone = repo.create("comp-A", "Zone A", 12.90, 77.50, 100)
        val empToken = generateToken("emp-1", "employee", "comp-A")

        // Employee cannot create zone
        val postRes = client.post("/pickup-zones") {
            header(HttpHeaders.Authorization, "Bearer $empToken")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(CreatePickupZoneRequest("New Zone", 12.90, 77.50, 100)))
        }
        assertEquals(HttpStatusCode.Forbidden, postRes.status)

        // Employee cannot update zone
        val putRes = client.put("/pickup-zones/${zone.id}") {
            header(HttpHeaders.Authorization, "Bearer $empToken")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(UpdatePickupZoneRequest(name = "Updated")))
        }
        assertEquals(HttpStatusCode.Forbidden, putRes.status)

        // Employee cannot delete zone
        val delRes = client.delete("/pickup-zones/${zone.id}") {
            header(HttpHeaders.Authorization, "Bearer $empToken")
        }
        assertEquals(HttpStatusCode.Forbidden, delRes.status)

        // However, employee CAN view zones belonging to their company
        val getRes = client.get("/pickup-zones/${zone.id}") {
            header(HttpHeaders.Authorization, "Bearer $empToken")
        }
        assertEquals(HttpStatusCode.OK, getRes.status)
    }

    @Test
    fun `Unauthenticated request is rejected with 401`() = testApplication {
        val repo = TestPickupZoneRepository()
        setupTestApp(repo)

        val res = client.get("/pickup-zones")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    // =========================================================================
    // SOFT DELETION TEST
    // =========================================================================
    @Test
    fun `Deleting a pickup zone deactivates it without hard deleting`() = testApplication {
        val repo = TestPickupZoneRepository()
        setupTestApp(repo)

        val zone = repo.create("comp-A", "Deletable Zone", 12.90, 77.50, 100)
        val adminToken = generateToken("adm-1", "admin", "comp-A")

        val delRes = client.delete("/pickup-zones/${zone.id}") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        assertEquals(HttpStatusCode.OK, delRes.status)

        val stored = repo.findById(zone.id)
        assertNotNull(stored)
        assertFalse(stored.active)

        // Active only query excludes it
        val activeRes = client.get("/pickup-zones?activeOnly=true") {
            header(HttpHeaders.Authorization, "Bearer $adminToken")
        }
        val activeZones = Json.decodeFromString<List<PickupZoneResponse>>(activeRes.bodyAsText())
        assertFalse(activeZones.any { it.id == zone.id })
    }

    // =========================================================================
    // EMPLOYEE ASSIGNMENT TESTS
    // =========================================================================
    @Test
    fun `Employee assignment allows same-company assignment and rejects cross-company assignment`() = testApplication {
        val repo = TestPickupZoneRepository().apply {
            employeeCompanies["emp-A"] = "comp-A"
            employeeCompanies["emp-B"] = "comp-B"
        }
        setupTestApp(repo)

        val zoneA = repo.create("comp-A", "Zone A", 12.90, 77.50, 100)
        val zoneB = repo.create("comp-B", "Zone B", 12.90, 77.50, 100)

        val adminTokenA = generateToken("adm-A", "admin", "comp-A")

        // 1. Same-company assignment: Employee A + Zone A by Company A admin -> ALLOWED
        val allowedRes = client.post("/pickup-zones/${zoneA.id}/employees/emp-A") {
            header(HttpHeaders.Authorization, "Bearer $adminTokenA")
        }
        assertEquals(HttpStatusCode.OK, allowedRes.status)
        assertEquals(zoneA.id, repo.findEmployeePickupZoneId("emp-A"))

        // 2. Cross-company assignment: Employee A + Zone B by Company A admin -> REJECTED (Zone B belongs to comp-B)
        val crossZoneRes = client.post("/pickup-zones/${zoneB.id}/employees/emp-A") {
            header(HttpHeaders.Authorization, "Bearer $adminTokenA")
        }
        assertEquals(HttpStatusCode.Forbidden, crossZoneRes.status)

        // 3. Cross-company employee: Employee B + Zone A by Company A admin -> REJECTED (Employee B belongs to comp-B)
        val crossEmpRes = client.post("/pickup-zones/${zoneA.id}/employees/emp-B") {
            header(HttpHeaders.Authorization, "Bearer $adminTokenA")
        }
        assertEquals(HttpStatusCode.Forbidden, crossEmpRes.status)
    }
}
