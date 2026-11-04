package com.company.attendance.enums;

/**
 * How far one person got.
 *
 * <p>Deliberately says nothing about passing. Pass or fail lives on the
 * attempt, derived from the answers; duplicating it here would create two
 * places that can disagree about the same result.
 */
public enum ExamParticipantStatus {

    /** On the roster, has not started. */
    ASSIGNED,
    IN_PROGRESS,
    /** Handed in — by the candidate, by the clock, or by the window closing. */
    SUBMITTED,
    /** The window shut without them starting. */
    NOT_ATTEMPTED;

    /**
     * Whether this person is owed a second chance.
     *
     * <p>Somebody still mid-paper is not: who needs a retake is not settled
     * until the original has closed.
     */
    public boolean eligibleForRetake(boolean passed) {
        return switch (this) {
            case SUBMITTED -> !passed;
            case NOT_ATTEMPTED, ASSIGNED -> true;
            case IN_PROGRESS -> false;
        };
    }
}
