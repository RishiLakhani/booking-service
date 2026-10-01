# Step 8 — Metrics + structured logs

**Goal:** Prometheus metrics that reconcile with API responses and show state; JSON logs carrying a request ID.
**Serves:** Deploy & Observe (metrics, logs), Deliverable 4.

## Metrics (`/actuator/prometheus`, package `observability`)
| Metric | Meaning |
|---|---|
| `reservations_held_total` | new holds (each 201) |
| `reservations_confirmed_total` | holds that became confirmed (a repeat confirm is not counted) |
| `reservations_declined_total{reason}` | `seat-taken`, `per-user-limit`, `idempotent-replay` (each 200 replay), `idempotency-mismatch`, `hold-expired` |
| `seats_available{show_id}` | available seats per show, read from the DB at scrape time (one grouped query, cached 1s) |

- Counters increment only after the transaction finishes: successes in the controllers, declines in the global exception handler after rollback.
- All reason labels are registered up front, so they show 0 before the first event.
- Free from Spring Boot: HTTP request counts by status, Hikari pool usage, JVM metrics.

## Logs
- Spring Boot structured logging, `logstash` JSON format.
- `RequestIdFilter` runs first: it reuses a safe incoming `X-Request-Id` or generates a UUID, puts it in MDC as `request_id` and echoes it in the response header.
- One line per reserve/confirm outcome with `event`, `outcome`, `reason`, `user_id`, `show_id`, `seats`, `reservation_id`.

## Tests
- Via HTTP (MockMvc): reserve → held +1, replay → idempotent-replay +1, conflict → seat-taken +1, confirm → confirmed +1, repeat confirm → +0.
- Gauge equals the `available` count of `GET /shows/{id}`, and the Prometheus scrape contains every series.
- Request ID echoed when safe, generated when missing or unsafe, present on 401s.
