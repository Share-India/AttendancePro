package com.company.attendance.enums;

/**
 * Where an exam is in its life.
 *
 * <p>There is no scheduler in this application, so nothing moves an exam
 * between PUBLISHED, OPEN and CLOSED on a timer. The window is the authority
 * and the status catches up the next time anybody reads the exam — see
 * {@code ExamServiceImpl.settle}.
 */
public enum ExamStatus {

    /** Created, no questions loaded yet. */
    DRAFT,

    /** A workbook has been imported and proof-read. Editable, publishable. */
    QUESTIONS_LOADED,

    /** The roster is fixed and everybody has been told. The window has not opened. */
    PUBLISHED,

    /** Inside the window. Papers can be started. */
    OPEN,

    /** The window has shut. Nobody else can sit it, and absentees are settled. */
    CLOSED,

    /** Called off. Never sat, and excluded from every figure. */
    CANCELLED;

    /** Only a proof-read paper can be published. */
    public boolean canPublish() {
        return this == QUESTIONS_LOADED;
    }

    /** Questions can be replaced only before anybody has been assigned one. */
    public boolean acceptsQuestions() {
        return this == DRAFT || this == QUESTIONS_LOADED;
    }

    /** Published or open — the window may admit a candidate. */
    public boolean isLive() {
        return this == PUBLISHED || this == OPEN;
    }
}
