package com.company.attendance.exception;

import com.company.attendance.enums.ErrorCode;

public class EmployeeNotFoundException extends BusinessException {

    public EmployeeNotFoundException(Object identifier) {
        super(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found: " + identifier);
    }
}
