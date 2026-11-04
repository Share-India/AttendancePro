package com.company.attendance.dto.response.audit;

import java.time.Instant;

/**
 * One entry in the audit trail.
 *
 * <p>Old and new values are returned as the JSON they were stored as. The
 * viewer renders them side by side rather than trying to interpret them, so an
 * auditor sees exactly what was recorded and not a re-derivation of it.
 */
public record AuditLogResponse(
        Long id,
        String action,
        String entityType,
        String entityId,
        String description,
        String oldValue,
        String newValue,

        Long actorUserId,
        String actorUsername,
        String actorName,

        String ipAddress,
        String userAgent,
        Instant createdAt
) {
}
