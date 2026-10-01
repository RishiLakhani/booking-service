# Step 10 — Container + deploy (AWS EC2 + RDS)

**Goal:** a clean checkout runs the same way it's deployed, and the public URL is healthy and survives restarts.
**Serves:** Deliverables 2 and 4; bar 2 over the real network.

## Tasks
**Container**
- Multi-stage `Dockerfile` (Gradle build → `eclipse-temurin:21-jre`), JVM memory flags sized for the instance.
- `docker-compose.yml` for local use: `app` + `postgres`, healthcheck, `depends_on: condition: service_healthy`.

**AWS**
- Budget alarm on the account first (free plan = credits).
- RDS PostgreSQL (micro, free-plan eligible, single-AZ, not publicly accessible).
- EC2 instance (free-plan eligible) with Docker; Elastic IP for a stable URL.
- Security groups: the RDS group allows 5432 **only** from the EC2 security group; the EC2 group allows 80/443 publicly and SSH from your IP only.
- Run the app container with the DB env vars, `JWT_SECRET` and `HOLD_TTL`, plus `--restart unless-stopped` so it comes back after a reboot.
- Hikari `maximum-pool-size` sized under RDS `max_connections`; Tomcat `max-connections` / `accept-count` raised.
- Readiness check: verify it returns 503 when RDS is unreachable (e.g. temporarily remove the SG rule).

**Prove it**
- Run the burst against the live URL; capture logs.

## Done when
- `docker compose up --build` from a clean clone works locally.
- The live URL is healthy after an instance reboot; the burst against it shows all PASS with 0 5xx.

## Open questions
- HTTPS approach.
- Deploy mechanism + image registry.
- Log access for graders.
- Region + instance types.
- `HOLD_TTL` for the deployed instance (default 2 min).
