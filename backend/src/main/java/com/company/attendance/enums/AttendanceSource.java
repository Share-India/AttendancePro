package com.company.attendance.enums;

/**
 * How an attendance record came to exist. Kept so a future biometric or mobile
 * channel is distinguishable from a browser punch, and so system-generated rows
 * are never mistaken for something an employee did.
 */
public enum AttendanceSource {
    WEB,
    MOBILE,
    BIOMETRIC,
    SYSTEM,
    REGULARIZATION,
    IMPORT
}
