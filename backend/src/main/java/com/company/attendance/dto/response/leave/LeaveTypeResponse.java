package com.company.attendance.dto.response.leave;

import java.math.BigDecimal;

public record LeaveTypeResponse(
        Long id,
        String code,
        String name,
        String description,
        boolean paid,
        boolean allowHalfDay,
        boolean requiresAttachment,
        Short attachmentRequiredAfterDays,
        int minNoticeDays,
        Short maxConsecutiveDays,
        boolean exemptFromNotice,
        boolean allowBackdated,
        String applicableGender,
        /** Whether holidays inside the range are charged. Shown so the count is explainable. */
        boolean countsHolidaysWeeklyOffs,
        boolean allowNegativeBalance,
        BigDecimal hrApprovalThresholdDays,
        boolean active
) {
}
