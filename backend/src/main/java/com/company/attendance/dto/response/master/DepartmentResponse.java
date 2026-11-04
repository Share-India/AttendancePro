package com.company.attendance.dto.response.master;

public record DepartmentResponse(
        Long id,
        String code,
        String name,
        Long parentDepartmentId,
        String parentDepartmentName,
        Long departmentHeadEmployeeId,
        String departmentHeadName,
        String description,
        boolean active,
        long employeeCount
) {
}
