# Step 1 — Postgres + config + health

**Goal:** the generated app boots against Postgres and reports liveness/readiness.
**Serves:** Deploy & Observe (health, compose parity).

Already in place: Spring Boot 4.1.1 project (Java 21, Gradle), package `com.app.bookingservice`, with the web, JDBC, validation, actuator, Flyway (+ PostgreSQL module), PostgreSQL driver and Prometheus dependencies.

## Tasks
- `docker-compose.yml` with a `postgres` service (the app service is added in step 10).
- `application.yaml`:
  - datasource from env vars (local defaults matching compose)
  - `spring.threads.virtual.enabled: true`
  - `management.endpoint.health.probes.enabled: true`
  - readiness group includes `db`
  - expose `health,prometheus`

## Files
`docker-compose.yml`, `src/main/resources/application.yaml`

## Done when
- `docker compose up postgres` + `./gradlew bootRun` → `/actuator/health/liveness` 200.
- Readiness 200 with the DB up; **503 after `docker compose stop postgres`** (fails closed).
