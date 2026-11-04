export interface LeaveTypeOption {
  id: number;
  code: string;
  name: string;
  description: string | null;
  paid: boolean;
  allowHalfDay: boolean;
  requiresAttachment: boolean;
  attachmentRequiredAfterDays: number | null;
  minNoticeDays: number;
  maxConsecutiveDays: number | null;
  exemptFromNotice: boolean;
  allowBackdated: boolean;
  applicableGender: string;
  countsHolidaysWeeklyOffs: boolean;
  allowNegativeBalance: boolean;
  hrApprovalThresholdDays: number | null;
  active: boolean;
}

export interface LeaveBalance {
  leaveTypeId: number;
  leaveTypeCode: string;
  leaveTypeName: string;
  paid: boolean;
  leaveYear: number;
  entitledDays: number;
  openingBalance: number;
  creditedDays: number;
  carriedForwardDays: number;
  adjustedDays: number;
  usedDays: number;
  pendingDays: number;
  encashedDays: number;
  lapsedDays: number;
  availableDays: number;
}

export interface LeaveDayBreakdown {
  date: string;
  dayOfWeek: string;
  dayPortion: string;
  deductible: boolean;
  dayType: string;
}

export interface LeaveApprovalStep {
  level: number;
  approverEmployeeId: number | null;
  approverName: string | null;
  approverRole: string | null;
  action: string;
  comments: string | null;
  actedAt: string | null;
}

export interface LeaveRequest {
  id: number;
  requestNumber: string;
  employeeId: number;
  employeeCode: string;
  employeeName: string;
  leaveTypeId: number;
  leaveTypeCode: string;
  leaveTypeName: string;
  paid: boolean;
  fromDate: string;
  toDate: string;
  fromDayPortion: string;
  toDayPortion: string;
  totalDays: number;
  calendarDays: number;
  reason: string;
  attachmentUrl: string | null;
  contactDuringLeave: string | null;
  status: string;
  currentLevel: number;
  currentApproverEmployeeId: number | null;
  currentApproverName: string | null;
  appliedAt: string;
  decidedAt: string | null;
  cancellationReason: string | null;
  canWithdraw: boolean;
  canCancel: boolean;
  days: LeaveDayBreakdown[];
  approvals: LeaveApprovalStep[];
}

export interface LeaveApplyResult {
  request: LeaveRequest;
  remainingDays: number;
  /** Present when the charge differs from the span picked. */
  countExplanation: string | null;
}

export const LEAVE_STATUS_TONE: Record<string, 'success' | 'warning' | 'error' | 'info' | 'neutral'> = {
  APPROVED: 'success',
  PENDING: 'warning',
  REJECTED: 'error',
  WITHDRAWN: 'neutral',
  CANCELLED: 'neutral',
};
