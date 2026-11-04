package com.company.attendance.enums;

/** Lifecycle of any employee request: leave, regularization or work from home. */
public enum RequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    /** Withdrawn by the employee before anyone acted. */
    WITHDRAWN,
    /** Cancelled after approval; future days are refunded, consumed days are not. */
    CANCELLED;

    /** Whether the request still holds a reservation against the balance. */
    public boolean holdsReservation() {
        return this == PENDING;
    }

    /** Whether the request still blocks the same dates being requested again. */
    public boolean blocksDates() {
        return this == PENDING || this == APPROVED;
    }

    public boolean isTerminal() {
        return this != PENDING;
    }
}
