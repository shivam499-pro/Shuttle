package com.dynashuttle.services

import com.dynashuttle.repositories.AuthAdmin
import com.dynashuttle.repositories.AuthDriver
import com.dynashuttle.repositories.AuthEmployee
import com.dynashuttle.repositories.AuthRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeAuthRepository : AuthRepository {

    val employees = mutableMapOf<String, AuthEmployee>()
    val admins = mutableMapOf<String, AuthAdmin>()
    val drivers = mutableMapOf<String, AuthDriver>()

    override fun findEmployeeByEmail(email: String): AuthEmployee? {
        return employees.values.find { it.email == email }
    }

    override fun findAdminByEmail(email: String): AuthAdmin? {
        return admins.values.find { it.email == email }
    }

    override fun findDriverByPhone(phone: String): AuthDriver? {
        return drivers.values.find { it.phone == phone }
    }
}

private class FakeOtpGenerator(
    private val otpToReturn: String = "123456"
) : OtpGenerator {

    var lastEmailRequested: String? = null

    override fun generate(email: String): String {
        lastEmailRequested = email
        return otpToReturn
    }
}

private class FakePasswordHasher : PasswordHasher {

    override fun hash(password: String): String {
        return "hashed-$password"
    }

    override fun verify(
        password: String,
        hashedPassword: String
    ): Boolean {
        return hashedPassword == "hashed-$password"
    }
}

private class FakeOtpSender(
    override val deliversReal: Boolean = true,
    private val shouldThrow: Boolean = false
) : OtpSender {

    val sentTo = mutableListOf<Pair<String, String>>()

    override suspend fun send(
        email: String,
        otp: String
    ) {
        if (shouldThrow) {
            throw IllegalStateException("simulated send failure")
        }

        sentTo.add(email to otp)
    }
}

class AuthServiceTest {

    @Test
    fun `requestOtp returns null when employee not found`() = runBlocking {
        val repo = FakeAuthRepository()

        val service = AuthService(
            repo,
            FakeOtpGenerator(),
            FakePasswordHasher(),
            "test-secret"
        )

        val otp = service.requestOtp("missing@company.com")

        assertNull(otp)
    }

    @Test
    fun `requestOtp returns OTP when employee exists`() = runBlocking {
        val repo = FakeAuthRepository().apply {
            employees["emp-1"] = AuthEmployee(
                id = "emp-1",
                email = "employee@company.com",
                companyId = "co-1",
                name = "Employee One"
            )
        }

        val generator = FakeOtpGenerator("999888")

        val service = AuthService(
            repo,
            generator,
            FakePasswordHasher(),
            "test-secret"
        )

        val otp = service.requestOtp("employee@company.com")

        assertEquals("999888", otp)
        assertEquals(
            "employee@company.com",
            generator.lastEmailRequested
        )
    }

    @Test
    fun `requestOtp calls the configured OtpSender with the generated code`() =
        runBlocking {

            val repo = FakeAuthRepository().apply {
                employees["emp-1"] = AuthEmployee(
                    id = "emp-1",
                    email = "employee@company.com",
                    companyId = "co-1",
                    name = "Employee One"
                )
            }

            val sender = FakeOtpSender()

            val service = AuthService(
                authRepository = repo,
                otpGenerator = FakeOtpGenerator("555444"),
                passwordHasher = FakePasswordHasher(),
                jwtSecret = "test-secret",
                otpSender = sender
            )

            service.requestOtp("employee@company.com")

            assertEquals(1, sender.sentTo.size)

            assertEquals(
                "employee@company.com" to "555444",
                sender.sentTo[0]
            )
        }

    @Test
    fun `realOtpDeliveryConfigured reflects the sender's deliversReal flag`() {

        val repo = FakeAuthRepository()

        val devModeService = AuthService(
            repo,
            FakeOtpGenerator(),
            FakePasswordHasher(),
            "test-secret"
        )

        assertFalse(
            devModeService.realOtpDeliveryConfigured,
            "Default NoOpOtpSender should report deliversReal = false"
        )

        val realSenderService = AuthService(
            authRepository = repo,
            otpGenerator = FakeOtpGenerator(),
            passwordHasher = FakePasswordHasher(),
            jwtSecret = "test-secret",
            otpSender = FakeOtpSender(deliversReal = true)
        )

        assertTrue(
            realSenderService.realOtpDeliveryConfigured
        )
    }

    @Test
    fun `verifyOtp fails with wrong OTP`() = runBlocking {

        val repo = FakeAuthRepository().apply {
            employees["emp-1"] = AuthEmployee(
                id = "emp-1",
                email = "employee@company.com",
                companyId = "co-1",
                name = "Employee One"
            )
        }

        val service = AuthService(
            repo,
            FakeOtpGenerator("123456"),
            FakePasswordHasher(),
            "test-secret"
        )

        service.requestOtp("employee@company.com")

        val response = service.verifyOtp(
            "employee@company.com",
            "wrong-otp"
        )

        assertNull(response)
    }

    @Test
    fun `verifyOtp succeeds with correct OTP`() = runBlocking {

        val repo = FakeAuthRepository().apply {
            employees["emp-1"] = AuthEmployee(
                id = "emp-1",
                email = "employee@company.com",
                companyId = "co-1",
                name = "Employee One"
            )
        }

        val service = AuthService(
            repo,
            FakeOtpGenerator("123456"),
            FakePasswordHasher(),
            "test-secret"
        )

        service.requestOtp("employee@company.com")

        val response = service.verifyOtp(
            "employee@company.com",
            "123456"
        )

        assertNotNull(response)
        assertNotNull(response.token)

        assertEquals(
            "emp-1",
            response.employeeId
        )

        assertEquals(
            "employee",
            response.role
        )

        val secondResponse = service.verifyOtp(
            "employee@company.com",
            "123456"
        )

        assertNull(secondResponse)
    }

    @Test
    fun `verifyOtp fails with expired OTP`() = runBlocking {

        val repo = FakeAuthRepository().apply {
            employees["emp-1"] = AuthEmployee(
                id = "emp-1",
                email = "employee@company.com",
                companyId = "co-1",
                name = "Employee One"
            )
        }

        val service = AuthService(
            authRepository = repo,
            otpGenerator = FakeOtpGenerator("123456"),
            passwordHasher = FakePasswordHasher(),
            jwtSecret = "test-secret",
            otpTtlSeconds = -1
        )

        service.requestOtp("employee@company.com")

        val response = service.verifyOtp(
            "employee@company.com",
            "123456"
        )

        assertNull(response)
    }

    @Test
    fun `loginAdmin fails with wrong password`() {

        val repo = FakeAuthRepository().apply {
            admins["admin-1"] = AuthAdmin(
                id = "admin-1",
                email = "admin@company.com",
                passwordHash = "hashed-secret",
                companyId = "co-1"
            )
        }

        val service = AuthService(
            repo,
            FakeOtpGenerator(),
            FakePasswordHasher(),
            "test-secret"
        )

        val response = service.loginAdmin(
            "admin@company.com",
            "wrong-password"
        )

        assertNull(response)
    }

    @Test
    fun `loginAdmin succeeds with correct password`() {

        val repo = FakeAuthRepository().apply {
            admins["admin-1"] = AuthAdmin(
                id = "admin-1",
                email = "admin@company.com",
                passwordHash = "hashed-secret",
                companyId = "co-1"
            )
        }

        val service = AuthService(
            repo,
            FakeOtpGenerator(),
            FakePasswordHasher(),
            "test-secret"
        )

        val response = service.loginAdmin(
            "admin@company.com",
            "secret"
        )

        assertNotNull(response)
        assertNotNull(response.token)

        assertEquals(
            "admin",
            response.role
        )
    }
}