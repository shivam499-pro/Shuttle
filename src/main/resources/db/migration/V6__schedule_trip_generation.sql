-- =============================================================================
-- V6__schedule_trip_generation.sql
-- Enables generation of concrete Trips from recurring ShuttleSchedules.
--
-- Rules:
--   1. Adds schedule_id to trips referencing shuttle_schedules(id).
--   2. Adds trip_date (DATE NOT NULL).
--   3. Makes vehicle_id and driver_id nullable to support scheduled trips
--      existing prior to operational vehicle/driver assignment.
--   4. Adds a UNIQUE constraint on (schedule_id, trip_date) preventing duplicate
--      trip generation for the same schedule on the same calendar date.
--   5. Adds indexes on schedule_id and trip_date.
--   6. Safe on existing databases: backfills any pre-existing unlinked trips
--      with a dedicated system schedule before setting NOT NULL.
--   7. Backwards compatible: installs a trigger to assign a default schedule
--      and date if legacy seed scripts insert without schedule_id or trip_date.
-- =============================================================================

-- 1. Add columns (nullable initially to allow safe backfill of existing rows)
ALTER TABLE trips
    ADD COLUMN IF NOT EXISTS schedule_id UUID REFERENCES shuttle_schedules(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS trip_date DATE;

-- 2. Backfill existing trips if any exist without schedule_id / trip_date using dedicated system records
DO $$
DECLARE
    sys_company_id CONSTANT UUID := '00000000-0000-0000-0000-000000000000'::uuid;
    sys_schedule_id CONSTANT UUID := '00000000-0000-0000-0000-000000000001'::uuid;
    needs_backfill BOOLEAN;
BEGIN
    SELECT EXISTS (SELECT 1 FROM trips WHERE schedule_id IS NULL OR trip_date IS NULL) INTO needs_backfill;
    IF needs_backfill THEN
        INSERT INTO companies (id, name, city, employee_count, created_at)
        VALUES (sys_company_id, 'System Fallback Company', 'System', 0, NOW())
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO shuttle_schedules (id, company_id, name, direction, departure_time, days_of_week, active, created_at, updated_at)
        VALUES (
            sys_schedule_id,
            sys_company_id,
            'System Default Schedule',
            'INBOUND',
            '08:00:00'::time,
            'MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY',
            true,
            NOW(),
            NOW()
        )
        ON CONFLICT (id) DO NOTHING;

        UPDATE trips
        SET schedule_id = COALESCE(schedule_id, sys_schedule_id),
            trip_date = COALESCE(trip_date, start_time::date, CURRENT_DATE)
        WHERE schedule_id IS NULL OR trip_date IS NULL;
    END IF;
END $$;

-- 3. Enforce NOT NULL on schedule_id and trip_date, and DROP NOT NULL on vehicle_id and driver_id
ALTER TABLE trips
    ALTER COLUMN schedule_id SET NOT NULL,
    ALTER COLUMN trip_date SET NOT NULL,
    ALTER COLUMN vehicle_id DROP NOT NULL,
    ALTER COLUMN driver_id DROP NOT NULL;

-- 4. Trigger for legacy inserts that don't supply schedule_id or trip_date
CREATE OR REPLACE FUNCTION trg_trips_default_schedule()
RETURNS TRIGGER AS $$
DECLARE
    sys_company_id CONSTANT UUID := '00000000-0000-0000-0000-000000000000'::uuid;
    sys_schedule_id CONSTANT UUID := '00000000-0000-0000-0000-000000000001'::uuid;
BEGIN
    IF NEW.trip_date IS NULL THEN
        NEW.trip_date := CURRENT_DATE;
    END IF;

    IF NEW.schedule_id IS NULL THEN
        INSERT INTO companies (id, name, city, employee_count, created_at)
        VALUES (sys_company_id, 'System Fallback Company', 'System', 0, NOW())
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO shuttle_schedules (id, company_id, name, direction, departure_time, days_of_week, active, created_at, updated_at)
        VALUES (
            sys_schedule_id,
            sys_company_id,
            'System Default Schedule',
            'INBOUND',
            '08:00:00'::time,
            'MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY',
            true,
            NOW(),
            NOW()
        )
        ON CONFLICT (id) DO NOTHING;

        NEW.schedule_id := sys_schedule_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_trips_default_schedule ON trips;
CREATE TRIGGER trg_trips_default_schedule
    BEFORE INSERT ON trips
    FOR EACH ROW
    WHEN (NEW.schedule_id IS NULL OR NEW.trip_date IS NULL)
    EXECUTE FUNCTION trg_trips_default_schedule();

-- 5. Uniqueness constraint: (schedule_id, trip_date)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_trips_schedule_date') THEN
        ALTER TABLE trips ADD CONSTRAINT uq_trips_schedule_date
            UNIQUE (schedule_id, trip_date);
    END IF;
END $$;

-- 6. Indexes for lookup and date-range queries
CREATE INDEX IF NOT EXISTS idx_trips_schedule_id
    ON trips(schedule_id);

CREATE INDEX IF NOT EXISTS idx_trips_trip_date
    ON trips(trip_date);
