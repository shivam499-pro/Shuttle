package com.dynashuttle.routes

import com.dynashuttle.models.CreateShuttleScheduleRequest
import com.dynashuttle.models.ErrorResponse
import com.dynashuttle.models.GenerateTripRequest
import com.dynashuttle.models.GeneratedTripResponse
import com.dynashuttle.models.ShuttleSchedule
import com.dynashuttle.models.ShuttleScheduleResponse
import com.dynashuttle.models.Trip
import com.dynashuttle.models.UpdateShuttleScheduleRequest
import com.dynashuttle.plugins.authenticatedUser
import com.dynashuttle.services.ShuttleScheduleService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun ShuttleSchedule.toResponse() = ShuttleScheduleResponse(
    id = id,
    companyId = companyId,
    name = name,
    direction = direction.name,
    departureTime = departureTime.format(timeFormatter),
    daysOfWeek = daysOfWeek.map { it.name },
    active = active,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)

private fun Trip.toResponse() = GeneratedTripResponse(
    tripId = id,
    scheduleId = scheduleId,
    tripDate = tripDate.toString(),
    startTime = startTime.toString(),
    vehicleId = vehicleId,
    driverId = driverId,
    status = status
)

fun Route.shuttleScheduleRoutes(scheduleService: ShuttleScheduleService) {

    authenticate("admin", "app-user") {
        route("/shuttle-schedules") {

            // POST /shuttle-schedules — admin creates a shuttle schedule for their company
            post {
                val user = call.authenticatedUser()
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse("unauthorized", "Authentication required")
                    )

                if (user.role != "admin") {
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("forbidden", "Only admins can manage shuttle schedules")
                    )
                }

                val request = call.receive<CreateShuttleScheduleRequest>()
                when (val result = scheduleService.createSchedule(user.companyId, request)) {
                    is ShuttleScheduleService.CreateResult.Success ->
                        call.respond(HttpStatusCode.Created, result.schedule.toResponse())
                    is ShuttleScheduleService.CreateResult.ValidationError ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("validation_error", result.message))
                }
            }

            // GET /shuttle-schedules — list schedules for the caller's company
            get {
                val user = call.authenticatedUser()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse("unauthorized", "Authentication required")
                    )

                val activeOnly = call.request.queryParameters["activeOnly"]?.toBooleanStrictOrNull() ?: false
                val schedules = scheduleService.getSchedulesByCompany(user.companyId, activeOnly)
                call.respond(HttpStatusCode.OK, schedules.map { it.toResponse() })
            }

            // GET /shuttle-schedules/{id} — get details of a specific schedule
            get("/{id}") {
                val id = call.parameters["id"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("bad_request", "Missing schedule id")
                    )
                val user = call.authenticatedUser()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse("unauthorized", "Authentication required")
                    )

                val schedule = scheduleService.getSchedule(id, user.companyId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("not_found", "Shuttle schedule not found")
                    )

                call.respond(HttpStatusCode.OK, schedule.toResponse())
            }

            // PUT /shuttle-schedules/{id} — admin updates an existing schedule
            put("/{id}") {
                val id = call.parameters["id"]
                    ?: return@put call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("bad_request", "Missing schedule id")
                    )
                val user = call.authenticatedUser()
                    ?: return@put call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse("unauthorized", "Authentication required")
                    )

                if (user.role != "admin") {
                    return@put call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("forbidden", "Only admins can manage shuttle schedules")
                    )
                }

                val request = call.receive<UpdateShuttleScheduleRequest>()
                when (val result = scheduleService.updateSchedule(id, user.companyId, request)) {
                    is ShuttleScheduleService.UpdateResult.Success ->
                        call.respond(HttpStatusCode.OK, result.schedule.toResponse())
                    is ShuttleScheduleService.UpdateResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Shuttle schedule not found"))
                    is ShuttleScheduleService.UpdateResult.ValidationError ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("validation_error", result.message))
                }
            }

            // DELETE /shuttle-schedules/{id} — admin soft-deactivates a schedule
            delete("/{id}") {
                val id = call.parameters["id"]
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("bad_request", "Missing schedule id")
                    )
                val user = call.authenticatedUser()
                    ?: return@delete call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse("unauthorized", "Authentication required")
                    )

                if (user.role != "admin") {
                    return@delete call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("forbidden", "Only admins can manage shuttle schedules")
                    )
                }

                val ok = scheduleService.deleteSchedule(id, user.companyId)
                if (ok) {
                    call.respond(HttpStatusCode.OK, mapOf("status" to "deactivated", "message" to "Shuttle schedule deactivated"))
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Shuttle schedule not found"))
                }
            }

            // POST /shuttle-schedules/{scheduleId}/trips — admin generates a concrete trip for a specific date
            post("/{scheduleId}/trips") {
                val user = call.authenticatedUser()
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse("unauthorized", "Authentication required")
                    )

                if (user.role != "admin") {
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("forbidden", "Only admins can generate trips from shuttle schedules")
                    )
                }

                val scheduleId = call.parameters["scheduleId"]
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("bad_request", "Missing scheduleId")
                    )

                val request = runCatching { call.receive<GenerateTripRequest>() }.getOrElse {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("bad_request", "Invalid request body")
                    )
                }

                when (val result = scheduleService.generateTrip(scheduleId, user.companyId, request)) {
                    is ShuttleScheduleService.GenerateTripResult.Success -> {
                        val status = if (result.isCreated) HttpStatusCode.Created else HttpStatusCode.OK
                        call.respond(status, result.trip.toResponse())
                    }
                    is ShuttleScheduleService.GenerateTripResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Schedule not found"))
                    is ShuttleScheduleService.GenerateTripResult.InactiveSchedule ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_schedule", result.message))
                    is ShuttleScheduleService.GenerateTripResult.ValidationError ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("validation_error", result.message))
                }
            }
        }
    }
}
