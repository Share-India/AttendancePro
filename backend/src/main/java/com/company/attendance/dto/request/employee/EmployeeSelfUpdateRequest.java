package com.company.attendance.dto.request.employee;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * What an employee may change about themselves. Deliberately narrow: joining
 * date, department, designation, office and employment type are HR-owned
 * because attendance and leave entitlement derive from them.
 */
public record EmployeeSelfUpdateRequest(
        @Email(message = "Enter a valid email address")
        @Size(max = 150)
        String personalEmail,

        @Pattern(regexp = "^$|^[+0-9 ()-]{6,20}$", message = "Enter a valid mobile number")
        String mobile,

        @Pattern(regexp = "^$|^[+0-9 ()-]{6,20}$", message = "Enter a valid mobile number")
        String alternateMobile,

        @Size(max = 10) String bloodGroup,
        @Size(max = 120) String emergencyContactName,

        @Pattern(regexp = "^$|^[+0-9 ()-]{6,20}$", message = "Enter a valid contact number")
        String emergencyContactNumber
) {
}
