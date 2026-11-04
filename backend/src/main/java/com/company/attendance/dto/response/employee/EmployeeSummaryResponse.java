package com.company.attendance.dto.response.employee;

import java.time.LocalDate;

/**
 * The list-view projection. Deliberately narrower than the detail response —
 * a directory page of 20 rows should not carry personal contact details that
 * the table never shows.
 */
public record EmployeeSummaryResponse(
        Long id,
        String employeeCode,
        String fullName,
        String officialEmail,
        String department,
        String designation,
        String office,
        String employmentType,
        String employmentStatus,
        LocalDate joiningDate,
        String profilePhotoUrl,
        boolean active
) {
}
