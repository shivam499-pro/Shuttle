package com.dynashuttle.plugins

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.dynashuttle.routes.*
import com.dynashuttle.repositories.PostgresVehicleRepository
import com.dynashuttle.repositories.PostgresRideRequestRepository
import com.dynashuttle.repositories.PostgresAuthRepository
import com.dynashuttle.services.RoutingService
import com.dynashuttle.services.TripService
import com.dynashuttle.services.AuthService
import com.dynashuttle.services.RandomOtpGenerator
import com.dynashuttle.services.BcryptPasswordHasher
import com.dynashuttle.services.OtpSender
import com.dynashuttle.services.NoOpOtpSender
import com.dynashuttle.services.BrevoOtpSender
import com.dynashuttle.services.routing.EtaEstimator
import com.dynashuttle.services.routing.GreedyNearestNeighbourOptimizer
import com.dynashuttle.services.routing.HaversineEtaEstimator
import com.dynashuttle.services.routing.TomTomEtaEstimator
import com.dynashuttle.config.Env
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.dynashuttle.plugins.Routing")

/**
 * This is the one place in the whole app where concrete implementations get
 * wired to interfaces. Routes and services never construct their own
 * dependencies — they receive interfaces from here. If you swap Postgres for
 * something else, or the greedy optimizer for a VRPTW solver, this is the
 * only file that changes.
 *
 * A single [PostgresVehicleRepository] instance is created here and shared
 * between [RoutingService], [TripService], and [websocketRoutes] — each of
 * which declares a dependency on a different narrow interface
 * (VehicleAvailabilityLookup, VehicleTripLocationLookup, DriverLocationChannel
 * respectively) that [PostgresVehicleRepository] satisfies all at once by
 * implementing the [com.dynashuttle.repositories.VehicleRepository] umbrella.
 * Same pattern for [PostgresRideRequestRepository], shared between RoutingService
 * (RideMatchingRepository) and TripService (TripStopRepository). One instance
 * per concrete data source; each consumer only sees the slice of it it actually
 * calls — that's Interface Segregation and single-instance DI at the same time.
 *
 * One shared [HttpClient] is created here for ALL outbound third-party calls —
 * TomTom and Brevo both reuse it rather than each constructing their own. A
 * client is a connection-pool object meant to be reused, not recreated per
 * provider.
 *
 * ETA estimation (master plan Section 5, item 9): if TOMTOM_API_KEY is set,
 * real traffic-aware ETAs come from [TomTomEtaEstimator]; if not, this falls
 * back to [HaversineEtaEstimator] (flat-speed guess) and logs a warning at
 * startup rather than crashing.
 *
 * OTP delivery (master plan Section 5, item 11): if BREVO_API_KEY and
 * BREVO_SENDER_EMAIL are both set, real email delivery comes from
 * [BrevoOtpSender]; if not, this falls back to [NoOpOtpSender] (dev mode — OTP
 * returned directly in the API response instead, see AuthRoutes.kt) and logs a
 * warning at startup. Unlike the ETA estimator, [BrevoOtpSender] does NOT
 * silently fall back on a failed send — see OtpSender's KDoc for why a failed
 * OTP delivery has to be a loud error, not a quiet degradation.
 */
fun Application.configureRouting() {
    val vehicleRepository = PostgresVehicleRepository()
    val rideRequestRepository = PostgresRideRequestRepository()

    val httpClient = HttpClient(CIO)

    val tomTomApiKey = Env.get("TOMTOM_API_KEY")
    val etaEstimator: EtaEstimator = if (tomTomApiKey != null) {
        TomTomEtaEstimator(httpClient = httpClient, apiKey = tomTomApiKey)
    } else {
        logger.warn(
            "TOMTOM_API_KEY not set — falling back to flat-speed ETA estimates. " +
                    "See master plan Section 5, item 9, and .env.example."
        )
        HaversineEtaEstimator()
    }

    val brevoApiKey = Env.get("BREVO_API_KEY")
    val brevoSenderEmail = Env.get("BREVO_SENDER_EMAIL")
    val otpSender: OtpSender = if (brevoApiKey != null && brevoSenderEmail != null) {
        BrevoOtpSender(httpClient = httpClient, apiKey = brevoApiKey, senderEmail = brevoSenderEmail)
    } else {
        logger.warn(
            "BREVO_API_KEY/BREVO_SENDER_EMAIL not set — using dev-mode OTP delivery " +
                    "(returned directly in the API response, not actually emailed). " +
                    "See master plan Section 5, item 11, and .env.example."
        )
        NoOpOtpSender()
    }

    val routeOptimizer = GreedyNearestNeighbourOptimizer(etaEstimator = etaEstimator)

    val routingService = RoutingService(
        vehicleRepository = vehicleRepository,
        routeOptimizer = routeOptimizer,
        rideRequestRepository = rideRequestRepository
    )

    val tripService = TripService(
        rideRequestRepository = rideRequestRepository,
        vehicleRepository = vehicleRepository,
        routeOptimizer = routeOptimizer
    )

    val jwtSecret = Env.get("JWT_SECRET", "dev-secret-change-me")!!
    val authService = AuthService(
        authRepository = PostgresAuthRepository(),
        otpGenerator = RandomOtpGenerator(),
        passwordHasher = BcryptPasswordHasher(),
        jwtSecret = jwtSecret,
        otpSender = otpSender
    )

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "dynashuttle-backend"))
        }

        rideRoutes(routingService)
        tripRoutes(tripService)
        websocketRoutes(vehicleRepository)
        authRoutes(authService)
    }
}