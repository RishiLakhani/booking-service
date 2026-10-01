# Step 9 — Burst script (this IS the grading tool)

**Goal:** `./burst.sh <BASE_URL>` reproduces the on-sale stampede and proves every correctness-bar item with a PASS/FAIL report.
**Serves:** Deliverable 3, and the evidence for bar items 1–6. Graders run this script.

## How it runs
- `burst/burst.py` declares its dependencies inline (PEP 723); `burst.sh` runs `uv run burst/burst.py "$@"`. No virtualenv or `pip install` needed.
- If `uv` is missing, `burst.sh` asks `[y/N]` before running the official installer (into `~/.local/bin`); declining or a non-interactive shell prints the install command and exits 1.
- Python asyncio + **aiohttp**. httpx was measured at ~137 req/s at 500 concurrency, which made a 20k run outlast the hold TTL; aiohttp does ~10k req/s.
- Needs `ADMIN_SECRET` (env var, or `--admin-secret`) to create its own fresh show.
- Defaults: 20,000 requests, 500 concurrent, 10 hot seats, 1,000-seat show; all are flags. `--seed` makes the mix reproducible.

## Phases
1. **Setup:** readiness check, admin token, fresh show, ~3,800 user tokens, metrics snapshot.
2. **Stampede** (all concurrent, shuffled):
   - hot-seat storm (10 seats × 800 requests)
   - overlapping multi-seat requests in random order
   - idempotent retry storm (same key 5–10×)
   - per-user limit attack (10 parallel per user)
   - identity spoofing
   - 13 kinds of invalid requests × 40
   - normal traffic fills the rest
   - A live poller on its own connection checks the invariant throughout.
3. **After the stampede:** key reuse with different seats, plain replays.
4. **Confirm storm:** 60% of holds confirmed (half twice, concurrently); foreign confirms; unknown ids.
5. **Reconciliation:** final show state, metric deltas, seats gauge.
6. **Optional `--expiry-check`:** waits out the TTL, then verifies release, re-booking, and that confirmed seats stay taken.

## Checks are time-aware
Holds expire, so a seat may legitimately change hands after a hold lapses. The script judges:
- one owner per seat **at any moment**
- max 4 **active** seats per user at any moment
- `hold-expired` on confirm only if that hold truly expired
- final state vs the reservations **still active**

A run that outlasts the TTL is reported as such, not as a failure.

## Done when
Local run against docker-compose: all 22 checks pass (26 with `--expiry-check`), zero 5xx, zero client errors. ✅ (20k requests in ~15s.)
