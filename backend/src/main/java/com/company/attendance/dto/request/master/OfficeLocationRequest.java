package com.company.attendance.dto.request.master;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** One geofence circle. */
public record OfficeLocationRequest(
        @NotBlank(message = "Location name is required")
        @Size(max = 150)
        String locationName,

        @Size(max = 500)
        String address,

        @NotNull(message = "Latitude is required")
        @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90")
        @DecimalMax(value = "90.0", message = "Latitude must be between -90 and 90")
        BigDecimal latitude,

        @NotNull(message = "Longitude is required")
        @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
        @DecimalMax(value = "180.0", message = "Longitude must be between -180 and 180")
        BigDecimal longitude,

        /* Capped deliberately. An oversized radius is the most common way
           geofencing gets quietly defeated - it is easier to widen the circle
           than to explain a rejected punch. */
        @NotNull(message = "Allowed radius is required")
        @Min(value = 10, message = "Radius must be at least 10 metres")
        @Max(value = 5000, message = "Radius above 5000 metres defeats the purpose of a geofence")
        Integer allowedRadiusMeters,

        @Size(max = 30)
        String locationType,

        Boolean openToAll,
        Boolean active
) {
}
