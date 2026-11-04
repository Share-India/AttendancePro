package com.company.attendance.enums;

/**
 * Who ended the attempt.
 *
 * <p>Recorded because a paper the clock handed in is not a paper the
 * candidate handed in, and a result read later should not have to guess.
 */
public enum ExamSubmissionSource {

    /** The candidate pressed Submit inside their time. */
    SELF,
    /** Their own twenty-five minutes ran out. */
    AUTO_TIMEOUT,
    /** The exam window shut while they were still writing. */
    AUTO_WINDOW_CLOSE
}
