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
        findAvailableNear(lat, lng, radiusMeters, null)

    override fun findAvailableNear(lat: Double, lng: Double, radiusMeters: Int, companyId: String?): List<VehicleCandidate> =
        transaction {
            val companyUuid = companyId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (companyId != null && companyUuid == null) {
                // If a non-null companyId is provided but is invalid UUID, return empty list
                return@transaction emptyList()
            }

            val companyClause = if (companyUuid != null) "AND v.company_id = ?" else ""

            // Exposed 0.50.1 has no ST_DWithin/ST_MakePoint DSL support, so we use
            // the underlying JDBC connection directly for this one spatial query.
            // All parameters are bound via '?' — no string concatenation of user input.
            val sql = """
                SELECT
                    v.id         AS vehicle_id,
                    t.id         AS trip_id,
                    t.driver_id  AS driver_id,
                    v.seat_capacity,
                    (
                        v.seat_capacity - COALESCE((
                            SELECT COUNT(*)
                            FROM ride_requests rr
                            WHERE rr.trip_id = t.id
                              AND rr.status IN ('MATCHED', 'PICKUP_PENDING', 'ONBOARD')
                        ), 0)
                    )            AS available_seats,
                    v.last_known_lat,
                    v.last_known_lng,
                    v.company_id AS vehicle_company_id
                FROM trips t
                JOIN vehicles v ON v.id = t.vehicle_id
                WHERE t.status = 'IN_PROGRESS'
                  AND v.last_known_lat IS NOT NULL
                  AND v.last_known_lng IS NOT NULL
                  $companyClause
                  AND ST_DWithin(
                        ST_MakePoint(v.last_known_lng, v.last_known_lat)::geography,
                        ST_MakePoint(?, ?)::geography,
                        ?
                  )
                  AND (
                        v.seat_capacity - COALESCE((
                            SELECT COUNT(*)
                            FROM ride_requests rr
                            WHERE rr.trip_id = t.id
                              AND rr.status IN ('MATCHED', 'PICKUP_PENDING', 'ONBOARD')
                        ), 0)
                  ) > 0
                ORDER BY
                    ST_Distance(
                        ST_MakePoint(v.last_known_lng, v.last_known_lat)::geography,
                        ST_MakePoint(?, ?)::geography
                    )
            """.trimIndent()

            // Exposed exposes the raw JDBC connection via this.connection.connection
            val jdbcConn = this.connection.connection as Connection
            jdbcConn.prepareStatement(sql).use { ps ->
                var idx = 1
                if (companyUuid != null) {
                    ps.setObject(idx++, companyUuid)
                }
                ps.setDouble(idx++, lng)   // ST_MakePoint(lng, lat) — PostGIS is (x=lng, y=lat)
                ps.setDouble(idx++, lat)
                ps.setInt(idx++, radiusMeters)
                ps.setDouble(idx++, lng)   // ORDER BY ST_Distance arguments mirror WHERE clause
                ps.setDouble(idx, lat)

                val rs = ps.executeQuery()
                val results = mutableListOf<VehicleCandidate>()
                while (rs.next()) {
                    results += VehicleCandidate(
                        vehicleId    = rs.getString("vehicle_id"),
                        tripId       = rs.getString("trip_id"),
                        driverId     = rs.getString("driver_id"),
                        currentLat   = rs.getDouble("last_known_lat"),
                        currentLng   = rs.getDouble("last_known_lng"),
                        availableSeats = rs.getInt("available_seats"),
                        companyId    = rs.getString("vehicle_company_id")
                    )
                }
                results
            }
        }

    override fun updateLocation(vehicleId: String, lat: Double, lng: Double) {
        transaction {
            val uuid = runCatching { UUID.fromString(vehicleId) }.getOrNull() ?: return@transaction
            Vehicles.update({ Vehicles.id eq uuid }) {
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
        val uuid = runCatching { UUID.fromString(tripId) }.getOrNull() ?: return@transaction null
        Trips
            .selectAll()
            .where { Trips.id eq uuid }
            .map { it[Trips.vehicleId]?.value?.toString() }
            .singleOrNull()
    }

    /**
     * Resolves complete trip details including assigned vehicle, driver, and company ownership.
     */
    override fun findTripDetails(tripId: String): TripDetails? = transaction {
        val uuid = runCatching { UUID.fromString(tripId) }.getOrNull() ?: return@transaction null
        (Trips innerJoin Vehicles)
            .selectAll()
            .where { Trips.id eq uuid }
            .map {
                TripDetails(
                    tripId = it[Trips.id].value.toString(),
                    vehicleId = it[Trips.vehicleId]?.value?.toString() ?: "",
                    driverId = it[Trips.driverId]?.value?.toString() ?: "",
                    companyId = it[Vehicles.companyId].value.toString(),
                    status = it[Trips.status]
                )
            }
            .singleOrNull()
    }

    /**
     * Joins Trips -> Vehicles to get the assigned vehicle's latest reported position.
     * Returns null only when [tripId] doesn't exist at all; a trip whose vehicle hasn't
     * reported GPS yet still returns a [VehicleLocation] with null lat/lng (see KDoc on
     * the interface for why that distinction matters to callers).
     */
    override fun findVehicleLocationByTripId(tripId: String): VehicleLocation? = transaction {
        val uuid = runCatching { UUID.fromString(tripId) }.getOrNull() ?: return@transaction null
        (Trips innerJoin Vehicles)
            .selectAll()
            .where { Trips.id eq uuid }
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