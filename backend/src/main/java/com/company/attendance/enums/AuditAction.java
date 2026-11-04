package com.company.attendance.enums;

public enum AuditAction {
    CREATE, UPDATE, DELETE, ACTIVATE, DEACTIVATE,
    LOGIN_SUCCESS, LOGIN_FAILURE, LOGOUT, LOGOUT_ALL,
    TOKEN_REFRESH, TOKEN_REUSE_DETECTED,
    PASSWORD_CHANGE, PASSWORD_RESET_REQUEST, PASSWORD_RESET_COMPLETE,
    ACCOUNT_LOCKED, ACCOUNT_UNLOCKED,
    ROLE_ASSIGN, ROLE_REVOKE, PERMISSION_CHANGE,
    PUNCH_IN, PUNCH_OUT, BREAK_START, BREAK_END,
    APPROVE, REJECT, CANCEL, EXPORT, POLICY_CHANGE,
    MEETING_PUBLISH, MEETING_START, MEETING_END, MEETING_JOIN, MEETING_CANCEL,
    EXAM_IMPORT, EXAM_PUBLISH, EXAM_SUBMIT, EXAM_RESCORE, EXAM_RETAKE_OPEN, EXAM_CANCEL,

    /**
     * A SUPER_ADMIN set punch times directly, bypassing approval.
     *
     * <p>Its own action rather than UPDATE: this is the one write that
     * changes a payroll input with nobody else's agreement, so it has to be
     * findable in the audit trail by itself.
     */
    ATTENDANCE_OVERRIDE
}
