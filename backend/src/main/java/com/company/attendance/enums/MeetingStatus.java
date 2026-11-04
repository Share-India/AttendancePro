package com.company.attendance.enums;

/**
 * Where a meeting is in its life.
 *
 * <p>FINALISED is distinct from ENDED on purpose. ENDED means the host stopped
 * it; FINALISED means every participant now carries a terminal attendance
 * status. The gap matters because a meeting whose host never pressed End still
 * has to finalise itself, and the reports must be able to tell the difference
 * between "not scored yet" and "nobody came".
 */
public enum MeetingStatus {
    DRAFT,
    SCHEDULED,
    OPEN,
    ENDED,
    FINALISED,
    CANCELLED
}
