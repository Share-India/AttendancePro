package com.company.attendance.dto.request.master;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ShiftAssignmentRequest(
        @NotNull(message = "Shift is required")
        Long shiftId,

        @NotNull(message = "Effective-from date is required")
        LocalDate effectiveFrom,

        /* Null means open-ended. Assigning a new open-ended shift closes the
           previous one the day before, so the two never overlap. */
        LocalDate effectiveTo,

        @Size(max = 255)
        String notes
) {
}
