# Step 3 — JWT auth + token endpoint

**Goal:** identity comes only from an HS256 JWT (`sub` = user id, `role` = `user` | `admin`), and testers can obtain tokens.
**Serves:** bar 6; lets the burst script obtain tokens for many users.

## Tasks
- Spring Security OAuth2 resource server (built-in Nimbus) with HS256, signed with `JWT_SECRET`.
- `POST /auth/token {"user_id": "...", "role": "user|admin"}` → signed JWT valid for 2 hours.
  - `role` defaults to `user`.
  - `admin` requires an `X-Admin-Secret` header matching `ADMIN_SECRET` (constant-time compare).
- `JWT_SECRET` (≥ 32 chars) and `ADMIN_SECRET` are required env vars with no defaults; startup fails without them. Local development reads a git-ignored `.env` (template: `.env.example`).
- The `role` claim maps to `ROLE_USER` / `ROLE_ADMIN`.
- Rules:
  - `POST /shows` → admin
  - health, prometheus and `POST /auth/token` → anonymous
  - everything else → authenticated
- Controllers read identity only from the authenticated principal. Request DTOs have **no** user field, so a spoofed `user_id` in the body is ignored (verified in step 5).
- Missing/invalid/expired token → 401; wrong role → 403.

## Done when
- No token → 401; tampered token → 401; expired token → 401 (after Spring's default 60s clock-skew allowance).
- User token on `POST /shows` → 403; admin token passes.
- Admin token without / with a wrong `X-Admin-Secret` → 403.
- Startup fails with a clear message when secrets are missing or too short.
