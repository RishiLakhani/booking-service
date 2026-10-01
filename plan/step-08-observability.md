# Step 8 — Metrics + structured logs

**Goal:** Prometheus metrics that reconcile with API state; JSON logs carrying a request ID.
**Serves:** Deploy & Observe (metrics, logs), Deliverable 4.

## Tasks
- `/actuator/prometheus` via `micrometer-registry-prometheus`.
- `reservations_confirmed_total` (counter): counts **confirms** (`held → confirmed`) only, not holds. Increment **after commit** only.
- `reservations_declined_total{reason="seat-taken|per-user-limit|idempotent-replay"}`.
- `seats_available{show=...}` gauge, computed from the DB at scrape (expiry is lazy) with the same effective-status query.
- Logs: JSON via Spring Boot structured logging (`logging.structured.format.console`, built into Boot 4) or `logstash-logback-encoder`. A servlet filter reads or generates `X-Request-Id`, puts it in MDC, and echoes it in the response header.
- One log line per reserve/confirm outcome: `user`, `show`, `seats`, `outcome`, `reason`.

## Done when
- After a small run, the counters match `GET /shows/{id}` on a fresh show.
- Log lines are JSON and carry `request_id`.

## Open questions
- Whether a replay counts under `declined{reason="idempotent-replay"}`.
- Where `idempotency-mismatch` 409s and confirm declines are counted (the brief lists only 3 reasons).
- Gauge per show vs total.
