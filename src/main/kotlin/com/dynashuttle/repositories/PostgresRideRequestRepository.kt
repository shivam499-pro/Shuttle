package com.dynashuttle.repositories

import com.dynashuttle.models.Employees
import com.dynashuttle.models.RideRequestDto
import com.dynashuttle.models.RideRequests
import com.dynashuttle.models.RideStatus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.sql.Connection
import java.time.Instant
import java.util.UUID

class PostgresRideRequestRepository : RideRequestRepository {

    override fun allocateSeat(request: RideRequestDto, tripId: String, companyId: String?): AllocationResult = transaction {
        val tripUuid = runCatching { UUID.fromString(tripId) }.getOrNull()
            ?: return@transaction AllocationResult.TripNotFound

        val empUuid = runCatching { UUID.fromString(request.employeeId) }.getOrNull()
            ?: return@transaction AllocationResult.TripNotFound

        val companyUuid = companyId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (companyId != null && companyUuid == null) {
            return@transaction AllocationResult.TripNotFound
        }

        // 1. Lock the specific trip row using SELECT ... FOR UPDATE OF t.
        // Joining vehicles gives the authoritative seat_capacity and company_id.
        val jdbcConn = this.connection.connection as Connection
        val lockSql = """
            SELECT
                t.id,
                t.status,
                v.seat_capacity,
                v.company_id
            FROM trips t
            JOIN vehicles v ON v.id = t.vehicle_id
            WHERE t.id = ?
            FOR UPDATE OF t
        """.trimIndent()

        var seatCapacity = 0
        var tripStatus = ""
        var tripCompanyUuid: UUID? = null
        var tripFound = false

        jdbcConn.prepareStatement(lockSql).use { ps ->
            ps.setObject(1, tripUuid)
            val rs = ps.executeQuery()
            if (rs.next()) {
                tripFound = true
                tripStatus = rs.getString("status")
                seatCapacity = rs.getInt("seat_capacity")
                tripCompanyUuid = rs.getObject("company_id") as? UUID
            }
        }

        if (!tripFound) {
            return@transaction AllocationResult.TripNotFound
        }

        // Trip must be actively in progress
        if (tripStatus != "IN_PROGRESS") {
            return@transaction AllocationResult.TripNotFound
        }

        // Company isolation check
        if (companyUuid != null && tripCompanyUuid != companyUuid) {
            return@transaction AllocationResult.TripNotFound
        }

        // 2. Count active passengers holding seats on this trip while holding the row lock
        val countSql = """
            SELECT COUNT(*)
            FROM ride_requests
            WHERE trip_id = ?
              AND status IN ('MATCHED', 'PICKUP_PENDING', 'ONBOARD')
        """.trimIndent()

        var activeCount = 0
        jdbcConn.prepareStatement(countSql).use { ps ->
            ps.setObject(1, tripUuid)
            val rs = ps.executeQuery()
            if (rs.next()) {
                activeCount = rs.getInt(1)
            }
        }

        // 3. Enforce capacity invariant: active passenger count < vehicle seat capacity
        if (activeCount >= seatCapacity) {
            return@transaction AllocationResult.CapacityExhausted
        }

        // 4. Atomically insert the ride request as MATCHED while holding the row lock
        val newId = UUID.randomUUID()
        val zoneUuid = request.pickupZoneId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        RideRequests.insert {
            it[id] = newId
            it[employeeId] = empUuid
            it[RideRequests.tripId] = tripUuid
            it[pickupLat] = request.pickupLat
            it[pickupLng] = request.pickupLng
            it[destinationLat] = request.destinationLat
            it[destinationLng] = request.destinationLng
            it[RideRequests.status] = "MATCHED"
            it[requestedAt] = Instant.now()
            it[pickupZoneId] = zoneUuid
        }

        val empCompanyId = Employees
            .selectAll()
            .where { Employees.id eq empUuid }
            .map { it[Employees.companyId].value.toString() }
            .singleOrNull()

        AllocationResult.Allocated(
            StoredRideRequest(
                id = newId.toString(),
                tripId = tripId,
                status = "MATCHED",
                pickupLat = request.pickupLat,
                pickupLng = request.pickupLng,
                employeeId = request.employeeId,
                companyId = empCompanyId,
                pickupZoneId = request.pickupZoneId
            )
        )
    }

    override fun save(request: RideRequestDto, tripId: String?, status: String): StoredRideRequest = transaction {
        val newId = UUID.randomUUID()
        val empUuid = UUID.fromString(request.employeeId)
        val zoneUuid = request.pickupZoneId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        RideRequests.insert {
            it[id] = newId
            it[employeeId] = empUuid
            it[RideRequests.tripId] = tripId?.let { t -> UUID.fromString(t) }
            it[pickupLat] = request.pickupLat
            it[pickupLng] = request.pickupLng
            it[destinationLat] = request.destinationLat
            it[destinationLng] = request.destinationLng
            it[RideRequests.status] = status
            it[requestedAt] = Instant.now()
            it[pickupZoneId] = zoneUuid
        }

        val companyId = Employees
            .selectAll()
            .where { Employees.id eq empUuid }
            .map { it[Employees.companyId].value.toString() }
            .singleOrNull()

        StoredRideRequest(
            id = newId.toString(),
            tripId = tripId,
            status = status,
            pickupLat = request.pickupLat,
            pickupLng = request.pickupLng,
            employeeId = request.employeeId,
            companyId = companyId,
            pickupZoneId = request.pickupZoneId
        )
    }

    override fun findById(id: String): StoredRideRequest? = transaction {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@transaction null
        (RideRequests innerJoin Employees)
            .selectAll()
            .where { RideRequests.id eq uuid }
            .map {
                val rawStatus = it[RideRequests.status]
                val normalisedStatus = RideStatus.fromString(rawStatus).name
                StoredRideRequest(
                    id = it[RideRequests.id].value.toString(),
                    tripId = it[RideRequests.tripId]?.value?.toString(),
                    status = normalisedStatus,
                    pickupLat = it[RideRequests.pickupLat],
                    pickupLng = it[RideRequests.pickupLng],
                    employeeId = it[RideRequests.employeeId].value.toString(),
                    companyId = it[Employees.companyId].value.toString(),
                    pickupZoneId = it[RideRequests.pickupZoneId]?.value?.toString()
                )
            }
            .singleOrNull()
    }

    override fun updateStatus(id: String, status: String, tripId: String?) {
        transaction {
            val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@transaction
            RideRequests.update({ RideRequests.id eq uuid }) {
                it[RideRequests.status] = status
                if (tripId != null) it[RideRequests.tripId] = UUID.fromString(tripId)
            }
        }
    }

    override fun findActiveByTripId(tripId: String): List<ActiveTripStop> = transaction {
        val tripUuid = runCatching { UUID.fromString(tripId) }.getOrNull() ?: return@transaction emptyList()
        (RideRequests innerJoin Employees)
            .selectAll()
            .where {
                (RideRequests.tripId eq tripUuid) and
                        (RideRequests.status inList listOf("MATCHED", "PICKUP_PENDING"))
            }
            .map {
                ActiveTripStop(
                    rideRequestId = it[RideRequests.id].value.toString(),
                    employeeName = it[Employees.name],
                    pickupLat = it[RideRequests.pickupLat],
                    pickupLng = it[RideRequests.pickupLng],
                    employeeId = it[RideRequests.employeeId].value.toString()
                )
            }
    }

    override fun hasActivePassenger(tripId: String, employeeId: String): Boolean = transaction {
        val tripUuid = runCatching { UUID.fromString(tripId) }.getOrNull() ?: return@transaction false
        val empUuid = runCatching { UUID.fromString(employeeId) }.getOrNull() ?: return@transaction false
        RideRequests
            .selectAll()
            .where {
                (RideRequests.tripId eq tripUuid) and
                (RideRequests.employeeId eq empUuid) and
                (RideRequests.status inList listOf("MATCHED", "PICKUP_PENDING", "ONBOARD"))
            }
            .count() > 0
    }

    override fun countActiveRiders(tripId: String): Int = transaction {
        val tripUuid = runCatching { UUID.fromString(tripId) }.getOrNull() ?: return@transaction 0
        RideRequests
            .selectAll()
            .where {
                (RideRequests.tripId eq tripUuid) and
                (RideRequests.status inList listOf("MATCHED", "PICKUP_PENDING", "ONBOARD"))
            }
            .count()
            .toInt()
    }

    /**
     * Atomically transitions a ride request's status using a single conditional UPDATE:
     *   UPDATE ride_requests SET status = ? WHERE id = ? AND status = ?
     * If 0 rows are affected, a follow-up SELECT determines whether the row is missing
     * or simply in a different state (concurrent transition already happened).
     * This is race-condition-safe: no separate SELECT-then-UPDATE.
     */
    override fun transitionStatus(
        id: String,
        expectedStatus: RideStatus,
        newStatus: RideStatus,
        tripId: String?
    ): TransitionResult = transaction {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull()
            ?: return@transaction TransitionResult.NotFound

        val jdbcConn = this.connection.connection as Connection

        val updateSql = if (tripId != null) {
            "UPDATE ride_requests SET status = ?, trip_id = ? WHERE id = ? AND status = ?"
        } else {
            "UPDATE ride_requests SET status = ? WHERE id = ? AND status = ?"
        }

        val rowsUpdated: Int = jdbcConn.prepareStatement(updateSql).use { ps ->
            if (tripId != null) {
                val tripUuid = runCatching { UUID.fromString(tripId) }.getOrNull()
                    ?: return@transaction TransitionResult.NotFound
                ps.setString(1, newStatus.name)
                ps.setObject(2, tripUuid)
                ps.setObject(3, uuid)
                ps.setString(4, expectedStatus.name)
            } else {
                ps.setString(1, newStatus.name)
                ps.setObject(2, uuid)
                ps.setString(3, expectedStatus.name)
            }
            ps.executeUpdate()
        }

        if (rowsUpdated == 1) {
            return@transaction TransitionResult.Success
        }

        // 0 rows affected — distinguish not-found vs state-mismatch
        val existsSql = "SELECT COUNT(*) FROM ride_requests WHERE id = ?"
        val exists: Boolean = jdbcConn.prepareStatement(existsSql).use { ps ->
            ps.setObject(1, uuid)
            val rs = ps.executeQuery()
            rs.next() && rs.getInt(1) > 0
        }

        if (exists) TransitionResult.StateMismatch else TransitionResult.NotFound
    }
}