-- V8: Trip lifecycle state machine extension
-- Extends chk_trips_status check constraint to include ASSIGNED, BOARDING, and ABORTED
-- Supports Task 8.3 production trip lifecycle: SCHEDULED -> ASSIGNED -> BOARDING -> IN_PROGRESS -> COMPLETED

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_trips_status') THEN
        ALTER TABLE trips DROP CONSTRAINT chk_trips_status;
    END IF;

    ALTER TABLE trips ADD CONSTRAINT chk_trips_status
        CHECK (status IN (
            'SCHEDULED',
            'ASSIGNED',
            'BOARDING',
            'IN_PROGRESS',
            'COMPLETED',
            'CANCELLED',
            'ABORTED'
        ));
END $$;
