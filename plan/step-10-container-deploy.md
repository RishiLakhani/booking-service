# Step 10 — Container + deploy (AWS EC2 + RDS)

**Goal:** a clean checkout runs the same way it's deployed, and the public URL is healthy and survives restarts.
**Serves:** Deliverables 2 and 4; bar 2 over the real network.

## Container
- Multi-stage `Dockerfile`: a JDK image builds the boot jar, a JRE image runs it as a non-root user, with `-XX:MaxRAMPercentage=60`.
- One `docker-compose.yml` with profiles:
  - `local` = `postgres` + `app`
  - `aws` = `app` + `caddy`
  - Secrets and the DB URL come from `.env`; the app refuses to start without `JWT_SECRET` / `ADMIN_SECRET`.
  - `DB_POOL_SIZE` sets the connection pool (default 10).
- `Caddyfile`: automatic Let's Encrypt HTTPS for `PUBLIC_HOST` (an `sslip.io` name for the Elastic IP).

## AWS (`ap-south-1`, everything tagged `project=booking-service`)
- **Identity:** dedicated IAM user `booking-service-deployer` with scoped policies; the admin profile was used only to create it.
- **Network:** recreated default VPC.
  - `booking-service-app` security group: 80/443 from anywhere, no SSH.
  - `booking-service-db` security group: 5432 only from the app group.
- **RDS:** `db.t4g.micro`, PostgreSQL 16.15, 20 GB gp3, single-AZ, not public, encrypted. SSL is required by the JDBC URL.
- **EC2:** `t4g.small` (ARM), Amazon Linux 2023, 20 GB encrypted gp3, IMDSv2 required, Elastic IP.
  - The first-boot script installs Docker + Compose/buildx, adds 2 GB swap, sets the `awslogs` log driver, and clones the repo.
  - Instance role `booking-service-ec2`: CloudWatch agent + SSM core.
- **Secrets:** SSM Parameter Store `SecureString` (`/booking-service/*`). The deploy script writes them to a root-only `.env` on the instance.
- **Logs:** CloudWatch Logs group `/booking-service`, 7-day retention, one stream per container.
- **Deploy:** via SSM Run Command: `git pull` → write `.env` from Parameter Store → `docker compose --profile aws up -d --build`.

## Verified
- HTTPS with a valid Let's Encrypt certificate; HTTP redirects to HTTPS; port 8080 is not reachable from the internet.
- Readiness 200 against RDS.
- Live 20k burst: all 22 checks pass, 0 5xx.
  - Pool 10 → 337 req/s (connection waits).
  - Pool 30, warm JVM → ~520 req/s; EC2 CPU is now the limit, while RDS CPU stays ~10%.

## Remaining
- Screen recording of the live log tail under load (user).
