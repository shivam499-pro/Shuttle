package com.dynashuttle.services

import com.dynashuttle.repositories.AuthAdmin
import com.dynashuttle.repositories.AuthEmployee
import com.dynashuttle.repositories.AuthRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class FakeAuthRepository : AuthRepository {
    val employees = mutableMapOf<String, AuthEmployee>()
    val admins = mutableMapOf<String, AuthAdmin>()

    override fun findEmployeeByEmail(email: String): AuthEmployee? {
        return employees.values.find { it.email == email }
    }

    override fun findAdminByEmail(email: String): AuthAdmin? {
        return admins.values.find { it.email == email }
    }
}

private class FakeOtpGenerator(private val otpToReturn: String = "123456") : OtpGenerator {
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

    override fun verify(password: String, hashedPassword: String): Boolean {
        return hashedPassword == "hashed-$password"
    }
}

class AuthServiceTest {

    @Test
    fun `requestOtp returns null when employee not found`() {
        val repo = FakeAuthRepository()
        val service = AuthService(repo, FakeOtpGenerator(), FakePasswordHasher(), "test-secret")

        val otp = service.requestOtp("missing@company.com")
        assertNull(otp)
    }

    @Test
    fun `requestOtp returns OTP when employee exists`() {
        val repo = FakeAuthRepository().apply {
            employees["emp-1"] = AuthEmployee(id = "emp-1", email = "employee@company.com", companyId = "co-1", name = "Employee One")
        }
        val generator = FakeOtpGenerator("999888")
        val service = AuthService(repo, generator, FakePasswordHasher(), "test-secret")

        val otp = service.requestOtp("employee@company.com")
        assertEquals("999888", otp)
        assertEquals("employee@company.com", generator.lastEmailRequested)
    }

    @Test
    fun `verifyOtp fails with wrong OTP`() {
        val repo = FakeAuthRepository().apply {
            employees["emp-1"] = AuthEmployee(id = "emp-1", email = "employee@company.com", companyId = "co-1", name = "Employee One")
        }
        val service = AuthService(repo, FakeOtpGenerator("123456"), FakePasswordHasher(), "test-secret")

        service.requestOtp("employee@company.com")

        val response = service.verifyOtp("employee@company.com", "wrong-otp")
        assertNull(response)
    }

    @Test
    fun `verifyOtp succeeds with correct OTP`() {
        val repo = FakeAuthRepository().apply {
            employees["emp-1"] = AuthEmployee(id = "emp-1", email = "employee@company.com", companyId = "co-1", name = "Employee One")
        }
        val service = AuthService(repo, FakeOtpGenerator("123456"), FakePasswordHasher(), "test-secret")

        service.requestOtp("employee@company.com")

        val response = service.verifyOtp("employee@company.com", "123456")
        assertNotNull(response)
        assertNotNull(response.token)
        assertEquals("emp-1", response.employeeId)
        assertEquals("employee", response.role)

        // Verify that OTP cannot be reused
        val secondResponse = service.verifyOtp("employee@company.com", "123456")
        assertNull(secondResponse)
    }

    @Test
    fun `verifyOtp fails with expired OTP`() {
        val repo = FakeAuthRepository().apply {
            employees["emp-1"] = AuthEmployee(id = "emp-1", email = "employee@company.com", companyId = "co-1", name = "Employee One")
        }
        val service = AuthService(
            authRepository = repo,
            otpGenerator = FakeOtpGenerator("123456"),
            passwordHasher = FakePasswordHasher(),
            jwtSecret = "test-secret",
            otpTtlSeconds = -1
        )

        service.requestOtp("employee@company.com")

        val response = service.verifyOtp("employee@company.com", "123456")
        assertNull(response)
    }

    @Test
    fun `loginAdmin fails with wrong password`() {
        val repo = FakeAuthRepository().apply {
            admins["admin-1"] = AuthAdmin(id = "admin-1", email = "admin@company.com", passwordHash = "hashed-secret", companyId = "co-1")
        }
        val service = AuthService(repo, FakeOtpGenerator(), FakePasswordHasher(), "test-secret")

        val response = service.loginAdmin("admin@company.com", "wrong-password")
        assertNull(response)
    }

    @Test
    fun `loginAdmin succeeds with correct password`() {
        val repo = FakeAuthRepository().apply {
            admins["admin-1"] = AuthAdmin(id = "admin-1", email = "admin@company.com", passwordHash = "hashed-secret", companyId = "co-1")
        }
        val service = AuthService(repo, FakeOtpGenerator(), FakePasswordHasher(), "test-secret")

        val response = service.loginAdmin("admin@company.com", "secret")
        assertNotNull(response)
        assertNotNull(response.token)
        assertEquals("admin", response.role)
    }
}
