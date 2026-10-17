-- =============================================================================
-- V7__dispatch_and_state_foundation.sql
-- Establishes the persistent schema foundation for the dynamic dispatch
-- and state model designed in Task 7.7:
--   1. dispatch_batches — batch lifecycle & window persistence
--   2. trip_stops — ordered pickup and dropoff stop sequence persistence
--   3. vehicle_operational_state — operational dispatch status & availability tracking
--   4. outbox_events — transactional outbox foundation for real-time dispatch events
--   5. ride_requests enhancements — batch_id, queued_at, expires_at columns & status constraint
--
-- Safety & Idempotency Rules:
--   - Safe on clean databases and existing databases (V1 through V6).
--   - Preserves all existing data and row counts.
--   - Does NOT drop or alter existing production data.
--   - Referential integrity enforced via foreign keys with ON UPDATE RESTRICT ON DELETE RESTRICT.
--   - Tenant isolation enforced via company_id foreign keys to companies(id).
-- =============================================================================

-- =============================================================================
-- 1. DISPATCH BATCHES TABLE
-- Tracks dynamic batch formation, 300s batch windows, locking, and assignment.
-- =============================================================================
CREATE TABLE IF NOT EXISTS dispatch_batches (
    id                  UUID PRIMARY KEY,
    company_id          UUID NOT NULL REFERENCES companies(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    schedule_id         UUID REFERENCES shuttle_schedules(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    trip_id             UUID REFERENCES trips(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    status              VARCHAR(20) DEFAULT 'OPEN' NOT NULL,
    direction           VARCHAR(10) NOT NULL,
    window_start_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    window_closes_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    dispatched_at       TIMESTAMP WITHOUT TIME ZONE,
    max_capacity        INTEGER NOT NULL,
    assigned_vehicle_id UUID REFERENCES vehicles(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    assigned_driver_id  UUID REFERENCES drivers(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    worker_id           VARCHAR(255),
    locked_at           TIMESTAMP WITHOUT TIME ZONE,
    retry_count         INTEGER DEFAULT 0 NOT NULL,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

-- Indexes for dispatch_batches
CREATE INDEX IF NOT EXISTS idx_dispatch_batches_company_id
    ON dispatch_batches(company_id);

CREATE INDEX IF NOT EXISTS idx_dispatch_batches_company_status_window
    ON dispatch_batches(company_id, status, window_closes_at);

CREATE INDEX IF NOT EXISTS idx_dispatch_batches_schedule_status
    ON dispatch_batches(schedule_id, status);

CREATE INDEX IF NOT EXISTS idx_dispatch_batches_assigned_vehicle_id
    ON dispatch_batches(assigned_vehicle_id);

CREATE INDEX IF NOT EXISTS idx_dispatch_batches_trip_id
    ON dispatch_batches(trip_id);

CREATE INDEX IF NOT EXISTS idx_dispatch_batches_status
    ON dispatch_batches(status);

-- Constraints for dispatch_batches
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_dispatch_batches_status') THEN
        ALTER TABLE dispatch_batches ADD CONSTRAINT chk_dispatch_batches_status
            CHECK (status IN (
                'OPEN', 'LOCKED', 'DISPATCHED', 'CLOSED',
                'COLLECTING', 'READY', 'ASSIGNING', 'OPTIMIZING',
                'EXECUTING', 'COMPLETED', 'CANCELLED'
            ));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_dispatch_batches_direction') THEN
        ALTER TABLE dispatch_batches ADD CONSTRAINT chk_dispatch_batches_direction
            CHECK (direction IN ('INBOUND', 'OUTBOUND'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_dispatch_batches_max_capacity') THEN
        ALTER TABLE dispatch_batches ADD CONSTRAINT chk_dispatch_batches_max_capacity
            CHECK (max_capacity > 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_dispatch_batches_retry_count') THEN
        ALTER TABLE dispatch_batches ADD CONSTRAINT chk_dispatch_batches_retry_count
            CHECK (retry_count >= 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_dispatch_batches_window') THEN
        ALTER TABLE dispatch_batches ADD CONSTRAINT chk_dispatch_batches_window
            CHECK (window_closes_at >= window_start_at);
    END IF;
END $$;


-- =============================================================================
-- 2. TRIP STOPS TABLE
-- Ordered stop sequence persistence supporting both PICKUP and DROPOFF stops.
-- Resolves the destination gap and enables multi-passenger route sequencing.
-- =============================================================================
CREATE TABLE IF NOT EXISTS trip_stops (
    id                  UUID PRIMARY KEY,
    trip_id             UUID NOT NULL REFERENCES trips(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    batch_id            UUID REFERENCES dispatch_batches(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    ride_request_id     UUID NOT NULL REFERENCES ride_requests(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    company_id          UUID NOT NULL REFERENCES companies(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    stop_type           VARCHAR(10) NOT NULL,
    sequence_order      INTEGER NOT NULL,
    latitude            DOUBLE PRECISION NOT NULL,
    longitude           DOUBLE PRECISION NOT NULL,
    status              VARCHAR(20) DEFAULT 'PLANNED' NOT NULL,
    planned_eta         TIMESTAMP WITHOUT TIME ZONE,
    actual_arrival      TIMESTAMP WITHOUT TIME ZONE,
    actual_departure    TIMESTAMP WITHOUT TIME ZONE,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

-- Indexes for trip_stops
CREATE INDEX IF NOT EXISTS idx_trip_stops_trip_id
    ON trip_stops(trip_id);

CREATE INDEX IF NOT EXISTS idx_trip_stops_trip_sequence
    ON trip_stops(trip_id, sequence_order);

CREATE INDEX IF NOT EXISTS idx_trip_stops_ride_request_id
    ON trip_stops(ride_request_id);

CREATE INDEX IF NOT EXISTS idx_trip_stops_company_id
    ON trip_stops(company_id);

CREATE INDEX IF NOT EXISTS idx_trip_stops_company_status
    ON trip_stops(company_id, status);

CREATE INDEX IF NOT EXISTS idx_trip_stops_batch_id
    ON trip_stops(batch_id);

-- Constraints for trip_stops
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_trip_stops_stop_type') THEN
        ALTER TABLE trip_stops ADD CONSTRAINT chk_trip_stops_stop_type
            CHECK (stop_type IN ('PICKUP', 'DROPOFF'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_trip_stops_sequence_order') THEN
        ALTER TABLE trip_stops ADD CONSTRAINT chk_trip_stops_sequence_order
            CHECK (sequence_order > 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_trip_stops_latitude') THEN
        ALTER TABLE trip_stops ADD CONSTRAINT chk_trip_stops_latitude
            CHECK (latitude >= -90.0 AND latitude <= 90.0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_trip_stops_longitude') THEN
        ALTER TABLE trip_stops ADD CONSTRAINT chk_trip_stops_longitude
            CHECK (longitude >= -180.0 AND longitude <= 180.0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_trip_stops_status') THEN
        ALTER TABLE trip_stops ADD CONSTRAINT chk_trip_stops_status
            CHECK (status IN ('PLANNED', 'EN_ROUTE', 'ARRIVED', 'COMPLETED', 'SKIPPED', 'CANCELLED'));
    END IF;

    -- Uniqueness constraint: a trip cannot have duplicate sequence numbers
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_trip_stops_trip_sequence') THEN
        ALTER TABLE trip_stops ADD CONSTRAINT uq_trip_stops_trip_sequence
            UNIQUE (trip_id, sequence_order);
    END IF;

    -- Uniqueness constraint: a ride request cannot have duplicate stops of the same type within a trip
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_trip_stops_trip_request_type') THEN
        ALTER TABLE trip_stops ADD CONSTRAINT uq_trip_stops_trip_request_type
            UNIQUE (trip_id, ride_request_id, stop_type);
    END IF;
END $$;


-- =============================================================================
-- 3. VEHICLE OPERATIONAL STATE TABLE
-- Decouples transient operational dispatch state from static vehicle attributes.
-- =============================================================================
CREATE TABLE IF NOT EXISTS vehicle_operational_state (
    vehicle_id          UUID PRIMARY KEY REFERENCES vehicles(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    company_id          UUID NOT NULL REFERENCES companies(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    status              VARCHAR(20) DEFAULT 'AVAILABLE' NOT NULL,
    active_trip_id      UUID REFERENCES trips(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    active_batch_id     UUID REFERENCES dispatch_batches(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    available_at        TIMESTAMP WITHOUT TIME ZONE,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

-- Indexes for vehicle_operational_state
CREATE INDEX IF NOT EXISTS idx_vehicle_operational_state_company_status
    ON vehicle_operational_state(company_id, status);

CREATE INDEX IF NOT EXISTS idx_vehicle_operational_state_active_trip_id
    ON vehicle_operational_state(active_trip_id);

CREATE INDEX IF NOT EXISTS idx_vehicle_operational_state_active_batch_id
    ON vehicle_operational_state(active_batch_id);

-- Constraints for vehicle_operational_state
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_vehicle_operational_state_status') THEN
        ALTER TABLE vehicle_operational_state ADD CONSTRAINT chk_vehicle_operational_state_status
            CHECK (status IN (
                'OFFLINE', 'AVAILABLE', 'IDLE', 'ASSIGNED',
                'EN_ROUTE_PICKUP', 'BOARDING', 'EN_ROUTE_DROPOFF',
                'IN_TRIP', 'OUT_OF_SERVICE'
            ));
    END IF;
END $$;

-- Backfill operational state for existing vehicles
INSERT INTO vehicle_operational_state (vehicle_id, company_id, status, updated_at)
SELECT v.id, v.company_id, 'AVAILABLE', NOW()
FROM vehicles v
ON CONFLICT (vehicle_id) DO NOTHING;


-- =============================================================================
-- 4. TRANSACTIONAL OUTBOX TABLE
-- Guarantees at-least-once real-time event delivery across transaction boundaries.
-- =============================================================================
CREATE TABLE IF NOT EXISTS outbox_events (
    id              UUID PRIMARY KEY,
    company_id      UUID REFERENCES companies(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    event_type      VARCHAR(50) NOT NULL,
    aggregate_type  VARCHAR(50),
    aggregate_id    UUID,
    payload         JSONB NOT NULL,
    target_type     VARCHAR(20),
    target_id       UUID,
    trip_id         UUID REFERENCES trips(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    published       BOOLEAN DEFAULT false NOT NULL,
    retry_count     INTEGER DEFAULT 0 NOT NULL,
    last_error      TEXT,
    created_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    published_at    TIMESTAMP WITHOUT TIME ZONE
);

-- Indexes for outbox polling and delivery
CREATE INDEX IF NOT EXISTS idx_outbox_events_published_created
    ON outbox_events(published, created_at);

CREATE INDEX IF NOT EXISTS idx_outbox_events_company_id
    ON outbox_events(company_id);

CREATE INDEX IF NOT EXISTS idx_outbox_events_trip_id
    ON outbox_events(trip_id);

-- Constraints for outbox_events
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_outbox_events_event_type') THEN
        ALTER TABLE outbox_events ADD CONSTRAINT chk_outbox_events_event_type
            CHECK (length(trim(event_type)) > 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_outbox_events_retry_count') THEN
        ALTER TABLE outbox_events ADD CONSTRAINT chk_outbox_events_retry_count
            CHECK (retry_count >= 0);
    END IF;
END $$;


-- =============================================================================
-- 5. RIDE REQUESTS ENHANCEMENTS
-- Add batch_id foreign key, queued_at, and expires_at timestamps.
-- Extend status check constraint to include QUEUED and EXPIRED.
-- =============================================================================
ALTER TABLE ride_requests
    ADD COLUMN IF NOT EXISTS batch_id UUID REFERENCES dispatch_batches(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS queued_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITHOUT TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_ride_requests_batch_id
    ON ride_requests(batch_id);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_ride_requests_status') THEN
        ALTER TABLE ride_requests DROP CONSTRAINT chk_ride_requests_status;
    END IF;

    ALTER TABLE ride_requests ADD CONSTRAINT chk_ride_requests_status
        CHECK (status IN (
            'REQUESTED',
            'QUEUED',
            'MATCHED',
            'WAITLISTED',
            'PICKUP_PENDING',
            'ONBOARD',
            'COMPLETED',
            'CANCELLED',
            'NO_SHOW',
            'EXPIRED'
        ));
END $$;
