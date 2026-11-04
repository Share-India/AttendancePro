package com.company.attendance.dto.response.employee;

import com.company.attendance.dto.response.master.ShiftAssignmentResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public record EmployeeDetailResponse(
        Long id,
        String employeeCode,
        String firstName,
        String middleName,
        String lastName,
        String fullName,
        String officialEmail,
        String personalEmail,
        String mobile,
        String alternateMobile,
        LocalDate dateOfBirth,
        String gender,
        String bloodGroup,
        LocalDate joiningDate,
        LocalDate confirmationDate,
        LocalDate exitDate,

        Long departmentId,
        String departmentName,
        Long designationId,
        String designationName,
        Long officeId,
        String officeName,
        String officeTimezone,

        String employmentType,
        String employmentStatus,
        String profilePhotoUrl,
        String emergencyContactName,
        String emergencyContactNumber,
        boolean active,

        Long reportingManagerId,
        String reportingManagerName,
        ShiftAssignmentResponse currentShift,
        List<ShiftAssignmentResponse> shiftHistory,

        Long userId,
        String username,
        Set<String> roles,
        boolean userActive,
        boolean userLocked
) {
}
