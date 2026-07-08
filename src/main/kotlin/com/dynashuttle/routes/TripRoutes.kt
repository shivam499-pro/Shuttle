package com.dynashuttle.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Driver-facing trip endpoints.
 * GET  /trips/{id}/stops          -> current ordered stop list for a trip
 * POST /trips/{id}/stops/{stopId}/pickup  -> mark a passenger as picked up
 * POST /trips/{id}/stops/{stopId}/no-show -> mark a passenger as a no-show
 */
fun Route.tripRoutes() {
    route("/trips/{id}") {

        get("/stops") {
            val tripId = call.parameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing trip id")

            // TODO: fetch current ordered stop list from RideRequests where tripId = tripId
            call.respond(HttpStatusCode.OK, mapOf("tripId" to tripId, "stops" to emptyList<Any>()))
        }

        post("/stops/{stopId}/pickup") {
            val stopId = call.parameters["stopId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing stop id")

            // TODO: update RideRequests.status = PICKED_UP for this stopId
            call.respond(HttpStatusCode.OK, mapOf("stopId" to stopId, "status" to "PICKED_UP"))
        }

        post("/stops/{stopId}/no-show") {
            val stopId = call.parameters["stopId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing stop id")

            // TODO: update RideRequests.status = NO_SHOW for this stopId
            call.respond(HttpStatusCode.OK, mapOf("stopId" to stopId, "status" to "NO_SHOW"))
        }
    }
}
