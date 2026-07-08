package com.dynashuttle.plugins

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.dynashuttle.routes.*
import com.dynashuttle.repositories.PostgresVehicleRepository
import com.dynashuttle.repositories.PostgresRideRequestRepository
import com.dynashuttle.repositories.PostgresAuthRepository
import com.dynashuttle.services.RoutingService
import com.dynashuttle.services.AuthService
import com.dynashuttle.services.RandomOtpGenerator
import com.dynashuttle.services.BcryptPasswordHasher
import com.dynashuttle.services.routing.GreedyNearestNeighbourOptimizer
import com.dynashuttle.config.Env

/**
 * This is the one place in the whole app where concrete implementations get
 * wired to interfaces. Routes and services never construct their own
 * dependencies — they receive interfaces from here. If you swap Postgres for
 * something else, or the greedy optimizer for a VRPTW solver, this is the
 * only file that changes.
 *
 * A single [PostgresVehicleRepository] instance is created here and shared
 * between [RoutingService] (ride matching) and [websocketRoutes] (GPS persistence).
 * Sharing one instance is the correct DI practice even though the current
 * implementation is stateless per-call — it avoids subtle bugs if the
 * repository ever gains per-instance state (e.g. a cache or connection pool
 * handle), and makes it unambiguous that there is exactly one vehicle data source.
 */
fun Application.configureRouting() {
    val vehicleRepository = PostgresVehicleRepository()

    val routingService = RoutingService(
        vehicleRepository = vehicleRepository,
        routeOptimizer = GreedyNearestNeighbourOptimizer(),
        rideRequestRepository = PostgresRideRequestRepository()
    )

    val jwtSecret = Env.get("JWT_SECRET", "dev-secret-change-me")!!
    val authService = AuthService(
        authRepository = PostgresAuthRepository(),
        otpGenerator = RandomOtpGenerator(),
        passwordHasher = BcryptPasswordHasher(),
        jwtSecret = jwtSecret
    )

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "dynashuttle-backend"))
        }

        rideRoutes(routingService)
        tripRoutes()
        websocketRoutes(vehicleRepository)
        authRoutes(authService)
    }
}

