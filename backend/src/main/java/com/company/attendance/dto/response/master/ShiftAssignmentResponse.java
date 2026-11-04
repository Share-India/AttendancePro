package com.company.attendance.dto.response.master;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalTime;

public record ShiftAssignmentResponse(
        Long id,
        Long employeeId,
        Long shiftId,
        String shiftCode,
        String shiftName,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String notes,
        boolean current
) {
}
