package com.company.attendance.enums;

/**
 * How an exam's audience is described before it is resolved into people.
 *
 * <p>Resolved once, at publish, and then never consulted again — which is
 * what stops a department reorganisation changing who was supposed to sit an
 * exam that has already happened.
 */
public enum ExamAudienceScope {

    /** Everybody active. The scope id is null. */
    GLOBAL,
    DEPARTMENT,
    OFFICE,
    DESIGNATION,
    /** One named person, which is how a retake cohort is written down. */
    EMPLOYEE;

    public boolean needsScopeId() {
        return this != GLOBAL;
    }
}
