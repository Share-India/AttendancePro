package com.company.attendance.enums;

/**
 * Where one person stands on one day, as a dashboard needs to say it.
 *
 * <p>Deliberately not the same thing as {@link AttendanceStatus}. That records
 * what the attendance row holds; this answers the question a manager actually
 * opens the screen to ask — who is working, who is legitimately off, and who is
 * unaccounted for.
 *
 * <p>The distinction that matters is between the last three. Collapsing them
 * into one "absent" figure is the classic dashboard lie: it counts a person on
 * approved leave alongside a person nobody can find, and a manager acting on
 * that number chases someone who did everything right. {@link #NOT_YET_IN} is
 * separated for the same reason — at 09:05 most of a shift has not arrived yet,
 * and calling them absent before their day has had a chance to start turns a
 * clock reading into an accusation.
 */
public enum DayState {

    /** Punched in from an office location, or already punched out. */
    PRESENT,

    /** Punched in against an approved work-from-home day. */
    WORKING_FROM_HOME,

    /** Approved leave covers the day. Expected to be away. */
    ON_LEAVE,

    /** A weekly off for this employee. Expected to be away. */
    WEEKLY_OFF,

    /** A public holiday. Expected to be away. */
    HOLIDAY,

    /**
     * A working day with no punch yet, and the shift's punch window is still
     * open. Nothing is wrong; the day has not started.
     */
    NOT_YET_IN,

    /**
     * A working day, no punch, and the window to arrive has closed. This is
     * the only state that warrants a manager doing something.
     */
    ABSENT
}
