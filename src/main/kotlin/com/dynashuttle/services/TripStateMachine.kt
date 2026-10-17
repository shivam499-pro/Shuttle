package com.dynashuttle.services

import com.dynashuttle.models.TripStatus

/**
 * State machine for Trip lifecycle transitions.
 *
 * Design reference: Task 7.7 Section 6.1 & 6.2.
 *
 * Canonical Lifecycle:
 *   SCHEDULED   -> ASSIGNED    (dispatch worker assigns vehicle + driver)
 *   ASSIGNED    -> BOARDING    (first pickup stop reached / driver begins boarding)
 *   BOARDING    -> IN_PROGRESS (first passenger boards - ONBOARD)
 *   IN_PROGRESS -> COMPLETED   (all passengers dropped off / terminal, no ONBOARD remaining)
 *
 * Terminal states:
 *   COMPLETED, CANCELLED, ABORTED
 *
 * Abort/Cancel transitions:
 *   Any non-terminal state -> CANCELLED (admin or operational cancellation)
 *   Any non-terminal state -> ABORTED   (system detection, driver disconnect/failure)
 */
object TripStateMachine {
    private val allowedTransitions: Map<TripStatus, Set<TripStatus>> = mapOf(
        TripStatus.SCHEDULED to setOf(
            TripStatus.ASSIGNED,
            TripStatus.CANCELLED,
            TripStatus.ABORTED
        ),
        TripStatus.ASSIGNED to setOf(
            TripStatus.BOARDING,
            TripStatus.CANCELLED,
            TripStatus.ABORTED
        ),
        TripStatus.BOARDING to setOf(
            TripStatus.IN_PROGRESS,
            TripStatus.CANCELLED,
            TripStatus.ABORTED
        ),
        TripStatus.IN_PROGRESS to setOf(
            TripStatus.COMPLETED,
            TripStatus.CANCELLED,
            TripStatus.ABORTED
        ),
        TripStatus.COMPLETED to emptySet(),
        TripStatus.CANCELLED to emptySet(),
        TripStatus.ABORTED   to emptySet()
    )

    fun canTransition(from: TripStatus, to: TripStatus): Boolean =
        allowedTransitions[from]?.contains(to) == true

    fun validateTransition(from: TripStatus, to: TripStatus) {
        if (!canTransition(from, to)) {
            throw InvalidTripTransitionException(from, to)
        }
    }

    fun transition(from: TripStatus, to: TripStatus): TripStatus {
        validateTransition(from, to)
        return to
    }
}

class InvalidTripTransitionException(
    val from: TripStatus,
    val to: TripStatus
) : IllegalStateException("Invalid trip transition: ${from.name} -> ${to.name}")
