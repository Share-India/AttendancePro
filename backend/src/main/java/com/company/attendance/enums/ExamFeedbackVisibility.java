package com.company.attendance.enums;

/**
 * When the worked answers become readable.
 *
 * <p>IMMEDIATE is the default and the reason the module exists — the exam is
 * there to teach. AFTER_CLOSE is for the case where the first person out of
 * the room could otherwise brief the queue.
 */
public enum ExamFeedbackVisibility {
    IMMEDIATE,
    AFTER_CLOSE,
    NEVER
}
