package com.company.attendance.dto.response.meeting;

import java.time.Instant;
import java.util.List;

/**
 * A meeting as the API returns it.
 *
 * <p>{@code meetingUrl} is deliberately absent. Nobody needs the raw link:
 * joining goes through this application so that the click is recorded, and
 * handing out the URL would make that bypassable. Hosts see it through the
 * edit form, which is a separate, permission-checked read.
 */
public record MeetingResponse(
        Long id,
        String meetingCode,
        String title,
        String agenda,
        String provider,
        Long hostEmployeeId,
        String hostName,
        Instant scheduledStartAt,
        Instant scheduledEndAt,
        Instant actualStartedAt,
        Instant actualEndedAt,
        String status,
        Short lateAfterMinutes,
        String cancellationReason,

        /** Roster size, frozen at publish. */
        int invitedCount,
        int joinedCount,

        /** What the signed-in caller may do with it right now. */
        boolean canJoin,
        boolean canStart,
        boolean canEnd,
        boolean canEdit,
        boolean canCancel,

        /** This caller's own row, when they are on the roster. */
        String myStatus,
        Instant myJoinedAt,

        List<AudienceScope> audience) {

    public record AudienceScope(String scopeType, Long scopeId, String label) {
    }
}
