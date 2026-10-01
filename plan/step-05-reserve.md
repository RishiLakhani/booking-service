# Step 5 — Reserve (core)

**Goal:** `POST /shows/{id}/reserve` as one transaction, with idempotency via the `Idempotency-Key` header.
**Serves:** bar 1, 2, 4, 5, 6.

## Flow (`ReservationService.reserve`, one `@Transactional`)
1. **Validate:** seats non-empty, no duplicates; `Idempotency-Key` header present (1–255 chars) → else 400. Unknown show → 404. Unknown seat → 400. Sort the seats.
2. **Lock** the `user_show` row, which serializes this user's requests for this show.
3. **Idempotency:** look up (user, show, key).
   - Different seats → 409 `idempotency-mismatch`.
   - Hold expired → 409 `hold-expired`.
   - Otherwise → **200** replay of the original.
4. **Per-user limit:** active seats + requested > 4 → 409 `per-user-limit`.
5. **Lazy expiry:** lock expired holds on the requested seats, mark them `expired`, delete their seat rows.
6. **Claim:** insert the reservation (`held`, `expires_at` = DB `now()` + `HOLD_TTL`), then seat rows in sorted order.
   - A `uq_seat_taken` violation → 409 `seat-taken`.
   - Otherwise → **201**.

Any decline throws `ReservationDeclinedException`, which rolls the transaction back. A small handler maps it to `409 {"error": "<reason>", "message": ...}`.

Reads report a `held` reservation past its expiry as `expired`. `HOLD_TTL` is an env var, default 2 minutes.

## Tests (Testcontainers, real threads, real Postgres)
- Hot seat: 50 users, one seat → exactly 1 created, 49 `seat-taken`.
- Per-user limit: 10 parallel requests from one user → exactly 4 created, 6 `per-user-limit`.
- Idempotency: same key 10× in parallel → 1 created, 9 replayed, one reservation id; same key + different seats → `idempotency-mismatch`.
- Overlapping multi-seat requests in alternating order → no deadlock, no partial holds (held seats = 2 × successes).
- The invariant holds after each test.

## Verified manually
- A spoofed `user_id` in the body is ignored.
- Replay → 200; mismatch → 409; seat taken → 409; over limit → 409; missing key / unknown seat / duplicate seats → 400; no token → 401.
- After expiry: replay → 409 `hold-expired`; another user can take the seat (lazy expiry).
