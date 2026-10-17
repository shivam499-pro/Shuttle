# Task 7.3 — Destination & Route Optimizer Audit

**Document Type:** Production Architecture Audit & Routing Model Trace  
**Target Repository:** `dynashuttle-backend` (`c:\project-shuttles-2026\dynashuttle-backend`)  
**Audit Mode:** STRICT READ-ONLY (No production code, interfaces, optimizers, repositories, services, database schema, migrations, tests, configuration, or experiment files modified)  
**Baseline Date:** October 2026  
**System Under Test:** DynaShuttle Production Routing Pipeline ([`RouteOptimizer.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/RouteOptimizer.kt), [`GreedyNearestNeighbourOptimizer.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/GreedyNearestNeighbourOptimizer.kt), [`RoutingService.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt), [`TripService.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/TripService.kt)) vs. Research Simulation Routing Model (Tasks 6.1–6.4)

---

## 1. Audit Status

* **Audit Completed:** **YES**
* **Production Code Modified:** **NO**
* **Interfaces / Routing Implementations Modified:** **NO**
* **Database Schema / Migrations Modified:** **NO**
* **Database Records Modified:** **NO**
* **Experiment Framework / Results Modified:** **NO**
* **Audit Mode:** Strict Read-Only Verification

---

## 2. Scope

This audit investigates the production routing and optimization subsystem of DynaShuttle to answer the core architectural question:

> **What would the production routing model need to represent in order to route a vehicle through multiple passenger pickups and drop-offs while respecting passenger state and vehicle capacity?**

The audit inspects:
* The optimizer interface definitions: [`RouteOptimizer.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/RouteOptimizer.kt) (`StopCandidate`, `OptimizedStop`, `RouteOptimizer`)
* The core heuristic implementation: [`GreedyNearestNeighbourOptimizer.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/GreedyNearestNeighbourOptimizer.kt)
* Ride request matching integration: [`RoutingService.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt) (`matchRideRequest`)
* Driver manifest generation: [`TripService.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/TripService.kt) (`getStopList`)
* Repository stop queries: [`PostgresRideRequestRepository.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt) (`findActiveByTripId`)
* ETA calculation and fallback pipelines: [`EtaEstimator.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/EtaEstimator.kt), [`TomTomEtaEstimator.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/TomTomEtaEstimator.kt), [`HaversineEtaEstimator.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/HaversineEtaEstimator.kt)
* Passenger lifecycle state mapping: [`RideRequestStateMachine.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RideRequestStateMachine.kt), [`RideStatus.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/RideStatus.kt)
* Existing routing and service unit tests: [`RoutingServiceTest.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/test/kotlin/com/dynashuttle/RoutingServiceTest.kt)

---

## 3. Executive Summary

1. **Complete Absence of Drop-Off Routing:** The production routing model represents **only passenger pickup events**. In [`StopCandidate`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/RouteOptimizer.kt#L13-L17), [`OptimizedStop`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/RouteOptimizer.kt#L19-L25), [`ActiveTripStop`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/RideRequestRepository.kt#L24-L30), and [`Stop`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Dto.kt#L102-L108), there is no stop type descriptor (`PICKUP` vs. `DROPOFF`) and no field for destination coordinates. Destination coordinates exist in the `ride_requests` database table, but are never queried or passed into any routing layer.
2. **Onboard Passengers Vanish from Routing:** In [`PostgresRideRequestRepository.findActiveByTripId()`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt#L201-L218), the query for active stops filters strictly on `status IN ('MATCHED', 'PICKUP_PENDING')`. The moment a driver marks a passenger as boarded (`status = 'ONBOARD'`), that passenger completely vanishes from the driver's stop list. The vehicle is never routed to drop them off.
3. **Single-Stop Optimizer Invocation During Matching:** In [`RoutingService.matchRideRequest()`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt#L53-L57), the optimizer is called with a list of **exactly one stop** (`listOf(StopCandidate(newRequest))`). It is used purely as an ETA estimator between the vehicle's current location and the new passenger's pickup location, ignoring all other intermediate stops assigned to the vehicle. The resulting sequence is immediately discarded.
4. **Lack of Precedence and Capacity Constraints:** [`GreedyNearestNeighbourOptimizer`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/GreedyNearestNeighbourOptimizer.kt) is an unconstrained greedy traveling salesperson heuristic. It has no concept of vehicle capacity along route legs, passenger boarding counts, or pickup-before-drop-off precedence constraints.
5. **Architectural Classification:** The production system is a **hybrid of single-request matching with preliminary ETA estimation (at booking time) and pickup-only stop sequencing (at driver execution time)**. It cannot perform pickup-and-drop-off vehicle routing.

---

## 4. RouteOptimizer Interface

The routing domain abstraction is defined in [`src/main/kotlin/com/dynashuttle/services/routing/RouteOptimizer.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/RouteOptimizer.kt):

```kotlin
data class StopCandidate(
    val rideRequestId: String,
    val lat: Double,
    val lng: Double
)

data class OptimizedStop(
    val rideRequestId: String,
    val lat: Double,
    val lng: Double,
    val sequenceOrder: Int,
    val etaMinutes: Int
)

interface RouteOptimizer {
    suspend fun optimize(
        vehicleLat: Double,
        vehicleLng: Double,
        stops: List<StopCandidate>
    ): List<OptimizedStop>
}
```

### Information Audit

| Field / Information | Status in Optimizer Input | Source / Location | Notes |
|---|---|---|---|
| **Vehicle Starting Coordinates** | **Available** | `vehicleLat`, `vehicleLng` parameters | Passed directly to `optimize()` |
| **Request ID** | **Available** | `StopCandidate.rideRequestId` | Passed in stop list |
| **Pickup Coordinates** | **Available** | `StopCandidate.lat`, `StopCandidate.lng` | Treated as generic waypoint coordinates |
| **Destination Coordinates** | **UNAVAILABLE** | Stored in `ride_requests.destination_lat/lng` | **Not passed** to optimizer anywhere |
| **Stop Type (`PICKUP` vs `DROPOFF`)** | **UNAVAILABLE** | Not modeled in domain | Interface assumes all stops are identical |
| **Passenger State (`MATCHED`, `ONBOARD`)** | **UNAVAILABLE** | Stored in `ride_requests.status` | Not passed to optimizer |
| **Sequence / Order Information** | **Output Only** | `OptimizedStop.sequenceOrder` | Output integer (1-indexed) |
| **Capacity Information** | **UNAVAILABLE** | Stored in `vehicles.seat_capacity` | Optimizer has no awareness of vehicle capacity |
| **Occupancy / Onboard Count** | **UNAVAILABLE** | Dynamically counted in DB | Optimizer has no concept of seat occupancy |
| **ETA Information** | **Output Only** | `OptimizedStop.etaMinutes` | Calculated per resolved leg and accumulated |
| **Vehicle Heading / Speed** | **UNAVAILABLE** | Not captured in telemetry | Optimizer assumes stationary start |
| **Existing Route State** | **UNAVAILABLE** | Routes are not persisted | Optimizer receives raw unsequenced list |

---

## 5. GreedyNearestNeighbourOptimizer

[`GreedyNearestNeighbourOptimizer.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/GreedyNearestNeighbourOptimizer.kt) implements `RouteOptimizer`:

```kotlin
class GreedyNearestNeighbourOptimizer(
    private val etaEstimator: EtaEstimator = HaversineEtaEstimator()
) : RouteOptimizer {

    override suspend fun optimize(
        vehicleLat: Double,
        vehicleLng: Double,
        stops: List<StopCandidate>
    ): List<OptimizedStop> {
        val remaining = stops.toMutableList()
        val ordered = mutableListOf<OptimizedStop>()

        var currentLat = vehicleLat
        var currentLng = vehicleLng
        var cumulativeMinutes = 0

        var sequenceOrder = 1
        while (remaining.isNotEmpty()) {
            val nearest = remaining.minByOrNull { haversineMeters(currentLat, currentLng, it.lat, it.lng) }!!
            cumulativeMinutes += etaEstimator.estimateMinutes(currentLat, currentLng, nearest.lat, nearest.lng)

            ordered.add(
                OptimizedStop(
                    rideRequestId = nearest.rideRequestId,
                    lat = nearest.lat,
                    lng = nearest.lng,
                    sequenceOrder = sequenceOrder++,
                    etaMinutes = cumulativeMinutes
                )
            )

            currentLat = nearest.lat
            currentLng = nearest.lng
            remaining.remove(nearest)
        }

        return ordered
    }
```

### Detailed Algorithmic Characteristics
* **Algorithm:** Greedy Nearest Neighbour (GNN) heuristic for waypoint sorting.
* **Input Representation:** `vehicleLat: Double`, `vehicleLng: Double`, `stops: List<StopCandidate>`.
* **Output Representation:** `List<OptimizedStop>`, containing original coordinates plus sequential order (`1..N`) and cumulative travel time (`etaMinutes`).
* **Starting Position:** Vehicle's instantaneous latitude and longitude (`vehicleLat`, `vehicleLng`).
* **Distance Calculation:** Pure in-memory spherical Haversine formula on Earth radius 6,371,000 meters ([lines 71-80](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/GreedyNearestNeighbourOptimizer.kt#L71-L80)). No network calls or road network geometry are used for distance comparisons.
* **ETA Calculation:** Calculated once per resolved stop transition:
  $$\text{cumulativeMinutes}_{i} = \text{cumulativeMinutes}_{i-1} + \text{etaEstimator.estimateMinutes}(lat_{i-1}, lng_{i-1}, lat_i, lng_i)$$
* **Tie-Breaking:** Kotlin's `minByOrNull` selects the first element encountered with the minimum evaluated distance.
* **Handling of Zero Stops (`stops.isEmpty()`):** The while loop does not execute; returns `emptyList()` immediately.
* **Handling of One Stop (`stops.size == 1`):** The while loop executes exactly once, assigns `sequenceOrder = 1`, and calls `etaEstimator.estimateMinutes(vehicleLat, vehicleLng, stop.lat, stop.lng)`. **No ordering occurs.**
* **Handling of Multiple Stops (`stops.size > 1`):** Recursively advances vehicle position to the nearest remaining candidate until all candidates are sequenced.
* **Input Mutation:** Non-destructive. A defensive copy is created via `stops.toMutableList()`.
* **Statefulness:** Completely stateless. Maintains no internal vehicle, passenger, or route state.
* **Destination Consideration:** **None.**
* **Capacity Consideration:** **None.**
* **Precedence Constraints:** **None.**
* **Onboard Passenger Consideration:** **None.**
* **Fleet Assignment:** **None.** It sequences stops for a single vehicle only.
* **Route Persistence:** **None.**
* **Network Calls:** None directly within the optimizer. If injected with `TomTomEtaEstimator`, exactly $N$ outbound HTTP calls occur (one per sequenced leg), never $N^2$.

---

## 6. RoutingService

Inspection of [`RoutingService.matchRideRequest()`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt#L41-L68):

```kotlin
suspend fun matchRideRequest(request: RideRequestDto, companyId: String? = null): RideResponseDto {
    val candidates = vehicleRepository.findAvailableNear(
        lat = request.pickupLat,
        lng = request.pickupLng,
        radiusMeters = searchRadiusMeters,
        companyId = companyId
    )

    for (candidate in candidates) {
        val allocation = rideRequestRepository.allocateSeat(request, candidate.tripId, companyId)
        if (allocation is AllocationResult.Allocated) {
            val stored = allocation.stored
            val optimizedStops = routeOptimizer.optimize(
                vehicleLat = candidate.currentLat,
                vehicleLng = candidate.currentLng,
                stops = listOf(StopCandidate(stored.id, request.pickupLat, request.pickupLng))
            )

            val etaForThisRequest = optimizedStops.firstOrNull { it.rideRequestId == stored.id }?.etaMinutes

            return RideResponseDto(
                rideRequestId = stored.id,
                status = "MATCHED",
                assignedVehicleId = candidate.vehicleId,
                etaMinutes = etaForThisRequest
            )
        }
    }

    val stored = rideRequestRepository.save(request, tripId = null, status = RideStatus.WAITLISTED.name)
    return RideResponseDto(rideRequestId = stored.id, status = RideStatus.WAITLISTED.name)
}
```

### Trace & Behavior
1. **Request Validation:** Handled upstream in `RideRoutes.kt` (ensures `user.role == "employee"` and `request.employeeId == user.userId`).
2. **Vehicle Search:** Queries `vehicleRepository.findAvailableNear()` for active `IN_PROGRESS` vehicles within 2,000m.
3. **Capacity Check:** Executes `allocateSeat()`, acquiring an exclusive row lock on `trips` and verifying `activeCount < seatCapacity`.
4. **Optimizer Invocation:** Calls `routeOptimizer.optimize()` passing:
   * `vehicleLat = candidate.currentLat`
   * `vehicleLng = candidate.currentLng`
   * `stops = listOf(StopCandidate(stored.id, request.pickupLat, request.pickupLng))`
5. **Stops Passed Count:** **Exactly 1 stop.**
6. **Existing Stops Included:** **NO.** Other passengers assigned to this vehicle's trip are completely excluded.
7. **Existing Onboard Passengers Included:** **NO.**
8. **Drop-Off Locations Included:** **NO.** `request.destinationLat/Lng` is completely ignored.
9. **Sequence Persistence:** **NO.** The returned `optimizedStops` list is not written to the database.
10. **Result Disposal:** The entire optimizer output is discarded after extracting `etaForThisRequest`.

---

## 7. Single-Stop Optimization

### Direct Verification
Lines 53–57 of [`RoutingService.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt#L53-L57) unequivocally prove that `RoutingService` calls the optimizer with a singleton list:
```kotlin
stops = listOf(StopCandidate(stored.id, request.pickupLat, request.pickupLng))
```

### Operational Consequences
* **Optimizer Used Purely as ETA Estimator:** When `stops.size == 1`, `GreedyNearestNeighbourOptimizer` does not sort or optimize anything. It computes:
  $$\text{ETA} = \text{etaEstimator.estimateMinutes}(\text{vehicleLat}, \text{vehicleLng}, \text{pickupLat}, \text{pickupLng})$$
* **Total Disregard of Prior Commitments:** If a shuttle already has 3 pending pickups scheduled across town, the optimizer ignores them. It calculates the ETA as if the shuttle were driving straight from its current GPS point directly to the 4th passenger.
* **Severe ETA Distortion:** Commuters receive an overly optimistic ETA that reports travel time for an empty direct run, completely omitting all intermediate stops the shuttle must make.
* **No Influence on Dispatch Sequence:** Because the sequence is not stored, calling the optimizer at this stage has zero effect on the vehicle's actual schedule or route.

---

## 8. TripService.getStopList()

Inspection of [`TripService.getStopList()`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/TripService.kt#L36-L63):

```kotlin
suspend fun getStopList(tripId: String): StopListUpdate? {
    val vehicleLocation = vehicleRepository.findVehicleLocationByTripId(tripId) ?: return null
    val activeStops = rideRequestRepository.findActiveByTripId(tripId)

    if (activeStops.isEmpty() || vehicleLocation.lat == null || vehicleLocation.lng == null) {
        return StopListUpdate(tripId = tripId, stops = emptyList())
    }

    val optimized = routeOptimizer.optimize(
        vehicleLat = vehicleLocation.lat,
        vehicleLng = vehicleLocation.lng,
        stops = activeStops.map { StopCandidate(it.rideRequestId, it.pickupLat, it.pickupLng) }
    )

    val employeeNameById = activeStops.associateBy({ it.rideRequestId }, { it.employeeName })

    val stops = optimized.map { stop ->
        Stop(
            rideRequestId = stop.rideRequestId,
            lat = stop.lat,
            lng = stop.lng,
            sequenceOrder = stop.sequenceOrder,
            employeeName = employeeNameById[stop.rideRequestId] ?: "Unknown"
        )
    }

    return StopListUpdate(tripId = tripId, stops = stops)
}
```

### Source Repository Query
In [`PostgresRideRequestRepository.kt:201-218`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt#L201-L218):
```kotlin
override fun findActiveByTripId(tripId: String): List<ActiveTripStop> = transaction {
    val tripUuid = runCatching { UUID.fromString(tripId) }.getOrNull() ?: return@transaction emptyList()
    (RideRequests innerJoin Employees)
        .selectAll()
        .where {
            (RideRequests.tripId eq tripUuid) and
                    (RideRequests.status inList listOf("MATCHED", "PICKUP_PENDING"))
        }
        .map {
            ActiveTripStop(
                rideRequestId = it[RideRequests.id].value.toString(),
                employeeName = it[Employees.name],
                pickupLat = it[RideRequests.pickupLat],
                pickupLng = it[RideRequests.pickupLng],
                employeeId = it[RideRequests.employeeId].value.toString()
            )
        }
}
```

### Operational Characteristics
* **Request Statuses Included:** Strictly `MATCHED` and `PICKUP_PENDING`.
* **Coordinates Provided:** Strictly `pickupLat` and `pickupLng`.
* **Destination Coordinates:** **Zero.** `ActiveTripStop` does not even have destination fields.
* **Onboard Passengers:** **EXCLUDED.** When a passenger boards (`POST /stops/{id}/pickup`), their status becomes `ONBOARD`. Consequently, they are immediately excluded by `findActiveByTripId()`.
* **Completed / Cancelled Passengers:** Excluded.
* **Stop List Nature:** **PICKUPS ONLY.** The driver is only ever directed to passenger pickup points. The system never provides drop-off instructions.

---

## 9. Passenger State vs Routing State

Audit of the alignment between passenger lifecycle states and routing representation:

| Passenger State | Current Production Meaning | Expected Routing Event | Appears in Optimizer Input? | Appears in Driver Stop List? | Destination Available to Routing? | Status Alignment |
|---|---|---|---|---|---|---|
| **`REQUESTED`** | Initial booking submitted | None (Unmatched) | No | No | No | Aligned |
| **`MATCHED`** | Seat allocated on active trip | **Pickup Stop** | Yes (Single-stop in match; Multi-stop in manifest) | Yes (Pickup) | No | Partially Aligned |
| **`WAITLISTED`** | No vehicle available | None (Pending match) | No | No | No | Aligned |
| **`PICKUP_PENDING`** | Vehicle arriving at pickup | **Pickup Stop** | Yes (In driver manifest) | Yes (Pickup) | No | Partially Aligned |
| **`ONBOARD`** | Passenger boarded shuttle | **Drop-Off Stop** | **NO (Excluded)** | **NO (Vanishes)** | **NO** | **CRITICAL MISMATCH** |
| **`COMPLETED`** | Passenger reached destination | None (Finished) | No | No | No | Aligned |
| **`CANCELLED`** | Ride cancelled | None (Void) | No | No | No | Aligned |
| **`NO_SHOW`** | Passenger failed to board | None (Void) | No | No | No | Aligned |

### The `ONBOARD` State Mismatch
An onboard passenger holds a seat inside the physical shuttle and has exactly one outstanding travel requirement: **to be dropped off at their destination**. However, in production:
1. `findActiveByTripId()` drops the passenger upon transition to `ONBOARD`.
2. The driver stop manifest no longer displays the passenger.
3. The route optimizer never receives their destination coordinates.
4. The driver has no digital routing guidance to complete the passenger's journey.

---

## 10. Pickup / Drop-Off Representation

Detailed code search for routing representation:

* `pickupLat`, `pickupLng`: Present in `RideRequestDto`, `RideRequests` table, `StoredRideRequest`, `ActiveTripStop`, `StopCandidate`, `OptimizedStop`, `Stop`.
* `destinationLat`, `destinationLng`: Present **only** in `RideRequestDto` ([`Dto.kt:10-11`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Dto.kt#L10-L11)) and `RideRequests` table ([`Tables.kt:107-108`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Tables.kt#L107-L108)).
* `StopType` / `EventType`: **NOT FOUND.** Does not exist in the codebase.
* `Stop` DTO: Contains `rideRequestId`, `lat`, `lng`, `sequenceOrder`, `employeeName`. It has no field indicating whether the stop is a pickup or a drop-off.
* `StopCandidate`: Contains `rideRequestId`, `lat`, `lng`. No type field.
* `OptimizedStop`: Contains `rideRequestId`, `lat`, `lng`, `sequenceOrder`, `etaMinutes`. No type field.

> **Definitive Conclusion:** The current domain model represents **only single-point pickup waypoints**. Drop-off waypoints are completely unrepresented in the routing and optimization layer.

---

## 11. Multi-Passenger Routing

Can production represent a sequence such as:
$$\text{Vehicle} \longrightarrow \text{Pickup A} \longrightarrow \text{Pickup B} \longrightarrow \text{Drop-Off A} \longrightarrow \text{Drop-Off B}$$
or:
$$\text{Vehicle} \longrightarrow \text{Pickup A} \longrightarrow \text{Drop-Off A} \longrightarrow \text{Pickup B} \longrightarrow \text{Drop-Off B}$$

**Answer: NO.**
1. **Drop-Offs Cannot Be Ingested:** Because `StopCandidate` does not support drop-offs, drop-off coordinates cannot enter the optimizer.
2. **Precedence Is Not Supported:** The GNN optimizer repeatedly selects the closest point. It has no mechanism to enforce that $\text{Pickup}_i$ must precede $\text{Drop-Off}_i$. If drop-offs were naively added to the candidate list, the vehicle could be instructed to drop off passenger B before picking up passenger B if the drop-off location happened to be geographically closer.
3. **Onboard Passengers Ignored:** Passengers who boarded at Pickup A are removed from `activeStops`, so Drop-off A can never be scheduled.

---

## 12. Capacity Interaction With Routing

### Separation of Concerns
* **Seat Allocation Correctness:** **IMPLEMENTED.** Enforced strictly at the database level in `PostgresRideRequestRepository.allocateSeat()` via pessimistic row locking (`SELECT ... FOR UPDATE OF t`). It ensures that total concurrent passenger reservations on a trip do not exceed `vehicles.seat_capacity`.
* **Capacity-Aware Route Optimization:** **NOT IMPLEMENTED / NOT FOUND.**
  * `RouteOptimizer` receives zero capacity parameters.
  * `GreedyNearestNeighbourOptimizer` has no knowledge of remaining vehicle seats.
  * In a mixed pickup/drop-off route, vehicle load fluctuates:
    $$\text{Load}(t) = \text{Load}(t-1) + 1 \quad (\text{Pickup})$$
    $$\text{Load}(t) = \text{Load}(t-1) - 1 \quad (\text{Drop-Off})$$
    The production optimizer has no capacity tracking along route legs, so it cannot ensure that vehicle load remains $\le \text{seat\_capacity}$ across intermediate stops.

---

## 13. ETA Architecture

* **Calculation Triggers:**
  1. *At Match Time:* In `RoutingService.matchRideRequest()`, calculated between vehicle current position and the single new pickup point.
  2. *At Manifest Time:* In `TripService.getStopList()`, calculated sequentially along the ordered pickup legs.
* **Provider Hierarchy:**
  * Primary: `TomTomEtaEstimator` via HTTP GET to `https://api.tomtom.com/routing/1/calculateRoute/.../json?traffic=true`.
  * Fallback: `HaversineEtaEstimator` assuming constant urban speed (25 km/h). Injected automatically if `TOMTOM_API_KEY` is missing or if any network/HTTP error occurs.
* **Scope:**
  * Match time: Single-leg direct run ETA.
  * Stop list: Multi-leg cumulative ETA (`cumulativeMinutes`).
* **Destinations / Drop-Offs:** **Excluded.** ETA to destination is never calculated.
* **Persistence:** **None.** Computed in memory on-the-fly and returned in response DTOs.
* **Client Notification:** Returned in HTTP response bodies; never broadcast over WebSockets.

---

## 14. Route Recalculation

Complete audit of routing triggers and recalculation behavior:

| Trigger Event | Optimizer Invoked? | Input Coordinates | Route Persisted? | Clients Notified? |
|---|---|---|---|---|
| **Employee requests ride (`POST /rides`)** | **YES** | Vehicle GPS $\rightarrow$ Single new pickup point | **NO** (Discarded) | **NO** (Returned in HTTP 201 only) |
| **Driver fetches stop list (`GET /trips/{id}/stops`)** | **YES** | Vehicle GPS $\rightarrow$ All pending pickups | **NO** (On-the-fly) | **NO** (Returned in HTTP 200 only) |
| **Driver pushes GPS update (`/ws/driver/{tripId}`)** | **NO** | None (Overwrites vehicle row only) | **NO** | **NO** (Coordinates relayed, route untouched) |
| **Passenger boarded (`POST /pickup`)** | **NO** | None (Updates DB row only) | **NO** | **NO** |
| **Passenger dropped off (`POST /complete`)** | **NO** | None (Updates DB row only) | **NO** | **NO** |
| **Passenger cancelled (`POST /cancel`)** | **NO** | None (Updates DB row only) | **NO** | **NO** |
| **Vehicle approaches pickup zone** | **NO** | None (No geofencing implemented) | **NO** | **NO** |

---

## 15. Vehicle Position Interaction

* **Origin Point:** Both `RoutingService` and `TripService` initialize route sequencing from `vehicles.lastKnownLat` and `vehicles.lastKnownLng`.
* **Data Freshness:**
  * In `RoutingService.matchRideRequest()`: Uses `candidate.currentLat/Lng` returned from the PostGIS query.
  * In `TripService.getStopList()`: Uses `vehicleLocation.lat/lng` retrieved from `Trips innerJoin Vehicles`.
* **Fixed Position Fallback:** If a vehicle has never reported a GPS fix (`lastKnownLat == null`), `TripService.getStopList()` aborts and returns an empty stop list (`stops = emptyList()`) ([`TripService.kt:40-42`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/TripService.kt#L40-L42)).
* **Movement Representation:** The system does not project vehicle motion, heading, or speed. It treats the vehicle as a static point at its latest reported coordinates.

---

## 16. External Routing Dependencies

* **TomTom Routing API v1:**
  * Endpoint: `https://api.tomtom.com/routing/1/calculateRoute/{fromLat},{fromLng}:{toLat},{toLng}/json`
  * Parameter: `traffic=true`
* **Dependency Isolation:**
  * Route ordering decisions ("which stop is next") use local Haversine distance and **never make network calls**.
  * Travel-time estimation uses TomTom only after the stop order is established.
* **Resilience:**
  * In [`TomTomEtaEstimator.kt:75-81`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/TomTomEtaEstimator.kt#L75-L81), all external exceptions are caught and logged at `WARN`.
  * The system falls back cleanly to flat-speed Haversine calculations.
  * External network downtime or API quota exhaustion **cannot crash ride matching or stop list retrieval**.

---

## 17. Research vs. Production Routing Comparison

| Routing Dimension | Research DynaShuttle (Tasks 6.1–6.4) | Production DynaShuttle (`dynashuttle-backend`) | Match? | Detailed Variance |
|---|---|---|---|---|
| **Request Batching** | 300-second demand accumulation buffer | None; immediate single-request matching | **DIFFERENT** | Production does not batch requests. |
| **Fleet Assignment** | Evaluates entire fleet based on earliest availability | Searches active `IN_PROGRESS` vehicles within 2km | **DIFFERENT** | Production considers only active vehicles within 2km; ignores idle fleet. |
| **Vehicle State** | Tracks batch, busy time, simulated location | Stores latest reported GPS coordinates in DB | **DIFFERENT** | Production relies on live client telemetry. |
| **Capacity Handling** | Decremented in memory per batch | Enforced via PostgreSQL row-level lock (`FOR UPDATE`) | **DIFFERENT** | Production provides real transactional concurrency safety. |
| **Pickup Representation** | Modeled as batch stops with origin coordinates | Modeled as `StopCandidate` with pickup coordinates | **MATCH** | Pickup point representation is functionally identical. |
| **Drop-Off Representation** | Explicit corporate campus hub destination | **None.** Destination coordinates excluded | **DIFFERENT** | Critical gap: production has no drop-off representation. |
| **Multi-Stop Ordering** | Greedy NN on full batch before dispatch | Greedy NN on active pickups when driver polls stops | **PARTIAL** | Heuristic matches; execution timing and stop set differ. |
| **Pickup/Drop-Off Precedence** | All pickups precede the single campus drop-off | **None.** Only pickups exist | **DIFFERENT** | Production has no precedence rules or drop-offs. |
| **ETA Estimation** | Deterministic flat speed model (25 km/h) | Live traffic-aware TomTom with Haversine fallback | **DIFFERENT** | Production supports real-world traffic APIs. |
| **Route Persistence** | Internal simulation itinerary | **None.** Calculated transiently and discarded | **DIFFERENT** | Neither system persists routes to a database table. |
| **Route Recalculation** | Triggered upon batch dispatch / vehicle arrival | Triggered only when driver polls `GET /trips/{id}/stops` | **DIFFERENT** | Production recalculation is lazy and polling-driven. |
| **Vehicle Movement** | Mathematical translation across simulation clock | Pushed by driver device via WebSocket frames | **DIFFERENT** | Production reflects physical vehicle movement. |
| **Completion** | Automatic upon arrival at campus hub | Driver calls `POST /complete` (No destination routed) | **DIFFERENT** | Production requires manual driver completion. |

---

## 18. Architectural Assessment

Based strictly on the code evidence, the current production routing architecture is best classified as:

> **Option D: Hybrid of B and C**
> * **Phase 1 (Ride Booking):** Functions as **C (Single-request matching with preliminary ETA optimization)**. The optimizer is called with only 1 stop to calculate an initial pickup ETA for the commuter; the sequence is immediately discarded.
> * **Phase 2 (Trip Execution):** Functions as **B (Pickup-only stop sequencing)**. The optimizer sequences multiple pending pickup points when the driver queries the manifest. Drop-off destinations are completely omitted.

It does **NOT** implement Option A (Full pickup-and-drop-off vehicle routing).

---

## 19. Future Design Implications

To evolve the production routing model into a true pickup-and-drop-off routing engine without breaking existing contracts, a future design would need to incorporate:

1. **Stop Type Distinction:**
   * Introduce a domain enum: `enum class StopType { PICKUP, DROPOFF }`.
   * Expand `StopCandidate` and `OptimizedStop` to include `stopType: StopType`.
2. **Dual-Waypoint Generation per Ride Request:**
   * For each active ride request, two potential waypoints exist:
     * *Pickup Stop:* `(pickupLat, pickupLng)` when `status IN ('MATCHED', 'PICKUP_PENDING')`.
     * *Drop-Off Stop:* `(destinationLat, destinationLng)` when `status IN ('MATCHED', 'PICKUP_PENDING', 'ONBOARD')`.
3. **Precedence Constraint Enforcement:**
   * The optimizer must enforce:
     $$\text{Index}(\text{Pickup}_i) < \text{Index}(\text{DropOff}_i)$$
   * For already `ONBOARD` passengers, only the `DROPOFF` stop is passed to the optimizer.
4. **Capacity-Constrained Route Feasibility:**
   * The optimizer must verify that vehicle passenger load at every waypoint $k$ satisfies:
     $$\text{Load}(k) = \text{CurrentOnboard} + \sum_{j=1}^k \Delta(j) \le \text{seat\_capacity}$$
     where $\Delta(j) = +1$ for pickup and $-1$ for drop-off.
5. **Driver Manifest Presentation:**
   * `Stop` DTO must include `stopType: String` so the driver app can visually distinguish whether to pick up or drop off a passenger.
6. **Multi-Stop ETA Accuracy in `RoutingService`:**
   * When matching a new request, `RoutingService` should pass the vehicle's full active route (existing pending pickups and drop-offs) plus the candidate stops to calculate a realistic ETA that accounts for detour time.

---

## 20. Severity-Ranked Findings

### CRITICAL Findings
1. **CRITICAL-1: Complete Omission of Drop-Off Coordinates from Routing Subsystem**
   * *Evidence:* [`RouteOptimizer.kt:13-17`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/RouteOptimizer.kt#L13-L17), [`RideRequestRepository.kt:24-30`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/RideRequestRepository.kt#L24-L30), [`TripService.kt:36-63`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/TripService.kt#L36-L63).
   * *Issue:* `StopCandidate`, `OptimizedStop`, and `ActiveTripStop` only represent pickup points. Passenger destination coordinates (`destinationLat/Lng`) are never queried or provided to the routing engine.
   * *Operational Consequence:* The system is incapable of routing vehicles to passenger destinations or corporate campus hubs. Drivers receive pickup instructions but zero drop-off guidance.
2. **CRITICAL-2: Onboard Passengers Disappear from Driver Stop Manifest**
   * *Evidence:* [`PostgresRideRequestRepository.kt:207`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt#L207).
   * *Issue:* `findActiveByTripId()` filters strictly on `status IN ('MATCHED', 'PICKUP_PENDING')`.
   * *Operational Consequence:* The moment a passenger boards (`ONBOARD`), they vanish from the driver's manifest. The driver has no digital record of onboard passengers or where to deliver them.

### HIGH Findings
3. **HIGH-1: Single-Stop Optimizer Invocation Distorts Commuter ETA**
   * *Evidence:* [`RoutingService.kt:53-57`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt#L53-L57).
   * *Issue:* `RoutingService` passes only the single new request to `routeOptimizer.optimize()`, ignoring other passengers already scheduled on that vehicle.
   * *Operational Consequence:* Commuters receive an inaccurate, overly optimistic ETA based on a direct empty trip, ignoring earlier scheduled stops.
4. **HIGH-2: Optimizer Lacks Precedence and Capacity Logic**
   * *Evidence:* [`GreedyNearestNeighbourOptimizer.kt:42-59`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/GreedyNearestNeighbourOptimizer.kt#L42-L59).
   * *Issue:* The algorithm sorts purely by nearest distance without precedence checks or capacity tracking.
   * *Operational Consequence:* If drop-offs were added naively, the optimizer could order drop-offs before pickups or exceed seat capacity on intermediate legs.

### MEDIUM Findings
5. **MEDIUM-1: Absence of Traffic-Aware Waypoint Sequencing**
   * *Evidence:* [`GreedyNearestNeighbourOptimizer.kt:43`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/GreedyNearestNeighbourOptimizer.kt#L43).
   * *Issue:* Sequencing decisions use straight-line Haversine math. TomTom is used only to compute leg duration after sequencing.
   * *Operational Consequence:* In congested urban road networks with one-way streets, straight-line nearest stops may require longer actual drive times.
6. **MEDIUM-2: Polling-Driven Lazy Route Recalculation**
   * *Evidence:* [`TripRoutes.kt:29-63`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/TripRoutes.kt#L29-L63).
   * *Issue:* Route optimization is never triggered by vehicle movement or passenger boarding; it only runs when the driver polls `GET /trips/{id}/stops`.
   * *Operational Consequence:* High API polling overhead; driver routes remain stale between polls.

### LOW Findings
7. **LOW-1: Transient Route Representation (No Persistence)**
   * *Evidence:* [`TripService.kt:52-62`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/TripService.kt#L52-L62).
   * *Issue:* Optimized routes are returned directly as HTTP responses without being saved to a database table.
   * *Operational Consequence:* Prevents historical route analysis, auditing, and planned path visualization.

---

## 21. Recommended Next Audit

**TASK 7.4 — Real-Time / WebSocket Audit**

*Rationale:*  
Task 7.1 mapped the overarching request-to-trip architecture. Task 7.2 audited the trip lifecycle and identified the vehicle/driver assignment deadlock. Task 7.3 audited the routing engine and proved that drop-offs are completely unrepresented in production routing. Task 7.4 should audit the real-time communication subsystem to determine:
1. What real-time messages are actually supported over `/ws/driver/{tripId}` and `/ws/trip/{tripId}`.
2. How vehicle GPS updates propagate to passengers and admins.
3. How dispatch events, route updates, and boarding transitions could be synchronized in real time to eliminate polling.

---

## 22. Safety / Verification Status

* **Production Code Modified:** **NO**
* **Interfaces / Optimizers Modified:** **NO**
* **Database Schema / Migrations Modified:** **NO**
* **Database Records Modified:** **NO**
* **Experiment Files Modified:** **NO**
* **Verification Checks:**
  * Clean Gradle build validation dry-run executed.
  * `git diff --check` executed with zero functional diffs.
  * `git status --short` confirmed only the Task 7.3 report was created.

---

```text
TASK 7.3 STATUS: COMPLETE

Report created:
docs/Task7_3_Destination_Route_Optimizer_Audit.md

Production code modified: NO

Database modified: NO

Experiment files modified: NO

Tests:
Dry-run verification clean (No test regressions)

git diff --check:
PASS

Most important findings:
1. Production routing represents ONLY pickup waypoints; passenger destination coordinates (destinationLat/Lng) are completely omitted from RouteOptimizer, StopCandidate, OptimizedStop, and driver stop manifests.
2. Onboard passengers completely vanish from the driver stop manifest upon boarding (status = 'ONBOARD'); the system never provides routing guidance to drop them off.
3. In RoutingService.matchRideRequest(), the optimizer is called with exactly one stop purely as a direct-line ETA helper, completely ignoring earlier passenger stops and producing distorted, overly optimistic ETAs.

Recommended next task:
TASK 7.4 — Real-Time / WebSocket Audit
```
