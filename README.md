# booking-service

A JSON HTTP API that acts as the **system of record for seat reservations** when a show goes on sale.

A hall of numbered seats opens at once, and thousands of buyers try to book at the same moment, often fighting over the same few seats. The service decides atomically who gets each seat: exactly one buyer wins a contested seat, and everyone else gets a clean "already taken" response, never a server error or a duplicate booking.

> **Status:** work in progress. The step-by-step build plan is in [`plan/`](plan/).

## Live deployment

**Base URL: https://43-204-225-126.sslip.io**

| What | Where |
|---|---|
| Liveness | https://43-204-225-126.sslip.io/actuator/health/liveness |
| Readiness (checks the database; `503` when it's unreachable) | https://43-204-225-126.sslip.io/actuator/health/readiness |
| Prometheus metrics | https://43-204-225-126.sslip.io/actuator/prometheus |
| Logs | AWS CloudWatch Logs, group `/booking-service` (one stream per container); see the recording below |

Run the burst against it (the admin secret is shared with the submission, not committed):

```bash
ADMIN_SECRET=<admin-secret> ./burst.sh https://43-204-225-126.sslip.io
```

**How it's deployed (AWS `ap-south-1`):**

```
client ──HTTPS──▶ EC2 t4g.small (Elastic IP)
                   ├─ caddy  :80/:443  automatic Let's Encrypt cert, proxies to app
                   └─ app    :8080     (not exposed; Spring Boot, Java 21)
                         │ JDBC + SSL, pool of 30
                         ▼
                  RDS PostgreSQL 16 db.t4g.micro (private; accepts only the app's security group)
```

- **Same build as local:** the instance runs the same `docker-compose.yml`, with the `aws` profile (`app` + `caddy`) instead of `local` (`postgres` + `app`).
- **Secrets:** stored in SSM Parameter Store (encrypted) and written to a root-only `.env` on the instance at deploy time.
- **Access:** only ports 80 and 443 are open; administration is through SSM Session Manager (no SSH).
- **Resilience:** containers restart automatically, so the service comes back after an instance reboot.

Measured from a laptop in India against this deployment: 20,000-request burst, all 22 checks pass, 0 5xx; ~520 req/s once the JVM is warm.

### Logs under load (recording)

[`docs/live-logs-under-load.mp4`](docs/live-logs-under-load.mp4) (~6.5 MB) shows the live CloudWatch logs while the burst runs against this deployment:

1. **Winners:** a live log tail filtered to successful holds on the 10 hot seats. Exactly one winning request per seat appears, out of ~8,000 concurrent attempts.
2. **Reconciliation:** a CloudWatch Logs Insights count of every outcome logged for the burst's show (held, `seat-taken`, `per-user-limit`, `idempotency-mismatch`, replays). It matches the burst script's report and the Prometheus counters.
3. **Correlation:** a reserve and a confirm sent with custom `X-Request-Id`s, then found in CloudWatch by those IDs.

## Progress

Each step is ticked in the commit that completes it. Details per step are in [`plan/`](plan/).

- [x] Project scaffold: Spring Boot 4, Java 21, Gradle
- [x] Execution plan
- [x] 1. Postgres (Docker Compose), configuration, liveness/readiness health checks
- [x] 2. Database schema (Flyway) and JDBC data access
- [x] 3. JWT authentication and token endpoint
- [x] 4. Create show and show state endpoints
- [x] 5. Reserve seats (atomic hold, idempotency, per-user limit)
- [x] 6. Confirm a hold
- [x] 7. Error handling: clean 4xx for every domain outcome
- [x] 8. Prometheus metrics and structured logs
- [x] 9. Burst script
- [x] 10. Docker image and AWS deployment (EC2 + RDS)
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
src/main/resources                     configuration (application.yaml), Flyway migrations
src/test/java/com/app/bookingservice   tests (Testcontainers Postgres)
Dockerfile, docker-compose.yml         container build; compose profiles "local" and "aws"
Caddyfile                              HTTPS reverse proxy (aws profile)
burst.sh, burst/                       one-command burst test
plan/                                  step-by-step execution plan
```

## Burst test (one command)

Reproduces the on-sale stampede against any running instance and checks every correctness rule. It runs on [uv](https://docs.astral.sh/uv/), which supplies Python 3.11+ and the script's dependencies on the fly. If `uv` isn't installed, `burst.sh` offers to install it (asking first), or prints the install command.

```bash
ADMIN_SECRET=<admin-secret> ./burst.sh <BASE_URL>
# e.g. ADMIN_SECRET=... ./burst.sh http://localhost:8080
```

Options: `--requests 20000`, `--concurrency 500`, `--hot-seats 10`, `--seats 1000`, `--seed 42`, and `--expiry-check` (also waits out the hold TTL and verifies release). It exits `0` only if every check passes.

**What it fires, all at once, against a fresh show:**
- a hot-seat storm (800 users per seat)
- overlapping multi-seat requests in random order
- idempotent retry storms
- a per-user limit attack (10 parallel requests per user)
- identity spoofing in the body
- 13 kinds of invalid requests
- normal traffic

It then runs a confirm storm (including concurrent double confirms and confirms of other users' holds).

**What it prints:** the outcome distribution (201 / 200 / 409 by reason / other 4xx / **5xx** / client errors), latency percentiles, the final reconciliation, and a PASS/FAIL line per check:
- zero 5xx
- one winner per hot seat
- no seat owned twice at once
- no user over 4 active seats
- idempotency
- token-derived identity
- the expected 4xx for each bad request
- the invariant in every live snapshot and at the end
- all-or-nothing final state
- metrics reconcile with responses

Sample result (local, Docker Postgres):

```
Stampede: 20,000 requests in 15.1s (1,322 req/s); latency p50 371 ms, p95 693 ms, p99 808 ms
available 305 + held 277 + confirmed 418 = 1,000   total_seats 1,000   OK
ALL CHECKS PASSED: 22 passed, 0 failed, 0 skipped
```

## Running locally

**Prerequisites:** Docker (with Docker Compose). For running outside Docker: Java 21.

```bash
cp .env.example .env              # then set JWT_SECRET (32+ chars) and ADMIN_SECRET
```

**Everything in Docker** (the same way it runs in production):

```bash
docker compose --profile local up --build    # Postgres 16 + app on http://localhost:8080
```

**Or the app from source,** with only Postgres in Docker:

```bash
docker compose up -d postgres     # Postgres 16 on localhost:5432
./gradlew bootRun                 # app on http://localhost:8080
```

`JWT_SECRET` and `ADMIN_SECRET` are required: the app will not start without them. Locally they are read from `.env` (git-ignored); in deployment they come from environment variables.

Database settings default to the Compose values and can be overridden with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`.

Tests start their own throwaway Postgres with Testcontainers (Docker required): `./gradlew test`.

### Authentication

Every endpoint except health, metrics and token issuance needs a `Bearer` token. The caller's identity is taken from the token only, never from the request body.

```bash
# User token (valid 2 hours)
curl -X POST localhost:8080/auth/token -H 'Content-Type: application/json' \
  -d '{"user_id": "alice"}'

# Admin token (needed to create shows)
curl -X POST localhost:8080/auth/token -H 'Content-Type: application/json' \
  -H "X-Admin-Secret: $ADMIN_SECRET" -d '{"user_id": "ops", "role": "admin"}'
```

The token endpoint stands in for a real login, so anyone can obtain a user token. Admin tokens require the admin secret.

### Shows

```bash
# Create a show: every seat starts available; price is integer paise
curl -X POST localhost:8080/shows -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"name": "friday-night", "seats": ["A1", "A2", "A3"], "price_paise": 25000}'

# Show state (any valid token): per-seat status and counts (available + held + confirmed == total_seats)
curl localhost:8080/shows/<show-id> -H "Authorization: Bearer $TOKEN"
```

Each user may hold or confirm at most 4 seats per show.

### Reserve seats

```bash
curl -X POST localhost:8080/shows/<show-id>/reserve -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"seats": ["A12", "A13"]}'
```

- **All-or-nothing:** either every requested seat is held, or none are.
- **Hold:** a successful reserve returns `201` with `"status": "held"` and an `expires_at`. The hold lasts `HOLD_TTL` (default 2 minutes) and must be confirmed before it expires. This deliberately differs from a plain `confirmed` response, because holds are time-boxed.
- **Idempotency:** the `Idempotency-Key` header is required.

| Situation | Response |
|---|---|
| New request, seats free | `201` new hold |
| Same key, same seats (retry) | `200` with the original reservation |
| Same key, different seats | `409 idempotency-mismatch` |
| Same key after its hold expired | `409 hold-expired`; retry with a new key |
| A seat is already held or confirmed | `409 seat-taken` |
| More than 4 seats for this user and show | `409 per-user-limit` |

Declines return `{"error": "<reason>", "message": "..."}`.

### Confirm a hold

```bash
curl -X POST localhost:8080/reservations/<reservation-id>/confirm -H "Authorization: Bearer $TOKEN"
```

| Situation | Response |
|---|---|
| Your hold, not expired | `200` with `"status": "confirmed"` |
| Already confirmed (retry) | `200` with the same reservation |
| Hold expired | `409 hold-expired` |
| Someone else's reservation | `403` |
| Unknown reservation | `404` |

Confirmed seats are permanent: they never expire and can't be taken by anyone else.

### Errors

Every error has the same shape:

```json
{ "error": "seat-taken", "message": "one or more seats are already taken" }
```

| Status | `error` codes |
|---|---|
| 400 | `invalid-request` |
| 401 | `unauthorized` (missing, invalid or expired token) |
| 403 | `forbidden` |
| 404 | `not-found` |
| 409 | `seat-taken`, `per-user-limit`, `idempotency-mismatch`, `hold-expired` |
| 500 | `internal-error` |
| 503 | `service-unavailable` (database busy or unreachable; includes `Retry-After`) |

### Metrics

Prometheus format at `GET /actuator/prometheus` (no token needed):

| Metric | Meaning |
|---|---|
| `reservations_held_total` | New holds (each `201`) |
| `reservations_confirmed_total` | Holds confirmed |
| `reservations_declined_total{reason}` | `seat-taken`, `per-user-limit`, `idempotent-replay` (each `200` retry), `idempotency-mismatch`, `hold-expired` |
| `seats_available{show_id}` | Available seats per show, read live from the database |

These reconcile with the API: every reserve response increments exactly one of held / declined, and `seats_available` equals the `available` count from `GET /shows/{id}`.

### Logs

JSON lines (Logstash format) on stdout. Every request gets a `request_id`: send `X-Request-Id` to set your own, otherwise one is generated. It is returned in the `X-Request-Id` response header and attached to every log line for that request. Reserve and confirm outcomes are logged with `event`, `outcome`, `reason`, `user_id`, `show_id` and `seats`.

### Health checks

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health/liveness` | Process is up |
| `GET /actuator/health/readiness` | Ready to serve: checks the database and returns `503` when it is unreachable |
