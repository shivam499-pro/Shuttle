package com.dynashuttle.services

import com.dynashuttle.models.RideStatus

/**
 * Centralized state machine for ride-request lifecycle transitions.
 *
 * This is the single source of truth for what transitions are allowed.
 * No route or service should hard-code transition logic; they all call
 * this class.  Invalid transitions throw [InvalidTransitionException].
 *
 * Transition table (complete — Task 8.2 extended for dynamic dispatch):
 *
 * Ingestion path (synchronous HTTP):
 *   REQUESTED       -> QUEUED      (BatchingService places request into batch window)
 *   REQUESTED       -> CANCELLED   (employee cancels before dispatch)
 *
 * Batch window path (pre-V7 direct matching retained for backwards compatibility):
 *   REQUESTED       -> MATCHED     (legacy: direct allocation on an IN_PROGRESS trip)
 *   REQUESTED       -> WAITLISTED  (legacy: no trip available immediately)
 *
 * Dispatch worker path:
 *   QUEUED          -> MATCHED     (vehicle + batch assigned by dispatch worker)
 *   QUEUED          -> WAITLISTED  (batch window closed; no eligible vehicle found)
 *   QUEUED          -> EXPIRED     (max wait threshold exceeded — terminal)
 *   QUEUED          -> CANCELLED   (employee cancels during batch window)
 *   WAITLISTED      -> QUEUED      (re-queued by dispatch worker on next batch cycle)
 *   WAITLISTED      -> EXPIRED     (absolute expiry threshold exceeded — terminal)
 *   WAITLISTED      -> CANCELLED   (employee cancels while waitlisted)
 *   WAITLISTED      -> MATCHED     (legacy / direct: vehicle found on retry)
 *
 * Trip execution path (driver-driven):
 *   MATCHED         -> PICKUP_PENDING  (driver en route to pickup stop)
 *   MATCHED         -> CANCELLED       (trip/batch cancelled after assignment)
 *   PICKUP_PENDING  -> ONBOARD         (passenger boards)
 *   PICKUP_PENDING  -> NO_SHOW         (driver arrived; passenger absent — terminal)
 *   PICKUP_PENDING  -> CANCELLED       (trip aborted before pickup)
 *   ONBOARD         -> COMPLETED       (passenger dropped off at destination — terminal)
 *
 * Terminal states: COMPLETED, CANCELLED, NO_SHOW, EXPIRED — no further transitions.
 */
object RideRequestStateMachine {

    /**
     * Maps each state to the set of states it is allowed to transition into.
     *
     * Design reference: Task 7.7 Section 5.1 — Derived State Machine.
     * Authorization: only the service designated in Section 5.3 may trigger each
     * transition. This class enforces the graph; callers are responsible for authz.
     */
    private val allowedTransitions: Map<RideStatus, Set<RideStatus>> = mapOf(
        RideStatus.REQUESTED to setOf(
            RideStatus.QUEUED,           // normal: BatchingService places into batch window
            RideStatus.MATCHED,          // legacy: direct allocation on IN_PROGRESS trip
            RideStatus.WAITLISTED,       // legacy: no trip available
            RideStatus.CANCELLED         // employee cancels before dispatch
        ),
        RideStatus.QUEUED to setOf(
            RideStatus.MATCHED,          // dispatch worker: vehicle + batch assigned
            RideStatus.WAITLISTED,       // dispatch worker: window closed, no vehicle
            RideStatus.EXPIRED,          // dispatch worker / expiry monitor: timeout exceeded
            RideStatus.CANCELLED         // employee cancels during batch window
        ),
        RideStatus.MATCHED to setOf(
            RideStatus.PICKUP_PENDING,   // driver en route to pickup stop
            RideStatus.CANCELLED         // trip/batch cancelled after assignment
        ),
        RideStatus.WAITLISTED to setOf(
            RideStatus.QUEUED,           // dispatch worker: re-queued on next batch cycle
            RideStatus.MATCHED,          // legacy / direct: vehicle found on retry
            RideStatus.EXPIRED,          // absolute expiry threshold exceeded
            RideStatus.CANCELLED         // employee cancels while waitlisted
        ),
        RideStatus.PICKUP_PENDING to setOf(
            RideStatus.ONBOARD,          // passenger boards
            RideStatus.NO_SHOW,          // driver arrived; passenger absent
            RideStatus.CANCELLED         // trip aborted before pickup
        ),
        RideStatus.ONBOARD    to setOf(RideStatus.COMPLETED),  // passenger dropped off
        RideStatus.COMPLETED  to emptySet(),   // terminal
        RideStatus.CANCELLED  to emptySet(),   // terminal
        RideStatus.NO_SHOW    to emptySet(),   // terminal
        RideStatus.EXPIRED    to emptySet()    // terminal
    )

    /**
     * Returns true iff [to] is a valid successor of [from].
     */
    fun canTransition(from: RideStatus, to: RideStatus): Boolean =
        allowedTransitions[from]?.contains(to) == true

    /**
     * Throws [InvalidTransitionException] when the transition is not allowed.
     * Call this before any UPDATE to guarantee the state machine is consulted.
     */
    fun validateTransition(from: RideStatus, to: RideStatus) {
        if (!canTransition(from, to)) {
            throw InvalidTransitionException(from, to)
        }
    }

    /**
     * Validates and returns [to] (convenience overload for fluent call-sites).
     * Throws [InvalidTransitionException] on invalid transitions.
     */
    fun transition(from: RideStatus, to: RideStatus): RideStatus {
        validateTransition(from, to)
        return to
    }
}

/**
 * Thrown when a lifecycle transition is attempted that is not permitted by
 * [RideRequestStateMachine].  The HTTP layer maps this to 422 Unprocessable Entity.
 */
class InvalidTransitionException(
    val from: RideStatus,
    val to: RideStatus
) : IllegalStateException(
    "Invalid ride-request transition: ${from.name} -> ${to.name}"
)
