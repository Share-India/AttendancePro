package com.company.attendance.exception;

import com.company.attendance.enums.ErrorCode;

import java.util.Map;

/**
 * Base for every expected failure. Carries an {@link ErrorCode} (which owns the
 * HTTP status) and an optional detail payload that the frontend can branch on —
 * for example the distance and radius on a geofence rejection.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
