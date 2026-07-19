package com.dynashuttle.routes

import com.dynashuttle.models.RideRequestDto
import com.dynashuttle.services.RoutingService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.rideRoutes(routingService: RoutingService) {

    authenticate("app-user") {
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
}