package com.company.attendance.enums;

/**
 * Signals that a punch may not be what it claims.
 *
 * <p>Browser geolocation can be spoofed. These flags do not pretend to prevent
 * that — they record enough for someone to notice a pattern. Whether a flag
 * blocks a punch or merely marks it is a policy setting.
 */
public enum SuspicionFlag {
    LOW_ACCURACY,
    IMPOSSIBLE_TRAVEL,
    IDENTICAL_COORDINATES,
    CLIENT_CLOCK_SKEW,
    PROHIBITED_REGION,
    OUTSIDE_GEOFENCE_RECORDED
}
