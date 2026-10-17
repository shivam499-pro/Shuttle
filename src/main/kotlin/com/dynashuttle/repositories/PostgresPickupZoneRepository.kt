package com.dynashuttle.repositories

import com.dynashuttle.models.Employees
import com.dynashuttle.models.PickupZone
import com.dynashuttle.models.PickupZones
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

class PostgresPickupZoneRepository : PickupZoneRepository {

    private fun toPickupZone(row: ResultRow): PickupZone = PickupZone(
        id = row[PickupZones.id].value.toString(),
        companyId = row[PickupZones.companyId].value.toString(),
        name = row[PickupZones.name],
        latitude = row[PickupZones.latitude],
        longitude = row[PickupZones.longitude],
        radius = row[PickupZones.radius],
        address = row[PickupZones.address],
        active = row[PickupZones.active],
        createdAt = row[PickupZones.createdAt],
        updatedAt = row[PickupZones.updatedAt]
    )

    override fun create(
        companyId: String,
        name: String,
        latitude: Double,
        longitude: Double,
        radius: Int,
        address: String?
    ): PickupZone = transaction {
        val newId = UUID.randomUUID()
        val compUuid = UUID.fromString(companyId)
        val now = Instant.now()

        PickupZones.insert {
            it[id] = newId
            it[PickupZones.companyId] = compUuid
            it[PickupZones.name] = name
            it[PickupZones.latitude] = latitude
            it[PickupZones.longitude] = longitude
            it[PickupZones.radius] = radius
            it[PickupZones.address] = address
            it[active] = true
            it[createdAt] = now
            it[updatedAt] = now
        }

        PickupZone(
            id = newId.toString(),
            companyId = companyId,
            name = name,
            latitude = latitude,
            longitude = longitude,
            radius = radius,
            address = address,
            active = true,
            createdAt = now,
            updatedAt = now
        )
    }

    override fun findById(id: String): PickupZone? = transaction {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@transaction null
        PickupZones
            .selectAll()
            .where { PickupZones.id eq uuid }
            .map { toPickupZone(it) }
            .singleOrNull()
    }

    override fun findByCompany(companyId: String): List<PickupZone> = transaction {
        val compUuid = runCatching { UUID.fromString(companyId) }.getOrNull() ?: return@transaction emptyList()
        PickupZones
            .selectAll()
            .where { PickupZones.companyId eq compUuid }
            .map { toPickupZone(it) }
    }

    override fun findActiveByCompany(companyId: String): List<PickupZone> = transaction {
        val compUuid = runCatching { UUID.fromString(companyId) }.getOrNull() ?: return@transaction emptyList()
        PickupZones
            .selectAll()
            .where { (PickupZones.companyId eq compUuid) and (PickupZones.active eq true) }
            .map { toPickupZone(it) }
    }

    override fun update(
        id: String,
        companyId: String,
        name: String?,
        latitude: Double?,
        longitude: Double?,
        radius: Int?,
        address: String?,
        active: Boolean?
    ): PickupZone? = transaction {
        val zoneUuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@transaction null
        val compUuid = runCatching { UUID.fromString(companyId) }.getOrNull() ?: return@transaction null
        val now = Instant.now()

        val updatedRows = PickupZones.update({ (PickupZones.id eq zoneUuid) and (PickupZones.companyId eq compUuid) }) {
            if (name != null) it[PickupZones.name] = name
            if (latitude != null) it[PickupZones.latitude] = latitude
            if (longitude != null) it[PickupZones.longitude] = longitude
            if (radius != null) it[PickupZones.radius] = radius
            if (address != null) it[PickupZones.address] = address
            if (active != null) it[PickupZones.active] = active
            it[updatedAt] = now
        }

        if (updatedRows > 0) {
            PickupZones
                .selectAll()
                .where { PickupZones.id eq zoneUuid }
                .map { toPickupZone(it) }
                .singleOrNull()
        } else {
            null
        }
    }

    override fun deactivate(id: String, companyId: String): Boolean = transaction {
        val zoneUuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@transaction false
        val compUuid = runCatching { UUID.fromString(companyId) }.getOrNull() ?: return@transaction false
        val now = Instant.now()

        val updatedRows = PickupZones.update({ (PickupZones.id eq zoneUuid) and (PickupZones.companyId eq compUuid) }) {
            it[active] = false
            it[updatedAt] = now
        }
        updatedRows > 0
    }

    override fun findEmployeeCompanyId(employeeId: String): String? = transaction {
        val empUuid = runCatching { UUID.fromString(employeeId) }.getOrNull() ?: return@transaction null
        Employees
            .selectAll()
            .where { Employees.id eq empUuid }
            .map { it[Employees.companyId].value.toString() }
            .singleOrNull()
    }

    override fun assignEmployeeToZone(employeeId: String, zoneId: String?): Boolean = transaction {
        val empUuid = runCatching { UUID.fromString(employeeId) }.getOrNull() ?: return@transaction false
        val zoneUuid = zoneId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        val updatedRows = Employees.update({ Employees.id eq empUuid }) {
            it[pickupZoneId] = zoneUuid
        }
        updatedRows > 0
    }

    override fun findEmployeePickupZoneId(employeeId: String): String? = transaction {
        val empUuid = runCatching { UUID.fromString(employeeId) }.getOrNull() ?: return@transaction null
        Employees
            .selectAll()
            .where { Employees.id eq empUuid }
            .map { it[Employees.pickupZoneId]?.value?.toString() }
            .singleOrNull()
    }
}
