package com.company.attendance.dto.request.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record WorkFromHomeApplyRequest(
        @NotNull(message = "Start date is required")
        LocalDate fromDate,

        @NotNull(message = "End date is required")
        LocalDate toDate,

        @NotBlank(message = "Give a reason")
        @Size(max = 1000)
        String reason,

        /** HOME or OTHER. */
        String workLocation,

        @Size(max = 50)
        String contactNumber
) {
}
