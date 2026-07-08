package com.dynashuttle.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dynashuttle.models.AdminLoginResponse
import com.dynashuttle.models.EmployeeVerifyResponse
import com.dynashuttle.repositories.AuthRepository
import java.time.Instant
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

data class OtpRecord(
    val otp: String,
    val expiresAt: Instant
)

class AuthService(
    private val authRepository: AuthRepository,
    private val otpGenerator: OtpGenerator,
    private val passwordHasher: PasswordHasher,
    private val jwtSecret: String,
    private val jwtIssuer: String = "dynashuttle",
    private val otpTtlSeconds: Long = 300
) {
    // Thread-safe in-memory cache for OTPs, mapped by employee email
    private val otpCache = ConcurrentHashMap<String, OtpRecord>()

    fun requestOtp(email: String): String? {
        val employee = authRepository.findEmployeeByEmail(email) ?: return null
        val otp = otpGenerator.generate(email)
        // OTP is valid for configured TTL (default 5 minutes)
        otpCache[email] = OtpRecord(otp, Instant.now().plusSeconds(otpTtlSeconds))
        return otp
    }

    fun verifyOtp(email: String, otp: String): EmployeeVerifyResponse? {
        val record = otpCache[email] ?: return null
        if (Instant.now().isAfter(record.expiresAt) || record.otp != otp) {
            return null
        }
        // Valid OTP used, remove to prevent reuse
        otpCache.remove(email)

        val employee = authRepository.findEmployeeByEmail(email) ?: return null
        val token = generateToken(userId = employee.id, role = "employee")
        return EmployeeVerifyResponse(
            token = token,
            employeeId = employee.id,
            role = "employee"
        )
    }

    fun loginAdmin(email: String, password: String): AdminLoginResponse? {
        val admin = authRepository.findAdminByEmail(email) ?: return null
        if (!passwordHasher.verify(password, admin.passwordHash)) {
            return null
        }
        val token = generateToken(userId = admin.id, role = "admin")
        return AdminLoginResponse(
            token = token,
            role = "admin"
        )
    }

    private fun generateToken(userId: String, role: String): String {
        return JWT.create()
            .withIssuer(jwtIssuer)
            .withClaim("role", role)
            .withClaim("userId", userId)
            .withExpiresAt(Date(System.currentTimeMillis() + 86400000)) // 24 hours validity
            .sign(Algorithm.HMAC256(jwtSecret))
    }
}
