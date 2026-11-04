package com.company.attendance.dto.response.meeting;

import java.time.Instant;

/**
 * One row of the roster.
 *
 * <p>{@code attendedMinutesEstimate} is named as an estimate on purpose. It is
 * the end of the meeting minus the moment the person opened the link, which is
 * not the same as how long they were in the call and must never be presented
 * as though it were.
 */
public record MeetingParticipantResponse(
        Long id,
        Long employeeId,
        String employeeCode,
        String employeeName,
        String departmentName,
        String status,
        Instant firstJoinedAt,
        int clickCount,
        Integer attendedMinutesEstimate,
        String attendanceSource,
        String excuseReason) {
}
