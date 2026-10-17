-- =============================================================================
-- V2__indexes_and_constraints.sql
-- Performance Indexes, Multi-Tenant Composite Indexes, Spatial GiST Index,
-- and Domain Integrity CHECK Constraints for DynaShuttle Schema.
-- =============================================================================

-- =============================================================================
-- 1. FOREIGN KEY INDEXES
-- PostgreSQL does not automatically create indexes on foreign key columns.
-- These eliminate table-scan overhead for joins and tenant lookups.
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_employees_company_id
    ON employees(company_id);

CREATE INDEX IF NOT EXISTS idx_drivers_company_id
    ON drivers(company_id);

CREATE INDEX IF NOT EXISTS idx_vehicles_company_id
    ON vehicles(company_id);

CREATE INDEX IF NOT EXISTS idx_admins_company_id
    ON admins(company_id);

CREATE INDEX IF NOT EXISTS idx_trips_vehicle_id
    ON trips(vehicle_id);

CREATE INDEX IF NOT EXISTS idx_trips_driver_id
    ON trips(driver_id);

CREATE INDEX IF NOT EXISTS idx_ride_requests_trip_id
    ON ride_requests(trip_id);

CREATE INDEX IF NOT EXISTS idx_ride_requests_employee_id
    ON ride_requests(employee_id);


-- =============================================================================
-- 2. QUERY-SPECIFIC & MULTI-TENANT COMPOSITE INDEXES
-- Optimized for current service access patterns:
-- - Active trip checks per vehicle in RoutingService / findAvailableNear
-- - Active passenger manifest queries by trip and status in TripService
-- =============================================================================

-- Accelerates: NOT EXISTS (SELECT 1 FROM trips t WHERE t.vehicle_id = v.id AND t.status IN ('SCHEDULED', 'IN_PROGRESS'))
CREATE INDEX IF NOT EXISTS idx_trips_vehicle_status
    ON trips(vehicle_id, status);

-- Accelerates: SELECT ... FROM ride_requests WHERE trip_id = ? AND status IN ('MATCHED', 'PICKED_UP')
CREATE INDEX IF NOT EXISTS idx_ride_requests_trip_status
    ON ride_requests(trip_id, status);

-- Accelerates: Employee active ride lookup
CREATE INDEX IF NOT EXISTS idx_ride_requests_employee_status
    ON ride_requests(employee_id, status);


-- =============================================================================
-- 3. POSTGIS SPATIAL GIST INDEX
-- Indexes vehicle real-time locations for sub-millisecond ST_DWithin execution
-- without modifying the existing double precision column representation.
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_vehicles_location_gist
    ON vehicles USING GIST (CAST(ST_MakePoint(last_known_lng, last_known_lat) AS geography));


-- =============================================================================
-- 4. DOMAIN INTEGRITY CHECK CONSTRAINTS
-- Enforce coordinate boundaries, positive capacities, and valid status values.
-- Wrapped in idempotent blocks to guarantee safe re-runs.
-- =============================================================================

DO $$
BEGIN
    -- Companies
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_companies_employee_count') THEN
        ALTER TABLE companies ADD CONSTRAINT chk_companies_employee_count
            CHECK (employee_count >= 0);
    END IF;

    -- Employees coordinates
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_employees_home_lat') THEN
        ALTER TABLE employees ADD CONSTRAINT chk_employees_home_lat
            CHECK (home_lat >= -90.0 AND home_lat <= 90.0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_employees_home_lng') THEN
        ALTER TABLE employees ADD CONSTRAINT chk_employees_home_lng
            CHECK (home_lng >= -180.0 AND home_lng <= 180.0);
    END IF;

    -- Vehicles capacity and coordinates
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_vehicles_seat_capacity') THEN
        ALTER TABLE vehicles ADD CONSTRAINT chk_vehicles_seat_capacity
            CHECK (seat_capacity > 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_vehicles_last_known_lat') THEN
        ALTER TABLE vehicles ADD CONSTRAINT chk_vehicles_last_known_lat
            CHECK (last_known_lat IS NULL OR (last_known_lat >= -90.0 AND last_known_lat <= 90.0));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_vehicles_last_known_lng') THEN
        ALTER TABLE vehicles ADD CONSTRAINT chk_vehicles_last_known_lng
            CHECK (last_known_lng IS NULL OR (last_known_lng >= -180.0 AND last_known_lng <= 180.0));
    END IF;

    -- Trips status enum check
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_trips_status') THEN
        ALTER TABLE trips ADD CONSTRAINT chk_trips_status
            CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'));
    END IF;

    -- Ride Requests coordinates and status enum check
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_ride_requests_pickup_lat') THEN
        ALTER TABLE ride_requests ADD CONSTRAINT chk_ride_requests_pickup_lat
            CHECK (pickup_lat >= -90.0 AND pickup_lat <= 90.0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_ride_requests_pickup_lng') THEN
        ALTER TABLE ride_requests ADD CONSTRAINT chk_ride_requests_pickup_lng
            CHECK (pickup_lng >= -180.0 AND pickup_lng <= 180.0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_ride_requests_dest_lat') THEN
        ALTER TABLE ride_requests ADD CONSTRAINT chk_ride_requests_dest_lat
            CHECK (destination_lat >= -90.0 AND destination_lat <= 90.0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_ride_requests_dest_lng') THEN
        ALTER TABLE ride_requests ADD CONSTRAINT chk_ride_requests_dest_lng
            CHECK (destination_lng >= -180.0 AND destination_lng <= 180.0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_ride_requests_status') THEN
        ALTER TABLE ride_requests ADD CONSTRAINT chk_ride_requests_status
            CHECK (status IN ('REQUESTED', 'MATCHED', 'PICKED_UP', 'COMPLETED', 'NO_SHOW', 'QUEUED'));
    END IF;
END $$;
