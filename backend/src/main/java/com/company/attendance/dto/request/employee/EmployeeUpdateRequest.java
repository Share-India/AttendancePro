package com.company.attendance.dto.request.employee;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Separate from the create request on purpose: employeeCode is immutable once
 * issued (attendance, leave and audit rows all reference it), and roles and
 * manager are changed through their own endpoints so each is separately
 * permissioned and separately audited.
 */
public record EmployeeUpdateRequest(
        @NotBlank(message = "First name is required")
        @Size(max = 80)
        String firstName,

        @Size(max = 80)
        String middleName,

        @NotBlank(message = "Last name is required")
        @Size(max = 80)
        String lastName,

        @NotBlank(message = "Official email is required")
        @Email(message = "Enter a valid email address")
        @Size(max = 150)
        String officialEmail,

        @Email(message = "Enter a valid email address")
        @Size(max = 150)
        String personalEmail,

        @Pattern(regexp = "^$|^[+0-9 ()-]{6,20}$", message = "Enter a valid mobile number")
        String mobile,

        @Pattern(regexp = "^$|^[+0-9 ()-]{6,20}$", message = "Enter a valid mobile number")
        String alternateMobile,

        @Past(message = "Date of birth must be in the past")
        LocalDate dateOfBirth,

        @Size(max = 20) String gender,
        @Size(max = 10) String bloodGroup,

        @NotNull(message = "Joining date is required")
        LocalDate joiningDate,

        LocalDate confirmationDate,

        Long departmentId,
        Long designationId,

        @NotNull(message = "Office is required")
        Long officeId,

        @NotNull(message = "Employment type is required")
        String employmentType,

        String employmentStatus,

        @Size(max = 120) String emergencyContactName,

        @Pattern(regexp = "^$|^[+0-9 ()-]{6,20}$", message = "Enter a valid contact number")
        String emergencyContactNumber,

        @Size(max = 500) String profilePhotoUrl
) {
}
