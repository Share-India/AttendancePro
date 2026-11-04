package com.company.attendance.dto.response.master;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalTime;

public record ShiftResponse(
        Long id,
        String code,
        String name,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        boolean nightShift,
        int gracePeriodMinutes,
        int earlyExitGraceMinutes,
        int standardWorkMinutes,
        int fullDayMinutes,
        int halfDayMinutes,
        int minAttendanceMinutes,
        int maxBreakMinutes,
        int punchInWindowBeforeMinutes,
        int punchOutWindowAfterMinutes,
        Long officeId,
        String officeName,
        String description,
        boolean defaultShift,
        boolean active,
        long assignedEmployeeCount
) {
}
