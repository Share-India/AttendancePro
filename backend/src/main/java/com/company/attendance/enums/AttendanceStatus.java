package com.company.attendance.enums;

/**
 * The state of one employee-day. PUNCHED_IN and ON_BREAK are transient states
 * during the day; the rest are settled outcomes.
 */
public enum AttendanceStatus {
    PUNCHED_IN,
    ON_BREAK,
    PRESENT,
    HALF_DAY,
    INSUFFICIENT_HOURS,
    ABSENT,
    ON_LEAVE,
    HALF_DAY_LEAVE,
    HOLIDAY,
    WEEKLY_OFF,
    WFH,
    ON_DUTY,
    MISSING_PUNCH_OUT,
    REGULARIZED;

    /** True while the day is still in progress. */
    public boolean isOpen() {
        return this == PUNCHED_IN || this == ON_BREAK;
    }

    /** Whether the day counts as attended for headcount reporting. */
    public boolean isPresent() {
        return this == PRESENT || this == HALF_DAY || this == WFH
                || this == ON_DUTY || this == REGULARIZED;
    }
}
