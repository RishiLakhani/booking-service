# Step 7 — Error → 4xx mapping

**Goal:** every domain outcome is a clean 4xx; nothing leaks as 5xx.
**Serves:** bar 2.

## Tasks
- `@RestControllerAdvice` handlers for:
  - domain exceptions
  - `DuplicateKeyException` (SQLState 23505), by constraint name from the `PSQLException` cause
  - validation / malformed JSON
  - not found
- Consistent error body `{"error","message"}`.
- Handle pool / lock timeouts explicitly. Ideally they never happen once the pool is tuned.

## Done when
Malformed JSON, missing fields, a wrong show id, a taken seat, over-limit, and an expired confirm each return the expected 4xx with a reason code.

## Open questions
- Should a pool timeout map to 503 (honest) or stay a 500? Both count as 5xx, so tuning has to prevent them.
