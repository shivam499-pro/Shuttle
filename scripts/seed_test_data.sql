-- =============================================================================
-- SEED DATA FOR RIDE-MATCHING "MATCHED" END-TO-END TEST
--
-- SEEDED UUIDS:
--   Company:  11111111-1111-1111-1111-111111111111
--   Employee: 22222222-2222-2222-2222-222222222222
--   Driver:   33333333-3333-3333-3333-333333333333
--   Vehicle:  44444444-4444-4444-4444-444444444444
--   Trip:     55555555-5555-5555-5555-555555555555
-- =============================================================================

-- 1. Clean up any existing records from previous runs to prevent PK conflicts
DELETE FROM ride_requests WHERE employee_id = '22222222-2222-2222-2222-222222222222'::uuid;
DELETE FROM trips WHERE id = '55555555-5555-5555-5555-555555555555'::uuid;
DELETE FROM vehicles WHERE id = '44444444-4444-4444-4444-444444444444'::uuid;
DELETE FROM drivers WHERE id = '33333333-3333-3333-3333-333333333333'::uuid;
DELETE FROM employees WHERE id = '22222222-2222-2222-2222-222222222222'::uuid;
DELETE FROM companies WHERE id = '11111111-1111-1111-1111-111111111111'::uuid;

-- 2. Insert Company
INSERT INTO companies (id, name, city, employee_count, created_at)
VALUES (
    '11111111-1111-1111-1111-111111111111'::uuid,
    'Acme Shuttles',
    'Bangalore',
    1,
    NOW()
);

-- 3. Insert Employee (pickup/home location at Whitefield, Bangalore: 12.9698, 77.7500)
INSERT INTO employees (id, company_id, email, name, home_lat, home_lng, created_at)
VALUES (
    '22222222-2222-2222-2222-222222222222'::uuid,
    '11111111-1111-1111-1111-111111111111'::uuid,
    'employee1@acme.com',
    'John Doe',
    12.9698,
    77.7500,
    NOW()
);

-- 4. Insert Driver
INSERT INTO drivers (id, company_id, name, phone, created_at)
VALUES (
    '33333333-3333-3333-3333-333333333333'::uuid,
    '11111111-1111-1111-1111-111111111111'::uuid,
    'Driver Bob',
    '+919999999999',
    NOW()
);

-- 5. Insert Vehicle (seat capacity 4, last known location at 12.9710, 77.7480: ~255 meters away)
INSERT INTO vehicles (id, company_id, plate_number, seat_capacity, created_at, last_known_lat, last_known_lng, last_location_updated_at)
VALUES (
    '44444444-4444-4444-4444-444444444444'::uuid,
    '11111111-1111-1111-1111-111111111111'::uuid,
    'KA-03-ME-1234',
    4,
    NOW(),
    12.9710,
    77.7480,
    NOW()
);

-- 6. Insert Trip (Status: IN_PROGRESS)
INSERT INTO trips (id, vehicle_id, driver_id, start_time, status)
VALUES (
    '55555555-5555-5555-5555-555555555555'::uuid,
    '44444444-4444-4444-4444-444444444444'::uuid,
    '33333333-3333-3333-3333-333333333333'::uuid,
    NOW(),
    'IN_PROGRESS'
);
