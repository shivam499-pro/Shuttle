package com.dynashuttle.services

/**
 * Delivers a one-time login code to an employee. See master plan Section 5,
 * item 11: "replace dev-mode OTP-in-response-body with real delivery."
 *
 * Deliberately NOT structured like EtaEstimator's silent-fallback-on-failure
 * pattern (see TomTomEtaEstimator). A wrong ETA number is a small, harmless
 * degradation — the ride still gets booked either way. A failed OTP delivery
 * is not survivable the same way: if send() silently swallowed a failure and
 * let the caller believe it succeeded, the employee would have no way to log
 * in and no error to act on. Implementations should THROW on failure and let
 * it propagate to the app's global error handler (see StatusPages in
 * Application.kt), which already returns a generic, non-leaking 500 — the
 * caller sees a clear failure instead of a false "sent!" with nothing behind it.
 */
interface OtpSender {
    /**
     * True if this sender actually delivers the code somewhere the employee can
     * retrieve it (real email, in production). False for [NoOpOtpSender], the
     * dev-mode fallback used when no real provider is configured (see Routing.kt).
     *
     * AuthService exposes this via [AuthService.realOtpDeliveryConfigured] so the
     * route layer (AuthRoutes.kt) can decide whether it's safe to also include the
     * raw OTP in the HTTP response: doing that when a real sender IS configured
     * would mean leaking a login credential over HTTP in production. Doing it
     * when there's no real sender is the entire point of dev mode — there's no
     * other way to get the code.
     */
    val deliversReal: Boolean

    suspend fun send(email: String, otp: String)
}