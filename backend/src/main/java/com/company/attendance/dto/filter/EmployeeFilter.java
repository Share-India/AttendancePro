package com.company.attendance.dto.filter;

/**
 * Bound from query parameters and turned into a JPA Specification. Every field
 * is optional; a null means "do not constrain on this".
 */
public record EmployeeFilter(
        String search,
        Long departmentId,
        Long designationId,
        Long officeId,
        String employmentType,
        String employmentStatus,
        Boolean active
) {

    public static EmployeeFilter empty() {
        return new EmployeeFilter(null, null, null, null, null, null, null);
    }
}
