package com.company.attendance.dto.response.request;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record WorkFromHomeResponse(
        Long id,
        String requestNumber,
        Long employeeId,
        String employeeCode,
        String employeeName,

        LocalDate fromDate,
        LocalDate toDate,
        int totalDays,
        int calendarDays,

        String reason,
        String workLocation,
        String contactNumber,

        String status,
        int currentLevel,
        Long currentApproverEmployeeId,
        String currentApproverName,

        Instant appliedAt,
        Instant decidedAt,
        String cancellationReason,
        boolean canWithdraw,
        boolean canCancel,

        List<LocalDate> days,
        List<RegularizationResponse.ApprovalStep> approvals
) {
}
