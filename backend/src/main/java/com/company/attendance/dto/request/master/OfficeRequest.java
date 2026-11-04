package com.company.attendance.dto.request.master;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OfficeRequest(
        @NotBlank(message = "Code is required")
        @Size(max = 30)
        @Pattern(regexp = "^[A-Z0-9_-]+$", message = "Use uppercase letters, digits, hyphen or underscore")
        String code,

        @NotBlank(message = "Name is required")
        @Size(max = 120)
        String name,

        @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @Size(max = 100) String city,
        @Size(max = 100) String state,
        @Size(max = 100) String country,
        @Size(max = 20) String postalCode,

        /* IANA zone id. Validated against ZoneId.getAvailableZoneIds() in the
           service, because a bad zone here silently shifts every business date
           for everyone posted to this office. */
        @NotBlank(message = "Timezone is required")
        @Size(max = 64)
        String timezone,

        Boolean active
) {
}
