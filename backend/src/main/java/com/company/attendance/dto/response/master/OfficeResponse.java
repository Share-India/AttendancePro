package com.company.attendance.dto.response.master;

import java.util.List;

public record OfficeResponse(
        Long id,
        String code,
        String name,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String country,
        String postalCode,
        String timezone,
        boolean active,
        long employeeCount,
        List<OfficeLocationResponse> locations
) {
}
