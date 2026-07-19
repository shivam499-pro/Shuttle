package com.dynashuttle.services

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class BrevoSender(val email: String, val name: String)

@Serializable
private data class BrevoRecipient(val email: String)

@Serializable
private data class BrevoSendEmailRequest(
    val sender: BrevoSender,
    val to: List<BrevoRecipient>,
    val subject: String,
    val htmlContent: String
)

/**
 * Real OTP delivery via Brevo's transactional email API (see master plan
 * Section 5, item 11). Chosen over SMS/DLT: DynaShuttle has no registered
 * business entity yet, and DLT (India's mandatory SMS compliance registry)
 * requires one as a hard prerequisite — email needs no such registration, at
 * any company size, ever. It also fits what's already built: the Employee app's
 * auth flow was already keyed on company email (see the Feature List's "Register
 * with company email" requirement), not a phone number — there's no phone
 * column on Employees at all, so real SMS would have meant a schema migration
 * this doesn't.
 *
 * API contract (per Brevo's docs): POST to /v3/smtp/email with the API key in
 * an `api-key` header, and a JSON body naming a sender (which MUST be a
 * pre-verified email/domain in the Brevo dashboard — an unverified sender will
 * fail every call) and a recipient. Response shape isn't modeled beyond
 * checking the status code — nothing else about the response is needed here.
 *
 * Unlike TomTomEtaEstimator, this class does NOT swallow failures into a
 * fallback — see OtpSender's KDoc for why a failed OTP send has to be a loud,
 * visible error rather than a silent degradation.
 */
class BrevoOtpSender(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val senderEmail: String,
    private val senderName: String = "DynaShuttle"
) : OtpSender {

    override val deliversReal = true

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun send(email: String, otp: String) {
        val requestBody = BrevoSendEmailRequest(
            sender = BrevoSender(email = senderEmail, name = senderName),
            to = listOf(BrevoRecipient(email = email)),
            subject = "Your DynaShuttle login code",
            htmlContent = "<p>Your DynaShuttle login code is <strong>$otp</strong>. " +
                    "It expires in 5 minutes. If you didn't request this, you can ignore this email.</p>"
        )

        val response = httpClient.post("https://api.brevo.com/v3/smtp/email") {
            header("api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(BrevoSendEmailRequest.serializer(), requestBody))
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                "Brevo email send failed for $email: HTTP ${response.status.value} - ${response.bodyAsText()}"
            )
        }
    }
}

