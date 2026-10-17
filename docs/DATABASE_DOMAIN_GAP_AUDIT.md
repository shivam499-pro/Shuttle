# DynaShuttle Database & Domain Gap Audit
**Document Type:** Read-Only Architectural & Database Audit  
**Target System:** DynaShuttle Backend (Kotlin 1.9 / Ktor 2.3 / PostgreSQL 17 + PostGIS / Exposed 0.50.1)  
**Blueprint Reference:** DynaShuttle Product Blueprint v1 (Corporate Van Pooling Platform)  
**Date:** September 2026  
**Audit Mode:** READ-ONLY (No code, schema, migrations, or database modified)  

---

## 1. Executive Summary

DynaShuttle is currently implemented as an MVP-stage on-demand shuttle routing backend for corporate campuses. The codebase exhibits clean internal software craft: strict interface segregation (e.g., separating vehicle availability lookup from trip location lookup and driver location channels), SOLID dependency injection centralized in `Routing.kt`, and unit-testable service orchestrators utilizing test fakes rather than heavy mocking frameworks.

However, an audit of the current database schema, repositories, and domain services against the **Product Blueprint v1** reveals substantial architectural gaps and critical operational risks:

1. **Database & Migration Maturity:** The project currently relies entirely on Exposed's `SchemaUtils.createMissingTablesAndColumns()` on application startup (`DatabaseFactory.kt:23-32`). There is **zero formal database migration tooling** (no Flyway or Liquibase). `SchemaUtils` cannot safely drop columns, alter column constraints, add non-standard spatial indexes (such as PostGIS GiST indexes), or support zero-downtime blue/green deployments.
2. **Missing Core Blueprint Entities:** The system models a single-tier booking flow (`RideRequest` -> `Trip` -> `Vehicle`/`Driver`). Key blueprint domain concepts are completely absent:
   - **Shuttle Schedule:** No template or recurring schedule entity exists; trips are treated as ad-hoc runtime instances without any parent schedule template.
   - **Recurring Booking:** No data structure or worker exists to generate individual trip ride requests from a recurring schedule rule.
   - **Pickup Zones:** The system operates strictly door-to-door using raw floating-point lat/lng coordinates (`pickup_lat`, `pickup_lng`). There is no concept of admin-approved or system-suggested pickup zones, polygonal service areas, or zone-to-trip mappings.
   - **Waitlist:** If a shuttle is full or unavailable, requests are simply marked `QUEUED`. There is no FIFO waitlist queue tied to specific trips.
3. **Critical Concurrency & Capacity Flaw:** Capacity enforcement is fundamentally broken. In `PostgresVehicleRepository.kt:53,91`, `findAvailableNear()` returns `v.seat_capacity` as `availableSeats` without subtracting active passengers already assigned to the trip. Furthermore, `RoutingService.kt:46` assigns the first vehicle returned without checking remaining seats or taking row-level database locks. Under concurrent booking requests for the last available seat, overbooking is guaranteed to occur.
4. **Complete Multi-Tenant Isolation Breach:** While `Companies` exists as a parent table and foreign keys link `Employees`, `Drivers`, `Vehicles`, and `Admins` to `company_id`, cross-company isolation is **unenforced in the application and repository layers**. Specifically, `findAvailableNear()` queries all active trips across the entire database without filtering by `company_id`. Consequently, an employee from Company A can be automatically matched and assigned to a shuttle owned by Company B. Additionally, JWT tokens do not encode `company_id`, and route handlers do not verify that the authenticated employee ID matches the requested booking payload.
5. **Evolution Feasibility:** The architecture does **not** require a rewrite. Because services depend strictly on narrow interfaces (`VehicleAvailabilityLookup`, `RideMatchingRepository`, `RouteOptimizer`), the system can evolve incrementally toward the blueprint by expanding domain repositories, introducing Flyway migrations, enriching the state machine, and placing an eligibility/policy evaluation layer in front of the route optimizer.

---

## 2. Current Database Inventory

The PostgreSQL database contains 7 tables defined via Exposed in `src/main/kotlin/com/dynashuttle/models/Tables.kt`. All tables inherit from `org.jetbrains.exposed.dao.id.UUIDTable`, using UUID primary keys (`id`).

| Table | Purpose | Important Columns | PK | FKs | Current Status |
|---|---|---|---|---|---|
| `companies` | Corporate tenants/customers | `id` (UUID), `name` (varchar 255), `city` (varchar 100), `employee_count` (int, def 0), `created_at` (timestamp) | `id` | None | Active; no status or subscription column; tenant boundary not enforced in queries. |
| `employees` | Corporate employees eligible for shuttles | `id` (UUID), `company_id` (UUID), `email` (varchar 255, unique), `name` (varchar 255), `home_lat` (double), `home_lng` (double), `created_at` (timestamp) | `id` | `company_id` -> `companies(id)` | Active; coordinates stored as raw doubles without PostGIS geometry/geography types or spatial index. |
| `drivers` | Fleet vehicle operators | `id` (UUID), `company_id` (UUID), `name` (varchar 255), `phone` (varchar 20), `created_at` (timestamp) | `id` | `company_id` -> `companies(id)` | Active; lacks driver license number, status (ACTIVE/INACTIVE), and authentication credentials/PIN. |
| `vehicles` | Fleet shuttles / vans | `id` (UUID), `company_id` (UUID), `plate_number` (varchar 20), `seat_capacity` (int), `last_known_lat` (double, nullable), `last_known_lng` (double, nullable), `last_location_updated_at` (timestamp, nullable), `created_at` (timestamp) | `id` | `company_id` -> `companies(id)` | Active; lacks spatial geometry column, GiST index, vehicle model/status. Mutable live location updated directly on row. |
| `trips` | Concrete vehicle journeys | `id` (UUID), `vehicle_id` (UUID), `driver_id` (UUID), `start_time` (timestamp), `status` (varchar 20, def 'SCHEDULED') | `id` | `vehicle_id` -> `vehicles(id)`, `driver_id` -> `drivers(id)` | Active; statuses used: SCHEDULED, IN_PROGRESS, COMPLETED, CANCELLED. No schedule FK, no route definition, no end_time. |
| `ride_requests` | Individual employee passenger bookings | `id` (UUID), `trip_id` (UUID, nullable), `employee_id` (UUID), `pickup_lat` (double), `pickup_lng` (double), `destination_lat` (double), `destination_lng` (double), `status` (varchar 20, def 'REQUESTED'), `requested_at` (timestamp) | `id` | `trip_id` -> `trips(id)` (nullable), `employee_id` -> `employees(id)` | Active; statuses used: REQUESTED, MATCHED, PICKED_UP, COMPLETED, NO_SHOW, QUEUED. No dropoff timestamp, no sequence order, no zone FK. |
| `admins` | Corporate fleet administrators | `id` (UUID), `company_id` (UUID), `email` (varchar 255, unique), `password_hash` (varchar 255), `created_at` (timestamp) | `id` | `company_id` -> `companies(id)` | Active; BCrypt hashed passwords; email unique globally rather than scoped per company. |

---

## 3. Current Kotlin Domain Model

The domain model is split between Exposed table singleton objects (`Tables.kt`), Kotlin data classes / DTOs (`Dto.kt`), and repository data transfer objects (`VehicleRepository.kt`, `RideRequestRepository.kt`, `AuthRepository.kt`).

| Kotlin Class/Model | Purpose | Related Table | Used By | Notes |
|---|---|---|---|---|
| `Companies` | Exposed table definition | `companies` | `DatabaseFactory`, `seed_test_data.sql` | Singleton object extending `UUIDTable`. |
| `Employees` | Exposed table definition | `employees` | `DatabaseFactory`, `PostgresAuthRepository`, `PostgresRideRequestRepository` | Has `uniqueIndex()` on `email`. |
| `Drivers` | Exposed table definition | `drivers` | `DatabaseFactory`, `Trips` reference | Lacks dedicated repository; queried via joins on Trips. |
| `Vehicles` | Exposed table definition | `vehicles` | `DatabaseFactory`, `PostgresVehicleRepository` | Contains mutable GPS coordinates. |
| `Trips` | Exposed table definition | `trips` | `DatabaseFactory`, `PostgresVehicleRepository` | No Exposed DAO entity; operated via raw JDBC or DSL joins. |
| `RideRequests` | Exposed table definition | `ride_requests` | `DatabaseFactory`, `PostgresRideRequestRepository` | Status is unconstrained varchar(20). |
| `Admins` | Exposed table definition | `admins` | `DatabaseFactory`, `PostgresAuthRepository` | Unique index on email. |
| `RideRequestDto` | HTTP request body for `/rides` | `ride_requests` | `RideRoutes`, `RoutingService` | Lacks `companyId`; coordinates are raw doubles. |
| `RideResponseDto` | HTTP response body for `/rides` | `ride_requests` | `RideRoutes`, `RoutingService` | Returns `status`, `assignedVehicleId`, `etaMinutes`. |
| `DriverLocationUpdate` | WebSocket GPS broadcast payload | `vehicles` | `WebSocketRoutes` | Carries `tripId`, `lat`, `lng`, `timestamp`. Lacks vehicleId. |
| `StopListUpdate` | Driver stop manifest response | Derived from `ride_requests` | `TripRoutes`, `TripService` | Contains ordered list of `Stop` items. |
| `Stop` | Stop item within `StopListUpdate` | `ride_requests` + `employees` | `TripRoutes`, `TripService` | Contains `rideRequestId`, `lat`, `lng`, `sequenceOrder`, `employeeName`. |
| `StopActionResponse` | HTTP response for pickup/no-show | `ride_requests` | `TripRoutes` | Returns `stopId` and `status`. |
| `EmployeeOtpRequest` / `Response` | Employee OTP initiation DTOs | None (in-memory) | `AuthRoutes`, `AuthService` | Response includes OTP in dev mode. |
| `EmployeeVerifyRequest` / `Response` | Employee OTP validation DTOs | `employees` | `AuthRoutes`, `AuthService` | Returns JWT token, employeeId, role. |
| `AdminLoginRequest` / `Response` | Admin credentials DTOs | `admins` | `AuthRoutes`, `AuthService` | Returns JWT token and role. |
| `VehicleCandidate` | Spatial search result DTO | `vehicles` + `trips` | `VehicleAvailabilityLookup`, `RoutingService` | In `VehicleRepository.kt`. `availableSeats` currently mirrors raw vehicle capacity. |
| `VehicleLocation` | Coordinates lookup DTO | `vehicles` + `trips` | `VehicleTripLocationLookup`, `TripService` | In `VehicleRepository.kt`. Nullable lat/lng. |
| `StoredRideRequest` | Saved ride entity DTO | `ride_requests` | `RideMatchingRepository`, `RoutingService` | In `RideRequestRepository.kt`. |
| `ActiveTripStop` | Pending pickup entity DTO | `ride_requests` + `employees` | `TripStopRepository`, `TripService` | In `RideRequestRepository.kt`. |
| `StopCandidate` / `OptimizedStop` | Routing algorithm input/output | In-memory | `RouteOptimizer`, `GreedyNearestNeighbourOptimizer` | In `services/routing/RouteOptimizer.kt`. |

---

## 4. Current Repository Architecture

The repository layer strongly adheres to the **Interface Segregation Principle (ISP)**. Narrow interfaces are defined in the repository contract files and implemented jointly by single PostgreSQL classes.

| Repository Implementation | Interface | Tables Used | Consumers | Notes |
|---|---|---|---|---|
| `PostgresVehicleRepository` | `VehicleAvailabilityLookup` | `trips`, `vehicles` | `RoutingService` | Implements `findAvailableNear()`. Uses raw JDBC via PostGIS `ST_DWithin`. Does NOT subtract passengers or filter by company. |
| `PostgresVehicleRepository` | `VehicleTripLocationLookup` | `trips`, `vehicles` | `TripService` | Implements `findVehicleLocationByTripId()`. Joins Trips -> Vehicles. |
| `PostgresVehicleRepository` | `DriverLocationChannel` | `trips`, `vehicles` | `WebSocketRoutes` | Implements `findVehicleIdByTripId()` and `updateLocation()`. Called every 5s per vehicle. |
| `PostgresRideRequestRepository` | `RideMatchingRepository` | `ride_requests` | `RoutingService` | Implements `save()` and `findById()`. |
| `PostgresRideRequestRepository` | `TripStopRepository` | `ride_requests`, `employees` | `TripService` | Implements `findActiveByTripId()`, `findById()`, `updateStatus()`. Joins RideRequests -> Employees for employee name. |
| `PostgresAuthRepository` | `AuthRepository` | `employees`, `admins` | `AuthService` | Implements `findEmployeeByEmail()` and `findAdminByEmail()`. Single-table lookups. |

### Repository Architectural Observations:
- **Transaction Boundaries:** In both `PostgresRideRequestRepository.kt` and `PostgresVehicleRepository.kt`, every method wraps its operations in a short-lived `transaction { ... }` block. There are **no cross-entity or multi-step transactions**. For example, in `RoutingService.matchRideRequest()`, finding a vehicle and saving the ride request occur in separate transactions without locks, making isolation impossible.
- **Shared Repository Instances:** `Routing.kt:66-67` instantiates single instances of `PostgresVehicleRepository` and `PostgresRideRequestRepository` and injects them into multiple consumers (`RoutingService`, `TripService`, `WebSocketRoutes`). This is memory-efficient and thread-safe because Exposed transactions are thread-local/coroutine-bound.
- **SQL Execution Paradigm:** Most queries use the Exposed DSL (`selectAll().where { ... }`). The only exception is `PostgresVehicleRepository.findAvailableNear()`, which drops down to `connection.prepareStatement()` due to Exposed 0.50.1 lacking native PostGIS DSL functions.

---

## 5. Current Service Architecture

| Service | Responsibility | Dependencies | Current Problems/Gaps |
|---|---|---|---|
| `RoutingService` | Orchestrates ride matching flow (`matchRideRequest`, `getRideStatus`). | `VehicleAvailabilityLookup`, `RouteOptimizer`, `RideMatchingRepository` | 1. Assigns vehicle without validating available capacity.<br>2. Optimizes stop list containing *only the single new stop*, ignoring existing trip passengers.<br>3. Completely ignores `destinationLat`/`destinationLng`.<br>4. No multi-tenant company check.<br>5. No retry/waitlist handling when no vehicle is found. |
| `TripService` | Driver trip operations: manifest listing (`getStopList`), stop pickups (`markPickedUp`), and no-shows (`markNoShow`). | `TripStopRepository`, `VehicleTripLocationLookup`, `RouteOptimizer` | 1. Recalculates stop sequence on every fetch instead of persisting sequence order.<br>2. Does not advance trip status to `COMPLETED` when last stop is picked up.<br>3. Does not manage passenger drop-offs, only pickups. |
| `AuthService` | Employee OTP delivery/verification and Admin password login; JWT generation. | `AuthRepository`, `OtpGenerator`, `PasswordHasher`, `OtpSender` | 1. In-memory `ConcurrentHashMap` for OTP storage (`otpCache`) does not survive server restarts or scale across multiple instances.<br>2. JWT claims omit `companyId`, preventing tenant-based route authorization. |
| `GreedyNearestNeighbourOptimizer` | Routes stops from vehicle position to nearest unvisited pickup. | `EtaEstimator` (Haversine or TomTom) | 1. Greedy $O(N^2)$ heuristic with no time windows, vehicle capacity constraints, or destination drop-off sequencing.<br>2. Only handles pickup points; destination is absent. |
| `TomTomEtaEstimator` | Fetches real-world driving ETAs via TomTom Routing API v1. | `HttpClient`, API Key | Functional; properly integrated with fallback to `HaversineEtaEstimator` when API key is missing. |

---

## 6. Blueprint vs Current System

| Blueprint Concept | Exists? | Current Representation | Gap | Priority |
|---|---|---|---|---|
| **Company** | Yes | `Companies` table (`Tables.kt:13`) | Table exists with `id`, `name`, `city`, `employee_count`. Missing status, subscription tier, and policy configurations. Isolation is not enforced in queries. | P0 |
| **Employee** | Yes | `Employees` table (`Tables.kt:20`) | Exists with `company_id`, `email`, `name`, `home_lat/lng`. Lacks default pickup zone, shift timings, phone number, and active status. | P1 |
| **Driver** | Yes | `Drivers` table (`Tables.kt:29`) | Exists with `company_id`, `name`, `phone`. Missing authentication credentials/PIN, active duty status, license info, and dedicated repository. | P1 |
| **Admin** | Yes | `Admins` table (`Tables.kt:74`) | Exists with `company_id`, `email`, `password_hash`. Email is unique globally rather than per company. No role-based access control. | P2 |
| **Vehicle** | Yes | `Vehicles` table (`Tables.kt:36`) | Exists with `plate_number`, `seat_capacity`, live lat/lng. Missing vehicle model, operational status (ACTIVE, MAINTENANCE), and PostGIS geometry. | P1 |
| **Pickup Zone** | **No** | None | Completely absent. No zone entity, no approved vs. suggested workflow, no bounding radius or polygons. System is strictly point-to-point door-to-door. | P0 |
| **Shuttle Schedule** | **No** | None | Completely absent. No template for recurring routes, departure times, day-of-week recurrence, or stops. | P0 |
| **Trip** | Partial | `Trips` table (`Tables.kt:54`) | Exists as an ad-hoc vehicle + driver pairing. Has no link to a Schedule template, no planned route, no scheduled end time, and no stop sequence. | P0 |
| **Ride Request** | Partial | `RideRequests` table (`Tables.kt:62`) | Stores pickup/drop coordinates, employee, trip, status. Lacks scheduled pickup time, dropoff time, zone reference, and cancellation metadata. | P0 |
| **Trip Stop** | Partial | Computed in-memory via `TripService.getStopList()` | No database representation (`RideRequests` has no `sequence_order` column). Stop list is dynamically calculated on every driver call. | P1 |
| **Route** | **No** | None | No stored route geometry, waypoint sequence, or corridor definition. | P1 |
| **Capacity** | Partial | `Vehicles.seat_capacity` column | Capacity exists as a static vehicle property, but is completely unenforced during ride matching. Available seats are not calculated from active passengers. | P0 |
| **Waitlist** | **No** | None | No waitlist entity or queue. Requests without vehicles get set to `QUEUED` status and are abandoned. | P1 |
| **Recurring Booking** | **No** | None | No recurring booking entity or schedule unrolling engine to generate individual ride requests across dates. | P1 |
| **Cancellation** | **No** | None | No cancellation endpoint, cancellation cutoff rules, or seat release logic. | P1 |
| **Service Area** | **No** | None | No geographic boundary or polygon defining where shuttles operate. Matching uses a hardcoded 2000m radius around vehicles. | P2 |
| **Eligibility** | **No** | None | No pre-matching validation. Any authenticated employee can request any ride regardless of company, subscription, or route compatibility. | P0 |
| **Route Feasibility** | **No** | None | Route optimizer blindly adds pickups in nearest-neighbor order without evaluating passenger detour limits or arrival deadlines. | P1 |
| **Multi-Tenancy** | Partial | Foreign keys in DB tables | Foreign keys exist, but data isolation is entirely missing from repository queries, API routes, and JWT tokens. | P0 |

---

## 7. Database Relationship Analysis

### Current System ER Diagram (Implemented in `Tables.kt`):

```
+-------------------------------------------------------------+
|                          COMPANIES                          |
+-------------------------------------------------------------+
| PK id : uuid                                                |
|    name : varchar(255)                                      |
|    city : varchar(100)                                      |
|    employee_count : integer                                 |
|    created_at : timestamp                                   |
+-------------------------------------------------------------+
         |                       |                 |
         | 1:N                   | 1:N             | 1:N
         v                       v                 v
+------------------+   +-------------------+   +--------------------+
|    EMPLOYEES     |   |      DRIVERS      |   |      VEHICLES      |
+------------------+   +-------------------+   +--------------------+
| PK id            |   | PK id             |   | PK id              |
| FK company_id    |   | FK company_id     |   | FK company_id      |
|    email (UQ)    |   |    name           |   |    plate_number    |
|    name          |   |    phone          |   |    seat_capacity   |
|    home_lat/lng  |   |    created_at     |   |    last_known_lat  |
|    created_at    |   +-------------------+   |    last_known_lng  |
+------------------+             |             |    last_location_at|
         |                       | 1:N         |    created_at      |
         |                       v             +--------------------+
         |             +-------------------+             |
         |             |       TRIPS       |             | 1:N
         |             +-------------------+             |
         |             | PK id             | <-----------+
         |             | FK vehicle_id     |
         |             | FK driver_id      |
         |             |    start_time     |
         |             |    status         |
         |             +-------------------+
         |                       |
         | 1:N                   | 1:N (nullable)
         v                       v
+-------------------------------------------------------------+
|                        RIDE_REQUESTS                        |
+-------------------------------------------------------------+
| PK id : uuid                                                |
| FK trip_id : uuid (NULLABLE)                                |
| FK employee_id : uuid                                       |
|    pickup_lat / pickup_lng : double                         |
|    destination_lat / destination_lng : double               |
|    status : varchar(20) [REQUESTED, MATCHED, QUEUED, etc.]  |
|    requested_at : timestamp                                 |
+-------------------------------------------------------------+

+-------------------------------------------------------------+
|                           ADMINS                            |
+-------------------------------------------------------------+
| PK id : uuid                                                |
| FK company_id : uuid -> companies(id)                       |
|    email : varchar(255) (UQ)                                |
|    password_hash : varchar(255)                             |
|    created_at : timestamp                                   |
+-------------------------------------------------------------+
```

---

### Conceptual Target ER Diagram (Blueprint Alignment — NOT IMPLEMENTED):

```
+-----------------------------------------------------------------------------------+
|                                     COMPANIES                                     |
+-----------------------------------------------------------------------------------+
| PK id : uuid | name | city | subscription_status | cancellation_cutoff_mins | ... |
+-----------------------------------------------------------------------------------+
    |           |               |                  |                   |
    | 1:N       | 1:N           | 1:N              | 1:N               | 1:N
    v           v               v                  v                   v
+-------+   +---------+   +------------+   +---------------+   +--------------------+
|ADMINS |   |EMPLOYEES|   |  DRIVERS   |   |   VEHICLES    |   |    PICKUP_ZONES    |
+-------+   +---------+   +------------+   +---------------+   +--------------------+
    |           |               |                  |           | PK id              |
    |           |               |                  |           | FK company_id      |
    |           |               |                  |           |    name            |
    |           |               |                  |           |    location : geog |
    |           |               |                  |           |    status (APPROVED|
    |           |               |                  |           |            SUGGEST)|
    |           |               |                  |           +--------------------+
    |           |               |                  |                     |
    |           |               |                  |                     | 1:N
    |           |               |                  |                     v
    |           |               |                  |           +--------------------+
    |           |               |                  |           | SCHEDULE_STOPS     |
    |           |               |                  |           +--------------------+
    |           |               |                  |           | PK id              |
    |           |               |                  |           | FK schedule_id     |
    |           |               |                  |           | FK pickup_zone_id  |
    |           |               |                  |           |    stop_sequence   |
    |           |               |                  |           |    offset_minutes  |
    |           |               |                  |           +--------------------+
    |           |               |                  |                     ^
    |           |               |                  |                     | N:1
    |           |               |                  |           +--------------------+
    |           |               |                  |           | SHUTTLE_SCHEDULES  |
    |           |               |                  |           +--------------------+
    |           |               |                  |           | PK id              |
    |           |               |                  |           | FK company_id      |
    |           |               |                  |           |    name            |
    |           |               |                  |           |    departure_time  |
    |           |               |                  |           |    days_of_week    |
    |           |               |                  |           |    active_status   |
    |           |               |                  |           +--------------------+
    |           |               |                  |                     |
    |           |               |                  |                     | 1:N
    |           |               |                  |                     v
    |           |               |                  |           +--------------------+
    |           |               |                  +---------> |       TRIPS        |
    |           |               |                              +--------------------+
    |           |               +----------------------------> | PK id              |
    |           |                                              | FK schedule_id     |
    |           |                                              | FK vehicle_id      |
    |           |                                              | FK driver_id       |
    |           |                                              |    service_date    |
    |           |                                              |    scheduled_start |
    |           |                                              |    status          |
    |           |                                              +--------------------+
    |           |                                                        |
    |           | 1:N                                                    | 1:N
    |           v                                                        v
+-------------------------------+                              +--------------------+
|      RECURRING_BOOKINGS       |                              |     TRIP_STOPS     |
+-------------------------------+                              +--------------------+
| PK id                         |                              | PK id              |
| FK employee_id                |                              | FK trip_id         |
| FK schedule_id                |                              | FK pickup_zone_id  |
| FK pickup_zone_id             |                              |    stop_sequence   |
|    days_of_week               |                              |    scheduled_eta   |
|    status (ACTIVE, PAUSED)    |                              |    actual_arrival  |
+-------------------------------+                              +--------------------+
                |                                                        |
                | 1:N (unrolled instances)                               |
                v                                                        |
+------------------------------------------------------------------------+
|                             RIDE_REQUESTS                              |
+------------------------------------------------------------------------+
| PK id : uuid                                                           |
| FK company_id : uuid -> companies(id)                                  |
| FK trip_id : uuid (nullable when waitlisted/requested)                 |
| FK employee_id : uuid -> employees(id)                                 |
| FK recurring_booking_id : uuid (nullable)                              |
| FK pickup_zone_id : uuid -> pickup_zones(id)                           |
|    requested_lat / lng : double (door-to-door passenger origin)        |
|    status : enum [REQUESTED, CONFIRMED, WAITLISTED, PICKUP_PENDING,    |
|                   ONBOARD, COMPLETED, CANCELLED, NO_SHOW]              |
|    seat_number : int (nullable)                                        |
|    created_at : timestamp                                              |
+------------------------------------------------------------------------+
                |
                | 0..1 : 1
                v
+------------------------------------------------------------------------+
|                            WAITLIST_ENTRIES                            |
+------------------------------------------------------------------------+
| PK id : uuid                                                           |
| FK trip_id : uuid -> trips(id)                                         |
| FK ride_request_id : uuid -> ride_requests(id)                         |
|    queue_position : integer (FIFO sequence)                            |
|    created_at : timestamp                                              |
+------------------------------------------------------------------------+
```

---

## 8. Migration Analysis

### Current Schema Management:
- **No Migration Engine:** The project contains no Flyway or Liquibase configuration, and no versioned migration files (such as `V1__init.sql`).
- **Exposed DDL Auto-creation:** Schema generation is performed at runtime in `src/main/kotlin/com/dynashuttle/db/DatabaseFactory.kt:23-32`:
  ```kotlin
  transaction {
      SchemaUtils.createMissingTablesAndColumns(
          Companies, Employees, Drivers, Vehicles, Trips, RideRequests, Admins
      )
  }
  ```
- **Seed Script:** A standalone manual script exists at `scripts/seed_test_data.sql` (74 lines), intended for populating sample data for automated or manual testing.

### Migration & DDL Problems Identified:
1. **Destructive Schema Drift Inability:** `SchemaUtils.createMissingTablesAndColumns` only executes `CREATE TABLE IF NOT EXISTS` and `ALTER TABLE ADD COLUMN`. It **never drops columns, never alters column types, and never drops or modifies constraints**. Schema evolution in staging or production will lead to silent drift and orphaned database structures.
2. **Missing Spatial Index (GiST):** In `PostgresVehicleRepository.kt:61-70`, spatial distance queries rely on `ST_DWithin` and `ST_Distance`. However, neither `DatabaseFactory.kt` nor `Tables.kt` creates a PostGIS GiST index on vehicle locations. Without an index such as:
   ```sql
   CREATE INDEX idx_vehicles_location ON vehicles USING GIST (
       ST_MakePoint(last_known_lng, last_known_lat)::geography
   );
   ```
   PostgreSQL executes a full sequential table scan on `vehicles` on every single ride matching request.
3. **Missing Foreign Key Indexes:** Exposed automatically adds unique indexes and primary keys, but standard B-Tree indexes on foreign key columns (`employees.company_id`, `drivers.company_id`, `vehicles.company_id`, `trips.vehicle_id`, `trips.driver_id`, `ride_requests.trip_id`, `ride_requests.employee_id`) are **missing**. Foreign key lookups and cascading joins will suffer severe performance degradation as rows multiply.
4. **Dangerous String Enums:** All status fields (`trips.status`, `ride_requests.status`) are defined as unconstrained `varchar(20)`. There are no PostgreSQL `CHECK` constraints or Postgres custom enum types. Any arbitrary string can be written to the database without validation error.
5. **Coordinate Column Types:** Coordinates are stored as plain IEEE 754 floating-point `double` precision numbers (`home_lat`, `home_lng`, `pickup_lat`, `pickup_lng`, `last_known_lat`, `last_known_lng`). There are no check constraints validating that latitudes fall within $[-90, +90]$ and longitudes fall within $[-180, +180]$.
6. **No Pre-deployment PostGIS Verification:** The application assumes PostGIS is enabled on PostgreSQL. If `CREATE EXTENSION postgis;` has not been run manually by a database administrator, runtime calls to `findAvailableNear()` will fail with a fatal JDBC `PSQLException: function st_dwithin does not exist`.

---

## 9. Capacity & Concurrency Audit

### Execution Trace of Current Booking Code Path:
1. **HTTP Ingestion:** `POST /rides` is received in `RideRoutes.kt:17-24`. The body is deserialized into `RideRequestDto`.
2. **Matching Service Invocation:** `RoutingService.matchRideRequest(request)` is called (`RoutingService.kt:39`).
3. **Candidate Search:** `RoutingService.kt:40-44` calls `vehicleRepository.findAvailableNear(lat, lng, radiusMeters = 2000)`.
4. **SQL Execution in Repository:** In `PostgresVehicleRepository.kt:48-71`, the following SQL is executed:
   ```sql
   SELECT v.id AS vehicle_id, t.id AS trip_id, t.driver_id, v.seat_capacity,
          v.last_known_lat, v.last_known_lng
   FROM trips t
   JOIN vehicles v ON v.id = t.vehicle_id
   WHERE t.status = 'IN_PROGRESS'
     AND v.last_known_lat IS NOT NULL
     AND v.last_known_lng IS NOT NULL
     AND ST_DWithin(ST_MakePoint(v.last_known_lng, v.last_known_lat)::geography, ST_MakePoint(?, ?)::geography, ?)
   ORDER BY ST_Distance(...)
   ```
5. **False Capacity Mapping:** In `PostgresVehicleRepository.kt:85-93`:
   ```kotlin
   results += VehicleCandidate(
       vehicleId = rs.getString("vehicle_id"),
       tripId = rs.getString("trip_id"),
       driverId = rs.getString("driver_id"),
       currentLat = rs.getDouble("last_known_lat"),
       currentLng = rs.getDouble("last_known_lng"),
       availableSeats = rs.getInt("seat_capacity") // <-- BUG: Total capacity used as available seats!
   )
   ```
6. **Selection Without Verification:** In `RoutingService.kt:46`:
   ```kotlin
   val bestVehicle = candidates.firstOrNull() ?: run { ... }
   ```
   The service takes the first candidate. It **does not inspect `availableSeats`** nor verify if any seats remain.
7. **Unprotected Persistence:** In `RoutingService.kt:52`:
   ```kotlin
   val stored = rideRequestRepository.save(request, tripId = bestVehicle.tripId, status = "MATCHED")
   ```
   A new `ride_requests` record is inserted with `status = 'MATCHED'` in its own isolated transaction (`PostgresRideRequestRepository.kt:16-38`).

### Race Condition & Overbooking Vulnerability:
- **Scenario:** Vehicle capacity = 4. Active confirmed passengers = 4 (vehicle is completely full).
- **Execution:**
  1. An employee requests a ride.
  2. `findAvailableNear()` returns the vehicle with `availableSeats = 4` because it queries `v.seat_capacity`, completely ignoring the 4 active rows in `ride_requests`.
  3. `RoutingService` marks the request as `MATCHED`. The vehicle now has 5 confirmed passengers for 4 seats.
- **Concurrent Request Race Scenario:**
  1. Vehicle capacity = 4. Active passengers = 3 (1 seat remaining).
  2. Employee A and Employee B submit ride requests simultaneously on concurrent Ktor worker threads.
  3. Request A and Request B both invoke `findAvailableNear()` at time $t_0$.
  4. Neither query locks the `trips` or `vehicles` row (`FOR UPDATE` is not used).
  5. Both queries observe capacity.
  6. Request A inserts a `MATCHED` record at $t_1$.
  7. Request B inserts a `MATCHED` record at $t_2$.
  8. **Result:** 5 confirmed passengers are assigned to a 4-seat shuttle. Overbooking occurs silently.
- **Root Causes:**
  1. No calculation of `seat_capacity - COUNT(active_ride_requests)`.
  2. Complete lack of database concurrency controls (no pessimistic `SELECT FOR UPDATE`, no optimistic row versioning, no database constraint preventing over-allocation).
  3. Transaction isolation level in `DatabaseFactory.kt:43` is set to `TRANSACTION_REPEATABLE_READ`. However, because each repository method runs in a disconnected transaction, repeatable read isolation across queries provides zero protection against phantom bookings.

---

## 10. Multi-Tenant / Company Isolation Audit

DynaShuttle is intended as an enterprise B2B platform where corporate data and fleets must remain completely isolated. The audit reveals **severe multi-tenancy vulnerabilities**:

### 1. Spatial Search Lacks Company Scoping
- **Code Location:** `PostgresVehicleRepository.kt:48-71` (`findAvailableNear`)
- **Vulnerability:** The SQL query filters on `t.status = 'IN_PROGRESS'` and PostGIS spatial proximity. It **does not include `WHERE v.company_id = ?`**.
- **Impact:** An employee belonging to Company A located near a vehicle operated by Company B will be matched to Company B's shuttle. Company B's vehicle manifest will display Company A's employee name.

### 2. Ride Request Submission Has No Tenant Validation
- **Code Location:** `RideRoutes.kt:17-24` & `RoutingService.kt:39-68`
- **Vulnerability:**
  - `RideRequestDto` accepts `employeeId` from the JSON body.
  - The route handler does not inspect the authenticated JWT caller (`call.principal<JWTPrincipal>()`).
  - Any authenticated user can submit a booking for an employee in any company.
  - Comment in `RideRoutes.kt:20`: `// TODO: validate employee belongs to a company with an active subscription`.

### 3. Trip Manifest Cross-Company Leakage
- **Code Location:** `TripRoutes.kt:34-45` & `TripService.kt:43-70`
- **Vulnerability:**
  - Endpoint `GET /trips/{id}/stops` accepts a raw `tripId` UUID from the path parameter.
  - It does not verify that the requesting driver belongs to the company owning the trip.
  - A driver from Company X can inspect the stop list, pickup locations, and passenger employee names of Company Y.

### 4. WebSocket GPS Spoofing & Eavesdropping
- **Code Location:** `WebSocketRoutes.kt:44-88`
- **Vulnerability:**
  - `/ws/driver/{tripId}` accepts GPS telemetry from any client holding a valid `app-user` JWT token (including any employee). It does not verify that the sender is the driver assigned to that trip or company.
  - `/ws/trip/{tripId}` allows any authenticated user to subscribe to real-time location telemetry of any trip across the database.

### 5. JWT Claims Omit Company ID
- **Code Location:** `AuthService.kt:76-83` (`generateToken`)
- **Vulnerability:**
  ```kotlin
  private fun generateToken(userId: String, role: String): String {
      return JWT.create()
          .withIssuer(jwtIssuer)
          .withClaim("role", role)
          .withClaim("userId", userId)
          .withExpiresAt(...)
          .sign(Algorithm.HMAC256(jwtSecret))
  }
  ```
  The token stores `userId` and `role`, but omits `companyId`. Even though `AuthRepository` fetches `companyId` for both employees and admins (`PostgresAuthRepository.kt:18,34`), `AuthService` discards it when building the JWT. As a result, downstream route interceptors cannot perform tenant authorization without performing an extra database lookup.

---

## 11. Routing Architecture Audit

### Responsibility Separation:
The blueprint dictates:
$$\text{Ride Request} \longrightarrow \text{Eligibility / Matching} \longrightarrow \text{Capacity} \longrightarrow \text{Time Constraints} \longrightarrow \text{Route Feasibility} \longrightarrow \text{Route Optimizer}$$

In the current implementation, these layers are collapsed and inverted:
- **No Eligibility Stage:** `RoutingService.kt:39-68` performs no eligibility checks (company match, subscription status, service area boundaries, allowed hours).
- **Single-Stop "Optimization":** In `RoutingService.kt:54-58`:
  ```kotlin
  val optimizedStops = routeOptimizer.optimize(
      vehicleLat = bestVehicle.currentLat,
      vehicleLng = bestVehicle.currentLng,
      stops = listOf(StopCandidate(stored.id, request.pickupLat, request.pickupLng))
  )
  ```
  The service passes **only the single new stop** to `RouteOptimizer.optimize()`. It does **not** load the vehicle's existing assigned passengers. As a result, the optimizer simply calculates a straight-line ETA from the vehicle to the new passenger, completely failing to optimize the trip's multi-passenger route or calculate detour impacts on existing riders.
- **Destinations Completely Ignored:**
  - In `PostgresRideRequestRepository.kt:25-26`, `destinationLat` and `destinationLng` are saved to the database.
  - However, neither `RoutingService`, `TripService`, nor `GreedyNearestNeighbourOptimizer` ever reads or processes destination coordinates. The routing engine is strictly a pickup collector. Drop-offs are not scheduled, sequenced, or estimated.
- **Optimizer Boundary Violations:** `GreedyNearestNeighbourOptimizer` correctly isolates distance calculation (`haversineMeters`) and delegates ETA calculation to `EtaEstimator`. However, because it lacks passenger capacity and drop-off modeling, it cannot determine whether adding a passenger violates a vehicle's route constraints.

---

## 12. Recurring Booking Readiness

### Current Architecture Assessment:
- **Readiness:** **0% (Completely Missing)**
- **Schema Gaps:**
  - No `shuttle_schedules` table exists to hold recurring route definitions, departure times, or frequency rules.
  - No `recurring_bookings` table exists to store an employee's subscription to a recurring shuttle.
  - No unrolling worker or scheduler (such as Quartz or Kotlin coroutine background worker) exists to generate daily instances.
- **Required New Domain Structures:**
  1. `ShuttleSchedule` entity: defines tenant, route corridor, fixed departure time, day-of-week bitmask/list, and assigned default vehicle/driver.
  2. `RecurringBooking` entity: links `employee_id`, `schedule_id`, active date ranges, and pickup zone.
  3. Generation Service / Worker: A daily batch job that unrolls active recurring bookings into individual `RideRequests` linked to that calendar day's concrete `Trip` instance.
  4. Individual Independence: Each generated `RideRequest` must have its own lifecycle (can be cancelled for a single day without terminating the recurring subscription).

---

## 13. Pickup Zone Readiness

### Current Architecture Assessment:
- **Readiness:** **0% (Completely Missing)**
- **Current Geographic Representation:**
  - `Employees.homeLat / homeLng`: raw floating-point doubles.
  - `RideRequests.pickupLat / pickupLng / destinationLat / destinationLng`: raw floating-point doubles.
  - `Vehicles.lastKnownLat / lastKnownLng`: raw floating-point doubles.
  - The system operates purely door-to-door.
- **Blueprint Service Area & Zone Model Gaps:**
  - The blueprint requires a **hybrid pickup-zone model**:
    1. Admin-created approved operational zones.
    2. System-suggested potential zones derived from passenger clustering, requiring admin review.
  - No `pickup_zones` table exists.
  - No zone lifecycle enum exists (`PROPOSED` / `SUGGESTED` vs. `APPROVED` / `ACTIVE`).
  - No spatial aggregation or clustering service exists.
  - No snapping mechanism exists to map an employee's requested door-to-door coordinate to the nearest valid pickup zone within walking threshold.

---

## 14. Booking State Machine Gap

### Comparison Matrix:

| State | Status in Current Codebase | Blueprint Target State | Gap Description & Risks |
|---|---|---|---|
| `REQUESTED` | Present (`Tables.kt:70`) | `REQUESTED` | Initial state when employee submits a booking. |
| `MATCHED` | Present (`Tables.kt:70`) | `CONFIRMED` | Current code uses `MATCHED`. Conflates algorithmic matching with formal booking confirmation. Missing seat reservation guarantee. |
| `QUEUED` | Present (`Tables.kt:70`) | `WAITLISTED` | Current code sets `QUEUED` when no vehicle is within 2000m. It does not queue for a full vehicle, nor does it maintain queue ordering. |
| `PICKUP_PENDING` | Missing | `PICKUP_PENDING` | In current code, state jumps directly from `MATCHED` to `PICKED_UP`. There is no state indicating the vehicle is actively en route to this specific passenger. |
| `PICKED_UP` | Present (`Tables.kt:70`) | `ONBOARD` | Current code uses `PICKED_UP`. Represents passenger boarding. |
| `COMPLETED` | Present (`Tables.kt:70`) | `COMPLETED` | Present in table definition comments, but `TripService` never transitions a ride request to `COMPLETED` (drop-offs are not tracked). |
| `NO_SHOW` | Present (`Tables.kt:70`) | `NO_SHOW` | Implemented in `TripService.markNoShow()`. Driver marks passenger absent. |
| `CANCELLED` | Missing | `CANCELLED` | Completely missing from `RideRequests`. Passengers cannot cancel bookings; seats cannot be released. |

### Technical State Machine Gaps:
1. **No Kotlin Enum Definition:** Statuses are defined as string literals in table comments and raw strings in code (`"REQUESTED"`, `"MATCHED"`, `"QUEUED"`, `"PICKED_UP"`, `"NO_SHOW"`). There is no Kotlin `enum class RideRequestStatus` ensuring type safety.
2. **Missing State Transitions & Validation:** In `PostgresRideRequestRepository.updateStatus()`, any status string can overwrite any existing status without validating transition legality (e.g., transitioning from `COMPLETED` back to `REQUESTED` is permitted by the repository).

---

## 15. API/Route Impact

### Existing API Inventory:
- `GET /health` (`Routing.kt:119`) — Unauthenticated health check.
- `POST /rides` (`RideRoutes.kt:17`) — Authenticated (`app-user`). Submits a ride request.
- `GET /rides/{id}` (`RideRoutes.kt:26`) — Authenticated (`app-user`). Polls status of a ride request.
- `GET /trips/{id}/stops` (`TripRoutes.kt:34`) — Authenticated (`app-user`). Retrieves driver stop manifest.
- `POST /trips/{id}/stops/{stopId}/pickup` (`TripRoutes.kt:47`) — Authenticated (`app-user`). Marks passenger picked up.
- `POST /trips/{id}/stops/{stopId}/no-show` (`TripRoutes.kt:63`) — Authenticated (`app-user`). Marks passenger no-show.
- `POST /auth/employee/request-otp` (`AuthRoutes.kt:17`) — Public. Initiates OTP generation.
- `POST /auth/employee/verify-otp` (`AuthRoutes.kt:33`) — Public. Validates OTP and returns JWT.
- `POST /auth/admin/login` (`AuthRoutes.kt:46`) — Public. Validates admin password and returns JWT.
- `WS /ws/driver/{tripId}` (`WebSocketRoutes.kt:45`) — Authenticated (`app-user`). Ingestion for driver GPS.
- `WS /ws/trip/{tripId}` (`WebSocketRoutes.kt:76`) — Authenticated (`app-user`). Real-time location stream for riders.

### Missing Blueprint Endpoints:
1. **Booking Lifecycle:**
   - `DELETE /rides/{id}` or `POST /rides/{id}/cancel`: Cancel a pending/confirmed ride and release seat.
   - `GET /rides/my-rides`: List upcoming and historical rides for the calling employee.
   - `POST /rides/recurring`: Create a recurring booking schedule.
2. **Schedules & Discovery:**
   - `GET /schedules`: List corporate shuttle schedules available for an employee's company.
   - `GET /trips/available`: Discover upcoming trips matching origin/destination.
3. **Pickup Zones & Admin Management:**
   - `GET /admin/zones`: List approved and suggested pickup zones.
   - `POST /admin/zones`: Create an approved operational pickup zone.
   - `POST /admin/zones/{id}/approve`: Approve a system-suggested pickup zone.
   - `POST /admin/schedules`: Create and configure corporate shuttle schedules.
   - `GET /admin/trips/live`: Monitor fleet status across company vehicles.
4. **Driver Workflow:**
   - `POST /trips/{id}/stops/{stopId}/dropoff`: Mark passenger dropped off.
   - `POST /trips/{id}/start` & `POST /trips/{id}/complete`: Manage trip lifecycle.

---

## 16. Security / Authorization Gaps

1. **Role Enforcement Lacking in Routes:**
   - In `Authentication.kt:22-30`, the `app-user` JWT verifier accepts tokens where `role in listOf("employee", "driver")`.
   - In `TripRoutes.kt:19-23` and `WebSocketRoutes.kt:37-38`, routes are protected by `authenticate("app-user")`.
   - However, **neither handler checks whether `principal.role == "driver"`**. An employee can invoke driver pickup/no-show actions or broadcast fake vehicle GPS coordinates.
2. **Zero Company Scoping in Route Interceptors:**
   - Because `companyId` is not present in the JWT claims, no route interceptor or handler verifies tenant ownership. An employee of Company A can view trips and ride statuses of Company B.
3. **IDOR (Insecure Direct Object Reference) in Ride Booking:**
   - In `RideRoutes.kt:18`, the route handler parses `RideRequestDto` containing an arbitrary `employeeId` string without verifying that `principal.userId == request.employeeId`. Any employee can create rides under other colleagues' IDs.
4. **WebSocket Authentication Weakness:**
   - `WebSocketRoutes.kt:44` relies on Ktor's `authenticate("app-user")` on the HTTP upgrade handshake. As documented in code comments (`WebSocketRoutes.kt:33-36`), standard browser WebSocket APIs cannot pass custom `Authorization` headers. While native mobile clients can pass headers during the handshake, web-based admin dashboards will fail to authenticate without query parameter token support.
5. **In-Memory OTP Cache Vulnerability:**
   - `AuthService.kt:27` stores OTPs in `ConcurrentHashMap<String, OtpRecord>()`. There is no rate limiting on OTP attempts. An attacker can brute-force 6-digit OTPs before the 5-minute expiration, as failed attempts do not increment a lockout counter.

---

## 17. Data Integrity Risks

| Risk | Evidence | Impact | Suggested Future Direction |
|---|---|---|---|
| **Overbooking & Phantom Seats** | `PostgresVehicleRepository.kt:91`, `RoutingService.kt:46` | Shuttles become overcrowded; passengers stranded without seats. | Compute available seats as `capacity - COUNT(active bookings)`; wrap booking in a pessimistic `FOR UPDATE` transaction. |
| **Cross-Company Data Leakage** | `PostgresVehicleRepository.kt:48-71`, `RideRoutes.kt:18` | Breach of corporate confidentiality; inter-company vehicle contention. | Scope all vehicle searches and bookings by `company_id`; embed `companyId` in JWT claims. |
| **Silent Schema Desynchronization** | `DatabaseFactory.kt:23-32` (`SchemaUtils`) | Production database divergence; inability to drop columns or manage migrations. | Adopt Flyway with explicit, versioned, reviewed SQL migration scripts. |
| **Spatial Query Degradation** | `PostgresVehicleRepository.kt:61-70`, `Tables.kt:49-50` | $O(N)$ sequential scans on vehicles table under high booking load. | Migrate to PostGIS `geography(Point, 4326)` columns with GiST spatial indexes. |
| **Unbounded WebSocket DB Saturation** | `WebSocketRoutes.kt:58-66`, `DatabaseFactory.kt:41` | HikariCP pool exhaustion (max pool size 10) crashes HTTP endpoints during GPS broadcasts. | Buffer GPS points in Redis/memory; write to PostgreSQL asynchronously or throttle updates. |
| **Concurrent Map Modification Exception** | `WebSocketRoutes.kt:40,79` (`ConcurrentHashMap<String, MutableList<...>>`) | Unsynchronized access to `MutableList` can throw `ConcurrentModificationException` during broadcast. | Use `CopyOnWriteArrayList` or thread-safe channel actor pattern for listener sessions. |
| **Orphaned Ride Requests** | `Tables.kt:63` (`RideRequests.tripId` is nullable) | Requests set to `QUEUED` remain stranded in database indefinitely without cleanup or promotion. | Introduce a formal Waitlist worker and explicit lifecycle reaper for expired requests. |
| **Unconstrained Status Text** | `Tables.kt:59,70` (`varchar(20)`) | Corrupt or invalid state strings entered into database without rejection. | Convert status columns to PostgreSQL ENUMs or enforce check constraints. |

---

## 18. Recommended Target Domain Model

### Conceptual Domain Entities (NOT IMPLEMENTED):

1. **Company:**
   - `id`: UUID (PK)
   - `name`: String
   - `city`: String
   - `subscriptionStatus`: Enum (`ACTIVE`, `TRIAL`, `SUSPENDED`)
   - `cancellationCutoffMinutes`: Int (default 30)

2. **PickupZone:**
   - `id`: UUID (PK)
   - `companyId`: UUID (FK -> Company)
   - `name`: String
   - `boundary`: PostGIS Geography (Point or Polygon)
   - `status`: Enum (`PROPOSED`, `APPROVED`, `INACTIVE`)
   - `approvedByAdminId`: UUID (FK -> Admin, nullable)

3. **ShuttleSchedule:**
   - `id`: UUID (PK)
   - `companyId`: UUID (FK -> Company)
   - `name`: String (e.g., "Whitefield Campus Morning Route 1")
   - `departureTime`: LocalTime (e.g., 07:30)
   - `daysOfWeek`: Set<DayOfWeek> (e.g., MON-FRI)
   - `defaultVehicleId`: UUID (FK -> Vehicle, nullable)
   - `defaultDriverId`: UUID (FK -> Driver, nullable)
   - `isActive`: Boolean

4. **ScheduleStop:**
   - `id`: UUID (PK)
   - `scheduleId`: UUID (FK -> ShuttleSchedule)
   - `pickupZoneId`: UUID (FK -> PickupZone)
   - `stopSequence`: Int
   - `offsetMinutes`: Int

5. **Trip (Occurrence):**
   - `id`: UUID (PK)
   - `scheduleId`: UUID (FK -> ShuttleSchedule, nullable for ad-hoc)
   - `vehicleId`: UUID (FK -> Vehicle)
   - `driverId`: UUID (FK -> Driver)
   - `serviceDate`: LocalDate
   - `scheduledStartTime`: Instant
   - `status`: Enum (`SCHEDULED`, `DISPATCHED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`)

6. **TripStop (Concrete Occurrence Stop):**
   - `id`: UUID (PK)
   - `tripId`: UUID (FK -> Trip)
   - `pickupZoneId`: UUID (FK -> PickupZone)
   - `stopSequence`: Int
   - `scheduledEta`: Instant
   - `actualArrival`: Instant?

7. **RecurringBooking:**
   - `id`: UUID (PK)
   - `employeeId`: UUID (FK -> Employee)
   - `scheduleId`: UUID (FK -> ShuttleSchedule)
   - `pickupZoneId`: UUID (FK -> PickupZone)
   - `daysOfWeek`: Set<DayOfWeek>
   - `startDate`: LocalDate
   - `endDate`: LocalDate?
   - `status`: Enum (`ACTIVE`, `PAUSED`, `CANCELLED`)

8. **RideRequest (Individual Booking Instance):**
   - `id`: UUID (PK)
   - `companyId`: UUID (FK -> Company)
   - `tripId`: UUID (FK -> Trip, nullable)
   - `employeeId`: UUID (FK -> Employee)
   - `recurringBookingId`: UUID (FK -> RecurringBooking, nullable)
   - `pickupZoneId`: UUID (FK -> PickupZone)
   - `passengerOriginLat / Lng`: Double
   - `destinationLat / Lng`: Double
   - `status`: Enum (`REQUESTED`, `CONFIRMED`, `WAITLISTED`, `PICKUP_PENDING`, `ONBOARD`, `COMPLETED`, `CANCELLED`, `NO_SHOW`)
   - `seatNumber`: Int?
   - `requestedAt`: Instant

9. **WaitlistEntry:**
   - `id`: UUID (PK)
   - `tripId`: UUID (FK -> Trip)
   - `rideRequestId`: UUID (FK -> RideRequest)
   - `queuePosition`: Int
   - `createdAt`: Instant

---

## 19. Gap Classification

### EXISTING (Already implemented properly):
- **Core HTTP & WebSocket Foundation:** Ktor Netty runtime, CIO HTTP client, Kotlinx Serialization, and CORS plugins.
- **SOLID Composition Root:** Clean single wiring point in `Routing.kt` using narrow interfaces.
- **Pluggable ETA Estimation:** `EtaEstimator` interface cleanly implemented by `TomTomEtaEstimator` with seamless fallback to `HaversineEtaEstimator`.
- **Pluggable Notifications:** `OtpSender` cleanly abstracts email/SMS delivery (`BrevoOtpSender` / `NoOpOtpSender`).
- **Basic Password & Token Cryptography:** BCrypt hashing (`BcryptPasswordHasher`) and JWT signature/verification.

### PARTIAL (Exists but requires substantial refactoring):
- **Trips & Ride Requests:** Tables and models exist, but represent flat, ad-hoc entities lacking links to schedules, zones, seat numbers, or comprehensive status lifecycles.
- **Authentication:** Dual realms exist (`app-user`, `admin`), but JWT tokens lack `companyId` and routes fail to enforce role separation or ownership verification.
- **Driver Stop Manifest:** `TripService.getStopList()` computes stops on the fly, but only supports pickups (no drop-offs) and does not persist stop sequencing.

### MISSING (No meaningful implementation exists):
- **Shuttle Schedule Template:** Zero entities, tables, or APIs for schedule definition.
- **Recurring Booking System:** Zero recurring booking entities or unrolling background workers.
- **Pickup Zones:** Zero representation of operational zones, geographic clusters, or approval workflows.
- **Waitlist Management:** Zero FIFO queueing or promotion mechanics.
- **Booking Cancellation:** Zero cancellation endpoints, cutoff policies, or seat release triggers.
- **Database Migration Tooling:** Zero Flyway/Liquibase version-controlled migrations.

### RISK (Exists but has severe correctness, security, or concurrency bugs):
- **Vehicle Availability & Capacity:** `PostgresVehicleRepository.findAvailableNear()` returns raw total capacity and ignores existing passengers.
- **Booking Concurrency:** No locking or transaction boundaries in `RoutingService.matchRideRequest()`, guaranteeing overbooking under concurrency.
- **Multi-Tenant Leakage:** Spatial vehicle queries and booking routes do not isolate by `company_id`.
- **WebSocket Database Write Storm:** Every 5-second GPS ping triggers synchronous PostgreSQL reads and writes on the HikariCP pool.
- **Unsynchronized WebSocket Sessions:** `tripListeners` uses a non-thread-safe `MutableList` inside a `ConcurrentHashMap`.

### FUTURE (Can wait for post-MVP enhancements):
- Advanced VRPTW (Vehicle Routing Problem with Time Windows) route optimization.
- AI-driven automated pickup zone clustering suggestions.
- Passenger in-app fare payments or corporate cross-charging billing engines.
- Multi-company shared fleet co-loading algorithms.

---

## 20. Recommended Implementation Order

To ensure architectural integrity without breaking existing working functionality, implementation should proceed in four sequential phases:

```
Phase 1: Database & Safety Foundation
  (Flyway, Constraints, GiST Indexes, Multi-Tenant Scoping, Capacity Locking)
       │
       ▼
Phase 2: Domain Entity Realignment & Zones
  (Schedules, Trips as Occurrences, Pickup Zones, Type-Safe State Machine)
       │
       ▼
Phase 3: Booking Engine & Waitlist
  (Eligibility Pre-check, Full-Route Optimization, Waitlist Promotion, Cancellations)
       │
       ▼
Phase 4: Recurring Bookings & Operational Tooling
  (Recurring Booking Unrolling Worker, Admin Zone Approval Workflow, Driver Drop-offs)
```

### Phase 1: Database & Safety Foundation
*Dependencies: None.*
1. Introduce **Flyway** migrations; baseline the existing 7 tables into `V1__initial_schema.sql`.
2. Add migration `V2__add_indexes_and_constraints.sql`:
   - Create GiST spatial index on vehicles.
   - Add B-Tree indexes on all foreign keys.
   - Enforce check constraints on coordinate ranges.
3. Fix multi-tenant scoping in `PostgresVehicleRepository.kt`: add `v.company_id = ?` to `findAvailableNear()`.
4. Fix capacity calculation and concurrency:
   - Query `seat_capacity - COUNT(active_ride_requests)`.
   - Implement pessimistic `SELECT FOR UPDATE` or serializable transaction for booking confirmation.
5. Embed `companyId` into JWT claims in `AuthService.kt`.

### Phase 2: Domain Entity Realignment & Zones
*Dependencies: Phase 1 (Flyway migrations and tenant-safe schema).*
1. Create tables `pickup_zones`, `shuttle_schedules`, `schedule_stops`, and `trip_stops`.
2. Decouple `Trips` from ad-hoc creation by linking `trip.schedule_id -> shuttle_schedules.id`.
3. Introduce Kotlin `enum class RideRequestStatus` with valid transition guards.
4. Implement `PickupZoneRepository` and associate employee home locations with nearest approved zones.

### Phase 3: Booking Engine & Waitlist
*Dependencies: Phase 2 (Zones, Schedules, and State Machine).*
1. Introduce explicit **Eligibility Service** upstream of the Route Optimizer.
2. Update `RoutingService` to optimize across **all active trip stops** plus the candidate stop, including both pickup and destination drop-off points.
3. Implement `WaitlistRepository`: when capacity is zero on an eligible trip, transition request to `WAITLISTED`.
4. Implement cancellation endpoints (`POST /rides/{id}/cancel`): release seat and trigger waitlist promotion.

### Phase 4: Recurring Bookings & Operational Tooling
*Dependencies: Phase 3 (Complete booking lifecycle and capacity management).*
1. Create `recurring_bookings` table.
2. Build daily recurring unrolling background worker to generate day-specific `RideRequest` records.
3. Build admin zone review and approval endpoints.
4. Complete driver workflow by adding passenger drop-off tracking to `TripService`.

---

## 21. What Should NOT Be Changed

The audit specifically confirms that the following architectural components are well-designed and should **NOT** be rewritten:

1. **Dependency Inversion & Service Architecture:** `RoutingService`, `TripService`, and `AuthService` are cleanly decoupled from database frameworks via narrow interfaces. Their structure is sound and should be retained.
2. **Composition Root in `Routing.kt`:** Centralizing application dependency wiring in `Routing.kt` adheres to standard SOLID principles and makes replacing individual components straightforward.
3. **Third-Party Service Abstractions:**
   - `EtaEstimator` interface with `TomTomEtaEstimator` and `HaversineEtaEstimator` fallback.
   - `OtpSender` interface with `BrevoOtpSender` and `NoOpOtpSender` fallback.
   - `PasswordHasher` interface with `BcryptPasswordHasher`.
   - Shared `HttpClient(CIO)` instance managed at application lifecycle level.
4. **WebSocket Architecture Framing:** The separation between driver broadcast (`/ws/driver/{tripId}`) and rider listener (`/ws/trip/{tripId}`) channels is conceptually correct (only session collection synchronization and DB write throttling need internal hardening).
5. **Testing Architecture Style:** The unit testing suite utilizes clean, zero-dependency Kotlin in-memory fakes. This pattern should be preserved and expanded to new services.

---

## 22. Final Findings

### Critical Findings (Immediate Blockers)
1. **Unchecked Overbooking:** `PostgresVehicleRepository.kt:91` maps static seat capacity as available seats without deducting existing riders, and `RoutingService.kt:46` assigns vehicles without verifying available seats or taking database locks.
2. **Cross-Company Fleet & Data Bleed:** Spatial vehicle queries (`findAvailableNear`) omit `company_id`, allowing employees from one company to book shuttles belonging to another company.
3. **No Migration Engine:** Relying on `SchemaUtils.createMissingTablesAndColumns()` precludes safe production DDL changes, index creation, and column modifications.
4. **JWT Security Holes:** JWT tokens omit `companyId`, route handlers do not enforce role boundaries between drivers and employees, and ride booking endpoints permit IDOR spoofing of `employeeId`.
5. **Missing Schedule & Recurring Domain Model:** The database contains no concepts of shuttle schedules, pickup zones, or recurring bookings, which are fundamental to the product blueprint.

### Important Findings (Non-Blocking Gaps)
1. **Drop-Off & Destination Ignored:** Passenger destinations are stored in the database but completely excluded from route optimization and trip execution.
2. **Missing PostGIS Spatial Index:** Spatial queries run via sequential table scans due to missing GiST indexes on vehicle coordinates.
3. **WebSocket Database Write Contention:** Drivers transmitting GPS telemetry every 5 seconds execute synchronous database queries and updates against a small HikariCP connection pool.
4. **Unsynchronized WebSocket Session Map:** `tripListeners` in `WebSocketRoutes.kt:40` uses a non-thread-safe `MutableList` inside a `ConcurrentHashMap`.
5. **In-Memory Volatile OTP Storage:** OTPs are stored in an in-memory map without persistent storage or brute-force rate-limiting.

### Future Enhancements (Post-MVP)
1. Advanced VRPTW vehicle scheduling algorithms replacing the greedy nearest-neighbor heuristic.
2. Machine-learning clustering of employee home coordinates to automatically propose new pickup zones.
3. Redis pub/sub backplane replacing in-memory WebSocket session maps for multi-node horizontal scaling.

### Unknowns
- **Production PostGIS Extension State:** UNVERIFIED — It cannot be verified from the codebase whether target production databases have the PostGIS extension pre-installed with appropriate superuser privileges.
- **Driver Authentication Strategy:** UNVERIFIED — While the JWT verifier accepts a `"driver"` role, the repository has no driver credentials table, PIN column, or driver login endpoint. How drivers obtain authenticated tokens in practice is currently unrepresented in code.

---
*End of DynaShuttle Database & Domain Architecture Gap Audit.*
