# Step 4 — Create show / show state

**Goal:** `POST /shows` (admin) and `GET /shows/{id}`.
**Serves:** bar 3 (invariant).

## Tasks
- `POST /shows`: validate the name, non-empty unique seats, and integer `price_paise >= 0`. Insert the show and batch-insert its seats in one transaction. Return 201 with every seat `available`.
- `GET /shows/{id}`: **one** SQL query computing effective status per seat (no row → available; confirmed → confirmed; held and not expired → held; held but expired → available) plus counts. Expired holds count as `available`.

## Done when
- A new 100-seat show → `available=100, held=0, confirmed=0`.
- `available + held + confirmed == total_seats` at all times (computed in one snapshot).

## Open questions
- Is `per_user_limit` accepted in the body?
- Does `GET /shows/{id}` require auth?
- Batch insert size for large N.
