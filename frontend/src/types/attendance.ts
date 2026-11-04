export interface ShiftSummary {
  id: number;
  code: string;
  name: string;
  startTime: string;
  endTime: string;
  nightShift: boolean;
  gracePeriodMinutes: number;
  standardWorkMinutes: number;
}

export interface AttendanceStatus {
  attendanceId: number | null;
  attendanceDate: string;
  status: string;
  dayType: string;
  workMode: string;

  punchInTime: string | null;
  punchOutTime: string | null;
  /** The server's clock. The timer counts from this, never from the browser's. */
  serverTime: string;

  workedMinutes: number;
  breakMinutes: number;
  expectedWorkMinutes: number;
  lateMinutes: number;
  earlyExitMinutes: number;
  overtimeMinutes: number;

  onBreak: boolean;
  currentBreakStartedAt: string | null;
  currentBreakType: string | null;

  canPunchIn: boolean;
  canPunchOut: boolean;
  canStartBreak: boolean;
  canEndBreak: boolean;
  blockedReason?: string | null;

  shift: ShiftSummary | null;
  punchInLocation: string | null;
  punchOutLocation: string | null;
  suspicious: boolean;
  suspicionReasons: string[];
}

export interface BreakSummary {
  id: number;
  breakType: string;
  startTime: string;
  endTime: string | null;
  durationMinutes: number;
  exceededLimit: boolean;
  autoClosed: boolean;
}

export interface AttendanceRecord {
  id: number;
  employeeId: number;
  employeeCode: string;
  employeeName: string;
  attendanceDate: string;
  dayOfWeek: string;
  punchInTime: string | null;
  punchOutTime: string | null;
  workingMinutes: number;
  breakMinutes: number;
  overtimeMinutes: number;
  lateMinutes: number;
  earlyExitMinutes: number;
  workingHours: string;
  attendanceStatus: string;
  workMode: string;
  source: string;
  punchInLocation: string | null;
  punchOutLocation: string | null;
  punchInDistanceMeters: number | null;
  punchOutDistanceMeters: number | null;
  shiftName: string | null;
  regularized: boolean;
  suspicious: boolean;
  suspicionReasons: string[];
  remarks: string | null;
  breaks: BreakSummary[];
}

export interface AttendanceSummary {
  year: number;
  month: number;
  presentDays: number;
  absentDays: number;
  halfDays: number;
  leaveDays: number;
  holidayDays: number;
  weeklyOffDays: number;
  wfhDays: number;
  lateMarks: number;
  earlyExits: number;
  totalWorkingMinutes: number;
  overtimeMinutes: number;
  totalWorkingHours: string;
  averageWorkingHours: string;
  attendancePercentage: number;
}

export interface CalendarDay {
  date: string;
  dayOfWeek: string;
  dayType: string;
  status: string;
  workingMinutes: number | null;
  workingHours: string | null;
  lateMinutes: number | null;
  holidayName: string | null;
  hasRecord: boolean;
}

export interface AttendanceCalendar {
  year: number;
  month: number;
  days: CalendarDay[];
  summary: AttendanceSummary;
}

export interface BreakTypeOption {
  code: string;
  name: string;
  maxMinutes: number;
  paid: boolean;
}

export interface PunchPayload {
  latitude?: number;
  longitude?: number;
  accuracyMeters?: number;
  capturedAt?: string;
  workMode?: string;
  deviceInfo?: string;
}

/** Detail the API attaches to a geofence rejection. */
export interface GeofenceRejection {
  nearestOffice?: string;
  nearestLocation?: string;
  distanceMeters?: number;
  allowedRadiusMeters?: number;
}

export const STATUS_TONE: Record<string, 'success' | 'warning' | 'error' | 'info' | 'neutral'> = {
  PRESENT: 'success',
  WFH: 'success',
  REGULARIZED: 'success',
  ON_DUTY: 'success',
  PUNCHED_IN: 'info',
  ON_BREAK: 'warning',
  HALF_DAY: 'warning',
  INSUFFICIENT_HOURS: 'warning',
  MISSING_PUNCH_OUT: 'warning',
  ABSENT: 'error',
  ON_LEAVE: 'info',
  HALF_DAY_LEAVE: 'info',
  HOLIDAY: 'neutral',
  WEEKLY_OFF: 'neutral',
  UPCOMING: 'neutral',
  NOT_PUNCHED_IN: 'neutral',
};

export const humaniseStatus = (status: string): string =>
  status
    .split('_')
    .map((word) => word.charAt(0) + word.slice(1).toLowerCase())
    .join(' ');
