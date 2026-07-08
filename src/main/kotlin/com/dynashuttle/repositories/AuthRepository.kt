package com.dynashuttle.repositories

data class AuthEmployee(
    val id: String,
    val email: String,
    val companyId: String,
    val name: String
)

data class AuthAdmin(
    val id: String,
    val email: String,
    val passwordHash: String,
    val companyId: String
)

interface AuthRepository {
    fun findEmployeeByEmail(email: String): AuthEmployee?
    fun findAdminByEmail(email: String): AuthAdmin?
}
