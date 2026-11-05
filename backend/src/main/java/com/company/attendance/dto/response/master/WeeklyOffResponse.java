package com.company.attendance.dto.response.master;

import java.time.LocalDate;

public record WeeklyOffResponse(
        Long id,
        String scopeType,
        Long scopeId,
        /** Resolved name of the office, department or employee the scope points at. */
        String scopeName,
        int dayOfWeek,
        String dayName,
        Integer weekOfMonth,
        /** "Every Saturday", "2nd Saturday" — built once here so every caller agrees. */
        String summary,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        boolean active
) {
}
