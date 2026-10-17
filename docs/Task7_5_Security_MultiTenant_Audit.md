# Task 7.5 — Security & Multi-Tenant Isolation Audit

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

---

## 2. Scope

This audit evaluates the security architecture, authentication mechanisms, authorization enforcement, and multi-tenant isolation boundaries of the DynaShuttle backend. 

The audit answers:
> **Where is the production backend secure, where are authorization boundaries incomplete, and where can cross-tenant or cross-user access occur?**

The audit covers:
1. Authentication, JWT issuance, verification, secret handling, and realm separation.
2. Complete inventory and security analysis of all 26 registered endpoints (HTTP and WebSocket).
3. Role-based access control (RBAC) across Employee, Driver, and Admin personas.
4. Resource ownership and IDOR/BOLA protections across rides, trips, stops, pickup zones, and schedules.
5. Multi-tenant isolation through the entire call graph: JWT $\to$ Route $\to$ Service $\to$ Repository $\to$ SQL.
6. Repository-level authorization assumptions and "dangerous-by-default" query patterns.
7. Database-level constraints, lack of Row-Level Security (RLS), and cross-tenant foreign key risks.
8. Sensitive information exposure (PII, credentials, and telemetry).
9. Integration of WebSocket security findings established in Task 7.4.

---

## 3. Authentication Audit

### 3.1 JWT Architecture & Configuration
Authentication is configured in [Authentication.kt:17-42](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/plugins/Authentication.kt#L17-L42). Two authentication realms are declared:

1. **`app-user` Realm:**
   * **Target Roles:** `employee`, `driver`
   * **Issuer:** `"dynashuttle"`
   * **Algorithm:** `HMAC256`
   * **Role Verification:**
     ```kotlin
     .withClaim("role") { value, _ -> value.asString() in listOf("employee", "driver") }
     ```
2. **`admin` Realm:**
   * **Target Role:** `admin`
   * **Issuer:** `"dynashuttle"`
   * **Algorithm:** `HMAC256`
   * **Role Verification:**
     ```kotlin
     .withClaim("role", "admin")
     ```

### 3.2 Key Findings on Authentication:

1. **CRITICAL: Driver Plaintext OTP Leakage (Authentication Bypass):**
   In [AuthRoutes.kt:63-81](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/AuthRoutes.kt#L63-L81):
   ```kotlin
   post("/driver/request-otp") {
       val request = call.receive<DriverOtpRequest>()
       val otp = authService.requestDriverOtp(request.phone)
       if (otp == null) {
           call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Driver not found"))
       } else {
           call.respond(HttpStatusCode.OK, DriverOtpResponse(otp = otp, message = "OTP generated"))
       }
   }
   ```
   Unlike employee OTP requests (which redact the OTP when `realOtpDeliveryConfigured` is true in [AuthRoutes.kt:28](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/AuthRoutes.kt#L28)), the driver OTP endpoint **unconditionally returns the generated OTP in plaintext in the HTTP response**. Any unauthenticated attacker with a driver's phone number receives the OTP immediately, calls `POST /auth/driver/verify-otp`, and obtains a legitimate driver JWT.

2. **HIGH: Default Fallback JWT Secret:**
   In [Authentication.kt:18](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/plugins/Authentication.kt#L18) and [Routing.kt:114](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/plugins/Routing.kt#L114):
   ```kotlin
   val secret = Env.get("JWT_SECRET", "dev-secret-change-me")!!
   ```
   If the `JWT_SECRET` environment variable is not supplied, the backend defaults to `"dev-secret-change-me"`. An attacker knowing this default can forge arbitrary tokens for any `userId`, `companyId`, and `role`, completely bypassing authentication.

3. **MEDIUM: Missing Audience (`aud`) Claim:**
   Tokens generated in [AuthService.kt:79-87](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/AuthService.kt#L79-L87) configure `withIssuer("dynashuttle")` and expiration (`24h`), but omit the `withAudience(...)` claim. Verifiers also omit audience validation.

4. **MEDIUM: Realm Isolation Conflict (Admin Lockout):**
   Endpoints wrapped in `authenticate("app-user")` ([RideRoutes.kt:17](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/RideRoutes.kt#L17), [TripRoutes.kt:26](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/TripRoutes.kt#L26), [WebSocketRoutes.kt:33](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/WebSocketRoutes.kt#L33)) reject tokens with `role == "admin"`. Although `RideRoutes.kt:49` attempts to handle admin callers (`callerUserId = if (user.role == "admin") null else user.userId`), an admin token cannot pass the Ktor verifier for `app-user`. Admins are locked out of viewing ride statuses and trip stops.

5. **LOW: Stateless Tokens Without Revocation:**
   JWTs remain valid for 24 hours without server-side revocation, blacklisting, or session tracking. Deactivated employees or drivers retain access until token expiry.

---

## 4. HTTP Endpoint Inventory

Tracing all routes from [Routing.kt:130-141](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/plugins/Routing.kt#L130-L141) identifies exactly 26 production endpoints (24 HTTP REST, 2 WebSockets):

| # | Method | Path | Auth Realm | Allowed Roles | Tenant Enforced? | Resource Ownership Enforced? | State Changing? | Primary Service / Repo Methods |
| :- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | `GET` | `/health` | None | Public | N/A | N/A | No | None |
| 2 | `POST` | `/auth/employee/request-otp` | None | Public | N/A | N/A | No | `AuthService.requestOtp`, `AuthRepo.findEmployeeByEmail` |
| 3 | `POST` | `/auth/employee/verify-otp` | None | Public | N/A | N/A | No | `AuthService.verifyOtp` |
| 4 | `POST` | `/auth/admin/login` | None | Public | N/A | N/A | No | `AuthService.loginAdmin`, `AuthRepo.findAdminByEmail` |
| 5 | `POST` | `/auth/driver/request-otp` | None | Public | N/A | N/A | No | `AuthService.requestDriverOtp`, `AuthRepo.findDriverByPhone` |
| 6 | `POST` | `/auth/driver/verify-otp` | None | Public | N/A | N/A | No | `AuthService.verifyDriverOtp` |
| 7 | `POST` | `/rides` | `app-user` | `employee` | Yes (`user.companyId`) | Yes (`request.employeeId == user.userId`) | Yes | `RoutingService.matchRideRequest`, `allocateSeat` |
| 8 | `GET` | `/rides/{id}` | `app-user` | `employee`, `admin`* | Yes (`callerCompanyId`) | Yes (`callerUserId == stored.employeeId`) | No | `RoutingService.getRideStatus`, `RideRepo.findById` |
| 9 | `POST` | `/rides/{id}/cancel` | `app-user` | `employee` | Yes (`callerCompanyId`) | Yes (`callerEmployeeId == stored.employeeId`) | Yes | `TripService.cancelRideRequest`, `transitionStatus` |
| 10 | `GET` | `/trips/{id}/stops` | `app-user` | `driver`, `employee` | Yes (`trip.companyId`) | Yes (`trip.driverId == userId` or `hasEmployeeOnTrip`) | No | `TripService.getStopList`, `findActiveByTripId` |
| 11 | `POST` | `/trips/{id}/stops/{stopId}/pickup` | `app-user` | `driver` | Yes (`trip.companyId`) | Yes (`trip.driverId == userId` & `existing.tripId == id`) | Yes | `TripService.markPickedUp`, `transitionStatus` |
| 12 | `POST` | `/trips/{id}/stops/{stopId}/no-show` | `app-user` | `driver` | Yes (`trip.companyId`) | Yes (`trip.driverId == userId` & `existing.tripId == id`) | Yes | `TripService.markNoShow`, `transitionStatus` |
| 13 | `POST` | `/trips/{id}/stops/{stopId}/complete` | `app-user` | `driver` | Yes (`trip.companyId`) | Yes (`trip.driverId == userId` & `existing.tripId == id`) | Yes | `TripService.markCompleted`, `transitionStatus` |
| 14 | `WS` | `/ws/driver/{tripId}` | `app-user` | `driver` | Yes (`trip.companyId`) | Yes (`trip.driverId == userId`) | Yes (GPS) | `VehicleRepo.updateLocation` |
| 15 | `WS` | `/ws/trip/{tripId}` | `app-user` | `employee`, `driver` | Yes (`trip.companyId`) | **NO** (BOLA: any company employee can track) | No | `VehicleRepo.findTripDetails` |
| 16 | `POST` | `/pickup-zones` | `admin`, `app-user` | `admin` | Yes (`user.companyId`) | N/A (Company-scoped creation) | Yes | `PickupZoneService.createZone`, `PickupZoneRepo.create` |
| 17 | `GET` | `/pickup-zones` | `admin`, `app-user` | `admin`, `employee`, `driver` | Yes (`user.companyId`) | N/A (Company-wide list) | No | `PickupZoneService.getZonesByCompany` |
| 18 | `GET` | `/pickup-zones/{id}` | `admin`, `app-user` | `admin`, `employee`, `driver` | Yes (`user.companyId`) | Yes (`zone.companyId == user.companyId`) | No | `PickupZoneService.getZone` |
| 19 | `PUT` | `/pickup-zones/{id}` | `admin`, `app-user` | `admin` | Yes (`user.companyId`) | Yes (`zone.companyId == user.companyId`) | Yes | `PickupZoneService.updateZone` |
| 20 | `DELETE`| `/pickup-zones/{id}` | `admin`, `app-user` | `admin` | Yes (`user.companyId`) | Yes (`zone.companyId == user.companyId`) | Yes | `PickupZoneService.deleteZone`, `deactivate` |
| 21 | `POST` | `/shuttle-schedules` | `admin`, `app-user` | `admin` | Yes (`user.companyId`) | N/A (Company-scoped creation) | Yes | `ShuttleScheduleService.createSchedule` |
| 22 | `GET` | `/shuttle-schedules` | `admin`, `app-user` | `admin`, `employee`, `driver` | Yes (`user.companyId`) | N/A (Company-wide list) | No | `ShuttleScheduleService.getSchedulesByCompany` |
| 23 | `GET` | `/shuttle-schedules/{id}` | `admin`, `app-user` | `admin`, `employee`, `driver` | Yes (`user.companyId`) | Yes (`schedule.companyId == user.companyId`) | No | `ShuttleScheduleService.getSchedule` |
| 24 | `PUT` | `/shuttle-schedules/{id}` | `admin`, `app-user` | `admin` | Yes (`user.companyId`) | Yes (`schedule.companyId == user.companyId`) | Yes | `ShuttleScheduleService.updateSchedule` |
| 25 | `DELETE`| `/shuttle-schedules/{id}` | `admin`, `app-user` | `admin` | Yes (`user.companyId`) | Yes (`schedule.companyId == user.companyId`) | Yes | `ShuttleScheduleService.deleteSchedule`, `deactivate` |
| 26 | `POST` | `/shuttle-schedules/{scheduleId}/trips` | `admin`, `app-user` | `admin` | Yes (`user.companyId`) | Yes (`schedule.companyId == user.companyId`) | Yes | `ShuttleScheduleService.generateTrip`, `TripRepo.create` |

*\* Note on Endpoint #8: Admin role is code-referenced in `RideRoutes.kt:49`, but blocked at the Ktor authentication realm boundary.*

---

## 5. Employee Authorization

1. **Ride Creation Protection:**
   In [RideRoutes.kt:31-33](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/RideRoutes.kt#L31-L33), the handler checks:
   ```kotlin
   if (request.employeeId != user.userId) {
       return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Cannot request ride for another employee"))
   }
   ```
   Employees cannot impersonate other employees during ride creation.

2. **Ride Status Reading:**
   In [RoutingService.kt:79-85](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt#L79-L85):
   ```kotlin
   val stored = rideRequestRepository.findById(rideRequestId) ?: return null
   if (callerUserId != null && stored.employeeId != null && stored.employeeId != callerUserId) {
       return null
   }
   if (callerCompanyId != null && stored.companyId != null && stored.companyId != callerCompanyId) {
       return null
   }
   ```
   An employee cannot read ride requests belonging to other employees or other tenants (returns HTTP 404).

3. **Ride Cancellation:**
   In [TripService.kt:161-163](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/TripService.kt#L161-L163), cancellation enforces:
   ```kotlin
   if (callerEmployeeId != null && existing.employeeId != null && existing.employeeId != callerEmployeeId) {
       return LifecycleResult.Forbidden
   }
   ```
   An employee cannot cancel another employee's ride.

4. **Trip Stop Manifest Access:**
   In [TripRoutes.kt:50-52](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/TripRoutes.kt#L50-L52):
   ```kotlin
   "employee" -> {
       if (!tripService.hasEmployeeOnTrip(tripId, user.userId)) {
           return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Not a passenger on this trip"))
       }
   }
   ```
   Employees not booked on the trip are forbidden from viewing the stop manifest.

5. **Cross-Tenant Pickup Zone Linkage Vulnerability:**
   In `POST /rides`, the `request.pickupZoneId` is passed directly into `RideRequests.insert` ([PostgresRideRequestRepository.kt:100-111](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt#L100-L111)). **The service never validates that `pickupZoneId` belongs to `user.companyId`**. An employee from Company A can link their commute request to a private pickup zone created by Company B.

---

## 6. Driver Authorization

1. **Stop Manifest Access:**
   In [TripRoutes.kt:44-48](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/TripRoutes.kt#L44-L48), driver access checks:
   ```kotlin
   "driver" -> {
       if (trip.driverId != user.userId) {
           return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Not assigned driver for this trip"))
       }
   }
   ```
   Drivers can only read manifests for trips explicitly assigned to them.

2. **Stop Action Authorization (Defense-in-Depth):**
   For all lifecycle mutations (`pickup`, `no-show`, `complete`), verification occurs at **both** route and service layers:
   * **Route Layer ([TripRoutes.kt:88-90](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/TripRoutes.kt#L88-L90)):** Verifies `trip.companyId == user.companyId` and `trip.driverId == user.userId`.
   * **Service Layer ([TripService.kt:205-218](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/TripService.kt#L205-L218)):** Re-verifies `trip.companyId`, `trip.driverId`, `existing.tripId == tripId`, and `existing.companyId == companyId`.
   * **State Machine:** Enforces valid lifecycle transitions centrally ([RideRequestStateMachine.kt](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RideRequestStateMachine.kt)).
   Drivers cannot manipulate stops on trips assigned to other drivers or other tenants.

3. **GPS Telemetry Authorization:**
   In [WebSocketRoutes.kt:49-51](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/WebSocketRoutes.kt#L49-L51), `/ws/driver/{tripId}` enforces `trip.driverId == user.userId`. Only the assigned driver can stream GPS updates for that trip.

---

## 7. Admin Authorization

1. **Authentication Realm Separation:**
   Admins authenticate via `POST /auth/admin/login` using email and bcrypt-hashed password ([AuthService.kt:66-77](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/AuthService.kt#L66-L77)), receiving a token with `role = "admin"` and `companyId`.

2. **Admin-Only Routes:**
   Mutations on pickup zones and shuttle schedules ([PickupZoneRoutes.kt:40](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/PickupZoneRoutes.kt#L40), [ShuttleScheduleRoutes.kt:58](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/ShuttleScheduleRoutes.kt#L58)) enforce:
   ```kotlin
   if (user.role != "admin") {
       return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Only admins can manage..."))
   }
   ```
   Employees and drivers attempting to modify zones or schedules receive HTTP 403 Forbidden.

3. **Tenant Boundary Enforcement:**
   Admins cannot manage resources outside their company. In `PickupZoneService` and `ShuttleScheduleService`, operations query by resource ID and verify `existing.companyId == callerCompanyId`. Mismatches return 404 Not Found to prevent existence discovery across tenants.

4. **Missing Fleet Administration Endpoints:**
   There are no production endpoints for admins to create, update, or assign vehicles or drivers (verified in Task 7.2).

---

## 8. Multi-Tenant Isolation Audit

Multi-tenant isolation was traced through every layer:

```text
JWT Principal (companyId)
      │
      ▼
Ktor Route Handler (extracts user.companyId from JWT)
      │
      ▼
Service Layer (passes callerCompanyId into business logic)
      │
      ▼
Repository Layer (filters SQL / Exposed queries)
      │
      ▼
PostgreSQL Database Row (company_id column)
```

### Analysis by Domain Entity:

| Entity | Has `company_id` Column? | Query Filters by `companyId`? | Verified Secure? | Evidence |
| :--- | :--- | :--- | :--- | :--- |
| **Companies** | Yes (`id`) | Primary key | Yes | [Tables.kt:33](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Tables.kt#L33) |
| **Employees** | Yes (`company_id`) | Filtered via Auth & Joins | Yes | [Tables.kt:41](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Tables.kt#L41) |
| **Drivers** | Yes (`company_id`) | Filtered via Auth & Joins | Yes | [Tables.kt:51](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Tables.kt#L51) |
| **Vehicles** | Yes (`company_id`) | `findAvailableNear` filters by `companyId` | Yes | [PostgresVehicleRepository.kt:54](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresVehicleRepository.kt#L54) |
| **Trips** | **NO** (Implicit via Vehicles/Schedules) | Join to Vehicles/Schedules required | **PARTIAL** | [Tables.kt:92](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Tables.kt#L92). Trips table lacks direct `company_id`. |
| **Ride Requests**| **NO** (Implicit via Employees) | Join to Employees required | **PARTIAL** | [Tables.kt:102](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Tables.kt#L102). Lacks `company_id`. |
| **Pickup Zones** | Yes (`company_id`) | Explicitly filtered in repo & service | Yes | [PostgresPickupZoneRepository.kt:82](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresPickupZoneRepository.kt#L82) |
| **Schedules** | Yes (`company_id`) | Explicitly filtered in repo & service | Yes | [PostgresShuttleScheduleRepository.kt:102](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresShuttleScheduleRepository.kt#L102) |
| **Admins** | Yes (`company_id`) | Filtered via Auth & Joins | Yes | [Tables.kt:116](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Tables.kt#L116) |

---

## 9. Repository-Level Audit

Inspection of public repository methods identifies several methods that are **dangerous by default** if invoked without upper-layer tenant checks:

| Repository | Method | Input Key | Tenant Filter in Query? | Risk Assessment |
| :--- | :--- | :--- | :--- | :--- |
| `PostgresVehicleRepository` | `findAvailableNear(lat, lng, r, companyId)` | `companyId?` | Conditional (`v.company_id = ?` only if non-null) | **Dangerous if `companyId` is null.** (Returns all tenants' vehicles). |
| `PostgresVehicleRepository` | `updateLocation(vehicleId, lat, lng)` | `vehicleId` | **NONE** | **Dangerous by default.** Updates any vehicle row. |
| `PostgresVehicleRepository` | `findVehicleIdByTripId(tripId)` | `tripId` | **NONE** | Unchecked single-row query. |
| `PostgresVehicleRepository` | `findVehicleLocationByTripId(tripId)` | `tripId` | **NONE** | Unchecked query; returns location across tenants. |
| `PostgresVehicleRepository` | `findTripDetails(tripId)` | `tripId` | **NONE** (Returns `companyId` for caller to check) | Relies entirely on service/route to enforce isolation. |
| `PostgresRideRequestRepository` | `findById(id)` | `id` | **NONE** (Returns `companyId` for caller to check) | Relies entirely on caller to check `companyId`. |
| `PostgresRideRequestRepository` | `transitionStatus(id, from, to, tripId)` | `id` | **NONE** (Updates by `id` and `status`) | Relies entirely on service layer checks. |
| `PostgresTripRepository` | `findById(tripId)` | `tripId` | **NONE** | Unchecked single-row query. |
| `PostgresPickupZoneRepository` | `findById(id)` | `id` | **NONE** | Relies on service to check `zone.companyId`. |
| `PostgresShuttleScheduleRepository` | `findById(id)` | `id` | **NONE** | Relies on service to check `schedule.companyId`. |

**Architectural Risk:** The repository layer does not enforce tenant boundaries internally. Tenant isolation is entirely dependent on service-layer discipline. Any developer calling `findById(id)` or `updateLocation(id)` without subsequent checks introduces an immediate cross-tenant vulnerability.

---

## 10. IDOR / BOLA Matrix

| Attacker Role | Target Resource | Victim Tenant / User | Attack Vector | Protected? | Defense Mechanism & Evidence |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Employee A | Ride Request B | Employee B (Same Company) | `GET /rides/{idB}` | **YES** | Blocked in `RoutingService.getRideStatus` ([RoutingService.kt:80](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt#L80)). |
| Employee A | Ride Request B | Employee B (Different Company) | `GET /rides/{idB}` | **YES** | Blocked in `RoutingService.getRideStatus` ([RoutingService.kt:83](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/RoutingService.kt#L83)). |
| Employee A | Ride Request B | Employee B (Same Company) | `POST /rides/{idB}/cancel` | **YES** | Blocked in `TripService.cancelRideRequest` ([TripService.kt:161](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/TripService.kt#L161)). |
| Employee A | Trip B Stops | Trip B (Not a passenger) | `GET /trips/{idB}/stops` | **YES** | Blocked in `TripRoutes.kt:50` (`hasEmployeeOnTrip`). |
| Employee A | Trip B Telemetry | Trip B (Not a passenger) | `WS /ws/trip/{idB}` | **NO (BOLA)** | **Vulnerable.** `/ws/trip/{tripId}` checks company matching, but NOT passenger association ([WebSocketRoutes.kt:87](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/WebSocketRoutes.kt#L87)). |
| Employee A | Pickup Zone B | Company B | `POST /rides` (pass `pickupZoneId`) | **NO (Cross-Tenant FK)** | **Vulnerable.** `pickupZoneId` is not validated against `companyId` ([PostgresRideRequestRepository.kt:100](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt#L100)). |
| Driver A | Trip B Stops | Driver B (Same Company) | `GET /trips/{idB}/stops` | **YES** | Blocked in `TripRoutes.kt:45` (`trip.driverId != user.userId`). |
| Driver A | Trip B Actions | Driver B (Same Company) | `POST /trips/{idB}/stops/{s}/pickup` | **YES** | Blocked in `TripRoutes.kt:88` and `TripService.kt:209`. |
| Driver A | Trip B GPS | Driver B (Same Company) | `WS /ws/driver/{idB}` | **YES** | Blocked in `WebSocketRoutes.kt:49` (`trip.driverId != user.userId`). |
| Admin A | Schedule B | Admin B (Different Company) | `PUT /shuttle-schedules/{idB}` | **YES** | Blocked in `ShuttleScheduleService.kt:156` (`existing.companyId != callerCompanyId`). |
| Admin A | Pickup Zone B | Admin B (Different Company) | `DELETE /pickup-zones/{idB}` | **YES** | Blocked in `PickupZoneService.kt:117` (`existing.companyId != callerCompanyId`). |
| Unauthenticated | Driver Token | Any Driver | `POST /auth/driver/request-otp` | **NO (Auth Bypass)** | **Vulnerable.** OTP returned in response body ([AuthRoutes.kt:76](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/AuthRoutes.kt#L76)). |

---

## 11. State-Changing Authorization

Audit of mutation operations:

1. **Ride Creation (`POST /rides`):**
   * Can an employee create rides for others? **NO** (`request.employeeId == user.userId` enforced).
   * Can an employee link cross-tenant pickup zones? **YES** (Vulnerability: `pickupZoneId` unvalidated).

2. **Ride Cancellation (`POST /rides/{id}/cancel`):**
   * Can an employee cancel another employee's ride? **NO** (`callerEmployeeId == stored.employeeId` enforced).
   * Can a cancelled/completed ride be re-cancelled? **NO** (`RideRequestStateMachine` rejects invalid transitions).

3. **Driver Stop Actions (`/stops/{id}/pickup`, `/no-show`, `/complete`):**
   * Can a driver operate an unassigned trip? **NO** (Checked at both route and service layers).
   * Can a driver manipulate a stop not belonging to the trip? **NO** (`existing.tripId == tripId` enforced).
   * Are concurrent state races possible? **NO** (`PostgresRideRequestRepository.transitionStatus` executes an atomic conditional SQL `UPDATE ... WHERE id = ? AND status = ?`).

4. **Zone & Schedule Mutations (`POST`, `PUT`, `DELETE`):**
   * Can non-admins modify zones or schedules? **NO** (Enforces `user.role == "admin"`).
   * Can admins modify other tenants' zones or schedules? **NO** (Enforces `existing.companyId == user.companyId`).

---

## 12. Cross-Tenant Attack Paths

### Attack A: Employee from Company A requests `GET /rides/{company-B-ride-id}`
* **Trace:** `RoutingService.getRideStatus()` executes `findById(id)`. Checks `stored.companyId != callerCompanyId`.
* **Result:** Returns `null` $\to$ HTTP 404 Not Found.
* **Status:** **BLOCKED (SECURE)**.

### Attack B: Employee from Company A requests `GET /trips/{company-B-trip-id}/stops`
* **Trace:** `TripRoutes.kt:39` checks `trip.companyId != user.companyId`.
* **Result:** Returns HTTP 404 Not Found.
* **Status:** **BLOCKED (SECURE)**.

### Attack C: Driver from Company A accesses `/trips/{company-B-trip-id}/...`
* **Trace:** `TripRoutes.kt` checks `trip.companyId != user.companyId`.
* **Result:** Returns HTTP 404 Not Found.
* **Status:** **BLOCKED (SECURE)**.

### Attack D: Driver A manipulates `/trips/{driver-B-trip-id}/stops/{stop-id}/pickup`
* **Trace:** Route checks `trip.driverId != user.userId` (403). Service re-checks `trip.driverId != driverId` (Forbidden) and `existing.tripId != tripId` (NotFound).
* **Result:** Returns HTTP 403 Forbidden.
* **Status:** **BLOCKED (SECURE)**.

### Attack E: Admin from Company A modifies Company B schedules or zones
* **Trace:** `ShuttleScheduleService.updateSchedule()` and `PickupZoneService.updateZone()` verify `existing.companyId == callerCompanyId`.
* **Result:** Returns HTTP 404 Not Found.
* **Status:** **BLOCKED (SECURE)**.

### Attack F: Employee from Company A connects to `/ws/trip/{trip-id}` for another employee's trip
* **Trace:** `/ws/trip/{tripId}` checks `trip.companyId != user.companyId`. If both employees belong to Company A, the check passes. **The route never checks `hasEmployeeOnTrip`**.
* **Result:** Connection accepted. Employee tracks real-time shuttle coordinates without booking a seat.
* **Status:** **VULNERABLE (BOLA / IDOR)**.

### Attack G: Cross-tenant Pickup Zone linkage on booking
* **Trace:** Employee from Company A passes `pickupZoneId` of Company B. `POST /rides` inserts the foreign key without validating company ownership.
* **Result:** Database row created linking Company A ride request to Company B pickup zone.
* **Status:** **VULNERABLE (CROSS-TENANT FOREIGN KEY INJECTION)**.

### Attack H: Unauthenticated Driver Account Takeover
* **Trace:** Attacker sends phone number to `POST /auth/driver/request-otp`. The server responds with `{ "otp": "..." }`. Attacker verifies OTP and receives driver JWT.
* **Result:** Attacker takes over driver account.
* **Status:** **VULNERABLE (CRITICAL AUTHENTICATION BYPASS)**.

---

## 13. Client-Supplied Identity Audit

Evaluation of client-supplied IDs versus trusted JWT claims:

1. **`companyId`:**
   * **Client-supplied in body/query:** **IGNORED.**
   * **Source of Truth:** Always derived from JWT principal via `call.authenticatedUser()!!.companyId`.
   * **Status:** **SECURE.**

2. **`employeeId`:**
   * **Client-supplied in `POST /rides`:** Validated strictly against `user.userId` ([RideRoutes.kt:31](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/RideRoutes.kt#L31)).
   * **Status:** **SECURE.**

3. **`driverId`:**
   * **Client-supplied in driver stop routes:** Not accepted from client; pulled from JWT `user.userId`.
   * **Client-supplied in frame `update.driverId`:** Validated against `user.userId` in [WebSocketRoutes.kt:59](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/WebSocketRoutes.kt#L59).
   * **Status:** **SECURE.**

4. **`pickupZoneId`:**
   * **Client-supplied in `POST /rides`:** **TRUSTED UNCHECKED.**
   * **Status:** **INSECURE (VULNERABILITY).**

---

## 14. Database-Level Isolation

1. **Absence of Row-Level Security (RLS):**
   Inspection of migrations `V1` through `V6` confirms that **no PostgreSQL Row-Level Security (RLS) policies exist**. All tenant isolation is enforced at the application/service layer. If a service query omits a `WHERE company_id = ?` clause, PostgreSQL executes the query across all tenants without restriction.

2. **Missing `company_id` on Central Relational Tables:**
   * Table `trips` has no `company_id` column ([Tables.kt:92](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Tables.kt#L92)). Tenant context must be inferred by joining `Vehicles` or `ShuttleSchedules`.
   * Table `ride_requests` has no `company_id` column ([Tables.kt:102](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Tables.kt#L102)). Tenant context must be inferred by joining `Employees`.
   * As a consequence, foreign keys cannot enforce cross-tenant integrity at the database engine level (e.g., PostgreSQL cannot prevent linking a Company A employee to a Company B trip).

---

## 15. Sensitive Information Exposure

1. **Co-Passenger PII Exposure in Stop Manifests:**
   In [TripRoutes.kt:62](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/TripRoutes.kt#L62), `GET /trips/{id}/stops` returns `StopListUpdate` containing:
   ```kotlin
   Stop(
       rideRequestId = stop.rideRequestId,
       lat = stop.lat,
       lng = stop.lng,
       sequenceOrder = stop.sequenceOrder,
       employeeName = employeeNameById[stop.rideRequestId] ?: "Unknown"
   )
   ```
   When an employee queries this endpoint, they receive the **full names and exact home/pickup GPS coordinates of all other co-passengers** on that vehicle run. While drivers require this information for pickup operations, exposing full names and coordinates to fellow passengers is an excessive PII exposure.

2. **Driver OTP Leakage:**
   Documented in Section 3.2. Exposes authentication secrets directly over HTTP.

---

## 16. WebSocket Security Integration

Integrating findings from Task 7.4 ([Task7_4_RealTime_WebSocket_Audit.md](file:///c:/project-shuttles-2026/dynashuttle-backend/docs/Task7_4_RealTime_WebSocket_Audit.md)):

1. **`/ws/trip/{tripId}` BOLA / IDOR:** Confirmed vulnerability. Any authenticated user in a company can track any vehicle in that company without being an assigned passenger.
2. **`/ws/driver/{tripId}` Missing Rate Limiting & DoS:** Confirmed weakness. Lack of telemetry frame rate limits permits database connection starvation.
3. **Admin WebSocket Lockout:** Confirmed architectural conflict. `authenticate("app-user")` locks admin tokens out of WebSocket monitoring.
4. **Thread Safety Defect:** Confirmed defect. Unsynchronized `ArrayList` in `ConcurrentHashMap` can crash sessions via `ConcurrentModificationException`.

---

## 17. Severity Classification

Findings are classified according to standard security risk criteria:

* **CRITICAL:** Vulnerabilities permitting authentication bypass, cross-tenant data access, or unauthorized control.
* **HIGH:** Significant authorization omissions, sensitive data exposure, or cross-tenant relationship corruption.
* **MEDIUM:** Architectural security conflicts, missing token safeguards, or DoS vectors requiring specific conditions.
* **LOW:** Defense-in-depth gaps and hardening improvements.

---

## 18. Security Findings Table

| ID | Severity | Component | Attack / Weakness | Impact | Current Protection | Missing Protection | Exact Evidence |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **SEC-AUTH-01** | **CRITICAL** | `POST /auth/driver/request-otp` | Unauthenticated OTP retrieval | Complete driver account takeover. Plaintext OTP returned in JSON response. | Phone must exist in `drivers` table | Redaction of OTP in response (`null` return in production) | [AuthRoutes.kt:73-80](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/AuthRoutes.kt#L73-L80) |
| **SEC-BOLA-01** | **CRITICAL** | `WS /ws/trip/{tripId}` | Broken Object Level Authorization (BOLA/IDOR) | Any employee tracks live GPS of any shuttle in the company without booking. | Tenant company matching enforced | Passenger association check (`hasEmployeeOnTrip`) | [WebSocketRoutes.kt:87](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/WebSocketRoutes.kt#L87) |
| **SEC-TENANT-01**| **HIGH** | `POST /rides` | Cross-tenant Pickup Zone linkage | Commute request linked to foreign tenant's pickup zone in database. | Employee ID matched to token | Verification that `pickupZoneId.companyId == user.companyId` | [PostgresRideRequestRepository.kt:100](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresRideRequestRepository.kt#L100) |
| **SEC-AUTH-02** | **HIGH** | `Authentication.kt` | Fallback default JWT secret | Forgery of arbitrary admin/user JWTs if environment variable unset. | None if env var missing | Mandatory secret requirement; fail server startup if secret is default | [Authentication.kt:18](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/plugins/Authentication.kt#L18) |
| **SEC-INFO-01** | **HIGH** | `GET /trips/{id}/stops` | Excessive PII exposure to passengers | Passenger learns full names and exact GPS coordinates of all co-passengers. | Passenger must be on trip | Role-differentiated DTO (anonymize co-passengers for employee callers) | [TripRoutes.kt:62](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/TripRoutes.kt#L62) |
| **SEC-DOS-01** | **HIGH** | `WS /ws/driver/{tripId}` | Unthrottled telemetry write saturation | Database connection pool exhaustion via high-frequency GPS frames. | Driver must be assigned to trip | In-memory frame rate limiting / write debouncing | [WebSocketRoutes.kt:64-66](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/WebSocketRoutes.kt#L64-L66) |
| **SEC-AUTH-03** | **MEDIUM** | `Authentication.kt` | Admin realm lockout on `app-user` routes | Admins cannot monitor rides or trip stops despite route code intending admin support. | Realm isolation | Dual-realm authentication or unified token claims | [RideRoutes.kt:17, 49](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/RideRoutes.kt#L17) |
| **SEC-DB-01** | **MEDIUM** | `trips`, `ride_requests` | Lack of direct `company_id` & database RLS | Database cannot enforce multi-tenant constraints independently of application. | Application-layer joins | Schema denormalization of `company_id` + composite FKs / RLS | [Tables.kt:92, 102](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/models/Tables.kt#L92) |
| **SEC-REPO-01** | **MEDIUM** | `PostgresVehicleRepository` | Dangerous-by-default repository methods | Unfiltered queries (`findAvailableNear(..., null)`, `updateLocation`) if invoked carelessly. | Upper service layer filtering | Mandatory `companyId` parameter on all repository methods | [PostgresVehicleRepository.kt:44, 132](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/repositories/PostgresVehicleRepository.kt#L44) |
| **SEC-AUTH-04** | **LOW** | `AuthService.kt` | Missing JWT audience claim | Potential cross-service token replay if JWT secret shared. | Issuer check enforced | Add and verify `aud` claim | [AuthService.kt:80](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/services/AuthService.kt#L80) |

---

## 19. Security Architecture Diagram

The diagram below maps the current security perimeter, showing where security boundaries are enforced and where omissions exist:

```text
Incoming Client Request
         │
         ▼
[ JWT Validation Layer ]
   ├── Issuer: "dynashuttle" (Enforced)
   ├── Expiration: 24h (Enforced)
   ├── Audience: OMITTED (Gap: SEC-AUTH-04)
   └── Secret: Defaults to "dev-secret-change-me" (Gap: SEC-AUTH-02)
         │
         ▼
[ Ktor Authentication Realm ]
   ├── "admin" vs "app-user" Realm Isolation (Enforced)
   └── Conflict: Admin tokens rejected on /rides, /trips, /ws/* (Gap: SEC-AUTH-03)
         │
         ▼
[ Route Handler Layer ]
   ├── Role Check: "admin" / "employee" / "driver" (Enforced)
   ├── Employee IDOR: request.employeeId == user.userId (Enforced)
   ├── Passenger Manifest Check: hasEmployeeOnTrip (Enforced for HTTP GET /stops)
   ├── Driver Trip Assignment: trip.driverId == user.userId (Enforced for Driver HTTP/WS)
   └── WebSocket Passenger Check: OMITTED on /ws/trip/{tripId} (CRITICAL Gap: SEC-BOLA-01)
         │
         ▼
[ Service Layer ]
   ├── Tenant Isolation: existing.companyId == user.companyId (Enforced on Rides, Stops, Zones, Schedules)
   ├── State Machine Transitions: RideRequestStateMachine (Enforced)
   ├── Co-Passenger PII Filter: OMITTED on GET /stops (Gap: SEC-INFO-01)
   └── Zone Ownership Check: OMITTED on POST /rides (Gap: SEC-TENANT-01)
         │
         ▼
[ Repository Layer ]
   ├── Exposed Queries: Most methods rely on service for companyId (Gap: SEC-REPO-01)
   └── PostGIS findAvailableNear: Optional companyId (Dangerous if null)
         │
         ▼
[ PostgreSQL Database Layer ]
   ├── PostgreSQL Row-Level Security (RLS): NONE (Gap: SEC-DB-01)
   └── Table Constraints: trips & ride_requests lack direct company_id
```

---

## 20. Research vs Production Security

| Security Dimension | Research Simulation Strategy | Production Implementation Requirement |
| :--- | :--- | :--- |
| **Authentication** | None. Agents execute deterministically in memory. | Mandatory cryptographic JWT authentication with phone/email OTP. |
| **Multi-Tenancy** | Single synthetic campus / single tenant model. | Strict data isolation across corporate tenants sharing a single database. |
| **Resource Authorization**| Implicit in simulation objects. | Explicit IDOR/BOLA protection on every HTTP and WebSocket endpoint. |
| **Data Privacy (PII)** | No real employee data; synthetic nodes. | Protection of employee names, phone numbers, and home/work coordinates. |
| **Telemetry Protection** | Algorithmic vehicle movement. | Authenticated driver streaming with DoS rate limiting and coordinate validation. |
| **Access Control (RBAC)**| None. Central script has global authority. | Segregation between Employees, Fleet Drivers, and Corporate Portal Admins. |

---

## 21. Final Recommendations

### P0 — Must Fix Before Production (Blocking Deployment)
1. **Redact Driver OTP:** Remove the raw OTP from `DriverOtpResponse` in [AuthRoutes.kt:76](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/AuthRoutes.kt#L76), mirroring the employee OTP implementation once SMS delivery is configured.
2. **Fix BOLA on `/ws/trip/{tripId}`:** Add passenger authorization verification (`hasEmployeeOnTrip`) in [WebSocketRoutes.kt:87](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/kotlin/com/dynashuttle/routes/WebSocketRoutes.kt#L87) before registering the listener session.
3. **Mandate Strong JWT Secret:** Fail application startup loudly if `JWT_SECRET` is unset or equals `"dev-secret-change-me"`.
4. **Validate Pickup Zone Ownership:** In `POST /rides`, verify that `request.pickupZoneId` belongs to `user.companyId` before database insertion.

### P1 — Strongly Recommended (Near-Term Hardening)
5. **Rate-Limit Driver WebSocket:** Implement a leaky-bucket or time-window debounce on `/ws/driver/{tripId}` to limit database updates to at most once per 3-5 seconds per driver.
6. **Resolve Admin WebSocket & Ride Access:** Permit admin tokens to authenticate on `/ws/trip/{tripId}` and `GET /rides/{id}` for administrative monitoring within their company.
7. **Anonymize Co-Passenger PII:** Differentiate the DTO returned by `GET /trips/{id}/stops` so that employee callers receive obfuscated or redacted co-passenger names and locations.
8. **Add Repository Tenant Safeguards:** Make `companyId` a non-nullable required parameter on `findAvailableNear` and dangerous repository methods.

### P2 — Defensive Hardening (Future Improvements)
9. **Denormalize `company_id` to `trips` and `ride_requests`:** Add direct `company_id` columns with composite foreign keys to guarantee database-level tenant isolation.
10. **Implement Token Blacklisting:** Introduce Redis or database token revocation for instant termination of deactivated employee/driver sessions.
11. **Add JWT Audience (`aud`) Validation:** Include and verify the audience claim on all tokens.

---

## 22. Final Security Assessment

The DynaShuttle backend implements **strong baseline security controls in several critical areas**:
* Core HTTP endpoints rigorously enforce employee identity matching (`request.employeeId == user.userId`).
* Driver lifecycle operations enforce double-layered defense-in-depth authorization and atomic SQL state transitions.
* Admin operations strictly enforce company isolation using server-side JWT claims.
* SQL injection is mitigated through Exposed parameter binding and prepared statements.

However, **two critical vulnerabilities undermine this baseline**:
1. `POST /auth/driver/request-otp` exposes generated driver OTPs directly in HTTP responses, enabling complete driver account takeover.
2. `/ws/trip/{tripId}` fails to verify passenger association, allowing any employee to track vehicles and drivers without authorization.

Addressing these two vulnerabilities, alongside enforcing pickup zone tenant ownership on booking, is mandatory before deploying the backend to production.

---

## 23. Verification Requirements

* **Production Code Modified:** NO
* **Database Modified:** NO
* **Migrations Modified:** NO
* **Experiment Files Modified:** NO
* **Verification Executed:**
  * `git status --short` verified (only audit document created)
  * `git diff --check` executed and clean
  * Gradle test suite run non-destructively: 320 passed, 0 failed, 0 skipped
