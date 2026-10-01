# Step 9 — Burst script (this IS the grading tool)

**Goal:** `./burst.sh <BASE_URL>` reproduces the on-sale stampede and proves every correctness-bar item with a PASS/FAIL report.
**Serves:** Deliverable 3, and the evidence for bar items 1–6. **Graders have no script of their own; they run this one**, so it must be robust and self-explanatory.

## Tasks
- `burst/burst.py` (asyncio + httpx), `burst/requirements.txt`, `burst.sh`.
- Setup: get tokens from `POST /auth/token`, then create a **fresh show**.
- Scenarios, each with an explicit assertion:
  | Bar | Scenario | Assert |
  |---|---|---|
  | 1 | Hot-seat storm: many users → the same few seats | exactly one 201 per hot seat, rest 409 `seat-taken` |
  | 2 | Whole burst (~20k requests, configurable) | zero 5xx |
  | 3 | Reconciliation during the burst (poll `GET /shows/{id}`) and after it | `available + held + confirmed == total` every time |
  | 4 | Same key resent N times in parallel; same key + different seats | one reservation id; mismatch → 409 |
  | 5 | One user fires 10 parallel reserves on a limit-4 show | ≤ 4 seats held |
  | 6 | Body carries a spoofed `user_id`; user B tries to confirm A's hold | reservation owned by the token user; B is declined |
  | — | Metrics reconcile | `/actuator/prometheus` counters/gauge match API state |
- **Client-side robustness:**
  - Bounded concurrency (semaphore) and an httpx pool sized so a grader's laptop isn't the bottleneck.
  - Report client errors (timeouts, connection resets) **separately** from server 5xx.
  - Warn if `ulimit -n` is too low.
- **Output:** outcome distribution (201 / 409 by reason / other 4xx / 5xx / client errors), final reconciliation, per-assertion PASS/FAIL, overall exit code.
- Flags: `--requests`, `--concurrency`, `--hot-seats`, `--seats`.
- The whole run must finish well within `HOLD_TTL` (default 2 min). Print elapsed time and warn if it's close.

## Done when
A local run against docker-compose and a run against the live URL both show all PASS, with zero 5xx.

## Open questions
- Token source (endpoint vs local minting with a shared secret).
- How graders run it (Python + pip / uv / Docker image).
- Do we still need automated tests besides this?
- Whether the script also confirms some holds (it exercises confirm and makes `confirmed` non-zero in the reconciliation).
