# Step 4 — Create show / show state

**Goal:** `POST /shows` (admin) and `GET /shows/{id}` (any authenticated user).
**Serves:** bar 3 (invariant).

## Tasks
- `POST /shows`:
  - Validate `name` (non-blank), `seats` (non-empty, non-blank, unique) and `price_paise` (integer ≥ 0; a float like `250.5` is rejected, not truncated).
  - Insert the show and its seats in one transaction. `per_user_limit` is fixed at 4.
  - Return 201 with every seat `available`.
- `GET /shows/{id}`:
  - **One** SQL query computes each seat's effective status (no row → available; confirmed → confirmed; held and not expired → held; held but expired → available), in creation order.
  - Counts are derived from that same snapshot.
- `POST /shows` requires an admin token; `GET /shows/{id}` requires any valid token.

## Done when
- A new show returns 201 with `available = total_seats`.
- `available + held + confirmed == total_seats` at all times, including with held, confirmed and expired holds.
- Non-admin create → 403; no token → 401; unknown show → 404; invalid input → 400.
