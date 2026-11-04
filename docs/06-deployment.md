# Deployment

## What runs

Three containers on one bridge network:

| Container | Image built from | Port | Notes |
|---|---|---|---|
| `attendance-mysql` | `mysql:8.4` | 3306 | Named volume `mysql-data`; server timezone pinned to UTC |
| `attendance-backend` | `backend/Dockerfile` | 8080 | Non-root user; Flyway migrates on start |
| `attendance-frontend` | `frontend/Dockerfile` | 80 | nginx serves the built SPA and proxies `/api/` to the backend |

The frontend proxies the API rather than calling it cross-origin, so in this
topology there is no CORS exchange at all. `VITE_API_BASE_URL` is baked in at
build time as `/api/v1`.

## Before the first deploy

```bash
cp .env.example .env
```

Then fill in, at minimum:

```bash
# 64 random bytes. The application refuses to start on the committed placeholder.
openssl rand -base64 64
```

- `JWT_SECRET` — the value above. Anyone holding it can mint a token for any employee.
- `MYSQL_ROOT_PASSWORD`, `DB_PASSWORD` — distinct, and neither reused elsewhere.
- `CORS_ALLOWED_ORIGINS` — the exact front-end origins, `https://` only.
- `REFRESH_COOKIE_SECURE=true`
- `SWAGGER_ENABLED=false`
- `SPRING_PROFILES_ACTIVE=prod`

## Bring it up

```bash
docker compose up -d --build
```

The backend waits for MySQL to report healthy, then runs Flyway. Watch it land:

```bash
docker compose logs -f backend
```

## The startup gate

The application validates its own configuration on startup and **refuses to
serve** outside a dev profile if any of these hold:

- `JWT_SECRET` is unset, is the committed placeholder, or decodes to under 64 bytes
- `REFRESH_COOKIE_SECURE` is false
- CORS allows `*`, or a plain-HTTP origin that is not localhost
- Swagger UI is enabled
- BCrypt strength is under 10
- The bootstrap administrator still has the password published in `V6__seed_reference_data.sql`

Each failure names itself and says what to do. On a dev profile the same checks
log as warnings instead, because a local checkout that will not start is its own
kind of problem.

## The bootstrap account

`V6` seeds `EMP0001` with a **publicly known** password so the system can be
opened at all. `must_change_password` stops it being used directly, but it does
not stop someone signing in with the published password and rotating it
themselves — which hands over a SUPER_ADMIN account.

First thing after the first deploy:

1. Sign in as `EMP0001`, set a real password.
2. Create a named administrator for whoever actually runs the system.
3. Deactivate `EMP0001`.

Until step 1, the startup gate refuses to run under a production profile.

## Health and probes

| Path | Purpose | Auth |
|---|---|---|
| `/actuator/health/liveness` | Container healthcheck | public |
| `/actuator/health/readiness` | Ready for traffic | public |
| `/actuator/health` | Aggregate | public |
| everything else under `/actuator` | Diagnostics | requires `AUDIT_VIEW` |

The backend image declares its own `HEALTHCHECK` against liveness, so
`docker compose ps` reports honestly.

## Backups

The only stateful thing is MySQL.

```bash
docker compose exec mysql \
  mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction \
  --routines --triggers attendance_db > backup-$(date +%F).sql
```

`--single-transaction` matters: without it the dump locks tables and punches
fail for its duration.

Never prune `audit_logs`. It is the one table the application cannot rewrite,
and its value is precisely that it is complete.

## Upgrading

Flyway migrations are forward-only and run automatically. An applied migration
is never edited — a mistake is corrected by a new one. Take a backup first
regardless; a failed migration leaves the schema half-changed and the fastest
way back is a restore.

```bash
git pull
docker compose up -d --build
docker compose logs -f backend    # confirm the migration applied
```

## Timezone

The JVM is pinned to UTC in `main()` before anything reads a clock, and every
instant is stored UTC. Business dates resolve in the *office's* timezone, taken
from `offices.timezone`. `COMPANY_TIMEZONE` is only the fallback for an office
that has none.

Do not set a non-UTC timezone on the containers. A shift stored as `09:30`
comes back as `15:00` on an IST-configured JVM, and every late mark, early exit
and full-day calculation is then wrong.

## What is not built

Stated plainly so nobody discovers it in production:

- **Exports run synchronously**, capped at 50,000 rows. Past that the request is
  refused with a clear message rather than queued. There is no async export job.
- **No email is sent.** Notifications are in-app only; the `email` column on
  `notification_preferences` is a placeholder.
- **Rate limiting is per instance**, held in memory. Behind more than one
  backend replica the effective limit multiplies by the replica count. A shared
  limiter is the change to make when scaling out.
- **No object storage.** Leave attachments are stored by URL; nothing uploads.
