# 05 — API Architecture

Base path `/api/v1`. JSON only. Bearer JWT on everything except `/api/v1/auth/**` and `/actuator/health`.

## 5.1 Response envelope

Every response — success or failure — has the same shape, so the frontend has exactly one unwrapping path.

```json
{
  "success": true,
  "message": "Punch in successful",
  "data": { "attendanceId": 90211, "punchInTime": "2026-08-31T04:02:11Z", "status": "PUNCHED_IN" },
  "errorCode": null,
  "timestamp": "2026-08-31T04:02:11.412Z",
  "path": "/api/v1/attendance/punch-in",
  "traceId": "8f2c1a7e-…"
}
```

```json
{
  "success": false,
  "message": "You are outside the permitted office location",
  "errorCode": "OUTSIDE_GEOFENCE",
  "data": { "nearestOffice": "Mumbai Office - Tower B", "distanceMeters": 412.7, "allowedRadiusMeters": 200 },
  "timestamp": "2026-08-31T04:02:11.412Z",
  "path": "/api/v1/attendance/punch-in",
  "traceId": "8f2c1a7e-…"
}
```

Validation failures add a field list:

```json
{
  "success": false,
  "message": "Validation failed",
  "errorCode": "VALIDATION_ERROR",
  "data": { "fieldErrors": [{ "field": "fromDate", "message": "must not be in the past" }] }
}
```

### Paged responses

```json
{
  "success": true,
  "data": {
    "content": [ … ],
    "page": 0, "size": 20, "totalElements": 1284, "totalPages": 65,
    "first": true, "last": false, "sort": "attendanceDate,desc"
  }
}
```

## 5.2 Error codes → HTTP status

| HTTP | `errorCode` |
|---|---|
| 400 | `VALIDATION_ERROR`, `INVALID_DATE_RANGE`, `INVALID_REQUEST` |
| 401 | `INVALID_CREDENTIALS`, `TOKEN_EXPIRED`, `TOKEN_INVALID`, `REFRESH_TOKEN_REUSED` |
| 403 | `ACCESS_DENIED`, `ACCOUNT_LOCKED`, `ACCOUNT_INACTIVE`, `NOT_AUTHORIZED_APPROVER` |
| 404 | `EMPLOYEE_NOT_FOUND`, `ATTENDANCE_NOT_FOUND`, `LEAVE_REQUEST_NOT_FOUND`, `RESOURCE_NOT_FOUND` |
| 409 | `ALREADY_PUNCHED_IN`, `ALREADY_PUNCHED_OUT`, `NOT_PUNCHED_IN`, `BREAK_ALREADY_OPEN`, `ATTENDANCE_ALREADY_EXISTS`, `OVERLAPPING_LEAVE`, `DUPLICATE_REGULARIZATION`, `CONCURRENT_MODIFICATION` |
| 422 | `OUTSIDE_GEOFENCE`, `INSUFFICIENT_LOCATION_ACCURACY`, `OUTSIDE_PUNCH_WINDOW`, `LEAVE_BALANCE_INSUFFICIENT`, `POLICY_VIOLATION`, `WFH_NOT_APPROVED`, `NO_SHIFT_ASSIGNED`, `ON_APPROVED_LEAVE` |
| 429 | `RATE_LIMIT_EXCEEDED` |
| 500 | `INTERNAL_ERROR` (message is generic; the `traceId` is the link to the log) |

`ErrorCode` is a Java enum carrying its own HTTP status and default message, so a code cannot be returned with an inconsistent status.

## 5.3 Conventions

| Concern | Rule |
|---|---|
| Naming | Plural kebab-case nouns (`/leave-requests`), verbs only for actions (`/punch-in`, `/{id}/approve`) |
| Self-service | `/me` sub-resources (`/attendance/me/today`) — never `/{employeeId}` for one's own data |
| Identity | Employee resolved from JWT. A body-supplied `employeeId` on a self-service endpoint is ignored |
| Pagination | `?page=0&size=20&sort=field,desc` — default 20, **max 100**, enforced server-side |
| Filtering | Typed query params bound to a `*Filter` DTO → JPA `Specification` |
| Dates | Request/response `LocalDate` as `yyyy-MM-dd`, instants as ISO-8601 UTC (`…Z`) |
| Idempotency | `Idempotency-Key` header honoured on punch and request-submission endpoints |
| Versioning | Path version `/v1`; breaking changes ship as `/v2`, never as a silent shape change |
| Docs | springdoc-openapi at `/swagger-ui.html`, disabled in prod |
| Correlation | `X-Request-Id` accepted or generated; echoed as `traceId` |

## 5.4 Endpoint catalogue

### Authentication — `/api/v1/auth`
| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/login` | public | `{usernameOrEmail, password}` → access token + refresh cookie |
| POST | `/refresh` | refresh cookie | Rotates; reuse of an old token kills the family |
| POST | `/logout` | authenticated | Revokes the current refresh token |
| POST | `/logout-all` | authenticated | Revokes every session |
| POST | `/forgot-password` | public | Always 200 (no account enumeration) |
| POST | `/reset-password` | public | Single-use token |
| POST | `/change-password` | authenticated | Requires the current password |
| GET | `/me` | authenticated | Profile + roles + permissions for UI gating |

### Attendance — `/api/v1/attendance`
| Method | Path | Permission |
|---|---|---|
| POST | `/punch-in` | `ATTENDANCE_PUNCH` |
| POST | `/punch-out` | `ATTENDANCE_PUNCH` |
| POST | `/break/start` · `/break/end` | `ATTENDANCE_PUNCH` |
| GET | `/me/today` | `ATTENDANCE_VIEW_SELF` |
| GET | `/me/status` | `ATTENDANCE_VIEW_SELF` (live state + elapsed for the timer) |
| GET | `/me/history?fromDate&toDate&status&page&size` | `ATTENDANCE_VIEW_SELF` |
| GET | `/me/calendar?year&month` | `ATTENDANCE_VIEW_SELF` |
| GET | `/me/summary?year&month` | `ATTENDANCE_VIEW_SELF` |
| GET | `/{employeeId}/history` | `ATTENDANCE_VIEW_TEAM` \| `_ALL` + scope check |
| PUT | `/{id}` | `ATTENDANCE_EDIT` (HR correction; fully audited) |
| POST | `/lock` | `ATTENDANCE_LOCK` (payroll freeze for a period) |

### Leave — `/api/v1/leaves`
`POST /` · `GET /me` · `GET /me/balance` · `GET /{id}` · `PUT /{id}/approve` · `PUT /{id}/reject` · `PUT /{id}/cancel` · `GET /pending-approvals` · `GET /types` · `POST /{id}/withdraw`

### Regularization — `/api/v1/regularizations`
`POST /` · `GET /me` · `GET /{id}` · `PUT /{id}/approve` · `PUT /{id}/reject` · `PUT /{id}/cancel` · `GET /pending-approvals`

### WFH — `/api/v1/wfh-requests`
`POST /` · `GET /me` · `PUT /{id}/approve` · `PUT /{id}/reject` · `PUT /{id}/cancel` · `GET /pending-approvals`

### Overtime — `/api/v1/overtime`
`GET /me` · `GET /pending-approvals` · `PUT /{id}/approve` · `PUT /{id}/reject`

### Employees — `/api/v1/employees`
`GET /?page&size&search&departmentId&officeId&status&sort` · `GET /{id}` · `POST /` · `PUT /{id}` · `PATCH /{id}/deactivate` · `PATCH /{id}/reactivate` · `POST /bulk-import` (multipart) · `GET /bulk-import/template` · `PUT /{id}/manager` · `PUT /{id}/shift` · `POST /{id}/photo` · `GET /me` · `PUT /me` · `GET /{id}/hierarchy`

### Manager — `/api/v1/manager`
`GET /team` · `GET /team/attendance?date` · `GET /team/attendance/summary?fromDate&toDate` · `GET /team/calendar?year&month` · `GET /pending-approvals` (all three request types, counted and listed) · `GET /team/{employeeId}/attendance`

### Dashboards — `/api/v1/dashboard`
`GET /employee` · `GET /manager` · `GET /hr` · `GET /admin` — each returns exactly the cards and series that dashboard renders, in one round trip. No N+1 of widget calls.

### Reports — `/api/v1/reports`
`GET /attendance/daily` · `/attendance/monthly` · `/attendance/employee` · `/late-arrivals` · `/early-exits` · `/absentees` · `/leaves` · `/overtime` · `/working-hours` · `/department-attendance` · `/location-attendance` · `/regularizations`

Shared filters: `fromDate`, `toDate`, `employeeIds`, `departmentId`, `officeId`, `managerId`, `status`, `page`, `size`.
Export: same path + `?format=EXCEL|CSV|PDF`, **streamed** (`StreamingResponseBody` + Apache POI SXSSF) so a 50 k-row export does not hold the rows in heap. Exports over a configurable row threshold run async and notify on completion.

### Notifications — `/api/v1/notifications`
`GET /?unreadOnly&page&size` · `GET /unread-count` · `PATCH /{id}/read` · `PATCH /read-all`

### Admin — `/api/v1/admin`
Uniform CRUD (`GET /`, `GET /{id}`, `POST /`, `PUT /{id}`, `DELETE /{id}` → soft deactivate) for: `/departments`, `/designations`, `/offices`, `/offices/{id}/locations`, `/shifts`, `/shifts/assign`, `/weekly-offs`, `/holidays`, `/holiday-calendars`, `/leave-types`, `/leave-policies`, `/attendance-policies`, `/location-policies`, `/overtime-policies`, `/policy-assignments`, `/roles`, `/roles/{id}/permissions`, `/permissions`, `/users/{id}/roles`, `/settings`, `/announcements`.
Plus `GET /audit-logs?entityType&entityId&actorUserId&action&fromDate&toDate&page&size` (`AUDIT_VIEW`).

## 5.5 Request/response examples

**Punch in**
```http
POST /api/v1/attendance/punch-in
Authorization: Bearer <jwt>
Idempotency-Key: 7c1f…
Content-Type: application/json

{ "latitude": 19.0759837, "longitude": 72.8776559, "accuracyMeters": 12.4,
  "capturedAt": "2026-08-31T04:02:09Z", "workMode": "OFFICE",
  "deviceInfo": "Chrome 141 / Windows 11" }
```
No `employeeId` — the server takes it from the token. IP comes from the request, not the body.

**Today's status** (`GET /attendance/me/status`)
```json
{ "success": true, "data": {
  "status": "PUNCHED_IN", "punchInTime": "2026-08-31T04:02:11Z", "punchOutTime": null,
  "workedMinutes": 272, "breakMinutes": 45, "onBreak": false,
  "expectedWorkMinutes": 480, "shift": { "name": "General", "startTime": "09:30", "endTime": "18:30" },
  "lateMinutes": 7, "workMode": "OFFICE", "punchInLocation": "Mumbai Office - Tower B" } }
```
`workedMinutes` is server-computed; the browser only animates seconds between polls, so a wrong client clock cannot inflate hours.

**Apply leave**
```json
{ "leaveTypeId": 2, "fromDate": "2026-09-14", "toDate": "2026-09-16",
  "fromDayPortion": "FULL_DAY", "toDayPortion": "FIRST_HALF",
  "reason": "Family function", "contactDuringLeave": "+91…" }
```

**Insufficient balance**
```json
{ "success": false, "message": "Insufficient Casual Leave balance",
  "errorCode": "LEAVE_BALANCE_INSUFFICIENT",
  "data": { "leaveType": "Casual Leave", "requestedDays": 2.5, "availableDays": 1.0, "shortfallDays": 1.5 } }
```

## 5.6 Frontend integration contract

- `axiosClient` unwraps `data` on success and throws a typed `ApiError{errorCode, message, data}` otherwise, so components branch on `errorCode` — never on a message string.
- A single 401 handler refreshes once and retries; a second failure clears the auth store and routes to `/login`.
- `429` surfaces a retry-after toast rather than a generic failure.
- Query keys mirror endpoints: `['attendance','me','today']`, `['leaves','me',filters]`. Punch mutations invalidate `['attendance','me']` and `['dashboard','employee']`.
- Optimistic updates are used for notification read-state only. Never for punches — a punch that the server rejected must never have appeared to succeed.
