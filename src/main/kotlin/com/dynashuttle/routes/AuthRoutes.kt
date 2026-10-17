package com.dynashuttle.routes

import com.dynashuttle.models.*
import com.dynashuttle.services.AuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(authService: AuthService) {
    route("/auth") {

        post("/employee/request-otp") {
            val request = call.receive<EmployeeOtpRequest>()
            val otp = authService.requestOtp(request.email)
            if (otp == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("not_found", "Employee not found")
                )
            } else {
                // Redact the OTP once real delivery is configured — including it in
                // the response would leak a login credential over HTTP in production.
                // In dev mode (no BREVO_API_KEY set), this is unchanged from before:
                // the OTP comes back in the response since there's no other way to
                // get it. See OtpSender's KDoc for the full reasoning.
                val otpForResponse = if (authService.realOtpDeliveryConfigured) null else otp
                val message = if (authService.realOtpDeliveryConfigured) {
                    "OTP sent to your email"
                } else {
                    "OTP generated"
                }
                call.respond(HttpStatusCode.OK, EmployeeOtpResponse(otp = otpForResponse, message = message))
            }
        }

        post("/employee/verify-otp") {
            val request = call.receive<EmployeeVerifyRequest>()
            val response = authService.verifyOtp(request.email, request.otp)
            if (response == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("unauthorized", "Invalid or expired OTP")
                )
            } else {
                call.respond(HttpStatusCode.OK, response)
            }
        }

        post("/admin/login") {
            val request = call.receive<AdminLoginRequest>()
            val response = authService.loginAdmin(request.email, request.password)
            if (response == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("unauthorized", "Invalid credentials")
                )
            } else {
                call.respond(HttpStatusCode.OK, response)
            }
        }
        post("/driver/request-otp") {
            val request = call.receive<DriverOtpRequest>()
            val otp = authService.requestDriverOtp(request.phone)

            if (otp == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("not_found", "Driver not found")
                )
            } else {
                call.respond(
                    HttpStatusCode.OK,
                    DriverOtpResponse(
                        otp = otp,
                        message = "OTP generated"
                    )
                )
            }
        }

        post("/driver/verify-otp") {
            val request = call.receive<DriverVerifyRequest>()
            val response = authService.verifyDriverOtp(
                phone = request.phone,
                otp = request.otp
            )

            if (response == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("unauthorized", "Invalid or expired OTP")
                )
            } else {
                call.respond(
                    HttpStatusCode.OK,
                    response
                )
            }
        }
    }
}