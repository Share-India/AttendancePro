package com.company.attendance.exception;

import com.company.attendance.enums.ErrorCode;

public class DuplicateResourceException extends BusinessException {

    public DuplicateResourceException(String resource, String field, Object value) {
        super(ErrorCode.DUPLICATE_RESOURCE, resource + " with " + field + " '" + value + "' already exists");
    }
}
