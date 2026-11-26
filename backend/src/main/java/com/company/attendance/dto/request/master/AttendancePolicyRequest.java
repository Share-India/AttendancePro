package com.company.attendance.dto.request.master;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The settings an administrator may change on an attendance policy.
 *
 * <p>Code is not here. It identifies the policy and is what assignments point
 * at, so renaming it would silently detach every employee from the rules they
 * are under.
 *
 * <p>Bounds are deliberately wide: they exist to catch a slipped digit, not to
 * second-guess what a company's working day is. The relationships that must
 * hold between these numbers -- a half day below a short day, a short day
 * below a full one -- are checked in the service, where a good message can be
 * written.
 */
public record AttendancePolicyRequest(

        @NotBlank(message = "Name is required")
        @Size(max = 120, message = "Name is too long")
        String name,

        @Size(max = 500, message = "Description is too long")
        String description,

        @Min(value = 1, message = "A full day must be at least a minute")
        @Max(value = 1440, message = "A full day cannot be longer than a day")
        short fullDayMinutes,

        @Min(value = 1, message = "A short day must be at least a minute")
        @Max(value = 1440, message = "A short day cannot be longer than a day")
        short shortDayMinutes,

        @Min(value = 1, message = "A half day must be at least a minute")
        @Max(value = 1440, message = "A half day cannot be longer than a day")
        short halfDayMinutes,

        @Min(value = 0, message = "Minimum attendance cannot be negative")
        @Max(value = 1440, message = "Minimum attendance cannot be longer than a day")
        short minAttendanceMinutes,

        @Min(value = 0, message = "Grace cannot be negative")
        @Max(value = 480, message = "Grace beyond eight hours is not a grace period")
        short gracePeriodMinutes,

        @Min(value = 0, message = "Early-exit grace cannot be negative")
        @Max(value = 480, message = "Early-exit grace beyond eight hours is not a grace period")
        short earlyExitGraceMinutes,

        @Min(value = 1, message = "At least one late mark must count")
        @Max(value = 31, message = "More late marks than days in a month")
        short lateMarksForHalfDay,

        @Min(value = 0, message = "A break cannot be negative")
        @Max(value = 1440, message = "A break cannot be longer than a day")
        short maxBreakMinutes,

        boolean deductBreakFromWorking,
        boolean allowPunchOnHoliday,
        boolean allowPunchOnWeeklyOff,

        @Min(value = 0, message = "The correction window cannot be negative")
        @Max(value = 365, message = "A correction window beyond a year is not a window")
        short regularizationWindowDays,

        @Min(value = 0, message = "Corrections per month cannot be negative")
        @Max(value = 31, message = "More corrections than days in a month")
        short maxRegularizationsPerMonth,

        @Min(value = 0, message = "Work-from-home days cannot be negative")
        @Max(value = 31, message = "More work-from-home days than days in a month")
        short maxWfhDaysPerMonth,

        @Min(value = 0, message = "Notice cannot be negative")
        @Max(value = 90, message = "Notice beyond three months is not notice")
        short wfhMinNoticeDays,

        boolean punchInReminderEnabled,

        @Min(value = 0, message = "The reminder cannot go out before the shift starts")
        @Max(value = 480, message = "A reminder eight hours into the shift is not a reminder")
        short punchInReminderDelayMinutes
) {
}
