package com.company.attendance.dto.response.leave;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LeaveRequestResponse(
        Long id,
        String requestNumber,
        Long employeeId,
        String employeeCode,
        String employeeName,

        Long leaveTypeId,
        String leaveTypeCode,
        String leaveTypeName,
        boolean paid,

        LocalDate fromDate,
        LocalDate toDate,
        String fromDayPortion,
        String toDayPortion,
        BigDecimal totalDays,
        /** Calendar days spanned, which may exceed the days charged. */
        int calendarDays,

        String reason,
        String attachmentUrl,
        String contactDuringLeave,

        String status,
        int currentLevel,
        Long currentApproverEmployeeId,
        String currentApproverName,

        Instant appliedAt,
        Instant decidedAt,
        String cancellationReason,

        /** True while the employee can still withdraw or cancel it themselves. */
        boolean canWithdraw,
        boolean canCancel,

        List<DayBreakdown> days,
        List<ApprovalStep> approvals
) {

    /** Why each date was or was not charged. */
    public record DayBreakdown(
            LocalDate date,
            String dayOfWeek,
            String dayPortion,
            boolean deductible,
            String dayType
    ) {
    }

    public record ApprovalStep(
            int level,
            Long approverEmployeeId,
            String approverName,
            String approverRole,
            String action,
            String comments,
            Instant actedAt
    ) {
    }
}
