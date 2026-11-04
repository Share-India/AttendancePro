package com.company.attendance.dto.request.leave;

import jakarta.validation.constraints.Size;

public record LeaveCancelRequest(
        @Size(max = 500)
        String reason
) {
}
