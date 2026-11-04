# 02 — Project Structure

## 2.1 Repository root

```
Emp_Attendance_Sys/
├─ docs/                         # this architecture set
├─ backend/                      # Spring Boot API
├─ frontend/                     # React + Vite SPA
├─ docker/
│  ├─ mysql/init.sql
│  └─ nginx/nginx.conf
├─ docker-compose.yml
├─ .env.example                  # every var documented, no real secret
└─ README.md
```

## 2.2 Backend — Spring Boot

Base package `com.company.attendance`. Organised **by layer at the top, by feature underneath** — feature packages keep related classes together while the layer names stay obvious to a new joiner.

```
backend/
├─ pom.xml
├─ mvnw / mvnw.cmd / .mvn/
├─ Dockerfile
└─ src/
   ├─ main/
   │  ├─ java/com/company/attendance/
   │  │  ├─ AttendanceApplication.java
   │  │  │
   │  │  ├─ config/
   │  │  │  ├─ SecurityConfig.java            # filter chain, password encoder, CORS
   │  │  │  ├─ JwtConfig.java                 # @ConfigurationProperties(prefix="app.jwt")
   │  │  │  ├─ WebMvcConfig.java              # interceptors, argument resolvers
   │  │  │  ├─ JacksonConfig.java             # JavaTimeModule, UTC, naming strategy
   │  │  │  ├─ OpenApiConfig.java             # springdoc, bearer scheme
   │  │  │  ├─ AsyncConfig.java               # @EnableAsync + bounded executor
   │  │  │  ├─ SchedulerConfig.java           # @EnableScheduling + ShedLock
   │  │  │  ├─ CacheConfig.java               # Caffeine/Redis cache manager
   │  │  │  ├─ AuditorAwareConfig.java        # @EnableJpaAuditing -> createdBy/updatedBy
   │  │  │  └─ RateLimitConfig.java           # Bucket4j buckets
   │  │  │
   │  │  ├─ security/
   │  │  │  ├─ JwtTokenProvider.java
   │  │  │  ├─ JwtAuthenticationFilter.java
   │  │  │  ├─ JwtAuthenticationEntryPoint.java
   │  │  │  ├─ RestAccessDeniedHandler.java
   │  │  │  ├─ CustomUserDetailsService.java
   │  │  │  ├─ UserPrincipal.java             # userId, employeeId, roles, permissions
   │  │  │  ├─ CurrentUser.java               # @AuthenticationPrincipal meta-annotation
   │  │  │  ├─ AccessScopeResolver.java       # SELF | TEAM | ORG
   │  │  │  ├─ PermissionEvaluatorImpl.java
   │  │  │  └─ RequestIdFilter.java           # traceId into MDC
   │  │  │
   │  │  ├─ controller/                       # thin; one per feature
   │  │  │  ├─ AuthController.java
   │  │  │  ├─ AttendanceController.java
   │  │  │  ├─ BreakController.java
   │  │  │  ├─ LeaveController.java
   │  │  │  ├─ LeaveBalanceController.java
   │  │  │  ├─ RegularizationController.java
   │  │  │  ├─ WorkFromHomeController.java
   │  │  │  ├─ OvertimeController.java
   │  │  │  ├─ EmployeeController.java
   │  │  │  ├─ ManagerController.java
   │  │  │  ├─ DashboardController.java
   │  │  │  ├─ ReportController.java
   │  │  │  ├─ NotificationController.java
   │  │  │  └─ admin/
   │  │  │     ├─ DepartmentController.java
   │  │  │     ├─ DesignationController.java
   │  │  │     ├─ OfficeController.java
   │  │  │     ├─ OfficeLocationController.java   # geofence CRUD
   │  │  │     ├─ ShiftController.java
   │  │  │     ├─ HolidayController.java
   │  │  │     ├─ WeeklyOffController.java
   │  │  │     ├─ LeaveTypeController.java
   │  │  │     ├─ PolicyController.java
   │  │  │     ├─ RoleController.java
   │  │  │     ├─ PermissionController.java
   │  │  │     ├─ CompanySettingsController.java
   │  │  │     └─ AuditLogController.java
   │  │  │
   │  │  ├─ service/                          # interfaces only
   │  │  │  ├─ AuthService.java
   │  │  │  ├─ TokenService.java
   │  │  │  ├─ AttendanceService.java
   │  │  │  ├─ PunchService.java
   │  │  │  ├─ BreakService.java
   │  │  │  ├─ AttendanceCalculationService.java
   │  │  │  ├─ GeofenceService.java
   │  │  │  ├─ SuspiciousPunchDetector.java
   │  │  │  ├─ ShiftResolutionService.java
   │  │  │  ├─ CalendarService.java           # holiday + weekly-off day typing
   │  │  │  ├─ PolicyResolutionService.java
   │  │  │  ├─ LeaveService.java
   │  │  │  ├─ LeaveBalanceService.java
   │  │  │  ├─ LeaveAccrualService.java
   │  │  │  ├─ ApprovalWorkflowService.java
   │  │  │  ├─ RegularizationService.java
   │  │  │  ├─ WorkFromHomeService.java
   │  │  │  ├─ OvertimeService.java
   │  │  │  ├─ EmployeeService.java
   │  │  │  ├─ EmployeeImportService.java
   │  │  │  ├─ HierarchyService.java          # reporting subtree resolution
   │  │  │  ├─ DashboardService.java
   │  │  │  ├─ ReportService.java
   │  │  │  ├─ ExportService.java             # Excel / CSV / PDF
   │  │  │  ├─ NotificationService.java
   │  │  │  ├─ FileStorageService.java
   │  │  │  ├─ GeocodingService.java
   │  │  │  └─ impl/                          # *ServiceImpl — all @Transactional here
   │  │  │
   │  │  ├─ repository/
   │  │  │  ├─ AttendanceRepository.java
   │  │  │  ├─ AttendanceSpecifications.java  # dynamic filter builders
   │  │  │  └─ … one per aggregate root
   │  │  │
   │  │  ├─ entity/
   │  │  │  ├─ base/BaseEntity.java           # id, createdAt/By, updatedAt/By, @Version
   │  │  │  ├─ base/SoftDeletable.java
   │  │  │  └─ … Attendance, Employee, LeaveRequest, …
   │  │  │
   │  │  ├─ dto/
   │  │  │  ├─ request/    PunchInRequest, LeaveApplyRequest, EmployeeCreateRequest, …
   │  │  │  ├─ response/   AttendanceResponse, ApiResponse, PageResponse, …
   │  │  │  ├─ filter/     AttendanceFilter, EmployeeFilter, ReportFilter
   │  │  │  └─ projection/ dashboard & report interface projections
   │  │  │
   │  │  ├─ mapper/                           # MapStruct interfaces
   │  │  │
   │  │  ├─ exception/
   │  │  │  ├─ BusinessException.java         # carries an ErrorCode
   │  │  │  ├─ ResourceNotFoundException.java
   │  │  │  ├─ EmployeeNotFoundException.java
   │  │  │  ├─ AlreadyPunchedInException.java
   │  │  │  ├─ NotPunchedInException.java
   │  │  │  ├─ AttendanceAlreadyExistsException.java
   │  │  │  ├─ OutsideGeofenceException.java
   │  │  │  ├─ InsufficientLocationAccuracyException.java
   │  │  │  ├─ LeaveBalanceInsufficientException.java
   │  │  │  ├─ OverlappingLeaveException.java
   │  │  │  ├─ UnauthorizedActionException.java
   │  │  │  ├─ PolicyViolationException.java
   │  │  │  └─ GlobalExceptionHandler.java
   │  │  │
   │  │  ├─ enums/
   │  │  │  ├─ AttendanceStatus.java  PRESENT, ABSENT, HALF_DAY, ON_LEAVE, HOLIDAY,
   │  │  │  │                         WEEKLY_OFF, WFH, ON_DUTY, PUNCHED_IN, ON_BREAK, REGULARIZED
   │  │  │  ├─ WorkMode.java          OFFICE, WFH, FIELD, CLIENT_SITE
   │  │  │  ├─ PunchEventType.java    PUNCH_IN, PUNCH_OUT, BREAK_START, BREAK_END
   │  │  │  ├─ AttendanceSource.java  WEB, MOBILE, BIOMETRIC, SYSTEM, REGULARIZATION, IMPORT
   │  │  │  ├─ RequestStatus.java     PENDING, APPROVED, REJECTED, CANCELLED, WITHDRAWN
   │  │  │  ├─ ApprovalAction.java    PENDING, APPROVED, REJECTED, SKIPPED, ESCALATED
   │  │  │  ├─ DayPortion.java        FULL_DAY, FIRST_HALF, SECOND_HALF
   │  │  │  ├─ ScopeType.java         GLOBAL, OFFICE, DEPARTMENT, EMPLOYMENT_TYPE, EMPLOYEE
   │  │  │  ├─ EmploymentType.java    PERMANENT, CONTRACT, INTERN, CONSULTANT
   │  │  │  ├─ ErrorCode.java
   │  │  │  └─ …
   │  │  │
   │  │  ├─ util/
   │  │  │  ├─ GeoUtils.java                  # Haversine, bounding box
   │  │  │  ├─ DateTimeUtils.java             # business-date resolution per office zone
   │  │  │  ├─ WorkingTimeCalculator.java
   │  │  │  ├─ IpAddressUtils.java            # X-Forwarded-For aware
   │  │  │  └─ DeviceInfoParser.java
   │  │  │
   │  │  ├─ scheduler/
   │  │  │  ├─ AttendanceReminderJob.java     # not-punched-in / punch-out reminders
   │  │  │  ├─ AutoAbsentJob.java             # end-of-day absent marking
   │  │  │  ├─ AutoPunchOutJob.java           # missing punch-out -> flag, not fabricate
   │  │  │  ├─ LeaveAccrualJob.java           # monthly/annual credit
   │  │  │  ├─ LeaveCarryForwardJob.java      # year-end carry/lapse/encash
   │  │  │  ├─ AttendanceSummaryRollupJob.java
   │  │  │  └─ DataRetentionPurgeJob.java
   │  │  │
   │  │  ├─ audit/
   │  │  │  ├─ Auditable.java                 # annotation
   │  │  │  ├─ AuditAspect.java
   │  │  │  └─ AuditLogService.java
   │  │  │
   │  │  └─ event/
   │  │     ├─ LeaveAppliedEvent.java … (domain events)
   │  │     └─ listener/NotificationEventListener.java
   │  │
   │  └─ resources/
   │     ├─ application.yml
   │     ├─ application-dev.yml / -docker.yml / -prod.yml
   │     ├─ logback-spring.xml
   │     └─ db/migration/                     # Flyway
   │        ├─ V1__create_auth_tables.sql
   │        ├─ V2__create_org_tables.sql
   │        ├─ V3__create_employee_tables.sql
   │        ├─ V4__create_shift_calendar_tables.sql
   │        ├─ V5__create_attendance_tables.sql
   │        ├─ V6__create_leave_tables.sql
   │        ├─ V7__create_request_tables.sql
   │        ├─ V8__create_policy_tables.sql
   │        ├─ V9__create_notification_audit_tables.sql
   │        ├─ V10__seed_roles_permissions.sql
   │        └─ V11__seed_reference_data.sql
   │
   └─ test/java/com/company/attendance/
      ├─ unit/            GeoUtilsTest, WorkingTimeCalculatorTest, LeaveBalanceServiceTest …
      ├─ integration/     AuthControllerIT, PunchInIT, LeaveWorkflowIT (Testcontainers MySQL)
      └─ security/        RoleAuthorizationIT
```

## 2.3 Frontend — React + Vite + TypeScript

```
frontend/
├─ package.json  vite.config.ts  tsconfig.json  .eslintrc  Dockerfile  nginx.conf
└─ src/
   ├─ main.tsx
   ├─ App.tsx
   │
   ├─ api/
   │  ├─ axiosClient.ts          # baseURL, auth header, 401 -> refresh & retry once
   │  ├─ endpoints.ts            # single source of URL truth
   │  └─ queryClient.ts          # TanStack Query defaults
   │
   ├─ types/
   │  ├─ api.ts                  # ApiResponse<T>, PageResponse<T>, ErrorCode
   │  ├─ auth.ts  attendance.ts  leave.ts  employee.ts  master.ts  report.ts
   │
   ├─ constants/
   │  ├─ roles.ts  permissions.ts  attendanceStatus.ts  routes.ts  dateFormats.ts
   │
   ├─ store/
   │  ├─ authStore.ts            # user, permissions, access token IN MEMORY
   │  ├─ uiStore.ts              # sidebar, theme
   │  └─ notificationStore.ts
   │
   ├─ routes/
   │  ├─ AppRoutes.tsx           # lazy-loaded route tree
   │  └─ routeConfig.ts          # path -> required permission
   │
   ├─ guards/
   │  ├─ ProtectedRoute.tsx      # authenticated?
   │  ├─ RoleGuard.tsx           # role check
   │  ├─ PermissionGuard.tsx     # permission check
   │  └─ PublicOnlyRoute.tsx
   │
   ├─ layouts/
   │  ├─ DashboardLayout.tsx     # sidebar + navbar + outlet
   │  ├─ AuthLayout.tsx
   │  ├─ Sidebar.tsx             # menu filtered by permission
   │  ├─ Topbar.tsx              # clock, notifications, profile menu
   │  └─ menuConfig.ts
   │
   ├─ components/                # shared, feature-agnostic
   │  ├─ common/     PageHeader, ConfirmDialog, EmptyState, ErrorBoundary, LoadingOverlay
   │  ├─ form/       FormTextField, FormSelect, FormDatePicker, FormFileUpload (RHF-bound)
   │  ├─ table/      DataTable (MUI DataGrid wrapper w/ server pagination), ExportMenu
   │  ├─ charts/     BarChart, LineChart, DonutChart, TrendCard
   │  ├─ calendar/   MonthCalendar, DayCell, StatusLegend
   │  ├─ map/        LocationMap, GeofenceCircle
   │  └─ feedback/   StatusChip, ToastProvider
   │
   ├─ hooks/
   │  ├─ useAuth.ts  usePermission.ts  useGeolocation.ts  useLiveTimer.ts
   │  ├─ useDebounce.ts  usePagination.ts  useServerTable.ts  useNotifications.ts
   │
   ├─ features/                  # each: api.ts | hooks.ts | components/ | schemas.ts
   │  ├─ auth/                   Login, ForgotPassword, ResetPassword, ChangePassword
   │  ├─ attendance/             PunchCard, LiveTimer, BreakControls, HistoryTable,
   │  │                          AttendanceCalendar, DayDetailDrawer
   │  ├─ leave/                  BalanceCards, ApplyLeaveForm, MyLeavesTable, LeaveDetail
   │  ├─ regularization/         RequestForm, MyRequestsTable
   │  ├─ wfh/                    WfhRequestForm, MyWfhTable
   │  ├─ employee/               EmployeeTable, EmployeeForm, ProfileView, BulkImportDialog
   │  ├─ manager/                TeamOverview, TeamAttendanceGrid, ApprovalInbox
   │  ├─ admin/                  departments/ designations/ offices/ geofence/ shifts/
   │  │                          holidays/ leaveTypes/ policies/ roles/ settings/ auditLogs/
   │  ├─ reports/                ReportBuilder, ReportFilters, ReportPreview
   │  ├─ dashboard/              employee/ manager/ hr/ admin/ widgets
   │  └─ notifications/          NotificationBell, NotificationList
   │
   ├─ pages/                     # thin route components composing feature pieces
   │  ├─ public/     LoginPage, ForgotPasswordPage, ResetPasswordPage
   │  ├─ employee/   DashboardPage, MyAttendancePage, AttendanceCalendarPage,
   │  │              LeaveBalancePage, ApplyLeavePage, MyLeavesPage,
   │  │              RegularizationPage, MyRequestsPage, ProfilePage, NotificationsPage
   │  ├─ manager/    ManagerDashboardPage, TeamAttendancePage, TeamCalendarPage,
   │  │              LeaveApprovalsPage, RegularizationApprovalsPage, TeamMembersPage
   │  ├─ hr/         HrDashboardPage, EmployeeManagementPage, AttendanceManagementPage,
   │  │              LeaveManagementPage, ReportsPage, HolidayManagementPage, ShiftManagementPage
   │  ├─ admin/      AdminDashboardPage, RoleManagementPage, PermissionManagementPage,
   │  │              DepartmentPage, DesignationPage, OfficePage, GeofencePage,
   │  │              LeavePolicyPage, AttendancePolicyPage, CompanySettingsPage, AuditLogPage
   │  └─ error/      NotFoundPage, ForbiddenPage
   │
   ├─ services/     geolocationService.ts, deviceInfoService.ts, exportService.ts
   ├─ utils/        dateUtils.ts, durationUtils.ts, formatters.ts, errorHandler.ts
   ├─ theme/        theme.ts (light+dark palette, typography, component overrides)
   └─ test/         setup.ts, mocks/handlers.ts (MSW)
```

### Frontend conventions

- **Server state is TanStack Query; client state is Zustand.** No server data in Zustand — that duplication is the usual source of stale dashboards.
- Query keys are namespaced tuples: `['attendance', 'history', filters]`. Mutations invalidate by prefix.
- Every list page uses `useServerTable` → server-side `page/size/sort/search`. No client-side filtering of a full dataset.
- Sidebar items and route guards read from the **same** `permissions` array the backend issued. The UI hides what the API would refuse; the API refuses regardless of the UI.
- Zod schemas mirror backend DTO validation, so the same rule failure is caught before the round trip and again on the server.
