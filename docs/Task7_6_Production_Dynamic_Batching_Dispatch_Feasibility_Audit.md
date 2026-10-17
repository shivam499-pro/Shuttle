# Task 7.6 — Production Dynamic Batching & Dispatch Feasibility Audit

## 1. Audit Status

* **Status:** COMPLETE
* **Audit Mode:** Strictly Read-Only (no production code, models, routes, repositories, services, database schema, migrations, database records, tests, or experiment files modified)
* **Date:** October 7, 2026
* **Target System:** DynaShuttle Backend (`dynashuttle-backend`)
* **Previous Audits Frozen:**
  * Task 7.1: Production Architecture & Request-to-Trip Flow Audit ([Task7_1_Production_Architecture_Audit.md](file:///c:/project-shuttles-2026/dynashuttle-backend/docs/Task7_1_Production_Architecture_Audit.md))
  * Task 7.2: Trip Lifecycle & Vehicle Assignment Audit ([Task7_2_Trip_Lifecycle_Vehicle_Assignment_Audit.md](file:///c:/project-shuttles-2026/dynashuttle-backend/docs/Task7_2_Trip_Lifecycle_Vehicle_Assignment_Audit.md))
  * Task 7.3: Destination & Route Optimizer Audit ([Task7_3_Destination_Route_Optimizer_Audit.md](file:///c:/project-shuttles-2026/dynashuttle-backend/docs/Task7_3_Destination_Route_Optimizer_Audit.md))
  * Task 7.4: Real-Time / WebSocket Audit ([Task7_4_RealTime_WebSocket_Audit.md](file:///c:/project-shuttles-2026/dynashuttle-backend/docs/Task7_4_RealTime_WebSocket_Audit.md))
  * Task 7.5: Security & Multi-Tenant Isolation Audit ([Task7_5_Security_MultiTenant_Audit.md](file:///c:/project-shuttles-2026/dynashuttle-backend/docs/Task7_5_Security_MultiTenant_Audit.md))
* **Frozen Research Baseline:** Tasks 4.1–6.4 (Simulation Engine, `DynaShuttleSimulationStrategy`, and experimental results in `experiment_results_raw.csv`).

---

## 2. Scope

This audit investigates the feasibility of translating the frozen research experiment's dynamic batching, fleet-level assignment, and route execution model into the production DynaShuttle backend.

The central architectural question is:

> **Can the current production architecture support the dynamic batching and vehicle-dispatch behavior evaluated in the research experiment, and what production changes would be required to implement it safely?**

The audit evaluates:
1. Frozen research simulation mechanics (`dynamicBatchWindowSeconds = 300`, `vehicleCapacity = 4`, nearest eligible vehicle selection, greedy nearest-neighbour stop sequencing, destination drop-off).
2. Production request-to-dispatch execution path (`POST /rides` $\to$ `RoutingService.matchRideRequest`).
3. Spatial vehicle selection vs. research fleet availability modeling.
4. Database locking, capacity enforcement, and concurrent request safety.
5. Batch formation mechanisms, queueing, and background scheduling.
6. Data model capacity to represent multi-passenger batches, ordered stops, and passenger drop-offs.
7. Role and usage of `GreedyNearestNeighbourOptimizer` in production vs. research.
8. Vehicle state tracking in PostgreSQL vs. simulation state.
9. Trip lifecycle dependencies and operational assignment deadlocks.
10. Real-time push dispatch capabilities via WebSockets.
11. Architectural identification of the ideal dispatch insertion point.
12. Gap matrix, production readiness classification, and minimum future architecture.

---

## 3. Executive Summary

1. **Fundamental Paradigm Divergence (Online Single-Request vs. Discrete Batching):**
   The production backend operates strictly on an **immediate, online, request-by-request matching model**. When `POST /rides` arrives, `RoutingService.matchRideRequest()` synchronously evaluates candidate vehicles, locks a seat, invokes the optimizer for a single point-to-point ETA, and returns. **There is zero concept of a batch window (`dynamicBatchWindowSeconds = 300`), zero request queueing, and zero delayed batch dispatch**.

2. **Sound Concurrency Foundation on Single-Trip Allocations:**
   Production implements robust pessimistic row-level locking (`SELECT ... FOR UPDATE OF t`) inside `PostgresRideRequestRepository.allocateSeat()` ([PostgresRideRequestRepository.kt:42](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt#L42)). This prevents seat over-allocation on individual active trips under concurrent load. However, this lock operates strictly on a pre-selected trip row; it does not coordinate fleet-wide vehicle selection or multi-request batch grouping.

3. **Fleet Dispatcher Misconception Clarified:**
   In the research experiment, fleet-level vehicle selection is handled by `DynaShuttleSimulationStrategy` ([DynaShuttleSimulationStrategy.kt:160](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/experiments/DynaShuttleSimulationStrategy.kt#L160)), while `GreedyNearestNeighbourOptimizer` is purely a local stop-sequencing component. In production, `GreedyNearestNeighbourOptimizer` is called with a singleton list (`listOf(StopCandidate(...))`) during ride matching, acting merely as an ETA helper. The returned sequence is immediately discarded.

4. **Missing Drop-Off and Route Persistence Models:**
   Production has no database table or schema column to persist ordered stop sequences (`trip_stops`). Furthermore, as established in Task 7.3, passenger destination coordinates (`destination_lat/lng`) never enter the routing manifest, and passengers who board (`status = 'ONBOARD'`) vanish from the driver stop manifest. Production currently cannot represent a complete pickup-and-drop-off route.

5. **Operational Lifecycle Deadlock:**
   Production spatial matching (`findAvailableNear`) strictly queries `trips.status = 'IN_PROGRESS'` with non-null vehicle coordinates. However, schedule trip generation creates trips as `SCHEDULED` with `vehicle_id = null` and `driver_id = null`. Because no production service assigns vehicles or transitions trips to `IN_PROGRESS`, dynamic matching inevitably encounters zero eligible vehicles and defaults to `WAITLISTED`.

6. **Production Readiness Classification:**
   The production architecture is classified as **C — Requires a substantial new dispatch/batching subsystem**. The fundamental domain entities (`trips`, `vehicles`, `ride_requests`), database locking primitives, and stop sequencing heuristics exist, but the system entirely lacks a batching buffer, an automated fleet dispatch coordinator, route sequence persistence, and drop-off modeling.

---

## 4. Production Request-to-Dispatch Flow

The actual end-to-end trace of a ride request in production:

```mermaid
sequenceDiagram
    autonumber
    actor Employee as Employee Mobile App
    participant Route as RideRoutes (POST /rides)
    participant RoutingSvc as RoutingService.matchRideRequest()
    participant VehRepo as PostgresVehicleRepository
    participant RideRepo as PostgresRideRequestRepository
    participant DB as PostgreSQL
    participant Optimizer as GreedyNearestNeighbourOptimizer

    Employee->>Route: POST /rides (RideRequestDto)
    Note over Route: Authenticate JWT (role=employee)<br/>Verify request.employeeId == user.userId
    Route->>RoutingSvc: matchRideRequest(request, companyId)
    
    RoutingSvc->>VehRepo: findAvailableNear(lat, lng, 3000m, companyId)
    VehRepo->>DB: Raw JDBC Spatial Query (WHERE status='IN_PROGRESS')
    DB-->>VehRepo: List<VehicleCandidate> (ordered by ST_Distance)
    VehRepo-->>RoutingSvc: candidates
    
    alt Candidates Found
        loop For each candidate vehicle
            RoutingSvc->>RideRepo: allocateSeat(request, candidate.tripId, companyId)
            Note over RideRepo: BEGIN TRANSACTION
            RideRepo->>DB: SELECT ... FROM trips JOIN vehicles WHERE id = ? FOR UPDATE OF t
            RideRepo->>DB: SELECT COUNT(*) FROM ride_requests WHERE trip_id = ? AND status IN (...)
            alt activeCount < seat_capacity
                RideRepo->>DB: INSERT INTO ride_requests (status='MATCHED', trip_id=?)
                Note over RideRepo: COMMIT TRANSACTION
                RideRepo-->>RoutingSvc: AllocationResult.Allocated(stored)
                
                RoutingSvc->>Optimizer: optimize(vehiclePos, stops = listOf(StopCandidate(stored.id, pickupLat, pickupLng)))
                Optimizer-->>RoutingSvc: List<OptimizedStop> (single stop)
                Note over RoutingSvc: Extract etaMinutes; DISCARD sequence
                RoutingSvc-->>Route: RideResponseDto(status="MATCHED", vehicleId, etaMinutes)
                Route-->>Employee: HTTP 201 Created (RideResponseDto)
            else Capacity Exhausted
                Note over RideRepo: ROLLBACK / COMMIT without insert
                RideRepo-->>RoutingSvc: AllocationResult.CapacityExhausted
            end
        end
    else No Candidates or All Exhausted
        RoutingSvc->>RideRepo: save(request, tripId=null, status="WAITLISTED")
        RideRepo->>DB: INSERT INTO ride_requests (status='WAITLISTED', trip_id=null)
        RoutingSvc-->>Route: RideResponseDto(status="WAITLISTED")
        Route-->>Employee: HTTP 201 Created (status="WAITLISTED")
    end
```

### Critical Trace Observations:
1. **Synchronous HTTP Thread:** Every operation (spatial query, row locking, seat allocation, optimizer execution, DB insert) executes synchronously inside the HTTP handler of `POST /rides`.
2. **Immediate Allocation:** The request is immediately committed to a specific vehicle/trip or waitlisted. It cannot be delayed or grouped.
3. **No Batching Participation:** There is no queue, channel, or buffer. The request cannot join a 300-second accumulation window.

---

## 5. Current Vehicle Assignment Audit

Comparing production vehicle assignment against the frozen research rule:

$$\text{Eligible Vehicle} = \text{Nearest Vehicle} + \text{Remaining Capacity} + \text{Availability} + \text{Deterministic Tie-Break}$$

| Assignment Dimension | Research Simulation Strategy | Production Implementation | Match? | Architectural Gap |
| :--- | :--- | :--- | :--- | :--- |
| **Selection Criterion** | Straight-line Haversine distance to pickup location | PostGIS `ST_Distance` on real-time vehicle GPS coordinates | **Partial** | PostGIS distance is geographically realistic; research used Haversine. |
| **Availability Filter** | `v.availableAtSeconds <= request.requestedAtSeconds` (future completion time) | `t.status = 'IN_PROGRESS' AND v.last_known_lat IS NOT NULL` | **NO** | Production only considers instantaneous location; has no concept of future schedule availability time. |
| **Capacity Filter** | `v.remainingCapacity > 0` | `available_seats > 0` computed via subquery in `findAvailableNear` | **YES** | Both filter out full vehicles. |
| **Tie-Breaking Rule** | Deterministic: `compareBy(distance).thenBy(vehicleId)` | Non-deterministic SQL: `ORDER BY ST_Distance(...) ASC` | **NO** | Equidistant vehicles in production resolve in arbitrary database row order. |
| **Trip Association** | Implicit in vehicle state | Requires pre-existing `IN_PROGRESS` trip record in PostgreSQL | **NO** | In production, a vehicle without an `IN_PROGRESS` trip is completely invisible to assignment. |
| **Driver Assignment** | Synthetic agent driver | Null in `trips`; no automated driver assignment service | **NO** | Drivers must be assigned out-of-band. |
| **Transactional Scope** | In-memory atomic step | Spatial query is non-locking; subsequent `allocateSeat` uses `FOR UPDATE` | **Partial** | Race condition between discovery and seat reservation handled gracefully by looping candidates. |

---

## 6. Current Capacity Audit

### 6.1 Capacity Invariant Verification
In [PostgresRideRequestRepository.kt:33-95](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt#L33-L95), capacity is enforced using PostgreSQL pessimistic locking:

```sql
SELECT t.id, t.status, v.seat_capacity, v.company_id
FROM trips t
JOIN vehicles v ON v.id = t.vehicle_id
WHERE t.id = ?
FOR UPDATE OF t
```

Followed immediately by:
```sql
SELECT COUNT(*)
FROM ride_requests
WHERE trip_id = ?
  AND status IN ('MATCHED', 'PICKUP_PENDING', 'ONBOARD')
```

And evaluated:
```kotlin
if (activeCount >= seatCapacity) {
    return@transaction AllocationResult.CapacityExhausted
}
```

### 6.2 Concurrency Evaluation:
* **Can two concurrent requests double-allocate the final seat?**
  **NO.** Because the trip row is locked with `FOR UPDATE OF t`, Transaction B blocks until Transaction A completes. When Transaction B acquires the lock, it re-queries `COUNT(*)` and sees Transaction A's newly inserted row. `activeCount >= seatCapacity` triggers, and Transaction B receives `AllocationResult.CapacityExhausted`.
* **Is capacity fleet-aware?**
  **NO.** Capacity is evaluated strictly on a single trip row. The system cannot evaluate multi-vehicle capacity distribution across a batch.

---

## 7. Current Batch Formation Capability

A comprehensive search of the codebase for batching constructs:

| Construct | Search Pattern | Exists in Production? | Evidence |
| :--- | :--- | :--- | :--- |
| **Batch Window Timer** | `batchWindow`, `debounce`, `delay` | **NO** | Zero occurrences in `src/main/kotlin/com/dynashuttle/services/` |
| **Asynchronous Queue** | `Queue`, `Channel`, `BlockingQueue` | **NO** | No queueing constructs exist |
| **Background Dispatch Worker** | `Worker`, `Job`, `ScheduledExecutorService` | **NO** | No periodic background workers exist |
| **Coroutine Dispatch Loop** | `launch`, `Flow`, `SharedFlow` | **NO** | Zero asynchronous dispatch coroutines |
| **Message Broker / Cache** | Redis, RabbitMQ, Kafka | **NO** | Only PostgreSQL via HikariCP and Exposed |
| **Database Queue** | `pending_requests`, `dispatch_queue` | **NO** | `ride_requests` table has no batching or queueing columns |

**Explicit Audit Answer:**
> **Production currently does NOT wait for multiple ride requests and optimize them together. It evaluates every request individually and synchronously.**

---

## 8. Batch State Representation

Can the existing production schema represent a batch of multiple ride requests?

### 8.1 Schema Support Analysis:
```text
Existing Production Schema:
trips (1) ───────────< ride_requests (N)
  │
  ├── vehicle_id (UUID, nullable)
  ├── driver_id (UUID, nullable)
  └── status (SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED)
```

1. **Multi-Passenger Association:**
   * **Supported.** Multiple `ride_requests` can have `trip_id = trip.id`.
   * The database correctly associates up to `seat_capacity` passengers with a single trip.
2. **Batch Window / Phase Tracking:**
   * **NOT Supported.** There is no column for `batch_window_start`, `batch_departure_time`, or `batch_status` (`COLLECTING`, `LOCKED`, `DISPATCHED`).
3. **Ordered Stop Sequence Representation:**
   * **NOT Supported.** There is no `trip_stops` table and no `sequence_order` column on `ride_requests`. Stop sequence exists only ephemerally in `GreedyNearestNeighbourOptimizer` memory and is lost the moment the HTTP response finishes.
4. **Pickup vs. Drop-Off Precedence:**
   * **NOT Supported.** As proven in Task 7.3, `ActiveTripStop` and `Stop` only hold pickup coordinates. Drop-off coordinates (`destination_lat/lng`) are never sequenced, and `StopType.DROPOFF` does not exist.

---

## 9. Current Route Optimization Capability

Audit of [GreedyNearestNeighbourOptimizer.kt](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/GreedyNearestNeighbourOptimizer.kt) usage in production:

| Dimension | Research Simulation Strategy | Production `matchRideRequest` | Production `getStopList` |
| :--- | :--- | :--- | :--- |
| **Input Stops Count** | Complete batch ($N \le 4$ stops) | **1 stop only** (`listOf(StopCandidate)`) | Active pickups ($N \le \text{capacity}$) |
| **Existing Trip Stops Included?** | Yes, all pending pickups in batch | **NO** (Existing stops ignored) | Yes (All `MATCHED` + `PICKUP_PENDING`) |
| **Drop-Offs Included?** | Yes (Campus hub destination) | **NO** | **NO** |
| **Output Ordering Retained?** | Yes, drives simulated vehicle route | **NO (Discarded immediately)** | Yes (Returned in HTTP DTO) |
| **Output Persisted?** | In-memory vehicle route | **NO** | **NO** |
| **ETA Retained?** | Computed per stop step | Yes (Extracted for new request) | Ephemeral in response |
| **Broadcast to Driver?** | Simulation event | **NO** | **NO** (Driver must poll) |

**Conclusion:**
In `RoutingService.matchRideRequest()`, the optimizer is **not performing route optimization**; it is operating as a **point-to-point ETA calculation helper**. In `TripService.getStopList()`, it sequences active pickups, but fails to sequence drop-offs.

---

## 10. Production Vehicle State

What the production system knows about a vehicle at any given moment:

| Vehicle State Dimension | Exists in DB? | Table & Column | Updated When | Used for Dispatch? | Required by Research? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Current Latitude** | YES | `vehicles.last_known_lat` | Driver WS frame (~5s) | YES (Spatial query) | YES |
| **Current Longitude** | YES | `vehicles.last_known_lng` | Driver WS frame (~5s) | YES (Spatial query) | YES |
| **Location Timestamp** | YES | `vehicles.last_location_updated_at` | Driver WS frame (~5s) | NO | NO |
| **Seat Capacity** | YES | `vehicles.seat_capacity` | Static config | YES (Capacity filter) | YES |
| **Current Passenger Load** | Computed | Subquery on `ride_requests` | On seat allocation | YES | YES |
| **Remaining Seats** | Computed | `seat_capacity - activeCount` | On seat allocation | YES | YES |
| **Vehicle Operational Status**| YES | `vehicles.status` (`ACTIVE`) | Static config | YES | YES |
| **Active Trip ID** | YES | `trips.vehicle_id` | Manual DB update | YES | YES |
| **Assigned Driver** | YES | `trips.driver_id` | Manual DB update | NO | YES |
| **Future Availability Time** | **NO** | None | N/A | **NO** | **CRITICAL (Research relies on `availableAtSeconds`)** |
| **Batch Departure Time** | **NO** | None | N/A | **NO** | **CRITICAL (Research relies on `batchDepartureTime`)** |
| **Current Route Stops** | **NO** | None (Ephemeral only) | N/A | **NO** | **CRITICAL** |
| **Destination Campus Hub** | **NO** | None | N/A | **NO** | **CRITICAL** |

---

## 11. Trip Lifecycle Feasibility

### 11.1 The Operational Lifecycle Trap:
In Task 7.2, the trip lifecycle was audited:

```text
Schedule Generator ──► Creates Trip (status='SCHEDULED', vehicle_id=null, driver_id=null)
                                  │
                                  ▼
      [ MISSING AUTOMATION: No service assigns vehicle/driver ]
      [ MISSING AUTOMATION: No service transitions SCHEDULED -> IN_PROGRESS ]
                                  │
                                  ▼
Ride Matching (findAvailableNear) requires:
   WHERE t.status = 'IN_PROGRESS' AND v.last_known_lat IS NOT NULL
                                  │
                                  ▼
               Result: ZERO ELIGIBLE VEHICLES FOUND
                                  │
                                  ▼
                   All ride requests WAITLISTED
```

### 11.2 Requirements for Dynamic Batching:
For dynamic batching to function:
1. Trips must be automatically bound to vehicles and drivers prior to departure.
2. A trip lifecycle worker must transition trips from `SCHEDULED` $\to$ `IN_PROGRESS` at scheduled departure or upon batch formation.
3. Upon all drop-offs being completed, trips must transition to `COMPLETED`, freeing vehicle capacity for the next batch run.

---

## 12. Real-Time Dispatch Feasibility

As established in Task 7.4:
* `/ws/driver/{tripId}`: Strictly an inbound GPS ingestion pipe. The server **never pushes dispatch frames or route updates to the driver**.
* `/ws/trip/{tripId}`: Strictly an outbound GPS relay pipe to riders. The server **never pushes trip status transitions, boarding notices, or route alterations**.

### Feasibility Assessment:
* **Can a newly batched stop be pushed to the driver via WebSockets today?** **NO.**
* **Can route changes be broadcast to passengers via WebSockets today?** **NO.**
* To support the research behavior in production, the WebSocket subsystem must transition from a passive GPS repeater to an **event-driven bidirectional dispatch channel**.

---

## 13. Concurrency & Race Condition Audit

### Scenario: Requests A, B, and C arrive simultaneously within 100ms.

1. **Step 1: Vehicle Discovery (`findAvailableNear`)**
   * All three requests execute `findAvailableNear()` concurrently.
   * Since this is a read query using raw JDBC without table locks, all three requests receive the **same candidate vehicle** (`veh-1` on `trip-1` with 2 available seats).
2. **Step 2: Contention on `allocateSeat`**
   * Request A reaches `allocateSeat()` first and executes `SELECT ... FOR UPDATE OF t`.
   * Requests B and C attempt the same query and are **blocked by PostgreSQL row-level lock** on `trips.id`.
3. **Step 3: Sequential Execution**
   * Request A counts active passengers ($2/4$), inserts its record, commits, and releases the lock.
   * Request B unblocks, re-counts active passengers ($3/4$), inserts its record, commits, and releases the lock.
   * Request C unblocks, re-counts active passengers ($4/4$), detects `activeCount >= seatCapacity`, rolls back, and returns `AllocationResult.CapacityExhausted`.
4. **Step 4: Fallback**
   * Request C tries the next vehicle in its candidate list or falls back to `WAITLISTED`.

### Verdict on Concurrency:
* **Seat Allocation Safety:** **100% SOUND.** The row-level lock guarantees zero over-allocation.
* **Batching Safety:** **UNSUPPORTED.** If requests arrive concurrently, rather than batching them together into a unified optimal route, the current architecture allocates them greedily one-by-one. The first requests grab seats; subsequent requests overflow to waitlists without holistic route optimization.

---

## 14. Recommended Dispatch Insertion Point

To integrate dynamic batching without violating separation of concerns, three architectural insertion options were evaluated:

### Option 1: Direct Integration into `RoutingService`
* *Mechanism:* Add a coroutine delay or buffer inside `RoutingService.matchRideRequest()`.
* *Evaluation:* **REJECTED.** Blocking the HTTP request thread of `POST /rides` for 300 seconds is unacceptable for HTTP REST ergonomics. Mobile clients would time out or experience extreme latency.

### Option 2: Database-Backed Dispatch Queue with Background Worker (RECOMMENDED)
* *Mechanism:*
  1. `POST /rides` immediately writes the ride request as `status = 'QUEUED'` (or `REQUESTED`) and responds HTTP 202 Accepted.
  2. A dedicated `BatchDispatchService` running as an asynchronous coroutine worker periodically (every 5-10s) collects requests within the 300s window.
  3. The worker performs fleet selection, executes `GreedyNearestNeighbourOptimizer`, commits the batched trip stops, and broadcasts dispatch events over WebSockets.
* *Evaluation:* **STRONGLY RECOMMENDED.**
  * Clean separation of concerns (API ingestion vs. dispatch orchestration).
  * Non-blocking HTTP endpoints.
  * Resilient to application restarts (queue state persisted in PostgreSQL).
  * Full alignment with the research simulation model.

### Option 3: Event-Driven Reactive Message Bus (Redis / Kafka)
* *Mechanism:* Introduce an external pub/sub broker.
* *Evaluation:* **OVERENGINEERED for Phase 7.** Introduces unneeded infrastructure complexity before core domain batching logic is established.

---

## 15. Research-to-Production Gap Matrix

| Research Capability | Production Implementation | Match? | Architectural Gap | Required Component |
| :--- | :--- | :--- | :--- | :--- |
| **Dynamic Request Ingestion**| `POST /rides` | **YES** | Requests arrive dynamically over HTTP | None |
| **300s Batch Accumulation** | Online immediate evaluation | **NO** | Requests processed one-by-one; no delay buffer | `BatchingBuffer` / Dispatch Queue |
| **Nearest Eligible Vehicle** | `findAvailableNear` spatial query | **Partial**| Production uses PostGIS, but requires `IN_PROGRESS` trip | Availability-aware vehicle query |
| **Capacity Enforcement** | Pessimistic row locking | **YES** | Row lock prevents double-allocation | None (Sound foundation) |
| **Vehicle Schedule Availability**| `availableAtSeconds` tracking | **NO** | Production has no future time tracking | `VehicleAvailabilityTracker` |
| **Multi-Passenger Batch** | In-memory batch list | **Partial**| DB associates multiple requests with a trip, but no batch phase | `BatchState` model & columns |
| **Pickup Stop Sequencing** | `GreedyNearestNeighbourOptimizer` | **YES** | Algorithm exists and is functional | Adapter integration |
| **Drop-Off Sequencing** | Campus hub drop-off | **NO** | Destination coordinates omitted from manifest | Stop model with `StopType.DROPOFF` |
| **Route State Persistence** | Simulated vehicle itinerary | **NO** | Routes are ephemeral; never saved to DB | `trip_stops` database table |
| **ETA Recalculation** | Recalculated per batch cycle | **NO** | Single ETA computed on booking; never updated | Dynamic ETA recalculation engine |
| **Driver Push Dispatch** | Programmatic simulation steps | **NO** | Driver WebSocket is receive-only; driver must poll | Server-to-Driver WebSocket messaging |
| **Passenger Push Notification** | Simulation event log | **NO** | Passenger must poll HTTP `GET /rides/{id}` | Passenger WebSocket event broadcast |
| **Automated Trip Lifecycle**| State machine execution | **NO** | Deadlocked: scheduled trips have null vehicles | `TripLifecycleService` / Worker |
| **Deterministic Tie-Breaking**| `thenBy { vehicleId }` | **NO** | Arbitrary database row ordering | SQL `ORDER BY ... vehicle_id ASC` |

---

## 16. Production Readiness Classification

Based strictly on verified architectural and algorithmic evidence:

### **Classification: C — Requires a substantial new dispatch/batching subsystem**

### Justification:
* **Why not A or B?** The system cannot support research batching with minor tweaks. Key architectural prerequisites (request queueing, batch windows, route persistence, drop-off sequencing, trip lifecycle transitions, and push messaging) are completely missing.
* **Why not D?** A complete rewrite from scratch is unnecessary. The core domain models (`trips`, `vehicles`, `ride_requests`), the PostgreSQL spatial indexing, the pessimistic seat-allocation locking, and the stop ordering algorithm (`GreedyNearestNeighbourOptimizer`) are structurally sound and can serve as the foundation for the dispatch subsystem.

---

## 17. Minimum Future Architecture

The minimum viable production architecture required to realize the research behavior:

```text
                      [ Employee App ]
                             │
                             ▼ HTTP POST /rides
                   [ Ride Ingestion Route ]
                             │
                             ▼ Writes status='QUEUED'
                 [ PostgreSQL: ride_requests ]
                             │
                             ▼ Polled periodically (e.g. every 5s)
               [ Batching & Dispatch Service ]
    ┌────────────────────────┴────────────────────────┐
    ▼                                                 ▼
1. Collect Requests in 300s Window         2. Vehicle Availability Engine
   - Group by Company & Direction             - Query active & idle vehicles
   - Cap batch at vehicleCapacity (4)         - Evaluate remaining capacity
    └────────────────────────┬────────────────────────┘
                             ▼
             3. Nearest Vehicle Fleet Assignment
                - PostGIS distance + tie-break
                             ▼
             4. Route Optimization Engine
                - Sequence Pickups + Drop-Offs
                - Compute stop ETAs
                             ▼
             5. State Persistence (Atomic DB Transaction)
                - Transition ride_requests -> 'MATCHED'
                - Write ordered sequence to trip_stops table
                - Transition trips -> 'IN_PROGRESS'
                             │
    ┌────────────────────────┴────────────────────────┐
    ▼                                                 ▼
6. Push Driver Manifest                     7. Push Passenger Notifications
   - Over /ws/driver/{tripId}                  - Over /ws/trip/{tripId}
   - New stops & route itinerary               - Vehicle assigned, pickup ETA
```

---

## 18. Severity-Ranked Findings

### CRITICAL
1. **Zero Batch Formation Capability (Immediate Online Processing):**
   The production system lacks any mechanism to buffer requests across a 300-second window. Requests are immediately processed individually, preventing the system from achieving the ride-sharing efficiencies demonstrated in research.
2. **Missing Stop Persistence & Drop-Off Routing:**
   The database has no schema to persist ordered routes, and the routing service omits drop-off destinations. Production cannot guide a vehicle through multiple pickups and drop-offs.
3. **Trip Lifecycle Operational Deadlock:**
   Schedules generate trips with null vehicles that remain `SCHEDULED` indefinitely, preventing the spatial query from finding active vehicles for dynamic matching.

### HIGH
4. **WebSocket Push Inability:**
   WebSockets are strictly inbound GPS ingestion pipes. The backend cannot push dispatch orders, stop additions, or ETA changes to drivers or passengers in real time.
5. **Missing Vehicle Availability Time Tracking:**
   Unlike the simulation which tracks `availableAtSeconds` to dispatch vehicles immediately upon completing prior trips, production has no temporal availability model.

### MEDIUM
6. **Non-Deterministic Spatial Tie-Breaking:**
   `findAvailableNear` orders by distance without a deterministic secondary sort key (`vehicle_id`), leading to arbitrary vehicle selection when equidistant candidates exist.
7. **Orphaned Waitlists:**
   Requests that fail immediate seat allocation are marked `WAITLISTED` and abandoned. No background worker revisits them when vehicle capacity frees up.

---

## 19. Exact File, Class, and Function References

* **Routing Entry Point:** [Routing.kt:135](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/plugins/Routing.kt#L135)
* **Ride Booking Route:** [RideRoutes.kt:20-38](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/RideRoutes.kt#L20-L38) (`POST /rides`)
* **Online Matching Logic:** [RoutingService.kt:29-72](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt#L29-L72) (`matchRideRequest`)
* **Spatial Vehicle Search:** [PostgresVehicleRepository.kt:46-130](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresVehicleRepository.kt#L46-L130) (`findAvailableNear`)
* **Pessimistic Seat Allocation:** [PostgresRideRequestRepository.kt:26-131](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt#L26-L131) (`allocateSeat`)
* **Greedy Stop Sequencer:** [GreedyNearestNeighbourOptimizer.kt:24-67](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/GreedyNearestNeighbourOptimizer.kt#L24-L67) (`optimize`)
* **Driver Stop Manifest Route:** [TripRoutes.kt:29-63](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/TripRoutes.kt#L29-L63) (`GET /trips/{id}/stops`)
* **Driver Stop Retrieval Logic:** [TripService.kt:36-63](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/TripService.kt#L36-L63) (`getStopList`)
* **WebSocket Ingestion & Relay:** [WebSocketRoutes.kt:34-100](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/WebSocketRoutes.kt#L34-L100) (`websocketRoutes`)
* **Research Simulation Strategy:** [DynaShuttleSimulationStrategy.kt:34-190](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/experiments/DynaShuttleSimulationStrategy.kt#L34-L190) (`run`)

---

## 20. Safety / Verification Status

* **Production Code Modified:** NO
* **Database Modified:** NO
* **Migrations Modified:** NO
* **Experiment Code Modified:** NO
* **Frozen Tasks 4.1–6.4 Modified:** NO
* **Previous Audit Reports Modified:** NO
* **Verification Executed:**
  * `git status --short` verified (only audit document created)
  * `git diff --check` executed and clean
  * Gradle test suite run non-destructively: 320 passed, 0 failed, 0 skipped
