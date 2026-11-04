package com.company.attendance.dto.response.master;

public record DesignationResponse(
        Long id,
        String code,
        String name,
        int level,
        Long departmentId,
        String departmentName,
        String description,
        boolean active,
        long employeeCount
) {
}
