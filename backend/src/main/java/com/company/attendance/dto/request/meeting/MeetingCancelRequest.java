package com.company.attendance.dto.request.meeting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cancelling a meeting. The reason is shown to everyone who was invited. */
public record MeetingCancelRequest(
        @NotBlank(message = "Say why it is cancelled")
        @Size(max = 500, message = "That reason is too long")
        String reason) {
}
