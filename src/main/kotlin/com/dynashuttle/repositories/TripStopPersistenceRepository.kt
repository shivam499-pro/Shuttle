package com.dynashuttle.repositories

import com.dynashuttle.models.StopStatus
import com.dynashuttle.models.StopType
import com.dynashuttle.models.StoredTripStop
import com.dynashuttle.models.TripStops
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

interface TripStopPersistenceRepository {
    fun findById(stopId: String): StoredTripStop?
    fun findByTripId(tripId: String): List<StoredTripStop>
    fun create(stop: StoredTripStop): StoredTripStop
    fun createBatch(stops: List<StoredTripStop>): List<StoredTripStop>
    fun transitionStopStatus(
        stopId: String,
        expectedStatus: StopStatus,
        newStatus: StopStatus,
        timestamp: Instant = Instant.now()
    ): TransitionResult
    fun countRemainingExecutableStops(tripId: String): Int
    fun countOnboardPassengers(tripId: String): Int
}

class PostgresTripStopPersistenceRepository : TripStopPersistenceRepository {

    override fun findById(stopId: String): StoredTripStop? = transaction {
        val uuid = runCatching { UUID.fromString(stopId) }.getOrNull() ?: return@transaction null
        TripStops
            .selectAll()
            .where { TripStops.id eq uuid }
            .map { toStoredTripStop(it) }
            .singleOrNull()
    }

    override fun findByTripId(tripId: String): List<StoredTripStop> = transaction {
        val tripUuid = runCatching { UUID.fromString(tripId) }.getOrNull() ?: return@transaction emptyList()
        TripStops
            .selectAll()
            .where { TripStops.tripId eq tripUuid }
            .orderBy(TripStops.sequenceOrder to SortOrder.ASC)
            .map { toStoredTripStop(it) }
    }

    override fun create(stop: StoredTripStop): StoredTripStop = transaction {
        val stopUuid = UUID.fromString(stop.id)
        val tripUuid = UUID.fromString(stop.tripId)
        val batchUuid = stop.batchId?.let { UUID.fromString(it) }
        val reqUuid = UUID.fromString(stop.rideRequestId)
        val compUuid = UUID.fromString(stop.companyId)

        TripStops.insert {
            it[id] = stopUuid
            it[tripId] = tripUuid
            it[batchId] = batchUuid
            it[rideRequestId] = reqUuid
            it[companyId] = compUuid
            it[stopType] = stop.stopType.name
            it[sequenceOrder] = stop.sequenceOrder
            it[latitude] = stop.latitude
            it[longitude] = stop.longitude
            it[status] = stop.status.name
            it[plannedEta] = stop.plannedEta
            it[actualArrival] = stop.actualArrival
            it[actualDeparture] = stop.actualDeparture
            it[createdAt] = stop.createdAt
            it[updatedAt] = stop.updatedAt
        }
        stop
    }

    override fun createBatch(stops: List<StoredTripStop>): List<StoredTripStop> = transaction {
        stops.map { create(it) }
    }

    override fun transitionStopStatus(
        stopId: String,
        expectedStatus: StopStatus,
        newStatus: StopStatus,
        timestamp: Instant
    ): TransitionResult = transaction {
        val uuid = runCatching { UUID.fromString(stopId) }.getOrNull()
            ?: return@transaction TransitionResult.NotFound

        val jdbcConn = this.connection.connection as Connection
        val sql = when (newStatus) {
            StopStatus.ARRIVED ->
                "UPDATE trip_stops SET status = ?, actual_arrival = ?, updated_at = ? WHERE id = ? AND status = ?"
            StopStatus.COMPLETED, StopStatus.SKIPPED ->
                "UPDATE trip_stops SET status = ?, actual_departure = ?, updated_at = ? WHERE id = ? AND status = ?"
            else ->
                "UPDATE trip_stops SET status = ?, updated_at = ? WHERE id = ? AND status = ?"
        }

        val rowsUpdated: Int = jdbcConn.prepareStatement(sql).use { ps ->
            val ts = Timestamp.from(timestamp)
            when (newStatus) {
                StopStatus.ARRIVED -> {
                    ps.setString(1, newStatus.name)
                    ps.setTimestamp(2, ts)
                    ps.setTimestamp(3, ts)
                    ps.setObject(4, uuid)
                    ps.setString(5, expectedStatus.name)
                }
                StopStatus.COMPLETED, StopStatus.SKIPPED -> {
                    ps.setString(1, newStatus.name)
                    ps.setTimestamp(2, ts)
                    ps.setTimestamp(3, ts)
                    ps.setObject(4, uuid)
                    ps.setString(5, expectedStatus.name)
                }
                else -> {
                    ps.setString(1, newStatus.name)
                    ps.setTimestamp(2, ts)
                    ps.setObject(3, uuid)
                    ps.setString(4, expectedStatus.name)
                }
            }
            ps.executeUpdate()
        }

        if (rowsUpdated == 1) {
            return@transaction TransitionResult.Success
        }

        val existsSql = "SELECT COUNT(*) FROM trip_stops WHERE id = ?"
        val exists = jdbcConn.prepareStatement(existsSql).use { ps ->
            ps.setObject(1, uuid)
            val rs = ps.executeQuery()
            rs.next() && rs.getInt(1) > 0
        }

        if (exists) TransitionResult.StateMismatch else TransitionResult.NotFound
    }

    override fun countRemainingExecutableStops(tripId: String): Int = transaction {
        val tripUuid = runCatching { UUID.fromString(tripId) }.getOrNull() ?: return@transaction 0
        val jdbcConn = this.connection.connection as Connection
        val sql = "SELECT COUNT(*) FROM trip_stops WHERE trip_id = ? AND status NOT IN ('COMPLETED', 'SKIPPED', 'CANCELLED')"
        jdbcConn.prepareStatement(sql).use { ps ->
            ps.setObject(1, tripUuid)
            val rs = ps.executeQuery()
            if (rs.next()) rs.getInt(1) else 0
        }
    }

    override fun countOnboardPassengers(tripId: String): Int = transaction {
        val tripUuid = runCatching { UUID.fromString(tripId) }.getOrNull() ?: return@transaction 0
        val jdbcConn = this.connection.connection as Connection
        val sql = "SELECT COUNT(*) FROM ride_requests WHERE trip_id = ? AND status = 'ONBOARD'"
        jdbcConn.prepareStatement(sql).use { ps ->
            ps.setObject(1, tripUuid)
            val rs = ps.executeQuery()
            if (rs.next()) rs.getInt(1) else 0
        }
    }

    private fun toStoredTripStop(row: ResultRow): StoredTripStop = StoredTripStop(
        id = row[TripStops.id].value.toString(),
        tripId = row[TripStops.tripId].value.toString(),
        batchId = row[TripStops.batchId]?.toString(),
        rideRequestId = row[TripStops.rideRequestId].value.toString(),
        companyId = row[TripStops.companyId].value.toString(),
        stopType = StopType.fromString(row[TripStops.stopType]),
        sequenceOrder = row[TripStops.sequenceOrder],
        latitude = row[TripStops.latitude],
        longitude = row[TripStops.longitude],
        status = StopStatus.fromString(row[TripStops.status]),
        plannedEta = row[TripStops.plannedEta],
        actualArrival = row[TripStops.actualArrival],
        actualDeparture = row[TripStops.actualDeparture],
        createdAt = row[TripStops.createdAt],
        updatedAt = row[TripStops.updatedAt]
    )
}
