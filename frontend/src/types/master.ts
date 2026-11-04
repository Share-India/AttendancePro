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
