package com.dynashuttle.services

import com.dynashuttle.models.RideStatus
import com.dynashuttle.models.StopStatus
import com.dynashuttle.models.StopType
import com.dynashuttle.models.TripStatus
import com.dynashuttle.repositories.RideRequestRepository
import com.dynashuttle.repositories.TransitionResult
import com.dynashuttle.repositories.TripRepository
import com.dynashuttle.repositories.TripStopPersistenceRepository
import com.dynashuttle.repositories.VehicleTripLocationLookup

/**
 * Service orchestrating production Trip lifecycle and persisted stop execution.
 *
 * Implements:
 * - Trip state machine: SCHEDULED -> ASSIGNED -> BOARDING -> IN_PROGRESS -> COMPLETED
 * - Stop execution for PICKUP, NO_SHOW, and DROPOFF
 * - Multi-tenant isolation and driver ownership verification
 * - Guard: Drop-off cannot operate on pickup stops or non-ONBOARD passengers
 * - Guard: Trip completion only when no executable stops and no ONBOARD passengers remain
 */
class TripExecutionService(
    private val tripRepository: TripRepository,
    private val tripStopRepository: TripStopPersistenceRepository,
    private val rideRequestRepository: RideRequestRepository,
    private val vehicleRepository: VehicleTripLocationLookup? = null
) {

    sealed class ExecutionResult {
        object Success : ExecutionResult()
        object NotFound : ExecutionResult()
        object InvalidTransition : ExecutionResult()
        object Forbidden : ExecutionResult()
    }

    /**
     * Transitions a trip from SCHEDULED to ASSIGNED and records vehicle and driver.
     */
    fun assignTrip(
        tripId: String,
        vehicleId: String,
        driverId: String,
        callerCompanyId: String? = null
    ): ExecutionResult {
        val trip = tripRepository.findById(tripId) ?: return ExecutionResult.NotFound

        val companyId = tripRepository.findCompanyId(tripId)
        if (callerCompanyId != null && companyId != null && companyId != callerCompanyId) {
            return ExecutionResult.NotFound
        }

        if (!TripStateMachine.canTransition(trip.tripStatus, TripStatus.ASSIGNED)) {
            return ExecutionResult.InvalidTransition
        }

        val result = tripRepository.assignVehicleAndDriver(tripId, vehicleId, driverId)
        return when (result) {
            TransitionResult.Success -> ExecutionResult.Success
            TransitionResult.NotFound -> ExecutionResult.NotFound
            TransitionResult.StateMismatch -> ExecutionResult.InvalidTransition
        }
    }

    /**
     * Transitions an ASSIGNED trip to BOARDING when driver begins boarding.
     */
    fun startBoarding(
        tripId: String,
        driverId: String? = null,
        callerCompanyId: String? = null
    ): ExecutionResult {
        val trip = tripRepository.findById(tripId) ?: return ExecutionResult.NotFound

        val companyId = tripRepository.findCompanyId(tripId)
        if (callerCompanyId != null && companyId != null && companyId != callerCompanyId) {
            return ExecutionResult.NotFound
        }

        if (driverId != null && trip.driverId != null && trip.driverId != driverId) {
            return ExecutionResult.Forbidden
        }

        if (!TripStateMachine.canTransition(trip.tripStatus, TripStatus.BOARDING)) {
            return ExecutionResult.InvalidTransition
        }

        val result = tripRepository.updateStatus(tripId, trip.tripStatus, TripStatus.BOARDING)
        return when (result) {
            TransitionResult.Success -> ExecutionResult.Success
            TransitionResult.NotFound -> ExecutionResult.NotFound
            TransitionResult.StateMismatch -> ExecutionResult.InvalidTransition
        }
    }

    /**
     * Executes a PICKUP stop:
     * - Verifies stop type is PICKUP
     * - Transitions stop to COMPLETED
     * - Transitions ride request to ONBOARD
     * - If trip is in BOARDING, transitions trip to IN_PROGRESS
     */
    fun processPickupStop(
        tripId: String,
        stopId: String,
        driverId: String? = null,
        callerCompanyId: String? = null
    ): ExecutionResult {
        val trip = tripRepository.findById(tripId) ?: return ExecutionResult.NotFound

        val companyId = tripRepository.findCompanyId(tripId)
        if (callerCompanyId != null && companyId != null && companyId != callerCompanyId) {
            return ExecutionResult.NotFound
        }

        if (driverId != null && trip.driverId != null && trip.driverId != driverId) {
            return ExecutionResult.Forbidden
        }

        val stop = tripStopRepository.findById(stopId) ?: return ExecutionResult.NotFound
        if (stop.tripId != tripId) return ExecutionResult.NotFound
        if (callerCompanyId != null && stop.companyId != callerCompanyId) return ExecutionResult.NotFound
        if (stop.stopType != StopType.PICKUP) return ExecutionResult.InvalidTransition
        if (stop.status.isTerminal) return ExecutionResult.InvalidTransition

        val rideReq = rideRequestRepository.findById(stop.rideRequestId) ?: return ExecutionResult.NotFound
        val currentRideStatus = try {
            RideStatus.fromString(rideReq.status)
        } catch (_: Exception) {
            return ExecutionResult.InvalidTransition
        }

        // Must be eligible for ONBOARD
        val canReachOnboard = currentRideStatus == RideStatus.PICKUP_PENDING ||
                (currentRideStatus == RideStatus.MATCHED && RideRequestStateMachine.canTransition(RideStatus.MATCHED, RideStatus.PICKUP_PENDING))
        if (!canReachOnboard) {
            return ExecutionResult.InvalidTransition
        }

        // Transition stop to COMPLETED
        val stopTransition = tripStopRepository.transitionStopStatus(stopId, stop.status, StopStatus.COMPLETED)
        if (stopTransition != TransitionResult.Success) {
            return ExecutionResult.InvalidTransition
        }

        // Transition ride request to ONBOARD
        if (currentRideStatus == RideStatus.MATCHED) {
            rideRequestRepository.transitionStatus(stop.rideRequestId, RideStatus.MATCHED, RideStatus.PICKUP_PENDING)
            val rideTransition = rideRequestRepository.transitionStatus(stop.rideRequestId, RideStatus.PICKUP_PENDING, RideStatus.ONBOARD)
            if (rideTransition != TransitionResult.Success) {
                return ExecutionResult.InvalidTransition
            }
        } else {
            val rideTransition = rideRequestRepository.transitionStatus(stop.rideRequestId, currentRideStatus, RideStatus.ONBOARD)
            if (rideTransition != TransitionResult.Success) {
                return ExecutionResult.InvalidTransition
            }
        }

        // If trip was in BOARDING, advance to IN_PROGRESS on first passenger boarding
        if (trip.tripStatus == TripStatus.BOARDING) {
            tripRepository.updateStatus(tripId, TripStatus.BOARDING, TripStatus.IN_PROGRESS)
        }

        return ExecutionResult.Success
    }

    /**
     * Executes a NO_SHOW for a pickup stop:
     * - Verifies stop type is PICKUP
     * - Transitions stop to SKIPPED
     * - Transitions ride request to NO_SHOW
     * - Checks if trip should complete
     */
    fun processNoShow(
        tripId: String,
        stopId: String,
        driverId: String? = null,
        callerCompanyId: String? = null
    ): ExecutionResult {
        val trip = tripRepository.findById(tripId) ?: return ExecutionResult.NotFound

        val companyId = tripRepository.findCompanyId(tripId)
        if (callerCompanyId != null && companyId != null && companyId != callerCompanyId) {
            return ExecutionResult.NotFound
        }

        if (driverId != null && trip.driverId != null && trip.driverId != driverId) {
            return ExecutionResult.Forbidden
        }

        val stop = tripStopRepository.findById(stopId) ?: return ExecutionResult.NotFound
        if (stop.tripId != tripId) return ExecutionResult.NotFound
        if (callerCompanyId != null && stop.companyId != callerCompanyId) return ExecutionResult.NotFound
        if (stop.stopType != StopType.PICKUP) return ExecutionResult.InvalidTransition
        if (stop.status.isTerminal) return ExecutionResult.InvalidTransition

        val rideReq = rideRequestRepository.findById(stop.rideRequestId) ?: return ExecutionResult.NotFound
        val currentRideStatus = try {
            RideStatus.fromString(rideReq.status)
        } catch (_: Exception) {
            return ExecutionResult.InvalidTransition
        }

        // Ride request must be able to reach NO_SHOW
        if (currentRideStatus == RideStatus.MATCHED) {
            rideRequestRepository.transitionStatus(stop.rideRequestId, RideStatus.MATCHED, RideStatus.PICKUP_PENDING)
        } else if (currentRideStatus != RideStatus.PICKUP_PENDING) {
            return ExecutionResult.InvalidTransition
        }

        // Transition stop to SKIPPED
        val stopTransition = tripStopRepository.transitionStopStatus(stopId, stop.status, StopStatus.SKIPPED)
        if (stopTransition != TransitionResult.Success) {
            return ExecutionResult.InvalidTransition
        }

        // Transition ride request to NO_SHOW
        val rideTransition = rideRequestRepository.transitionStatus(stop.rideRequestId, RideStatus.PICKUP_PENDING, RideStatus.NO_SHOW)
        if (rideTransition != TransitionResult.Success) {
            return ExecutionResult.InvalidTransition
        }

        checkAndCompleteTrip(tripId)

        return ExecutionResult.Success
    }

    /**
     * Executes a DROPOFF stop:
     * - Verifies stop type is DROPOFF (cannot operate on pickup stop)
     * - Verifies ride request is ONBOARD (cannot drop off passenger who has not boarded)
     * - Transitions stop to COMPLETED
     * - Transitions ride request ONBOARD -> COMPLETED
     * - Evaluates trip completion: completes trip if all stops and passengers done
     */
    fun processDropoffStop(
        tripId: String,
        stopId: String,
        driverId: String? = null,
        callerCompanyId: String? = null
    ): ExecutionResult {
        val trip = tripRepository.findById(tripId) ?: return ExecutionResult.NotFound

        val companyId = tripRepository.findCompanyId(tripId)
        if (callerCompanyId != null && companyId != null && companyId != callerCompanyId) {
            return ExecutionResult.NotFound
        }

        if (driverId != null && trip.driverId != null && trip.driverId != driverId) {
            return ExecutionResult.Forbidden
        }

        val stop = tripStopRepository.findById(stopId) ?: return ExecutionResult.NotFound
        if (stop.tripId != tripId) return ExecutionResult.NotFound
        if (callerCompanyId != null && stop.companyId != callerCompanyId) return ExecutionResult.NotFound

        // CRITICAL GUARD: Drop-off cannot operate on pickup stops
        if (stop.stopType != StopType.DROPOFF) {
            return ExecutionResult.InvalidTransition
        }

        // CRITICAL GUARD: Duplicate drop-off rejection
        if (stop.status.isTerminal) {
            return ExecutionResult.InvalidTransition
        }

        val rideReq = rideRequestRepository.findById(stop.rideRequestId) ?: return ExecutionResult.NotFound
        val currentRideStatus = try {
            RideStatus.fromString(rideReq.status)
        } catch (_: Exception) {
            return ExecutionResult.InvalidTransition
        }

        // CRITICAL GUARD: Passenger must be ONBOARD
        if (currentRideStatus != RideStatus.ONBOARD) {
            return ExecutionResult.InvalidTransition
        }

        // Validate state machine rule: ONBOARD -> COMPLETED
        if (!RideRequestStateMachine.canTransition(RideStatus.ONBOARD, RideStatus.COMPLETED)) {
            return ExecutionResult.InvalidTransition
        }

        // Transition stop to COMPLETED
        val stopTransition = tripStopRepository.transitionStopStatus(stopId, stop.status, StopStatus.COMPLETED)
        if (stopTransition != TransitionResult.Success) {
            return ExecutionResult.InvalidTransition
        }

        // Transition ride request to COMPLETED
        val rideTransition = rideRequestRepository.transitionStatus(stop.rideRequestId, RideStatus.ONBOARD, RideStatus.COMPLETED)
        if (rideTransition != TransitionResult.Success) {
            return ExecutionResult.InvalidTransition
        }

        // Check if trip should complete
        checkAndCompleteTrip(tripId)

        return ExecutionResult.Success
    }

    /**
     * Checks if all stops on [tripId] are finished and no ONBOARD passengers remain.
     * If so, transitions the trip from IN_PROGRESS to COMPLETED.
     */
    fun checkAndCompleteTrip(tripId: String): Boolean {
        val remainingStops = tripStopRepository.countRemainingExecutableStops(tripId)
        val onboardPassengers = tripStopRepository.countOnboardPassengers(tripId)

        // Trip can ONLY complete if NO executable stops remain AND NO onboard passengers remain
        if (remainingStops == 0 && onboardPassengers == 0) {
            val trip = tripRepository.findById(tripId) ?: return false
            if (trip.tripStatus == TripStatus.IN_PROGRESS) {
                val result = tripRepository.updateStatus(tripId, TripStatus.IN_PROGRESS, TripStatus.COMPLETED)
                return result == TransitionResult.Success
            }
        }
        return false
    }
}
