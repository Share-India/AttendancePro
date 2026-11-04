package com.company.attendance.dto.response.request;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A regularization request.
 *
 * <p>Both the original and requested values are returned, so an approver can
 * see what is actually being changed rather than only what is being asked for.
 */
public record RegularizationResponse(
        Long id,
        String requestNumber,
        Long employeeId,
        String employeeCode,
        String employeeName,

        LocalDate attendanceDate,
        String dayOfWeek,
        String reasonType,
        String reason,
        String attachmentUrl,

        Instant requestedPunchInTime,
        Instant requestedPunchOutTime,
        String requestedWorkMode,

        Instant originalPunchInTime,
        Instant originalPunchOutTime,
        Integer originalWorkingMinutes,
        String originalStatus,
        /** True when no record existed, so nothing is being overwritten. */
        boolean creatingMissingDay,

        String status,
        int currentLevel,
        Long currentApproverEmployeeId,
        String currentApproverName,

        Instant appliedAt,
        Instant decidedAt,
        String cancellationReason,
        boolean canWithdraw,

        List<ApprovalStep> approvals
) {

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
