package com.dynashuttle.repositories

import com.dynashuttle.models.Admins
import com.dynashuttle.models.Employees
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class PostgresAuthRepository : AuthRepository {

    override fun findEmployeeByEmail(email: String): AuthEmployee? = transaction {
        Employees
            .selectAll()
            .where { Employees.email eq email }
            .map {
                AuthEmployee(
                    id = it[Employees.id].value.toString(),
                    email = it[Employees.email],
                    companyId = it[Employees.companyId].value.toString(),
                    name = it[Employees.name]
                )
            }
            .singleOrNull()
    }

    override fun findAdminByEmail(email: String): AuthAdmin? = transaction {
        Admins
            .selectAll()
            .where { Admins.email eq email }
            .map {
                AuthAdmin(
                    id = it[Admins.id].value.toString(),
                    email = it[Admins.email],
                    passwordHash = it[Admins.passwordHash],
                    companyId = it[Admins.companyId].value.toString()
                )
            }
            .singleOrNull()
    }
}
