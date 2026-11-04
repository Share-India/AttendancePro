package com.company.attendance.enums;

/**
 * Coarse grouping used for filtering and for per-user opt-outs.
 *
 * <p>Preferences are stored per category rather than per type so that adding a
 * new notification type never leaves people with no preference row and no way
 * to receive it.
 */
public enum NotificationCategory {
    LEAVE,
    ATTENDANCE,
    REQUEST,
    APPROVAL,
    SYSTEM
}
