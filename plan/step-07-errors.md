# Step 7 — Error → 4xx mapping

**Goal:** every domain outcome is a clean 4xx; nothing leaks as a 5xx; every error has one shape.
**Serves:** bar 2.

## Tasks
- One error format everywhere: `{"error": "<code>", "message": "..."}` with short, general messages (no field-by-field dumps).
- Services throw domain exceptions (package `exception`) and never HTTP types: `InvalidRequestException` 400, `ForbiddenException` 403, `NotFoundException` 404, `ReservationDeclinedException` 409.
- `ApiExceptionHandler` (package `exception`, extends `ResponseEntityExceptionHandler`) is the only place statuses are chosen:
  - 409 declines with their reason code
  - `ResponseStatusException` with the service's own message
  - validation, malformed JSON, wrong types, missing header, bad path value → 400 `invalid-request`
  - unknown path → 404; wrong method → 405; wrong content type → 415
  - database busy/unreachable (no pooled connection in time, lock timeout, deadlock victim) → 503 `service-unavailable` with `Retry-After: 1`
  - anything unexpected → 500 `internal-error`, logged in full, returned without internals
- Spring Security's 401/403 (raised before controllers) use the same format via custom entry point / access-denied handlers. The standard `WWW-Authenticate` header is kept.

## Codes
`invalid-request` 400 · `unauthorized` 401 · `forbidden` 403 · `not-found` 404 · `method-not-allowed` 405 · `unsupported-media-type` 415 · `seat-taken` / `per-user-limit` / `idempotency-mismatch` / `hold-expired` 409 · `internal-error` 500 · `service-unavailable` 503

## Done when
No token, tampered token, wrong role, malformed JSON, float price, empty seats, missing header, bad UUID, unknown show/path, wrong method, wrong content type, unknown seat and seat taken each return the expected status with the unified body.

## 5xx safeguard
- Every pooled connection runs with `statement_timeout = 10s` and `lock_timeout = 5s` (Hikari `connection-init-sql`), so nothing can hold a connection indefinitely. Both timeouts return 503; Postgres lock timeouts (`55P03`) are mapped explicitly, because Spring leaves them uncategorized.
- Tests: the settings are applied; a lock wait and a long query each time out and map to 503.
