package com.company.attendance.dto.request.master;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

/**
 * Shift thresholds. Cross-field rules (half day within full day, full day
 * within the shift span) are checked in the service, where the message can
 * name both fields.
 */
public record ShiftRequest(
        @NotBlank(message = "Code is required")
        @Size(max = 30)
        @Pattern(regexp = "^[A-Z0-9_-]+$", message = "Use uppercase letters, digits, hyphen or underscore")
        String code,

        @NotBlank(message = "Name is required")
        @Size(max = 120)
        String name,

        @NotNull(message = "Start time is required")
        @JsonFormat(pattern = "HH:mm")
        LocalTime startTime,

        @NotNull(message = "End time is required")
        @JsonFormat(pattern = "HH:mm")
        LocalTime endTime,

        @Min(0) @Max(240)
        Integer gracePeriodMinutes,

        @Min(0) @Max(240)
        Integer earlyExitGraceMinutes,

        @NotNull(message = "Standard working minutes is required")
        @Min(value = 60, message = "A shift must be at least 1 hour")
        @Max(value = 1440, message = "A shift cannot exceed 24 hours")
        Integer standardWorkMinutes,

        @NotNull(message = "Full day minutes is required")
        @Min(60) @Max(1440)
        Integer fullDayMinutes,

        @NotNull(message = "Half day minutes is required")
        @Min(30) @Max(1440)
        Integer halfDayMinutes,

        @Min(0) @Max(1440)
        Integer minAttendanceMinutes,

        @Min(0) @Max(480)
        Integer maxBreakMinutes,

        @Min(0) @Max(720)
        Integer punchInWindowBeforeMinutes,

        @Min(0) @Max(720)
        Integer punchOutWindowAfterMinutes,

        Long officeId,

        @Size(max = 500)
        String description,

        Boolean defaultShift,
        Boolean active
) {
}
