# DynaShuttle Backend

Ktor + Kotlin backend for the DynaShuttle on-demand corporate van pooling platform.

## Stack

- **Framework**: Ktor 2.3 (Netty engine)
- **Language**: Kotlin 1.9
- **Database**: PostgreSQL via Exposed ORM + HikariCP connection pooling
- **Real-time**: Native Ktor WebSockets for driver GPS broadcast
- **Auth**: JWT (separate realms for app-users vs admin)

## Project structure

```
src/main/kotlin/com/dynashuttle/
  Application.kt          Entry point — wires up all plugins
  models/
    Tables.kt              Exposed table definitions (matches the ERD)
    Dto.kt                 JSON request/response shapes
  plugins/
    Serialization.kt       JSON content negotiation
    CORS.kt                Cross-origin config for web admin + mobile
    StatusPages.kt          Centralized error handling
    Authentication.kt       JWT verification (app-user + admin realms)
    WebSockets.kt           WebSocket server config
    Routing.kt              Wires all route modules together
  routes/
    RideRoutes.kt           POST /rides, GET /rides/{id}  (employee flow)
    TripRoutes.kt           Driver stop list + pickup/no-show endpoints
    WebSocketRoutes.kt      /ws/driver/{tripId}, /ws/trip/{tripId}
  services/
    RoutingService.kt       Core matching + dynamic routing logic (stub — build this out first)
  db/
    DatabaseFactory.kt      Postgres connection setup
```

## Running locally

1. Install PostgreSQL locally, or point `DATABASE_URL` at a free Neon instance.
2. Copy `.env.example` to `.env` and fill in your local Postgres credentials.
3. Export the env vars (or use a tool like `direnv` / IntelliJ's run config) and run:

```bash
./gradlew run
```

4. Confirm it's alive: `curl http://localhost:8080/health`

## What's stubbed vs real

This skeleton wires up all the infrastructure (auth, websockets, db connection,
routing layer) but leaves the actual business logic as `TODO`s in two places:

- **`RoutingService.matchRideRequest()`** — currently always returns "no vehicle found".
  This is where you'll add the PostGIS nearby-vehicle query and the Google Routes API
  call for stop-sequence optimization. This is the single most important piece of
  code in the whole product — it's your competitive whitespace.
- **`TripRoutes.kt`** — the stop list and pickup/no-show endpoints return placeholder
  data instead of querying the database.

## Next steps (Foundation phase, weeks 1-3)

1. Get this running locally against a real Postgres instance.
2. Build out `RoutingService` with a real PostGIS query + greedy nearest-neighbour logic.
3. Wire `TripRoutes` to actually read/write `RideRequests` rows.
4. Add the `AuthRoutes.kt` file for employee OTP login and admin email/password login.
5. Deploy to Railway or Render once the core flow works end-to-end locally.
