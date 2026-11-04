/**
 * Regularization and work-from-home requests.
 *
 * <p>Both share an approval shape with leave, but they are not leave: neither
 * touches a balance, and both change what attendance the day is allowed to
 * record. They are kept in their own module for that reason.
 */

export interface RequestApprovalStep {
  level: number;
  approverEmployeeId: number | null;
  approverName: string | null;
  approverRole: string | null;
  action: string;
  comments: string | null;
  actedAt: string | null;
}

export interface Regularization {
  id: number;
  requestNumber: string;
  employeeId: number;
  employeeCode: string;
  employeeName: string;

  attendanceDate: string;
  dayOfWeek: string;
  reasonType: string;
  reason: string;
  attachmentUrl: string | null;

  requestedPunchInTime: string | null;
  requestedPunchOutTime: string | null;
  requestedWorkMode: string | null;

  /** What the day held when the request was raised, frozen at that moment. */
  originalPunchInTime: string | null;
  originalPunchOutTime: string | null;
  originalWorkingMinutes: number | null;
  originalStatus: string | null;
  /** True when no record existed, so nothing is being overwritten. */
  creatingMissingDay: boolean;

  status: string;
  currentLevel: number;
  currentApproverEmployeeId: number | null;
  currentApproverName: string | null;

  appliedAt: string;
  decidedAt: string | null;
  cancellationReason: string | null;
  canWithdraw: boolean;

  approvals: RequestApprovalStep[];
}

export interface WorkFromHomeRequest {
  id: number;
  requestNumber: string;
  employeeId: number;
  employeeCode: string;
  employeeName: string;

  fromDate: string;
  toDate: string;
  totalDays: number;
  calendarDays: number;

  reason: string;
  workLocation: string | null;
  contactNumber: string | null;

  status: string;
  currentLevel: number;
  currentApproverEmployeeId: number | null;
  currentApproverName: string | null;

  appliedAt: string;
  decidedAt: string | null;
  cancellationReason: string | null;
  canWithdraw: boolean;
  canCancel: boolean;

  /** Working days only — holidays and weekly offs are already removed. */
  days: string[];
  approvals: RequestApprovalStep[];
}

/** Mirrors RegularizationReason. Labels are what the employee actually picks from. */
export const REGULARIZATION_REASONS = [
  { value: 'FORGOT_PUNCH_IN', label: 'Forgot to punch in' },
  { value: 'FORGOT_PUNCH_OUT', label: 'Forgot to punch out' },
  { value: 'WRONG_PUNCH', label: 'Punched at the wrong time' },
  { value: 'TECHNICAL_ISSUE', label: 'App or device problem' },
  { value: 'CLIENT_LOCATION', label: 'Was at a client site' },
  { value: 'WORK_FROM_HOME', label: 'Was working from home' },
  { value: 'OTHER', label: 'Something else' },
] as const;

/** Which times a reason expects. The API checks the same thing. */
export const REASON_NEEDS: Record<string, { punchIn: boolean; punchOut: boolean }> = {
  FORGOT_PUNCH_IN: { punchIn: true, punchOut: false },
  FORGOT_PUNCH_OUT: { punchIn: false, punchOut: true },
  WRONG_PUNCH: { punchIn: true, punchOut: true },
  TECHNICAL_ISSUE: { punchIn: true, punchOut: true },
  CLIENT_LOCATION: { punchIn: true, punchOut: true },
  WORK_FROM_HOME: { punchIn: true, punchOut: true },
  OTHER: { punchIn: false, punchOut: false },
};

export const WORK_LOCATIONS = [
  { value: 'HOME', label: 'Home' },
  { value: 'OTHER', label: 'Somewhere else' },
] as const;

export const REQUEST_STATUS_TONE: Record<
  string,
  'success' | 'warning' | 'error' | 'info' | 'neutral'
> = {
  APPROVED: 'success',
  PENDING: 'warning',
  REJECTED: 'error',
  WITHDRAWN: 'neutral',
  CANCELLED: 'neutral',
};
