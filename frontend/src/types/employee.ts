export interface EmployeeSummary {
  id: number;
  employeeCode: string;
  fullName: string;
  officialEmail: string;
  department: string | null;
  designation: string | null;
  office: string | null;
  employmentType: string;
  employmentStatus: string;
  joiningDate: string;
  profilePhotoUrl: string | null;
  active: boolean;
}

export interface ShiftAssignment {
  id: number;
  employeeId: number;
  shiftId: number;
  shiftCode: string;
  shiftName: string;
  startTime: string;
  endTime: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  notes: string | null;
  current: boolean;
}

export interface EmployeeDetail {
  id: number;
  employeeCode: string;
  firstName: string;
  middleName: string | null;
  lastName: string;
  fullName: string;
  officialEmail: string;
  personalEmail: string | null;
  mobile: string | null;
  alternateMobile: string | null;
  dateOfBirth: string | null;
  gender: string | null;
  bloodGroup: string | null;
  joiningDate: string;
  confirmationDate: string | null;
  exitDate: string | null;
  departmentId: number | null;
  departmentName: string | null;
  designationId: number | null;
  designationName: string | null;
  officeId: number | null;
  officeName: string | null;
  officeTimezone: string | null;
  employmentType: string;
  employmentStatus: string;
  profilePhotoUrl: string | null;
  emergencyContactName: string | null;
  emergencyContactNumber: string | null;
  active: boolean;
  reportingManagerId: number | null;
  reportingManagerName: string | null;
  currentShift: ShiftAssignment | null;
  shiftHistory: ShiftAssignment[];
  userId: number | null;
  username: string | null;
  roles: string[];
  userActive: boolean;
  userLocked: boolean;
}

export interface EmployeeSearchParams {
  search?: string;
  departmentId?: number;
  designationId?: number;
  officeId?: number;
  employmentType?: string;
  employmentStatus?: string;
  active?: boolean;
  page?: number;
  size?: number;
  sort?: string;
}

export interface EmployeeCreationResult {
  employee: EmployeeDetail;
  temporaryPassword: string;
  note: string;
}

export interface BulkImportResult {
  totalRows: number;
  imported: number;
  failed: number;
  errors: Array<{ rowNumber: number; employeeCode: string | null; message: string }>;
}

export const EMPLOYMENT_TYPES = ['PERMANENT', 'CONTRACT', 'INTERN', 'CONSULTANT'] as const;
export const EMPLOYMENT_STATUSES = ['ACTIVE', 'ON_NOTICE', 'EXITED', 'SUSPENDED'] as const;
