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
                call.respond(
                    HttpStatusCode.OK,
                    EmployeeOtpResponse(otp = otp, message = "OTP generated")
                )
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
    }
}
