package com.company.attendance.enums;

/**
 * How a meeting's audience is expressed before it is resolved into a roster.
 *
 * <p>TEAM means the host's own reporting subtree, which is the common case for
 * a morning stand-up and avoids re-listing the same people every day.
 */
public enum MeetingScopeType {
    GLOBAL,
    OFFICE,
    DEPARTMENT,
    TEAM,
    EMPLOYEE
}
