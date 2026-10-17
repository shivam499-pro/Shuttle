package com.dynashuttle.routes

import com.dynashuttle.models.ErrorResponse
import com.dynashuttle.models.RideRequestDto
import com.dynashuttle.plugins.authenticatedUser
import com.dynashuttle.services.RoutingService
import com.dynashuttle.services.TripService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.rideRoutes(routingService: RoutingService, tripService: TripService) {

    authenticate("app-user") {
        route("/rides") {

            // POST /rides — create a new ride request
            post {
                val request = call.receive<RideRequestDto>()
                val user = call.authenticatedUser()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                if (user.role != "employee") {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Only employees can request rides"))
                }

                // Employee IDOR protection: client-provided employeeId MUST match authenticated identity
                if (request.employeeId != user.userId) {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Cannot request ride for another employee"))
                }

                val result = routingService.matchRideRequest(request, companyId = user.companyId)

                call.respond(HttpStatusCode.Created, result)
            }

            // GET /rides/{id} — get ride status
            get("/{id}") {
                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing ride id"))
                val user = call.authenticatedUser()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                val status = routingService.getRideStatus(
                    rideRequestId = id,
                    callerUserId = if (user.role == "admin") null else user.userId,
                    callerCompanyId = user.companyId
                ) ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Ride request not found"))

                call.respond(status)
            }

            // POST /rides/{id}/cancel — employee cancels their own ride request
            post("/{id}/cancel") {
                val id = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing ride id"))
                val user = call.authenticatedUser()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                if (user.role != "employee") {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Only employees can cancel their own rides"))
                }

                val result = tripService.cancelRideRequest(
                    rideRequestId = id,
                    callerEmployeeId = user.userId,
                    callerCompanyId = user.companyId
                )

                when (result) {
                    TripService.LifecycleResult.Success ->
                        call.respond(HttpStatusCode.OK, ErrorResponse("cancelled", "Ride request cancelled successfully"))
                    TripService.LifecycleResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Ride request not found"))
                    TripService.LifecycleResult.Forbidden ->
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Cannot cancel another employee's ride"))
                    TripService.LifecycleResult.InvalidTransition ->
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("invalid_transition", "Ride cannot be cancelled in its current state"))
                }
            }
        }
    }
}