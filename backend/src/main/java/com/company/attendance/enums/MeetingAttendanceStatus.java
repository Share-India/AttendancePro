package com.company.attendance.enums;

/**
 * What we are willing to say about one person and one meeting.
 *
 * <p>The names are chosen to be honest about a weak signal. We know whether
 * somebody opened our join link; we do not know whether they sat through the
 * call. NOT_JOINED says exactly what was observed and nothing more — it is
 * deliberately not called ABSENT, because a person who dialled in from a
 * calendar invite without clicking our link was present and we cannot see it.
 *
 * <p>EXCUSED is resolved from approved leave, weekly offs, holidays and
 * employment dates, so a person on sanctioned leave is never listed beside
 * someone who simply did not turn up.
 */
public enum MeetingAttendanceStatus {
    INVITED,
    JOINED,
    JOINED_LATE,
    NOT_JOINED,
    EXCUSED
}
