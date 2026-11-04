# Attendance Pro

Attendance and leave management for Share India. Geofenced punch in and out, shift-aware hour
calculation, multi-level approvals, leave and balances, morning meetings, weekly study exams and a
company holiday calendar.

React (Vite · TypeScript · Tailwind) → Spring Boot REST API → MySQL.

Architecture lives in [`docs/`](docs/README.md). Read that before changing anything structural.
Deployment on AWS Lightsail is [`docs/08-lightsail-deployment.md`](docs/08-lightsail-deployment.md).

## What it does

| Area | |
|---|---|
| **Attendance** | Punch in and out against a geofence, breaks, shift-aware working hours, late and early-exit minutes, overtime |
| **Corrections** | Regularisation requests and work-from-home, each with its own approval chain |
| **Leave** | Leave types, balance ledger with reservations, holiday-aware day counting, multi-level approval |
| **Calendar** | Company holidays, weekly offs including alternate-Saturday rules, per-employee shift assignment |
| **Meetings** | Morning meetings with click-through join tracking and a snapshotted roster |
| **Exams** | Weekly study exams: question bank import, timed attempts, scoring, retakes |
| **Oversight** | Team and organisation dashboards, twelve reports exporting to CSV, Excel and PDF, notifications, audit trail |
| **Administration** | Employees, departments, designations, offices and geofences, shifts, holidays, roles and permissions, bulk CSV import |

Runs on phones as well as desktop — iOS and Android layouts, safe-area insets, 44px touch targets.

## Running it locally

Requires **MySQL 8**, **JDK 21+** and **Node 20+**.

### 1. Create the database

Run this yourself — it sets a password:

```sql
CREATE DATABASE IF NOT EXISTS attendance_db
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'attendance_app'@'localhost' IDENTIFIED BY 'CHOOSE_A_PASSWORD';
GRANT ALL PRIVILEGES ON attendance_db.* TO 'attendance_app'@'localhost';
FLUSH PRIVILEGES;
```

On Windows the MySQL client is usually not on `PATH`; it lives at
`C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe`.

### 2. Configure

Copy `.env.example` to `.env` and fill in `DB_PASSWORD` and `JWT_SECRET`. Generate a secret with
`openssl rand -base64 64`. It must base64-decode to at least 64 bytes — the application refuses to
start otherwise rather than silently signing with a weak key.

The backend reads `.env` itself, so no credentials go on the command line or into shell history.

### 3. Start the backend

```bash
cd backend && ./mvnw spring-boot:run
```

Flyway applies `V1`–`V20` on a fresh database: schema, the role and permission catalogue, reference
data and a bootstrap administrator.

### 4. Start the frontend

```bash
cd frontend && npm run dev
```

On Windows PowerShell with script execution disabled, `npm` fails with a `PSSecurityException`.
Use `npm.cmd run dev` — it is a batch shim, so the execution policy does not apply.

### 5. Sign in

| Field | Value |
|---|---|
| Employee ID | `EMP0001` |
| Password on a fresh database | `ChangeMe@123` |

The account ships flagged `must_change_password`, so the application forces a rotation before
anything else. Changing a password revokes every session, so you are returned to sign-in.

> **This bootstrap account must be deleted or disabled before any real deployment.** Its hash is
> published in `V6`, which is exactly why `StartupSecurityValidator` refuses to serve outside a dev
> profile while it is still in place.

### Ports

The API reads `SERVER_PORT` from `.env` and the dev frontend is pinned to `5175`. The frontend port
is deliberately strict rather than auto-incrementing: a dev server that silently moves is one the
API's CORS list no longer matches. Keep `VITE_API_BASE_URL` and `CORS_ALLOWED_ORIGINS` in step with
whatever you choose.

## Tests

```bash
cd backend && ./mvnw test
```

**308 tests.** Integration tests run against H2 with `create-drop`, which means **migrations are
never exercised by the suite** — schema changes have to be verified against real MySQL separately.

What they hold down, beyond the obvious: identity always comes from the token and never the request
body; a person away for a legitimate reason is never counted absent; an approved correction adds to
the punch evidence rather than replacing it; exam answer keys cannot reach a candidate's DTO; and a
holiday declared through the service actually changes what the calendar reports. A timezone guard
pins the JVM to UTC so shift times cannot drift with the server's offset.

## Layout

```
backend/    Spring Boot API — see docs/02-project-structure.md
frontend/   React SPA — see docs/02-project-structure.md
docs/       Architecture, database design, workflows, API design, deployment
scripts/    Build helpers, including the documentation PDF bundler
stitch_enterprise_attendance_leave_system/
            Source design mockups and the Kinetic Enterprise design system.
            frontend/tailwind.config.js is transcribed from kinetic_enterprise/DESIGN.md —
            change a token there and change it here.
```

## Non-negotiables

Enforced in review, because breaking them is how this class of system goes wrong:

1. **Identity comes from the JWT, never the request body.** A body-supplied `employeeId` on a
   self-service endpoint is ignored.
2. **Attendance is never silently overwritten.** `attendance` is a derived summary;
   `attendance_punch_events` is append-only.
3. **No hardcoded policy.** Grace periods, hour thresholds, geofence radii and approval levels all
   resolve from configuration at runtime.
4. **All instants stored UTC.** Business dates resolve in the *office's* timezone.
5. **Every list endpoint is paginated.** Default 20, hard maximum 100.
6. **Schema changes only through Flyway.** `ddl-auto` is `validate` everywhere.
7. **No scheduler.** Exam windows and meetings settle lazily on the next read. There is no cron
   anywhere, and adding one introduces a failure mode this codebase has deliberately avoided.
8. **New permissions need an explicit ADMIN and SUPER_ADMIN grant.** `V5`'s role grants were
   one-shot `INSERT … SELECT` statements, not rules — a permission added later reaches no role on
   its own, and administrators get 403 on every endpoint behind it.

---

© 2026 Share India. All rights reserved. This source is published for reference and is not
licensed for reuse or redistribution.
