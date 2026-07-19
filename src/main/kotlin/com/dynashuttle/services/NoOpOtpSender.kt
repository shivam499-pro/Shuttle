package com.dynashuttle.services

/**
 * Dev-mode fallback: does not actually send anything anywhere. Used when no
 * real provider (BREVO_API_KEY / BREVO_SENDER_EMAIL) is configured — see
 * Routing.kt. Because [deliversReal] is false, AuthRoutes.kt includes the raw
 * OTP directly in the HTTP response instead — this is exactly today's existing
 * dev-convenience behavior (see the "known gaps" note in the planning doc),
 * unchanged, just now represented as an explicit sender so the fallback wiring
 * in Routing.kt mirrors the same shape as EtaEstimator/HaversineEtaEstimator.
 */
class NoOpOtpSender : OtpSender {
    override val deliversReal = false

    override suspend fun send(email: String, otp: String) {
        // Intentionally does nothing — see class doc. The OTP still gets
        // returned to the caller via AuthService.requestOtp()'s return value;
        // this class just never delivers it anywhere external.
    }
}