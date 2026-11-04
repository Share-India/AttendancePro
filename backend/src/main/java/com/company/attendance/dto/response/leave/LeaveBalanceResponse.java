package com.company.attendance.dto.response.leave;

import java.math.BigDecimal;

/**
 * One row of the balance card. Every component is exposed, not just the
 * remaining figure, so an employee who disagrees with the number can see
 * exactly which part they disagree with.
 */
public record LeaveBalanceResponse(
        Long leaveTypeId,
        String leaveTypeCode,
        String leaveTypeName,
        boolean paid,
        short leaveYear,

        BigDecimal entitledDays,
        BigDecimal openingBalance,
        BigDecimal creditedDays,
        BigDecimal carriedForwardDays,
        BigDecimal adjustedDays,

        BigDecimal usedDays,
        /** Reserved by requests awaiting a decision. */
        BigDecimal pendingDays,
        BigDecimal encashedDays,
        BigDecimal lapsedDays,

        BigDecimal availableDays
) {
}
