-- =============================================================================
-- V3__ride_lifecycle_state_machine.sql
-- Introduces the full canonical ride-request lifecycle and normalises the
-- legacy QUEUED state to WAITLISTED.
--
-- Rules:
--   1. Safe on clean databases (no QUEUED rows yet).
--   2. Safe on databases already at V2 (QUEUED rows are normalised first).
--   3. Does NOT modify V1 or V2.
--   4. Drops the old chk_ride_requests_status constraint and replaces it
--      with one that contains only the canonical set of states.
-- =============================================================================

-- Step 1: Drop the old status CHECK constraint (from V2) if it exists,
-- so that updating legacy statuses to new canonical statuses (e.g. WAITLISTED)
-- does not violate the V2 constraint during migration.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_ride_requests_status') THEN
        ALTER TABLE ride_requests DROP CONSTRAINT chk_ride_requests_status;
    END IF;

    -- Step 2: Normalise legacy QUEUED rows to WAITLISTED.
    UPDATE ride_requests
    SET status = 'WAITLISTED'
    WHERE status = 'QUEUED';

    -- Also normalise any lingering PICKED_UP rows to PICKUP_PENDING.
    UPDATE ride_requests
    SET status = 'PICKUP_PENDING'
    WHERE status = 'PICKED_UP';

    -- Step 3: Add updated constraint with full canonical lifecycle states.
    -- QUEUED and PICKED_UP are intentionally excluded.
    ALTER TABLE ride_requests ADD CONSTRAINT chk_ride_requests_status
        CHECK (status IN (
            'REQUESTED',
            'MATCHED',
            'WAITLISTED',
            'PICKUP_PENDING',
            'ONBOARD',
            'COMPLETED',
            'CANCELLED',
            'NO_SHOW'
        ));
END $$;

