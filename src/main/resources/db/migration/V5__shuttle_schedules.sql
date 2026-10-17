-- =============================================================================
-- V5__shuttle_schedules.sql
-- Introduces the ShuttleSchedule template domain.
--
-- A ShuttleSchedule is a recurring company-defined shuttle service definition.
-- It is NOT an operational trip. Trips are generated from schedules later.
--
-- Rules:
--   1. Safe on clean databases and existing V4 databases.
--   2. Does NOT modify V1, V2, V3, or V4.
--   3. days_of_week is stored as TEXT: a comma-separated deterministic list
--      of day identifiers (e.g. 'MONDAY,TUESDAY,WEDNESDAY'). Validation of
--      individual values is enforced at the service layer.
--   4. departure_time is stored as TIME WITHOUT TIME ZONE — a recurring local
--      time of day with no date component.
--   5. direction is a VARCHAR constrained to INBOUND or OUTBOUND.
-- =============================================================================

-- 1. Shuttle Schedules Table
CREATE TABLE IF NOT EXISTS shuttle_schedules (
    id              UUID PRIMARY KEY,
    company_id      UUID NOT NULL REFERENCES companies(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    name            VARCHAR(255) NOT NULL,
    direction       VARCHAR(20)  NOT NULL,
    departure_time  TIME WITHOUT TIME ZONE NOT NULL,
    days_of_week    TEXT         NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

-- 2. Tenant-aware indexes
CREATE INDEX IF NOT EXISTS idx_shuttle_schedules_company_id
    ON shuttle_schedules(company_id);

CREATE INDEX IF NOT EXISTS idx_shuttle_schedules_company_active
    ON shuttle_schedules(company_id, active);

-- 3. Domain integrity CHECK constraints
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_shuttle_schedules_name') THEN
        ALTER TABLE shuttle_schedules ADD CONSTRAINT chk_shuttle_schedules_name
            CHECK (length(trim(name)) > 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_shuttle_schedules_direction') THEN
        ALTER TABLE shuttle_schedules ADD CONSTRAINT chk_shuttle_schedules_direction
            CHECK (direction IN ('INBOUND', 'OUTBOUND'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_shuttle_schedules_days_of_week') THEN
        ALTER TABLE shuttle_schedules ADD CONSTRAINT chk_shuttle_schedules_days_of_week
            CHECK (length(trim(days_of_week)) > 0);
    END IF;
END $$;
