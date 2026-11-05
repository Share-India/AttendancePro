package com.company.attendance.dto.request.master;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/**
 * A recurring non-working day.
 *
 * <p>Alternate Saturdays are two rules, not one: Saturday with
 * {@code weekOfMonth} 1 and Saturday with {@code weekOfMonth} 3. A month's
 * fifth Saturday matches no rule and is therefore worked by everybody, which
 * is the intended behaviour here.
 *
 * <p>There is no half-day field. The column exists, but nothing in the system
 * reads it — and a half working day is not a weekly off at all: the calculator
 * short-circuits any weekly off to WEEKLY_OFF before it looks at hours, so a
 * rule marked half-day would excuse the day rather than shorten it. Shortened
 * Saturdays belong to the shift, which carries the window and the thresholds.
 */
public record WeeklyOffRequest(
        @NotBlank(message = "Scope is required")
        @Pattern(regexp = "GLOBAL|OFFICE|DEPARTMENT|EMPLOYEE",
                message = "Scope must be GLOBAL, OFFICE, DEPARTMENT or EMPLOYEE")
        String scopeType,

        /** Required for every scope except GLOBAL; ignored for GLOBAL. */
        Long scopeId,

        @NotNull(message = "Day is required")
        @Min(value = 1, message = "Day must be 1 (Monday) to 7 (Sunday)")
        @Max(value = 7, message = "Day must be 1 (Monday) to 7 (Sunday)")
        Integer dayOfWeek,

        /** Null means every week. 1-5 selects that occurrence in the month. */
        @Min(value = 1, message = "Week must be 1 to 5")
        @Max(value = 5, message = "Week must be 1 to 5")
        Integer weekOfMonth,

        LocalDate effectiveFrom,
        LocalDate effectiveTo,

        Boolean active
) {
}
