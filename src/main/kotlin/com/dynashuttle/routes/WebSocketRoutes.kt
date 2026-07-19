package com.dynashuttle.routes

import com.dynashuttle.models.DriverLocationUpdate
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
 * [vehicleRepository] is injected from Routing.kt (the single wiring point for the
 * whole app) so that this route handler never constructs its own dependencies —
 * consistent with the pattern established by rideRoutes(routingService).
 *
 * This in-memory map is fine for a single-instance MVP. If you scale to multiple
 * backend instances later, swap this for a Redis pub/sub channel instead.
 *
 * Both endpoints are wrapped in authenticate("app-user"). The JWT is validated
 * during the HTTP handshake (the WebSocket upgrade request), before the socket
 * is accepted — Ktor's jwt() auth provider reads it from a standard Authorization:
 * Bearer header on that handshake request, same as any other route. Native Android
 * clients (this project's employee/driver apps, per the tech stack) can set that
 * header when opening the socket; this does not work for a plain browser WebSocket
 * client, which can't set custom headers, but that's not a client this app has.
 * TODO: once real roles are enforced per-endpoint, restrict /ws/driver/{tripId}
 * to role == "driver" specifically, inside the handler.
 */
private val tripListeners = ConcurrentHashMap<String, MutableList<DefaultWebSocketServerSession>>()

fun Route.websocketRoutes(vehicleRepository: DriverLocationChannel) {

    authenticate("app-user") {
        webSocket("/ws/driver/{tripId}") {
            val tripId = call.parameters["tripId"] ?: return@webSocket close()

            try {
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val update = Json.decodeFromString<DriverLocationUpdate>(frame.readText())

                        // Resolve vehicleId from tripId via the repository — DriverLocationUpdate
                        // carries a tripId (not a vehicleId directly), so we delegate the lookup
                        // to VehicleRepository.findVehicleIdByTripId rather than querying the
                        // Trips table here. Route handlers in this codebase never talk to the
                        // database directly; they go through an injected interface (see RideRoutes.kt).
                        val vehicleId = withContext(Dispatchers.IO) {
                            vehicleRepository.findVehicleIdByTripId(update.tripId)
                        }

                        if (vehicleId != null) {
                            withContext(Dispatchers.IO) {
                                vehicleRepository.updateLocation(vehicleId, update.lat, update.lng)
                            }
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