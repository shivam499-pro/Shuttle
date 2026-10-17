package com.dynashuttle.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import com.dynashuttle.config.Env

/**
 * Two JWT realms:
 *  - "app-user"  -> employees and drivers logging in via OTP / phone
 *  - "admin"     -> fleet admins logging in via email + password
 * Keeping them separate means an employee token can never accidentally
 * unlock an admin-only route, and vice versa.
 */
fun Application.configureAuthentication() {
    val secret = Env.get("JWT_SECRET", "dev-secret-change-me")!!
    val issuer = "dynashuttle"

    install(Authentication) {
        jwt("app-user") {
            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withIssuer(issuer)
                    .withClaim("role") { value, _ -> value.asString() in listOf("employee", "driver") }
                    .build()
            )
            validate { credential -> JWTPrincipal(credential.payload) }
        }

        jwt("admin") {
            verifier(
                JWT.require(Algorithm.HMAC256(secret))
                    .withIssuer(issuer)
                    .withClaim("role", "admin")
                    .build()
            )
            validate { credential -> JWTPrincipal(credential.payload) }
        }
    }
}

data class UserPrincipal(
    val userId: String,
    val role: String,
    val companyId: String
)

/**
 * Extracts and validates the authenticated user identity and company from the JWT claims.
 * Returns null if no JWTPrincipal is attached or if any of userId, role, or companyId is missing.
 */
fun ApplicationCall.authenticatedUser(): UserPrincipal? {
    val principal = principal<JWTPrincipal>() ?: return null
    val userId = principal.payload.getClaim("userId")?.asString() ?: return null
    val role = principal.payload.getClaim("role")?.asString() ?: return null
    val companyId = principal.payload.getClaim("companyId")?.asString() ?: return null
    return UserPrincipal(userId = userId, role = role, companyId = companyId)
}
