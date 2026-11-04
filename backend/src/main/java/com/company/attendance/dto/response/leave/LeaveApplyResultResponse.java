package com.company.attendance.dto.response.leave;

import java.math.BigDecimal;

/**
 * The outcome of applying, including the balance left afterwards so the
 * employee does not have to reload a second screen to see it.
 */
public record LeaveApplyResultResponse(
        LeaveRequestResponse request,
        BigDecimal remainingDays,
        /** Set when the count differs from the calendar span, explaining why. */
        String countExplanation
) {
}
