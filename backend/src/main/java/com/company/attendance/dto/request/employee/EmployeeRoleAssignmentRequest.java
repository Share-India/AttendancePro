package com.company.attendance.dto.request.employee;

import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record EmployeeRoleAssignmentRequest(
        @NotEmpty(message = "Assign at least one role")
        Set<String> roleCodes
) {
}
