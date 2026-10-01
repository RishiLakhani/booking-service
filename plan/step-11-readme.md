# Step 11 — Submission README

**Goal:** a top-level `README.md` that lets graders run, verify and understand the service quickly.
**Serves:** Deliverables 2, 3, 4.

## Contents
- Live URL; how to get tokens (`POST /auth/token`).
- Run locally: `docker compose up --build`.
- Endpoints + sample requests.
- **Documented behaviour:**
  - all-or-nothing
  - reserve → `held` (differs from the brief's sample `confirmed`) → confirm
  - `HOLD_TTL` and lazy expiry
  - idempotency key in the body, scoped per (user, show)
  - replay status
  - per-user limit
- Health, metrics names (+ link), log access.
- **Burst (primary grading tool):** prerequisites, one command `./burst.sh <BASE_URL>`, flags, how to read the PASS/FAIL report, sample output.

## Done when
Someone new can go from clone to running the burst using only the README.

## Open questions
- Include a sample burst output / screenshot?
