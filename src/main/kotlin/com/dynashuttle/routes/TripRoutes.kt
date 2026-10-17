package com.dynashuttle.routes

import com.dynashuttle.models.ErrorResponse
import com.dynashuttle.models.StopActionResponse
import com.dynashuttle.plugins.authenticatedUser
import com.dynashuttle.services.TripExecutionService
import com.dynashuttle.services.TripService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Driver-facing trip endpoints.
 *
 * GET  /trips/{id}/stops                       -> current ordered stop list
 * POST /trips/{id}/stops/{stopId}/pickup       -> PICKUP_PENDING -> ONBOARD
 * POST /trips/{id}/stops/{stopId}/no-show      -> PICKUP_PENDING -> NO_SHOW
 * POST /trips/{id}/stops/{stopId}/complete     -> ONBOARD -> COMPLETED (legacy: by rideRequestId)
 * POST /trips/{id}/stops/{stopId}/dropoff      -> persisted trip_stops DROPOFF (by stopId)
 * POST /trips/{id}/assign                      -> SCHEDULED -> ASSIGNED
 * POST /trips/{id}/boarding                    -> ASSIGNED -> BOARDING
 *
 * Wrapped in authenticate("app-user") — all endpoints verify JWT caller identity.
 * Driver endpoints strictly enforce role == "driver", assigned-driver check, and
 * company/tenant isolation.  Invalid lifecycle transitions return 422.
 */
fun Route.tripRoutes(
    tripService: TripService,
    tripExecutionService: TripExecutionService? = null
) {
    authenticate("app-user") {
        route("/trips/{id}") {

            get("/stops") {
                val tripId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing trip id"))
                val user = call.authenticatedUser()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                val trip = tripService.getTripDetails(tripId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Trip not found"))

                // Cross-company access is blocked (return 404 to avoid leaking trip existence)
                if (trip.companyId != user.companyId) {
                    return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Trip not found"))
                }

                when (user.role) {
                    "driver" -> {
                        if (trip.driverId != user.userId) {
                            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Not assigned driver for this trip"))
                        }
                    }
                    "employee" -> {
                        if (!tripService.hasEmployeeOnTrip(tripId, user.userId)) {
                            return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Not a passenger on this trip"))
                        }
                    }
                    else -> {
                        return@get call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Unauthorized role"))
                    }
                }

                val stopList = tripService.getStopList(tripId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Trip not found"))

                call.respond(HttpStatusCode.OK, stopList)
            }

            // ----------------------------------------------------------------
            // POST /trips/{id}/assign
            // SCHEDULED -> ASSIGNED (assigns vehicle and driver)
            // ----------------------------------------------------------------
            post("/assign") {
                val tripId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing trip id"))
                val user = call.authenticatedUser()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                if (tripExecutionService == null) {
                    return@post call.respond(HttpStatusCode.NotImplemented, ErrorResponse("not_implemented", "Trip execution service not configured"))
                }

                val vehicleId = call.request.queryParameters["vehicleId"] ?: call.parameters["vehicleId"]
                val driverId = call.request.queryParameters["driverId"] ?: call.parameters["driverId"] ?: user.userId

                if (vehicleId.isNullOrBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing vehicleId"))
                }

                val result = tripExecutionService.assignTrip(tripId, vehicleId, driverId, user.companyId)
                when (result) {
                    TripExecutionService.ExecutionResult.Success ->
                        call.respond(HttpStatusCode.OK, mapOf("tripId" to tripId, "status" to "ASSIGNED"))
                    TripExecutionService.ExecutionResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Trip not found"))
                    TripExecutionService.ExecutionResult.InvalidTransition ->
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid_transition", "Trip cannot be assigned from current state"))
                    TripExecutionService.ExecutionResult.Forbidden ->
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Not authorised"))
                }
            }

            // ----------------------------------------------------------------
            // POST /trips/{id}/boarding
            // ASSIGNED -> BOARDING (driver begins boarding)
            // ----------------------------------------------------------------
            post("/boarding") {
                val tripId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing trip id"))
                val user = call.authenticatedUser()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                if (user.role != "driver") {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Only drivers can perform this action"))
                }

                if (tripExecutionService == null) {
                    return@post call.respond(HttpStatusCode.NotImplemented, ErrorResponse("not_implemented", "Trip execution service not configured"))
                }

                val result = tripExecutionService.startBoarding(tripId, user.userId, user.companyId)
                when (result) {
                    TripExecutionService.ExecutionResult.Success ->
                        call.respond(HttpStatusCode.OK, mapOf("tripId" to tripId, "status" to "BOARDING"))
                    TripExecutionService.ExecutionResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Trip not found"))
                    TripExecutionService.ExecutionResult.InvalidTransition ->
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid_transition", "Trip cannot enter boarding from current state"))
                    TripExecutionService.ExecutionResult.Forbidden ->
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Not authorised for this trip"))
                }
            }

            // ----------------------------------------------------------------
            // POST /trips/{id}/stops/{stopId}/pickup
            // PICKUP_PENDING -> ONBOARD  (driver confirms passenger boarded)
            // ----------------------------------------------------------------
            post("/stops/{stopId}/pickup") {
                val tripId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing trip id"))
                val stopId = call.parameters["stopId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing stop id"))
                val user = call.authenticatedUser()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                if (user.role != "driver") {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Only drivers can perform this action"))
                }

                val trip = tripService.getTripDetails(tripId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Trip not found"))

                if (trip.companyId != user.companyId) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Trip not found"))
                }

                if (trip.driverId != user.userId) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Not assigned driver for this trip"))
                }

                val result = tripService.markPickedUp(tripId, stopId, driverId = user.userId, companyId = user.companyId)
                when (result) {
                    TripService.LifecycleResult.Success ->
                        call.respond(HttpStatusCode.OK, StopActionResponse(stopId = stopId, status = "ONBOARD"))
                    TripService.LifecycleResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Stop not found on this trip"))
                    TripService.LifecycleResult.InvalidTransition ->
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid_transition", "Ride is not in a state that allows pickup"))
                    TripService.LifecycleResult.Forbidden ->
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Not authorised for this trip"))
                }
            }

            // ----------------------------------------------------------------
            // POST /trips/{id}/stops/{stopId}/no-show
            // PICKUP_PENDING -> NO_SHOW
            // ----------------------------------------------------------------
            post("/stops/{stopId}/no-show") {
                val tripId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing trip id"))
                val stopId = call.parameters["stopId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing stop id"))
                val user = call.authenticatedUser()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                if (user.role != "driver") {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Only drivers can perform this action"))
                }

                val trip = tripService.getTripDetails(tripId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Trip not found"))

                if (trip.companyId != user.companyId) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Trip not found"))
                }

                if (trip.driverId != user.userId) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Not assigned driver for this trip"))
                }

                val result = tripService.markNoShow(tripId, stopId, driverId = user.userId, companyId = user.companyId)
                when (result) {
                    TripService.LifecycleResult.Success ->
                        call.respond(HttpStatusCode.OK, StopActionResponse(stopId = stopId, status = "NO_SHOW"))
                    TripService.LifecycleResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Stop not found on this trip"))
                    TripService.LifecycleResult.InvalidTransition ->
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid_transition", "Ride is not in a state that allows no-show"))
                    TripService.LifecycleResult.Forbidden ->
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Not authorised for this trip"))
                }
            }

            // ----------------------------------------------------------------
            // POST /trips/{id}/stops/{stopId}/complete
            // ONBOARD -> COMPLETED  (driver marks drop-off done by rideRequestId)
            // ----------------------------------------------------------------
            post("/stops/{stopId}/complete") {
                val tripId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing trip id"))
                val stopId = call.parameters["stopId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing stop id"))
                val user = call.authenticatedUser()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                if (user.role != "driver") {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Only drivers can perform this action"))
                }

                val trip = tripService.getTripDetails(tripId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Trip not found"))

                if (trip.companyId != user.companyId) {
                    return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Trip not found"))
                }

                if (trip.driverId != user.userId) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Not assigned driver for this trip"))
                }

                val result = tripService.markCompleted(tripId, stopId, driverId = user.userId, companyId = user.companyId)
                when (result) {
                    TripService.LifecycleResult.Success ->
                        call.respond(HttpStatusCode.OK, StopActionResponse(stopId = stopId, status = "COMPLETED"))
                    TripService.LifecycleResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Stop not found on this trip"))
                    TripService.LifecycleResult.InvalidTransition ->
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid_transition", "Ride is not in a state that allows completion"))
                    TripService.LifecycleResult.Forbidden ->
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Not authorised for this trip"))
                }
            }

            // ----------------------------------------------------------------
            // POST /trips/{id}/stops/{stopId}/dropoff
            // Persisted trip_stops DROPOFF execution (by stop UUID):
            // - Stop PLANNED/ARRIVED -> COMPLETED
            // - RideRequest ONBOARD -> COMPLETED
            // - Checks and triggers trip completion
            // ----------------------------------------------------------------
            post("/stops/{stopId}/dropoff") {
                val tripId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing trip id"))
                val stopId = call.parameters["stopId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing stop id"))
                val user = call.authenticatedUser()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                if (user.role != "driver") {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Only drivers can perform this action"))
                }

                if (tripExecutionService == null) {
                    return@post call.respond(HttpStatusCode.NotImplemented, ErrorResponse("not_implemented", "Trip execution service not configured"))
                }

                val result = tripExecutionService.processDropoffStop(
                    tripId = tripId,
                    stopId = stopId,
                    driverId = user.userId,
                    callerCompanyId = user.companyId
                )

                when (result) {
                    TripExecutionService.ExecutionResult.Success ->
                        call.respond(HttpStatusCode.OK, StopActionResponse(stopId = stopId, status = "COMPLETED"))
                    TripExecutionService.ExecutionResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Stop not found on this trip"))
                    TripExecutionService.ExecutionResult.InvalidTransition ->
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid_transition", "Drop-off is not permitted for this stop state or ride request"))
                    TripExecutionService.ExecutionResult.Forbidden ->
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Not authorised for this trip"))
                }
            }
        }
    }
}