package com.company.attendance.enums;

public enum ApprovalAction {
    PENDING,
    APPROVED,
    REJECTED,
    /** The level was bypassed — typically the applicant was its own approver. */
    SKIPPED,
    /** Reassigned upward after the approval SLA elapsed. */
    ESCALATED
}
