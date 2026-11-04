package com.company.attendance.dto.request.meeting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Creating or editing a meeting.
 *
 * <p>There is no host id here. The host is whoever is signed in, taken from
 * the JWT, because a body-supplied employee id is exactly what this
 * application refuses to trust anywhere else.
 */
public record MeetingCreateRequest(

        @NotBlank(message = "Give the meeting a title")
        @Size(max = 200, message = "Title is too long")
        String title,

        @Size(max = 4000, message = "Agenda is too long")
        String agenda,

        @NotBlank(message = "Paste the meeting link")
        @Size(max = 1000, message = "That link is too long")
        String meetingUrl,

        @NotNull(message = "When does it start?")
        Instant scheduledStartAt,

        @NotNull(message = "When does it end?")
        Instant scheduledEndAt,

        /** Null falls back to the company setting. */
        Short lateAfterMinutes,

        /** Who is expected. Empty means the host's own reporting subtree. */
        List<AudienceScope> audience) {

    /** One audience rule. scopeId is null for GLOBAL and TEAM. */
    public record AudienceScope(
            @NotBlank(message = "Scope type is required") String scopeType,
            Long scopeId) {
    }
}
