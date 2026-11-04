package com.company.attendance.dto.request.employee;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.Set;

/**
 * Creating an employee also creates their login. The password is never taken
 * from this request — the account is created with a generated temporary
 * password and {@code must_change_password}, so no administrator ever chooses
 * or sees another person's working password.
 */
public record EmployeeCreateRequest(
        @NotBlank(message = "Employee code is required")
        @Size(max = 30)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Use letters, digits, hyphen or underscore")
        String employeeCode,

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

        @Size(max = 20)
        String gender,

        @Size(max = 10)
        String bloodGroup,

        @NotNull(message = "Joining date is required")
        LocalDate joiningDate,

        LocalDate confirmationDate,

        Long departmentId,
        Long designationId,

        @NotNull(message = "Office is required")
        Long officeId,

        /* Drives the geofence candidate set and, through the office timezone,
           every business-date calculation for this employee. */
        @NotNull(message = "Employment type is required")
        String employmentType,

        Long reportingManagerId,
        Long shiftId,

        /* Role codes. Defaults to EMPLOYEE when omitted; assigning anything
           beyond that requires USER_ROLE_ASSIGN. */
        Set<String> roleCodes,

        @Size(max = 120)
        String emergencyContactName,

        @Pattern(regexp = "^$|^[+0-9 ()-]{6,20}$", message = "Enter a valid contact number")
        String emergencyContactNumber
) {
}
