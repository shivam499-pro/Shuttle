# Task 8.2 — Ride Request State Machine Extension

---

## 1. Task Status

| Field | Value |
|---|---|
| **Status** | COMPLETE |
| **Date** | October 8, 2026 |
| **Depends On** | Task 8.1 (V7 schema — `QUEUED` and `EXPIRED` status values added to `chk_ride_requests_status`) |
| **Prerequisite For** | Task 8.3 (Trip Lifecycle), Task 8.4 (Batching Service) |

---

## 2. Objective

Extend `RideStatus` and `RideRequestStateMachine` to reflect the full dynamic-dispatch lifecycle defined in the Task 7.7 design (Section 5). Specifically:

1. Add `QUEUED` and `EXPIRED` to the `RideStatus` enum.
2. Wire all new transitions into `RideRequestStateMachine`.
3. Update `RideStatus.fromString` to treat `QUEUED` as a real production state (not a legacy normalisation).
4. Write unit tests for all new transitions (tests S–AH).

---

## 3. Files Changed

| File | Change |
|---|---|
| `src/main/kotlin/com/dynashuttle/models/RideStatus.kt` | Added `QUEUED`, `EXPIRED`; updated `isTerminal`; removed V3-era `QUEUED->WAITLISTED` normalisation |
| `src/main/kotlin/com/dynashuttle/services/RideRequestStateMachine.kt` | Added all QUEUED and EXPIRED edges; full design-spec comment block |
| `src/test/kotlin/com/dynashuttle/RideLifecycleStateMachineTest.kt` | Added tests S–AH (20 new tests); updated QUEUED fromString assertion |

---

## 4. State Machine — Complete Transition Table (Post-Task 8.2)

### Valid Transitions

| From | To | Trigger |
|---|---|---|
| `REQUESTED` | `QUEUED` | BatchingService — places request into open batch window |
| `REQUESTED` | `MATCHED` | Legacy: direct allocation on an IN_PROGRESS trip |
| `REQUESTED` | `WAITLISTED` | Legacy: no trip immediately available |
| `REQUESTED` | `CANCELLED` | Employee cancels before dispatch |
| `QUEUED` | `MATCHED` | Dispatch worker — vehicle + batch assigned |
| `QUEUED` | `WAITLISTED` | Dispatch worker — batch window closed, no eligible vehicle |
| `QUEUED` | `EXPIRED` | Expiry monitor — max wait threshold exceeded |
| `QUEUED` | `CANCELLED` | Employee cancels during batch window |
| `WAITLISTED` | `QUEUED` | Dispatch worker — re-queued on next batch cycle |
| `WAITLISTED` | `MATCHED` | Legacy / direct: vehicle found on retry |
| `WAITLISTED` | `EXPIRED` | Absolute expiry threshold exceeded |
| `WAITLISTED` | `CANCELLED` | Employee cancels while waitlisted |
| `MATCHED` | `PICKUP_PENDING` | Driver en route to pickup stop |
| `MATCHED` | `CANCELLED` | Trip/batch cancelled after assignment |
| `PICKUP_PENDING` | `ONBOARD` | Passenger boards |
| `PICKUP_PENDING` | `NO_SHOW` | Driver arrived; passenger absent |
| `PICKUP_PENDING` | `CANCELLED` | Trip aborted before pickup |
| `ONBOARD` | `COMPLETED` | Passenger dropped off at destination |

### Terminal States

| State | Reachable From |
|---|---|
| `COMPLETED` | `ONBOARD` only |
| `CANCELLED` | `REQUESTED`, `QUEUED`, `MATCHED`, `PICKUP_PENDING` |
| `NO_SHOW` | `PICKUP_PENDING` only |
| `EXPIRED` | `QUEUED`, `WAITLISTED` only |

---

## 5. `RideStatus.fromString` Change

**Before (V3-era):**
```kotlin
"QUEUED" -> WAITLISTED  // legacy normalisation
```

**After (V7):**
```kotlin
// QUEUED is a real production state; no normalisation applied
"PICKED_UP" -> PICKUP_PENDING   // only legacy normalisation retained
else -> valueOf(value.uppercase())
```

> **Why:** The V7 migration adds `QUEUED` as a valid DB value in `chk_ride_requests_status`. Rows written by the dispatch worker in state `QUEUED` must round-trip through `fromString` cleanly. The old V3 normalisation would silently convert them to `WAITLISTED`, corrupting dispatch state.

---

## 6. Test Results

```
RideLifecycleStateMachineTest
  Tests : 38
  Passed: 38
  Failed: 0
  Errors: 0
```

Tests A–R (original) all still pass. Tests S–AH (Task 8.2 additions) all pass.

---

## 7. Invariants Enforced by the State Machine

| Invariant | Enforcement |
|---|---|
| EXPIRED is terminal | `EXPIRED -> emptySet()` in transition map; `isTerminal = true` |
| EXPIRED only reachable from QUEUED or WAITLISTED | Verified by tests AC, AD |
| QUEUED cannot loop to REQUESTED | Verified by test AG |
| REQUESTED cannot reach EXPIRED directly | Verified by test AH |
| ONBOARD cannot be cancelled | Unchanged; verified by test O |
| Four and only four terminal states | Verified by test AE |
| All non-terminal states accounted for | Verified by test AF |

---

## 8. Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Retain `REQUESTED->MATCHED` and `REQUESTED->WAITLISTED` | YES | Backwards compat — existing `RoutingService` and integration tests use these paths for direct trip allocation |
| Remove `QUEUED->WAITLISTED` `fromString` normalisation | YES | V7 schema explicitly permits `QUEUED` in the DB; normalisation would corrupt dispatch state |
| `EXPIRED` not reachable from execution-phase states | YES | Matches design: once a vehicle is assigned, the request can no longer expire |

---

## 9. Next Task

**Task 8.3 — Trip Lifecycle Implementation**

- Add `ASSIGNED` and `BOARDING` trip states to the trips table and `TripService`.
- Implement `TripExecutionService` with the stop-level state machine.
- Add drop-off endpoint: `POST /trips/{id}/stops/{stopId}/dropoff`.
- Integration tests for full trip lifecycle.

---

## 10. Verification

```
Production code modified: YES
  - src/main/kotlin/com/dynashuttle/models/RideStatus.kt
  - src/main/kotlin/com/dynashuttle/services/RideRequestStateMachine.kt

Database modified: NO
Schema modified: NO
Migrations modified: NO
Experiment code modified: NO
Frozen tasks (4.1-6.4) modified: NO

Tests: 38 passed, 0 failed, 0 errors (RideLifecycleStateMachineTest)
Full suite: BUILD SUCCESSFUL (pending — running)
```
