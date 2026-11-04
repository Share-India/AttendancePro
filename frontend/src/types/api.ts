/** Mirrors com.company.attendance.dto.response.ApiResponse */
export interface ApiResponse<T> {
  success: boolean;
  message: string;
  data: T | null;
  errorCode: string | null;
  timestamp: string;
  path?: string | null;
  traceId?: string | null;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
  sort: string;
}

export interface FieldErrorDetail {
  field: string;
  message: string;
}

/** Mirrors com.company.attendance.enums.ErrorCode — branch on these, never on message text. */
export type ErrorCode =
  | 'VALIDATION_ERROR'
  | 'INVALID_REQUEST'
  | 'MALFORMED_JSON'
  | 'INVALID_CREDENTIALS'
  | 'TOKEN_EXPIRED'
  | 'TOKEN_INVALID'
  | 'TOKEN_MISSING'
  | 'REFRESH_TOKEN_INVALID'
  | 'REFRESH_TOKEN_EXPIRED'
  | 'REFRESH_TOKEN_REUSED'
  | 'ACCESS_DENIED'
  | 'ACCOUNT_LOCKED'
  | 'ACCOUNT_INACTIVE'
  | 'EMPLOYEE_INACTIVE'
  | 'PASSWORD_CHANGE_REQUIRED'
  | 'RESOURCE_NOT_FOUND'
  | 'USER_NOT_FOUND'
  | 'EMPLOYEE_NOT_FOUND'
  | 'DUPLICATE_RESOURCE'
  | 'ALREADY_PUNCHED_IN'
  | 'ALREADY_PUNCHED_OUT'
  | 'NOT_PUNCHED_IN'
  | 'CONCURRENT_MODIFICATION'
  | 'OUTSIDE_GEOFENCE'
  | 'INSUFFICIENT_LOCATION_ACCURACY'
  | 'OUTSIDE_PUNCH_WINDOW'
  | 'LEAVE_BALANCE_INSUFFICIENT'
  | 'POLICY_VIOLATION'
  | 'PASSWORD_MISMATCH'
  | 'PASSWORD_REUSED'
  | 'RESET_TOKEN_INVALID'
  | 'RESET_TOKEN_EXPIRED'
  | 'RATE_LIMIT_EXCEEDED'
  | 'INTERNAL_ERROR';

/** Thrown by the axios client so components can switch on errorCode. */
export class ApiError extends Error {
  // Declared explicitly rather than as constructor parameter properties:
  // the build runs with erasableSyntaxOnly, which disallows that shorthand.
  readonly errorCode: ErrorCode | string;
  readonly status: number;
  readonly details?: unknown;
  readonly traceId?: string | null;

  constructor(
    errorCode: ErrorCode | string,
    message: string,
    status: number,
    details?: unknown,
    traceId?: string | null,
  ) {
    super(message);
    this.name = 'ApiError';
    this.errorCode = errorCode;
    this.status = status;
    this.details = details;
    this.traceId = traceId;
  }

  get fieldErrors(): FieldErrorDetail[] {
    const details = this.details as { fieldErrors?: FieldErrorDetail[] } | undefined;
    return details?.fieldErrors ?? [];
  }
}
