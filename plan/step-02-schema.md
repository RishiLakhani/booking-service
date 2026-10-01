# Step 2 — Schema (Flyway) + JDBC access

**Goal:** the tables and named constraints from the design (`shows`, `seats`, `reservations`, `reservation_seats`, `user_show`), plus thin JDBC repository classes.
**Serves:** bar 1 (`uq_seat_taken`), 4 (`uq_reservation_idem`), 5 (`user_show`).

## Tasks
- `V1__init.sql`: `shows`, `seats`, `reservations` (with `status`, `expires_at`), `reservation_seats`, `user_show`, using **named** constraints `uq_seat_taken` and `uq_reservation_idem`. Index on `reservations(show_id, user_id)`.
- Repository classes using `JdbcTemplate` / `NamedParameterJdbcTemplate`:
  - `ShowRepo`: insert show, batch-insert seats, find show, show-state query (§1 effective status)
  - `UserShowRepo`: `insertIfAbsent`, `lockForUpdate`
  - `ReservationRepo`: find by idem key, count active seats for user, lock expired holds for seats, mark expired, insert, confirm (guarded update)
  - `ReservationSeatRepo`: insert sorted, delete by reservation ids
- Records for row mapping.

## Done when
The app boots, Flyway applies V1, and the tables/constraints exist (`\d reservation_seats` shows `uq_seat_taken`).

## Open questions
- UUID generation: DB `gen_random_uuid()` vs Java `UUID.randomUUID()`.
