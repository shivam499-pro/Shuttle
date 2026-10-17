-- =============================================================================
-- V4__pickup_zones.sql
-- Introduces company-defined pickup zones and associates them with employees
-- and ride requests.
--
-- Rules:
--   1. Safe on clean databases and existing V3 databases.
--   2. Does NOT modify V1, V2, or V3.
--   3. Coordinates stored as DOUBLE PRECISION latitude / longitude (matching V1/V2).
--   4. Preserves backwards compatibility: pickup_zone_id is nullable on
--      employees and ride_requests.
-- =============================================================================

-- 1. Pickup Zones Table
CREATE TABLE IF NOT EXISTS pickup_zones (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    name VARCHAR(255) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    radius INTEGER NOT NULL,
    address VARCHAR(500),
    active BOOLEAN DEFAULT true NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

-- 2. Foreign Key & Query Indexes
CREATE INDEX IF NOT EXISTS idx_pickup_zones_company_id
    ON pickup_zones(company_id);

CREATE INDEX IF NOT EXISTS idx_pickup_zones_company_active
    ON pickup_zones(company_id, active);

-- 3. Additive Foreign Key Columns (Nullable for backwards compatibility)
ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS pickup_zone_id UUID REFERENCES pickup_zones(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_employees_pickup_zone_id
    ON employees(pickup_zone_id);

ALTER TABLE ride_requests
    ADD COLUMN IF NOT EXISTS pickup_zone_id UUID REFERENCES pickup_zones(id) ON UPDATE RESTRICT ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_ride_requests_pickup_zone_id
    ON ride_requests(pickup_zone_id);

-- 4. Domain Integrity CHECK Constraints
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_pickup_zones_latitude') THEN
        ALTER TABLE pickup_zones ADD CONSTRAINT chk_pickup_zones_latitude
            CHECK (latitude >= -90.0 AND latitude <= 90.0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_pickup_zones_longitude') THEN
        ALTER TABLE pickup_zones ADD CONSTRAINT chk_pickup_zones_longitude
            CHECK (longitude >= -180.0 AND longitude <= 180.0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_pickup_zones_radius') THEN
        ALTER TABLE pickup_zones ADD CONSTRAINT chk_pickup_zones_radius
            CHECK (radius > 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_pickup_zones_name') THEN
        ALTER TABLE pickup_zones ADD CONSTRAINT chk_pickup_zones_name
            CHECK (length(trim(name)) > 0);
    END IF;
END $$;
