package com.dynashuttle

import com.dynashuttle.models.RideRequestDto
import com.dynashuttle.repositories.ActiveTripStop
import com.dynashuttle.repositories.AllocationResult
import com.dynashuttle.repositories.RideRequestRepository
import com.dynashuttle.repositories.StoredRideRequest
import com.dynashuttle.repositories.VehicleCandidate
import com.dynashuttle.repositories.VehicleLocation
import com.dynashuttle.repositories.VehicleRepository
import com.dynashuttle.services.RoutingService
import com.dynashuttle.services.routing.OptimizedStop
import com.dynashuttle.services.routing.RouteOptimizer
import com.dynashuttle.services.routing.StopCandidate
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------

/**
 * A capacity-aware fake that tracks allocated seat count per trip and enforces
 * the same contract as [PostgresRideRequestRepository.allocateSeat]: atomic
 * check-then-insert under a single lock, returning [AllocationResult.CapacityExhausted]
 * once the seat ceiling is hit.
 *
 * Thread safety: [allocateSeat] and [save] are both @Synchronized so the
 * barrier-based concurrency tests (Test G, Test H) exercise the fallback /
 * exhaustion path correctly even with a fake.
 */
private class CapacityAwareFakeRideRepo(
    private val tripSeats: Map<String, Int>  // tripId -> max seats
) : RideRequestRepository {

    private val store = mutableListOf<StoredRideRequest>()
    private val seatCountPerTrip = mutableMapOf<String, AtomicInteger>()
    private val nextId = AtomicInteger(1)

    fun savedRequests(): List<StoredRideRequest> = synchronized(store) { store.toList() }
    fun allocatedCountFor(tripId: String): Int = seatCountPerTrip[tripId]?.get() ?: 0

    @Synchronized
    override fun allocateSeat(request: RideRequestDto, tripId: String, companyId: String?): AllocationResult {
        val capacity = tripSeats[tripId] ?: return AllocationResult.TripNotFound
        val counter = seatCountPerTrip.getOrPut(tripId) { AtomicInteger(0) }
        if (counter.get() >= capacity) {
            return AllocationResult.CapacityExhausted
        }
        counter.incrementAndGet()
        val stored = StoredRideRequest(
            id = "ride-${nextId.getAndIncrement()}",
            tripId = tripId,
            status = "MATCHED",
            pickupLat = request.pickupLat,
            pickupLng = request.pickupLng,
            employeeId = request.employeeId,
            companyId = companyId
        )
        synchronized(store) { store.add(stored) }
        return AllocationResult.Allocated(stored)
    }

    @Synchronized
    override fun save(request: RideRequestDto, tripId: String?, status: String): StoredRideRequest {
        val stored = StoredRideRequest(
            id = "ride-${nextId.getAndIncrement()}",
            tripId = tripId,
            status = status,
            pickupLat = request.pickupLat,
            pickupLng = request.pickupLng,
            employeeId = request.employeeId
        )
        synchronized(store) { store.add(stored) }
        return stored
    }

    override fun findById(id: String): StoredRideRequest? =
        synchronized(store) { store.find { it.id == id } }

    override fun updateStatus(id: String, status: String, tripId: String?) {
        synchronized(store) {
            val idx = store.indexOfFirst { it.id == id }
            if (idx >= 0) store[idx] = store[idx].copy(status = status, tripId = tripId ?: store[idx].tripId)
        }
    }

    override fun findActiveByTripId(tripId: String): List<ActiveTripStop> = emptyList()
    override fun countActiveRiders(tripId: String): Int = allocatedCountFor(tripId)
}

/** Fake vehicle repo that filters candidates by companyId when provided. */
private class CapacityAwareFakeVehicleRepo(
    private val candidates: List<VehicleCandidate>
) : VehicleRepository {
    override fun findAvailableNear(lat: Double, lng: Double, radiusMeters: Int): List<VehicleCandidate> = candidates
    override fun findAvailableNear(lat: Double, lng: Double, radiusMeters: Int, companyId: String?): List<VehicleCandidate> =
        if (companyId == null) candidates
        else candidates.filter { it.companyId == null || it.companyId == companyId }

    override fun updateLocation(vehicleId: String, lat: Double, lng: Double) {}
    override fun findVehicleIdByTripId(tripId: String): String? = null
    override fun findVehicleLocationByTripId(tripId: String): VehicleLocation? = null
}

private object InstantOptimizer : RouteOptimizer {
    override suspend fun optimize(vehicleLat: Double, vehicleLng: Double, stops: List<StopCandidate>): List<OptimizedStop> =
        stops.mapIndexed { i, s -> OptimizedStop(s.rideRequestId, s.lat, s.lng, i + 1, 5) }
}

private fun sampleRequest(n: Int = 1) = RideRequestDto(
    employeeId = "emp-$n",
    pickupLat = 12.9716,
    pickupLng = 77.5946,
    destinationLat = 12.9900,
    destinationLng = 77.6100
)

private fun candidate(tripId: String, seats: Int, companyId: String? = null) =
    VehicleCandidate("veh-$tripId", tripId, "drv-$tripId", 12.97, 77.59, seats, companyId)

// ---------------------------------------------------------------------------
// Tests A–L: Capacity & Concurrency Contract
// ---------------------------------------------------------------------------

class CapacityConcurrencyTest {

    // -----------------------------------------------------------------------
    // Test A — Single seat: first request matches, second exhausts capacity
    // -----------------------------------------------------------------------

    @Test
    fun `A - single seat trip matches first request and rejects second via CapacityExhausted`() = runBlocking {
        val tripId = "trip-A"
        val rideRepo = CapacityAwareFakeRideRepo(mapOf(tripId to 1))
        val vehicleRepo = CapacityAwareFakeVehicleRepo(listOf(candidate(tripId, 1)))
        val service = RoutingService(vehicleRepo, InstantOptimizer, rideRepo)

        val r1 = service.matchRideRequest(sampleRequest(1))
        val r2 = service.matchRideRequest(sampleRequest(2))

        assertEquals("MATCHED", r1.status, "First request must be matched")
        assertEquals("WAITLISTED", r2.status, "Second request must be waitlisted when capacity is exhausted")
        assertEquals(1, rideRepo.allocatedCountFor(tripId), "Exactly 1 seat should be allocated")
    }

    // -----------------------------------------------------------------------
    // Test B — All seats filled: N requests on an N-seat vehicle
    // -----------------------------------------------------------------------

    @Test
    fun `B - exactly N requests fill an N-seat vehicle with no overflow`() = runBlocking {
        val capacity = 4
        val tripId = "trip-B"
        val rideRepo = CapacityAwareFakeRideRepo(mapOf(tripId to capacity))
        val vehicleRepo = CapacityAwareFakeVehicleRepo(listOf(candidate(tripId, capacity)))
        val service = RoutingService(vehicleRepo, InstantOptimizer, rideRepo)

        repeat(capacity) { i ->
            val response = service.matchRideRequest(sampleRequest(i + 1))
            assertEquals("MATCHED", response.status, "Request ${i + 1} should be matched")
        }
        assertEquals(capacity, rideRepo.allocatedCountFor(tripId), "All $capacity seats should be allocated")
    }

    // -----------------------------------------------------------------------
    // Test C — Overflow: N+1 requests on an N-seat vehicle
    // -----------------------------------------------------------------------

    @Test
    fun `C - N+1 requests on N-seat vehicle result in exactly N MATCHED and 1 WAITLISTED`() = runBlocking {
        val capacity = 3
        val tripId = "trip-C"
        val rideRepo = CapacityAwareFakeRideRepo(mapOf(tripId to capacity))
        val vehicleRepo = CapacityAwareFakeVehicleRepo(listOf(candidate(tripId, capacity)))
        val service = RoutingService(vehicleRepo, InstantOptimizer, rideRepo)

        val results = (1..(capacity + 1)).map { i -> service.matchRideRequest(sampleRequest(i)) }

        assertEquals(capacity, results.count { it.status == "MATCHED" }, "Exactly $capacity requests should be matched")
        assertEquals(1, results.count { it.status == "WAITLISTED" }, "Exactly 1 request should be waitlisted")
        assertEquals(capacity, rideRepo.allocatedCountFor(tripId), "Seat count must not exceed capacity")
    }

    // -----------------------------------------------------------------------
    // Test D — Capacity=0 vehicle: all requests go to WAITLISTED
    // -----------------------------------------------------------------------

    @Test
    fun `D - zero-capacity trip waitlists all requests immediately`() = runBlocking {
        val tripId = "trip-D"
        val rideRepo = CapacityAwareFakeRideRepo(mapOf(tripId to 0))
        val vehicleRepo = CapacityAwareFakeVehicleRepo(listOf(candidate(tripId, 0)))
        val service = RoutingService(vehicleRepo, InstantOptimizer, rideRepo)

        repeat(3) { i ->
            val result = service.matchRideRequest(sampleRequest(i + 1))
            assertEquals("WAITLISTED", result.status, "Request ${i + 1} must be waitlisted on a zero-capacity vehicle")
        }
        assertEquals(0, rideRepo.allocatedCountFor(tripId))
    }

    // -----------------------------------------------------------------------
    // Test E — Multi-candidate fallback: first trip full, second has seats
    // -----------------------------------------------------------------------

    @Test
    fun `E - falls through to second candidate when first trip is at capacity`() = runBlocking {
        val trip1 = "trip-E1"
        val trip2 = "trip-E2"
        val rideRepo = CapacityAwareFakeRideRepo(mapOf(trip1 to 1, trip2 to 3))
        val vehicleRepo = CapacityAwareFakeVehicleRepo(listOf(candidate(trip1, 1), candidate(trip2, 3)))
        val service = RoutingService(vehicleRepo, InstantOptimizer, rideRepo)

        val r1 = service.matchRideRequest(sampleRequest(1))
        assertEquals("MATCHED", r1.status)
        assertEquals("veh-$trip1", r1.assignedVehicleId)

        // First trip is now full; second request should fall through to trip2
        val r2 = service.matchRideRequest(sampleRequest(2))
        assertEquals("MATCHED", r2.status)
        assertEquals("veh-$trip2", r2.assignedVehicleId, "Should fall through to second candidate")
    }

    // -----------------------------------------------------------------------
    // Test F — All candidates exhausted: falls back to WAITLISTED
    // -----------------------------------------------------------------------

    @Test
    fun `F - waitlists request when all candidate trips are at capacity`() = runBlocking {
        val trip1 = "trip-F1"
        val trip2 = "trip-F2"
        val rideRepo = CapacityAwareFakeRideRepo(mapOf(trip1 to 0, trip2 to 0))
        val vehicleRepo = CapacityAwareFakeVehicleRepo(listOf(candidate(trip1, 0), candidate(trip2, 0)))
        val service = RoutingService(vehicleRepo, InstantOptimizer, rideRepo)

        val result = service.matchRideRequest(sampleRequest(1))
        assertEquals("WAITLISTED", result.status, "Must be waitlisted when all candidates are full")
    }

    // -----------------------------------------------------------------------
    // Test G — Concurrent allocation: barrier-synchronized threads contend on
    //           a single-seat trip; exactly 1 must succeed, rest must be waitlisted.
    // -----------------------------------------------------------------------

    @Test
    fun `G - concurrent requests on single-seat trip - exactly 1 matched, rest waitlisted`() {
        val capacity = 1
        val tripId = "trip-G"
        val threads = 8
        val rideRepo = CapacityAwareFakeRideRepo(mapOf(tripId to capacity))
        val vehicleRepo = CapacityAwareFakeVehicleRepo(listOf(candidate(tripId, capacity)))
        val service = RoutingService(vehicleRepo, InstantOptimizer, rideRepo)

        val barrier = CyclicBarrier(threads)
        val matchedCount = AtomicInteger(0)
        val waitlistedCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threads)

        repeat(threads) { i ->
            executor.submit {
                barrier.await()   // All threads release at exactly the same moment
                val result = runBlocking { service.matchRideRequest(sampleRequest(i + 1)) }
                when (result.status) {
                    "MATCHED"    -> matchedCount.incrementAndGet()
                    "WAITLISTED" -> waitlistedCount.incrementAndGet()
                }
            }
        }

        executor.shutdown()
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(capacity, matchedCount.get(),
            "Exactly $capacity request(s) should be matched under contention")
        assertEquals(threads - capacity, waitlistedCount.get(),
            "${threads - capacity} requests should be waitlisted when capacity is exhausted")
        assertEquals(capacity, rideRepo.allocatedCountFor(tripId),
            "Seat counter must not exceed capacity under concurrent load")
    }

    // -----------------------------------------------------------------------
    // Test H — Concurrent allocation on N-seat trip: exactly N matched, rest waitlisted
    // -----------------------------------------------------------------------

    @Test
    fun `H - concurrent requests on N-seat trip - exactly N matched under contention`() {
        val capacity = 4
        val threads = 12
        val tripId = "trip-H"
        val rideRepo = CapacityAwareFakeRideRepo(mapOf(tripId to capacity))
        val vehicleRepo = CapacityAwareFakeVehicleRepo(listOf(candidate(tripId, capacity)))
        val service = RoutingService(vehicleRepo, InstantOptimizer, rideRepo)

        val barrier = CyclicBarrier(threads)
        val matchedCount = AtomicInteger(0)
        val waitlistedCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threads)

        repeat(threads) { i ->
            executor.submit {
                barrier.await()
                val result = runBlocking { service.matchRideRequest(sampleRequest(i + 1)) }
                when (result.status) {
                    "MATCHED"    -> matchedCount.incrementAndGet()
                    "WAITLISTED" -> waitlistedCount.incrementAndGet()
                }
            }
        }

        executor.shutdown()
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(capacity, matchedCount.get(),
            "Exactly $capacity seats should be allocated under $threads concurrent requests")
        assertEquals(threads - capacity, waitlistedCount.get(),
            "${threads - capacity} excess requests must be waitlisted")
        assertTrue(rideRepo.allocatedCountFor(tripId) <= capacity,
            "Allocated count must never exceed seat capacity (overbooking prevented)")
    }

    // -----------------------------------------------------------------------
    // Test I — Company isolation: cross-company request cannot take seats on
    //           a trip belonging to a different company
    // -----------------------------------------------------------------------

    @Test
    fun `I - cross-company request is rejected and falls back to WAITLISTED`() = runBlocking {
        val tripId = "trip-I"
        val rideRepo = CapacityAwareFakeRideRepo(mapOf(tripId to 5))
        // Vehicle belongs to company-A; RoutingService filters by companyId
        val vehicleRepo = CapacityAwareFakeVehicleRepo(listOf(candidate(tripId, 5, companyId = "company-A")))
        val service = RoutingService(vehicleRepo, InstantOptimizer, rideRepo)

        // Request from company-B — the fake vehicle repo will filter it out
        val result = service.matchRideRequest(sampleRequest(1), companyId = "company-B")

        assertEquals("WAITLISTED", result.status,
            "Cross-company request must not be matched against a different company's trip")
        assertEquals(0, rideRepo.allocatedCountFor(tripId),
            "No seats should be allocated on the foreign trip")
    }

    // -----------------------------------------------------------------------
    // Test J — Seat count accuracy: countActiveRiders reflects allocation state
    // -----------------------------------------------------------------------

    @Test
    fun `J - countActiveRiders accurately tracks allocated seats`() = runBlocking {
        val tripId = "trip-J"
        val capacity = 5
        val rideRepo = CapacityAwareFakeRideRepo(mapOf(tripId to capacity))
        val vehicleRepo = CapacityAwareFakeVehicleRepo(listOf(candidate(tripId, capacity)))
        val service = RoutingService(vehicleRepo, InstantOptimizer, rideRepo)

        assertEquals(0, rideRepo.countActiveRiders(tripId), "Should start at 0")

        repeat(3) { i -> service.matchRideRequest(sampleRequest(i + 1)) }
        assertEquals(3, rideRepo.countActiveRiders(tripId), "Should reflect 3 allocated seats")

        repeat(2) { i -> service.matchRideRequest(sampleRequest(10 + i)) }
        assertEquals(5, rideRepo.countActiveRiders(tripId), "Should reflect all 5 seats filled")

        // One more request — must be waitlisted, count must stay at 5
        service.matchRideRequest(sampleRequest(20))
        assertEquals(5, rideRepo.countActiveRiders(tripId), "Count must not exceed capacity")
    }

    // -----------------------------------------------------------------------
    // Test K — TripNotFound falls to next candidate
    // -----------------------------------------------------------------------

    @Test
    fun `K - TripNotFound result for first candidate falls through to second`() = runBlocking {
        val trip1 = "trip-unknown"  // Not in rideRepo's map => TripNotFound
        val trip2 = "trip-K2"
        val rideRepo = CapacityAwareFakeRideRepo(mapOf(trip2 to 3))
        val vehicleRepo = CapacityAwareFakeVehicleRepo(listOf(candidate(trip1, 3), candidate(trip2, 3)))
        val service = RoutingService(vehicleRepo, InstantOptimizer, rideRepo)

        val result = service.matchRideRequest(sampleRequest(1))

        assertEquals("MATCHED", result.status)
        assertEquals("veh-$trip2", result.assignedVehicleId,
            "Should fall through to the second candidate when first returns TripNotFound")
    }

    // -----------------------------------------------------------------------
    // Test L — Matched response fields completeness
    // -----------------------------------------------------------------------

    @Test
    fun `L - matched response contains non-null rideRequestId, vehicle, and ETA`() {
        // Block body (not = runBlocking) so return type is Unit — JUnit 4 requires void.
        // assertNotNull returns T (not Unit) so = runBlocking { assertNotNull(...) } would
        // infer a non-void return and JUnit would reject it with InvalidTestClassError.
        runBlocking {
            val tripId = "trip-L"
            val rideRepo = CapacityAwareFakeRideRepo(mapOf(tripId to 2))
            val vehicleRepo = CapacityAwareFakeVehicleRepo(listOf(candidate(tripId, 2)))
            val service = RoutingService(vehicleRepo, InstantOptimizer, rideRepo)

            val result = service.matchRideRequest(sampleRequest(1))

            assertEquals("MATCHED", result.status)
            assertNotNull(result.rideRequestId, "rideRequestId must be present on MATCHED response")
            assertNotNull(result.assignedVehicleId, "assignedVehicleId must be present on MATCHED response")
            assertNotNull(result.etaMinutes, "etaMinutes must be present on MATCHED response")
        }
    }
}
