# Task 8.1 — Production Schema & State Foundation

---

## 1. Scope

This task establishes the persistent database schema primitives required by the production dynamic batching and vehicle dispatch state model designed in [Task 7.7](file:///c:/project-shuttles-2026/dynashuttle-backend/docs/Task7_7_Production_Dispatch_State_Model_Design.md).

It is strictly a **schema and state foundation task**. No dispatch workers, batch formation services, vehicle assignment algorithms, route optimization logic, or WebSocket delivery mechanisms were implemented in this task.

---

## 2. Existing Schema & Migration State Discovered

Prior to this task, the database migration directory contained six Flyway migrations:

| Version | Migration Script | Purpose |
|---|---|---|
| `V1` | `V1__initial_schema.sql` | 7 core tables: `companies`, `employees`, `drivers`, `vehicles`, `trips`, `ride_requests`, `admins`, plus PostGIS extension |
| `V2` | `V2__indexes_and_constraints.sql` | Foreign key indexes, composite indexes, spatial GiST index, domain CHECK constraints |
| `V3` | `V3__ride_lifecycle_state_machine.sql` | Canonical 8-state ride request lifecycle constraint normalising legacy QUEUED/PICKED_UP |
| `V4` | `V4__pickup_zones.sql` | Pickup zone management and foreign keys |
| `V5` | `V5__shuttle_schedules.sql` | Recurring schedule templates |
| `V6` | `V6__schedule_trip_generation.sql` | Schedule trip generation, `(schedule_id, trip_date)` uniqueness, nullable `vehicle_id`/`driver_id` on `trips` |

The development database (`dynashuttle`) was baselined at `V1` and contained migrations up to `V3`, while test databases (`dynashuttle_migration_test` and `dynashuttle_test`) had migrations up to `V6`.

---

## 3. Migration Version Used

- **Migration file:** [`src/main/resources/db/migration/V7__dispatch_and_state_foundation.sql`](file:///c:/project-shuttles-2026/dynashuttle-backend/src/main/resources/db/migration/V7__dispatch_and_state_foundation.sql)
- **Version:** `V7`
- **Description:** `dispatch and state foundation`
- **Application:** Successfully executed on clean databases and migrated forward on existing databases.

---

## 4. New Tables & Structures

Four new tables were created, adhering strictly to the Task 7.7 design specification and DynaShuttle conventions:

### 4.1 `dispatch_batches`
Durable representation of dynamic batch formation windows, locking, and assignment lifecycle.

```sql
CREATE TABLE IF NOT EXISTS dispatch_batches (
    id                  UUID PRIMARY KEY,
    company_id          UUID NOT NULL REFERENCES companies(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    schedule_id         UUID REFERENCES shuttle_schedules(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    trip_id             UUID REFERENCES trips(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    status              VARCHAR(20) DEFAULT 'OPEN' NOT NULL,
    direction           VARCHAR(10) NOT NULL,
    window_start_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    window_closes_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    dispatched_at       TIMESTAMP WITHOUT TIME ZONE,
    max_capacity        INTEGER NOT NULL,
    assigned_vehicle_id UUID REFERENCES vehicles(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    assigned_driver_id  UUID REFERENCES drivers(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    worker_id           VARCHAR(255),
    locked_at           TIMESTAMP WITHOUT TIME ZONE,
    retry_count         INTEGER DEFAULT 0 NOT NULL,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL
);
```

### 4.2 `trip_stops`
Ordered pickup and dropoff stop sequence persistence supporting multi-passenger routing and closing the destination gap identified in Task 7.3.

```sql
CREATE TABLE IF NOT EXISTS trip_stops (
    id                  UUID PRIMARY KEY,
    trip_id             UUID NOT NULL REFERENCES trips(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    batch_id            UUID REFERENCES dispatch_batches(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    ride_request_id     UUID NOT NULL REFERENCES ride_requests(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    company_id          UUID NOT NULL REFERENCES companies(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    stop_type           VARCHAR(10) NOT NULL,
    sequence_order      INTEGER NOT NULL,
    latitude            DOUBLE PRECISION NOT NULL,
    longitude           DOUBLE PRECISION NOT NULL,
    status              VARCHAR(20) DEFAULT 'PLANNED' NOT NULL,
    planned_eta         TIMESTAMP WITHOUT TIME ZONE,
    actual_arrival      TIMESTAMP WITHOUT TIME ZONE,
    actual_departure    TIMESTAMP WITHOUT TIME ZONE,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL
);
```

### 4.3 `vehicle_operational_state`
Decouples transient operational dispatch state (availability, assignments, active batches) from static vehicle attributes.

```sql
CREATE TABLE IF NOT EXISTS vehicle_operational_state (
    vehicle_id          UUID PRIMARY KEY REFERENCES vehicles(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    company_id          UUID NOT NULL REFERENCES companies(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    status              VARCHAR(20) DEFAULT 'AVAILABLE' NOT NULL,
    active_trip_id      UUID REFERENCES trips(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    active_batch_id     UUID REFERENCES dispatch_batches(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    available_at        TIMESTAMP WITHOUT TIME ZONE,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL
);
```

Existing vehicles in `vehicles` are automatically backfilled upon migration into `vehicle_operational_state` with status `'AVAILABLE'`.

### 4.4 `outbox_events`
Transactional outbox persistence foundation for at-least-once real-time event delivery across transaction boundaries.

```sql
CREATE TABLE IF NOT EXISTS outbox_events (
    id              UUID PRIMARY KEY,
    company_id      UUID REFERENCES companies(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    event_type      VARCHAR(50) NOT NULL,
    aggregate_type  VARCHAR(50),
    aggregate_id    UUID,
    payload         JSONB NOT NULL,
    target_type     VARCHAR(20),
    target_id       UUID,
    trip_id         UUID REFERENCES trips(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    published       BOOLEAN DEFAULT false NOT NULL,
    retry_count     INTEGER DEFAULT 0 NOT NULL,
    last_error      TEXT,
    created_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    published_at    TIMESTAMP WITHOUT TIME ZONE
);
```

---

## 5. New Columns on Existing Tables

### Alterations to `ride_requests`:
- `batch_id UUID REFERENCES dispatch_batches(id) ON UPDATE RESTRICT ON DELETE RESTRICT` (nullable) — connects ride requests to their owning batch window.
- `queued_at TIMESTAMP WITHOUT TIME ZONE` (nullable) — timestamp when the request was placed inside a collecting/open batch window.
- `expires_at TIMESTAMP WITHOUT TIME ZONE` (nullable) — absolute timeout timestamp for request expiration.

---

## 6. New States, Enums & Check Constraints

### 6.1 `dispatch_batches`
- `chk_dispatch_batches_status`: `status IN ('OPEN', 'LOCKED', 'DISPATCHED', 'CLOSED', 'COLLECTING', 'READY', 'ASSIGNING', 'OPTIMIZING', 'EXECUTING', 'COMPLETED', 'CANCELLED')`
- `chk_dispatch_batches_direction`: `direction IN ('INBOUND', 'OUTBOUND')`
- `chk_dispatch_batches_max_capacity`: `max_capacity > 0`
- `chk_dispatch_batches_retry_count`: `retry_count >= 0`
- `chk_dispatch_batches_window`: `window_closes_at >= window_start_at`

### 6.2 `trip_stops`
- `chk_trip_stops_stop_type`: `stop_type IN ('PICKUP', 'DROPOFF')`
- `chk_trip_stops_sequence_order`: `sequence_order > 0`
- `chk_trip_stops_latitude`: `latitude >= -90.0 AND latitude <= 90.0`
- `chk_trip_stops_longitude`: `longitude >= -180.0 AND longitude <= 180.0`
- `chk_trip_stops_status`: `status IN ('PLANNED', 'EN_ROUTE', 'ARRIVED', 'COMPLETED', 'SKIPPED', 'CANCELLED')`

### 6.3 `vehicle_operational_state`
- `chk_vehicle_operational_state_status`: `status IN ('OFFLINE', 'AVAILABLE', 'IDLE', 'ASSIGNED', 'EN_ROUTE_PICKUP', 'BOARDING', 'EN_ROUTE_DROPOFF', 'IN_TRIP', 'OUT_OF_SERVICE')`

### 6.4 `outbox_events`
- `chk_outbox_events_event_type`: `length(trim(event_type)) > 0`
- `chk_outbox_events_retry_count`: `retry_count >= 0`

### 6.5 `ride_requests`
- `chk_ride_requests_status`: extended to include `'QUEUED'` and `'EXPIRED'`:
  `status IN ('REQUESTED', 'QUEUED', 'MATCHED', 'WAITLISTED', 'PICKUP_PENDING', 'ONBOARD', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'EXPIRED')`

---

## 7. Foreign Keys

All foreign keys use `ON UPDATE RESTRICT ON DELETE RESTRICT` to ensure referential integrity and prevent cascading deletions:

| Table | Column | References |
|---|---|---|
| `dispatch_batches` | `company_id` | `companies(id)` |
| `dispatch_batches` | `schedule_id` | `shuttle_schedules(id)` |
| `dispatch_batches` | `trip_id` | `trips(id)` |
| `dispatch_batches` | `assigned_vehicle_id` | `vehicles(id)` |
| `dispatch_batches` | `assigned_driver_id` | `drivers(id)` |
| `trip_stops` | `trip_id` | `trips(id)` |
| `trip_stops` | `batch_id` | `dispatch_batches(id)` |
| `trip_stops` | `ride_request_id` | `ride_requests(id)` |
| `trip_stops` | `company_id` | `companies(id)` |
| `vehicle_operational_state` | `vehicle_id` | `vehicles(id)` |
| `vehicle_operational_state` | `company_id` | `companies(id)` |
| `vehicle_operational_state` | `active_trip_id` | `trips(id)` |
| `vehicle_operational_state` | `active_batch_id` | `dispatch_batches(id)` |
| `outbox_events` | `company_id` | `companies(id)` |
| `outbox_events` | `trip_id` | `trips(id)` |
| `ride_requests` | `batch_id` | `dispatch_batches(id)` |

---

## 8. Unique Constraints

1. `uq_trip_stops_trip_sequence` on `trip_stops(trip_id, sequence_order)` — guarantees that no trip can contain duplicate stop sequence numbers.
2. `uq_trip_stops_trip_request_type` on `trip_stops(trip_id, ride_request_id, stop_type)` — guarantees that a ride request cannot have duplicate stops of the same type (PICKUP or DROPOFF) within a single trip.

---

## 9. Performance & Query Indexes

19 indexes were established across the new tables and modified tables:

### `dispatch_batches`
- `idx_dispatch_batches_company_id` on `(company_id)`
- `idx_dispatch_batches_company_status_window` on `(company_id, status, window_closes_at)`
- `idx_dispatch_batches_schedule_status` on `(schedule_id, status)`
- `idx_dispatch_batches_assigned_vehicle_id` on `(assigned_vehicle_id)`
- `idx_dispatch_batches_trip_id` on `(trip_id)`
- `idx_dispatch_batches_status` on `(status)`

### `trip_stops`
- `idx_trip_stops_trip_id` on `(trip_id)`
- `idx_trip_stops_trip_sequence` on `(trip_id, sequence_order)`
- `idx_trip_stops_ride_request_id` on `(ride_request_id)`
- `idx_trip_stops_company_id` on `(company_id)`
- `idx_trip_stops_company_status` on `(company_id, status)`
- `idx_trip_stops_batch_id` on `(batch_id)`

### `vehicle_operational_state`
- `idx_vehicle_operational_state_company_status` on `(company_id, status)`
- `idx_vehicle_operational_state_active_trip_id` on `(active_trip_id)`
- `idx_vehicle_operational_state_active_batch_id` on `(active_batch_id)`

### `outbox_events`
- `idx_outbox_events_published_created` on `(published, created_at)`
- `idx_outbox_events_company_id` on `(company_id)`
- `idx_outbox_events_trip_id` on `(trip_id)`

### `ride_requests`
- `idx_ride_requests_batch_id` on `(batch_id)`

---

## 10. Tenant Isolation Implications

Every new entity (`dispatch_batches`, `trip_stops`, `vehicle_operational_state`, `outbox_events`) contains a direct `company_id` foreign key referencing `companies(id)`.

- Worker queries can enforce strict tenant boundaries via `WHERE company_id = ?`.
- Vehicle assignment queries filter `vehicle_operational_state` by tenant key.
- Outbox event polling and WebSocket event delivery isolate event streams by company identity.

---

## 11. Existing Data Preservation Verification

Pre-migration and post-migration row counts on development database (`dynashuttle`):

| Table | Pre-Migration Count | Post-Migration Count | Delta |
|---|---|---|---|
| `companies` | 1 | 2* | +1 (*system fallback company created by V6 backfill) |
| `employees` | 1 | 1 | 0 (preserved) |
| `drivers` | 1 | 1 | 0 (preserved) |
| `vehicles` | 1 | 1 | 0 (preserved) |
| `trips` | 1 | 1 | 0 (preserved) |
| `ride_requests` | 8 | 8 | 0 (preserved) |
| `admins` | 0 | 0 | 0 (preserved) |
| `pickup_zones` | — | 0 | New table |
| `shuttle_schedules` | — | 1 | System schedule from V6 backfill |
| `dispatch_batches` | — | 0 | New table |
| `trip_stops` | — | 0 | New table |
| `vehicle_operational_state` | — | 1 | Backfilled for existing vehicle |
| `outbox_events` | — | 0 | New table |

All existing commuter and ride records remain completely intact.

---

## 12. Fresh-Database & Existing-Database Migration Verification

1. **Clean Database Verification:** Tested via `DatabaseMigrationTest.Test 1` — a newly created database cleanly executes migrations `V1` through `V7` sequentially with target schema version `7`.
2. **Existing Database Verification:** Tested via `DatabaseMigrationTest.Test ZB` — `dynashuttle` baselined at `V1` cleanly executes remaining migrations (`V4` through `V7`), achieving target schema version `7`.
3. **Idempotency Verification:** Tested via `DatabaseMigrationTest.Test 6` — re-running migrations against an up-to-date schema results in 0 executed migrations and exit code 0.
4. **PostGIS Health:** PostGIS 3.6 (`USE_GEOS=1 USE_PROJ=1 USE_STATS=1`) verified fully functional.

---

## 13. Test Results

- **Tests Before:** 320 passed, 0 failed, 0 skipped
- **Tests After:** 323 passed, 0 failed, 0 skipped
- **New Tests Added:**
  - `Test ZA - V7 migration executes cleanly and leaves schema at version 7`
  - `Test V7 - V7 schema constraints, dispatch batches, trip stops, vehicle state, outbox`
  - `Test ZB - dynashuttle development database migrates cleanly to V7`
- **Gradle Build:** `BUILD SUCCESSFUL`

---

## 14. Explicit Non-Goals

The following components were **NOT implemented** in Task 8.1, as per strict instructions:
- No `DispatchWorker` background coroutine loop.
- No dynamic batching algorithm or schedule window polling.
- No vehicle assignment algorithm or spatial distance sorting.
- No routing optimizer adapter or stop sequence generator.
- No WebSocket event broadcaster or outbox publisher.
- No security remediation, OTP changes, or BOLA route changes.
- No experiment infrastructure modifications.

---

## 15. Known Follow-Up Work for Task 8.2

The next logical task in the implementation sequence:
- **TASK 8.2 — Ride Request State Machine Extension:**
  - Update `RideStatus` enum in Kotlin with `QUEUED` and `EXPIRED`.
  - Extend `RideRequestStateMachine` transition rules to support `REQUESTED → QUEUED`, `QUEUED → MATCHED`, `QUEUED → WAITLISTED`, `WAITLISTED → QUEUED`, and `QUEUED → EXPIRED`.
  - Unit tests verifying the extended state machine transitions and invalid transition rejections.
