# booking-service

A JSON HTTP API that acts as the **system of record for seat reservations** when a show goes on sale.

A hall of numbered seats opens at once, and thousands of buyers try to book at the same moment, often fighting over the same few seats. The service decides atomically who gets each seat: exactly one buyer wins a contested seat, and everyone else gets a clean "already taken" response, never a server error or a duplicate booking.

> **Status:** work in progress. The step-by-step build plan is in [`plan/`](plan/).

## Progress

Each step is ticked in the commit that completes it. Details per step are in [`plan/`](plan/).

- [x] Project scaffold: Spring Boot 4, Java 21, Gradle
- [x] Execution plan
- [x] 1. Postgres (Docker Compose), configuration, liveness/readiness health checks
- [ ] 2. Database schema (Flyway) and JDBC data access
- [ ] 3. JWT authentication and token endpoint
- [ ] 4. Create show and show state endpoints
- [ ] 5. Reserve seats (atomic hold, idempotency, per-user limit)
- [ ] 6. Confirm a hold
- [ ] 7. Error handling: clean 4xx for every domain outcome
- [ ] 8. Prometheus metrics and structured logs
- [ ] 9. Burst script
- [ ] 10. Docker image and AWS deployment (EC2 + RDS)
- [ ] 11. README: run, test and burst instructions
- [ ] 12. WRITEUP.md

## What it does (planned)

- **Create a show:** an admin creates a show with a list of seats and a price (integer paise).
- **Reserve seats:** an authenticated user places a time-limited hold on one or more seats.
  - All-or-nothing: either every requested seat is held or none are.
  - Idempotent: retrying with the same idempotency key never reserves twice.
  - Per-user limit: at most 4 seats per user per show by default.
- **Confirm:** the owner confirms a hold before it expires. Unconfirmed holds expire and the seats become available again.
- **Show state:** per-seat status (`available` / `held` / `confirmed`) and counts, where `available + held + confirmed == total_seats` at all times.
- **Operations:** liveness and readiness health checks, Prometheus metrics, structured JSON logs with request IDs.
- **Burst script:** one command that simulates the on-sale stampede against a running instance and reports the outcome.

## Tech stack

- Java 21, Spring Boot 4, Gradle
- PostgreSQL with Flyway migrations, accessed via JdbcTemplate
- JWT (HS256) authentication
- Docker / Docker Compose; deployed on AWS (EC2 + RDS)
- Python burst script

## Repository layout

```
src/main/java/com/app/bookingservice   application code
src/main/resources                     configuration (application.yaml), migrations
plan/                                  step-by-step execution plan
```

## Running locally

**Prerequisites:** Java 21, Docker (with Docker Compose).

```bash
docker compose up -d postgres     # Postgres 16 on localhost:5432
./gradlew bootRun                 # app on http://localhost:8080
```

Database settings default to the Compose values and can be overridden with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`.

Tests need Postgres running: `./gradlew test`.

### Health checks

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health/liveness` | Process is up |
| `GET /actuator/health/readiness` | Ready to serve: checks the database and returns `503` when it is unreachable |
