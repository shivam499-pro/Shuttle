package com.dynashuttle.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import com.dynashuttle.config.Env
import javax.sql.DataSource

/**
 * Sets up the Postgres connection pool and runs versioned Flyway migrations.
 * Replaces runtime schema evolution via SchemaUtils with deterministic,
 * versioned migrations suitable for staging and production.
 */
object DatabaseFactory {

    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init(runMigrations: Boolean = true): HikariDataSource {
        val dataSource = createDataSource()
        if (runMigrations) {
            runFlyway(dataSource)
        }
        Database.connect(dataSource)
        return dataSource
    }

    /**
     * Executes Flyway migrations against the provided data source.
     * Uses baselineOnMigrate = true with baselineVersion = 1 so that:
     * - A fresh database executes V1 then V2.
     * - An existing database (with existing tables) is safely baselined at V1 and migrates forward to V2.
     */
    fun runFlyway(dataSource: DataSource) {
        logger.info("Running Flyway database migrations...")
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("1")
            .load()

        val result = flyway.migrate()
        logger.info(
            "Flyway migration complete: {} migrations applied (target schema version: {})",
            result.migrationsExecuted,
            result.targetSchemaVersion ?: "none"
        )
    }

    fun createDataSource(): HikariDataSource {
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
