package com.company.attendance.dto.response.attendance;

import java.time.LocalDate;
import java.util.List;

/** A month of days for the calendar view, including non-working ones. */
public record AttendanceCalendarResponse(
        int year,
        int month,
        List<CalendarDay> days,
        AttendanceSummaryResponse summary
) {

    public record CalendarDay(
            LocalDate date,
            String dayOfWeek,
            String dayType,
            String status,
            Integer workingMinutes,
            String workingHours,
            Integer lateMinutes,
            String holidayName,
            boolean hasRecord
    ) {
    }
}
