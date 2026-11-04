package com.company.attendance.dto.response.auth;

import java.util.Set;

/**
 * Everything the UI needs to render for this principal. The permissions list is
 * the same one the API enforces, so the sidebar hides exactly what the API
 * would refuse — and the API refuses regardless of what the UI shows.
 */
public record CurrentUserResponse(
        Long userId,
        Long employeeId,
        String employeeCode,
        String username,
        String email,
        String fullName,
        String department,
        String designation,
        String office,
        String reportingManager,
        String profilePhotoUrl,
        Set<String> roles,
        Set<String> permissions,
        boolean mustChangePassword
) {
}
