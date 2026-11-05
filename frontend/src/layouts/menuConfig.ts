import { PERMISSIONS } from '../constants/permissions';
import { ROUTES } from '../constants/routes';

export interface MenuItem {
  label: string;
  to: string;
  icon: string;
  /** Item renders only if the user holds at least one of these. Empty = always. */
  requiredPermissions?: string[];
  children?: MenuItem[];
}

/**
 * The sidebar reads the same permission array the API enforces, so it hides
 * exactly what the API would refuse. The API still refuses regardless — this is
 * presentation, never the access check.
 */
export const MENU: MenuItem[] = [
  {
    label: 'Dashboard',
    to: ROUTES.dashboard,
    icon: 'dashboard',
  },
  {
    label: 'Team',
    to: ROUTES.teamDashboard,
    icon: 'groups_2',
    requiredPermissions: [PERMISSIONS.ATTENDANCE_VIEW_TEAM, PERMISSIONS.ATTENDANCE_VIEW_ALL],
  },
  {
    label: 'My Attendance',
    to: ROUTES.myAttendance,
    icon: 'timer',
    requiredPermissions: [PERMISSIONS.ATTENDANCE_VIEW_SELF],
    children: [
      { label: 'History', to: ROUTES.myAttendance, icon: 'history' },
      { label: 'Calendar', to: ROUTES.attendanceCalendar, icon: 'calendar_month' },
    ],
  },
  {
    label: 'Leave',
    to: ROUTES.leaveBalance,
    icon: 'event_available',
    requiredPermissions: [PERMISSIONS.LEAVE_APPLY],
    children: [
      { label: 'Leave Balance', to: ROUTES.leaveBalance, icon: 'account_balance_wallet' },
      { label: 'Apply Leave', to: ROUTES.applyLeave, icon: 'post_add' },
      { label: 'Leave History', to: ROUTES.myLeaves, icon: 'receipt_long' },
    ],
  },
  {
    label: 'Approvals',
    to: ROUTES.leaveApprovals,
    icon: 'approval',
    requiredPermissions: [
      PERMISSIONS.LEAVE_APPROVE_L1,
      PERMISSIONS.LEAVE_APPROVE_L2,
      PERMISSIONS.REGULARIZATION_APPROVE_L1,
      PERMISSIONS.REGULARIZATION_APPROVE_L2,
      PERMISSIONS.WFH_APPROVE,
    ],
    children: [
      {
        label: 'Leave',
        to: ROUTES.leaveApprovals,
        icon: 'event_available',
        requiredPermissions: [PERMISSIONS.LEAVE_APPROVE_L1, PERMISSIONS.LEAVE_APPROVE_L2],
      },
      {
        label: 'Corrections & WFH',
        to: ROUTES.requestApprovals,
        icon: 'assignment_turned_in',
        requiredPermissions: [
          PERMISSIONS.REGULARIZATION_APPROVE_L1,
          PERMISSIONS.REGULARIZATION_APPROVE_L2,
          PERMISSIONS.WFH_APPROVE,
        ],
      },
    ],
  },
  {
    label: 'Meetings',
    to: ROUTES.meetings,
    icon: 'videocam',
    requiredPermissions: [PERMISSIONS.MEETING_VIEW_SELF],
    children: [
      { label: 'My Meetings', to: ROUTES.meetings, icon: 'event' },
      {
        label: 'Manage',
        to: ROUTES.meetingsManage,
        icon: 'edit_calendar',
        requiredPermissions: [PERMISSIONS.MEETING_MANAGE_TEAM, PERMISSIONS.MEETING_MANAGE_ALL],
      },
    ],
  },
  {
    label: 'Requests',
    to: ROUTES.regularization,
    icon: 'assignment_turned_in',
    requiredPermissions: [PERMISSIONS.REGULARIZATION_APPLY, PERMISSIONS.WFH_APPLY],
    children: [
      { label: 'Regularization', to: ROUTES.regularization, icon: 'edit_calendar' },
      { label: 'Work From Home', to: ROUTES.workFromHome, icon: 'home_work' },
    ],
  },
  {
    label: 'Exams',
    to: ROUTES.exams,
    icon: 'quiz',
    requiredPermissions: [PERMISSIONS.EXAM_VIEW_SELF, PERMISSIONS.EXAM_MANAGE],
    children: [
      { label: 'My Exams', to: ROUTES.exams, icon: 'assignment',
        requiredPermissions: [PERMISSIONS.EXAM_VIEW_SELF] },
      { label: 'Manage Exams', to: ROUTES.examsManage, icon: 'edit_note',
        requiredPermissions: [PERMISSIONS.EXAM_MANAGE] },
    ],
  },
  {
    label: 'Employees',
    to: ROUTES.employees,
    icon: 'groups',
    requiredPermissions: [PERMISSIONS.EMPLOYEE_VIEW_TEAM, PERMISSIONS.EMPLOYEE_VIEW_ALL],
    children: [
      { label: 'Directory', to: ROUTES.employees, icon: 'badge' },
      {
        label: 'Bulk Import',
        to: ROUTES.employeeImport,
        icon: 'upload_file',
        requiredPermissions: [PERMISSIONS.EMPLOYEE_BULK_IMPORT],
      },
    ],
  },
  {
    label: 'Organisation',
    to: ROUTES.adminDepartments,
    icon: 'account_tree',
    requiredPermissions: ['DEPARTMENT_MANAGE', 'OFFICE_MANAGE', 'SHIFT_MANAGE',
      'HOLIDAY_MANAGE', 'WEEKLY_OFF_MANAGE', PERMISSIONS.AUDIT_VIEW],
    children: [
      { label: 'Departments', to: ROUTES.adminDepartments, icon: 'apartment',
        requiredPermissions: ['DEPARTMENT_MANAGE'] },
      { label: 'Designations', to: ROUTES.adminDesignations, icon: 'workspace_premium',
        requiredPermissions: ['DESIGNATION_MANAGE'] },
      { label: 'Offices & Geofences', to: ROUTES.adminOffices, icon: 'location_on',
        requiredPermissions: ['OFFICE_MANAGE', 'GEOFENCE_MANAGE'] },
      { label: 'Shifts', to: ROUTES.adminShifts, icon: 'schedule',
        requiredPermissions: ['SHIFT_MANAGE'] },
      { label: 'Holidays', to: ROUTES.adminHolidays, icon: 'event_busy',
        requiredPermissions: ['HOLIDAY_MANAGE'] },
      { label: 'Weekly Offs', to: ROUTES.adminWeeklyOffs, icon: 'event_repeat',
        requiredPermissions: ['WEEKLY_OFF_MANAGE'] },
      { label: 'Audit Trail', to: ROUTES.auditLogs, icon: 'policy',
        requiredPermissions: [PERMISSIONS.AUDIT_VIEW] },
    ],
  },
  {
    label: 'Reports',
    to: ROUTES.reports,
    icon: 'bar_chart',
    requiredPermissions: [PERMISSIONS.REPORT_VIEW_TEAM, PERMISSIONS.REPORT_VIEW_ALL],
  },
  {
    label: 'Profile',
    to: ROUTES.profile,
    icon: 'person',
    requiredPermissions: [PERMISSIONS.EMPLOYEE_VIEW_SELF],
  },
];

export const filterMenu = (items: MenuItem[], has: (permissions: string[]) => boolean): MenuItem[] =>
  items
    .filter((item) => !item.requiredPermissions || has(item.requiredPermissions))
    .map((item) => ({
      ...item,
      children: item.children ? filterMenu(item.children, has) : undefined,
    }));
