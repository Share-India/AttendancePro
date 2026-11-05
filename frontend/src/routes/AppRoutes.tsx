import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { PERMISSIONS } from '../constants/permissions';
import { ROUTES } from '../constants/routes';
import { PermissionGuard } from '../guards/PermissionGuard';
import { ProtectedRoute } from '../guards/ProtectedRoute';
import { PublicOnlyRoute } from '../guards/PublicOnlyRoute';
import { AuthLayout } from '../layouts/AuthLayout';
import { DashboardLayout } from '../layouts/DashboardLayout';
import { ForbiddenPage } from '../pages/error/ForbiddenPage';
import { NotFoundPage } from '../pages/error/NotFoundPage';

/**
 * Every screen is loaded on demand.
 *
 * <p>Bundled together they came to 686 kB, which meant an employee who only
 * ever punches in and books leave still downloaded the audit viewer, the report
 * engine and the bulk importer before seeing anything. Splitting per route
 * costs a brief fallback the first time a screen is opened and nothing
 * thereafter.
 */
const ApplyLeavePage = lazy(() => import('../pages/employee/ApplyLeavePage').then((m) => ({ default: m.ApplyLeavePage })));
const AttendanceCalendarPage = lazy(() => import('../pages/employee/AttendanceCalendarPage').then((m) => ({ default: m.AttendanceCalendarPage })));
const AuditLogPage = lazy(() => import('../pages/admin/AuditLogPage').then((m) => ({ default: m.AuditLogPage })));
const BulkImportPage = lazy(() => import('../pages/hr/BulkImportPage').then((m) => ({ default: m.BulkImportPage })));
const ChangePasswordPage = lazy(() => import('../pages/employee/ChangePasswordPage').then((m) => ({ default: m.ChangePasswordPage })));
const DashboardPage = lazy(() => import('../pages/employee/DashboardPage').then((m) => ({ default: m.DashboardPage })));
const DepartmentsPage = lazy(() => import('../pages/admin/DepartmentsPage').then((m) => ({ default: m.DepartmentsPage })));
const DesignationsPage = lazy(() => import('../pages/admin/DesignationsPage').then((m) => ({ default: m.DesignationsPage })));
const EmployeeDetailPage = lazy(() => import('../pages/hr/EmployeeDetailPage').then((m) => ({ default: m.EmployeeDetailPage })));
const EmployeeDirectoryPage = lazy(() => import('../pages/hr/EmployeeDirectoryPage').then((m) => ({ default: m.EmployeeDirectoryPage })));
const EmployeeFormPage = lazy(() => import('../pages/hr/EmployeeFormPage').then((m) => ({ default: m.EmployeeFormPage })));
const ExamsPage = lazy(() => import('../pages/employee/ExamsPage').then((m) => ({ default: m.ExamsPage })));
const ExamAttemptPage = lazy(() => import('../pages/employee/ExamAttemptPage').then((m) => ({ default: m.ExamAttemptPage })));
const ExamResultPage = lazy(() => import('../pages/employee/ExamResultPage').then((m) => ({ default: m.ExamResultPage })));
const ManageExamsPage = lazy(() => import('../pages/manager/ManageExamsPage').then((m) => ({ default: m.ManageExamsPage })));
const ExamFormPage = lazy(() => import('../pages/manager/ExamFormPage').then((m) => ({ default: m.ExamFormPage })));
const ExamDetailPage = lazy(() => import('../pages/manager/ExamDetailPage').then((m) => ({ default: m.ExamDetailPage })));
const EmployeeAttendancePage = lazy(() => import('../pages/hr/EmployeeAttendancePage').then((m) => ({ default: m.EmployeeAttendancePage })));
const PrivacyPolicyPage = lazy(() => import('../pages/public/PrivacyPolicyPage').then((m) => ({ default: m.PrivacyPolicyPage })));
const ForgotPasswordPage = lazy(() => import('../pages/public/ForgotPasswordPage').then((m) => ({ default: m.ForgotPasswordPage })));
const LeaveApprovalsPage = lazy(() => import('../pages/manager/LeaveApprovalsPage').then((m) => ({ default: m.LeaveApprovalsPage })));
const LeaveBalancePage = lazy(() => import('../pages/employee/LeaveBalancePage').then((m) => ({ default: m.LeaveBalancePage })));
const LoginPage = lazy(() => import('../pages/public/LoginPage').then((m) => ({ default: m.LoginPage })));
const MyAttendancePage = lazy(() => import('../pages/employee/MyAttendancePage').then((m) => ({ default: m.MyAttendancePage })));
const MyLeavesPage = lazy(() => import('../pages/employee/MyLeavesPage').then((m) => ({ default: m.MyLeavesPage })));
const NotificationsPage = lazy(() => import('../pages/employee/NotificationsPage').then((m) => ({ default: m.NotificationsPage })));
const OfficesPage = lazy(() => import('../pages/admin/OfficesPage').then((m) => ({ default: m.OfficesPage })));
const ProfilePage = lazy(() => import('../pages/employee/ProfilePage').then((m) => ({ default: m.ProfilePage })));
const MeetingsPage = lazy(() => import('../pages/employee/MeetingsPage').then((m) => ({ default: m.MeetingsPage })));
const MeetingDetailPage = lazy(() => import('../pages/employee/MeetingDetailPage').then((m) => ({ default: m.MeetingDetailPage })));
const ManageMeetingsPage = lazy(() => import('../pages/manager/ManageMeetingsPage').then((m) => ({ default: m.ManageMeetingsPage })));
const MeetingFormPage = lazy(() => import('../pages/manager/MeetingFormPage').then((m) => ({ default: m.MeetingFormPage })));
const MeetingAttendancePage = lazy(() => import('../pages/manager/MeetingAttendancePage').then((m) => ({ default: m.MeetingAttendancePage })));
const RegularizationPage = lazy(() => import('../pages/employee/RegularizationPage').then((m) => ({ default: m.RegularizationPage })));
const ReportsPage = lazy(() => import('../pages/manager/ReportsPage').then((m) => ({ default: m.ReportsPage })));
const RequestApprovalsPage = lazy(() => import('../pages/manager/RequestApprovalsPage').then((m) => ({ default: m.RequestApprovalsPage })));
const ResetPasswordPage = lazy(() => import('../pages/public/ResetPasswordPage').then((m) => ({ default: m.ResetPasswordPage })));
const ShiftsPage = lazy(() => import('../pages/admin/ShiftsPage').then((m) => ({ default: m.ShiftsPage })));
const HolidayManagementPage = lazy(() =>
  import('../pages/admin/HolidayManagementPage').then((m) => ({ default: m.HolidayManagementPage })),
);
const WeeklyOffsPage = lazy(() =>
  import('../pages/admin/WeeklyOffsPage').then((m) => ({ default: m.WeeklyOffsPage })),
);
const TeamDashboardPage = lazy(() => import('../pages/manager/TeamDashboardPage').then((m) => ({ default: m.TeamDashboardPage })));
const WorkFromHomePage = lazy(() => import('../pages/employee/WorkFromHomePage').then((m) => ({ default: m.WorkFromHomePage })));

/** Shown only while a route's chunk is in flight, so it must be quiet. */
const RouteFallback = () => (
  <div className="flex min-h-[60vh] items-center justify-center" role="status" aria-live="polite">
    <span className="sr-only">Loading</span>
    <span className="h-8 w-8 animate-spin rounded-full border-2 border-outline-variant border-t-primary" />
  </div>
);

export const AppRoutes = () => (
  <Suspense fallback={<RouteFallback />}>
  <Routes>
    {/* Public */}
    <Route path="/privacy" element={<PrivacyPolicyPage />} />

    <Route element={<PublicOnlyRoute />}>
      <Route
        path={ROUTES.login}
        element={
          <AuthLayout title="Welcome Back" subtitle="Please sign in to access your HRMS portal.">
            <LoginPage />
          </AuthLayout>
        }
      />
      <Route
        path={ROUTES.forgotPassword}
        element={
          <AuthLayout
            title="Forgot your password?"
            subtitle="Enter your work email and we will send you a reset link."
          >
            <ForgotPasswordPage />
          </AuthLayout>
        }
      />
      <Route
        path={ROUTES.resetPassword}
        element={
          <AuthLayout title="Set a new password" subtitle="Choose a password you have not used before.">
            <ResetPasswordPage />
          </AuthLayout>
        }
      />
    </Route>

    {/* Authenticated */}
    <Route element={<ProtectedRoute />}>
      <Route element={<DashboardLayout />}>
        <Route path={ROUTES.dashboard} element={<DashboardPage />} />
        <Route path={ROUTES.changePassword} element={<ChangePasswordPage />} />
        <Route path={ROUTES.forbidden} element={<ForbiddenPage />} />
        <Route path={ROUTES.notifications} element={<NotificationsPage />} />

        {/* Employee self-service */}
        <Route element={<PermissionGuard anyOf={[PERMISSIONS.EMPLOYEE_VIEW_SELF]} />}>
          <Route path={ROUTES.profile} element={<ProfilePage />} />
        </Route>

        {/* Employee directory — the service narrows rows to the caller's scope */}
        <Route
          element={
            <PermissionGuard anyOf={[PERMISSIONS.EMPLOYEE_VIEW_ALL, PERMISSIONS.EMPLOYEE_VIEW_TEAM]} />
          }
        >
          <Route path={ROUTES.employees} element={<EmployeeDirectoryPage />} />
          <Route path="/employees/:id" element={<EmployeeDetailPage />} />
        </Route>

        <Route element={<PermissionGuard anyOf={[PERMISSIONS.EMPLOYEE_VIEW_ALL]} />}>
          <Route path={ROUTES.employeeNew} element={<EmployeeFormPage />} />
          <Route path="/employees/:id/edit" element={<EmployeeFormPage />} />
          <Route path={ROUTES.employeeImport} element={<BulkImportPage />} />
          <Route path="/employees/:id/attendance" element={<EmployeeAttendancePage />} />
        </Route>

        {/* Team and organisation view — the API decides how wide it is */}
        <Route
          element={
            <PermissionGuard
              anyOf={[PERMISSIONS.ATTENDANCE_VIEW_TEAM, PERMISSIONS.ATTENDANCE_VIEW_ALL]}
            />
          }
        >
          <Route path={ROUTES.teamDashboard} element={<TeamDashboardPage />} />
        </Route>

        {/* Attendance */}
        <Route element={<PermissionGuard anyOf={[PERMISSIONS.ATTENDANCE_VIEW_SELF]} />}>
          <Route path={ROUTES.myAttendance} element={<MyAttendancePage />} />
          <Route path={ROUTES.attendanceCalendar} element={<AttendanceCalendarPage />} />
        </Route>

        {/* Leave */}
        <Route element={<PermissionGuard anyOf={[PERMISSIONS.LEAVE_APPLY]} />}>
          <Route path={ROUTES.leaveBalance} element={<LeaveBalancePage />} />
          <Route path={ROUTES.applyLeave} element={<ApplyLeavePage />} />
          <Route path={ROUTES.myLeaves} element={<MyLeavesPage />} />
        </Route>

        {/* Approvals */}
        <Route
          element={
            <PermissionGuard anyOf={[PERMISSIONS.LEAVE_APPROVE_L1, PERMISSIONS.LEAVE_APPROVE_L2]} />
          }
        >
          <Route path={ROUTES.leaveApprovals} element={<LeaveApprovalsPage />} />
        </Route>

        <Route
          element={
            <PermissionGuard
              anyOf={[
                PERMISSIONS.REGULARIZATION_APPROVE_L1,
                PERMISSIONS.REGULARIZATION_APPROVE_L2,
                PERMISSIONS.WFH_APPROVE,
              ]}
            />
          }
        >
          <Route path={ROUTES.requestApprovals} element={<RequestApprovalsPage />} />
        </Route>

        {/* Requests */}
        <Route
          element={
            <PermissionGuard anyOf={[PERMISSIONS.REGULARIZATION_APPLY, PERMISSIONS.WFH_APPLY]} />
          }
        >
          <Route path={ROUTES.regularization} element={<RegularizationPage />} />
          <Route path={ROUTES.workFromHome} element={<WorkFromHomePage />} />
        </Route>

        {/* Meetings */}
        <Route element={<PermissionGuard anyOf={[PERMISSIONS.MEETING_MANAGE_TEAM, PERMISSIONS.MEETING_MANAGE_ALL]} />}>
          <Route path={ROUTES.meetingsManage} element={<ManageMeetingsPage />} />
          <Route path={ROUTES.meetingNew} element={<MeetingFormPage />} />
          <Route path="/meetings/:id/edit" element={<MeetingFormPage />} />
        </Route>
        <Route element={<PermissionGuard anyOf={[PERMISSIONS.MEETING_REPORT_VIEW, PERMISSIONS.MEETING_MANAGE_TEAM, PERMISSIONS.MEETING_MANAGE_ALL]} />}>
          <Route path="/meetings/:id/attendance" element={<MeetingAttendancePage />} />
        </Route>
        <Route element={<PermissionGuard anyOf={[PERMISSIONS.MEETING_VIEW_SELF]} />}>
          <Route path={ROUTES.meetings} element={<MeetingsPage />} />
          <Route path="/meetings/:id" element={<MeetingDetailPage />} />
        </Route>

        {/*
          Exams. The manage routes come first so /exams/manage and /exams/new
          are matched before /exams/:id would swallow them.
        */}
        <Route element={<PermissionGuard anyOf={[PERMISSIONS.EXAM_MANAGE]} />}>
          <Route path={ROUTES.examsManage} element={<ManageExamsPage />} />
          <Route path={ROUTES.examNew} element={<ExamFormPage />} />
          <Route path="/exams/:id/edit" element={<ExamFormPage />} />
        </Route>
        <Route
          element={
            <PermissionGuard
              anyOf={[
                PERMISSIONS.EXAM_MANAGE,
                PERMISSIONS.EXAM_RESULT_VIEW_TEAM,
                PERMISSIONS.EXAM_RESULT_VIEW_ALL,
              ]}
            />
          }
        >
          <Route path="/exams/:id" element={<ExamDetailPage />} />
        </Route>
        <Route element={<PermissionGuard anyOf={[PERMISSIONS.EXAM_ATTEMPT]} />}>
          <Route path="/exams/attempt/:attemptId" element={<ExamAttemptPage />} />
          <Route path="/exams/result/:attemptId" element={<ExamResultPage />} />
        </Route>
        <Route element={<PermissionGuard anyOf={[PERMISSIONS.EXAM_VIEW_SELF]} />}>
          <Route path={ROUTES.exams} element={<ExamsPage />} />
        </Route>

        {/* Reports */}
        <Route
          element={
            <PermissionGuard anyOf={[PERMISSIONS.REPORT_VIEW_TEAM, PERMISSIONS.REPORT_VIEW_ALL]} />
          }
        >
          <Route path={ROUTES.reports} element={<ReportsPage />} />
        </Route>

        {/* Admin master data */}
        <Route element={<PermissionGuard anyOf={['DEPARTMENT_MANAGE', 'DESIGNATION_MANAGE']} />}>
          <Route path={ROUTES.adminDepartments} element={<DepartmentsPage />} />
          <Route path={ROUTES.adminDesignations} element={<DesignationsPage />} />
        </Route>

        <Route element={<PermissionGuard anyOf={['OFFICE_MANAGE', 'GEOFENCE_MANAGE']} />}>
          <Route path={ROUTES.adminOffices} element={<OfficesPage />} />
        </Route>

        <Route element={<PermissionGuard anyOf={['SHIFT_MANAGE']} />}>
          <Route path={ROUTES.adminShifts} element={<ShiftsPage />} />
        </Route>

        {/* No guard beyond being signed in: reading the holiday list is open
            to everyone, and the page hides its own write controls without
            HOLIDAY_MANAGE. Guarding the route would hide the office holidays
            from the people who work them. */}
        <Route path={ROUTES.adminHolidays} element={<HolidayManagementPage />} />
        <Route path={ROUTES.adminWeeklyOffs} element={<WeeklyOffsPage />} />

        <Route element={<PermissionGuard anyOf={[PERMISSIONS.AUDIT_VIEW]} />}>
          <Route path={ROUTES.auditLogs} element={<AuditLogPage />} />
        </Route>
      </Route>
    </Route>

    <Route path="/" element={<Navigate to={ROUTES.dashboard} replace />} />
    <Route path="*" element={<NotFoundPage />} />
  </Routes>
  </Suspense>
);
