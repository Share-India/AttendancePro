package com.company.attendance.exception;

import com.company.attendance.enums.ErrorCode;

public class UnauthorizedActionException extends BusinessException {

    public UnauthorizedActionException(String message) {
        super(ErrorCode.ACCESS_DENIED, message);
    }

    public UnauthorizedActionException() {
        super(ErrorCode.ACCESS_DENIED);
    }
}
