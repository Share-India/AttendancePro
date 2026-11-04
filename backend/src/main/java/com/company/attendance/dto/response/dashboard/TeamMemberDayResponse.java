package com.company.attendance.dto.response.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Where one person stands on one day. The roster is a list of these. */
public record TeamMemberDayResponse(
        Long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String designationName,
        String officeName,

        LocalDate date,
        /** See DayState — the reason someone is away is part of the answer. */
        String dayState,

        String shiftName,
        LocalTime shiftStartTime,
        LocalTime shiftEndTime,

        Instant punchInTime,
        Instant punchOutTime,
        Integer workingMinutes,
        Integer lateMinutes,
        String workMode,
        String attendanceStatus,

        /** Set when the day is ON_LEAVE, so the manager sees which leave. */
        String leaveTypeName,
        String leaveDayPortion
) {
}
