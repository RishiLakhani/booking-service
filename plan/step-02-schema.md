# Step 2 — Schema (Flyway) + JDBC access

**Goal:** the tables and named constraints from the design (`shows`, `seats`, `reservations`, `reservation_seats`, `user_show`), plus thin JDBC repository classes.
**Serves:** bar 1 (`uq_seat_taken`), 4 (`uq_reservation_idem`), 5 (`user_show`).

## Tasks
- `V1__init.sql`:
  - `shows`
  - `seats` with a `position` column (creation order)
  - `reservations` with `seats text[]`, `status`, `expires_at`
  - `reservation_seats`
  - `user_show`
  - **Named** constraints `uq_seat_taken` and `uq_reservation_idem`. The latter's index also serves lookups by `(user_id, show_id)`, so no extra index is needed.
- Repositories (`repository` package) using `JdbcTemplate`:
  - `ShowRepository`: insert show, insert seats, find show, count existing seats, show-state query (effective status in one snapshot)
  - `UserShowRepository`: `lock`, which creates the row if absent, then `SELECT … FOR UPDATE`
  - `ReservationRepository`: insert held (expiry = DB `now()` + TTL), find by id / idempotency key, count active seats, lock expired holds for seats, mark expired, confirm (guarded update)
  - `ReservationSeatRepository`: insert seats one by one in sorted order; delete by reservation ids
- Records/enums in the `model` package.
- UUIDs are generated in Java.

## Done when
The app boots, Flyway applies V1, and the tables/constraints exist (`\d reservation_seats` shows `uq_seat_taken`).
