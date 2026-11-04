package com.company.attendance.dto.response.attendance;

/** Aggregates for a period, used by the dashboard cards. */
public record AttendanceSummaryResponse(
        int year,
        int month,
        int presentDays,
        int absentDays,
        int halfDays,
        int leaveDays,
        int holidayDays,
        int weeklyOffDays,
        int wfhDays,
        int lateMarks,
        int earlyExits,
        int totalWorkingMinutes,
        int overtimeMinutes,
        String totalWorkingHours,
        String averageWorkingHours,
        double attendancePercentage
) {
}
