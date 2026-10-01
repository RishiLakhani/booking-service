# Step 12 — WRITEUP.md

**Goal:** the short write-up required by Deliverable 5.
**Serves:** Deliverable 5.

## Sections → source material
| Section | Source |
|---|---|
| Atomic decision (exact mechanism, why race-free, multi-seat deadlock) | `uq_seat_taken`; fixed lock order `user_show` → expired reservations by id → sorted seat inserts |
| Idempotency (where stored, exactly-once, same-key-different-body) | `reservations` + `uq_reservation_idem`; `user_show` lock serializes; `seats_sorted` comparison → 409 |
| Holds & expiry | reserve → held (TTL, lazy expiry) → confirm; confirm-vs-expiry race resolved by guarded status transition under the row lock |
| Consistency vs availability under partition | single Postgres = CP; readiness fails closed (503) when the DB is unreachable; no writes accepted without the DB |
| Observability: what pages at 2am | any 5xx, readiness failing, invariant drift (gauge vs counts), pool saturation / p99 latency |
| AI usage (directed vs decided) | decision log: AI proposed options + pros/cons; the developer decided; cite specific decisions and the confirm-vs-expiry race fix |
| What's next | notes collected during execution (e.g. sweeper, best-effort mode, rate limiting) |

## Tasks
- Keep a running "notes for WRITEUP" list while executing steps 1–10.
- Write it last, from real burst results.
