package com.company.attendance.enums;

/**
 * Whether somebody's result means anything.
 *
 * <p>HR and administrators sit the paper having already read the answers
 * while loading them. Leaving them in makes every question look better
 * understood than it is, so they stay on the roster and out of the figures.
 */
public enum ExamParticipantKind {
    CANDIDATE,
    TESTER
}
