package com.company.attendance.dto.response.meeting;

import java.time.Instant;

/**
 * The live payload the meeting screen polls.
 *
 * <p>Mirrors the punch status endpoint, including {@code serverTime}: the
 * browser clock is used for display only, and the frontend corrects its
 * elapsed-time counter against this value rather than trusting the device.
 */
public record MeetingStatusResponse(
        Long meetingId,
        String status,
        Instant serverTime,
        Instant scheduledStartAt,
        Instant scheduledEndAt,
        Instant actualStartedAt,
        Instant actualEndedAt,
        int invitedCount,
        int joinedCount,
        boolean canJoin,
        boolean canStart,
        boolean canEnd) {
}
