package com.company.attendance.enums;

/**
 * Why an attendance record needs correcting.
 *
 * <p>Recorded as a category rather than left to free text so HR can see which
 * reason dominates — a spike in TECHNICAL_ISSUE says something about the app,
 * a spike in FORGOT_PUNCH_OUT says something about the reminder schedule.
 */
public enum RegularizationReason {
    FORGOT_PUNCH_IN,
    FORGOT_PUNCH_OUT,
    WRONG_PUNCH,
    TECHNICAL_ISSUE,
    CLIENT_LOCATION,
    WORK_FROM_HOME,
    OTHER
}
