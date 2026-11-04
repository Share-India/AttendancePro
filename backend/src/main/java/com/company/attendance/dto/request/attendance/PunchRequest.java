package com.company.attendance.dto.request.attendance;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A punch, in or out.
 *
 * <p>There is deliberately no employeeId. Identity comes from the JWT; a client
 * cannot punch on someone else's behalf by supplying an id.
 *
 * <p>Latitude and longitude are optional at this layer rather than required,
 * because whether location is mandatory depends on the resolved location policy
 * and the work mode — a work-from-home punch legitimately has none. The service
 * rejects a missing position when the policy demands one, with a message that
 * explains which rule applied.
 */
public record PunchRequest(
        @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90")
        @DecimalMax(value = "90.0", message = "Latitude must be between -90 and 90")
        BigDecimal latitude,

        @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
        @DecimalMax(value = "180.0", message = "Longitude must be between -180 and 180")
        BigDecimal longitude,

        /** GPS accuracy radius in metres, as reported by the browser. */
        @DecimalMin(value = "0.0", message = "Accuracy cannot be negative")
        BigDecimal accuracyMeters,

        /** What the client believed the time was. Recorded, never trusted. */
        Instant capturedAt,

        /** OFFICE, WFH, FIELD or CLIENT_SITE. Defaults to OFFICE. */
        @Size(max = 20)
        String workMode,

        @Size(max = 255)
        String deviceInfo
) {
}
