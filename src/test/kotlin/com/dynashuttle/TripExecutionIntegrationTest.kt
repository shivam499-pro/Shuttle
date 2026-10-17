package com.dynashuttle

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dynashuttle.models.*
import com.dynashuttle.repositories.*
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
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class TripExecutionIntegrationTest {

    private val jwtSecret = "execution-integration-test-secret"
    private val jwtIssuer = "dynashuttle"

    private fun generateToken(userId: String, role: String, companyId: String): String =
        JWT.create()
            .withIssuer(jwtIssuer)
            .withClaim("userId", userId)
            .withClaim("role", role)
            .withClaim("companyId", companyId)
            .sign(Algorithm.HMAC256(jwtSecret))

    // ---------------------------------------------------------------------------
    // Test Repositories (In-Memory Fakes for Unit / Integration Testing)
    // ---------------------------------------------------------------------------

    private class InMemoryTripRepository : TripRepository {
        val trips = mutableMapOf<String, Trip>()
        val companyIds = mutableMapOf<String, String>()

        override fun findById(tripId: String): Trip? = trips[tripId]
        override fun findByScheduleAndDate(scheduleId: String, tripDate: LocalDate): Trip? =
            trips.values.firstOrNull { it.scheduleId == scheduleId && it.tripDate == tripDate }

        override fun create(trip: Trip): Trip {
            trips[trip.id] = trip
            return trip
        }

        override fun findCompanyId(tripId: String): String? = companyIds[tripId]

        @Synchronized
        override fun updateStatus(
            tripId: String,
            expectedStatus: TripStatus,
            newStatus: TripStatus
        ): TransitionResult {
            val trip = trips[tripId] ?: return TransitionResult.NotFound
            if (trip.tripStatus != expectedStatus) return TransitionResult.StateMismatch
            trips[tripId] = trip.copy(status = newStatus.name)
            return TransitionResult.Success
        }

        @Synchronized
        override fun assignVehicleAndDriver(
            tripId: String,
            vehicleId: String,
            driverId: String
        ): TransitionResult {
            val trip = trips[tripId] ?: return TransitionResult.NotFound
            if (trip.tripStatus != TripStatus.SCHEDULED) return TransitionResult.StateMismatch
            trips[tripId] = trip.copy(
                vehicleId = vehicleId,
                driverId = driverId,
                status = TripStatus.ASSIGNED.name
            )
            return TransitionResult.Success
        }
    }

    private class InMemoryTripStopPersistenceRepository(
        private val rideRepo: InMemoryRideRequestRepository
    ) : TripStopPersistenceRepository {
        val stops = mutableMapOf<String, StoredTripStop>()

        override fun findById(stopId: String): StoredTripStop? = stops[stopId]
        override fun findByTripId(tripId: String): List<StoredTripStop> =
            stops.values.filter { it.tripId == tripId }.sortedBy { it.sequenceOrder }

        override fun create(stop: StoredTripStop): StoredTripStop {
            stops[stop.id] = stop
            return stop
        }

        override fun createBatch(stops: List<StoredTripStop>): List<StoredTripStop> =
            stops.map { create(it) }

        @Synchronized
        override fun transitionStopStatus(
            stopId: String,
            expectedStatus: StopStatus,
            newStatus: StopStatus,
            timestamp: Instant
        ): TransitionResult {
            val stop = stops[stopId] ?: return TransitionResult.NotFound
            if (stop.status != expectedStatus) return TransitionResult.StateMismatch
            stops[stopId] = stop.copy(status = newStatus, updatedAt = timestamp)
            return TransitionResult.Success
        }

        override fun countRemainingExecutableStops(tripId: String): Int =
            stops.values.count { it.tripId == tripId && !it.status.isTerminal }

        override fun countOnboardPassengers(tripId: String): Int =
            rideRepo.requests.values.count { it.tripId == tripId && it.status == RideStatus.ONBOARD.name }
    }

    private class InMemoryRideRequestRepository : RideRequestRepository {
        val requests = mutableMapOf<String, StoredRideRequest>()

        override fun findById(id: String): StoredRideRequest? = requests[id]

        override fun save(request: RideRequestDto, tripId: String?, status: String): StoredRideRequest {
            val stored = StoredRideRequest(
                id = UUID.randomUUID().toString(),
                tripId = tripId,
                status = status,
                pickupLat = request.pickupLat,
                pickupLng = request.pickupLng,
                employeeId = request.employeeId,
                companyId = null,
                pickupZoneId = request.pickupZoneId
            )
            requests[stored.id] = stored
            return stored
        }

        @Synchronized
        override fun updateStatus(id: String, status: String, tripId: String?) {
            val req = requests[id] ?: return
            requests[id] = req.copy(status = status, tripId = tripId ?: req.tripId)
        }

        @Synchronized
        override fun transitionStatus(
            id: String,
            expectedStatus: RideStatus,
            newStatus: RideStatus,
            tripId: String?
        ): TransitionResult {
            val req = requests[id] ?: return TransitionResult.NotFound
            if (req.status != expectedStatus.name) return TransitionResult.StateMismatch
            requests[id] = req.copy(status = newStatus.name, tripId = tripId ?: req.tripId)
            return TransitionResult.Success
        }

        override fun findActiveByTripId(tripId: String): List<ActiveTripStop> = emptyList()
    }

    private class FakeVehicleRepository : VehicleRepository {
        val tripDetailsMap = mutableMapOf<String, TripDetails>()

        override fun findAvailableNear(lat: Double, lng: Double, radiusMeters: Int): List<VehicleCandidate> = emptyList()
        override fun findAvailableNear(lat: Double, lng: Double, radiusMeters: Int, companyId: String?): List<VehicleCandidate> = emptyList()
        override fun updateLocation(vehicleId: String, lat: Double, lng: Double) {}
        override fun findVehicleIdByTripId(tripId: String): String? = tripDetailsMap[tripId]?.vehicleId
        override fun findTripDetails(tripId: String): TripDetails? = tripDetailsMap[tripId]
        override fun findVehicleLocationByTripId(tripId: String): VehicleLocation? = null
    }

    // ---------------------------------------------------------------------------
    // Ktor Test App Setup
    // ---------------------------------------------------------------------------

    private fun ApplicationTestBuilder.setupTestApp(
        tripExecutionService: TripExecutionService,
        tripService: TripService
    ) {
        install(ServerContentNegotiation) { json() }
        install(Authentication) {
            jwt("app-user") {
                verifier(
                    JWT.require(Algorithm.HMAC256(jwtSecret))
                        .withIssuer(jwtIssuer)
                        .build()
                )
                validate { credential ->
                    JWTPrincipal(credential.payload)
                }
            }
        }
        routing {
            tripRoutes(tripService, tripExecutionService)
        }
    }

    // ===========================================================================
    // Service-Level Tests
    // ===========================================================================

    @Test
    fun `1 - assignTrip transitions SCHEDULED to ASSIGNED and records vehicle and driver`() {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val service = TripExecutionService(tripRepo, stopRepo, rideRepo)

        val tripId = "trip-1"
        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), status = "SCHEDULED", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = "comp-1"

        val result = service.assignTrip(tripId, vehicleId = "veh-1", driverId = "drv-1", callerCompanyId = "comp-1")
        assertEquals(TripExecutionService.ExecutionResult.Success, result)

        val updated = tripRepo.findById(tripId)
        assertNotNull(updated)
        assertEquals("ASSIGNED", updated.status)
        assertEquals("veh-1", updated.vehicleId)
        assertEquals("drv-1", updated.driverId)
    }

    @Test
    fun `2 - startBoarding transitions ASSIGNED to BOARDING`() {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val service = TripExecutionService(tripRepo, stopRepo, rideRepo)

        val tripId = "trip-1"
        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), vehicleId = "veh-1", driverId = "drv-1", status = "ASSIGNED", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = "comp-1"

        val result = service.startBoarding(tripId, driverId = "drv-1", callerCompanyId = "comp-1")
        assertEquals(TripExecutionService.ExecutionResult.Success, result)

        val updated = tripRepo.findById(tripId)
        assertNotNull(updated)
        assertEquals("BOARDING", updated.status)
    }

    @Test
    fun `3 - processPickupStop boards passenger and advances trip to IN_PROGRESS`() {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val service = TripExecutionService(tripRepo, stopRepo, rideRepo)

        val tripId = "trip-1"
        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), vehicleId = "veh-1", driverId = "drv-1", status = "BOARDING", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = "comp-1"

        val rideId = "ride-1"
        rideRepo.requests[rideId] = StoredRideRequest(
            id = rideId, tripId = tripId, status = "PICKUP_PENDING", pickupLat = 12.97, pickupLng = 77.59
        )

        val stopId = "stop-1"
        stopRepo.create(
            StoredTripStop(
                id = stopId, tripId = tripId, rideRequestId = rideId, companyId = "comp-1",
                stopType = StopType.PICKUP, sequenceOrder = 1, latitude = 12.97, longitude = 77.59,
                status = StopStatus.PLANNED
            )
        )

        val result = service.processPickupStop(tripId, stopId, driverId = "drv-1", callerCompanyId = "comp-1")
        assertEquals(TripExecutionService.ExecutionResult.Success, result)

        assertEquals(StopStatus.COMPLETED, stopRepo.findById(stopId)?.status)
        assertEquals("ONBOARD", rideRepo.findById(rideId)?.status)
        assertEquals("IN_PROGRESS", tripRepo.findById(tripId)?.status)
    }

    @Test
    fun `4 - processNoShow marks stop SKIPPED and passenger NO_SHOW`() {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val service = TripExecutionService(tripRepo, stopRepo, rideRepo)

        val tripId = "trip-1"
        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), vehicleId = "veh-1", driverId = "drv-1", status = "BOARDING", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = "comp-1"

        val rideId = "ride-1"
        rideRepo.requests[rideId] = StoredRideRequest(
            id = rideId, tripId = tripId, status = "PICKUP_PENDING", pickupLat = 12.97, pickupLng = 77.59
        )

        val stopId = "stop-1"
        stopRepo.create(
            StoredTripStop(
                id = stopId, tripId = tripId, rideRequestId = rideId, companyId = "comp-1",
                stopType = StopType.PICKUP, sequenceOrder = 1, latitude = 12.97, longitude = 77.59,
                status = StopStatus.PLANNED
            )
        )

        val result = service.processNoShow(tripId, stopId, driverId = "drv-1", callerCompanyId = "comp-1")
        assertEquals(TripExecutionService.ExecutionResult.Success, result)

        assertEquals(StopStatus.SKIPPED, stopRepo.findById(stopId)?.status)
        assertEquals("NO_SHOW", rideRepo.findById(rideId)?.status)
    }

    @Test
    fun `5 - drop-off on PICKUP stop is rejected with InvalidTransition`() {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val service = TripExecutionService(tripRepo, stopRepo, rideRepo)

        val tripId = "trip-1"
        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), vehicleId = "veh-1", driverId = "drv-1", status = "IN_PROGRESS", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = "comp-1"

        val rideId = "ride-1"
        rideRepo.requests[rideId] = StoredRideRequest(
            id = rideId, tripId = tripId, status = "ONBOARD", pickupLat = 12.97, pickupLng = 77.59
        )

        val stopId = "stop-pickup-1"
        stopRepo.create(
            StoredTripStop(
                id = stopId, tripId = tripId, rideRequestId = rideId, companyId = "comp-1",
                stopType = StopType.PICKUP, sequenceOrder = 1, latitude = 12.97, longitude = 77.59,
                status = StopStatus.PLANNED
            )
        )

        val result = service.processDropoffStop(tripId, stopId, driverId = "drv-1", callerCompanyId = "comp-1")
        assertEquals(TripExecutionService.ExecutionResult.InvalidTransition, result)
    }

    @Test
    fun `6 - drop-off when passenger is NOT ONBOARD is rejected with InvalidTransition`() {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val service = TripExecutionService(tripRepo, stopRepo, rideRepo)

        val tripId = "trip-1"
        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), vehicleId = "veh-1", driverId = "drv-1", status = "IN_PROGRESS", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = "comp-1"

        val rideId = "ride-1"
        rideRepo.requests[rideId] = StoredRideRequest(
            id = rideId, tripId = tripId, status = "MATCHED", pickupLat = 12.97, pickupLng = 77.59
        )

        val stopId = "stop-dropoff-1"
        stopRepo.create(
            StoredTripStop(
                id = stopId, tripId = tripId, rideRequestId = rideId, companyId = "comp-1",
                stopType = StopType.DROPOFF, sequenceOrder = 2, latitude = 12.98, longitude = 77.60,
                status = StopStatus.PLANNED
            )
        )

        val result = service.processDropoffStop(tripId, stopId, driverId = "drv-1", callerCompanyId = "comp-1")
        assertEquals(TripExecutionService.ExecutionResult.InvalidTransition, result)
    }

    @Test
    fun `7 - duplicate drop-off on already completed stop is rejected with InvalidTransition`() {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val service = TripExecutionService(tripRepo, stopRepo, rideRepo)

        val tripId = "trip-1"
        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), vehicleId = "veh-1", driverId = "drv-1", status = "IN_PROGRESS", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = "comp-1"

        val rideId = "ride-1"
        rideRepo.requests[rideId] = StoredRideRequest(
            id = rideId, tripId = tripId, status = "ONBOARD", pickupLat = 12.97, pickupLng = 77.59
        )

        val stopId = "stop-dropoff-1"
        stopRepo.create(
            StoredTripStop(
                id = stopId, tripId = tripId, rideRequestId = rideId, companyId = "comp-1",
                stopType = StopType.DROPOFF, sequenceOrder = 2, latitude = 12.98, longitude = 77.60,
                status = StopStatus.COMPLETED // Already completed
            )
        )

        val result = service.processDropoffStop(tripId, stopId, driverId = "drv-1", callerCompanyId = "comp-1")
        assertEquals(TripExecutionService.ExecutionResult.InvalidTransition, result)
    }

    @Test
    fun `8 - drop-off on cross-trip or cross-tenant stop returns NotFound`() {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val service = TripExecutionService(tripRepo, stopRepo, rideRepo)

        val tripId = "trip-1"
        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), vehicleId = "veh-1", driverId = "drv-1", status = "IN_PROGRESS", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = "comp-1"

        val rideId = "ride-1"
        rideRepo.requests[rideId] = StoredRideRequest(
            id = rideId, tripId = "other-trip", status = "ONBOARD", pickupLat = 12.97, pickupLng = 77.59
        )

        val stopId = "stop-other-trip"
        stopRepo.create(
            StoredTripStop(
                id = stopId, tripId = "other-trip", rideRequestId = rideId, companyId = "comp-1",
                stopType = StopType.DROPOFF, sequenceOrder = 1, latitude = 12.98, longitude = 77.60,
                status = StopStatus.PLANNED
            )
        )

        // Cross-trip mismatch
        assertEquals(TripExecutionService.ExecutionResult.NotFound, service.processDropoffStop(tripId, stopId, "drv-1", "comp-1"))

        // Cross-tenant mismatch
        tripRepo.companyIds[tripId] = "comp-A"
        stopRepo.stops[stopId] = stopRepo.stops[stopId]!!.copy(tripId = tripId, companyId = "comp-B")
        assertEquals(TripExecutionService.ExecutionResult.NotFound, service.processDropoffStop(tripId, stopId, "drv-1", "comp-A"))
    }

    @Test
    fun `9 - drop-off does NOT complete trip when ONBOARD passengers remain`() {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val service = TripExecutionService(tripRepo, stopRepo, rideRepo)

        val tripId = "trip-1"
        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), vehicleId = "veh-1", driverId = "drv-1", status = "IN_PROGRESS", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = "comp-1"

        // Passenger 1 (being dropped off)
        val ride1 = "ride-1"
        rideRepo.requests[ride1] = StoredRideRequest(id = ride1, tripId = tripId, status = "ONBOARD", pickupLat = 12.97, pickupLng = 77.59)
        val stop1 = "stop-drop-1"
        stopRepo.create(StoredTripStop(id = stop1, tripId = tripId, rideRequestId = ride1, companyId = "comp-1", stopType = StopType.DROPOFF, sequenceOrder = 1, latitude = 12.98, longitude = 77.60, status = StopStatus.PLANNED))

        // Passenger 2 (still ONBOARD!)
        val ride2 = "ride-2"
        rideRepo.requests[ride2] = StoredRideRequest(id = ride2, tripId = tripId, status = "ONBOARD", pickupLat = 12.97, pickupLng = 77.59)
        val stop2 = "stop-drop-2"
        stopRepo.create(StoredTripStop(id = stop2, tripId = tripId, rideRequestId = ride2, companyId = "comp-1", stopType = StopType.DROPOFF, sequenceOrder = 2, latitude = 12.99, longitude = 77.61, status = StopStatus.PLANNED))

        val result = service.processDropoffStop(tripId, stop1, "drv-1", "comp-1")
        assertEquals(TripExecutionService.ExecutionResult.Success, result)

        assertEquals(StopStatus.COMPLETED, stopRepo.findById(stop1)?.status)
        assertEquals("COMPLETED", rideRepo.findById(ride1)?.status)

        // Trip must NOT be completed! Passenger 2 is still onboard
        assertEquals("IN_PROGRESS", tripRepo.findById(tripId)?.status)
    }

    @Test
    fun `10 - final drop-off completes trip when no passengers or stops remain`() {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val service = TripExecutionService(tripRepo, stopRepo, rideRepo)

        val tripId = "trip-1"
        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), vehicleId = "veh-1", driverId = "drv-1", status = "IN_PROGRESS", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = "comp-1"

        val ride1 = "ride-1"
        rideRepo.requests[ride1] = StoredRideRequest(id = ride1, tripId = tripId, status = "ONBOARD", pickupLat = 12.97, pickupLng = 77.59)
        val stop1 = "stop-drop-1"
        stopRepo.create(StoredTripStop(id = stop1, tripId = tripId, rideRequestId = ride1, companyId = "comp-1", stopType = StopType.DROPOFF, sequenceOrder = 1, latitude = 12.98, longitude = 77.60, status = StopStatus.PLANNED))

        val result = service.processDropoffStop(tripId, stop1, "drv-1", "comp-1")
        assertEquals(TripExecutionService.ExecutionResult.Success, result)

        assertEquals(StopStatus.COMPLETED, stopRepo.findById(stop1)?.status)
        assertEquals("COMPLETED", rideRepo.findById(ride1)?.status)

        // All stops and passengers are finished -> Trip is COMPLETED!
        assertEquals("COMPLETED", tripRepo.findById(tripId)?.status)
    }

    @Test
    fun `11 - concurrent drop-offs on the same stop - exactly one succeeds`() {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val service = TripExecutionService(tripRepo, stopRepo, rideRepo)

        val tripId = "trip-1"
        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), vehicleId = "veh-1", driverId = "drv-1", status = "IN_PROGRESS", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = "comp-1"

        val ride1 = "ride-1"
        rideRepo.requests[ride1] = StoredRideRequest(id = ride1, tripId = tripId, status = "ONBOARD", pickupLat = 12.97, pickupLng = 77.59)
        val stop1 = "stop-drop-1"
        stopRepo.create(StoredTripStop(id = stop1, tripId = tripId, rideRequestId = ride1, companyId = "comp-1", stopType = StopType.DROPOFF, sequenceOrder = 1, latitude = 12.98, longitude = 77.60, status = StopStatus.PLANNED))

        val threadCount = 2
        val barrier = CyclicBarrier(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val successes = AtomicInteger(0)
        val failures = AtomicInteger(0)

        for (i in 0 until threadCount) {
            executor.submit {
                barrier.await()
                val result = service.processDropoffStop(tripId, stop1, "drv-1", "comp-1")
                if (result == TripExecutionService.ExecutionResult.Success) {
                    successes.incrementAndGet()
                } else if (result == TripExecutionService.ExecutionResult.InvalidTransition) {
                    failures.incrementAndGet()
                }
            }
        }

        executor.shutdown()
        while (!executor.isTerminated) {
            Thread.sleep(10)
        }

        assertEquals(1, successes.get(), "Exactly one drop-off transition must succeed")
        assertEquals(1, failures.get(), "The losing concurrent drop-off must receive InvalidTransition")
    }

    // ===========================================================================
    // Ktor Endpoint Tests
    // ===========================================================================

    @Test
    fun `12 - POST dropoff endpoint succeeds with 200 and StopActionResponse`() = testApplication {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val vehicleRepo = FakeVehicleRepository()

        val optimizer = object : RouteOptimizer {
            override suspend fun optimize(vehicleLat: Double, vehicleLng: Double, stops: List<StopCandidate>): List<OptimizedStop> = emptyList()
        }
        val tripService = TripService(rideRepo, vehicleRepo, optimizer)
        val tripExecService = TripExecutionService(tripRepo, stopRepo, rideRepo, vehicleRepo)

        val tripId = "trip-100"
        val companyId = "comp-100"
        val driverId = "driver-100"

        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), vehicleId = "veh-1", driverId = driverId, status = "IN_PROGRESS", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = companyId
        vehicleRepo.tripDetailsMap[tripId] = TripDetails(tripId, "veh-1", driverId, companyId, "IN_PROGRESS")

        val rideId = "ride-100"
        rideRepo.requests[rideId] = StoredRideRequest(id = rideId, tripId = tripId, status = "ONBOARD", pickupLat = 12.97, pickupLng = 77.59)

        val stopId = "stop-drop-100"
        stopRepo.create(StoredTripStop(id = stopId, tripId = tripId, rideRequestId = rideId, companyId = companyId, stopType = StopType.DROPOFF, sequenceOrder = 1, latitude = 12.98, longitude = 77.60, status = StopStatus.PLANNED))

        setupTestApp(tripExecService, tripService)

        val token = generateToken(userId = driverId, role = "driver", companyId = companyId)
        val response = client.post("/trips/$tripId/stops/$stopId/dropoff") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<StopActionResponse>(response.bodyAsText())
        assertEquals(stopId, body.stopId)
        assertEquals("COMPLETED", body.status)
    }

    @Test
    fun `13 - POST dropoff endpoint rejects non-driver callers with 403`() = testApplication {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val vehicleRepo = FakeVehicleRepository()

        val optimizer = object : RouteOptimizer {
            override suspend fun optimize(vehicleLat: Double, vehicleLng: Double, stops: List<StopCandidate>): List<OptimizedStop> = emptyList()
        }
        val tripService = TripService(rideRepo, vehicleRepo, optimizer)
        val tripExecService = TripExecutionService(tripRepo, stopRepo, rideRepo, vehicleRepo)

        setupTestApp(tripExecService, tripService)

        val token = generateToken(userId = "emp-1", role = "employee", companyId = "comp-1")
        val response = client.post("/trips/trip-1/stops/stop-1/dropoff") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `14 - POST dropoff endpoint rejects drop-off on pickup stop with 422`() = testApplication {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val vehicleRepo = FakeVehicleRepository()

        val optimizer = object : RouteOptimizer {
            override suspend fun optimize(vehicleLat: Double, vehicleLng: Double, stops: List<StopCandidate>): List<OptimizedStop> = emptyList()
        }
        val tripService = TripService(rideRepo, vehicleRepo, optimizer)
        val tripExecService = TripExecutionService(tripRepo, stopRepo, rideRepo, vehicleRepo)

        val tripId = "trip-200"
        val companyId = "comp-200"
        val driverId = "driver-200"

        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), vehicleId = "veh-1", driverId = driverId, status = "IN_PROGRESS", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = companyId

        val rideId = "ride-200"
        rideRepo.requests[rideId] = StoredRideRequest(id = rideId, tripId = tripId, status = "ONBOARD", pickupLat = 12.97, pickupLng = 77.59)

        val stopId = "stop-pickup-200"
        stopRepo.create(StoredTripStop(id = stopId, tripId = tripId, rideRequestId = rideId, companyId = companyId, stopType = StopType.PICKUP, sequenceOrder = 1, latitude = 12.97, longitude = 77.59, status = StopStatus.PLANNED))

        setupTestApp(tripExecService, tripService)

        val token = generateToken(userId = driverId, role = "driver", companyId = companyId)
        val response = client.post("/trips/$tripId/stops/$stopId/dropoff") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test
    fun `15 - POST assign endpoint transitions SCHEDULED trip to ASSIGNED`() = testApplication {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val vehicleRepo = FakeVehicleRepository()

        val optimizer = object : RouteOptimizer {
            override suspend fun optimize(vehicleLat: Double, vehicleLng: Double, stops: List<StopCandidate>): List<OptimizedStop> = emptyList()
        }
        val tripService = TripService(rideRepo, vehicleRepo, optimizer)
        val tripExecService = TripExecutionService(tripRepo, stopRepo, rideRepo, vehicleRepo)

        val tripId = "trip-300"
        val companyId = "comp-300"
        val driverId = "driver-300"

        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), status = "SCHEDULED", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = companyId

        setupTestApp(tripExecService, tripService)

        val token = generateToken(userId = driverId, role = "driver", companyId = companyId)
        val response = client.post("/trips/$tripId/assign?vehicleId=veh-300&driverId=$driverId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ASSIGNED", tripRepo.findById(tripId)?.status)
    }

    @Test
    fun `16 - POST boarding endpoint transitions ASSIGNED trip to BOARDING`() = testApplication {
        val tripRepo = InMemoryTripRepository()
        val rideRepo = InMemoryRideRequestRepository()
        val stopRepo = InMemoryTripStopPersistenceRepository(rideRepo)
        val vehicleRepo = FakeVehicleRepository()

        val optimizer = object : RouteOptimizer {
            override suspend fun optimize(vehicleLat: Double, vehicleLng: Double, stops: List<StopCandidate>): List<OptimizedStop> = emptyList()
        }
        val tripService = TripService(rideRepo, vehicleRepo, optimizer)
        val tripExecService = TripExecutionService(tripRepo, stopRepo, rideRepo, vehicleRepo)

        val tripId = "trip-400"
        val companyId = "comp-400"
        val driverId = "driver-400"

        tripRepo.create(
            Trip(id = tripId, scheduleId = "sched-1", tripDate = LocalDate.now(), vehicleId = "veh-400", driverId = driverId, status = "ASSIGNED", startTime = Instant.now())
        )
        tripRepo.companyIds[tripId] = companyId

        setupTestApp(tripExecService, tripService)

        val token = generateToken(userId = driverId, role = "driver", companyId = companyId)
        val response = client.post("/trips/$tripId/boarding") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("BOARDING", tripRepo.findById(tripId)?.status)
    }
}
