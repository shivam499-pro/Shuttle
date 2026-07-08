package com.dynashuttle.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import com.dynashuttle.config.Env
import com.dynashuttle.models.*

/**
 * Sets up the Postgres connection pool and creates tables on startup.
 * Reads connection details via Env (which loads .env locally, falls back to
 * real system env vars in production) — see com.dynashuttle.config.Env.
 */
object DatabaseFactory {

    fun init() {
        val dataSource = createDataSource()
        Database.connect(dataSource)

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Companies,
                Employees,
                Drivers,
                Vehicles,
                Trips,
                RideRequests,
                Admins
            )
        }
    }

    private fun createDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = Env.get("DATABASE_URL", "jdbc:postgresql://localhost:5432/dynashuttle")
            username = Env.get("DATABASE_USER", "postgres")
            password = Env.get("DATABASE_PASSWORD", "postgres")
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
        return HikariDataSource(config)
    }
}
