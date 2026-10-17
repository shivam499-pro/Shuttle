package com.dynashuttle.repositories

import com.dynashuttle.models.ShuttleSchedules
import com.dynashuttle.models.Trip
import com.dynashuttle.models.TripStatus
import com.dynashuttle.models.Trips
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection
import java.time.LocalDate
import java.util.UUID

/**
 * PostgreSQL/Exposed implementation of [TripRepository].
 */
class PostgresTripRepository : TripRepository {

    override fun findById(tripId: String): Trip? = transaction {
        val uuid = runCatching { UUID.fromString(tripId) }.getOrNull() ?: return@transaction null
        Trips
            .selectAll()
            .where { Trips.id eq uuid }
            .map { toTrip(it) }
            .singleOrNull()
    }

    override fun findByScheduleAndDate(scheduleId: String, tripDate: LocalDate): Trip? = transaction {
        val schedUuid = runCatching { UUID.fromString(scheduleId) }.getOrNull() ?: return@transaction null
        Trips
            .selectAll()
            .where { (Trips.scheduleId eq schedUuid) and (Trips.tripDate eq tripDate) }
            .map { toTrip(it) }
            .singleOrNull()
    }

    override fun create(trip: Trip): Trip = transaction {
        val tripUuid = UUID.fromString(trip.id)
        val schedUuid = UUID.fromString(trip.scheduleId)
        val vehUuid = trip.vehicleId?.let { UUID.fromString(it) }
        val drvUuid = trip.driverId?.let { UUID.fromString(it) }

        val statement = Trips.insertIgnore {
            it[id] = tripUuid
            it[scheduleId] = schedUuid
            it[tripDate] = trip.tripDate
            it[vehicleId] = vehUuid
            it[driverId] = drvUuid
            it[startTime] = trip.startTime
            it[status] = trip.status
        }

        if (statement.insertedCount > 0) {
            trip
        } else {
            findByScheduleAndDate(trip.scheduleId, trip.tripDate)
                ?: error("Trip not found after insert conflict on schedule ${trip.scheduleId} and date ${trip.tripDate}")
        }
    }

    override fun findCompanyId(tripId: String): String? = transaction {
        val uuid = runCatching { UUID.fromString(tripId) }.getOrNull() ?: return@transaction null
        (Trips innerJoin ShuttleSchedules)
            .select(ShuttleSchedules.companyId)
            .where { Trips.id eq uuid }
            .map { it[ShuttleSchedules.companyId].value.toString() }
            .singleOrNull()
    }

    override fun updateStatus(
        tripId: String,
        expectedStatus: TripStatus,
        newStatus: TripStatus
    ): TransitionResult = transaction {
        val uuid = runCatching { UUID.fromString(tripId) }.getOrNull()
            ?: return@transaction TransitionResult.NotFound

        val jdbcConn = this.connection.connection as Connection
        val sql = "UPDATE trips SET status = ? WHERE id = ? AND status = ?"
        val rowsUpdated: Int = jdbcConn.prepareStatement(sql).use { ps ->
            ps.setString(1, newStatus.name)
            ps.setObject(2, uuid)
            ps.setString(3, expectedStatus.name)
            ps.executeUpdate()
        }

        if (rowsUpdated == 1) {
            return@transaction TransitionResult.Success
        }

        val existsSql = "SELECT COUNT(*) FROM trips WHERE id = ?"
        val exists: Boolean = jdbcConn.prepareStatement(existsSql).use { ps ->
            ps.setObject(1, uuid)
            val rs = ps.executeQuery()
            rs.next() && rs.getInt(1) > 0
        }

        if (exists) TransitionResult.StateMismatch else TransitionResult.NotFound
    }

    override fun assignVehicleAndDriver(
        tripId: String,
        vehicleId: String,
        driverId: String
    ): TransitionResult = transaction {
        val tripUuid = runCatching { UUID.fromString(tripId) }.getOrNull()
            ?: return@transaction TransitionResult.NotFound
        val vehUuid = runCatching { UUID.fromString(vehicleId) }.getOrNull()
            ?: return@transaction TransitionResult.NotFound
        val drvUuid = runCatching { UUID.fromString(driverId) }.getOrNull()
            ?: return@transaction TransitionResult.NotFound

        val jdbcConn = this.connection.connection as Connection
        val sql = "UPDATE trips SET vehicle_id = ?, driver_id = ?, status = 'ASSIGNED' WHERE id = ? AND status = 'SCHEDULED'"
        val rowsUpdated: Int = jdbcConn.prepareStatement(sql).use { ps ->
            ps.setObject(1, vehUuid)
            ps.setObject(2, drvUuid)
            ps.setObject(3, tripUuid)
            ps.executeUpdate()
        }

        if (rowsUpdated == 1) {
            return@transaction TransitionResult.Success
        }

        val existsSql = "SELECT COUNT(*) FROM trips WHERE id = ?"
        val exists: Boolean = jdbcConn.prepareStatement(existsSql).use { ps ->
            ps.setObject(1, tripUuid)
            val rs = ps.executeQuery()
            rs.next() && rs.getInt(1) > 0
        }

        if (exists) TransitionResult.StateMismatch else TransitionResult.NotFound
    }

    private fun toTrip(row: ResultRow): Trip = Trip(
        id = row[Trips.id].value.toString(),
        scheduleId = row[Trips.scheduleId].value.toString(),
        tripDate = row[Trips.tripDate],
        vehicleId = row[Trips.vehicleId]?.value?.toString(),
        driverId = row[Trips.driverId]?.value?.toString(),
        startTime = row[Trips.startTime],
        status = row[Trips.status]
    )
}
