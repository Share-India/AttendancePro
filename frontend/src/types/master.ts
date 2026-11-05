export interface Department {
  id: number;
  code: string;
  name: string;
  parentDepartmentId: number | null;
  parentDepartmentName: string | null;
  departmentHeadEmployeeId: number | null;
  departmentHeadName: string | null;
  description: string | null;
  active: boolean;
  employeeCount: number;
}

export interface Designation {
  id: number;
  code: string;
  name: string;
  level: number;
  departmentId: number | null;
  departmentName: string | null;
  description: string | null;
  active: boolean;
  employeeCount: number;
}

export interface OfficeLocation {
  id: number;
  officeId: number;
  officeName: string;
  locationName: string;
  address: string | null;
  latitude: number;
  longitude: number;
  allowedRadiusMeters: number;
  locationType: string;
  openToAll: boolean;
  active: boolean;
}

export interface Office {
  id: number;
  code: string;
  name: string;
  addressLine1: string | null;
  addressLine2: string | null;
  city: string | null;
  state: string | null;
  country: string | null;
  postalCode: string | null;
  timezone: string;
  active: boolean;
  employeeCount: number;
  locations: OfficeLocation[];
}

export interface Shift {
  id: number;
  code: string;
  name: string;
  startTime: string;
  endTime: string;
  nightShift: boolean;
  gracePeriodMinutes: number;
  earlyExitGraceMinutes: number;
  standardWorkMinutes: number;
  fullDayMinutes: number;
  halfDayMinutes: number;
  minAttendanceMinutes: number;
  maxBreakMinutes: number;
  punchInWindowBeforeMinutes: number;
  punchOutWindowAfterMinutes: number;
  officeId: number | null;
  officeName: string | null;
  description: string | null;
  defaultShift: boolean;
  active: boolean;
  assignedEmployeeCount: number;
}

export const LOCATION_TYPES = ['OFFICE', 'BRANCH', 'CLIENT_SITE', 'WAREHOUSE'] as const;

/**
 * A company-wide holiday. No office field by design: every holiday here
 * applies to everybody, and CalendarService reads it that way.
 */
export interface Holiday {
  id: number;
  name: string;
  /** ISO date, yyyy-MM-dd. */
  holidayDate: string;
  holidayType: HolidayType;
  description: string | null;
  holidayYear: number;
  active: boolean;
  /** Served by the API against the office timezone, not the browser clock. */
  past: boolean;
}

/**
 * OPTIONAL and RESTRICTED exist in the database but are refused by the API:
 * nothing models a day an individual may choose, so storing one would excuse
 * the whole company.
 */
export const HOLIDAY_TYPES = ['NATIONAL', 'STATE', 'COMPANY'] as const;

export type HolidayType = (typeof HOLIDAY_TYPES)[number];

export const HOLIDAY_TYPE_LABEL: Record<HolidayType, string> = {
  NATIONAL: 'National',
  STATE: 'State',
  COMPANY: 'Company',
};

/**
 * A recurring non-working day.
 *
 * <p>Alternate Saturdays are two rules, not one — Saturday/week 1 and
 * Saturday/week 3. A fifth Saturday matches neither and is worked by all.
 *
 * <p>No half-day field: a shortened Saturday is a shift, not a weekly off.
 * Any weekly off short-circuits the day to WEEKLY_OFF before hours are
 * considered, so a "half day" rule would excuse the day, not shorten it.
 */
export interface WeeklyOff {
  id: number;
  scopeType: WeeklyOffScope;
  scopeId: number | null;
  /** Resolved name of whatever the scope points at, or "everyone". */
  scopeName: string;
  dayOfWeek: number;
  dayName: string;
  /** Null means every week. */
  weekOfMonth: number | null;
  /** "Every Saturday", "2nd Saturday" — built server-side so callers agree. */
  summary: string;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  active: boolean;
}

export const WEEKLY_OFF_SCOPES = ['GLOBAL', 'OFFICE', 'DEPARTMENT', 'EMPLOYEE'] as const;

export type WeeklyOffScope = (typeof WEEKLY_OFF_SCOPES)[number];

export const WEEKLY_OFF_SCOPE_LABEL: Record<WeeklyOffScope, string> = {
  GLOBAL: 'Everyone',
  OFFICE: 'One office',
  DEPARTMENT: 'One department',
  EMPLOYEE: 'One person',
};

export const WEEKDAYS = [
  { value: 1, label: 'Monday' },
  { value: 2, label: 'Tuesday' },
  { value: 3, label: 'Wednesday' },
  { value: 4, label: 'Thursday' },
  { value: 5, label: 'Friday' },
  { value: 6, label: 'Saturday' },
  { value: 7, label: 'Sunday' },
] as const;
