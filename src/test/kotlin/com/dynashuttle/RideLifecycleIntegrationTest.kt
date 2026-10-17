package com.dynashuttle

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dynashuttle.models.*
import com.dynashuttle.repositories.*
import com.dynashuttle.routes.rideRoutes
import com.dynashuttle.routes.tripRoutes
import com.dynashuttle.services.*
import com.dynashuttle.services.routing.OptimizedStop
import com.dynashuttle.services.routing.RouteOptimizer
import com.dynashuttle.services.routing.StopCandidate
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RideLifecycleIntegrationTest {

    private val jwtSecret = "lifecycle-integration-test-secret"
    private val jwtIssuer = "dynashuttle"

    private fun generateToken(userId: String, role: String, companyId: String): String =
        JWT.create()
            .withIssuer(jwtIssuer)
            .withClaim("userId", userId)
            .withClaim("role", role)
            .withClaim("companyId", companyId)
            .sign(Algorithm.HMAC256(jwtSecret))

    // ---------------------------------------------------------------------------
    // Test Repositories
    // ---------------------------------------------------------------------------

    private class LifecycleTestVehicleRepository : VehicleRepository {
        val candidates = mutableListOf<VehicleCandidate>()
        val tripDetailsMap = mutableMapOf<String, TripDetails>()

        override fun findAvailableNear(lat: Double, lng: Double, radiusMeters: Int): List<VehicleCandidate> =
            candidates

        override fun findAvailableNear(lat: Double, lng: Double, radiusMeters: Int, companyId: String?): List<VehicleCandidate> =
            if (companyId == null) candidates else candidates.filter { it.companyId == companyId }

        override fun updateLocation(vehicleId: String, lat: Double, lng: Double) {}
        override fun findVehicleIdByTripId(tripId: String): String? = tripDetailsMap[tripId]?.vehicleId
        override fun findTripDetails(tripId: String): TripDetails? = tripDetailsMap[tripId]
        override fun findVehicleLocationByTripId(tripId: String): VehicleLocation? =
            tripDetailsMap[tripId]?.let { VehicleLocation(it.vehicleId, 12.97, 77.59) }
    }

    private class LifecycleTestRideRequestRepository(
        private val tripCapacities: MutableMap<String, Int> = mutableMapOf()
    ) : RideRequestRepository {
        val store = mutableMapOf<String, StoredRideRequest>()
        private val allocatedSeats = mutableMapOf<String, AtomicInteger>()
        private var idCounter = 1

        fun setCapacity(tripId: String, capacity: Int) {
            tripCapacities[tripId] = capacity
        }

        fun allocatedCount(tripId: String): Int = allocatedSeats[tripId]?.get() ?: 0

        @Synchronized
        override fun allocateSeat(request: RideRequestDto, tripId: String, companyId: String?): AllocationResult {
            val capacity = tripCapacities[tripId] ?: return AllocationResult.TripNotFound
            val activeHolding = store.values.count { it.tripId == tripId && it.status in listOf("MATCHED", "PICKUP_PENDING", "ONBOARD") }
            val counter = allocatedSeats.getOrPut(tripId) { AtomicInteger(0) }
            val currentOccupied = maxOf(counter.get(), activeHolding)
            if (currentOccupied >= capacity) {
                return AllocationResult.CapacityExhausted
            }
            counter.set(currentOccupied + 1)
            val id = "req-${idCounter++}"
            val stored = StoredRideRequest(
                id = id,
                tripId = tripId,
                status = RideStatus.MATCHED.name,
                pickupLat = request.pickupLat,
                pickupLng = request.pickupLng,
                employeeId = request.employeeId,
                companyId = companyId
            )
            store[id] = stored
            return AllocationResult.Allocated(stored)
        }

        @Synchronized
        override fun save(request: RideRequestDto, tripId: String?, status: String): StoredRideRequest {
            val id = "req-${idCounter++}"
            val compId = if (request.employeeId.contains("comp-B")) "comp-B" else "comp-A"
            val stored = StoredRideRequest(
                id = id,
                tripId = tripId,
                status = status,
                pickupLat = request.pickupLat,
                pickupLng = request.pickupLng,
                employeeId = request.employeeId,
                companyId = compId
            )
            store[id] = stored
            return stored
        }

        @Synchronized
        override fun findById(id: String): StoredRideRequest? = store[id]

        @Synchronized
        override fun updateStatus(id: String, status: String, tripId: String?) {
            val existing = store[id] ?: return
            store[id] = existing.copy(status = status, tripId = tripId ?: existing.tripId)
        }

        @Synchronized
        override fun transitionStatus(
            id: String,
            expectedStatus: RideStatus,
            newStatus: RideStatus,
            tripId: String?
        ): TransitionResult {
            val existing = store[id] ?: return TransitionResult.NotFound
            val currentStatus = RideStatus.fromString(existing.status)
            if (currentStatus != expectedStatus) {
                return TransitionResult.StateMismatch
            }
            store[id] = existing.copy(
                status = newStatus.name,
                tripId = tripId ?: existing.tripId
            )
            return TransitionResult.Success
        }

        @Synchronized
        fun promoteWaitlistedAtomic(id: String, tripId: String): Boolean {
            val capacity = tripCapacities[tripId] ?: return false
            val counter = allocatedSeats.getOrPut(tripId) { AtomicInteger(0) }
            if (counter.get() >= capacity) {
                return false
            }
            val res = transitionStatus(id, RideStatus.WAITLISTED, RideStatus.MATCHED, tripId)
            if (res == TransitionResult.Success) {
                counter.incrementAndGet()
                return true
            }
            return false
        }

        override fun findActiveByTripId(tripId: String): List<ActiveTripStop> =
            store.values.filter { it.tripId == tripId && (it.status == "MATCHED" || it.status == "PICKUP_PENDING") }
                .map { ActiveTripStop(it.id, it.employeeId ?: "Employee", it.pickupLat, it.pickupLng, it.employeeId) }

        override fun hasActivePassenger(tripId: String, employeeId: String): Boolean =
            store.values.any { it.tripId == tripId && it.employeeId == employeeId && it.status in listOf("MATCHED", "PICKUP_PENDING", "ONBOARD") }

        override fun countActiveRiders(tripId: String): Int =
            store.values.count { it.tripId == tripId && it.status in listOf("MATCHED", "PICKUP_PENDING", "ONBOARD") }
    }

    private class LifecycleTestRouteOptimizer : RouteOptimizer {
        override suspend fun optimize(vehicleLat: Double, vehicleLng: Double, stops: List<StopCandidate>): List<OptimizedStop> =
            stops.mapIndexed { idx, s -> OptimizedStop(s.rideRequestId, s.lat, s.lng, idx + 1, 5) }
    }

    private fun ApplicationTestBuilder.setupLifecycleApp(
        vehicleRepo: LifecycleTestVehicleRepository,
        rideRepo: LifecycleTestRideRequestRepository
    ) {
        val routingService = RoutingService(vehicleRepo, LifecycleTestRouteOptimizer(), rideRepo)
        val tripService = TripService(rideRepo, vehicleRepo, LifecycleTestRouteOptimizer())

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
            }
            routing {
                rideRoutes(routingService, tripService)
                tripRoutes(tripService)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Tests X–AG: End-to-End Lifecycle & Transition Guarantees
    // ---------------------------------------------------------------------------

    @Test
    fun `Test X - No available vehicle or capacity exhausted returns WAITLISTED not QUEUED`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository() // No vehicles
        val rideRepo = LifecycleTestRideRequestRepository()
        setupLifecycleApp(vehicleRepo, rideRepo)

        val token = generateToken("emp-1", "employee", "comp-A")
        val response = client.post("/rides") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(RideRequestDto("emp-1", 12.9716, 77.5946, 12.9900, 77.6100)))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<RideResponseDto>(response.bodyAsText())
        assertEquals("WAITLISTED", body.status, "Must return WAITLISTED when no vehicle is available")
        assertNotEquals("QUEUED", body.status, "Must NEVER return QUEUED")
        assertNull(body.assignedVehicleId)
    }

    @Test
    fun `Test Y - Available capacity matches request and returns MATCHED`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository().apply {
            candidates.add(VehicleCandidate("veh-1", "trip-1", "drv-1", 12.97, 77.59, 4, "comp-A"))
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = LifecycleTestRideRequestRepository(mutableMapOf("trip-1" to 4))
        setupLifecycleApp(vehicleRepo, rideRepo)

        val token = generateToken("emp-1", "employee", "comp-A")
        val response = client.post("/rides") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(RideRequestDto("emp-1", 12.9716, 77.5946, 12.9900, 77.6100)))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<RideResponseDto>(response.bodyAsText())
        assertEquals("MATCHED", body.status)
        assertEquals("veh-1", body.assignedVehicleId)
        assertEquals(1, rideRepo.allocatedCount("trip-1"))
    }

    @Test
    fun `Test Z - Waitlisted request transitions to MATCHED when capacity becomes available`() = runBlocking {
        val rideRepo = LifecycleTestRideRequestRepository(mutableMapOf("trip-1" to 1))
        val initial = rideRepo.save(
            RideRequestDto("emp-1", 12.9716, 77.5946, 12.9900, 77.6100),
            tripId = null,
            status = RideStatus.WAITLISTED.name
        )

        assertEquals(RideStatus.WAITLISTED.name, initial.status)

        // Validate via state machine that WAITLISTED -> MATCHED is valid
        assertTrue(RideRequestStateMachine.canTransition(RideStatus.WAITLISTED, RideStatus.MATCHED))

        // Atomic transition from WAITLISTED to MATCHED on trip-1
        val res = rideRepo.transitionStatus(initial.id, RideStatus.WAITLISTED, RideStatus.MATCHED, "trip-1")
        assertEquals(TransitionResult.Success, res)

        val updated = rideRepo.findById(initial.id)
        assertNotNull(updated)
        assertEquals(RideStatus.MATCHED.name, updated.status)
        assertEquals("trip-1", updated.tripId)
    }

    @Test
    fun `Test AA - Concurrent promotion of waitlisted requests cannot overbook capacity`() {
        val capacity = 2
        val candidatesCount = 8
        val tripId = "trip-AA"
        val rideRepo = LifecycleTestRideRequestRepository(mutableMapOf(tripId to capacity))

        val waitlistedIds = (1..candidatesCount).map { i ->
            rideRepo.save(
                RideRequestDto("emp-$i", 12.97, 77.59, 12.99, 77.61),
                tripId = null,
                status = RideStatus.WAITLISTED.name
            ).id
        }

        val barrier = CyclicBarrier(candidatesCount)
        val promotedCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(candidatesCount)

        waitlistedIds.forEach { id ->
            executor.submit {
                barrier.await()
                val success = rideRepo.promoteWaitlistedAtomic(id, tripId)
                if (success) {
                    promotedCount.incrementAndGet()
                }
            }
        }

        executor.shutdown()
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(capacity, promotedCount.get(), "Only $capacity requests can be promoted up to capacity")
        assertEquals(capacity, rideRepo.allocatedCount(tripId), "Seat count must strictly equal capacity")
    }

    @Test
    fun `Test AB - Employee cannot cancel another employee ride request`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository()
        val rideRepo = LifecycleTestRideRequestRepository().apply {
            store["req-alice"] = StoredRideRequest(
                id = "req-alice",
                tripId = "trip-1",
                status = "MATCHED",
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-alice",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        // Bob tries to cancel Alice's ride request
        val bobToken = generateToken("emp-bob", "employee", "comp-A")
        val response = client.post("/rides/req-alice/cancel") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(RideStatus.MATCHED.name, rideRepo.findById("req-alice")?.status)
    }

    @Test
    fun `Test AC - Cross-company employee cannot cancel ride request returning 404`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository()
        val rideRepo = LifecycleTestRideRequestRepository().apply {
            store["req-comp-a"] = StoredRideRequest(
                id = "req-comp-a",
                tripId = "trip-A",
                status = "MATCHED",
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-A",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        // Employee in Company B tries to cancel Company A's ride
        val compBToken = generateToken("emp-B", "employee", "comp-B")
        val response = client.post("/rides/req-comp-a/cancel") {
            header(HttpHeaders.Authorization, "Bearer $compBToken")
        }

        assertEquals(HttpStatusCode.NotFound, response.status, "Cross-company resource access must return 404")
        assertEquals(RideStatus.MATCHED.name, rideRepo.findById("req-comp-a")?.status)
    }

    @Test
    fun `Test AD - Unassigned driver cannot perform pickup returning 403`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = LifecycleTestRideRequestRepository().apply {
            store["req-1"] = StoredRideRequest(
                id = "req-1",
                tripId = "trip-1",
                status = "PICKUP_PENDING",
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        // drv-2 (not assigned) tries to perform pickup
        val drv2Token = generateToken("drv-2", "driver", "comp-A")
        val response = client.post("/trips/trip-1/stops/req-1/pickup") {
            header(HttpHeaders.Authorization, "Bearer $drv2Token")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(RideStatus.PICKUP_PENDING.name, rideRepo.findById("req-1")?.status)
    }

    @Test
    fun `Test AE - Assigned driver can perform pickup transitioning PICKUP_PENDING to ONBOARD`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = LifecycleTestRideRequestRepository().apply {
            store["req-1"] = StoredRideRequest(
                id = "req-1",
                tripId = "trip-1",
                status = "PICKUP_PENDING",
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        // Assigned driver performs pickup
        val drv1Token = generateToken("drv-1", "driver", "comp-A")
        val response = client.post("/trips/trip-1/stops/req-1/pickup") {
            header(HttpHeaders.Authorization, "Bearer $drv1Token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<StopActionResponse>(response.bodyAsText())
        assertEquals("ONBOARD", body.status)
        assertEquals(RideStatus.ONBOARD.name, rideRepo.findById("req-1")?.status)
    }

    @Test
    fun `Test AF - Employee cannot perform driver-only transitions returning 403`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = LifecycleTestRideRequestRepository().apply {
            store["req-1"] = StoredRideRequest(
                id = "req-1",
                tripId = "trip-1",
                status = "PICKUP_PENDING",
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        val empToken = generateToken("emp-1", "employee", "comp-A")
        val response = client.post("/trips/trip-1/stops/req-1/pickup") {
            header(HttpHeaders.Authorization, "Bearer $empToken")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `Test AG - Invalid lifecycle transition returns 422 Unprocessable Entity`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = LifecycleTestRideRequestRepository().apply {
            // Ride is ONBOARD: cannot be cancelled
            store["req-onboard"] = StoredRideRequest(
                id = "req-onboard",
                tripId = "trip-1",
                status = "ONBOARD",
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
            // Ride is PICKUP_PENDING: cannot directly complete (must be ONBOARD first)
            store["req-pending"] = StoredRideRequest(
                id = "req-pending",
                tripId = "trip-1",
                status = "PICKUP_PENDING",
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        // 1. Employee tries to cancel ride while ONBOARD -> 422
        val empToken = generateToken("emp-1", "employee", "comp-A")
        val cancelResponse = client.post("/rides/req-onboard/cancel") {
            header(HttpHeaders.Authorization, "Bearer $empToken")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, cancelResponse.status)
        val cancelBody = Json.decodeFromString<ErrorResponse>(cancelResponse.bodyAsText())
        assertEquals("invalid_transition", cancelBody.error)

        // 2. Driver tries to complete ride that is still PICKUP_PENDING -> 422
        val drvToken = generateToken("drv-1", "driver", "comp-A")
        val completeResponse = client.post("/trips/trip-1/stops/req-pending/complete") {
            header(HttpHeaders.Authorization, "Bearer $drvToken")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, completeResponse.status)
        val completeBody = Json.decodeFromString<ErrorResponse>(completeResponse.bodyAsText())
        assertEquals("invalid_transition", completeBody.error)
    }

    @Test
    fun `Test AH - Successful no-show transition updates status to NO_SHOW`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = LifecycleTestRideRequestRepository().apply {
            store["req-noshow"] = StoredRideRequest(
                id = "req-noshow",
                tripId = "trip-1",
                status = RideStatus.PICKUP_PENDING.name,
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        val drvToken = generateToken("drv-1", "driver", "comp-A")
        val response = client.post("/trips/trip-1/stops/req-noshow/no-show") {
            header(HttpHeaders.Authorization, "Bearer $drvToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<StopActionResponse>(response.bodyAsText())
        assertEquals("NO_SHOW", body.status)
        assertEquals(RideStatus.NO_SHOW.name, rideRepo.findById("req-noshow")?.status)
    }

    @Test
    fun `Test AI - Successful completion transition updates status to COMPLETED`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = LifecycleTestRideRequestRepository().apply {
            store["req-onboard"] = StoredRideRequest(
                id = "req-onboard",
                tripId = "trip-1",
                status = RideStatus.ONBOARD.name,
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        val drvToken = generateToken("drv-1", "driver", "comp-A")
        val response = client.post("/trips/trip-1/stops/req-onboard/complete") {
            header(HttpHeaders.Authorization, "Bearer $drvToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<StopActionResponse>(response.bodyAsText())
        assertEquals("COMPLETED", body.status)
        assertEquals(RideStatus.COMPLETED.name, rideRepo.findById("req-onboard")?.status)
    }

    @Test
    fun `Test AJ - Invalid pickup transition returns 422 Unprocessable Entity`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = LifecycleTestRideRequestRepository().apply {
            // MATCHED cannot directly pickup (must be PICKUP_PENDING first)
            store["req-matched"] = StoredRideRequest(
                id = "req-matched",
                tripId = "trip-1",
                status = RideStatus.MATCHED.name,
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        val drvToken = generateToken("drv-1", "driver", "comp-A")
        val response = client.post("/trips/trip-1/stops/req-matched/pickup") {
            header(HttpHeaders.Authorization, "Bearer $drvToken")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("invalid_transition", body.error)
    }

    @Test
    fun `Test AK - Invalid no-show transition returns 422 Unprocessable Entity`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = LifecycleTestRideRequestRepository().apply {
            // ONBOARD cannot transition to NO_SHOW
            store["req-onboard"] = StoredRideRequest(
                id = "req-onboard",
                tripId = "trip-1",
                status = RideStatus.ONBOARD.name,
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        val drvToken = generateToken("drv-1", "driver", "comp-A")
        val response = client.post("/trips/trip-1/stops/req-onboard/no-show") {
            header(HttpHeaders.Authorization, "Bearer $drvToken")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
        assertEquals("invalid_transition", body.error)
    }

    @Test
    fun `Test AL - Terminal states cannot transition again returning 422`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = LifecycleTestRideRequestRepository().apply {
            store["req-completed"] = StoredRideRequest(
                id = "req-completed",
                tripId = "trip-1",
                status = RideStatus.COMPLETED.name,
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
            store["req-cancelled"] = StoredRideRequest(
                id = "req-cancelled",
                tripId = "trip-1",
                status = RideStatus.CANCELLED.name,
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
            store["req-noshow"] = StoredRideRequest(
                id = "req-noshow",
                tripId = "trip-1",
                status = RideStatus.NO_SHOW.name,
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        val drvToken = generateToken("drv-1", "driver", "comp-A")
        val empToken = generateToken("emp-1", "employee", "comp-A")

        // 1. COMPLETED cannot pickup
        val res1 = client.post("/trips/trip-1/stops/req-completed/pickup") {
            header(HttpHeaders.Authorization, "Bearer $drvToken")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, res1.status)

        // 2. CANCELLED cannot complete
        val res2 = client.post("/trips/trip-1/stops/req-cancelled/complete") {
            header(HttpHeaders.Authorization, "Bearer $drvToken")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, res2.status)

        // 3. NO_SHOW cannot pickup
        val res3 = client.post("/trips/trip-1/stops/req-noshow/pickup") {
            header(HttpHeaders.Authorization, "Bearer $drvToken")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, res3.status)

        // 4. Employee cannot cancel COMPLETED
        val res4 = client.post("/rides/req-completed/cancel") {
            header(HttpHeaders.Authorization, "Bearer $empToken")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, res4.status)
    }

    @Test
    fun `Test AM - Unassigned driver cannot complete or mark no-show returning 403`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = LifecycleTestRideRequestRepository().apply {
            store["req-1"] = StoredRideRequest(
                id = "req-1",
                tripId = "trip-1",
                status = RideStatus.PICKUP_PENDING.name,
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
            store["req-2"] = StoredRideRequest(
                id = "req-2",
                tripId = "trip-1",
                status = RideStatus.ONBOARD.name,
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-2",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        val drv2Token = generateToken("drv-2", "driver", "comp-A")

        val resNoShow = client.post("/trips/trip-1/stops/req-1/no-show") {
            header(HttpHeaders.Authorization, "Bearer $drv2Token")
        }
        assertEquals(HttpStatusCode.Forbidden, resNoShow.status)

        val resComplete = client.post("/trips/trip-1/stops/req-2/complete") {
            header(HttpHeaders.Authorization, "Bearer $drv2Token")
        }
        assertEquals(HttpStatusCode.Forbidden, resComplete.status)
    }

    @Test
    fun `Test AN - Cross-company driver actions return 404`() = testApplication {
        val vehicleRepo = LifecycleTestVehicleRepository().apply {
            tripDetailsMap["trip-1"] = TripDetails("trip-1", "veh-1", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = LifecycleTestRideRequestRepository().apply {
            store["req-1"] = StoredRideRequest(
                id = "req-1",
                tripId = "trip-1",
                status = RideStatus.PICKUP_PENDING.name,
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        val drvCompBToken = generateToken("drv-B", "driver", "comp-B")

        val resPickup = client.post("/trips/trip-1/stops/req-1/pickup") {
            header(HttpHeaders.Authorization, "Bearer $drvCompBToken")
        }
        assertEquals(HttpStatusCode.NotFound, resPickup.status)

        val resNoShow = client.post("/trips/trip-1/stops/req-1/no-show") {
            header(HttpHeaders.Authorization, "Bearer $drvCompBToken")
        }
        assertEquals(HttpStatusCode.NotFound, resNoShow.status)

        val resComplete = client.post("/trips/trip-1/stops/req-1/complete") {
            header(HttpHeaders.Authorization, "Bearer $drvCompBToken")
        }
        assertEquals(HttpStatusCode.NotFound, resComplete.status)
    }

    @Test
    fun `Test AO - Active capacity counting treats MATCHED, PICKUP_PENDING, and ONBOARD as holding seats`() = testApplication {
        val tripId = "trip-AO"
        val vehicleRepo = LifecycleTestVehicleRepository().apply {
            candidates.add(VehicleCandidate("veh-AO", tripId, "drv-1", 12.97, 77.59, 2, "comp-A"))
            tripDetailsMap[tripId] = TripDetails(tripId, "veh-AO", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = LifecycleTestRideRequestRepository(mutableMapOf(tripId to 2)).apply {
            // One rider is ONBOARD, one is PICKUP_PENDING => 2 seats occupied (capacity = 2)
            store["req-onboard"] = StoredRideRequest(
                id = "req-onboard",
                tripId = tripId,
                status = RideStatus.ONBOARD.name,
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-1",
                companyId = "comp-A"
            )
            store["req-pending"] = StoredRideRequest(
                id = "req-pending",
                tripId = tripId,
                status = RideStatus.PICKUP_PENDING.name,
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-2",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        val token = generateToken("emp-3", "employee", "comp-A")
        val response = client.post("/rides") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(RideRequestDto("emp-3", 12.9716, 77.5946, 12.9900, 77.6100)))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.decodeFromString<RideResponseDto>(response.bodyAsText())
        assertEquals("WAITLISTED", body.status, "Must return WAITLISTED when capacity occupied by ONBOARD and PICKUP_PENDING")
        assertNull(body.assignedVehicleId)
    }

    @Test
    fun `Test AP - Onboard passenger can view trip stop list`() = testApplication {
        val tripId = "trip-AP"
        val vehicleRepo = LifecycleTestVehicleRepository().apply {
            tripDetailsMap[tripId] = TripDetails(tripId, "veh-AP", "drv-1", "comp-A", "IN_PROGRESS")
        }
        val rideRepo = LifecycleTestRideRequestRepository().apply {
            store["req-onboard"] = StoredRideRequest(
                id = "req-onboard",
                tripId = tripId,
                status = RideStatus.ONBOARD.name,
                pickupLat = 12.97,
                pickupLng = 77.59,
                employeeId = "emp-onboard",
                companyId = "comp-A"
            )
        }
        setupLifecycleApp(vehicleRepo, rideRepo)

        val empToken = generateToken("emp-onboard", "employee", "comp-A")
        val response = client.get("/trips/$tripId/stops") {
            header(HttpHeaders.Authorization, "Bearer $empToken")
        }

        assertEquals(HttpStatusCode.OK, response.status, "Onboard passenger must have access to trip stop list")
    }
}
