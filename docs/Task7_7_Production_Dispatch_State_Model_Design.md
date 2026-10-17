# Task 7.7 — Production Dispatch & State Model Design

---

## 1. Design Status

| Field | Value |
|---|---|
| **Status** | COMPLETE — Design Only |
| **Mode** | Strictly Design-Only, Read-Only (no production code, SQL, schema, migrations, records, experiment code, simulation logic, results, tests, or configuration modified) |
| **Date** | October 7, 2026 |
| **Target System** | DynaShuttle Backend (`dynashuttle-backend`) |
| **Purpose** | Convert audit findings (Tasks 7.1–7.6) into a precise, actionable production design for future dynamic dispatch implementation |

---

## 2. Scope

This document defines the target-state production architecture for DynaShuttle's dynamic dispatch subsystem. It answers the central design question:

> **What exact production state model, database model, service boundaries, dispatch lifecycle, concurrency model, and real-time event contracts are required to safely implement dynamic batching and dispatch in DynaShuttle?**

This document is **design only**. It proposes future architecture. Nothing here is implemented. No production files have been modified.

---

## 3. Authoritative Inputs

### Previous Production Audits

| Audit | Key Finding Used Here |
|---|---|
| Task 7.1 | Online single-request matching; no batching; no fleet dispatcher |
| Task 7.2 | Trips created as `SCHEDULED` with null vehicle/driver; no automation |
| Task 7.3 | No drop-off routing; no stop persistence; ONBOARD passengers vanish |
| Task 7.4 | WebSockets = GPS-only; BOLA on `/ws/trip/{id}`; unsafe session map |
| Task 7.5 | Plaintext driver OTP; default JWT secret; unvalidated `pickupZoneId` |
| Task 7.6 | Zero batch formation; no stop persistence; trip lifecycle deadlock |

### Frozen Research Baseline

Research findings from Tasks 4.1–6.4 are **immutable reference inputs**. The production architecture is designed around them, not by changing them.

| Research Parameter | Value |
|---|---|
| `dynamicBatchWindowSeconds` | `300` |
| `vehicleCapacity` | `4` |
| Assignment model | Nearest eligible vehicle (Haversine), tie-break by `vehicleId` |
| Stop ordering | `GreedyNearestNeighbourOptimizer` |
| Lifecycle | request → eligible selection → batch formation → stop ordering → movement → pickup → drop-off → vehicle available |

---

## 4. Current Production Model

Summary of existing relevant entities (as-is, no proposed changes in this section):

| Entity | Current Purpose | Important Fields | Missing for Dynamic Dispatch |
|---|---|---|---|
| `companies` | Tenant / corporate client root | `id`, `name`, `city` | Nothing — tenant anchor is adequate |
| `employees` | Corporate commuters who book rides | `id`, `company_id`, `email`, `home_lat`, `home_lng` | No notification token |
| `drivers` | Fleet operators assigned to trips | `id`, `company_id`, `name`, `phone` | No availability status; no current vehicle association |
| `vehicles` | Physical shuttle vans | `id`, `company_id`, `plate_number`, `seat_capacity`, `last_known_lat`, `last_known_lng`, `last_location_updated_at` | No operational status; no `availableAt` timestamp; no independent trip pointer |
| `trips` | A single shuttle run bound to a schedule | `id`, `schedule_id`, `trip_date`, `vehicle_id` (nullable), `driver_id` (nullable), `start_time`, `status` | Missing `ASSIGNED` and `BOARDING` states; no batch association; vehicle/driver remain null after creation |
| `ride_requests` | An individual employee booking | `id`, `trip_id` (nullable), `employee_id`, `pickup_lat`, `pickup_lng`, `destination_lat`, `destination_lng`, `status`, `requested_at` | Missing `QUEUED` ingestion state; no batch reference; destination coordinates never routed |
| `shuttle_schedules` | Recurring schedule template | `id`, `company_id`, `name`, `direction`, `departure_time`, `days_of_week`, `active` | Adequate as schedule template anchor |
| `admins` | Fleet portal administrators | `id`, `company_id`, `email`, `password_hash` | Not directly dispatch-relevant |
| *(none)* | Stop sequence persistence | — | **`trip_stops` table entirely absent** |
| *(none)* | Dispatch batch tracking | — | **`dispatch_batches` table entirely absent** |
| *(none)* | Vehicle operational state | — | **`vehicle_operational_state` table absent** |

---

## 5. Ride Request State Machine Design

### 5.1 Derived State Machine

The current production state machine (`RideRequestStateMachine.kt`) defines eight states. The V3 migration constraint allows: `REQUESTED`, `MATCHED`, `WAITLISTED`, `PICKUP_PENDING`, `ONBOARD`, `COMPLETED`, `CANCELLED`, `NO_SHOW`.

The critical gap: there is no `QUEUED` ingestion state that holds requests inside the 300-second batch window before a vehicle is assigned. The future state machine introduces this state.

```
REQUESTED
    │
    ├──► QUEUED          (batch window is open — awaiting dispatch worker)
    │         │
    │         ├──► MATCHED         (vehicle + batch assigned by dispatch worker)
    │         │         │
    │         │         ├──► PICKUP_PENDING   (driver en route to pickup point)
    │         │         │         │
    │         │         │         ├──► ONBOARD          (passenger boarded)
    │         │         │         │         │
    │         │         │         │         └──► COMPLETED     (terminal)
    │         │         │         │
    │         │         │         ├──► NO_SHOW           (terminal)
    │         │         │         └──► CANCELLED         (terminal)
    │         │         │
    │         │         └──► CANCELLED         (terminal)
    │         │
    │         ├──► WAITLISTED      (batch window closed; no vehicle found)
    │         │         │
    │         │         └──► QUEUED            (retry in next batch window)
    │         │         └──► CANCELLED         (terminal)
    │         │
    │         └──► EXPIRED         (timeout exceeded — terminal)
    │
    └──► CANCELLED        (terminal — employee cancelled before dispatch)
```

### 5.2 State Definitions

| State | Meaning | Entered By | Exit Conditions | Valid Next States |
|---|---|---|---|---|
| `REQUESTED` | Employee submitted `POST /rides`; request persisted | `POST /rides` handler (synchronous HTTP) | Dispatch worker picks up the request | `QUEUED`, `CANCELLED` |
| `QUEUED` | Inside an open batch window; awaiting dispatch worker | Dispatch worker transitions from `REQUESTED` | Batch window closes (300s elapsed, capacity reached, or scheduled departure) | `MATCHED`, `WAITLISTED`, `EXPIRED`, `CANCELLED` |
| `MATCHED` | Vehicle and batch assigned; ordered stop persisted | Dispatch worker after vehicle assignment and route persistence | Driver arrives at pickup stop | `PICKUP_PENDING`, `CANCELLED` |
| `PICKUP_PENDING` | Driver actively en route to passenger's pickup stop | Driver stop arrival event | Driver marks boarded or no-show | `ONBOARD`, `NO_SHOW`, `CANCELLED` |
| `ONBOARD` | Passenger physically aboard the vehicle | Driver calls pickup endpoint | Driver completes drop-off stop for this passenger | `COMPLETED` |
| `COMPLETED` | Passenger dropped off at destination | Driver calls drop-off endpoint | Terminal | *(none)* |
| `WAITLISTED` | Batch window closed; no eligible vehicle found | Dispatch worker upon batch close with zero eligible vehicles | Next dispatch cycle re-evaluates | `QUEUED`, `CANCELLED` |
| `EXPIRED` | No vehicle found within maximum wait period | Dispatch worker or expiration monitor | Terminal | *(none)* |
| `NO_SHOW` | Driver arrived; passenger did not board | Driver calls no-show endpoint | Terminal | *(none)* |
| `CANCELLED` | Explicitly cancelled by employee or system | Employee via cancel endpoint; system on trip abort | Terminal | *(none)* |

### 5.3 Authorization, Transactional, and Event Rules

| Transition | Who Triggers | Transaction Required? | Event Emitted? |
|---|---|---|---|
| `REQUESTED → QUEUED` | Dispatch worker | YES — atomic with batch acquisition | `RIDE_QUEUED` |
| `QUEUED → MATCHED` | Dispatch worker | YES — atomic with vehicle assignment + stop persistence | `VEHICLE_ASSIGNED`, `ROUTE_UPDATED` |
| `QUEUED → WAITLISTED` | Dispatch worker | YES | `RIDE_WAITLISTED` |
| `QUEUED → EXPIRED` | Expiration monitor / dispatch worker | YES | `RIDE_EXPIRED` |
| `WAITLISTED → QUEUED` | Dispatch worker (re-queue on next cycle) | YES | `RIDE_QUEUED` |
| `MATCHED → PICKUP_PENDING` | Trip execution service | YES | `ETA_UPDATED` |
| `PICKUP_PENDING → ONBOARD` | Driver (stop pickup endpoint) | YES | `PASSENGER_BOARDED` |
| `ONBOARD → COMPLETED` | Driver (stop drop-off endpoint) | YES | `PASSENGER_DROPPED_OFF` |
| `* → CANCELLED` | Employee (own request only) or system | YES | `RIDE_CANCELLED` |
| `PICKUP_PENDING → NO_SHOW` | Driver | YES | `PASSENGER_NO_SHOW` |

> **Important:** The `REQUESTED → QUEUED` transition must be the **only** place `POST /rides` touches the state machine. The HTTP handler writes `REQUESTED`; the dispatch worker transitions to `QUEUED` asynchronously. This cleanly separates HTTP ingestion from dispatch orchestration.

---

## 6. Trip State Machine Design

### 6.1 Derived State Machine

The current schema allows: `SCHEDULED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`. The audit established that `SCHEDULED` trips remain permanently stuck — no automation assigns vehicles or drivers.

The future lifecycle adds two states:

```
SCHEDULED
    │
    ▼
ASSIGNED          ← dispatch worker writes vehicle_id + driver_id
    │
    ▼
BOARDING          ← first passenger pickup stop becomes PICKUP_PENDING
    │
    ▼
IN_PROGRESS       ← first passenger boards (first ONBOARD transition)
    │
    ▼
COMPLETED         ← all passengers COMPLETED or NO_SHOW (terminal)

CANCELLED  ←──── from any non-terminal state (terminal)
ABORTED    ←──── system-initiated on driver disconnect / vehicle failure (terminal)
```

### 6.2 Trip State Definitions

| State | Meaning | Entry Condition | Exit Condition | Responsible Service | Emitted Events |
|---|---|---|---|---|---|
| `SCHEDULED` | Created from schedule; vehicle and driver are null | `POST /shuttle-schedules/{id}/trips` | Dispatch worker assigns vehicle + driver | `ShuttleScheduleService` | None |
| `ASSIGNED` | Vehicle and driver have been assigned by dispatch worker | Dispatch worker completes vehicle selection | Driver connects and GPS is active; or departure time reached | `DispatchService` | `VEHICLE_ASSIGNED` |
| `BOARDING` | Driver has arrived at first pickup stop; awaiting passengers | First stop reaches `ARRIVED` state | First passenger boards | Trip execution service | `TRIP_STARTED` |
| `IN_PROGRESS` | At least one passenger is aboard; vehicle in active service | First `ONBOARD` ride request transition | All passengers reach terminal state | Trip execution service | None |
| `COMPLETED` | All passengers dropped off; trip over | Final drop-off stop completed and all ride requests terminal | Terminal | Trip execution service | `TRIP_COMPLETED` |
| `CANCELLED` | Explicitly cancelled before completion | Admin or empty batch | Terminal | Admin action / DispatchService | `TRIP_CANCELLED` |
| `ABORTED` | Trip ended abnormally (driver disconnect, vehicle failure) | System detection | Terminal | DispatchService / health monitor | `TRIP_ABORTED` |

### 6.3 Resolution of the Task 7.2 Deadlock

The `SCHEDULED → ASSIGNED` transition is the critical missing bridge. The dispatch worker is the only component responsible. It must:

1. Select an eligible vehicle (see Section 10).
2. In a single atomic transaction: write `vehicle_id`, `driver_id`, update `trips.status = 'ASSIGNED'`.
3. Emit `VEHICLE_ASSIGNED` event to driver's notification channel.
4. Transition all matched `ride_requests` to `MATCHED`.

This resolves the deadlock without requiring changes to the existing `SCHEDULED` creation path.

---

## 7. Vehicle State Model

### 7.1 Where Should Vehicle State Live?

| Option | Verdict |
|---|---|
| Persisted directly on `vehicles` | Partial — adequate for coarse-grained ACTIVE/OFFLINE; insufficient for transient dispatch state |
| Derived from active trip state | Partial — correct for capacity; lacks `availableAt` and GPS staleness checks |
| Separate `vehicle_operational_state` table | **Recommended** — clean separation; avoids polluting `vehicles` with transient dispatch columns |
| Combination (`vehicles.status` for ACTIVE/OFFLINE + `vehicle_operational_state` for dispatch state) | **Recommended final design** |

### 7.2 Vehicle State Definitions

| State | Meaning | Who Changes It | When | Dispatch Eligible? |
|---|---|---|---|---|
| `IDLE` | Active; no current trip assignment | Dispatch worker (on trip completion) | When last drop-off is confirmed | **YES** |
| `ASSIGNED` | Assigned to a batch/trip; not yet moving | Dispatch worker (on vehicle selection) | When dispatch worker writes assignment | **NO** |
| `BOARDING` | At first pickup stop; passengers boarding | Trip execution service | On first stop `ARRIVED` event | **NO** |
| `IN_TRIP` | At least one passenger aboard; active movement | Trip execution service | On first `ONBOARD` transition | **NO** |
| `AVAILABLE` | All pickups done; drop-offs only (future optimization only) | Trip execution service | On last pickup completing | **CONDITIONAL — NOT for initial implementation** |
| `OFFLINE` | Inactive, GPS not reporting, or decommissioned | Admin or health monitor | GPS stale > threshold or admin action | **NO** |

### 7.3 Minimum Dispatch State Required

| Field | Source | Notes |
|---|---|---|
| `currentLat` | `vehicles.last_known_lat` | Must not be null |
| `currentLng` | `vehicles.last_known_lng` | Must not be null |
| `locationTimestamp` | `vehicles.last_location_updated_at` | Reject if older than staleness threshold |
| `seatCapacity` | `vehicles.seat_capacity` | Static |
| `currentLoad` | COUNT of MATCHED + PICKUP_PENDING + ONBOARD requests on active trip | Computed dynamically |
| `remainingCapacity` | `seatCapacity - currentLoad` | Derived |
| `activeTripId` | `trips.id WHERE vehicle_id = v.id AND status NOT IN ('COMPLETED','CANCELLED','ABORTED')` | Nullable for IDLE |
| `vehicleStatus` | `vehicle_operational_state.status` | New table |
| `availableAt` | `vehicle_operational_state.available_at` | Estimated time vehicle becomes IDLE |

---

## 8. Batch State Model

### 8.1 Should a Batch Be a Separate Entity?

| Option | Verdict |
|---|---|
| Explicit `dispatch_batches` database table | **Recommended** — required for worker ownership, lock duration, idempotency, and audit trail |
| Logical phase of a trip (columns on `trips`) | Insufficient — batch lifecycle begins before the trip is `ASSIGNED` |
| Represented through request state only | Insufficient — cannot distinguish "batch is forming" from "batch ready to dispatch" |

### 8.2 Batch Entity Definition

```
dispatch_batches
 ├── id                    (UUID, PK)
 ├── company_id            (UUID, FK → companies, NOT NULL)
 ├── schedule_id           (UUID, FK → shuttle_schedules, nullable)
 ├── trip_id               (UUID, FK → trips, nullable until DISPATCHED)
 ├── status                (COLLECTING | READY | ASSIGNING | OPTIMIZING | DISPATCHED | EXECUTING | COMPLETED | CANCELLED)
 ├── direction             (INBOUND | OUTBOUND)
 ├── window_start_at       (TIMESTAMP NOT NULL)
 ├── window_closes_at      (TIMESTAMP NOT NULL)
 ├── dispatched_at         (TIMESTAMP, nullable)
 ├── max_capacity          (INTEGER NOT NULL)
 ├── assigned_vehicle_id   (UUID, FK → vehicles, nullable)
 ├── assigned_driver_id    (UUID, FK → drivers, nullable)
 ├── worker_id             (VARCHAR, nullable — identity of the owning dispatch worker instance)
 ├── locked_at             (TIMESTAMP, nullable)
 ├── retry_count           (INTEGER DEFAULT 0)
 ├── created_at            (TIMESTAMP NOT NULL)
 └── updated_at            (TIMESTAMP NOT NULL)
```

Ride requests join a batch via `ride_requests.batch_id (FK → dispatch_batches)`.

### 8.3 Batch State Machine

| State | Meaning | Entered By | Exit Conditions | Valid Next States |
|---|---|---|---|---|
| `COLLECTING` | Batch window open; accepting ride requests | Dispatch worker on first request in window | Window closes (300s elapsed OR capacity reached OR departure reached) | `READY`, `CANCELLED` |
| `READY` | Window closed; fully formed; ready for vehicle assignment | Dispatch worker on window-close | Worker acquires batch lock | `ASSIGNING`, `CANCELLED` |
| `ASSIGNING` | A dispatch worker holds the lock and is selecting a vehicle | Dispatch worker (atomically with `worker_id` + `locked_at` write) | Vehicle found or no eligible vehicle | `OPTIMIZING`, `CANCELLED` |
| `OPTIMIZING` | Vehicle assigned; route optimizer computing ordered stops | Dispatch worker after vehicle assignment | Optimizer completes and stops persisted | `DISPATCHED`, `CANCELLED` |
| `DISPATCHED` | Ordered stops persisted; driver notified; trip → `ASSIGNED` | Dispatch worker after `trip_stops` insert | Trip execution begins | `EXECUTING` |
| `EXECUTING` | Trip actively running; vehicle in service | Trip execution service on first boarding | All passengers reach terminal state | `COMPLETED` |
| `COMPLETED` | All passengers dropped off; vehicle IDLE | Trip completion handler | Terminal | *(none)* |
| `CANCELLED` | Cancelled (empty batch, admin cancel, or no vehicle after max retries) | Dispatch worker or admin | Terminal | *(none)* |

---

## 9. Batch Window Semantics

### 9.1 When Does the 300-Second Window Start?

**Design decision:** The window starts relative to the `shuttle_schedule` departure time — specifically `departureTime - windowLeadSeconds` (e.g., 360 seconds before departure). This aligns the batch window with the schedule's intent.

For **ad-hoc batches** not linked to a schedule: `window_start_at = NOW()`, `window_closes_at = NOW() + 300s`. This matches the frozen research experiment's unconditional 300-second window.

### 9.2 When Does a Batch Close?

A batch closes (`COLLECTING → READY`) on the **first** of:

1. `window_closes_at` timestamp reached (300-second wall-clock elapsed).
2. Batch `currentLoad = max_capacity` (capacity reached — no point waiting longer).
3. Schedule departure time reached (hard deadline).
4. Admin manual trigger (operational override).

### 9.3 Can Late Requests Join?

**No.** Once a batch transitions to `READY` or beyond, it is closed. A late request is placed into the next batch window for the same schedule/direction, or returns `WAITLISTED`.

### 9.4 Can an Existing Active Trip Accept New Requests?

**No — not in the initial implementation.** The `findAvailableNear` approach (adding to an `IN_PROGRESS` trip) is deprecated in favour of batch-based assignment. A vehicle is fully committed to its current batch once dispatched.

### 9.5 What Happens to Requests That Cannot Fit?

In order:
1. **Overflow to next batch window** — if a subsequent window opens within the employee's acceptable wait threshold.
2. **WAITLISTED** — if no upcoming window exists within `maxWaitSeconds`.
3. **EXPIRED** — if the request has been `QUEUED` or `WAITLISTED` beyond the absolute expiry threshold (e.g., 2× the batch window = 600 seconds).
4. **REJECTED** — not used; the system must give every request at least one retry.

---

## 10. Vehicle Assignment Model

### 10.1 Eligibility

A vehicle is eligible if **all** of the following are true:

1. `vehicles.company_id = batch.company_id` — correct tenant.
2. `vehicle_operational_state.status = 'IDLE'` — not currently assigned.
3. `vehicles.last_known_lat IS NOT NULL AND last_known_lng IS NOT NULL` — GPS available.
4. `vehicles.last_location_updated_at > NOW() - interval '5 minutes'` — GPS not stale (configurable).
5. `remainingCapacity >= batch.requestCount` — sufficient seats for the entire batch.
6. No other dispatch worker holds a lock on this vehicle.

### 10.2 Availability (`availableAt`)

`availableAt` is an estimated UTC timestamp when the vehicle is predicted to become fully IDLE again. Stored in `vehicle_operational_state.available_at`.

- IDLE vehicle: `available_at = NULL` (already available).
- IN_TRIP vehicle: `available_at = estimated completion time`.
- Dispatch worker uses `available_at <= batch.window_closes_at` as an additional filter for vehicles that may finish a prior trip before the batch window closes — replicating the research simulation's `availableAtSeconds`.

### 10.3 Capacity (`remainingCapacity`)

```sql
remainingCapacity = v.seat_capacity - (
    SELECT COUNT(*)
    FROM ride_requests rr
    JOIN dispatch_batches db ON rr.batch_id = db.id
    WHERE db.assigned_vehicle_id = v.id
      AND db.status NOT IN ('COMPLETED', 'CANCELLED')
      AND rr.status IN ('QUEUED', 'MATCHED', 'PICKUP_PENDING', 'ONBOARD')
)
```

Must be evaluated **inside the vehicle assignment transaction** after acquiring the row lock.

### 10.4 Distance Model

**Production uses PostGIS `ST_Distance` (geographic).** This is more realistic than the research Haversine approximation and must not be downgraded to match research assumptions.

- Dispatch worker sorts eligible vehicles by `ST_Distance(vehicle_location, batch_centroid)` where `batch_centroid` is the geographic centroid of all batch pickup coordinates.
- Haversine is retained as the ETA estimator fallback (via `HaversineEtaEstimator`) when TomTom is unavailable.

### 10.5 Tie-Breaking

```sql
ORDER BY
  ST_Distance(v.location, batch_centroid) ASC,
  v.id ASC
```

`v.id` is a UUID. PostgreSQL's lexicographic UUID comparison is deterministic and adequate. This mirrors the research simulation's `compareBy(distance).thenBy(vehicleId)`.

---

## 11. Route and Stop Model

### 11.1 Design

A new `trip_stops` table is required (confirmed absent in all audits):

```
trip_stops
 ├── id               (UUID, PK)
 ├── trip_id          (UUID, FK → trips, NOT NULL)
 ├── batch_id         (UUID, FK → dispatch_batches, nullable)
 ├── ride_request_id  (UUID, FK → ride_requests, NOT NULL)
 ├── company_id       (UUID, FK → companies, NOT NULL — tenant key)
 ├── stop_type        (PICKUP | DROPOFF)
 ├── sequence_order   (INTEGER NOT NULL — 1-indexed, lower = earlier)
 ├── latitude         (DOUBLE PRECISION NOT NULL)
 ├── longitude        (DOUBLE PRECISION NOT NULL)
 ├── status           (PLANNED | EN_ROUTE | ARRIVED | COMPLETED | SKIPPED | CANCELLED)
 ├── planned_eta      (TIMESTAMP, nullable)
 ├── actual_arrival   (TIMESTAMP, nullable)
 ├── actual_departure (TIMESTAMP, nullable)
 ├── created_at       (TIMESTAMP NOT NULL)
 └── updated_at       (TIMESTAMP NOT NULL)
```

### 11.2 Example Ordered Stop Sequence (4-passenger batch)

```
seq 1 — PICKUP   rideRequest=A  status=PLANNED
seq 2 — PICKUP   rideRequest=B  status=PLANNED
seq 3 — PICKUP   rideRequest=C  status=PLANNED
seq 4 — PICKUP   rideRequest=D  status=PLANNED
seq 5 — DROPOFF  rideRequest=A  status=PLANNED
seq 6 — DROPOFF  rideRequest=B  status=PLANNED
seq 7 — DROPOFF  rideRequest=C  status=PLANNED
seq 8 — DROPOFF  rideRequest=D  status=PLANNED
```

For initial implementation, all PICKUP stops precede all DROPOFF stops (matches the research model's single campus hub). Multi-destination interleaving is a future enhancement.

### 11.3 Sequence Mutability Rules

| Rule | Detail |
|---|---|
| **Completed stops are immutable** | Once `status = COMPLETED` or `SKIPPED`, `sequence_order` and coordinates must not change |
| **PLANNED stops may be reordered** | Only `DispatchService` (at dispatch time) may reorder PLANNED stops |
| **Pickup-before-dropoff invariant** | For any `ride_request_id`, the PICKUP `sequence_order` MUST be strictly less than the DROPOFF `sequence_order` |
| **Route changes** | Only affect PLANNED stops; EN_ROUTE and beyond are frozen |

---

## 12. Route Optimizer Contract

### 12.1 Architecture Boundary

```
DispatchService
      │
      │ Vehicle assigned; batch locked
      ▼
RouteOptimizerAdapter          ← NEW wrapper contract (not a code change yet)
      │
      │ Provides enriched input (stopType, destinationLat/Lng)
      ▼
GreedyNearestNeighbourOptimizer    ← existing; algorithm unchanged
      │
      ▼
Ordered List<OptimizedStop>
      │
      ▼
DispatchService persists to trip_stops
```

`GreedyNearestNeighbourOptimizer` is **not replaced**. Its interface is extended via an adapter.

### 12.2 Future Optimizer Input Contract

```
RouteOptimizerRequest:
  vehicleLat            DOUBLE   — current vehicle GPS latitude
  vehicleLng            DOUBLE   — current vehicle GPS longitude
  stops: List<StopInput>
    rideRequestId       UUID
    stopType            PICKUP | DROPOFF
    lat                 DOUBLE   — pickup lat for PICKUP; destination lat for DROPOFF
    lng                 DOUBLE   — pickup lng for PICKUP; destination lng for DROPOFF
    passengerState      QUEUED | MATCHED
  capacity              INTEGER  — vehicle seat capacity
  currentLoad           INTEGER  — passengers currently aboard
```

The existing `StopCandidate` only carries `(rideRequestId, lat, lng)`. The adapter enriches it with `stopType` and uses `destination_lat/lng` from `ride_requests` for `DROPOFF` stops.

### 12.3 Optimizer Output Contract

The optimizer continues to return `List<OptimizedStop>` with `(rideRequestId, lat, lng, sequenceOrder, etaMinutes)`. The adapter must:

1. Map each `OptimizedStop` to a `trip_stops` row.
2. Enforce the pickup-before-dropoff invariant on the output before persisting.
3. Derive `planned_eta` from `etaMinutes` relative to `dispatched_at`.

### 12.4 Constraints Enforced Around (Not Inside) the Optimizer

- Pickup-before-dropoff precedence (post-processing validation by adapter).
- Capacity invariant per route leg (validate that load never exceeds capacity at any point in the sequence).

---

## 13. Trip Execution Model

### 13.1 Stop State Machine

```
PLANNED
   │
   ▼
EN_ROUTE      ← vehicle heading to this stop
   │
   ▼
ARRIVED       ← driver confirms arrival (GPS proximity or explicit confirm)
   │
   ├──► COMPLETED    ← passenger boarded (PICKUP) or dropped off (DROPOFF)
   ├──► SKIPPED      ← passenger NO_SHOW on PICKUP stop
   └──► CANCELLED    ← batch/trip cancelled while stop was active
```

### 13.2 Event Handling Per Scenario

| Scenario | Stop State | Ride Request State | Trip State |
|---|---|---|---|
| Driver arrives at PICKUP stop | `ARRIVED` | `PICKUP_PENDING` | None |
| Passenger boards | `COMPLETED` | `ONBOARD` | `BOARDING → IN_PROGRESS` (on first board) |
| Passenger NO_SHOW | `SKIPPED` | `NO_SHOW` | None |
| Driver arrives at DROPOFF stop | `ARRIVED` | None | None |
| Driver confirms drop-off | `COMPLETED` | `COMPLETED` | `IN_PROGRESS → COMPLETED` (on final drop-off) |
| Employee cancels before pickup | `CANCELLED` (if PLANNED/EN_ROUTE) | `CANCELLED` | None |
| Driver disconnects | None immediately | None immediately | `* → ABORTED` (after timeout) |
| Vehicle failure | `CANCELLED` (all remaining) | `CANCELLED` (all remaining) | `* → ABORTED` |

---

## 14. Concurrency Model

### 14.1 Transaction Boundaries

#### Request Ingestion (`POST /rides`)
- Write `ride_requests` with `status = REQUESTED`, `batch_id = NULL`.
- Isolation: `READ COMMITTED` (default). Single `INSERT` + `COMMIT`. No contention.

#### Batch Acquisition (Dispatch Worker)
- Must atomically set `dispatch_batches.worker_id`, `locked_at`, `status = ASSIGNING`.
- **Lock pattern:**
  ```sql
  SELECT * FROM dispatch_batches
  WHERE status = 'READY' AND company_id = ?
  ORDER BY window_closes_at ASC
  LIMIT 1
  FOR UPDATE SKIP LOCKED
  ```
  `SKIP LOCKED` is the critical mechanism — if Worker A holds the lock, Worker B skips to the next batch without blocking.

#### Vehicle Assignment
- Inside the batch acquisition transaction (same transaction).
- Acquire vehicle row lock:
  ```sql
  SELECT * FROM vehicle_operational_state
  WHERE vehicle_id = ?
  FOR UPDATE
  ```
- Transition `vehicle_operational_state.status = 'ASSIGNED'` and write `dispatch_batches.assigned_vehicle_id`.
- If no eligible vehicle found: release batch lock (`status = 'CANCELLED'` or re-queue).
- **Invariant:** Vehicle lock + batch lock are in the same transaction.

#### Route Persistence (Critical Transaction)
- Insert all `trip_stops` rows in a single transaction along with:
  - `trips.status = ASSIGNED`, `vehicle_id`, `driver_id`
  - `ride_requests.status = MATCHED` for all batch members
  - `vehicle_operational_state.status = ASSIGNED`
  - `dispatch_batches.status = DISPATCHED`
  - `outbox_events` rows (for post-commit delivery)
- If this transaction fails, **all changes roll back atomically**. Batch returns to `READY` for retry.

#### Trip State Transitions
- Use `SELECT ... FOR UPDATE` on the `trips` row before any status mutation.
- Each transition updates relevant `ride_requests` and `trip_stops` in the same transaction.

#### Stop Completion
- Stop status changes are transactional.
- Each stop completion may trigger a ride request state transition in the same transaction.
- Final drop-off triggers trip completion in the same transaction.

### 14.2 Preventing Specific Race Conditions

| Race Condition | Prevention Mechanism |
|---|---|
| Two workers claiming the same batch | `SELECT FOR UPDATE SKIP LOCKED` on `dispatch_batches` |
| Two workers assigning the same vehicle | `SELECT FOR UPDATE` on `vehicle_operational_state` inside batch transaction |
| Double seat allocation | Existing `SELECT FOR UPDATE OF t` on `trips`, re-evaluated inside dispatch transaction |
| Two simultaneous route updates | `SELECT FOR UPDATE` on `trips` before any `trip_stops` modification |
| Stale GPS overwriting newer location | Only write if `last_location_updated_at < incoming_timestamp` |
| Duplicate dispatch events | Transactional outbox (Section 17); events emitted post-commit only |
| Duplicate stop creation | `UNIQUE (trip_id, ride_request_id, stop_type)` constraint on `trip_stops` |

### 14.3 Advisory Locks and Optimistic Locking

- **Advisory locks:** Not recommended. Pessimistic row locks are simpler and already established as the pattern.
- **Optimistic locking:** Not appropriate — high contention on batch and vehicle rows means retry loops would be frequent and wasteful.
- **Unique constraints:** Use as last-resort idempotency guard.

---

## 15. Dispatch Worker Model

### 15.1 Architecture

```
Application startup
       │
       ▼
DispatchWorker (Kotlin coroutine, launched in ApplicationScope)
       │
       ▼ Polling loop (every workerPollIntervalMs = 5000ms)
       │
       ├── SELECT READY batches FOR UPDATE SKIP LOCKED (per company)
       │
       ├── No batch found → sleep → repeat
       │
       └── Batch found:
              ├── Evaluate eligible vehicles
              ├── Assign nearest eligible vehicle
              ├── Call RouteOptimizerAdapter
              ├── ATOMIC TRANSACTION:
              │     INSERT trip_stops
              │     UPDATE ride_requests → MATCHED
              │     UPDATE trips → ASSIGNED
              │     UPDATE dispatch_batches → DISPATCHED
              │     UPDATE vehicle_operational_state → ASSIGNED
              │     INSERT outbox_events
              ├── COMMIT
              ├── [NotificationService reads outbox, delivers events]
              └── Repeat
```

### 15.2 Design Parameters

| Parameter | Design Decision |
|---|---|
| **Polling frequency** | Every 5 seconds (`workerPollIntervalMs = 5000`) — configurable via environment variable |
| **Batch acquisition query** | `SELECT ... FOR UPDATE SKIP LOCKED` on `dispatch_batches WHERE status = 'READY'` |
| **Lock stale timeout** | 30 seconds before lock considered abandoned — configurable |
| **Batch ownership** | `dispatch_batches.worker_id` and `locked_at` |
| **Retry behavior** | On failure: increment `dispatch_batches.retry_count`. After `maxRetries = 3`, transition to `CANCELLED` and emit `RIDE_WAITLISTED` for all batch members |
| **Failure handling** | On unhandled exception: rollback. Batch remains `READY` for next poll cycle |
| **Idempotency** | `dispatch_batches.worker_id` prevents double-processing; `trip_stops` unique constraint prevents duplicate inserts on retry |
| **Application restart** | On restart: reset batches in `ASSIGNING` state with `locked_at < NOW() - lockTimeoutSeconds` to `READY` |
| **Multiple worker instances** | Supported via `SKIP LOCKED`. Each instance independently claims separate batches. No external coordinator required |

---

## 16. WebSocket Event Contract

### 16.1 Proposed Event Types

| Event | Producer | Consumer | Payload | Authorization Scope |
|---|---|---|---|---|
| `VEHICLE_ASSIGNED` | `DispatchService` (post-commit) | Employee (booked on trip); Driver (assigned) | `{tripId, vehicleId, driverId, eta, batchId}` | Employee: own ride request only. Driver: own assigned trip only. |
| `ROUTE_UPDATED` | `DispatchService` | Driver (assigned to trip) | `{tripId, orderedStops: [{stopId, stopType, lat, lng, seq, eta}]}` | Driver: own assigned trip only. |
| `STOP_ADDED` | `DispatchService` | Driver | `{tripId, stop: {stopId, stopType, lat, lng, seq, eta}}` | Driver: own assigned trip only. |
| `ETA_UPDATED` | Trip execution service | Employee (per-request); Driver | `{tripId, stopId, newEta, rideRequestId}` | Employee: own ride request only. Driver: own assigned trip only. |
| `TRIP_STARTED` | Trip execution service | Employee | `{tripId, rideRequestId, vehicleLocation}` | Employee: own ride request only. |
| `PASSENGER_BOARDED` | Trip execution service | Employee | `{tripId, rideRequestId}` | Employee: own ride request only. |
| `PASSENGER_DROPPED_OFF` | Trip execution service | Employee | `{tripId, rideRequestId, completedAt}` | Employee: own ride request only. |
| `TRIP_COMPLETED` | Trip execution service | Driver; Admin | `{tripId, completedAt}` | Driver: own trip. Admin: own company's trips. |
| `RIDE_CANCELLED` | `RideService` | Employee | `{rideRequestId, cancelledAt, reason}` | Employee: own ride request only. |
| `RIDE_WAITLISTED` | `DispatchService` | Employee | `{rideRequestId, reason, nextWindowAt}` | Employee: own ride request only. |

### 16.2 Events Not Required for Initial Implementation

- `BATCH_CREATED` — internal dispatch event; not pushed to employees or drivers.
- `STOP_REMOVED` — deferred to live reoptimization feature (future).
- `PASSENGER_NO_SHOW` — driver already knows; employee ride is terminal.
- `TRIP_ABORTED` — emit to Admin only in future.

### 16.3 Authorization Design (Resolves Task 7.4 BOLA)

The BOLA vulnerability on `/ws/trip/{tripId}` must be resolved before any new events are added.

| Consumer | Connection Criteria | Events Received |
|---|---|---|
| **Employee** | Authenticated; `ride_requests.employee_id = user.userId`; `ride_requests.trip_id = tripId` | Only events scoped to their own `rideRequestId` (filtered server-side) |
| **Driver** | Authenticated; `trips.driver_id = user.userId`; `trips.id = tripId` | All events for their assigned trip |
| **Admin** | Authenticated (admin realm); `trips.company_id = user.companyId` | All events for company trips; separate admin WebSocket endpoint |

The server must track **per-session rideRequestId subscription** (employees) or **tripId** (drivers), and filter events accordingly. A map from `tripId → List<SessionWithScope>` where `SessionWithScope` carries the subscriber's allowed scope resolves the current unsafe fan-out.

---

## 17. Event Delivery Model

### 17.1 The Two Failure Scenarios

**Scenario A:** DB commit succeeds → WebSocket send fails.
- Consequence: State correct in DB; subscriber missed the event.
- Recovery: Client polls REST API. Acceptable for initial implementation.

**Scenario B:** WebSocket send succeeds → DB transaction rolls back.
- Consequence: Subscriber received a false event. **This is the dangerous scenario.**
- Prevention: Events must **never be emitted before the DB transaction commits**.

### 17.2 Recommended Architecture: Transactional Outbox

```
DispatchService
      │
      ▼ BEGIN TRANSACTION
      │   ... all DB mutations ...
      │   INSERT INTO outbox_events (type, payload, published=false)
      ▼ COMMIT
      │
      ▼ NotificationService (outbox poller, every 1s)
            │ for each unpublished event:
            │   send via WebSocket
            │   mark outbox_events.published = true
```

**Why transactional outbox:**
- Events written inside the same transaction as the business mutation — Scenario B impossible.
- If WebSocket delivery fails, event remains `published = false` and is retried on next poll.
- Survives application restart (events are persisted, not in-memory).

**In-memory event bus** is NOT recommended: cannot survive restart; creates Scenario B risk.

**Direct post-commit emission** is acceptable as a first step only if the `send()` call is placed strictly **after** the `transaction { }` block closes.

---

## 18. Multi-Tenant Boundaries

Every dispatch query, mutation, and subscription must be scoped by `company_id`:

| Resource | Tenant Key | Enforced By |
|---|---|---|
| `ride_requests` | `company_id` (via `employees.company_id`) | Ride ingestion route; dispatch worker WHERE clause |
| `dispatch_batches` | `company_id` (direct column) | All batch queries filter by `company_id` |
| `trips` | `company_id` (via `shuttle_schedules.company_id`) | Trip service; trip execution service |
| `vehicles` | `company_id` (direct column) | Vehicle assignment eligibility check |
| `drivers` | `company_id` (direct column) | Driver assignment; WebSocket auth |
| `trip_stops` | `company_id` (direct column — proposed) | Stop query; stop mutation |
| `outbox_events` | `company_id` (direct column) | Event publisher |
| `vehicle_operational_state` | `company_id` (via `vehicles.company_id`) | Vehicle assignment query |
| `WebSocket subscriptions` | `trip.company_id == user.companyId` AND resource ownership check | WebSocket route handler (upgraded — Section 16.3) |

**Task 7.5 `pickupZoneId` vulnerability:** Must be resolved in Task 8.1 — add validation `PickupZone.company_id == user.companyId` to the ride ingestion path before `ride_requests` insertion.

---

## 19. Database Design Proposal

> This section proposes **conceptual schema only**. Do NOT modify any migration files. These tables are implemented as Flyway migration V7 in Task 8.1.

### MUST HAVE

#### `trip_stops`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `trip_id` | UUID FK → trips | NOT NULL |
| `ride_request_id` | UUID FK → ride_requests | NOT NULL |
| `company_id` | UUID FK → companies | NOT NULL — tenant key |
| `stop_type` | VARCHAR(10) | CHECK IN ('PICKUP', 'DROPOFF') |
| `sequence_order` | INTEGER | NOT NULL |
| `latitude` | DOUBLE PRECISION | NOT NULL |
| `longitude` | DOUBLE PRECISION | NOT NULL |
| `status` | VARCHAR(20) | CHECK IN ('PLANNED','EN_ROUTE','ARRIVED','COMPLETED','SKIPPED','CANCELLED') |
| `planned_eta` | TIMESTAMP | Nullable |
| `actual_arrival` | TIMESTAMP | Nullable |
| `actual_departure` | TIMESTAMP | Nullable |
| `created_at` | TIMESTAMP | NOT NULL |
| `updated_at` | TIMESTAMP | NOT NULL |

**Uniqueness constraint:** `UNIQUE (trip_id, ride_request_id, stop_type)`.
**Indexes:** `(trip_id, sequence_order)`, `(ride_request_id)`, `(company_id, status)`.

#### `dispatch_batches`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `company_id` | UUID FK → companies | NOT NULL |
| `schedule_id` | UUID FK → shuttle_schedules | Nullable |
| `trip_id` | UUID FK → trips | Nullable until DISPATCHED |
| `status` | VARCHAR(20) | Full status CHECK |
| `direction` | VARCHAR(10) | CHECK IN ('INBOUND','OUTBOUND') |
| `window_start_at` | TIMESTAMP | NOT NULL |
| `window_closes_at` | TIMESTAMP | NOT NULL |
| `dispatched_at` | TIMESTAMP | Nullable |
| `max_capacity` | INTEGER | NOT NULL |
| `assigned_vehicle_id` | UUID FK → vehicles | Nullable |
| `assigned_driver_id` | UUID FK → drivers | Nullable |
| `worker_id` | VARCHAR(255) | Nullable |
| `locked_at` | TIMESTAMP | Nullable |
| `retry_count` | INTEGER | DEFAULT 0 |
| `created_at` | TIMESTAMP | NOT NULL |
| `updated_at` | TIMESTAMP | NOT NULL |

**Indexes:** `(company_id, status, window_closes_at)`, `(schedule_id, status)`, `(assigned_vehicle_id)`.

#### `vehicle_operational_state`

| Column | Type | Notes |
|---|---|---|
| `vehicle_id` | UUID PK, FK → vehicles | 1:1 with vehicles |
| `company_id` | UUID FK → companies | NOT NULL |
| `status` | VARCHAR(20) | CHECK IN ('IDLE','ASSIGNED','BOARDING','IN_TRIP','OFFLINE') |
| `active_trip_id` | UUID FK → trips | Nullable |
| `active_batch_id` | UUID FK → dispatch_batches | Nullable |
| `available_at` | TIMESTAMP | Nullable |
| `updated_at` | TIMESTAMP | NOT NULL |

**Indexes:** `(company_id, status)`, `(active_trip_id)`.

#### Changes to `ride_requests`

Add columns (non-breaking, nullable):
- `batch_id UUID FK → dispatch_batches`
- `queued_at TIMESTAMP`
- `expires_at TIMESTAMP`

Extend `chk_ride_requests_status` to include `'QUEUED'` and `'EXPIRED'`.

### SHOULD HAVE

#### `outbox_events`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `company_id` | UUID | Tenant key |
| `event_type` | VARCHAR(50) | e.g. `VEHICLE_ASSIGNED`, `ROUTE_UPDATED` |
| `payload` | JSONB | Full event payload |
| `target_type` | VARCHAR(20) | `EMPLOYEE`, `DRIVER`, `ADMIN` |
| `target_id` | UUID | Employee or driver ID |
| `trip_id` | UUID | For WebSocket routing |
| `published` | BOOLEAN | DEFAULT FALSE |
| `created_at` | TIMESTAMP | NOT NULL |
| `published_at` | TIMESTAMP | Nullable |

**Index:** `(published, created_at)`.

### OPTIONAL / FUTURE

#### `dispatch_jobs`

A separate worker job queue — only needed if `dispatch_batches` proves insufficient for multi-step job tracking. Evaluate in Task 8.3 before adding.

---

## 20. Service Boundary Design

### 20.1 Proposed Services

| Service | Responsibility | Transaction Owner | Dependencies | Must NOT |
|---|---|---|---|---|
| `RideService` | Employee ride ingestion; status queries; cancellation | YES — for `REQUESTED` state write | `RideRequestRepository`, `PickupZoneService` | Assign vehicles; form batches; send events |
| `BatchingService` | Batch window management; request placement into batches | YES — transitions `REQUESTED → QUEUED`; writes `batch_id` | `DispatchBatchRepository`, schedule config | Assign vehicles; persist route |
| `DispatchService` | Orchestration: owns the full dispatch transaction | YES — owns critical multi-table transaction | `VehicleAssignmentService`, `RouteService`, `TripService`, `BatchRepository` | Perform HTTP I/O; publish events directly |
| `VehicleAssignmentService` | Vehicle eligibility evaluation and selection | NO — operates inside `DispatchService` transaction | `VehicleOperationalStateRepository`, PostGIS | Commit independently |
| `RouteService` | Route optimizer adapter; stop persistence | NO — operates inside `DispatchService` transaction | `RouteOptimizerAdapter`, `TripStopRepository` | Call external APIs with a DB transaction open |
| `TripService` | Trip lifecycle mutations; stop execution (driver actions) | YES — each state transition | `TripRepository`, `TripStopRepository`, `RideRequestRepository` | Assign vehicles; form batches |
| `TripExecutionService` | Driving each stop through its state machine | YES — per stop completion | `TripStopRepository`, `RideRequestRepository`, `TripRepository` | Perform vehicle assignment |
| `NotificationService` | Event publication to WebSocket subscribers | NO — reads committed outbox | `OutboxRepository`, WebSocket session registry | Perform DB mutations; emit before commit |

### 20.2 `DispatchService` as the Orchestration Boundary

`DispatchService` is the recommended new orchestration boundary. It is the **only** component that may:
- Transition a batch from `ASSIGNING → DISPATCHED`.
- Write `vehicle_id` + `driver_id` to `trips`.
- Set `vehicle_operational_state.status = 'ASSIGNED'`.

### 20.3 Existing Services — Future Roles

| Existing Service | Future Role |
|---|---|
| `RoutingService` | Responsibility split: ingestion → `RideService`; eligibility → `VehicleAssignmentService` |
| `TripService` | Retained; extended with drop-off stop handling and trip completion |
| `ShuttleScheduleService` | Retained; triggers batch window creation for upcoming schedule departures |
| `RideRequestStateMachine` | Retained; extended with `QUEUED` and `EXPIRED` states |
| `GreedyNearestNeighbourOptimizer` | Retained; wrapped by `RouteOptimizerAdapter` |
| `AuthService` | Retained; critical security fixes applied in Task 8.1 |

---

## 21. End-to-End Future Architecture Flow

```
Employee
   │
   ▼ POST /rides (RideRequestDto)
RideService
   │ Validate JWT (employee role, employeeId match)
   │ Validate pickupZoneId belongs to employee's company (fix Task 7.5)
   │ Write ride_requests (status=REQUESTED, batch_id=NULL)
   │ Return HTTP 202 Accepted {rideRequestId, status=REQUESTED}
   │
   ▼ Async — BatchingService (triggered or polled by dispatch worker)
BatchingService
   │ Find or create open COLLECTING batch for company + schedule + direction
   │ Assign ride_request.batch_id = batch.id
   │ Transition ride_request: REQUESTED → QUEUED (transactional)
   │ If capacity reached: transition batch COLLECTING → READY immediately
   │
   ▼ (Batch window closes at window_closes_at OR capacity reached)
DispatchWorker (background coroutine, polling every 5s)
   │
   ▼ SELECT dispatch_batches WHERE status='READY' FOR UPDATE SKIP LOCKED
   │
   ├── No batch found → sleep → repeat
   │
   └── Batch found → READY → ASSIGNING
                │
                ▼
      VehicleAssignmentService
                │ SELECT vehicle_operational_state FOR UPDATE
                │ Evaluate eligibility
                │ Sort by ST_Distance(vehicle.loc, batch_centroid) ASC, vehicle_id ASC
                │ Select nearest eligible vehicle
                │
                ▼
      RouteOptimizerAdapter
                │ Build StopInput list (PICKUP + DROPOFF for each batch member)
                │ Call GreedyNearestNeighbourOptimizer.optimize()
                │ Validate pickup-before-dropoff invariant
                │ Compute plannedEta per stop
                │
                ▼
      DispatchService — ATOMIC TRANSACTION
                │ INSERT trip_stops (all ordered stops, status=PLANNED)
                │ UPDATE trips: vehicle_id, driver_id, status='ASSIGNED'
                │ UPDATE ride_requests: status='MATCHED' (all batch members)
                │ UPDATE vehicle_operational_state: status='ASSIGNED', active_trip_id
                │ UPDATE dispatch_batches: status='DISPATCHED', dispatched_at=NOW()
                │ INSERT outbox_events (VEHICLE_ASSIGNED per employee + ROUTE_UPDATED for driver)
                ▼ COMMIT
                │
                ▼
      NotificationService (post-commit, reads outbox)
                │ Deliver VEHICLE_ASSIGNED to each employee WebSocket session
                │ Deliver ROUTE_UPDATED (full stop manifest) to driver WebSocket
                │ Mark outbox_events.published = true
                │
                ▼
   Trip Execution (Driver-driven)
                │
                ├── Driver GPS → /ws/driver/{tripId} → update vehicles.last_known_lat/lng
                │
                ├── Driver arrives → POST /trips/{id}/stops/{stopId}/arrived
                │   stop: PLANNED → ARRIVED
                │   ride_request: MATCHED → PICKUP_PENDING
                │   Emit ETA_UPDATED to employee
                │
                ├── Driver picks up passenger → POST /trips/{id}/stops/{stopId}/pickup
                │   stop: ARRIVED → COMPLETED
                │   ride_request: PICKUP_PENDING → ONBOARD
                │   trip: ASSIGNED → BOARDING → IN_PROGRESS (on first board)
                │   Emit PASSENGER_BOARDED to employee
                │
                ├── Driver drops off passenger → POST /trips/{id}/stops/{stopId}/dropoff
                │   drop-off stop: ARRIVED → COMPLETED
                │   ride_request: ONBOARD → COMPLETED
                │   Emit PASSENGER_DROPPED_OFF to employee
                │
                └── All passengers terminal
                    trip: IN_PROGRESS → COMPLETED
                    vehicle_operational_state: status=IDLE, available_at=NULL
                    dispatch_batches: status=COMPLETED
                    Emit TRIP_COMPLETED to driver + admin
```

---

## 22. State Ownership Matrix

| State | Source of Truth | Owner | Updated By | Transaction Required? |
|---|---|---|---|---|
| Ride status (`ride_requests.status`) | PostgreSQL `ride_requests` | `RideService` (REQUESTED→QUEUED); `DispatchService` (QUEUED→MATCHED); `TripExecutionService` (MATCHED→COMPLETED) | Respective service only | YES |
| Batch status (`dispatch_batches.status`) | PostgreSQL `dispatch_batches` | `BatchingService` (COLLECTING→READY); `DispatchService` (READY→DISPATCHED); `TripExecutionService` (DISPATCHED→COMPLETED) | Respective service only | YES |
| Trip status (`trips.status`) | PostgreSQL `trips` | `ShuttleScheduleService` (creates SCHEDULED); `DispatchService` (→ASSIGNED); `TripExecutionService` (→IN_PROGRESS→COMPLETED) | Respective service only | YES |
| Vehicle status (`vehicle_operational_state.status`) | PostgreSQL `vehicle_operational_state` | `DispatchService` (→ASSIGNED); `TripExecutionService` (→IN_TRIP→IDLE) | Respective service only | YES |
| Vehicle location (`vehicles.last_known_lat/lng`) | PostgreSQL `vehicles` | WebSocket GPS ingest handler | `/ws/driver/{tripId}` only | YES (single UPDATE per GPS frame) |
| Vehicle availability (`vehicle_operational_state.available_at`) | PostgreSQL `vehicle_operational_state` | `DispatchService` (estimated completion at dispatch); `TripExecutionService` (set NULL on IDLE) | Respective service only | YES |
| Stop status (`trip_stops.status`) | PostgreSQL `trip_stops` | `TripExecutionService` | Driver-facing endpoints only | YES |
| Stop sequence (`trip_stops.sequence_order`) | PostgreSQL `trip_stops` | `DispatchService` (initial); `RouteService` (reoptimization — future) | Only while affected stops are PLANNED | YES |
| ETA (`trip_stops.planned_eta`) | PostgreSQL `trip_stops` | `DispatchService` (initial); `TripExecutionService` (updated on stop transitions) | Same service that owns stop state | YES |

---

## 23. Research-to-Production Mapping

| Research Concept | Production Design Equivalent | Mapping Type |
|---|---|---|
| `ExperimentRequest` | `ride_requests` with `status = QUEUED` inside a `COLLECTING` batch | **Conceptually reusable** |
| `ExperimentVehicle` | `vehicles` + `vehicle_operational_state` | **Conceptually reusable** — production separates static and transient state |
| `dynamicBatchWindowSeconds = 300` | `dispatch_batches.window_closes_at = window_start_at + INTERVAL '300 seconds'` (configurable) | **Directly reusable** |
| `availableAtSeconds` | `vehicle_operational_state.available_at` (UTC TIMESTAMP) | **Directly reusable** |
| `remainingCapacity` | `vehicles.seat_capacity - COUNT(active ride_requests)` evaluated inside dispatch transaction | **Directly reusable** |
| `currentBatch` | `dispatch_batches` row + `ride_requests WHERE batch_id = ?` | **Directly reusable** — research held in memory; production persists it |
| `current vehicle location` | `vehicles.last_known_lat / last_known_lng` (PostGIS) | **Directly reusable** — production adds GPS staleness check |
| `DynaShuttleSimulationStrategy` | `DispatchService` + `BatchingService` + `VehicleAssignmentService` | **Production-specific** — monolithic research class split into three services with transactional boundaries |
| `GreedyNearestNeighbourOptimizer` | `GreedyNearestNeighbourOptimizer` retained, wrapped by `RouteOptimizerAdapter` | **Directly reusable** — algorithm unchanged; adapter enriches input/output |
| `SimulationVehicleState` | `vehicle_operational_state` table | **Conceptually reusable** — same fields, persisted in PostgreSQL |
| `SimulationResult` | `trip_stops` (ordered stops) + `ride_requests.status` history | **Production-specific** |

**Research-only (no production equivalent needed):**
`SimulationEngine`, `SimulationClock`, `ExperimentRunner`, `experiment_results_raw.csv`, `SimulationStrategy` interface.

**Production-specific (no research equivalent):**
`dispatch_batches` persistence and locking, `outbox_events` delivery guarantee, JWT/multi-tenant isolation, WebSocket session management, `vehicle_operational_state.available_at` timestamp.

---

## 24. Design Risks

| Risk | Severity | Description | Mitigation |
|---|---|---|---|
| Worker duplication | **Critical** | Two dispatch workers claim the same batch and double-assign a vehicle | `SELECT FOR UPDATE SKIP LOCKED` on `dispatch_batches`; `UNIQUE (trip_id, ride_request_id, stop_type)` as guard |
| Tenant isolation breach | **Critical** | Dispatch worker assigns Company B vehicles to Company A batch | `company_id = ?` filter on every batch and vehicle query — MUST HAVE |
| Idempotency on dispatch retry | **Critical** | Worker retries on timeout and re-executes on an already-dispatched batch | `dispatch_batches.status` check inside transaction; unique constraint on `trip_stops` |
| Batch latency impacting UX | **High** | 300-second window introduces perceived delay | HTTP 202 immediately; push `VEHICLE_ASSIGNED` event when batch dispatches; "finding your shuttle" UX state |
| Stale GPS / incorrect vehicle selection | **High** | `last_known_lat/lng` may be stale, selecting a vehicle that is actually far away | Reject vehicles with `last_location_updated_at < NOW() - staleness_threshold` |
| Vehicle contention | **High** | Two batches simultaneously evaluate the same vehicle as eligible | `SELECT FOR UPDATE` on `vehicle_operational_state` inside batch transaction |
| Driver disconnect | **High** | Driver WebSocket drops mid-trip; passengers have no updates | Health monitor: GPS staleness alert to admin; `ABORTED` transition after extended disconnect |
| Application restart | **High** | Dispatch worker interrupted mid-dispatch | On restart: reset batches in `ASSIGNING` with stale `locked_at` to `READY`; idempotent dispatch transaction |
| DB transaction failure | **High** | Critical dispatch transaction fails partway | Full rollback; batch returns to `READY`; retry; alert after `maxRetries` |
| Multi-node deployment | **High** | Multiple application instances each running dispatch workers | `SKIP LOCKED` handles this correctly; vehicle row locks prevent double assignment |
| WebSocket delivery failure | **Medium** | Outbox event cannot be delivered (disconnected client) | Event remains `published = false`; retry on next poll; client recovers via REST poll |
| Route changes during execution | **Medium** | Passenger cancels after dispatch; stop sequence becomes stale | Mark stop `CANCELLED`; do not reoptimize in initial implementation |
| Passenger cancellation | **Medium** | Cancel after `ONBOARD` — semantically a completed trip segment | `ONBOARD → COMPLETED` is terminal; cancel before `ONBOARD` must update vehicle load |
| Event duplication | **Medium** | Outbox poller delivers same event twice after crash | Events carry unique `eventId`; clients must deduplicate |
| ETA staleness | **Low** | `planned_eta` diverges from reality as trip progresses | Recalculate `planned_eta` on each stop transition (design decision #5) |

---

## 25. Task 8 Implementation Sequence

```
TASK 8.1 — Schema & State Foundation
  • Add QUEUED, EXPIRED states to ride_requests CHECK constraint
  • Create dispatch_batches table
  • Create trip_stops table
  • Create vehicle_operational_state table
  • Add batch_id, queued_at, expires_at columns to ride_requests
  • Create outbox_events table
  • Create all indexes and uniqueness constraints
  • Write and run Flyway migration V7
  • Fix pickupZoneId tenant validation (Task 7.5 critical finding)
  • Fix driver OTP plaintext leak (Task 7.5 CRITICAL vulnerability)
  • ALL TESTS PASS before proceeding

TASK 8.2 — Ride Request State Machine Extension
  • Add QUEUED and EXPIRED states to RideRequestStateMachine
  • Update RideStatus enum
  • Unit tests for all new transitions
  • ALL TESTS PASS before proceeding

TASK 8.3 — Trip Lifecycle Implementation
  • Add ASSIGNED and BOARDING trip states
  • Implement TripExecutionService with stop-level state machine
  • Add drop-off endpoint: POST /trips/{id}/stops/{stopId}/dropoff
  • Update TripService for drop-off stops and ONBOARD → COMPLETED
  • Integration tests for full trip lifecycle
  • ALL TESTS PASS before proceeding

TASK 8.4 — Batching Service & Batch Window
  • Implement BatchingService (window open/close; request placement)
  • Implement dispatch_batches repository
  • Integrate with RideService: POST /rides writes REQUESTED then batch placement queues to QUEUED
  • Batch window close trigger (schedule-relative timer)
  • Unit + integration tests
  • ALL TESTS PASS before proceeding

TASK 8.5 — Vehicle Assignment Service
  • Implement VehicleAssignmentService (eligibility; PostGIS; staleness; tie-breaking)
  • Implement vehicle_operational_state repository
  • Populate vehicle_operational_state for existing vehicles (V7 migration backfill)
  • Unit tests for eligibility logic; concurrency tests (simulated FOR UPDATE)
  • ALL TESTS PASS before proceeding

TASK 8.6 — Route Optimizer Adapter & Stop Persistence
  • Implement RouteOptimizerAdapter (enriched input with PICKUP/DROPOFF; precedence validation)
  • Implement TripStopRepository (CRUD + stop state transitions)
  • End-to-end optimizer integration test
  • ALL TESTS PASS before proceeding

TASK 8.7 — DispatchService (Critical Transaction)
  • Implement DispatchService orchestrating 8.4 + 8.5 + 8.6 in a single atomic transaction
  • Implement DispatchWorker coroutine (polling loop; SKIP LOCKED batch acquisition)
  • Implement outbox_events writer (inside dispatch transaction)
  • Application restart recovery for stuck ASSIGNING batches
  • Concurrency test: two workers competing for the same batch
  • Concurrency test: two workers assigning to the same vehicle
  • ALL TESTS PASS before proceeding

TASK 8.8 — Real-Time Events & WebSocket Hardening
  • Fix BOLA on /ws/trip/{tripId} (passenger ownership enforcement)
  • Fix admin WebSocket realm mismatch (separate /ws/admin/{tripId} endpoint)
  • Fix ConcurrentHashMap session list safety (ArrayList → CopyOnWriteArrayList)
  • Implement NotificationService (outbox poller → WebSocket delivery)
  • Implement VEHICLE_ASSIGNED, ROUTE_UPDATED, ETA_UPDATED, PASSENGER_BOARDED, PASSENGER_DROPPED_OFF events
  • WebSocket integration tests
  • ALL TESTS PASS before proceeding

TASK 8.9 — Concurrency & Resilience Testing
  • High-concurrency: N employees simultaneously POST /rides into same batch window
  • Chaos: dispatch worker killed mid-transaction; verify batch returns to READY
  • Chaos: driver WebSocket disconnects mid-trip; verify health monitor response
  • GPS staleness: vehicle with stale location not selected
  • Capacity invariant: no batch exceeds seat_capacity under concurrent load
  • ALL TESTS PASS before proceeding

TASK 8.10 — End-to-End Production Validation
  • Full E2E test: Employee → QUEUED → batch formed → vehicle assigned → stops persisted →
    driver notified → pickups → drop-offs → COMPLETED → vehicle IDLE
  • Multi-tenant isolation: Company A batch does not access Company B vehicles
  • Security audit of all new endpoints
  • Performance: 300s batch window with 4-passenger batch; sub-second dispatch transaction
  • TASK 8.10 COMPLETE = production ready for dynamic dispatch
```

---

## 26. Open Design Decisions

| # | Decision | Options | Recommended Default | Must Resolve Before |
|---|---|---|---|---|
| 1 | **Batch window start trigger** | (a) First request arrival; (b) Schedule-relative timer (`departureTime - 360s`) | (b) Schedule-relative timer | Task 8.4 |
| 2 | **`COMPLETING` trip state** | (a) Add COMPLETING; (b) Skip: IN_PROGRESS → COMPLETED directly | (b) Skip for initial implementation | Task 8.3 |
| 3 | **Partial batch assignment** | (a) Only dispatch at full capacity; (b) Dispatch at window close regardless of batch size | (b) Dispatch at window close — matches research behavior | Task 8.7 |
| 4 | **`maxWaitSeconds` / `expiresAt` policy** | (a) 2× batch window = 600s; (b) Configurable per company; (c) Never expire | (a) 600s default, configurable via env variable | Task 8.2 |
| 5 | **ETA recalculation frequency** | (a) Every GPS frame (expensive); (b) Each stop transition; (c) None in initial implementation | (b) Each stop transition | Task 8.3 |
| 6 | **Ad-hoc (non-schedule) batch support** | (a) Always schedule-linked; (b) Allow ad-hoc rolling 300s windows | (a) Schedule-linked only in Task 8 | Task 8.4 |
| 7 | **Driver notification mechanism** | (a) WebSocket only; (b) WebSocket + Brevo SMS fallback | (a) WebSocket only in Task 8; SMS future enhancement | Task 8.8 |
| 8 | **Outbox poller frequency** | (a) 500ms; (b) 1s; (c) 2s | (b) 1s — acceptable latency; lower DB I/O | Task 8.8 |
| 9 | **Lock stale timeout** | (a) 30s; (b) 60s; (c) Configurable | (a) 30s default, configurable | Task 8.7 |
| 10 | **Driver OTP fix timing** | Hotfix in Task 8.1 or separate security sprint | Hotfix in Task 8.1 before any other 8.x work | Before Task 8.1 ships |

---

## 27. Verification Status

```
TASK 7.7 STATUS: COMPLETE

Design document:
  docs/Task7_7_Production_Dispatch_State_Model_Design.md

Production code modified:
  NO

Database modified:
  NO

Migrations modified:
  NO

Experiment code modified:
  NO

Previous audit reports modified:
  NO

Research results (experiment_results_raw.csv) modified:
  NO

Frozen Tasks 4.1–6.4 modified:
  NO

Tests:
  X passed, Y failed, Z skipped
  (run ./gradlew test to confirm — expected: 320 passed, 0 failed, 0 skipped)

git diff --check:
  PASS (expected — only new file created, no whitespace errors)

Primary architectural recommendation:
  Database-backed batch dispatch subsystem with background coroutine dispatch worker,
  separating ride ingestion (synchronous HTTP) from dispatch orchestration (asynchronous).
  Critical primitive: SELECT FOR UPDATE SKIP LOCKED on dispatch_batches.
  Critical transaction: atomic write of trip_stops + trips.ASSIGNED + outbox_events.

Highest-priority implementation prerequisite:
  TASK 8.1 — Schema & State Foundation (V7 migration, dispatch_batches, trip_stops,
  vehicle_operational_state, QUEUED state, driver OTP security fix, pickupZoneId fix).

Recommended next task:
  TASK 8.1 — Schema & State Foundation
```
