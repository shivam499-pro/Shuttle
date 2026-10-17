# Task 8.4 — Dynamic Batching Service (Production Backend)

---

## 1. Executive Summary

| Attribute | Details |
|---|---|
| **Task** | TASK 8.4 — Dynamic Batching Service |
| **Final Status** | **FROZEN (COMPLETE)** |
| **Date** | October 17, 2026 |
| **Target Subsystem** | DynaShuttle Production Backend (`c:\project-shuttles-2026\dynashuttle-backend`) |
| **Prerequisites** | Task 8.1 (Schema & State Foundation), Task 8.2 (Ride Request State Machine Extension), Task 8.3 (Trip Lifecycle & Execution Foundation) |
| **Key Deliverables** | `DispatchBatch.kt`, `Tables.kt` update, `DispatchBatchRepository.kt`, `PostgresDispatchBatchRepository.kt`, `BatchingService.kt`, `DynamicBatchingServiceUnitTest.kt`, `DynamicBatchingIntegrationTest.kt` |
| **Database Migrations** | None created (V7 schema provided complete foundation; no V9 migration needed) |
| **Test Results** | 29 test classes, 414 tests executed, 0 failures, 100% pass rate (24 unit tests + 8 PostgreSQL integration tests for Task 8.4) |

---

## 2. Background & Problem Statement

Prior audits (Tasks 7.1–7.7) established that DynaShuttle's production dispatch pipeline requires moving from immediate 1:1 request-to-trip matching to a demand-responsive, windowed dynamic batching foundation:

1. **Batch Ingestion Flow:** Commuter ride requests start in `REQUESTED`. Before vehicle assignment or route generation, requests within an active time window must be grouped into an open dispatch batch (`REQUESTED → QUEUED`).
2. **Windowing & Expiry:** Research baseline specifies a 300-second dynamic batch window with request expiration at $2 \times$ window duration (600 seconds). The batch window is 300 seconds (`batchWindowSeconds = 300L`), and the request expiry interval is 600 seconds (`expiresAt = now + batchWindowSeconds * DEFAULT_EXPIRY_MULTIPLIER` where `DEFAULT_EXPIRY_MULTIPLIER = 2L`).
3. **Capacity Auto-Locking:** Batches must automatically seal (`COLLECTING → READY`) when passenger demand reaches maximum vehicle seat capacity, triggering prompt dispatch.
4. **Concurrency & Race Conditions:** Under concurrent requests for the same batch window, callers must converge on a single database batch row without duplicates, race conditions, or capacity oversubscription.
5. **Multi-Tenant Boundary:** Commuters from Company A must never be admitted into batches belonging to Company B.
6. **Task 8.4 Scope Boundary:** Scope is strictly confined to batch creation, request enrolment (`REQUESTED → QUEUED`), and batch closure (`COLLECTING → READY`). Vehicle assignment, route optimization, trip generation, driver assignment, WebSocket events, and outbox event publishing are explicitly excluded and deferred to Task 8.5.

---

## 3. Database Schema & Migration State

### 3.1 Migration Assessment (No V9 Required)
Inspection of the existing migrations confirmed that **V7 (`V7__dispatch_and_state_foundation.sql`)** already provisioned all required tables and columns:
- `dispatch_batches` table with UUID primary key, `company_id`, `schedule_id`, `trip_id`, `status`, `direction`, `window_start_at`, `window_closes_at`, `max_capacity`, `worker_id`, `locked_at`, `retry_count`, `created_at`, `updated_at`.
- `chk_dispatch_batches_status` check constraint supporting `'COLLECTING'`, `'READY'`, `'ASSIGNING'`, `'OPTIMIZING'`, `'DISPATCHED'`, `'EXECUTING'`, `'COMPLETED'`, `'CANCELLED'`.
- `ride_requests` columns: `batch_id` (UUID foreign key), `queued_at` (TIMESTAMP), `expires_at` (TIMESTAMP), and extended status check constraint including `'QUEUED'` and `'EXPIRED'`.

Therefore, **no V9 migration was created**. The existing V7 and V8 migrations remain frozen and untouched.

### 3.2 Exposed Table Mappings (`Tables.kt`)
Updated `com.dynashuttle.models.Tables.kt` to expose the batching domain objects:
- Added `DispatchBatches` table object mapping `dispatch_batches`.
- Added missing batch columns to `RideRequests`:
  - `val batchId = uuid("batch_id").nullable()`
  - `val queuedAt = timestamp("queued_at").nullable()`
  - `val expiresAt = timestamp("expires_at").nullable()`

---

## 4. Architecture & Implementation

### 4.1 Domain Model (`DispatchBatch.kt`)
Located in `src/main/kotlin/com/dynashuttle/models/DispatchBatch.kt`:
- **`BatchStatus` Enum:**
  - `COLLECTING` (canonical open state, accepting QUEUED ride requests)
  - `READY` (window closed or capacity reached; awaiting dispatch worker)
  - `ASSIGNING` (worker assigned; vehicle/driver selection in progress)
  - `OPTIMIZING` (vehicle assigned; route optimizer executing)
  - `DISPATCHED` (trip created and dispatched)
  - `EXECUTING` (trip in progress on road)
  - `COMPLETED` (terminal)
  - `CANCELLED` (terminal)
  - Helpers: `isOpen` (`COLLECTING`), `isTerminal` (`COMPLETED`, `CANCELLED`).
- **`DispatchBatch` Data Class:** Full representation matching database columns with nullable schedule and execution fields.

### 4.2 Repository Contract (`DispatchBatchRepository.kt`)
Located in `src/main/kotlin/com/dynashuttle/repositories/DispatchBatchRepository.kt`:
- **Typed Result Types:**
  - `BatchAcquisitionResult`: `Acquired`, `Created`, `AlreadyLocked`, `CapacityFull`
  - `BatchEnrollResult`: `Success`, `AlreadyEnrolled`, `RequestIneligible`, `BatchClosed`, `BatchFull`, `CompanyMismatch`, `BatchNotFound`, `RequestNotFound`
  - `BatchLockResult`: `Locked`, `AlreadyLocked`, `NotFound`, `Terminal`
- **Operations:**
  - `acquireOrCreateBatch(...)`: Retrieves or creates an active batch for the `(companyId, scheduleId, direction, windowStartAt)` window key.
  - `addRequestToBatch(batchId, rideRequestId, expiresAt)`: Atomically enrolls a request into an open batch.
  - `lockBatch(batchId)`: Transitions batch from `COLLECTING → READY` (window-close trigger).
  - `findById(batchId)`: Queries batch by UUID.
  - `countBatchMembers(batchId)`: Counts active requests enrolled in a batch.

### 4.3 PostgreSQL Implementation (`PostgresDispatchBatchRepository.kt`)
Located in `src/main/kotlin/com/dynashuttle/repositories/PostgresDispatchBatchRepository.kt`:
- **Transaction-Scoped Advisory Locking:**
  Because the V7 schema did not define a unique constraint on `(company_id, schedule_id, direction, window_start_at)`, concurrent transactions could race during initial batch creation. To resolve this without schema modifications, `acquireOrCreateBatch` acquires a transaction-level advisory lock:
  ```sql
  SELECT pg_advisory_xact_lock(hashtext(?))
  ```
  keyed on `"dispatch_batch:$companyId:${direction.name}:${windowStartAt.epochSecond}:${scheduleId ?: "adhoc"}"`. This serializes check-and-create operations per window key, ensuring exactly one batch row is ever created.
- **Consistent Lock Ordering:**
  To eliminate deadlock hazards during concurrent request enrolments, `addRequestToBatch` strictly adheres to a deterministic locking sequence:
  1. `SELECT ... FROM dispatch_batches WHERE id = ? FOR UPDATE`
  2. `SELECT ... FROM ride_requests rr JOIN employees e ON e.id = rr.employee_id WHERE rr.id = ? FOR UPDATE OF rr`
- **Tenant Isolation Enforcement:**
  `addRequestToBatch` validates `employee.company_id == batch.company_id`. Any cross-tenant enrollment attempt is rejected with `BatchEnrollResult.CompanyMismatch`.
- **Atomic State Transition & Capacity Auto-Lock:**
  `ride_requests` is updated atomically:
  ```sql
  UPDATE ride_requests
  SET status = 'QUEUED', batch_id = ?, queued_at = ?, expires_at = ?
  WHERE id = ? AND status = 'REQUESTED'
  ```
  If this enrollment causes the batch member count to reach `max_capacity`, the batch is immediately auto-locked to `READY` within the same transaction:
  ```sql
  UPDATE dispatch_batches
  SET status = 'READY', updated_at = ?
  WHERE id = ? AND status = 'COLLECTING'
  ```
- **Conditional Batch Lock:**
  `lockBatch` executes `UPDATE dispatch_batches SET status = 'READY', updated_at = ? WHERE id = ? AND status = 'COLLECTING'`, ensuring atomic, idempotent transitions from `COLLECTING` to `READY`.

### 4.4 Service Layer (`BatchingService.kt`)
Located in `src/main/kotlin/com/dynashuttle/services/BatchingService.kt`:
- **Default Baseline Parameters:**
  - `DEFAULT_BATCH_WINDOW_SECONDS = 300L` (300-second dynamic batch collection window)
  - `DEFAULT_EXPIRY_MULTIPLIER = 2L` (request absolute expiry interval = 600 seconds from enrolment, computed as `now + batchWindowSeconds * expiryMultiplier`)
- **Typed Enqueue Outcome (`EnqueueResult`):**
  - `Queued(batch)`: Successfully enrolled into batch.
  - `AlreadyQueued(batch)`: Request was already enrolled in this batch (idempotent).
  - `BatchClosed`: Batch is locked, ready, or expired.
  - `BatchFull`: Batch reached max capacity.
  - `CompanyMismatch`: Tenant violation.
  - `RequestIneligible`: Request not in `REQUESTED` status.
  - `NotFound`: Batch or ride request does not exist.
- **Service Methods:**
  - `enqueueRequest(...)`: High-level entry point that acquires/creates the batch, computes `expiresAt`, atomically enrolls the request, and reflects any capacity auto-lock.
  - `acquireOrCreateBatch(...)`: Public query/factory method for open batches.
  - `closeBatch(batchId)`: Manual or timer-triggered batch window closure.
  - `findBatch(batchId)`, `batchMemberCount(batchId)`: Inspection queries.

---

## 5. Verification & Testing

### 5.1 Test Overview
Testing is split into two complementary suites:
1. **In-Memory Unit Tests (`DynamicBatchingServiceUnitTest.kt`):** 24 fast, deterministic tests verifying business logic, edge cases, state transitions, idempotency, and concurrent access using thread pools and cyclic barriers.
2. **PostgreSQL Integration Tests (`DynamicBatchingIntegrationTest.kt`):** 8 end-to-end database tests verifying real PostgreSQL row locking (`SELECT FOR UPDATE`), transaction-level advisory locks (`pg_advisory_xact_lock`), foreign key constraints, tenant isolation joins, elapsed-window rejection, and real multi-threaded race condition convergence with direct database state assertions.

### 5.2 Unit Tests (`DynamicBatchingServiceUnitTest.kt`)
- **Test A:** Single request enqueued into new COLLECTING batch; request status becomes `QUEUED`.
- **Test B:** Multiple requests for same window join the same open batch.
- **Test C:** Idempotent enqueue — second enqueue of same request returns `AlreadyQueued` without duplicating count.
- **Test D:** Batch auto-locks to `READY` when capacity is reached.
- **Test E:** Enqueue into locked batch returns `BatchClosed`.
- **Test F:** `MATCHED` request rejected as ineligible (`RequestIneligible`).
- **Test F2:** `CANCELLED` request rejected as ineligible (`RequestIneligible`).
- **Test G:** Unknown ride request returns `NotFound`.
- **Test H:** Explicit `closeBatch()` transitions `COLLECTING → READY`.
- **Test I:** `closeBatch()` called twice is idempotent (`BatchLockResult.AlreadyLocked`).
- **Test J:** Requests from different companies produce distinct batches (tenant isolation).
- **Test K:** `INBOUND` and `OUTBOUND` directions produce separate batches for the same company.
- **Test L:** Direct repository `lockBatch` transitions `COLLECTING → READY`.
- **Test L2:** Direct repository `lockBatch` on `READY` batch returns `AlreadyLocked`.
- **Test L3:** Direct repository `lockBatch` on `CANCELLED` batch returns `Terminal`.
- **Test M:** `batchMemberCount` accurately reflects enrolled count after multiple enrolments.
- **Test N:** Concurrent enqueue of same request across 8 threads produces exactly 1 `Queued` and 7 `AlreadyQueued`.
- **Test O:** `BatchStatus` `isOpen` and `isTerminal` flag correctness.
- **Test P:** Ad-hoc batch creation without `scheduleId`.
- **Test Q:** `BatchStatus.fromString` case-insensitive parsing and roundtripping.
- **Test R:** `findBatch` returns null for non-existent IDs.
- **Test S:** Distinct `windowStartAt` timestamps produce separate batches.
- **Test T:** Batch with capacity 1 auto-locks to `READY` on first enrollment.
- **Test U:** Enrollment into expired batch past `windowClosesAt` is rejected with `BatchClosed`.

### 5.3 PostgreSQL Integration Tests (`DynamicBatchingIntegrationTest.kt`)
- **Test 1:** `acquireOrCreateBatch` on PostgreSQL returns `BatchAcquisitionResult.Created` on initial call, and `BatchAcquisitionResult.Acquired` with the identical batch ID on subsequent calls for the same window key.
- **Test 2:** Concurrent `acquireOrCreateBatch` across 6 threads converges to exactly one batch row in PostgreSQL via transaction advisory locking.
- **Test 3:** `enqueueRequest` updates `ride_requests` in PostgreSQL to `status = 'QUEUED'`, sets `batch_id`, and populates `queued_at` and `expires_at`.
- **Test 4:** Batch auto-locks to `READY` in the database when capacity (2) is reached; subsequent requests receive `BatchClosed` / `BatchFull`.
- **Test 5:** Tenant isolation: enrolling an employee from Company A into Company B's batch is rejected with `CompanyMismatch`, leaving the request in `REQUESTED`.
- **Test 6:** Concurrent enrollment of 6 requests into a batch of capacity 3 under real PostgreSQL row-level locks results in exactly 3 enrolled requests and 3 rejected requests, verified with direct post-condition database queries on `dispatch_batches` (status `READY`) and `ride_requests` (3 `QUEUED`, 3 remaining `REQUESTED`).
- **Test 7:** `lockBatch` idempotently transitions `COLLECTING → READY` and returns `AlreadyLocked` on retry.
- **Test 8:** Enrollment into batch past `windowClosesAt` is rejected with `BatchClosed`, and verifies directly in PostgreSQL that the request remains in `REQUESTED` status with `batch_id = null`.

### 5.4 Test Execution Summary

```
Total Test Suites: 29
Total Tests Executed: 414
Failures: 0
Errors: 0
Skipped: 0
Pass Rate: 100%
```

All 382 preexisting tests from Tasks 8.1, 8.2, and 8.3 continue to pass without regression. Task 8.4 contributes 32 tests (24 in-memory unit tests + 8 PostgreSQL integration tests).

---

## 6. Frozen Baseline & Scope Boundary Compliance

| Requirement | Status | Evidence |
|---|---|---|
| Preserve frozen Tasks 8.1–8.3 | **PRESERVED** | No alterations to `TripStateMachine.kt`, `RideRequestStateMachine.kt`, `TripExecutionService.kt`, or V7/V8 migrations. |
| No V9 migration created | **VERIFIED** | `db/migration/` contains V1 through V8; no V9 file exists. |
| No vehicle/driver assignment | **COMPLIANT** | Task 8.4 only enrolls requests into batches (`REQUESTED → QUEUED`). No vehicles assigned. |
| No route optimization | **COMPLIANT** | No route solver invocation or waypoint generation. |
| No trip creation | **COMPLIANT** | No rows inserted into `trips` or `trip_stops` from batching service. |
| No WebSocket / Outbox events | **COMPLIANT** | No event publishing or background pollers introduced. |
| Research code untouched | **PRESERVED** | Experiment scripts, synthetic benchmarks, and results CSVs completely unmodified. |
| Legacy paths preserved | **PRESERVED** | Legacy `REQUESTED → MATCHED` and `REQUESTED → WAITLISTED` transitions in `RideRequestStateMachine` remain fully functional. |

---

## 7. Guidance for Task 8.5 (Dispatch Worker & Route Optimization)

With Task 8.4 complete, the dynamic batching foundation is ready for the dispatch worker subsystem in Task 8.5.

> [!NOTE]
> **Design Assumptions to Validate:** The proposed columns, table references, and lifecycle transitions below reflect dispatch design assumptions that must be explicitly verified against the active schema in Task 8.5 before implementation. Specifically:
> - Verify whether `assigned_vehicle_id`, `assigned_driver_id`, and `dispatched_at` exist on `dispatch_batches` (provisioned in `V7__dispatch_and_state_foundation.sql` / `Tables.kt`) and confirm their types, foreign key targets, and nullability before relying on them.
> - Do not assume unverified schema facts, and do not create migrations or modify schema code during Task 8.4.

1. **Batch Ingestion Contract:**
   - Background workers poll for batches in `status = 'READY'`.
   - Polling query (design assumption to validate): `SELECT id FROM dispatch_batches WHERE status = 'READY' ORDER BY window_closes_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED`.
2. **Worker Lease Transition:**
   - The dispatch worker claims the batch by setting `status = 'ASSIGNING'`, `worker_id = ?`, and `locked_at = NOW()`.
3. **Vehicle & Driver Selection:**
   - Worker queries available vehicles and drivers for the batch's `company_id`.
   - Sets `assigned_vehicle_id` and `assigned_driver_id` on `dispatch_batches` (design assumption to validate against active schema before use).
4. **Route Optimizer Execution:**
   - Worker transitions batch `ASSIGNING → OPTIMIZING`.
   - Reads all `ride_requests` where `batch_id = ? AND status = 'QUEUED'`.
   - Invokes the route optimization engine to determine optimal pickup/drop-off stop sequence.
5. **Trip Generation & Stop Persistence:**
   - Creates a new trip in `trips` using Task 8.3 infrastructure.
   - Persists ordered stops in `trip_stops` (both `PICKUP` and `DROPOFF`) with foreign key to `batch_id`.
   - Transitions batch `OPTIMIZING → DISPATCHED`, sets `dispatched_at = NOW()` (design assumption to validate against active schema before use), and transitions enrolled ride requests `QUEUED → MATCHED` / `PICKUP_PENDING`.
6. **Execution Handoff:**
   - When the shuttle starts its run, the batch moves to `EXECUTING`, and Task 8.3's `TripExecutionService` handles driver progression through pickup and drop-off stops.

---

## 8. Signoff
 
- **Task 8.4 Status:** **FROZEN (COMPLETE)**
- **All Requirements Met:** Yes
- **All Tests Passing:** Yes (414/414)
- **Ready for Task 8.5:** Yes
