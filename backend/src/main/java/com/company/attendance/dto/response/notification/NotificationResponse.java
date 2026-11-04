package com.company.attendance.dto.response.notification;

import java.time.Instant;

/** One notification as the bell menu shows it. */
public record NotificationResponse(
        Long id,
        String type,
        String category,
        String title,
        String body,
        /** App-relative path to open, or null when there is nothing to open. */
        String link,
        String entityType,
        String entityId,
        String actorName,
        boolean read,
        Instant readAt,
        Instant createdAt
) {
}
