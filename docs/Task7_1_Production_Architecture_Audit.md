# Task 7.1 — Production Architecture & Request-to-Trip Flow Audit

**Document Type:** Production Architecture Audit & System Flow Trace  
**Target Repository:** `dynashuttle-backend` (`c:\project-shuttles-2026\dynashuttle-backend`)  
**Audit Mode:** STRICT READ-ONLY (No production code, schema, migrations, routes, tests, configuration, or experiment files modified)  
**Baseline Date:** October 2026  
**System Under Test:** DynaShuttle Production Backend (Kotlin 1.9.22 / Ktor 2.3.8 / PostgreSQL 17 + PostGIS / Exposed 0.50.1 / Netty) vs. DynaShuttle Research Simulation Engine (Tasks 6.1–6.4)

---

## 1. Executive Summary

This audit evaluates the actual production implementation of DynaShuttle against the conceptual routing and dispatching model evaluated in our research experiments (Tasks 6.1–6.4).

### Main Question Answered
> **Does the current DynaShuttle production backend implement the same conceptual routing behavior that we evaluated experimentally, or are there important differences between the research model and the real production system?**

**Definitive Finding:** **No, the current DynaShuttle production backend does NOT implement the same conceptual routing behavior evaluated in the research experiments.** There are fundamental architectural and algorithmic differences between the two systems across demand handling, vehicle selection, waypoint sequencing, trip lifecycle, and real-time execution.

### Key Summary Findings
1. **Fleet Dispatcher vs. Local Sequencer:** The experimental DynaShuttle strategy functions as a centralized fleet dispatcher operating on a discrete simulation timeline. It accumulates requests into time-windowed batches (default 300s batch window) and assigns each batch to the closest eligible vehicle across the fleet. In contrast, the production backend ([`RoutingService.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt)) has **no batching window, no request accumulator, and no fleet-level assignment**. It processes requests online, one-at-a-time, performing an immediate spatial search for a vehicle already operating an `IN_PROGRESS` trip within a fixed 2,000-meter radius.
2. **Optimizer Scope and Destination Handling:** In production, [`GreedyNearestNeighbourOptimizer`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/GreedyNearestNeighbourOptimizer.kt) is purely a local single-vehicle waypoint re-orderer. When an employee requests a ride (`POST /rides`), the optimizer is called with only **one single stop candidate** (the newly submitted pickup point) purely to estimate a preliminary pickup ETA; the resulting sequence is discarded. Multi-stop reordering is only executed lazily when a driver calls `GET /trips/{id}/stops`. Crucially, **passenger destinations (`destinationLat`, `destinationLng`) are NEVER passed to the optimizer in production**—only pickup coordinates are routed.
3. **Trip Operational Deadlock:** Production models a recurring schedule template entity (`ShuttleSchedules`) and can generate calendar trips (`POST /shuttle-schedules/{scheduleId}/trips`), but trips are generated with `status = 'SCHEDULED'`, `vehicle_id = null`, and `driver_id = null`. **There is no API endpoint, service method, or automated worker in the entire backend to assign vehicles or drivers to trips, or to transition trips from `SCHEDULED` to `IN_PROGRESS`**. Because [`PostgresVehicleRepository.findAvailableNear()`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresVehicleRepository.kt) queries only vehicles linked to `IN_PROGRESS` trips, any system running without manual, external database inserts will find 0 eligible vehicles, immediately dumping every ride request into `WAITLISTED`.
4. **WebSocket Telemetry Decoupling:** Production WebSockets (`/ws/driver/{tripId}` and `/ws/trip/{tripId}`) function exclusively as a unidirectional telemetry pipeline (driver sends GPS coordinates; coordinates are saved to `Vehicles.lastKnownLat/Lng` and forwarded to trip listeners). **No dispatch events, match notifications, or route update events are broadcast over WebSockets**.
5. **Architectural Strengths:** The codebase demonstrates high-quality interface segregation (ISP) and explicit transactional locking. When candidate vehicles are available, [`PostgresRideRequestRepository.allocateSeat`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt#L18-L131) uses pessimistic row-level locking (`SELECT ... FOR UPDATE OF t`) to enforce capacity invariants and prevent overbooking under high concurrency.

---

## 2. Production Architecture Overview

The production backend is structured as a modular Ktor server application configured in [`Application.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/Application.kt) and [`Routing.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/plugins/Routing.kt). It enforces strict Dependency Inversion (DIP) and Interface Segregation (ISP): routes and domain services depend strictly on narrow interfaces rather than concrete database classes or third-party HTTP clients.

### Component Map
* **Application Entry Point:** [`Application.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/Application.kt) boots Netty on port 8080, initializes HikariCP via `DatabaseFactory.init()`, and installs serialization, CORS, StatusPages, JWT authentication, WebSockets, and routing.
* **Dependency Wiring Hub:** [`Routing.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/plugins/Routing.kt#L70-L142) is the single composition root. It constructs singleton repository instances (`PostgresVehicleRepository`, `PostgresRideRequestRepository`, `PostgresAuthRepository`, `PostgresPickupZoneRepository`, `PostgresShuttleScheduleRepository`, `PostgresTripRepository`), inspects environment variables (`TOMTOM_API_KEY`, `BREVO_API_KEY`, `JWT_SECRET`), and wires them into services.
* **Authentication Realms:** [`Authentication.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/plugins/Authentication.kt#L17-L42) defines two JWT realms:
  * `app-user`: For corporate commuters (`role == "employee"`) and shuttle drivers (`role == "driver"`).
  * `admin`: For corporate fleet administrators (`role == "admin"`).
* **Domain Services:**
  * [`RoutingService`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt): Orchestrates real-time ride matching and status lookups.
  * [`TripService`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/TripService.kt): Handles driver-facing stop manifests and passenger boarding transitions.
  * [`RideRequestStateMachine`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RideRequestStateMachine.kt): Central source of truth for ride request lifecycle transitions.
  * [`ShuttleScheduleService`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/ShuttleScheduleService.kt): Manages recurring schedule templates and calendar trip generation.
  * [`PickupZoneService`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/PickupZoneService.kt): Manages corporate pickup zones and employee zone assignments.
  * [`AuthService`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/AuthService.kt): Handles OTP generation, verification, and BCrypt admin authentication.
* **Routing & Optimization Engines:**
  * [`GreedyNearestNeighbourOptimizer`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/GreedyNearestNeighbourOptimizer.kt): Implements `RouteOptimizer` using straight-line Haversine math for sequencing.
  * [`TomTomEtaEstimator`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/TomTomEtaEstimator.kt): Calls TomTom Routing API v1 (`traffic=true`) with automatic Haversine fallback.
  * [`HaversineEtaEstimator`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/HaversineEtaEstimator.kt): Fallback estimator assuming constant 25 km/h urban speed.

---

## 3. Request-to-Trip Flow

Tracing a corporate employee requesting a ride through the production system:

```mermaid
sequenceDiagram
    autonumber
    actor Emp as Employee App
    participant RR as RideRoutes (POST /rides)
    participant Auth as JWT Auth ("app-user")
    participant RS as RoutingService.matchRideRequest()
    participant VR as PostgresVehicleRepository
    participant DB as PostgreSQL 17 + PostGIS
    participant RRR as PostgresRideRequestRepository
    participant Opt as GreedyNearestNeighbourOptimizer
    participant ETA as TomTom / Haversine ETA

    Emp->>RR: POST /rides {employeeId, pickupLat/Lng, destLat/Lng}
    RR->>Auth: Verify JWT claim (role == "employee")
    Note over RR: IDOR check: token.userId == request.employeeId
    RR->>RS: matchRideRequest(request, companyId)
    RS->>VR: findAvailableNear(pickupLat, pickupLng, 2000m, companyId)
    VR->>DB: SQL: ST_DWithin(v.loc, req.loc) WHERE t.status='IN_PROGRESS' AND available_seats > 0
    DB-->>VR: List<VehicleCandidate> (Ordered nearest-first)
    VR-->>RS: candidates

    alt Candidates Exist (at least 1 active vehicle within 2km)
        loop For each candidate
            RS->>RRR: allocateSeat(request, candidate.tripId, companyId)
            RRR->>DB: BEGIN TX -> SELECT ... FROM trips t WHERE t.id=? FOR UPDATE OF t
            RRR->>DB: SELECT COUNT(*) FROM ride_requests WHERE trip_id=? AND status IN ('MATCHED', 'PICKUP_PENDING', 'ONBOARD')
            alt Active Count < Seat Capacity
                RRR->>DB: INSERT INTO ride_requests (status='MATCHED', trip_id=t.id)
                DB-->>RRR: COMMIT TX
                RRR-->>RS: AllocationResult.Allocated(stored)
                RS->>Opt: optimize(candidate.lat, candidate.lng, stops=[StopCandidate(newRequest)])
                Opt->>ETA: estimateMinutes(candidate.lat/lng -> pickup.lat/lng)
                ETA-->>Opt: etaMinutes
                Opt-->>RS: List<OptimizedStop> (Size = 1)
                RS-->>RR: RideResponseDto(status="MATCHED", assignedVehicleId, etaMinutes)
                RR-->>Emp: HTTP 201 Created {status: "MATCHED", ...}
            else Capacity Full
                RRR-->>RS: AllocationResult.CapacityExhausted (try next candidate)
            end
        end
    else No Candidates Found (or all candidates exhausted)
        RS->>RRR: save(request, tripId=null, status="WAITLISTED")
        RRR->>DB: INSERT INTO ride_requests (status='WAITLISTED', trip_id=NULL)
        DB-->>RRR: StoredRideRequest
        RS-->>RR: RideResponseDto(status="WAITLISTED")
        RR-->>Emp: HTTP 201 Created {status: "WAITLISTED"}
    end
```

### Granular Answers to Traceability Questions

#### Employee Request
* **Endpoint:** `POST /rides` ([RideRoutes.kt:21](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/RideRoutes.kt#L21)).
* **HTTP Method:** `POST`.
* **Request Body Accepted:** `RideRequestDto` ([Dto.kt:6-13](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Dto.kt#L6-L13)):
  ```kotlin
  data class RideRequestDto(
      val employeeId: String,
      val pickupLat: Double,
      val pickupLng: Double,
      val destinationLat: Double,
      val destinationLng: Double,
      val pickupZoneId: String? = null
  )
  ```
* **Authentication / Identity Used:** `authenticate("app-user")`. Requires valid JWT containing claims `userId`, `role`, and `companyId`. Enforces `user.role == "employee"` and verifies IDOR integrity (`request.employeeId == user.userId`) ([RideRoutes.kt:26-33](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/RideRoutes.kt#L26-L33)).
* **Service Handling Request:** `RoutingService.matchRideRequest(request, companyId = user.companyId)` ([RoutingService.kt:41](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt#L41)).
* **Repository Writing Request:** `PostgresRideRequestRepository` (via interfaces `RideMatchingRepository` and `RideRequestWriter`) ([PostgresRideRequestRepository.kt:18, 133](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt#L18)).
* **Database Record Created:** Row in table `ride_requests` with UUID primary key, `employee_id`, `pickup_lat`, `pickup_lng`, `destination_lat`, `destination_lng`, `pickup_zone_id`, `requested_at = Instant.now()`.
* **Initial Status Assigned:**
  * If candidate vehicle has seats: `MATCHED` (and `trip_id` set to candidate's `tripId`).
  * If no vehicle available: `WAITLISTED` (and `trip_id = null`).

#### Vehicle Assignment
* **Where Selection Happens:** Inside `RoutingService.matchRideRequest()` ([RoutingService.kt:42-50](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt#L42-L50)).
* **Class/Function Performing It:** `PostgresVehicleRepository.findAvailableNear()` ([PostgresVehicleRepository.kt:46-130](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresVehicleRepository.kt#L46-L130)).
* **Timing:** Immediate (online, single-request matching). There is **no batching window** and **no request accumulation**.
* **Candidate Selection Criteria:**
  * Must be joined to a trip where `trips.status = 'IN_PROGRESS'`.
  * `vehicles.last_known_lat` and `last_known_lng` must be non-null.
  * Must match caller's `company_id`.
  * Must fall within `searchRadiusMeters` (default 2,000m) via PostGIS `ST_DWithin`.
  * Must have `available_seats > 0` (`seat_capacity - active_passengers > 0`).
  * Ordered nearest-first by straight-line spherical distance (`ST_Distance`).
* **Factors Considered:**
  * Vehicle availability: Yes, via `trips.status = 'IN_PROGRESS'`.
  * Current location: Yes, via PostGIS `ST_DWithin`.
  * Capacity: Yes, via SQL subquery count and pessimistic row lock.
  * Current trip: Yes, strictly requires `t.status = 'IN_PROGRESS'`.
  * Driver status: Not checked; presence of `t.driver_id` on active trip is assumed.
  * Company/Tenant: Yes, filtered via `v.company_id = ?`.
  * ETA: **No, ETA is NOT considered during vehicle selection.**
* **Determinism:** Yes, PostGIS ordering by `ST_Distance` is deterministic.
* **Nearest-Vehicle Logic:** Yes, strictly Euclidean straight-line nearest.

#### Route Generation
* **Where Invoked:**
  1. In `RoutingService.matchRideRequest()` ([line 53](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt#L53)) upon ride booking.
  2. In `TripService.getStopList()` ([line 44](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/TripService.kt#L44)) when a driver polls for stops.
* **Optimizer Used:** `GreedyNearestNeighbourOptimizer` ([GreedyNearestNeighbourOptimizer.kt:25](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/GreedyNearestNeighbourOptimizer.kt#L25)).
* **Stops Passed:**
  * During matching: **Only 1 single stop** (the new request's pickup). Existing vehicle stops are ignored.
  * During driver stop list retrieval: All requests on that trip in status `MATCHED` or `PICKUP_PENDING`.
* **Scope:** Per-vehicle only. It does **not** perform fleet-level assignment.
* **Stop Ordering:** Orders stops using straight-line Haversine math.
* **Coordinates Used:** Uses **only pickup coordinates** (`pickupLat`, `pickupLng`). **Destination coordinates (`destinationLat`, `destinationLng`) are NEVER passed to the optimizer.**
* **ETA / Travel Time:** Haversine distance is used to decide the sequence order. TomTom or Haversine ETA is used only to calculate the leg duration in minutes once the stop is chosen.
* **Recalculation:** Recalculated dynamically on-demand only when `GET /trips/{id}/stops` is called.

#### Trip Creation
* **Where Trips Are Created:** In `ShuttleScheduleService.generateTrip()` ([ShuttleScheduleService.kt:198-256](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/ShuttleScheduleService.kt#L198-L256)), exposed via `POST /shuttle-schedules/{scheduleId}/trips` ([ShuttleScheduleRoutes.kt:169-209](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/ShuttleScheduleRoutes.kt#L169-L209)).
* **Linked Vehicle:** `null` ([ShuttleScheduleService.kt:247](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/ShuttleScheduleService.kt#L247)).
* **Linked Driver:** `null` ([ShuttleScheduleService.kt:248](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/ShuttleScheduleService.kt#L248)).
* **Linked Ride Requests:** None at creation.
* **Initial Trip Status:** `"SCHEDULED"` ([ShuttleScheduleService.kt:250](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/ShuttleScheduleService.kt#L250)).
* **Route Information Persisted:** None.
* **Timestamps Stored:** `tripDate` (Date), `startTime` (Timestamp calculated from schedule `departureTime` in application timezone).

#### Trip Execution
* **Trip Statuses in DB:** `SCHEDULED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED` (enforced by `chk_trips_status` in V2 migration).
* **Trip Status Transitions in Code:** **NOT IMPLEMENTED / NOT FOUND.** There is no code in the backend that transitions a trip from `SCHEDULED` to `IN_PROGRESS` or from `IN_PROGRESS` to `COMPLETED`.
* **Ride Request Transitions:**
  * Driver marks pickup: `POST /trips/{id}/stops/{stopId}/pickup` transitions request `PICKUP_PENDING` $\rightarrow$ `ONBOARD` ([TripRoutes.kt:69-103](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/TripRoutes.kt#L69-L103)).
  * Driver marks no-show: `POST /trips/{id}/stops/{stopId}/no-show` transitions request `PICKUP_PENDING` $\rightarrow$ `NO_SHOW` ([TripRoutes.kt:109-143](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/TripRoutes.kt#L109-L143)).
  * Driver marks completion: `POST /trips/{id}/stops/{stopId}/complete` transitions request `ONBOARD` $\rightarrow$ `COMPLETED` ([TripRoutes.kt:149-183](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/TripRoutes.kt#L149-L183)).
  * Employee cancels: `POST /rides/{id}/cancel` transitions request to `CANCELLED` ([RideRoutes.kt:57-83](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/RideRoutes.kt#L57-L83)).
* **Vehicle State Changes:** Vehicle coordinates (`last_known_lat/lng`) change only when the driver app pushes GPS updates via `/ws/driver/{tripId}` ([WebSocketRoutes.kt:63-66](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/WebSocketRoutes.kt#L63-L66)).

---

## 4. Vehicle Assignment Flow

Detailed analysis of vehicle selection mechanics in production:

1. **Class and Function:** `PostgresVehicleRepository.findAvailableNear(lat, lng, radiusMeters, companyId)` called by `RoutingService.matchRideRequest()`.
2. **Timing:** Instantaneous online execution upon HTTP request receipt.
3. **Considered Vehicles:** Only vehicles meeting the strict SQL criteria:
   ```sql
   SELECT v.id AS vehicle_id, t.id AS trip_id, t.driver_id, v.seat_capacity,
          (v.seat_capacity - COALESCE((
              SELECT COUNT(*) FROM ride_requests rr
              WHERE rr.trip_id = t.id AND rr.status IN ('MATCHED', 'PICKUP_PENDING', 'ONBOARD')
          ), 0)) AS available_seats,
          v.last_known_lat, v.last_known_lng, v.company_id
   FROM trips t
   JOIN vehicles v ON v.id = t.vehicle_id
   WHERE t.status = 'IN_PROGRESS'
     AND v.last_known_lat IS NOT NULL
     AND v.last_known_lng IS NOT NULL
     AND v.company_id = ?
     AND ST_DWithin(ST_MakePoint(v.last_known_lng, v.last_known_lat)::geography,
                    ST_MakePoint(?, ?)::geography, ?)
     AND (v.seat_capacity - active_count) > 0
   ORDER BY ST_Distance(ST_MakePoint(v.last_known_lng, v.last_known_lat)::geography,
                        ST_MakePoint(?, ?)::geography)
   ```
4. **Availability & Status:** Vehicles not assigned to an active trip with `status = 'IN_PROGRESS'` are **completely invisible** to the matcher.
5. **Driver Status:** Ignored; assumed valid if `t.driver_id` exists.
6. **Company Isolation:** Strictly enforced by filtering `v.company_id = ?` using caller's JWT `companyId`.
7. **ETA & Travel Time:** **Completely ignored during vehicle selection.** Vehicles are ordered solely by PostGIS straight-line geographical distance.
8. **Fallback when No Vehicle Available:** The request is inserted into `ride_requests` with `status = 'WAITLISTED'` and `trip_id = null`.
9. **Concurrency Handling:** When multiple requests arrive simultaneously for the same vehicle, `PostgresRideRequestRepository.allocateSeat()` acquires a pessimistic row-level lock on the trip (`SELECT ... FROM trips WHERE id = ? FOR UPDATE OF t`), re-evaluates available capacity, and rejects excess requests with `AllocationResult.CapacityExhausted`, directing them to the next vehicle candidate or waitlist.

---

## 5. Capacity Enforcement

* **Capacity Storage:** `vehicles.seat_capacity` (integer column on `vehicles` table, constrained $> 0$ by check constraint `chk_vehicles_seat_capacity`).
* **Occupancy Storage:** Not stored as a counter column. Dynamically computed via SQL subquery:
  ```sql
  SELECT COUNT(*) FROM ride_requests
  WHERE trip_id = ? AND status IN ('MATCHED', 'PICKUP_PENDING', 'ONBOARD')
  ```
* **Enforcement Points:**
  1. *Preliminary filtering:* In `findAvailableNear()` (`available_seats > 0`).
  2. *Authoritative allocation:* In `allocateSeat()` under pessimistic row lock (`FOR UPDATE OF t`).
* **Transactional Guarantee:** Fully transactional. Row locking prevents race conditions and overbooking. Verified in unit tests ([`CapacityConcurrencyTest.kt:253-326`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/test/kotlin/com/dynashuttle/CapacityConcurrencyTest.kt#L253-L326)), where 12 concurrent requests for 4 seats result in exactly 4 `MATCHED` and 8 `WAITLISTED`.
* **Trip Execution Check:** Capacity is not checked when a passenger boards because `MATCHED` status already holds a seat reservation.
* **Exhaustion Behavior:** Returns `AllocationResult.CapacityExhausted`. `RoutingService` attempts the next nearest candidate; if all fail, status becomes `WAITLISTED`.

---

## 6. Route Optimization Flow

* **Invocation Points:**
  * Request matching: [`RoutingService.kt:53`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt#L53).
  * Stop list retrieval: [`TripService.kt:44`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/TripService.kt#L44).
* **Interface & Implementation:**
  * Interface: [`RouteOptimizer`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/RouteOptimizer.kt).
  * Implementation: [`GreedyNearestNeighbourOptimizer`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/GreedyNearestNeighbourOptimizer.kt).
* **Scope:** Single vehicle only (`vehicleLat`, `vehicleLng`, `stops`). It is **not** a fleet dispatcher.
* **Stop Selection:** Repeatedly selects the unvisited stop with the minimum straight-line Haversine distance from the current vehicle position:
  $$\text{nearest} = \arg\min_{s \in \text{remaining}} \text{haversine}(current, s)$$
* **Missing Destinations:** The optimizer only accepts `StopCandidate(rideRequestId, lat, lng)` ([RouteOptimizer.kt:13-17](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/RouteOptimizer.kt#L13-L17)). `ActiveTripStop` only maps `pickupLat/Lng` ([RideRequestRepository.kt:24-30](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/RideRequestRepository.kt#L24-L30)). **Destination coordinates are never passed to the optimizer.**
* **Recalculation:** Route is recalculated on-the-fly whenever the driver calls `GET /trips/{id}/stops`. Route order is not persisted in the database.

---

## 7. ETA Flow

* **Interface:** [`EtaEstimator`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/EtaEstimator.kt#L22-L24):
  ```kotlin
  interface EtaEstimator {
      suspend fun estimateMinutes(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Int
  }
  ```
* **Implementations:**
  1. [`TomTomEtaEstimator`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/TomTomEtaEstimator.kt#L48-L82): Queries TomTom Routing API v1 (`calculateRoute`, `traffic=true`) over HTTP using Ktor CIO client.
  2. [`HaversineEtaEstimator`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/HaversineEtaEstimator.kt#L21-L41): Straight-line calculation assuming constant urban speed (25 km/h).
* **Fallback Behavior:** If `TOMTOM_API_KEY` is not set, or if an HTTP call fails, times out, or returns a non-2xx status, it logs a warning and falls back to `HaversineEtaEstimator`.
* **Where Used:**
  * In `RoutingService.matchRideRequest()` to return estimated pickup time to the commuter.
  * In `GreedyNearestNeighbourOptimizer.optimize()` to accumulate cumulative travel times along the sequenced route.
* **Persistence & Influence:** ETA is **not persisted** in PostgreSQL and **does not influence vehicle assignment or stop ordering decisions**.

---

## 8. Trip Lifecycle

### Defined Database States
* **Trips Table:** `SCHEDULED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED` (enforced by `chk_trips_status`).
* **Ride Requests Table:** `REQUESTED`, `MATCHED`, `WAITLISTED`, `PICKUP_PENDING`, `ONBOARD`, `COMPLETED`, `CANCELLED`, `NO_SHOW` (enforced by `chk_ride_requests_status`).

### Operational Status Matrix

| Stage | Expected Operational Action | Production Implementation Status | Evidence / Code Location |
|---|---|---|---|
| 1. Creation | Admin generates operational trip from schedule | **IMPLEMENTED** | `POST /shuttle-schedules/{scheduleId}/trips` creates trip with `status='SCHEDULED'`, `vehicleId=null`, `driverId=null` ([ShuttleScheduleRoutes.kt:169-209](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/ShuttleScheduleRoutes.kt#L169-L209)). |
| 2. Assignment | Dispatcher binds vehicle & driver to trip | **NOT IMPLEMENTED / NOT FOUND** | No route or service method exists to update `trips.vehicle_id` or `trips.driver_id`. |
| 3. Start | Driver/system transitions trip to `IN_PROGRESS` | **NOT IMPLEMENTED / NOT FOUND** | No route or service method exists to transition `trips.status` from `SCHEDULED` to `IN_PROGRESS`. |
| 4. Stop Insertion | Dynamic matching attaches passenger to trip | **IMPLEMENTED** | `RoutingService.matchRideRequest()` sets `ride_requests.trip_id` and `status = 'MATCHED'` ([RoutingService.kt:50](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt#L50)). |
| 5. Pickup Arrival | Passenger ready at stop | **IMPLEMENTED** | `TripService.markPickupPending()` transitions `MATCHED` $\rightarrow$ `PICKUP_PENDING` ([TripService.kt:77-86](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/TripService.kt#L77-L86)). |
| 6. Boarding | Driver confirms passenger boarded | **IMPLEMENTED** | `POST /trips/{id}/stops/{stopId}/pickup` transitions `PICKUP_PENDING` $\rightarrow$ `ONBOARD` ([TripRoutes.kt:69-103](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/TripRoutes.kt#L69-L103)). |
| 7. No-Show | Driver marks absent passenger | **IMPLEMENTED** | `POST /trips/{id}/stops/{stopId}/no-show` transitions `PICKUP_PENDING` $\rightarrow$ `NO_SHOW` ([TripRoutes.kt:109-143](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/TripRoutes.kt#L109-L143)). |
| 8. Drop-Off | Driver marks drop-off done | **PARTIAL** | `POST /trips/{id}/stops/{stopId}/complete` transitions `ONBOARD` $\rightarrow$ `COMPLETED` ([TripRoutes.kt:149-183](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/TripRoutes.kt#L149-L183)). Destination was never routed. |
| 9. Completion | Trip finishes when all stops are completed | **NOT IMPLEMENTED / NOT FOUND** | No endpoint or worker exists to mark `Trips.status = 'COMPLETED'`. |

---

## 9. Vehicle State

* **Model:** [`Vehicles`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Tables.kt#L51-L67) Exposed table and repository DTOs [`VehicleCandidate`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/VehicleRepository.kt#L10-L18), [`VehicleLocation`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/VehicleRepository.kt#L37-L41).
* **Persistent Columns:**
  * `id`: UUID Primary Key.
  * `company_id`: UUID Foreign Key referencing `companies(id)`.
  * `plate_number`: VARCHAR(20).
  * `seat_capacity`: INTEGER (constrained $> 0$).
  * `last_known_lat`: DOUBLE PRECISION, nullable.
  * `last_known_lng`: DOUBLE PRECISION, nullable.
  * `last_location_updated_at`: TIMESTAMP WITHOUT TIME ZONE, nullable.
* **Update Channel:** Updated via `PostgresVehicleRepository.updateLocation()` when driver WebSocket pushes a `DriverLocationUpdate` frame.

### Evaluative Question Answered
> **Can the production routing system determine the current state of a vehicle accurately enough to perform dynamic assignment?**

**Answer: NO.** The production system cannot accurately determine vehicle state for dynamic assignment because:
1. **No Operational Link to Vehicles:** Generated trips have `vehicle_id = null`. The system cannot determine which vehicle is operating which trip unless manually populated in the database.
2. **Missing Operational Status:** The `vehicles` table lacks an operational state column (e.g. `OFFLINE`, `IDLE_AT_DEPOT`, `EN_ROUTE`, `MAINTENANCE`). The system can only infer that a vehicle is in service if it is joined to a trip where `trips.status = 'IN_PROGRESS'`.
3. **Stale Location Vulnerability:** If a driver loses cellular connection or closes the app, `last_known_lat/lng` remains frozen indefinitely. There is no heartbeat expiration check to invalidate stale locations.
4. **No Heading or Velocity:** Telemetry stores only instantaneous latitude and longitude without heading, bearing, or speed, preventing trajectory-aware vehicle insertion.

---

## 10. WebSocket / Real-Time Overview

* **Endpoints:**
  * `/ws/driver/{tripId}`: Driver sends GPS position frames (`DriverLocationUpdate`) every ~5 seconds ([WebSocketRoutes.kt:34-74](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/WebSocketRoutes.kt#L34-L74)).
  * `/ws/trip/{tripId}`: Commuters and admins listen to real-time vehicle movement ([WebSocketRoutes.kt:76-99](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/WebSocketRoutes.kt#L76-L99)).
* **Authentication & Authorization:** Authenticated via `authenticate("app-user")`.
  * `/ws/driver/{tripId}`: Caller must have `role == "driver"`, must match `trip.driverId`, and must belong to `trip.companyId`.
  * `/ws/trip/{tripId}`: Caller must belong to `trip.companyId`.
* **State Broadcast:** Exclusively broadcasts `DriverLocationUpdate(driverId, tripId, lat, lng, timestamp)` to active sessions in `tripListeners[tripId]`.
* **Real-Time Decoupling:**
  * No ride request status changes (`MATCHED`, `PICKUP_PENDING`, `CANCELLED`) are sent over WebSockets.
  * No route updates or new stop notifications are sent to drivers over WebSockets.
  * Request arrivals do not trigger server-side push notifications.

---

## 11. Authentication & Tenant Context Overview

* **Token Format:** Signed JWT (HMAC-256) containing claims: `userId`, `role` (`"employee"`, `"driver"`, `"admin"`), `companyId`, and 24-hour expiration ([AuthService.kt:79-87](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/AuthService.kt#L79-L87)).
* **Context Propagation:** Extracted at route level using helper `call.authenticatedUser()` ([Authentication.kt:54-60](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/plugins/Authentication.kt#L54-L60)).
* **Service Layer Propagation:** Service methods explicitly accept `companyId`:
  * `RoutingService.matchRideRequest(request, companyId = user.companyId)`
  * `ShuttleScheduleService.getSchedule(id, callerCompanyId = user.companyId)`
  * `PickupZoneService.getZone(id, callerCompanyId = user.companyId)`
* **Repository Layer Filtering:**
  * `PostgresVehicleRepository.findAvailableNear()` filters `AND v.company_id = ?`.
  * `PostgresRideRequestRepository.allocateSeat()` validates `tripCompanyUuid == companyUuid`.
  * Single-entity lookups across schedules and pickup zones return HTTP 404 on cross-company requests to prevent resource enumeration.

---

## 12. Production vs. Research Comparison

| Research Concept | Experimental Implementation | Production Implementation | Match? | Gap |
|---|---|---|---|---|
| **Ride request** | `ExperimentRequest` with origin, destination, time | `RideRequestDto` / `StoredRideRequest` | **MATCH** | None; identical data shape. |
| **Request ordering** | Ordered chronologically by `requestedAtSeconds` | Online event-driven arrival via `POST /rides` | **DIFFERENT** | Production processes requests immediately without temporal accumulation. |
| **Vehicle state** | `SimulationVehicleState` with full simulation timeline | `Vehicles.lastKnownLat/Lng` in PostgreSQL | **DIFFERENT** | Production lacks vehicle operational states, headings, and trajectory tracking. |
| **Vehicle availability** | Derived from `availableAtSeconds <= simTime` | Hardcoded SQL check `trips.status = 'IN_PROGRESS'` | **DIFFERENT** | Production excludes idle or depot vehicles; requires active trip. |
| **Vehicle assignment** | Fleet-wide nearest eligible vehicle selection | Immediate PostGIS spatial search (`ST_DWithin` 2km) | **DIFFERENT** | Production does not assign from the fleet; only inserts into active trips within 2km. |
| **Capacity enforcement** | In-memory batch counter decrement | Transactional pessimistic row lock (`FOR UPDATE OF t`) | **DIFFERENT** | Production uses real database row-level locking; highly resilient to concurrency. |
| **Batching** | 300-second batch accumulation window | None; immediate first-come-first-served matching | **DIFFERENT** | Production has no batch accumulation window or request buffer. |
| **Route optimization** | Performed on full batch prior to dispatch | Only sequences single stop during matching; sequences active pickups on driver poll | **DIFFERENT** | Production does not optimize routes prior to dispatch; sequencing is lazy. |
| **Nearest-vehicle logic** | Evaluates all idle/returning fleet vehicles | PostGIS `ST_Distance` on active vehicles within 2km | **PARTIAL** | Production uses spatial proximity but limits candidate pool to active vehicles within 2km. |
| **Greedy NN routing** | `GreedyNearestNeighbourOptimizer` via adapter | `GreedyNearestNeighbourOptimizer` | **MATCH** | Core waypoint sequencing heuristic is identical. |
| **Destination handling** | Visits all pickups, then drives to campus hub | Excluded entirely; only pickup locations are routed | **DIFFERENT** | Critical gap: production never routes to passenger destinations or campus hubs. |
| **ETA** | Deterministic travel time model | TomTom Routing API v1 with Haversine fallback | **DIFFERENT** | Production uses live traffic-aware routing; simulation used deterministic flat speeds. |
| **Travel-time model** | Deterministic flat urban speed (25 km/h) | Real traffic-aware TomTom or flat Haversine fallback | **DIFFERENT** | Production supports external API integration with live traffic. |
| **Trip creation** | Implicit vehicle trip created upon batch dispatch | Explicit `POST /shuttle-schedules/{id}/trips` | **DIFFERENT** | Production creates trips as templates with null vehicles and drivers. |
| **Trip execution** | Simulated discrete timeline progression | Driver HTTP endpoints (`/pickup`, `/complete`) | **DIFFERENT** | Production models granular passenger boarding states but lacks trip completion transitions. |
| **Vehicle state updates** | Updated mathematically at each leg arrival | Driver WebSocket GPS frame pushes every 5s | **DIFFERENT** | Production relies on live client telemetry rather than internal dead-reckoning. |
| **Real-time updates** | Instantaneous state transitions in simulation loop | WebSocket location relay only; no event push | **DIFFERENT** | Production WebSockets do not notify drivers or commuters of routing events. |

---

## 13. Architectural Gaps

1. **Trip Operational Deadlock / Missing Vehicle & Driver Assignment:**
   * *Classification:* **CRITICAL**
   * *Description:* `ShuttleScheduleService.generateTrip()` generates trips with `status = 'SCHEDULED'`, `vehicle_id = null`, and `driver_id = null`. There is **no API endpoint or service method** to assign a vehicle or driver to a trip, and no endpoint or worker to transition a trip from `SCHEDULED` to `IN_PROGRESS`.
   * *Impact:* Because `PostgresVehicleRepository.findAvailableNear()` explicitly filters `WHERE t.status = 'IN_PROGRESS'`, the system cannot discover any vehicles unless manually injected via direct SQL scripts. In an automated deployment, all ride requests are guaranteed to become `WAITLISTED`.
2. **Missing Passenger Destinations in Route Optimization:**
   * *Classification:* **CRITICAL**
   * *Description:* When `TripService.getStopList()` sequences stops for the driver, it only queries `ride_requests` with status `MATCHED` or `PICKUP_PENDING` and passes their `pickupLat/Lng` to the optimizer. It **never passes `destinationLat` or `destinationLng`**. Furthermore, once a driver marks a passenger as picked up (`ONBOARD`), that passenger is excluded from `findActiveByTripId()`.
   * *Impact:* The vehicle is routed to pick up passengers, but **the system never instructs the driver where to drop them off**. The vehicle route does not include corporate campus hubs or drop-off destinations.
3. **No Batch Accumulation in Production Matching:**
   * *Classification:* **HIGH**
   * *Description:* The research DynaShuttle strategy demonstrated major efficiency gains by holding requests in a 300-second batch window before solving route sequences. Production processes requests instantly one-by-one.
   * *Impact:* First-come-first-served greedy matching leads to suboptimal vehicle routes, higher deadheading, and fragmentation of vehicle capacity.
4. **Single-Stop Optimizer Call in `RoutingService`:**
   * *Classification:* **HIGH**
   * *Description:* In `RoutingService.matchRideRequest()`, `routeOptimizer.optimize()` is called with `stops = listOf(StopCandidate(...))` containing only the single new request. Existing active stops already assigned to that vehicle are ignored during the match.
   * *Impact:* The initial ETA returned to the passenger (`etaMinutes`) represents travel time directly from the vehicle to their pickup point, ignoring all intermediate stops the vehicle must make for earlier passengers. The passenger receives an inaccurate, overly optimistic ETA.
5. **No Event Notification on Ride Match / Route Change:**
   * *Classification:* **HIGH**
   * *Description:* When a ride request is matched to a trip, no message is broadcast over `/ws/trip/{tripId}` or sent to the driver. The driver's route is not pushed dynamically.
   * *Impact:* Drivers must manually poll `GET /trips/{id}/stops` to discover new passengers; passengers cannot be notified in real time when assigned.
6. **No Real-Time Traffic Awareness in Route Ordering:**
   * *Classification:* **MEDIUM**
   * *Description:* While TomTom is integrated for calculating leg travel times, `GreedyNearestNeighbourOptimizer` strictly uses Euclidean/Haversine distance to pick which stop is visited next.
   * *Impact:* In dense urban networks with one-way streets, bridges, or severe congestion, the nearest straight-line stop may have a significantly longer driving time than an alternative stop.
7. **Fixed 2,000-Meter Spatial Search Radius:**
   * *Classification:* **MEDIUM**
   * *Description:* `searchRadiusMeters` is hardcoded to 2,000m in `RoutingService`.
   * *Impact:* If an empty shuttle is 2,100 meters away, the system will not consider it, and the commuter will be waitlisted, even if the shuttle has no other assignments.
8. **No Persistent Location History:**
   * *Classification:* **LOW**
   * *Description:* Vehicle GPS updates overwrite `Vehicles.lastKnownLat/Lng`. No breadcrumb trail is stored.
   * *Impact:* Prevents post-trip analytics and route replay, though acceptable for MVP real-time operations.
9. **In-Memory OTP Cache:**
   * *Classification:* **LOW**
   * *Description:* `AuthService` stores OTPs in a `ConcurrentHashMap`.
   * *Impact:* OTPs do not survive server restarts and cannot be shared across multiple backend replicas without Redis.

---

## 14. Severity Classification

| Gap ID | Description | Severity | Target Remediation Task |
|---|---|---|---|
| GAP-1 | Trip operational deadlock; missing vehicle/driver assignment & trip start endpoints | **CRITICAL** | Task 7.2 |
| GAP-2 | Passenger destinations omitted from optimizer and driver stop manifests | **CRITICAL** | Task 7.3 |
| GAP-3 | Absence of demand accumulation batching window in production | **HIGH** | Task 7.6 |
| GAP-4 | Single-stop optimizer call during ride matching distorts passenger ETA | **HIGH** | Task 7.3 |
| GAP-5 | WebSockets lack event notifications for ride matches and route updates | **HIGH** | Task 7.4 |
| GAP-6 | Waypoint sequencing relies on straight-line distance rather than traffic-aware travel time | **MEDIUM** | Task 7.3 |
| GAP-7 | Hardcoded 2km spatial search radius limits vehicle discovery | **MEDIUM** | Task 7.2 |
| GAP-8 | Vehicle GPS telemetry overwrites coordinates without breadcrumb history | **LOW** | Future Phase |
| GAP-9 | OTP verification relies on in-memory concurrent map | **LOW** | Future Phase |

---

## 15. Recommended Next Audit Tasks

Based on the evidence uncovered in this audit, the recommended sequence for subsequent audits and hardening tasks is:

1. **TASK 7.2 — Trip Lifecycle & Vehicle Assignment Audit:**
   * Audit the missing operational bridge: how vehicles and drivers should be assigned to `SCHEDULED` trips, and how trips should be transitioned to `IN_PROGRESS` and `COMPLETED`.
   * Define the required API contracts and state machine transitions to unblock production matching.
2. **TASK 7.3 — Route Optimizer & Destination Routing Audit:**
   * Trace how passenger drop-off destinations (`destinationLat/Lng`) and corporate campus hubs can be incorporated into `RouteOptimizer`, `ActiveTripStop`, and `TripService.getStopList()`.
   * Address the single-stop ETA calculation deficiency in `RoutingService.matchRideRequest()`.
3. **TASK 7.4 — Real-Time WebSocket & Event Synchronization Audit:**
   * Audit the WebSocket and event architecture to determine how ride match events, driver stop list updates, and vehicle location broadcasts should be synchronized to eliminate polling.
4. **TASK 7.5 — Security, IDOR, & Multi-Tenant Authorization Audit:**
   * Perform an exhaustive audit of tenant boundaries, IDOR protections, and role enforcement across all routes, services, and repositories.
5. **TASK 7.6 — Batching & Dynamic Dispatching Production Feasibility Audit:**
   * Evaluate whether introducing a production request accumulation buffer / batching window (mirroring the research model) is feasible and beneficial for DynaShuttle's target deployment scale.

---

## 16. Evidence / Code References

Every key finding in this report is traceable to specific source files in the repository:

* **Pessimistic Row Locking in Capacity Allocation:**
  * File: [`src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt#L33-L43)
  * Function: `PostgresRideRequestRepository.allocateSeat()`
  * Evidence: `SELECT t.id, t.status, v.seat_capacity, v.company_id FROM trips t JOIN vehicles v ON v.id = t.vehicle_id WHERE t.id = ? FOR UPDATE OF t`
* **Single-Stop Optimizer Call during Matching:**
  * File: [`src/main/kotlin/com/dynashuttle/services/RoutingService.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt#L53-L57)
  * Function: `RoutingService.matchRideRequest()`
  * Evidence: `val optimizedStops = routeOptimizer.optimize(..., stops = listOf(StopCandidate(stored.id, request.pickupLat, request.pickupLng)))`
* **Exclusion of Destinations from Driver Stop List:**
  * File: [`src/main/kotlin/com/dynashuttle/repositories/RideRequestRepository.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/RideRequestRepository.kt#L24-L30)
  * Model: `ActiveTripStop` only models `pickupLat` and `pickupLng`; omits destination coordinates.
  * File: [`src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt#L201-L218)
  * Function: `PostgresRideRequestRepository.findActiveByTripId()` filters `WHERE status inList listOf("MATCHED", "PICKUP_PENDING")`. Once `ONBOARD`, passengers disappear.
* **Spatial Query Filtering on `IN_PROGRESS` Trips:**
  * File: [`src/main/kotlin/com/dynashuttle/repositories/PostgresVehicleRepository.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresVehicleRepository.kt#L76-L81)
  * Function: `PostgresVehicleRepository.findAvailableNear()`
  * Evidence: `WHERE t.status = 'IN_PROGRESS' AND v.last_known_lat IS NOT NULL ...`
* **Schedule Trip Generation with Null Vehicle and Driver:**
  * File: [`src/main/kotlin/com/dynashuttle/services/ShuttleScheduleService.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/ShuttleScheduleService.kt#L242-L251)
  * Function: `ShuttleScheduleService.generateTrip()`
  * Evidence: Creates trip with `vehicleId = null, driverId = null, status = "SCHEDULED"`.
  * File: [`src/main/kotlin/com/dynashuttle/models/Trip.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Trip.kt#L12-L13) explicitly states: *"Generated trips begin in the SCHEDULED state with vehicleId and driverId as null, awaiting operational assignment in a subsequent phase."*
* **Absence of Trip Updating Endpoints / Methods:**
  * File: [`src/main/kotlin/com/dynashuttle/repositories/TripRepository.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/TripRepository.kt#L12-L16)
  * Interface: Only declares `findById`, `findByScheduleAndDate`, and `create`. Contains zero update methods.
* **TomTom Integration and Haversine Fallback:**
  * File: [`src/main/kotlin/com/dynashuttle/plugins/Routing.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/plugins/Routing.kt#L76-L85)
  * File: [`src/main/kotlin/com/dynashuttle/services/routing/TomTomEtaEstimator.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/routing/TomTomEtaEstimator.kt#L57-L82)
* **WebSocket GPS Telemetry Scope:**
  * File: [`src/main/kotlin/com/dynashuttle/routes/WebSocketRoutes.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/WebSocketRoutes.kt#L63-L68, 103-107)
* **Research DynaShuttle Batch Strategy:**
  * File: [`src/main/kotlin/com/dynashuttle/experiments/DynaShuttleSimulationStrategy.kt`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/experiments/DynaShuttleSimulationStrategy.kt#L50-L135)

---

```text
TASK 7.1 STATUS

Audit completed: YES
Production code modified: NO
Research experiment modified: NO
Research results modified: NO
Database modified: NO

Next recommended task:
TASK 7.2 — Trip Lifecycle & Vehicle Assignment Audit
```
