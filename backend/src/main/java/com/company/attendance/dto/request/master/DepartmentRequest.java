package com.company.attendance.dto.request.master;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DepartmentRequest(
        @NotBlank(message = "Code is required")
        @Size(max = 30, message = "Code must be 30 characters or fewer")
        @Pattern(regexp = "^[A-Z0-9_-]+$", message = "Use uppercase letters, digits, hyphen or underscore")
        String code,

        @NotBlank(message = "Name is required")
        @Size(max = 120)
        String name,

        Long parentDepartmentId,
        Long departmentHeadEmployeeId,

        @Size(max = 500)
        String description,

        Boolean active
) {
}
