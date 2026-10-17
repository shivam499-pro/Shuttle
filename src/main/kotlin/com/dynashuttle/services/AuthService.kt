package com.dynashuttle.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dynashuttle.models.AdminLoginResponse
import com.dynashuttle.models.EmployeeVerifyResponse
import com.dynashuttle.models.DriverVerifyResponse
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
    private val otpTtlSeconds: Long = 300,
    private val otpSender: OtpSender = NoOpOtpSender()
) {
    // Thread-safe in-memory cache for OTPs, mapped by employee email
    private val otpCache = ConcurrentHashMap<String, OtpRecord>()

    /**
     * True when a real [OtpSender] (e.g. [BrevoOtpSender]) is configured, rather
     * than the dev-mode [NoOpOtpSender] fallback. AuthRoutes.kt uses this to
     * decide whether including the raw OTP in the HTTP response is safe — see
     * OtpSender's KDoc for why that distinction matters.
     */
    val realOtpDeliveryConfigured: Boolean
        get() = otpSender.deliversReal

    suspend fun requestOtp(email: String): String? {
        authRepository.findEmployeeByEmail(email) ?: return null
        val otp = otpGenerator.generate(email)
        // OTP is valid for configured TTL (default 5 minutes)
        otpCache[email] = OtpRecord(otp, Instant.now().plusSeconds(otpTtlSeconds))
        otpSender.send(email, otp)
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
        val token = generateToken(userId = employee.id, role = "employee", companyId = employee.companyId)
        return EmployeeVerifyResponse(
            token = token,
            employeeId = employee.id,
            role = "employee",
            companyId = employee.companyId
        )
    }

    fun loginAdmin(email: String, password: String): AdminLoginResponse? {
        val admin = authRepository.findAdminByEmail(email) ?: return null
        if (!passwordHasher.verify(password, admin.passwordHash)) {
            return null
        }
        val token = generateToken(userId = admin.id, role = "admin", companyId = admin.companyId)
        return AdminLoginResponse(
            token = token,
            role = "admin",
            companyId = admin.companyId
        )
    }

    fun generateToken(userId: String, role: String, companyId: String): String {
        return JWT.create()
            .withIssuer(jwtIssuer)
            .withClaim("role", role)
            .withClaim("userId", userId)
            .withClaim("companyId", companyId)
            .withExpiresAt(Date(System.currentTimeMillis() + 86400000)) // 24 hours validity
            .sign(Algorithm.HMAC256(jwtSecret))
    }
    suspend fun requestDriverOtp(phone: String): String? {
        authRepository.findDriverByPhone(phone) ?: return null

        val otp = otpGenerator.generate(phone)

        otpCache["driver:$phone"] = OtpRecord(
            otp = otp,
            expiresAt = Instant.now().plusSeconds(otpTtlSeconds)
        )

        return otp
    }

    fun verifyDriverOtp(phone: String, otp: String): DriverVerifyResponse? {
        val record = otpCache["driver:$phone"] ?: return null

        if (Instant.now().isAfter(record.expiresAt) || record.otp != otp) {
            return null
        }

        // OTP is single-use
        otpCache.remove("driver:$phone")

        val driver = authRepository.findDriverByPhone(phone) ?: return null

        val token = generateToken(
            userId = driver.id,
            role = "driver",
            companyId = driver.companyId
        )

        return DriverVerifyResponse(
            token = token,
            driverId = driver.id,
            role = "driver",
            companyId = driver.companyId
        )
    }
}