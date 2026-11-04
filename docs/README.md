# Employee Attendance & Leave Management System — Architecture

Enterprise HRMS attendance platform. React (Vite + TypeScript) SPA → Spring Boot REST API → MySQL.

## Documents

| # | Document | Covers |
|---|----------|--------|
| 01 | [System Architecture](01-architecture.md) | Deployment topology, layered backend, module breakdown, roles & permission matrix |
| 02 | [Project Structure](02-project-structure.md) | Spring Boot package tree, React folder tree |
| 03 | [Database Design](03-database-design.md) | Full MySQL schema, relationships, indexes, ER explanation |
| 04 | [Workflows](04-workflows.md) | Punch-in / punch-out, geofencing, leave approval, regularization |
| 05 | [API Design](05-api-design.md) | REST conventions, response envelope, error codes, endpoint catalogue |

## Technology decisions (locked unless you say otherwise)

| Layer | Choice | Reason |
|-------|--------|--------|
| Backend | Spring Boot 3.5.16, compiled to Java 21 bytecode | Widest ecosystem validation for Hibernate/Flyway/Bucket4j |
| Build | Maven 3.9.16 via Maven Wrapper (`mvnw`) | No global Maven install required |
| Persistence | Spring Data JPA + Hibernate 6.6, MySQL 8.x | |
| Migrations | **Flyway** (versioned SQL) | Explicit, reviewable DDL; `ddl-auto=validate` in every environment |
| Security | Spring Security 6 + JJWT 0.12, BCrypt (strength 12) | Stateless access token + rotating refresh token |
| Frontend | React 19 + **Vite** + **TypeScript (strict)** | |
| UI | **Tailwind CSS 3 + Material Symbols** | Matches the supplied Stitch mockups (see below) |
| Server state | TanStack Query v5 | Cache, background refetch, mutation invalidation |
| Client state | Zustand (auth/session/UI only) | Small; avoids Redux boilerplate |
| Forms | React Hook Form + Zod | Schema mirrors backend DTO validation |
| HTTP | Axios with interceptors (auth header, 401 refresh-and-retry) | |
| Testing | JUnit 5 + Spring Boot Test (H2) / Vitest + RTL | See the note on H2 below |
| Container | Dockerfile (multi-stage) each + docker-compose (mysql, backend, frontend) | |

### Changed since the first draft

- **Tailwind CSS instead of MUI.** The `stitch_enterprise_attendance_leave_system/` mockups are Tailwind markup with Material Design 3 token names and Material Symbols icons. Rebuilding them in MUI would have meant re-deriving every screen and still not matching. The `kinetic_enterprise/DESIGN.md` tokens are transcribed into `frontend/tailwind.config.js`, so the mockups and the app share one palette, type scale and spacing rhythm. Headless components (TanStack Table, react-day-picker) will cover the DataGrid and date-picker roles MUI X would have filled.
- **JDK 26 is fine.** The concern raised in the first draft was tested, not assumed: Spring Boot 3.5.16, Hibernate 6.6.53 and Spring Security 6 all start and run correctly on the installed JDK 26. No JDK 21 install is needed. `<java.version>21</java.version>` keeps the emitted bytecode at 21.
- **Tests use H2, not Testcontainers.** Docker is not installed on this machine, so Testcontainers cannot start MySQL. Tests run against H2 in MySQL mode with Hibernate generating the schema and Flyway disabled. The MySQL DDL in `db/migration` therefore stays the single source of truth for real environments and is *not* exercised by the test suite — it is verified by running the app against MySQL. Swap the test profile to Testcontainers once Docker is available.

## Environment (this machine, verified 2026-08-31)

| Tool | State |
|---|---|
| Java | **26.0.1** at `C:\Program Files\Java\jdk-26.0.1` — works; `JAVA_HOME` is unset but the wrapper resolves it |
| Maven | Not on PATH — supplied by the committed wrapper (`backend/mvnw`) |
| MySQL | **8.0.46 installed and running** as service `MySQL80` on port 3306. Client is at `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe`, not on PATH. `root` has a password |
| Node | 24.18 / npm 11.16 |
| Docker | Not installed — `docker-compose.yml` is written but unverified on this machine |

## Cross-cutting rules

1. **Identity never comes from the request body.** Every secured operation resolves the employee from the JWT principal. DTOs carrying `employeeId` for self-service endpoints are rejected at the controller boundary.
2. **All instants stored UTC** (`TIMESTAMP`, Java `Instant`, `hibernate.jdbc.time_zone=UTC`). The *business date* (`attendance_date`, leave dates, holiday dates) is a `DATE` resolved in the **office's** timezone, not the server's or the browser's.
3. **Attendance is never silently overwritten.** `attendance` holds the computed daily state; `attendance_punch_events` is an append-only event log; regularization writes a new corrected state while preserving the original values on the regularization row.
4. **No hardcoded policy.** Grace period, full-day hours, half-day thresholds, geofence radius, break caps, overtime rules, approval levels and weekly-offs all resolve from policy tables at runtime.
5. **Everything paginated.** No unbounded list endpoint ships. Default page size 20, max 100.

## Build sequence

| Phase | Scope | Status |
|-------|-------|--------|
| 1 | Architecture, DB design, backend + frontend scaffolding | **done** |
| 2 | Authentication, JWT + refresh, roles & permissions | **done** |
| 3 | Employee, Department, Designation, Office, Shift masters | **done — 27 tests, verified end to end against MySQL** |
| 4 | Punch in/out, geofencing, attendance calculation | **done — 86 tests, verified end to end against MySQL** |
| 5 | Attendance dashboard, history, calendar | **done** — manager/HR views landed in Phase 8 |
| 6 | Leave management, balances, approval workflow | **done — 112 tests, verified end to end against MySQL** |
| 7 | Regularization, WFH | **done — 143 tests, verified end to end against MySQL** |
| 8 | Manager dashboard, HR dashboard | **done — 173 tests, verified end to end against MySQL** |
| 9 | Reports, notifications, audit logs | **done — 193 tests, verified end to end against MySQL** |
| 10 | Testing, security hardening, Docker, deployment | **done — 206 tests; see docs/06-deployment.md** |
