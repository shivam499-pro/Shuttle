package com.dynashuttle.routes

import com.dynashuttle.models.CreatePickupZoneRequest
import com.dynashuttle.models.ErrorResponse
import com.dynashuttle.models.PickupZone
import com.dynashuttle.models.PickupZoneResponse
import com.dynashuttle.models.UpdatePickupZoneRequest
import com.dynashuttle.plugins.authenticatedUser
import com.dynashuttle.services.PickupZoneService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private fun PickupZone.toResponse() = PickupZoneResponse(
    id = id,
    companyId = companyId,
    name = name,
    latitude = latitude,
    longitude = longitude,
    radius = radius,
    address = address,
    active = active,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)

fun Route.pickupZoneRoutes(pickupZoneService: PickupZoneService) {

    authenticate("admin", "app-user") {
        route("/pickup-zones") {

            // POST /pickup-zones — admin creates a pickup zone for their company
            post {
                val user = call.authenticatedUser()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                if (user.role != "admin") {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Only admins can manage pickup zones"))
                }

                val request = call.receive<CreatePickupZoneRequest>()
                when (val result = pickupZoneService.createZone(user.companyId, request)) {
                    is PickupZoneService.CreateResult.Success ->
                        call.respond(HttpStatusCode.Created, result.zone.toResponse())
                    is PickupZoneService.CreateResult.ValidationError ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("validation_error", result.message))
                }
            }

            // GET /pickup-zones — list all pickup zones for the caller's company
            get {
                val user = call.authenticatedUser()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                val activeOnly = call.request.queryParameters["activeOnly"]?.toBooleanStrictOrNull() ?: false
                val zones = pickupZoneService.getZonesByCompany(user.companyId, activeOnly)
                call.respond(HttpStatusCode.OK, zones.map { it.toResponse() })
            }

            // GET /pickup-zones/{id} — get details of a specific pickup zone
            get("/{id}") {
                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing pickup zone id"))
                val user = call.authenticatedUser()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                val zone = pickupZoneService.getZone(id, user.companyId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Pickup zone not found"))

                call.respond(HttpStatusCode.OK, zone.toResponse())
            }

            // PUT /pickup-zones/{id} — admin updates an existing pickup zone
            put("/{id}") {
                val id = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing pickup zone id"))
                val user = call.authenticatedUser()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                if (user.role != "admin") {
                    return@put call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Only admins can manage pickup zones"))
                }

                val request = call.receive<UpdatePickupZoneRequest>()
                when (val result = pickupZoneService.updateZone(id, user.companyId, request)) {
                    is PickupZoneService.UpdateResult.Success ->
                        call.respond(HttpStatusCode.OK, result.zone.toResponse())
                    is PickupZoneService.UpdateResult.NotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Pickup zone not found"))
                    is PickupZoneService.UpdateResult.ValidationError ->
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("validation_error", result.message))
                }
            }

            // DELETE /pickup-zones/{id} — admin soft-deletes a pickup zone
            delete("/{id}") {
                val id = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing pickup zone id"))
                val user = call.authenticatedUser()
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                if (user.role != "admin") {
                    return@delete call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Only admins can manage pickup zones"))
                }

                val ok = pickupZoneService.deleteZone(id, user.companyId)
                if (ok) {
                    call.respond(HttpStatusCode.OK, mapOf("status" to "deactivated", "message" to "Pickup zone deactivated"))
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Pickup zone not found"))
                }
            }

            // POST /pickup-zones/{id}/employees/{employeeId} — assign an employee to a pickup zone
            post("/{id}/employees/{employeeId}") {
                val zoneId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing pickup zone id"))
                val employeeId = call.parameters["employeeId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", "Missing employee id"))
                val user = call.authenticatedUser()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized", "Authentication required"))

                if (user.role != "admin") {
                    return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Only admins can assign employees to pickup zones"))
                }

                when (pickupZoneService.assignEmployeeToZone(employeeId, zoneId, user.companyId)) {
                    is PickupZoneService.AssignmentResult.Success ->
                        call.respond(HttpStatusCode.OK, mapOf("status" to "assigned", "message" to "Employee assigned to pickup zone"))
                    is PickupZoneService.AssignmentResult.CrossCompanyMismatch ->
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden", "Cannot assign employee to a zone from another company"))
                    is PickupZoneService.AssignmentResult.EmployeeNotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Employee not found"))
                    is PickupZoneService.AssignmentResult.ZoneNotFound ->
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found", "Pickup zone not found"))
                }
            }
        }
    }
}
