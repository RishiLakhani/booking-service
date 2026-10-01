# Step 5 — Reserve (core)

**Goal:** `POST /shows/{id}/reserve` as a single transaction.
**Serves:** bar 1, 2, 4, 5.

## Tasks
- `ReservationService.reserve(user, showId, seats, key)` in one `@Transactional`:
  validate → upsert + lock `user_show` → idempotency check → limit check (active holds + confirmed) → lazy-expire requested seats (status transition) → insert reservation (`held`, `expires_at`) + sorted seat rows.
- Domain exceptions: `SeatTakenException`, `PerUserLimitException`, `IdempotencyMismatchException`.
- 201 body: `reservation_id, show_id, user_id, seats, amount_paise, status: "held", expires_at`.
- `amount_paise = price_paise * seats.size()` (long arithmetic).
- `HOLD_TTL` from env (default 2 min).

## Done when
- 2 users race for A12 → one 201, one 409 `seat-taken`.
- Same key + same seats → same `reservation_id`; same key + different seats → 409.
- 10 parallel reserves from one user (limit 4) → ≤ 4 seats.
- `["A12","A13"]` with A13 taken → 409, and A12 stays available.
- With `HOLD_TTL` set short: after expiry, another user can reserve the seat.

## Open questions
- Idempotent replay status: 201 vs 200.
- Add a Testcontainers concurrency test, or rely on the burst script?
- Status for unknown seats (400 / 404 / 409).
- Idempotent replay after the hold expired: return original, treat as new, or 409?
