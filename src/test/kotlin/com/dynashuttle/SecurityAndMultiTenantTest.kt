package com.dynashuttle

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dynashuttle.models.*
import com.dynashuttle.repositories.*
import com.dynashuttle.routes.rideRoutes
import com.dynashuttle.routes.tripRoutes
import com.dynashuttle.routes.websocketRoutes
import com.dynashuttle.services.*
import com.dynashuttle.services.routing.OptimizedStop
import com.dynashuttle.services.routing.RouteOptimizer
import com.dynashuttle.services.routing.StopCandidate
import io.ktor.client.plugins.websocket.*
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
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.websocket.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class SecurityAndMultiTenantTest {

    private val jwtSecret = "security-test-secret"
    private val jwtIssuer = "dynashuttle"

    private fun createAuthService(
        employees: Map<String, AuthEmployee> = emptyMap(),
        admins: Map<String, AuthAdmin> = emptyMap()
    ): AuthService {
        val repo = object : AuthRepository {
            override fun findEmployeeByEmail(email: String): AuthEmployee? = employees.values.find { it.email == email }
            override fun findAdminByEmail(email: String): AuthAdmin? = admins.values.find { it.email == email }
        }
        val generator = object : OtpGenerator {
            override fun generate(email: String): String = "123456"
        }
        val hasher = object : PasswordHasher {
            override fun hash(password: String): String = "hash-$password"
            override fun verify(password: String, hashedPassword: String): Boolean = hashedPassword == "hash-$password"
        }
        return AuthService(repo, generator, hasher, jwtSecret, jwtIssuer)
    }

    private class TestVehicleRepository : VehicleRepository {
        val candidates = mutableListOf<VehicleCandidate>()
        val tripDetailsMap = mutableMapOf<String, TripDetails>()
        val locations = mutableMapOf<String, Pair<Double, Double>>()

        override fun findAvailableNear(lat: Double, lng: Double, radiusMeters: Int): List<VehicleCandidate> =
            findAvailableNear(lat, lng, radiusMeters, null)

        override fun findAvailableNear(lat: Double, lng: Double, radiusMeters: Int, companyId: String?): List<VehicleCandidate> {
            return if (companyId == null) {
                candidates
            } else {
                candidates.filter { it.companyId == companyId }
            }
        }

        override fun updateLocation(vehicleId: String, lat: Double, lng: Double) {
            locations[vehicleId] = lat to lng
        }

        override fun findVehicleIdByTripId(tripId: String): String? = tripDetailsMap[tripId]?.vehicleId

        override fun findTripDetails(tripId: String): TripDetails? = tripDetailsMap[tripId]

        override fun findVehicleLocationByTripId(tripId: String): VehicleLocation? {
            val trip = tripDetailsMap[tripId] ?: return null
            val loc = locations[trip.vehicleId]
            return VehicleLocation(vehicleId = trip.vehicleId, lat = loc?.first ?: 12.9710, lng = loc?.second ?: 77.7480)
        }
    }

    private class TestRideRequestRepository : RideRequestRepository {
        val requests = mutableMapOf<String, StoredRideRequest>()
        val activeStops = mutableListOf<ActiveTripStop>()
        private var idCounter = 100

        override fun save(request: RideRequestDto, tripId: String?, status: String): StoredRideRequest {
            val id = "req-${idCounter++}"
            // Derive companyId for test if known, else default to "comp-A"
            val compId = if (request.employeeId.contains("comp-B") || request.employeeId == "emp-B") "comp-B" else "comp-A"
            val stored = StoredRideRequest(
                id = id,
                tripId = tripId,
                status = status,
                pickupLat = request.pickupLat,
                pickupLng = request.pickupLng,
                employeeId = request.employeeId,
                companyId = compId
            )
            requests[id] = stored
            return stored
        }

        override fun findById(id: String): StoredRideRequest? = requests[id]

        override fun updateStatus(id: String, status: String, tripId: String?) {
            val existing = requests[id] ?: return
            requests[id] = existing.copy(status = status, tripId = tripId ?: existing.tripId)
        }

        override fun findActiveByTripId(tripId: String): List<ActiveTripStop> =
            activeStops.filter { stop ->
                val r = requests[stop.rideRequestId]
                r?.tripId == tripId && r.status == "MATCHED"
            }
    }

    private class TestRouteOptimizer : RouteOptimizer {
        override suspend fun optimize(vehicleLat: Double, vehicleLng: Double, stops: List<StopCandidate>): List<OptimizedStop> {
            return stops.mapIndexed { idx, s ->
                OptimizedStop(s.rideRequestId, s.lat, s.lng, idx + 1, etaMinutes = 5)
            }
        }
    }

    // =========================================================================
    // TEST A: JWT contains companyId
    // =========================================================================
    @Test
    fun `Test A - JWT contains companyId claim for both employee and admin`() = runBlocking {
        val authService = createAuthService(
            employees = mapOf("emp-1" to AuthEmployee("emp-1", "emp@acme.com", "comp-acme", "Alice")),
            admins = mapOf("adm-1" to AuthAdmin("adm-1", "adm@acme.com", "hash-secret", "comp-acme"))
        )

        // Employee OTP flow
        authService.requestOtp("emp@acme.com")
        val empVerify = authService.verifyOtp("emp@acme.com", "123456")
        assertNotNull(empVerify)
        assertEquals("comp-acme", empVerify.companyId)

        val decodedEmpJwt = JWT.decode(empVerify.token)
        assertEquals("emp-1", decodedEmpJwt.getClaim("userId").asString())
        assertEquals("employee", decodedEmpJwt.getClaim("role").asString())
        assertEquals("comp-acme", decodedEmpJwt.getClaim("companyId").asString())

        // Admin login flow
        val adminLogin = authService.loginAdmin("adm@acme.com", "secret")
        assertNotNull(adminLogin)
        assertEquals("comp-acme", adminLogin.companyId)

        val decodedAdminJwt = JWT.decode(adminLogin.token)
        assertEquals("adm-1", decodedAdminJwt.getClaim("userId").asString())
        assertEquals("admin", decodedAdminJwt.getClaim("role").asString())
        assertEquals("comp-acme", decodedAdminJwt.getClaim("companyId").asString())
    }

    // =========================================================================
    // TEST F: Company A vehicle matching cannot return Company B vehicles
    // =========================================================================
    @Test
    fun `Test F - Company A vehicle matching cannot return Company B vehicles`() = runBlocking {
        val vehicleRepo = TestVehicleRepository().apply {
            candidates.add(
                VehicleCandidate(
                    vehicleId = "veh-B",
                    tripId = "trip-B",
                    driverId = "drv-B",
                    currentLat = 12.9710,
                    currentLng = 77.5940,
                    availableSeats = 4,
                    companyId = "comp-B" // Vehicle belongs to Company B
                )
            )
        }
        val rideRepo = TestRideRequestRepository()
        val routingService = RoutingService(vehicleRepo, TestRouteOptimizer(), rideRepo)

        val request = RideRequestDto(
            employeeId = "emp-A",
            pickupLat = 12.9716,
            pickupLng = 77.5946,
            destinationLat = 12.9900,
            destinationLng = 77.6100
        )

        // Request ride for Company A
        val response = routingService.matchRideRequest(request, companyId = "comp-A")

        // Must NOT match Company B's vehicle; must be WAITLISTED
        assertEquals("WAITLISTED", response.status)
        assertNull(response.assignedVehicleId)
        assertNull(response.etaMinutes)
    }

    // =========================================================================
    // Integration Setup Helper for Routes
    // =========================================================================
    private fun ApplicationTestBuilder.setupTestApp(
        vehicleRepo: TestVehicleRepository,
        rideRepo: TestRideRequestRepository
    ) {
        val routingService = RoutingService(vehicleRepo, TestRouteOptimizer(), rideRepo)
        val tripService = TripService(rideRepo, vehicleRepo, TestRouteOptimizer())

        application {
            install(ServerContentNegotiation) {
                json()
            }
            install(ServerWebSockets)
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
            }
            routing {
                rideRoutes(routingService, tripService)
                tripRoutes(tripService)
                websocketRoutes(vehicleRepo)
            }
        }
    }

    // =========================================================================
    // TEST B: Employee can create a legitimate ride for themselves
    // =========================================================================
    @Test
    fun `Test B - Employee can create a legitimate ride for themselves`() = testApplication {
        val vehicleRepo = TestVehicleRepository().apply {
            candidates.add(
                VehicleCandidate(
                    vehicleId = "veh-A",
                    tripId = "trip-A",
                    driverId = "drv-A",
                    currentLat = 12.9710,
                    currentLng = 77.5940,
                    availableSeats = 4,
                    companyId = "comp-A"
                )
            )
        }
        val rideRepo = TestRideRequestRepository()
        setupTestApp(vehicleRepo, rideRepo)

        val authService = createAuthService()
        val token = authService.generateToken(userId = "emp-1", role = "employee", companyId = "comp-A")

        val response = client.post("/rides") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                Json.encodeToString(
                    RideRequestDto(
                        employeeId = "emp-1", // Matches authenticated userId
                        pickupLat = 12.9716,
                        pickupLng = 77.5946,
                        destinationLat = 12.9900,
                        destinationLng = 77.6100
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<RideResponseDto>(response.bodyAsText())
        assertEquals("MATCHED", body.status)
        assertEquals("veh-A", body.assignedVehicleId)
    }

    // =========================================================================
    // TEST C: Employee cannot create a ride using another employee's ID
    // =========================================================================
    @Test
    fun `Test C - Employee cannot create a ride using another employee ID`() = testApplication {
        val vehicleRepo = TestVehicleRepository()
        val rideRepo = TestRideRequestRepository()
        setupTestApp(vehicleRepo, rideRepo)

        val authService = createAuthService()
        val token = authService.generateToken(userId = "emp-1", role = "employee", companyId = "comp-A")

        val response = client.post("/rides") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                Json.encodeToString(
                    RideRequestDto(
                        employeeId = "emp-2", // Does NOT match authenticated userId "emp-1"
                        pickupLat = 12.9716,
                        pickupLng = 77.5946,
                        destinationLat = 12.9900,
                        destinationLng = 77.6100
                    )
                )
            )
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // =========================================================================
    // TEST D: Employee cannot retrieve another employee's ride
    // =========================================================================
    @Test
    fun `Test D - Employee cannot retrieve another employee ride`() = testApplication {
        val vehicleRepo = TestVehicleRepository()
        val rideRepo = TestRideRequestRepository().apply {
            requests["ride-2"] = StoredRideRequest(
                id = "ride-2",
                tripId = "trip-A",
                status = "MATCHED",
                pickupLat = 12.9716,
                pickupLng = 77.5946,
                employeeId = "emp-2", // Belongs to emp-2
                companyId = "comp-A"
            )
        }
        setupTestApp(vehicleRepo, rideRepo)

        val authService = createAuthService()
        val token = authService.generateToken(userId = "emp-1", role = "employee", companyId = "comp-A")

        // emp-1 tries to read ride-2 belonging to emp-2
        val response = client.get("/rides/ride-2") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // =========================================================================
    // TEST E: Company A cannot access Company B's resource
    // =========================================================================
    @Test
    fun `Test E - Company A cannot access Company B ride resource`() = testApplication {
        val vehicleRepo = TestVehicleRepository()
        val rideRepo = TestRideRequestRepository().apply {
            requests["ride-B"] = StoredRideRequest(
                id = "ride-B",
                tripId = "trip-B",
                status = "MATCHED",
                pickupLat = 12.9716,
                pickupLng = 77.5946,
                employeeId = "emp-B",
                companyId = "comp-B" // Belongs to Company B
            )
        }
        setupTestApp(vehicleRepo, rideRepo)

        val authService = createAuthService()
        val token = authService.generateToken(userId = "emp-A", role = "employee", companyId = "comp-A")

        val response = client.get("/rides/ride-B") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // =========================================================================
    // TEST G: Employee cannot perform driver-only pickup operation
    // =========================================================================
    @Test
    fun `Test G - Employee cannot perform driver-only pickup operation`() = testApplication {
        val vehicleRepo = TestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = TestRideRequestRepository()
        setupTestApp(vehicleRepo, rideRepo)

        val authService = createAuthService()
        val token = authService.generateToken(userId = "emp-1", role = "employee", companyId = "comp-A")

        val response = client.post("/trips/trip-1/stops/stop-1/pickup") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // =========================================================================
    // TEST H: Employee cannot perform driver-only no-show operation
    // =========================================================================
    @Test
    fun `Test H - Employee cannot perform driver-only no-show operation`() = testApplication {
        val vehicleRepo = TestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = TestRideRequestRepository()
        setupTestApp(vehicleRepo, rideRepo)

        val authService = createAuthService()
        val token = authService.generateToken(userId = "emp-1", role = "employee", companyId = "comp-A")

        val response = client.post("/trips/trip-1/stops/stop-1/no-show") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // =========================================================================
    // TEST I: Driver A cannot operate Driver B's trip
    // =========================================================================
    @Test
    fun `Test I - Driver A cannot operate Driver B trip`() = testApplication {
        val vehicleRepo = TestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-B", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = TestRideRequestRepository()
        setupTestApp(vehicleRepo, rideRepo)

        val authService = createAuthService()
        // Driver A tries to operate trip assigned to Driver B
        val token = authService.generateToken(userId = "drv-A", role = "driver", companyId = "comp-A")

        val response = client.post("/trips/trip-1/stops/stop-1/pickup") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // =========================================================================
    // TEST J: Driver from Company A cannot operate Company B's trip
    // =========================================================================
    @Test
    fun `Test J - Driver from Company A cannot operate Company B trip`() = testApplication {
        val vehicleRepo = TestVehicleRepository().apply {
            tripDetailsMap["trip-B"] = TripDetails("trip-B", "veh-B", "drv-B", "comp-B", "IN_PROGRESS")
        }
        val rideRepo = TestRideRequestRepository()
        setupTestApp(vehicleRepo, rideRepo)

        val authService = createAuthService()
        // Driver from Company A
        val token = authService.generateToken(userId = "drv-A", role = "driver", companyId = "comp-A")

        val response = client.post("/trips/trip-B/stops/stop-1/pickup") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // =========================================================================
    // TEST K: Unauthorized WebSocket GPS update is rejected
    // =========================================================================
    @Test
    fun `Test K - Unauthorized WebSocket GPS update is rejected for employee caller`() = testApplication {
        val vehicleRepo = TestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = TestRideRequestRepository()
        setupTestApp(vehicleRepo, rideRepo)

        val authService = createAuthService()
        // Employee tries to connect to driver GPS channel
        val empToken = authService.generateToken(userId = "emp-1", role = "employee", companyId = "comp-A")

        val client = createClient {
            install(WebSockets)
        }

        assertFails {
            client.webSocket("/ws/driver/trip-1", request = {
                header(HttpHeaders.Authorization, "Bearer $empToken")
            }) {
                // Connection should be closed immediately by server policy
                val frame = incoming.receive()
                assertNull(frame)
            }
        }
    }

    // =========================================================================
    // TEST L: Driver can update GPS for their own assigned trip
    // =========================================================================
    @Test
    fun `Test L - Driver can update GPS for their own assigned trip`() = testApplication {
        val vehicleRepo = TestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = TestRideRequestRepository()
        setupTestApp(vehicleRepo, rideRepo)

        val authService = createAuthService()
        val driverToken = authService.generateToken(userId = "drv-1", role = "driver", companyId = "comp-A")

        val client = createClient {
            install(WebSockets)
        }

        client.webSocket("/ws/driver/trip-1", request = {
            header(HttpHeaders.Authorization, "Bearer $driverToken")
        }) {
            val update = DriverLocationUpdate("drv-1", "trip-1", 12.9800, 77.7500, System.currentTimeMillis())
            send(Frame.Text(Json.encodeToString(update)))
            close()
        }

        assertEquals(12.9800 to 77.7500, vehicleRepo.locations["veh-1"])
    }

    // =========================================================================
    // TEST M: Cross-company WebSocket trip subscription is rejected
    // =========================================================================
    @Test
    fun `Test M - Cross-company WebSocket trip subscription is rejected`() = testApplication {
        val vehicleRepo = TestVehicleRepository().apply {
            tripDetailsMap["trip-B"] = TripDetails("trip-B", "veh-B", "drv-B", "comp-B", "IN_PROGRESS")
        }
        val rideRepo = TestRideRequestRepository()
        setupTestApp(vehicleRepo, rideRepo)

        val authService = createAuthService()
        // User from Company A
        val userAToken = authService.generateToken(userId = "user-A", role = "employee", companyId = "comp-A")

        val client = createClient {
            install(WebSockets)
        }

        assertFails {
            client.webSocket("/ws/trip/trip-B", request = {
                header(HttpHeaders.Authorization, "Bearer $userAToken")
            }) {
                val frame = incoming.receive()
                assertNull(frame)
            }
        }
    }
}
