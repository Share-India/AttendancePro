package com.company.attendance.enums;

import org.springframework.http.HttpStatus;

/**
 * Every failure the API can return. Each code carries its own HTTP status so a
 * code can never be sent with an inconsistent status.
 */
public enum ErrorCode {

    // 400
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid request"),
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "Invalid date range"),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "Malformed request body"),

    // 401
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Session expired, please sign in again"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Invalid authentication token"),
    TOKEN_MISSING(HttpStatus.UNAUTHORIZED, "Authentication required"),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Invalid refresh token"),
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Refresh token expired"),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "Refresh token reuse detected, all sessions revoked"),

    // 403
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "You do not have permission to perform this action"),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "Account is locked"),
    ACCOUNT_INACTIVE(HttpStatus.FORBIDDEN, "Account is inactive"),
    EMPLOYEE_INACTIVE(HttpStatus.FORBIDDEN, "Employee record is inactive"),
    PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN, "Password change required before continuing"),
    NOT_AUTHORIZED_APPROVER(HttpStatus.FORBIDDEN, "You are not the assigned approver for this request"),

    // 404
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    EMPLOYEE_NOT_FOUND(HttpStatus.NOT_FOUND, "Employee not found"),
    ATTENDANCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Attendance record not found"),
    LEAVE_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "Leave request not found"),

    // 409
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "Resource already exists"),
    ALREADY_PUNCHED_IN(HttpStatus.CONFLICT, "You have already punched in today"),
    ALREADY_PUNCHED_OUT(HttpStatus.CONFLICT, "You have already punched out today"),
    NOT_PUNCHED_IN(HttpStatus.CONFLICT, "You have not punched in yet"),
    BREAK_ALREADY_OPEN(HttpStatus.CONFLICT, "A break is already in progress"),
    ATTENDANCE_ALREADY_EXISTS(HttpStatus.CONFLICT, "An attendance record already exists for this date"),
    OVERLAPPING_LEAVE(HttpStatus.CONFLICT, "You already have a leave request covering these dates"),
    DUPLICATE_REGULARIZATION(HttpStatus.CONFLICT, "A regularization request for this date is already pending"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "This record was modified by someone else, please retry"),

    // 422
    OUTSIDE_GEOFENCE(HttpStatus.UNPROCESSABLE_ENTITY, "You are outside the permitted office location"),
    INSUFFICIENT_LOCATION_ACCURACY(HttpStatus.UNPROCESSABLE_ENTITY, "Location accuracy is too low to record a punch"),
    OUTSIDE_PUNCH_WINDOW(HttpStatus.UNPROCESSABLE_ENTITY, "Punch is outside the allowed window for your shift"),
    NO_SHIFT_ASSIGNED(HttpStatus.UNPROCESSABLE_ENTITY, "No shift is assigned for this date"),
    ON_APPROVED_LEAVE(HttpStatus.UNPROCESSABLE_ENTITY, "You are on approved leave for this date"),
    WFH_NOT_APPROVED(HttpStatus.UNPROCESSABLE_ENTITY, "No approved work-from-home request for this date"),
    LEAVE_BALANCE_INSUFFICIENT(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient leave balance"),
    POLICY_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "This action violates a configured policy"),
    WEAK_PASSWORD(HttpStatus.UNPROCESSABLE_ENTITY, "Password does not meet the security policy"),
    PASSWORD_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "Current password is incorrect"),
    PASSWORD_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "New password must differ from the current one"),
    RESET_TOKEN_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "This reset link is invalid or has already been used"),
    RESET_TOKEN_EXPIRED(HttpStatus.UNPROCESSABLE_ENTITY, "This reset link has expired"),

    // 429
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, please slow down"),

    // 500
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
