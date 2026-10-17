package com.dynashuttle.routes

import com.dynashuttle.models.DriverLocationUpdate
import com.dynashuttle.plugins.authenticatedUser
import com.dynashuttle.repositories.DriverLocationChannel
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.consumeEach
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Real-time location channel.
 *
 * Driver app connects to /ws/driver/{tripId} and sends its GPS position every 5s.
 * Employee app and admin dashboard connect to /ws/trip/{tripId} as listeners and
 * receive each location update the moment it arrives — no polling needed.
 *
 * Enforces strict authorization:
 * - /ws/driver/{tripId}: caller must have role == "driver", must be assigned to tripId,
 *   and must belong to the trip's company.
 * - /ws/trip/{tripId}: caller must be authenticated and belong to the trip's company.
 */
private val tripListeners = ConcurrentHashMap<String, MutableList<DefaultWebSocketServerSession>>()

fun Route.websocketRoutes(vehicleRepository: DriverLocationChannel) {

    authenticate("app-user") {
        webSocket("/ws/driver/{tripId}") {
            val tripId = call.parameters["tripId"] ?: return@webSocket close()
            val user = call.authenticatedUser()
            if (user == null || user.role != "driver") {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Driver role required"))
                return@webSocket
            }

            val trip = withContext(Dispatchers.IO) {
                vehicleRepository.findTripDetails(tripId)
            }
            if (trip == null || trip.companyId != user.companyId) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Trip not found"))
                return@webSocket
            }
            if (trip.driverId != user.userId) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not assigned driver for this trip"))
                return@webSocket
            }

            try {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val update = Json.decodeFromString<DriverLocationUpdate>(frame.readText())

                        if (update.tripId != tripId || update.driverId != user.userId) {
                            return@consumeEach
                        }

                        val vehicleId = trip.vehicleId
                        withContext(Dispatchers.IO) {
                            vehicleRepository.updateLocation(vehicleId, update.lat, update.lng)
                        }

                        broadcastToListeners(tripId, update)
                    }
                }
            } finally {
                tripListeners[tripId]?.remove(this)
            }
        }

        webSocket("/ws/trip/{tripId}") {
            val tripId = call.parameters["tripId"] ?: return@webSocket close()
            val user = call.authenticatedUser()
            if (user == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication required"))
                return@webSocket
            }

            val trip = withContext(Dispatchers.IO) {
                vehicleRepository.findTripDetails(tripId)
            }
            if (trip == null || trip.companyId != user.companyId) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Trip not found"))
                return@webSocket
            }

            tripListeners.getOrPut(tripId) { mutableListOf() }.add(this)

            try {
                incoming.consumeEach { /* listeners are read-only, no incoming messages expected */ }
            } finally {
                tripListeners[tripId]?.remove(this)
            }
        }
    }
}

private suspend fun broadcastToListeners(tripId: String, update: DriverLocationUpdate) {
    val payload = Json.encodeToString(update)
    tripListeners[tripId]?.forEach { session ->
        session.send(Frame.Text(payload))
    }
}