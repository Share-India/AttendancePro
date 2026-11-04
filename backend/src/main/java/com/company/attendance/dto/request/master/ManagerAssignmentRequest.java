package com.company.attendance.dto.request.master;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record ManagerAssignmentRequest(
        @NotNull(message = "Manager is required")
        Long managerEmployeeId,

        /* PRIMARY, DOTTED_LINE or HR_BUSINESS_PARTNER. Only PRIMARY drives
           approval routing and team scope. */
        String relationshipType,

        @NotNull(message = "Effective-from date is required")
        LocalDate effectiveFrom,

        LocalDate effectiveTo
) {
}
