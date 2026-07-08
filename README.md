# DynaShuttle Backend

On-demand corporate van pooling platform for IT campuses in Bangalore and Hyderabad.

Instead of fixed shuttle routes that run empty and make employees wait blind, DynaShuttle dynamically reroutes vans in real time based on actual employee ride requests — like Uber Pool, purpose-built for corporate campuses.

---

## What's working right now

- **Dynamic ride matching** — `POST /rides` finds the nearest available van using a real PostGIS `ST_DWithin` spatial query, computes an ETA using a greedy nearest-neighbour algorithm, and returns either `MATCHED` (with vehicle ID and ETA) or `QUEUED` (no vehicle in range)
- **Live vehicle location** — driver GPS position is broadcast every 5 seconds over WebSocket (`/ws/driver/{tripId}`) and persisted to the database, so the spatial query always reflects real positions
- **Employee authentication** — `POST /auth/employee/request-otp` and `POST /auth/employee/verify-otp` issue a signed JWT on successful OTP verification
- **Admin authentication** — `POST /auth/admin/login` verifies BCrypt-hashed password and issues a signed JWT
- **Auto schema management** — all tables are created automatically on server startup via Exposed's `SchemaUtils`

---

## Tech stack

| Layer | Technology |
|---|---|
| Backend framework | Ktor 2.3 (Netty engine) |
| Language | Kotlin 1.9 |
| Database | PostgreSQL 17 + PostGIS extension |
| ORM | Exposed 0.50 + HikariCP connection pooling |
| Real-time | Ktor WebSockets (driver GPS broadcast) |
| Spatial queries | PostGIS `ST_DWithin` / `ST_MakePoint` via raw JDBC |
| Auth | JWT (com.auth0:java-jwt) + BCrypt (at.favre.lib:bcrypt) |
| Config | dotenv-kotlin — reads `.env` locally, falls back to system env vars in production |
| Build | Gradle with Kotlin DSL |

---

## Running locally

### Prerequisites

- JDK 21 (IntelliJ will prompt to install if missing)
- PostgreSQL 17 with PostGIS extension available
- IntelliJ IDEA Community Edition

### Database setup

```sql
-- Create the database
CREATE DATABASE dynashuttle;

-- Enable PostGIS (required for the spatial vehicle-matching query)
\c dynashuttle
CREATE EXTENSION IF NOT EXISTS postgis;
```

### Environment variables

Copy `.env.example` to `.env` and fill in your values:

```
DATABASE_URL=jdbc:postgresql://localhost:5432/dynashuttle
DATABASE_USER=postgres
DATABASE_PASSWORD=your_password_here
JWT_SECRET=a-long-random-string-change-this
PORT=8080
```

### Run

Open the project in IntelliJ, let Gradle sync complete, then click the green play button next to `fun main()` in `Application.kt`. The server starts at `http://localhost:8080`.

All database tables are created automatically on first startup — no migration scripts needed.

### Verify it's working

```powershell
# Health check
Invoke-WebRequest -Uri "http://localhost:8080/health" -UseBasicParsing

# Request an OTP (use an email from your seeded employees table)
Invoke-WebRequest -Uri "http://localhost:8080/auth/employee/request-otp" `
  -Method POST -ContentType "application/json" `
  -Body '{"email":"employee@yourcompany.com"}' -UseBasicParsing
```

---

## API endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/health` | None | Server health check |
| POST | `/rides` | None* | Request a ride — returns MATCHED or QUEUED |
| GET | `/rides/{id}` | None* | Poll ride request status |
| GET | `/trips/{id}/stops` | None* | Current stop list for a trip (driver) |
| POST | `/trips/{id}/stops/{stopId}/pickup` | None* | Mark passenger as picked up |
| POST | `/trips/{id}/stops/{stopId}/no-show` | None* | Mark passenger as no-show |
| POST | `/auth/employee/request-otp` | None | Request OTP for employee login |
| POST | `/auth/employee/verify-otp` | None | Verify OTP, receive JWT |
| POST | `/auth/admin/login` | None | Admin login, receive JWT |
| WS | `/ws/driver/{tripId}` | None* | Driver GPS broadcast channel |
| WS | `/ws/trip/{tripId}` | None* | Employee/admin live location listener |

*JWT auth enforcement on these endpoints is next on the roadmap.

---

## Project structure

```
src/main/kotlin/com/dynashuttle/
  Application.kt              Entry point
  config/
    Env.kt                    .env + system env loader
  models/
    Tables.kt                 Exposed table definitions (7 tables)
    Dto.kt                    JSON request/response shapes
  plugins/
    Authentication.kt         JWT verification config (two realms: app-user, admin)
    Routing.kt                Single dependency-wiring point (SOLID composition root)
    Serialization.kt, CORS.kt, StatusPages.kt, WebSockets.kt
  routes/
    RideRoutes.kt             POST/GET /rides
    TripRoutes.kt             Driver stop list + pickup/no-show
    WebSocketRoutes.kt        Real-time GPS channels
    AuthRoutes.kt             OTP and admin login endpoints
  services/
    RoutingService.kt         Ride matching orchestrator
    AuthService.kt            OTP flow + JWT issuing
    OtpGenerator.kt           Interface (swappable for SMS provider)
    PasswordHasher.kt         Interface (BCrypt implementation)
    RandomOtpGenerator.kt     Dev implementation — returns OTP in response
    BcryptPasswordHasher.kt   Production password verification
    routing/
      RouteOptimizer.kt                   Interface
      GreedyNearestNeighbourOptimizer.kt  MVP implementation
  repositories/
    VehicleRepository.kt + PostgresVehicleRepository.kt
    RideRequestRepository.kt + PostgresRideRequestRepository.kt
    AuthRepository.kt + PostgresAuthRepository.kt
  db/
    DatabaseFactory.kt        Connection pool + schema auto-creation
```

---

## Architecture principles

This codebase follows SOLID principles deliberately:

- **RoutingService** and **AuthService** depend only on interfaces — never on Exposed, Postgres, or HTTP clients directly
- All dependency wiring (which concrete class implements which interface) happens in exactly one place: `Routing.kt`'s `configureRouting()` function
- The routing algorithm (`GreedyNearestNeighbourOptimizer`) implements a `RouteOptimizer` interface — upgrading to a proper VRPTW solver later means writing a new class and changing one line in `Routing.kt`

---

## Test coverage

```
src/test/kotlin/com/dynashuttle/
  services/
    routing/
      GreedyNearestNeighbourOptimizerTest.kt   6 tests
    RoutingServiceTest.kt                       11 tests
    AuthServiceTest.kt                          7 tests
  HealthCheckTest.kt                            placeholder
```

All 24 meaningful tests pass. Fakes are plain Kotlin classes — no mocking library.

Run tests from IntelliJ: right-click `src/test/kotlin` → Run All Tests.

---

## What's next

- [ ] Apply JWT auth enforcement to `/rides`, `/trips`, and WebSocket routes
- [ ] Customer discovery interviews (8–10 HR/transport managers in Bangalore IT parks)
- [ ] Android employee + driver app (Kotlin + Jetpack Compose)
- [ ] Replace flat-speed ETA estimate with Google Routes API integration
- [ ] Real SMS OTP delivery (swap `RandomOtpGenerator` for an SMS provider)

---

## Business context

B2B2C SaaS — companies pay a per-vehicle monthly subscription, employees use the app free. Target market: 100–500 employee IT companies in Bangalore and Hyderabad that already run shuttle fleets on fixed routes. The competitive gap: no existing player (MoveInSync, Routematic, Safetrax) offers true on-demand dynamic rerouting for this market segment.