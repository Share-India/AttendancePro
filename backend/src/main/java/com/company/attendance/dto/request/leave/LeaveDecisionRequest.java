package com.company.attendance.dto.request.leave;

import jakarta.validation.constraints.Size;

/** An approver's decision. A rejection must say why. */
public record LeaveDecisionRequest(
        @Size(max = 1000, message = "Keep comments under 1000 characters")
        String comments
) {
}
