# Step 12 — WRITEUP.md

**Goal:** the short write-up required by Deliverable 5 (about 2–3 pages).
**Serves:** Deliverable 5.

## Sections
1. **Atomic decision:** `uq_seat_taken` unique index; all-or-nothing transaction; sorted seat claims and a fixed lock order against deadlocks; the per-user row lock for the limit.
2. **Idempotency:** `Idempotency-Key` header stored under `UNIQUE (user, show, key)`; serialized by the per-user lock; `200` replay; `409 idempotency-mismatch`; an expired key returns `409 hold-expired`.
3. **Holds and expiry:** time-boxed holds, lazy expiry, the confirm-vs-expiry race and its guarded-transition fix, the database clock.
4. **Consistency vs availability:** a single primary (consistency first); `503` + readiness when the DB is unreachable; client retries via idempotency keys.
5. **What pages at 2am:** sustained 5xx, readiness/503 spikes, pool saturation, a stalled on-sale; tickets vs never-page.
6. **AI usage:** directed vs decided, with specific examples and the mistakes that were caught.
7. **What's next.**

## Sources
The decision log, the design notes, burst results (local and live), the planted-bug check, and the live performance analysis.

## Done when
The write-up is linked from the README and reviewed by the user. ✅
