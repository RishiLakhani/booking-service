# booking-service

A JSON HTTP API that acts as the **system of record for seat reservations** when a show goes on sale.

A hall of numbered seats opens at once, and thousands of buyers try to book at the same moment, often fighting over the same few seats. The service decides atomically who gets each seat: exactly one buyer wins a contested seat, and everyone else gets a clean "already taken" response, never a server error or a duplicate booking.

**Live:** https://43-204-225-126.sslip.io · **One-command burst:** `./burst.sh <BASE_URL>` · **Design write-up:** [`WRITEUP.md`](WRITEUP.md) · **Plan:** [`plan/`](plan/)

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

https://github.com/user-attachments/assets/7613d97b-24c1-47f8-a5b7-46ff9d708928

The above video shows the live CloudWatch logs while the burst runs against this deployment (also in the repo: [`docs/live-logs-under-load.mp4`](docs/live-logs-under-load.mp4)):

1. **Winners:** a live log tail filtered to successful holds on the 10 hot seats. Exactly one winning request per seat appears, out of ~8,000 concurrent attempts.
2. **Reconciliation:** a CloudWatch Logs Insights count of every outcome logged for the burst's show (held, `seat-taken`, `per-user-limit`, `idempotency-mismatch`, replays). It matches the burst script's report and the Prometheus counters.
3. **Correlation:** a reserve and a confirm sent with custom `X-Request-Id`s, then found in CloudWatch by those IDs.

## What it does

- **Create a show:** an admin creates a show with a list of seats and a price (integer paise; fractional values are rejected).
- **Reserve seats:** an authenticated user places a **time-limited hold** on one or more seats.
  - **All-or-nothing:** either every requested seat is held, or none are.
  - **Idempotent:** an idempotency key (the `Idempotency-Key` header, or `idempotency_key` in the body) makes retries safe; a retry returns the original reservation.
  - **Per-user limit:** at most 4 active seats (held or confirmed) per user per show.
- **Confirm:** the owner confirms a hold before it expires (`HOLD_TTL`, default 2 minutes). Unconfirmed holds expire and their seats become available again. Confirmed seats never expire.
- **Show state:** per-seat status (`available` / `held` / `confirmed`) and counts, where `available + held + confirmed == total_seats` at all times.
- **Operations:** liveness and readiness checks, Prometheus metrics, structured JSON logs with request IDs.

> **Note on the reserve response.** The brief's sample shows `"status": "confirmed"` on a successful reserve. This service uses **time-boxed holds**, so a successful reserve returns `201` with `"status": "held"` plus an `expires_at`, and `POST /reservations/{id}/confirm` turns it into `confirmed`. The brief lets each implementation choose between explicit cancel and time-boxed holds.

## How correctness is guaranteed

Every decision is made **inside PostgreSQL in a single transaction**, never by "read, then write" in application code:

| Rule | Mechanism |
|---|---|
| **No double-sell** | `reservation_seats` has `UNIQUE (show_id, seat_no)` (`uq_seat_taken`). Concurrent claims on a seat race on that index: exactly one commits, the rest get a unique violation, which becomes `409 seat-taken`. |
| **No deadlocks on multi-seat requests** | Seats are always claimed in sorted order, and locks are always taken in the same order (user row → expired holds by id → seats). Two requests for `[A1,A2]` and `[A2,A1]` can't wait on each other. |
| **Per-user limit under concurrency** | Each reserve first locks a `(user, show)` row (`SELECT … FOR UPDATE`), so one user's parallel requests run one at a time while the active seats are counted. |
| **Idempotency** | Keys (header or body) are stored with `UNIQUE (user_id, show_id, idempotency_key)`; the same per-user lock serializes retries of one key. Same seats → replay (`200`); different seats → `409 idempotency-mismatch`. |
| **Expiry never frees a confirmed seat** | Expiry is lazy: a reserve that needs a seat moves an expired hold `held → expired` under its row lock and only then releases its seats. Confirm is a guarded `UPDATE … WHERE status='held' AND expires_at > now()`. Both touch the same row, so exactly one wins. |
| **Identity** | The user comes only from the JWT `sub`; request bodies have no user field. |

All times (hold creation and expiry) use the database clock, so app servers can't disagree about whether a hold has expired.

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

Other settings (env vars, with defaults): `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` (the Compose Postgres), `HOLD_TTL` (`2m`), and the connection pool size: `DB_POOL_SIZE` under Docker Compose, or `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` when running from source (`10`).

## API

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
# Create a show (admin): every seat starts available; price is integer paise
curl -X POST localhost:8080/shows -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"name": "friday-night", "seats": ["A1", "A2", "A3"], "price_paise": 25000}'

# Show state (any valid token): per-seat status and counts
curl localhost:8080/shows/<show-id> -H "Authorization: Bearer $TOKEN"
```

### Reserve seats

```bash
curl -X POST localhost:8080/shows/<show-id>/reserve -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"seats": ["A12", "A13"]}'
```

The idempotency key can go in the `Idempotency-Key` header (as above) or in the body as `{"seats": [...], "idempotency_key": "..."}`. If both are sent they must match.

Success is `201` with `reservation_id`, `show_id`, `user_id`, `seats`, `amount_paise` (price × seats), `"status": "held"` and `expires_at`.

| Situation | Response |
|---|---|
| New request, seats free | `201` new hold |
| Same key, same seats (retry) | `200` with the original reservation |
| Same key, different seats | `409 idempotency-mismatch` |
| Same key after its hold expired | `409 hold-expired`; retry with a new key |
| A seat is already held or confirmed | `409 seat-taken` |
| More than 4 active seats for this user and show | `409 per-user-limit` |
| Unknown seat, duplicate seats, missing key, header and body keys differ | `400 invalid-request` |

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

Every query runs with `statement_timeout = 10s` and `lock_timeout = 5s`, so nothing can hold a connection indefinitely; hitting either returns `503`.

## Observability

### Metrics

Prometheus format at `GET /actuator/prometheus` (no token needed):

| Metric | Meaning |
|---|---|
| `reservations_held_total` | New holds (each `201`) |
| `reservations_confirmed_total` | Holds confirmed (a repeat confirm isn't counted) |
| `reservations_declined_total{reason}` | `seat-taken`, `per-user-limit`, `idempotent-replay` (each `200` retry), `idempotency-mismatch`, `hold-expired` |
| `seats_available{show_id}` | Available seats per show, read live from the database |

These reconcile with the API: every reserve response increments exactly one of held / declined, and `seats_available` equals the `available` count from `GET /shows/{id}`. Spring Boot also exports HTTP request counts by status, connection-pool usage and JVM metrics.

### Logs

JSON lines (Logstash format) on stdout; in AWS they go to CloudWatch. Every request gets a `request_id`: send `X-Request-Id` to set your own, otherwise one is generated. It is returned in the `X-Request-Id` response header and attached to every log line for that request. Reserve and confirm outcomes are logged with `event`, `outcome`, `reason`, `user_id`, `show_id` and `seats`.

### Health checks

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health/liveness` | Process is up |
| `GET /actuator/health/readiness` | Ready to serve: checks the database and returns `503` when it is unreachable |

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

Checks are time-aware: if a run outlasts the hold TTL, seats that legitimately change hands after expiry aren't reported as double-sold.

Sample results:

```
# Local (Docker Postgres)
Stampede: 20,000 requests in 15.1s (1,322 req/s); latency p50 371 ms, p95 693 ms, p99 808 ms
available 305 + held 277 + confirmed 418 = 1,000   total_seats 1,000   OK
ALL CHECKS PASSED: 22 passed, 0 failed, 0 skipped

# Live AWS deployment, from a laptop in India
Stampede: 20,000 requests in 34.6s (578 req/s); latency p50 842 ms, p95 1766 ms, p99 2559 ms
ALL CHECKS PASSED: 22 passed, 0 failed, 0 skipped
```

## Tests

```bash
./gradlew test    # starts a throwaway Postgres with Testcontainers (Docker required)
```

- 31 tests against a real PostgreSQL, using real threads for the concurrency cases: hot-seat race, per-user limit, idempotent retries, opposite-order multi-seat requests (deadlock), hold expiry, confirm rules, security rules, every error mapping, metrics reconciliation, database timeouts.
- Coverage (JaCoCo, `build/reports/jacoco/test/html/index.html`): 97.6% of lines, 84.6% of branches.
- The suite was checked against deliberately planted bugs (double-sell, missing lock, skipped idempotency, unsorted seat claims, expiry mistakes, auth gaps, and more): each one makes at least one test fail.

## Tech stack

- Java 21, Spring Boot 4, Gradle
- PostgreSQL 16 with Flyway migrations, accessed via JdbcTemplate
- JWT (HS256) authentication via Spring Security
- Micrometer / Prometheus, Logstash-format JSON logs
- Docker / Docker Compose, Caddy; AWS EC2 + RDS + CloudWatch Logs
- Python (asyncio + aiohttp, run with uv) for the burst script

## Repository layout

```
src/main/java/com/app/bookingservice   application code (controller, service, repository, model,
                                       security, exception, observability, config)
src/main/resources                     configuration (application.yaml), Flyway migrations
src/test/java/com/app/bookingservice   tests (Testcontainers Postgres)
Dockerfile, docker-compose.yml         container build; compose profiles "local" and "aws"
Caddyfile                              HTTPS reverse proxy (aws profile)
burst.sh, burst/                       one-command burst test
docs/                                  recording of the live logs under load
plan/                                  step-by-step execution plan
```

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
- [x] 11. README: run, test and burst instructions
- [x] 12. WRITEUP.md
