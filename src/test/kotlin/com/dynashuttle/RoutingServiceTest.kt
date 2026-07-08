package com.dynashuttle.services

import com.dynashuttle.models.RideRequestDto
import com.dynashuttle.repositories.RideRequestRepository
import com.dynashuttle.repositories.StoredRideRequest
import com.dynashuttle.repositories.VehicleCandidate
import com.dynashuttle.repositories.VehicleRepository
import com.dynashuttle.services.routing.OptimizedStop
import com.dynashuttle.services.routing.RouteOptimizer
import com.dynashuttle.services.routing.StopCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hand-written fakes for the three interfaces RoutingService depends on.
 * No mocking library needed at this scale (per the project's testing notes).
 */

private class FakeVehicleRepository(
    private val vehiclesToReturn: List<VehicleCandidate>
) : VehicleRepository {
    var lastLat: Double? = null
    var lastLng: Double? = null
    var lastRadiusMeters: Int? = null
    val locationUpdates = mutableListOf<LocationUpdate>()

    data class LocationUpdate(val vehicleId: String, val lat: Double, val lng: Double)

    override fun findAvailableNear(lat: Double, lng: Double, radiusMeters: Int): List<VehicleCandidate> {
        lastLat = lat
        lastLng = lng
        lastRadiusMeters = radiusMeters
        return vehiclesToReturn
    }

    override fun updateLocation(vehicleId: String, lat: Double, lng: Double) {
        locationUpdates.add(LocationUpdate(vehicleId, lat, lng))
    }

    override fun findVehicleIdByTripId(tripId: String): String? = null
}

private class FakeRouteOptimizer(
    private val etaMinutesToReturn: Int = 8
) : RouteOptimizer {
    var lastVehicleLat: Double? = null
    var lastVehicleLng: Double? = null
    var lastStops: List<StopCandidate>? = null

    override fun optimize(vehicleLat: Double, vehicleLng: Double, stops: List<StopCandidate>): List<OptimizedStop> {
        lastVehicleLat = vehicleLat
        lastVehicleLng = vehicleLng
        lastStops = stops
        return stops.mapIndexed { index, stop ->
            OptimizedStop(
                rideRequestId = stop.rideRequestId,
                lat = stop.lat,
                lng = stop.lng,
                sequenceOrder = index + 1,
                etaMinutes = etaMinutesToReturn
            )
        }
    }
}

private class FakeRideRequestRepository : RideRequestRepository {
    val savedRequests = mutableListOf<StoredRideRequest>()
    private var nextId = 1

    override fun save(request: RideRequestDto, tripId: String?, status: String): StoredRideRequest {
        val stored = StoredRideRequest(
            id = "ride-${nextId++}",
            tripId = tripId,
            status = status,
            pickupLat = request.pickupLat,
            pickupLng = request.pickupLng
        )
        savedRequests.add(stored)
        return stored
    }

    override fun findById(id: String): StoredRideRequest? = savedRequests.find { it.id == id }

    override fun updateStatus(id: String, status: String, tripId: String?) {
        val index = savedRequests.indexOfFirst { it.id == id }
        if (index >= 0) {
            val current = savedRequests[index]
            savedRequests[index] = current.copy(
                status = status,
                tripId = tripId ?: current.tripId
            )
        }
    }
}

class RoutingServiceTest {

    private val sampleRequest = RideRequestDto(
        employeeId = "emp-1",
        pickupLat = 12.9716,
        pickupLng = 77.5946,
        destinationLat = 12.9900,
        destinationLng = 77.6100
    )

    // ---- Queued path (no vehicle available) ----

    @Test
    fun `returns QUEUED when no vehicle is available nearby`() {
        val vehicleRepo = FakeVehicleRepository(vehiclesToReturn = emptyList())
        val service = RoutingService(vehicleRepo, FakeRouteOptimizer(), FakeRideRequestRepository())

        val response = service.matchRideRequest(sampleRequest)

        assertEquals("QUEUED", response.status)
        assertNull(response.assignedVehicleId)
        assertNull(response.etaMinutes)
    }

    @Test
    fun `persists the ride request as QUEUED with no tripId when unmatched`() {
        val vehicleRepo = FakeVehicleRepository(vehiclesToReturn = emptyList())
        val rideRepo = FakeRideRequestRepository()
        val service = RoutingService(vehicleRepo, FakeRouteOptimizer(), rideRepo)

        service.matchRideRequest(sampleRequest)

        assertEquals(1, rideRepo.savedRequests.size)
        assertEquals("QUEUED", rideRepo.savedRequests[0].status)
        assertNull(rideRepo.savedRequests[0].tripId)
    }

    @Test
    fun `queued path never calls the route optimizer`() {
        val vehicleRepo = FakeVehicleRepository(vehiclesToReturn = emptyList())
        val optimizer = FakeRouteOptimizer()
        val service = RoutingService(vehicleRepo, optimizer, FakeRideRequestRepository())

        service.matchRideRequest(sampleRequest)

        assertNull(optimizer.lastStops, "Optimizer should not be invoked when no vehicle is available")
    }

    // ---- Matched path (vehicle available) ----

    @Test
    fun `returns MATCHED with assigned vehicle and ETA when a vehicle is available`() {
        val vehicle = VehicleCandidate(
            vehicleId = "veh-1",
            tripId = "trip-1",
            driverId = "drv-1",
            currentLat = 12.9700,
            currentLng = 77.5900,
            availableSeats = 3
        )
        val vehicleRepo = FakeVehicleRepository(vehiclesToReturn = listOf(vehicle))
        val optimizer = FakeRouteOptimizer(etaMinutesToReturn = 12)
        val service = RoutingService(vehicleRepo, optimizer, FakeRideRequestRepository())

        val response = service.matchRideRequest(sampleRequest)

        assertEquals("MATCHED", response.status)
        assertEquals("veh-1", response.assignedVehicleId)
        assertEquals(12, response.etaMinutes)
    }

    @Test
    fun `persists the ride request as MATCHED with the assigned tripId`() {
        val vehicle = VehicleCandidate(
            vehicleId = "veh-1",
            tripId = "trip-1",
            driverId = "drv-1",
            currentLat = 12.9700,
            currentLng = 77.5900,
            availableSeats = 2
        )
        val vehicleRepo = FakeVehicleRepository(vehiclesToReturn = listOf(vehicle))
        val rideRepo = FakeRideRequestRepository()
        val service = RoutingService(vehicleRepo, FakeRouteOptimizer(), rideRepo)

        service.matchRideRequest(sampleRequest)

        assertEquals(1, rideRepo.savedRequests.size)
        assertEquals("MATCHED", rideRepo.savedRequests[0].status)
        assertEquals("trip-1", rideRepo.savedRequests[0].tripId)
    }

    @Test
    fun `picks the first candidate vehicle when multiple are available`() {
        val first = VehicleCandidate("veh-1", "trip-1", "drv-1", 12.9700, 77.5900, 3)
        val second = VehicleCandidate("veh-2", "trip-2", "drv-2", 12.9750, 77.5950, 4)
        val vehicleRepo = FakeVehicleRepository(vehiclesToReturn = listOf(first, second))
        val service = RoutingService(vehicleRepo, FakeRouteOptimizer(), FakeRideRequestRepository())

        val response = service.matchRideRequest(sampleRequest)

        assertEquals("veh-1", response.assignedVehicleId)
    }

    @Test
    fun `optimizer is invoked with the matched vehicle's current position and the pickup as the only stop`() {
        val vehicle = VehicleCandidate(
            vehicleId = "veh-1",
            tripId = "trip-1",
            driverId = "drv-1",
            currentLat = 12.9000,
            currentLng = 77.5000,
            availableSeats = 1
        )
        val vehicleRepo = FakeVehicleRepository(vehiclesToReturn = listOf(vehicle))
        val optimizer = FakeRouteOptimizer()
        val service = RoutingService(vehicleRepo, optimizer, FakeRideRequestRepository())

        service.matchRideRequest(sampleRequest)

        assertEquals(12.9000, optimizer.lastVehicleLat)
        assertEquals(77.5000, optimizer.lastVehicleLng)
        assertEquals(1, optimizer.lastStops?.size)
        assertEquals(sampleRequest.pickupLat, optimizer.lastStops?.first()?.lat)
        assertEquals(sampleRequest.pickupLng, optimizer.lastStops?.first()?.lng)
    }

    // ---- Search radius wiring ----

    @Test
    fun `passes the configured search radius and pickup coordinates to the vehicle repository`() {
        val vehicleRepo = FakeVehicleRepository(vehiclesToReturn = emptyList())
        val service = RoutingService(
            vehicleRepository = vehicleRepo,
            routeOptimizer = FakeRouteOptimizer(),
            rideRequestRepository = FakeRideRequestRepository(),
            searchRadiusMeters = 1500
        )

        service.matchRideRequest(sampleRequest)

        assertEquals(sampleRequest.pickupLat, vehicleRepo.lastLat)
        assertEquals(sampleRequest.pickupLng, vehicleRepo.lastLng)
        assertEquals(1500, vehicleRepo.lastRadiusMeters)
    }

    @Test
    fun `defaults to a 2000 meter search radius when not overridden`() {
        val vehicleRepo = FakeVehicleRepository(vehiclesToReturn = emptyList())
        val service = RoutingService(vehicleRepo, FakeRouteOptimizer(), FakeRideRequestRepository())

        service.matchRideRequest(sampleRequest)

        assertEquals(2000, vehicleRepo.lastRadiusMeters)
    }

    // ---- getRideStatus ----

    @Test
    fun `getRideStatus returns null for an unknown ride request id`() {
        val service = RoutingService(
            FakeVehicleRepository(emptyList()),
            FakeRouteOptimizer(),
            FakeRideRequestRepository()
        )

        assertNull(service.getRideStatus("does-not-exist"))
    }

    @Test
    fun `getRideStatus returns the stored status for a known ride request`() {
        val rideRepo = FakeRideRequestRepository()
        val service = RoutingService(FakeVehicleRepository(emptyList()), FakeRouteOptimizer(), rideRepo)

        val matchResponse = service.matchRideRequest(sampleRequest) // persisted as QUEUED

        val status = service.getRideStatus(matchResponse.rideRequestId)

        assertNotNull(status)
        assertEquals(matchResponse.rideRequestId, status.rideRequestId)
        assertEquals("QUEUED", status.status)
    }
}
