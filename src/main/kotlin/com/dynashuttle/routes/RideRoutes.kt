package com.dynashuttle.routes

import com.dynashuttle.models.RideRequestDto
import com.dynashuttle.services.RoutingService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Employee-facing ride request endpoints.
 * POST /rides         -> request a new ride (triggers the matching + routing engine)
 * GET  /rides/{id}     -> poll current status of a ride request
 *
 * routingService is injected from outside (see Application.kt) rather than
 * constructed here — this route file has no idea whether it's talking to
 * Postgres, an in-memory fake, or anything else, which is exactly the point.
 */
fun Route.rideRoutes(routingService: RoutingService) {

    route("/rides") {

        post {
            val request = call.receive<RideRequestDto>()

            // TODO: validate employee belongs to a company with an active subscription
            val result = routingService.matchRideRequest(request)

            call.respond(HttpStatusCode.Created, result)
        }

        get("/{id}") {
            val id = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing ride id")

            val status = routingService.getRideStatus(id)
                ?: return@get call.respond(HttpStatusCode.NotFound, "Ride request not found")

            call.respond(status)
        }
    }
}
