# Step 6 — Confirm

**Goal:** `POST /reservations/{id}/confirm`: owner-only, guarded transition `held → confirmed`.
**Serves:** bar 1 ("never confirmed to two users"), bar 6 (act only on your own holds); "a release must never resurrect a confirmed seat".

## Flow (`ReservationService.confirm`, one `@Transactional`)
1. Guarded update: `UPDATE … SET status='confirmed' WHERE id AND user_id = token user AND status='held' AND expires_at > now()`. One row updated → **200**.
2. Otherwise, read the reservation to pick the response:
   - not found → **404**
   - another user's → **403**
   - already confirmed → **200** with it unchanged (safe to retry)
   - expired → **409 `hold-expired`** (same code as reserve)

A confirm racing a lazy expiry is settled by the row lock: both write the same reservation row, guarded on `status='held'`, so exactly one wins. Confirmed seats never expire.

## Tests
- Owner confirm; repeat confirm returns the same reservation.
- Non-owner → 403, and the hold is unchanged. Unknown id → 404.
- 10 parallel confirms by the owner all succeed.
- With a 1-second hold: expired confirm → `hold-expired`, and the seat is re-bookable; a **confirmed** seat stays confirmed after the TTL, and another user gets `seat-taken`.

## Verified manually
403 / 200 / 200 (repeat) / 404 / 409 `hold-expired` / 401 (no token).
