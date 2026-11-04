/**
 * Team and organisation roll-ups.
 *
 * <p>The states are kept distinct all the way to the screen. Collapsing
 * "on leave", "weekly off" and "absent" into one number is the fastest way to
 * make a dashboard lie, so the UI never adds them together either.
 */

export type DayState =
  | 'PRESENT'
  | 'WORKING_FROM_HOME'
  | 'ON_LEAVE'
  | 'WEEKLY_OFF'
  | 'HOLIDAY'
  | 'NOT_YET_IN'
  | 'ABSENT';

export interface PendingApprovals {
  leave: number;
  regularization: number;
  workFromHome: number;
  total: number;
}

export interface GroupCount {
  id: number | null;
  name: string;
  expected: number;
  present: number;
  workingFromHome: number;
  onLeave: number;
  absent: number;
}

export interface DashboardSummary {
  date: string;
  /** ORG or TEAM — which population these numbers describe. */
  scope: 'ORG' | 'TEAM';

  headcount: number;
  /** People whose day was supposed to be a working one. The real denominator. */
  expected: number;

  present: number;
  workingFromHome: number;
  onLeave: number;
  weeklyOff: number;
  holiday: number;
  notYetIn: number;
  absent: number;

  late: number;
  punchedOut: number;
  currentlyIn: number;

  attendanceRate: number;

  pendingApprovals: PendingApprovals;
  byDepartment: GroupCount[];
}

export interface TeamMemberDay {
  employeeId: number;
  employeeCode: string;
  employeeName: string;
  departmentName: string | null;
  designationName: string | null;
  officeName: string | null;

  date: string;
  dayState: DayState;

  shiftName: string | null;
  shiftStartTime: string | null;
  shiftEndTime: string | null;

  punchInTime: string | null;
  punchOutTime: string | null;
  workingMinutes: number | null;
  lateMinutes: number | null;
  workMode: string | null;
  attendanceStatus: string | null;

  leaveTypeName: string | null;
  leaveDayPortion: string | null;
}

export interface TrendPoint {
  date: string;
  /** False for weekends and holidays — the chart greys these rather than hiding them. */
  working: boolean;
  expected: number;
  present: number;
  workingFromHome: number;
  onLeave: number;
  absent: number;
  late: number;
}

export interface AttendanceTrend {
  fromDate: string;
  toDate: string;
  points: TrendPoint[];
}

/** How each state is spoken about and coloured. One place, so the page cannot drift. */
export const DAY_STATE_META: Record<
  DayState,
  { label: string; tone: 'success' | 'warning' | 'error' | 'info' | 'neutral'; icon: string }
> = {
  PRESENT: { label: 'Present', tone: 'success', icon: 'check_circle' },
  WORKING_FROM_HOME: { label: 'Work from home', tone: 'info', icon: 'home_work' },
  ON_LEAVE: { label: 'On leave', tone: 'info', icon: 'event_available' },
  WEEKLY_OFF: { label: 'Weekly off', tone: 'neutral', icon: 'weekend' },
  HOLIDAY: { label: 'Holiday', tone: 'neutral', icon: 'celebration' },
  NOT_YET_IN: { label: 'Not yet in', tone: 'warning', icon: 'schedule' },
  ABSENT: { label: 'Absent', tone: 'error', icon: 'person_off' },
};

export interface BulkImportRowError {
  rowNumber: number;
  employeeCode: string | null;
  message: string;
}

export interface BulkImportResult {
  totalRows: number;
  imported: number;
  failed: number;
  errors: BulkImportRowError[];
  /** Imported, but not fully wired up — usually a manager code matching nobody. */
  warnings: BulkImportRowError[];
  /** Readable only in this response; the server keeps a hash and nothing else. */
  credentials: BulkImportCredential[];
}

export interface BulkImportCredential {
  rowNumber: number;
  employeeCode: string;
  employeeName: string;
  temporaryPassword: string;
}
