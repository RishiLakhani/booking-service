# Write-up: booking-service

A seat-reservation system of record for a show on-sale: holds that expire, owner-only confirmation, and every correctness decision pushed into PostgreSQL. Live at https://43-204-225-126.sslip.io (AWS EC2 + RDS). The [README](README.md) covers running it and the API.

## 1. The atomic decision

**Mechanism: a unique index decides who gets a seat.** A held or confirmed seat is a row in `reservation_seats`, which has `UNIQUE (show_id, seat_no)` (`uq_seat_taken`). Reserving inserts one row per requested seat inside the reservation's transaction:

- When N transactions try to claim seat A12 at once, PostgreSQL's unique index admits exactly one row. Concurrent inserters of the same key block on the first uncommitted entry.
  - If the first commits, the others get a unique violation, which the service turns into `409 seat-taken`.
  - If it rolls back, one of the waiters proceeds.
- There is no "is it free? then take it" step in application code. The check and the claim are the same statement, so no interleaving can produce two winners.
- **All-or-nothing:** any failed insert aborts the whole transaction, so a multi-seat request never leaves a partial hold.

Proof under load: in every 20,000-request burst (800 requests per hot seat, 500 in flight at once), each of the 10 hot seats got exactly one `201`, with 0 5xx, both locally and against the live deployment. After an early slow run in which holds expired mid-burst and seats legitimately changed hands, a query over the database found 206 seat hand-overs and 0 overlapping owners.

**Deadlock avoidance for multi-seat requests.** Requests for `[A1, A2]` and `[A2, A1]` would deadlock if each inserted in its own order: each would hold one index entry and wait for the other. The service sorts seats before inserting, and every transaction takes locks in one fixed order:

1. the `(user, show)` row
2. expired holds being released, ordered by id
3. seat rows, in sorted seat order

With a global order, waits can't form a cycle. A dedicated test fires 40 concurrent requests per round with opposite orders over 10 rounds; it passes with sorting, and fails when sorting is deliberately removed.

**Per-user limit.** Counting a user's seats and then inserting would be a read-then-write race. Each reserve therefore first locks the user's `(user, show)` row (`SELECT … FOR UPDATE`, creating it if absent), so one user's concurrent requests run one at a time while other users are unaffected. Ten parallel requests from one user on a limit-4 show always produce exactly 4 holds.

## 2. Idempotency

- **Where the key lives:** the client sends an `Idempotency-Key` header (required). The key is stored on the reservation row, scoped to the token's user and the show: `UNIQUE (user_id, show_id, idempotency_key)`. Scoping by user means one client can't collide with, or probe, another's keys.
- **Exactly once:** the lookup by key happens *after* taking the per-user lock, so concurrent retries of the same key are serialized. The first creates the reservation; the rest find it and get a **`200` replay** of the original body. The unique constraint is the backstop.
  - Replays deliberately return `200`, not `201`, so "exactly one `201` per seat" stays true even when the winner retries. They're counted as `reservations_declined_total{reason="idempotent-replay"}`.
- **Same key, different body:** the stored seat list (a sorted `text[]`) is compared with the request's sorted seats. A difference returns `409 idempotency-mismatch`, checked before anything else.
- **Expired key:** retrying a key whose hold has lapsed returns `409 hold-expired`. The key is spent, and the client retries with a new key instead of silently getting a different hold.

## 3. Holds and expiry

- **Model:** reserve creates a **time-boxed hold** (`status: held`, `expires_at = now() + HOLD_TTL`, default 2 minutes); the owner confirms it (`POST /reservations/{id}/confirm`). This is the brief's "time-boxed hold" option, so a successful reserve returns `held` rather than the sample's `confirmed`.
- **Lazy expiry, no background job.** An expired hold is inert. Every read computes effective status in SQL (`held AND expires_at <= now()` counts as available), so show state, the per-user count and the metrics gauge never show stale holds. A seat's row is physically released only when someone else wants it.
- **The race this creates, and its fix.** A confirm and a release of the same expired hold can run at the same time. A blind `DELETE … WHERE expires_at <= now()` could free a seat whose owner had just confirmed it. So:
  - **Release** locks the expired reservation (`FOR UPDATE`), moves it `held → expired`, and deletes seat rows *only* for reservations it actually moved.
  - **Confirm** is a guarded `UPDATE … WHERE status='held' AND expires_at > now() AND user_id = <token user>`.
  - Both write the same reservation row under its lock, and PostgreSQL re-checks the `WHERE` clause after waiting. Exactly one wins, so **a confirmed seat can never be released.**
- **One clock:** expiry is set and checked with the database's `now()`, so app instances with drifting clocks can't disagree.
- **Confirm semantics:** owner only (`403` otherwise); repeating a confirm returns `200` unchanged; confirming an expired hold returns `409 hold-expired`, the same code as reserve.

## 4. Consistency vs availability under a partition

The service chooses **consistency**: there is one PostgreSQL primary, and it is the only thing that can say yes to a seat.

- **App can't reach the database:** requests fail fast and honestly with `503 service-unavailable` + `Retry-After`. Statement and lock timeouts (10s / 5s) stop stuck connections from cascading. Readiness turns `503`, so a load balancer would stop routing to the instance. Nothing is cached or queued to "accept now, decide later", because accepting a seat without the database could double-sell.
- **Client is cut off mid-request:** the transaction either committed or rolled back, and the client can't tell which. Retrying with the same `Idempotency-Key` resolves it safely: the result is either the original reservation (`200`) or a fresh attempt.
- **Trade-off:** during a database outage the on-sale stops, rather than risk overselling. A seat-of-record system should make that trade.
- **Availability improvements that keep this model:** Multi-AZ RDS (automatic failover in a minute or two), and several stateless app instances behind a load balancer, since the app holds no state.

## 5. Observability: what would page me at 2am

Signals already exported: `reservations_held_total`, `reservations_confirmed_total`, `reservations_declined_total{reason}`, `seats_available{show_id}`, HTTP counts by status, connection-pool usage, JVM metrics, and JSON logs with `request_id` in CloudWatch. Every one of the burst's responses reconciles with these counters exactly, and with a CloudWatch Logs Insights count of the logs (shown in the recording).

**Page (wake someone up):**
- **Any sustained 5xx rate,** from `http_server_requests` with status 5xx. Declines are 409s by design, so a 5xx means something is genuinely broken.
- **Readiness failing / `503 service-unavailable` spikes:** the database is unreachable or saturated, and the sale is effectively down.
- **Connection-pool saturation:** pending acquisitions > 0 sustained, or acquire time p99 above about 1s. It's the early warning before timeouts become 503s.
- **On-sale stalled:** during a scheduled sale, `reservations_held_total` stops increasing while requests keep arriving.

**Ticket, not page:**
- high p99 latency without errors
- EC2 / RDS CPU-credit balance trending to zero (both are burstable `t4g` instances)
- disk use
- certificate renewal failures (Caddy renews automatically)

**Never page on** `seat-taken` or `per-user-limit` volume: that is the system working.

## 6. AI usage: directed vs decided

I built this with an AI coding assistant (Claude, in Claude Code). The division of work:

- **I directed and decided; the assistant proposed and implemented.** For every design choice, the assistant laid out options with pros and cons, and I chose; I sometimes picked differently from its lean. Every choice is recorded with its provenance in a decision log (about 70 entries), which is the source for this section. Examples of my decisions:
  - time-boxed holds with a confirm step, over explicit cancel
  - the `Idempotency-Key` header, scoped per user and show
  - `200` for replays, and `409` (not `410`) for an expired confirm
  - plain JDBC over JPA
  - Spring Boot 4 on AWS (EC2 + RDS) with a dedicated, least-privilege IAM user
  - an adversarial burst script, plus planted-bug checks of the test suite
- **The assistant wrote most of the code and tests** against a plan we agreed up front (12 steps, in [`plan/`](plan/)). I reviewed each step and made every commit myself, so the history reflects the actual pace of work.
- **The assistant found things I then decided on.** Specific cases:
  - The confirm-vs-expiry race in §3: it spotted that a naive delete could free a just-confirmed seat and designed the guarded transition; I approved it.
  - A Postgres lock timeout leaking out as a 500, caught by a test it wrote.
  - The live burst being limited by app CPU, not the database.
- **Where it was wrong, and how that surfaced:**
  - The first burst script reported "double-sells" that were really seats legitimately re-booked after holds expired mid-run. The run was slow because of a slow HTTP client, measured at ~137 req/s versus ~10,000 for the replacement. I asked whether the failures came from the script or the database; a database query showed no overlap, and the checks were rewritten to be time-aware.
  - Deliberately planting 18 bugs showed the original tests missed 3 expiry and lock-ordering cases. Tests were added until all 18 were caught.

## 7. What I'd do next

- **Availability:** Multi-AZ RDS, 2+ app instances behind a load balancer, a faster readiness check (it currently takes ~30s to report a DB outage, because it waits on the pool's connection timeout).
- **Capacity:** the live bottleneck is app CPU on a 2-vCPU instance (~520 req/s warm; RDS CPU stayed ~10%). A larger or second instance, plus JVM warm-up before a sale, would raise it.
- **Product:** an explicit cancel endpoint to release holds early, real login (OIDC) instead of the stand-in token endpoint, payment integration on confirm, per-user and per-IP rate limiting, paging for very large seat maps.
- **Operations:** alert rules for §5 (CloudWatch alarms or Prometheus + Alertmanager), infrastructure as code (Terraform) for the hand-built AWS setup, CI running the test suite and a short burst on every push.
- **Housekeeping:** a periodic sweep of long-expired holds, purely to keep tables small; correctness doesn't depend on it.
