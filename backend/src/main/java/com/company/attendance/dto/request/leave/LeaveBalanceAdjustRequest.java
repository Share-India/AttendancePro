package com.company.attendance.dto.request.leave;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * An HR correction to a balance. The reason is mandatory: an unexplained
 * adjustment to someone's entitlement is exactly what an audit asks about.
 */
public record LeaveBalanceAdjustRequest(
        @NotNull(message = "Leave type is required")
        Long leaveTypeId,

        /** Positive credits, negative debits. */
        @NotNull(message = "An adjustment amount is required")
        BigDecimal days,

        @NotBlank(message = "A reason is required for any balance adjustment")
        @Size(max = 500)
        String reason
) {
}
