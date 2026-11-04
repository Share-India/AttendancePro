package com.company.attendance.dto.request.master;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DesignationRequest(
        @NotBlank(message = "Code is required")
        @Size(max = 30)
        @Pattern(regexp = "^[A-Z0-9_-]+$", message = "Use uppercase letters, digits, hyphen or underscore")
        String code,

        @NotBlank(message = "Name is required")
        @Size(max = 120)
        String name,

        @Min(value = 0, message = "Level cannot be negative")
        @Max(value = 999, message = "Level must be 999 or lower")
        Integer level,

        Long departmentId,

        @Size(max = 500)
        String description,

        Boolean active
) {
}
