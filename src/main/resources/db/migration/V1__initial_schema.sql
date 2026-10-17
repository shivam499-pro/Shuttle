-- =============================================================================
-- V1__initial_schema.sql
-- Baseline schema representing the current 7-table DynaShuttle domain:
--   companies, employees, drivers, vehicles, trips, ride_requests, admins
-- Lacks pickup_zones, shuttle_schedules, waitlists, etc. (deferred to future phases).
-- =============================================================================

-- Enable PostGIS extension for spatial distance & range queries (ST_DWithin, ST_MakePoint).
-- Note: In managed cloud PostgreSQL (e.g. AWS RDS, Neon, Supabase), PostGIS is available
-- by default but may require database administrator privileges to enable.
CREATE EXTENSION IF NOT EXISTS postgis;

-- 1. Companies (Tenants)
CREATE TABLE IF NOT EXISTS companies (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL,
    employee_count INTEGER DEFAULT 0 NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

-- 2. Employees (Corporate Commuters)
CREATE TABLE IF NOT EXISTS employees (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    home_lat DOUBLE PRECISION NOT NULL,
    home_lng DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

-- 3. Drivers (Fleet Operators)
CREATE TABLE IF NOT EXISTS drivers (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    name VARCHAR(255) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

-- 4. Vehicles (Fleet Shuttles / Vans)
CREATE TABLE IF NOT EXISTS vehicles (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    plate_number VARCHAR(20) NOT NULL,
    seat_capacity INTEGER NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    last_known_lat DOUBLE PRECISION,
    last_known_lng DOUBLE PRECISION,
    last_location_updated_at TIMESTAMP WITHOUT TIME ZONE
);

-- 5. Trips (Active or Scheduled Shuttle Runs)
CREATE TABLE IF NOT EXISTS trips (
    id UUID PRIMARY KEY,
    vehicle_id UUID NOT NULL REFERENCES vehicles(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    driver_id UUID NOT NULL REFERENCES drivers(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    start_time TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    status VARCHAR(20) DEFAULT 'SCHEDULED' NOT NULL
);

-- 6. Ride Requests (Individual Employee Booking / Commute Requests)
CREATE TABLE IF NOT EXISTS ride_requests (
    id UUID PRIMARY KEY,
    trip_id UUID REFERENCES trips(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    employee_id UUID NOT NULL REFERENCES employees(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    pickup_lat DOUBLE PRECISION NOT NULL,
    pickup_lng DOUBLE PRECISION NOT NULL,
    destination_lat DOUBLE PRECISION NOT NULL,
    destination_lng DOUBLE PRECISION NOT NULL,
    status VARCHAR(20) DEFAULT 'REQUESTED' NOT NULL,
    requested_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

-- 7. Admins (Corporate Shuttle Portal Administrators)
CREATE TABLE IF NOT EXISTS admins (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);
