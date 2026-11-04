package com.company.attendance.dto.request.leave;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * A leave application.
 *
 * <p>No employeeId: the applicant is the authenticated principal. HR applying
 * on someone's behalf is a separate, separately permissioned operation, so a
 * self-service endpoint cannot be used to book leave for another person.
 */
public record LeaveApplyRequest(
        @NotNull(message = "Leave type is required")
        Long leaveTypeId,

        @NotNull(message = "Start date is required")
        LocalDate fromDate,

        @NotNull(message = "End date is required")
        LocalDate toDate,

        /** FULL_DAY, FIRST_HALF or SECOND_HALF. Defaults to a full day. */
        String fromDayPortion,
        String toDayPortion,

        @NotBlank(message = "A reason is required")
        @Size(max = 1000, message = "Keep the reason under 1000 characters")
        String reason,

        @Size(max = 500)
        String attachmentUrl,

        @Size(max = 50)
        String emergencyContact,

        @Size(max = 50)
        String contactDuringLeave
) {
}
