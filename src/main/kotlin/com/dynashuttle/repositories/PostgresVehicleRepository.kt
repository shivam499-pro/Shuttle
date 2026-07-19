package com.dynashuttle.repositories

import com.dynashuttle.models.Trips
import com.dynashuttle.models.Vehicles
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Postgres-backed implementation of VehicleRepository.
 *
 * ## Spatial query strategy (findAvailableNear)
 * Exposed 0.50.1 has no first-class support for PostGIS functions such as
 * ST_DWithin or ST_MakePoint. Rather than vendor-patching the DSL or pulling
 * in a third-party Exposed spatial extension, we drop to a raw JDBC
 * PreparedStatement for this one method where PostGIS is needed.
 * All other methods continue to use the Exposed DSL.
 * All SQL parameters are bound via '?' placeholders — no string concatenation
 * of user input — so there is no SQL-injection risk.
 *
 * Prerequisite: run `CREATE EXTENSION IF NOT EXISTS postgis;` on the target
 * database once (see findAvailableNear's KDoc below).
 */
class PostgresVehicleRepository : VehicleRepository {

    /**
     * Returns vehicles on an IN_PROGRESS trip within [radiusMeters] of ([lat], [lng])
     * that have reported a GPS position, ordered nearest-first.
     *
     * **Database prerequisite**: PostGIS must be enabled on the target database.
     * Run this once as a superuser (or an account with CREATE EXTENSION privilege):
     * ```sql
     * CREATE EXTENSION IF NOT EXISTS postgis;
     * ```
     * ST_DWithin with geography inputs interprets the radius in metres, which is
     * exactly what our interface contract promises. The GIST index on (last_known_lat,
     * last_known_lng) added by a future migration will make this sub-millisecond at
     * scale; the query is correct without it, just slower on large tables.
     */
    override fun findAvailableNear(lat: Double, lng: Double, radiusMeters: Int): List<VehicleCandidate> =
        transaction {
            // Exposed 0.50.1 has no ST_DWithin/ST_MakePoint DSL support, so we use
            // the underlying JDBC connection directly for this one spatial query.
            // All parameters are bound via '?' — no string concatenation of user input.
            val sql = """
                SELECT
                    v.id         AS vehicle_id,
                    t.id         AS trip_id,
                    t.driver_id  AS driver_id,
                    v.seat_capacity,
                    v.last_known_lat,
                    v.last_known_lng
                FROM trips t
                JOIN vehicles v ON v.id = t.vehicle_id
                WHERE t.status = 'IN_PROGRESS'
                  AND v.last_known_lat IS NOT NULL
                  AND v.last_known_lng IS NOT NULL
                  AND ST_DWithin(
                        ST_MakePoint(v.last_known_lng, v.last_known_lat)::geography,
                        ST_MakePoint(?, ?)::geography,
                        ?
                  )
                ORDER BY
                    ST_Distance(
                        ST_MakePoint(v.last_known_lng, v.last_known_lat)::geography,
                        ST_MakePoint(?, ?)::geography
                    )
            """.trimIndent()

            // Exposed exposes the raw JDBC connection via this.connection.connection
            val jdbcConn = this.connection.connection as Connection
            jdbcConn.prepareStatement(sql).use { ps ->
                ps.setDouble(1, lng)   // ST_MakePoint(lng, lat) — PostGIS is (x=lng, y=lat)
                ps.setDouble(2, lat)
                ps.setInt(3, radiusMeters)
                ps.setDouble(4, lng)   // ORDER BY ST_Distance arguments mirror WHERE clause
                ps.setDouble(5, lat)

                val rs = ps.executeQuery()
                val results = mutableListOf<VehicleCandidate>()
                while (rs.next()) {
                    results += VehicleCandidate(
                        vehicleId    = rs.getString("vehicle_id"),
                        tripId       = rs.getString("trip_id"),
                        driverId     = rs.getString("driver_id"),
                        currentLat   = rs.getDouble("last_known_lat"),
                        currentLng   = rs.getDouble("last_known_lng"),
                        availableSeats = rs.getInt("seat_capacity")
                    )
                }
                results
            }
        }

    override fun updateLocation(vehicleId: String, lat: Double, lng: Double) {
        transaction {
            Vehicles.update({ Vehicles.id eq UUID.fromString(vehicleId) }) {
                it[lastKnownLat] = lat
                it[lastKnownLng] = lng
                it[lastLocationUpdatedAt] = Instant.now()
            }
        }
    }

    /**
     * Looks up the vehicle assigned to [tripId] via the Trips table's vehicle_id FK.
     * Used by the WebSocket GPS handler to resolve a vehicleId from the tripId
     * carried in each [com.dynashuttle.models.DriverLocationUpdate] frame, keeping
     * all Exposed/database logic inside the repository layer.
     */
    override fun findVehicleIdByTripId(tripId: String): String? = transaction {
        Trips
            .selectAll()
            .where { Trips.id eq UUID.fromString(tripId) }
            .map { it[Trips.vehicleId].toString() }
            .singleOrNull()
    }

    /**
     * Joins Trips -> Vehicles to get the assigned vehicle's latest reported position.
     * Returns null only when [tripId] doesn't exist at all; a trip whose vehicle hasn't
     * reported GPS yet still returns a [VehicleLocation] with null lat/lng (see KDoc on
     * the interface for why that distinction matters to callers).
     */
    override fun findVehicleLocationByTripId(tripId: String): VehicleLocation? = transaction {
        (Trips innerJoin Vehicles)
            .selectAll()
            .where { Trips.id eq UUID.fromString(tripId) }
            .map {
                VehicleLocation(
                    vehicleId = it[Vehicles.id].toString(),
                    lat = it[Vehicles.lastKnownLat],
                    lng = it[Vehicles.lastKnownLng]
                )
            }
            .singleOrNull()
    }
}