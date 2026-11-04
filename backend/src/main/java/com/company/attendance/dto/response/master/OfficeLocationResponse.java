package com.company.attendance.dto.response.master;

import java.math.BigDecimal;

public record OfficeLocationResponse(
        Long id,
        Long officeId,
        String officeName,
        String locationName,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        int allowedRadiusMeters,
        String locationType,
        boolean openToAll,
        boolean active
) {
}
