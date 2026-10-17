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

data class AuthDriver(
    val id: String,
    val phone: String,
    val companyId: String,
    val name: String
)

interface AuthRepository {
    fun findEmployeeByEmail(email: String): AuthEmployee?
    fun findAdminByEmail(email: String): AuthAdmin?
    fun findDriverByPhone(phone: String): AuthDriver? = null
}