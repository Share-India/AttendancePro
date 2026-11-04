package com.company.attendance.dto.request.request;

import jakarta.validation.constraints.Size;

/** An approver's decision on a regularization or work-from-home request. */
public record RequestDecisionRequest(
        @Size(max = 1000)
        String comments
) {
}
