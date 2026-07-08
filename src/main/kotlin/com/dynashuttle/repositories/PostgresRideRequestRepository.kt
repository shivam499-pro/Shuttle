package com.dynashuttle.repositories

import com.dynashuttle.models.RideRequestDto
import com.dynashuttle.models.RideRequests
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

class PostgresRideRequestRepository : RideRequestRepository {

    override fun save(request: RideRequestDto, tripId: String?, status: String): StoredRideRequest = transaction {
        val newId = UUID.randomUUID()

        RideRequests.insert {
            it[id] = newId
            it[employeeId] = UUID.fromString(request.employeeId)
            it[RideRequests.tripId] = tripId?.let { t -> UUID.fromString(t) }
            it[pickupLat] = request.pickupLat
            it[pickupLng] = request.pickupLng
            it[destinationLat] = request.destinationLat
            it[destinationLng] = request.destinationLng
            it[RideRequests.status] = status
            it[requestedAt] = Instant.now()
        }

        StoredRideRequest(
            id = newId.toString(),
            tripId = tripId,
            status = status,
            pickupLat = request.pickupLat,
            pickupLng = request.pickupLng
        )
    }

    override fun findById(id: String): StoredRideRequest? = transaction {
        RideRequests
            .selectAll()
            .where { RideRequests.id eq UUID.fromString(id) }
            .map {
                StoredRideRequest(
                    id = it[RideRequests.id].toString(),
                    tripId = it[RideRequests.tripId]?.toString(),
                    status = it[RideRequests.status],
                    pickupLat = it[RideRequests.pickupLat],
                    pickupLng = it[RideRequests.pickupLng]
                )
            }
            .singleOrNull()
    }

    override fun updateStatus(id: String, status: String, tripId: String?) {
        transaction {
            RideRequests.update({ RideRequests.id eq UUID.fromString(id) }) {
                it[RideRequests.status] = status
                if (tripId != null) it[RideRequests.tripId] = UUID.fromString(tripId)
            }
        }
    }
}
