# Step 3 — JWT auth + token endpoint

**Goal:** identity comes only from an HS256 JWT (`sub` = user id, `role` = `user` | `admin`), and testers can obtain tokens.
**Serves:** bar 6; lets the burst script obtain tokens for many users.

## Tasks
- HS256 JWT with the secret from env `JWT_SECRET`. Library per the open question below.
- `POST /auth/token {"user_id": "..."}` → signed JWT.
- Controllers read identity only from the authenticated principal. Request DTOs have **no** user field, so a spoofed `user_id` in the body is ignored.
- Secured rules: `/shows` POST → `admin`; reserve/confirm → authenticated; health/prometheus/token → anonymous.
- Missing/invalid token → 401; wrong role → 403.

## Done when
- No token → 401; tampered token → 401; user token on `POST /shows` → 403.
- Reserve with body `"user_id":"bob"` and a token for alice → reservation owned by alice.

## Open questions
- How admin tokens are issued: same endpoint with `role` (open) vs an admin secret header vs an admin token minted offline from `JWT_SECRET`.
- Token expiry duration.
- Library: Spring Security OAuth2 resource server (`NimbusJwtDecoder`/`NimbusJwtEncoder` with HS256), which is standard but heavier; or `jjwt` + a small `OncePerRequestFilter`, which is less code. Spring Boot 4 uses Jackson 3, and `jjwt`'s JSON module is built on Jackson 2, so check compatibility before choosing it.
