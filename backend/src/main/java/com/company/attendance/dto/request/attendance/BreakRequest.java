package com.company.attendance.dto.request.attendance;

import jakarta.validation.constraints.Size;

/**
 * Starting a break names its type; ending one does not need to, since only one
 * break can be open at a time.
 */
public record BreakRequest(
        @Size(max = 30)
        String breakTypeCode
) {
}
