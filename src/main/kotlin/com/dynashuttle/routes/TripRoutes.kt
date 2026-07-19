package com.dynashuttle.routes

import com.dynashuttle.models.StopActionResponse
import com.dynashuttle.models.ErrorResponse
import com.dynashuttle.services.TripService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Driver-facing trip endpoints.
 * GET  /trips/{id}/stops          -> current ordered stop list for a trip
 * POST /trips/{id}/stops/{stopId}/pickup  -> mark a passenger as picked up
 * POST /trips/{id}/stops/{stopId}/no-show -> mark a passenger as a no-show
 *
 * Wrapped in authenticate("app-user") — same realm as /rides. It accepts both
 * "employee" and "driver" roles (see Authentication.kt); these three endpoints
 * are driver-only in practice, but that's an authorization concern (checking the
 * "role" claim inside the handler), not an authentication one.
 * TODO: once roles are enforced per-endpoint, add a role == "driver" check here too.
 *
 * Real persistence now, via [TripService] — see its KDoc for how stop order is
 * computed (recomputed live, not read from a stored column that doesn't exist).
 * tripService is injected from Routing.kt, same pattern as rideRoutes(routingService) —
 * this route file never talks to Exposed/Postgres directly.
 */
fun Route.tripRoutes(tripService: TripService) {
    authenticate("app-user") {
        route("/trips/{id}") {

            get("/stops") {
                val tripId = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing trip id")

                val stopList = tripService.getStopList(tripId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("not_found", "Trip not found")
                    )

                call.respond(HttpStatusCode.OK, stopList)
            }

            post("/stops/{stopId}/pickup") {
                val tripId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing trip id")
                val stopId = call.parameters["stopId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing stop id")

                val updated = tripService.markPickedUp(tripId, stopId)
                if (!updated) {
                    return@post call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("not_found", "Stop not found on this trip")
                    )
                }
                call.respond(HttpStatusCode.OK, StopActionResponse(stopId = stopId, status = "PICKED_UP"))
            }

            post("/stops/{stopId}/no-show") {
                val tripId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing trip id")
                val stopId = call.parameters["stopId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing stop id")

                val updated = tripService.markNoShow(tripId, stopId)
                if (!updated) {
                    return@post call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("not_found", "Stop not found on this trip")
                    )
                }
                call.respond(HttpStatusCode.OK, StopActionResponse(stopId = stopId, status = "NO_SHOW"))
            }
        }
    }
}