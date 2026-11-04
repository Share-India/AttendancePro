package com.company.attendance.dto.request.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A request to correct one day's attendance.
 *
 * <p>Times are sent as instants rather than wall-clock strings so the office
 * timezone applies consistently, exactly as it does for a live punch.
 */
public record RegularizationApplyRequest(
        @NotNull(message = "Which date are you correcting?")
        LocalDate attendanceDate,

        /* FORGOT_PUNCH_IN, FORGOT_PUNCH_OUT, WRONG_PUNCH, TECHNICAL_ISSUE,
           CLIENT_LOCATION, WORK_FROM_HOME or OTHER. */
        @NotBlank(message = "Choose a reason type")
        String reasonType,

        /* At least one of these must be present — the service checks, because
           which one depends on the reason type. */
        Instant requestedPunchInTime,
        Instant requestedPunchOutTime,

        String requestedWorkMode,

        @NotBlank(message = "Explain what happened")
        @Size(max = 1000)
        String reason,

        @Size(max = 500)
        String attachmentUrl
) {
}
