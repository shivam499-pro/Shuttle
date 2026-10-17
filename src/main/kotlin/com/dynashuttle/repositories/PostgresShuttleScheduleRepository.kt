package com.dynashuttle.repositories

import com.dynashuttle.models.ScheduleDay
import com.dynashuttle.models.ScheduleDirection
import com.dynashuttle.models.ShuttleSchedule
import com.dynashuttle.models.ShuttleSchedules
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

class PostgresShuttleScheduleRepository : ShuttleScheduleRepository {

    // -------------------------------------------------------------------------
    // Serialization helpers for days_of_week (stored as comma-separated TEXT)
    // -------------------------------------------------------------------------

    private fun List<ScheduleDay>.toDb(): String =
        joinToString(",") { it.name }

    private fun String.toDayList(): List<ScheduleDay> =
        split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { ScheduleDay.valueOf(it) }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private fun toSchedule(row: ResultRow): ShuttleSchedule = ShuttleSchedule(
        id = row[ShuttleSchedules.id].value.toString(),
        companyId = row[ShuttleSchedules.companyId].value.toString(),
        name = row[ShuttleSchedules.name],
        direction = ScheduleDirection.valueOf(row[ShuttleSchedules.direction]),
        departureTime = row[ShuttleSchedules.departureTime],
        daysOfWeek = row[ShuttleSchedules.daysOfWeek].toDayList(),
        active = row[ShuttleSchedules.active],
        createdAt = row[ShuttleSchedules.createdAt],
        updatedAt = row[ShuttleSchedules.updatedAt]
    )

    // -------------------------------------------------------------------------
    // Interface implementation
    // -------------------------------------------------------------------------

    override fun create(
        companyId: String,
        name: String,
        direction: ScheduleDirection,
        departureTime: LocalTime,
        daysOfWeek: List<ScheduleDay>
    ): ShuttleSchedule = transaction {
        val newId = UUID.randomUUID()
        val compUuid = UUID.fromString(companyId)
        val now = Instant.now()

        ShuttleSchedules.insert {
            it[id] = newId
            it[ShuttleSchedules.companyId] = compUuid
            it[ShuttleSchedules.name] = name
            it[ShuttleSchedules.direction] = direction.name
            it[ShuttleSchedules.departureTime] = departureTime
            it[ShuttleSchedules.daysOfWeek] = daysOfWeek.toDb()
            it[active] = true
            it[createdAt] = now
            it[updatedAt] = now
        }

        ShuttleSchedule(
            id = newId.toString(),
            companyId = companyId,
            name = name,
            direction = direction,
            departureTime = departureTime,
            daysOfWeek = daysOfWeek,
            active = true,
            createdAt = now,
            updatedAt = now
        )
    }

    override fun findById(id: String): ShuttleSchedule? = transaction {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@transaction null
        ShuttleSchedules
            .selectAll()
            .where { ShuttleSchedules.id eq uuid }
            .map { toSchedule(it) }
            .singleOrNull()
    }

    override fun findByCompany(companyId: String): List<ShuttleSchedule> = transaction {
        val compUuid = runCatching { UUID.fromString(companyId) }.getOrNull()
            ?: return@transaction emptyList()
        ShuttleSchedules
            .selectAll()
            .where { ShuttleSchedules.companyId eq compUuid }
            .map { toSchedule(it) }
    }

    override fun findActiveByCompany(companyId: String): List<ShuttleSchedule> = transaction {
        val compUuid = runCatching { UUID.fromString(companyId) }.getOrNull()
            ?: return@transaction emptyList()
        ShuttleSchedules
            .selectAll()
            .where { (ShuttleSchedules.companyId eq compUuid) and (ShuttleSchedules.active eq true) }
            .map { toSchedule(it) }
    }

    override fun update(
        id: String,
        companyId: String,
        name: String?,
        direction: ScheduleDirection?,
        departureTime: LocalTime?,
        daysOfWeek: List<ScheduleDay>?,
        active: Boolean?
    ): ShuttleSchedule? = transaction {
        val schedUuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@transaction null
        val compUuid = runCatching { UUID.fromString(companyId) }.getOrNull() ?: return@transaction null
        val now = Instant.now()

        val updated = ShuttleSchedules.update(
            { (ShuttleSchedules.id eq schedUuid) and (ShuttleSchedules.companyId eq compUuid) }
        ) {
            if (name != null) it[ShuttleSchedules.name] = name
            if (direction != null) it[ShuttleSchedules.direction] = direction.name
            if (departureTime != null) it[ShuttleSchedules.departureTime] = departureTime
            if (daysOfWeek != null) it[ShuttleSchedules.daysOfWeek] = daysOfWeek.toDb()
            if (active != null) it[ShuttleSchedules.active] = active
            it[updatedAt] = now
        }

        if (updated > 0) {
            ShuttleSchedules
                .selectAll()
                .where { ShuttleSchedules.id eq schedUuid }
                .map { toSchedule(it) }
                .singleOrNull()
        } else {
            null
        }
    }

    override fun deactivate(id: String, companyId: String): Boolean = transaction {
        val schedUuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@transaction false
        val compUuid = runCatching { UUID.fromString(companyId) }.getOrNull() ?: return@transaction false
        val now = Instant.now()

        val updated = ShuttleSchedules.update(
            { (ShuttleSchedules.id eq schedUuid) and (ShuttleSchedules.companyId eq compUuid) }
        ) {
            it[active] = false
            it[updatedAt] = now
        }
        updated > 0
    }
}
