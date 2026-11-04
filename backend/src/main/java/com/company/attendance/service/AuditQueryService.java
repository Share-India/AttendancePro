package com.company.attendance.service;

import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.audit.AuditLogResponse;
import com.company.attendance.entity.AuditLog;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AuditLogRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.security.UserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reading the audit trail.
 *
 * <p>Read-only by design and by intent: there is no method here that edits or
 * deletes an entry, and none should be added. An audit log that its own
 * application can rewrite is not evidence of anything.
 *
 * <p><strong>One action is filtered by who is asking.</strong>
 * {@code ATTENDANCE_OVERRIDE} entries are returned only to callers who hold the
 * {@code ATTENDANCE_OVERRIDE} permission — in practice SUPER_ADMIN alone.
 * Everybody else with {@code AUDIT_VIEW}, which today means ADMIN, sees the
 * whole log without them.
 *
 * <p>Note what this is and is not. The rows are still written, still complete,
 * and still carry the before values, the after values and the operator's
 * reason; nothing is falsified and nothing is deleted. This is a read
 * restriction — who may see an entry — not a change to what the log records.
 * The distinction matters: if a corrected timesheet is ever queried, the
 * evidence of what it said before is still there to produce.
 */
@Service
public class AuditQueryService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AuditQueryService(AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(UserPrincipal principal,
                                                 String entityType,
                                                 String entityId,
                                                 Long actorUserId,
                                                 String action,
                                                 LocalDate fromDate,
                                                 LocalDate toDate,
                                                 String search,
                                                 Pageable pageable) {

        AuditAction parsedAction = parseAction(action);

        // Whoever can perform the action can review it. Keyed on the
        // permission rather than on a role name, so the two can never drift
        // apart: granting ATTENDANCE_OVERRIDE to another role would give that
        // role sight of its own entries, which is the only coherent answer.
        boolean seesOverrides = principal != null
                && principal.hasPermission("ATTENDANCE_OVERRIDE");

        Specification<AuditLog> spec = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (entityType != null && !entityType.isBlank()) {
                predicates.add(builder.equal(root.get("entityType"), entityType.trim()));
            }
            if (entityId != null && !entityId.isBlank()) {
                predicates.add(builder.equal(root.get("entityId"), entityId.trim()));
            }
            if (actorUserId != null) {
                predicates.add(builder.equal(root.get("actorUserId"), actorUserId));
            }
            if (parsedAction != null) {
                predicates.add(builder.equal(root.get("action"), parsedAction));
            }
            if (!seesOverrides) {
                // Filtered in the query, not after it, so the row count and
                // the paging agree with what comes back. Trimming the page
                // afterwards would leave short pages that give it away.
                predicates.add(builder.notEqual(root.get("action"),
                        AuditAction.ATTENDANCE_OVERRIDE));
            }
            if (fromDate != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"),
                        fromDate.atStartOfDay().toInstant(ZoneOffset.UTC)));
            }
            if (toDate != null) {
                // Inclusive of the whole end day. A range filter that quietly
                // excluded the last day would hide exactly what an auditor
                // opened the screen to find.
                predicates.add(builder.lessThan(root.get("createdAt"),
                        toDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
            }
            if (search != null && !search.isBlank()) {
                String needle = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("description")), needle),
                        builder.like(builder.lower(root.get("actorUsername")), needle),
                        builder.like(builder.lower(root.get("entityType")), needle)));
            }
            return builder.and(predicates.toArray(new Predicate[0]));
        };

        Page<AuditLog> page = auditLogRepository.findAll(spec, pageable);

        // One lookup for the whole page rather than one per row.
        Map<Long, String> names = resolveActorNames(page.getContent());

        return PageResponse.of(page, log -> toResponse(log, names));
    }

    /** The entity types that actually appear, so the filter offers real options. */
    @Transactional(readOnly = true)
    public List<String> knownEntityTypes() {
        return auditLogRepository.findDistinctEntityTypes();
    }

    private Map<Long, String> resolveActorNames(List<AuditLog> logs) {
        Set<Long> userIds = logs.stream()
                .map(AuditLog::getActorUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (userIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> names = new HashMap<>();
        userRepository.findAllById(userIds).forEach(user ->
                names.put(user.getId(), user.getEmployee() != null
                        ? user.getEmployee().getFullName()
                        : user.getUsername()));
        return names;
    }

    private AuditAction parseAction(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AuditAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Unknown audit action '" + value + "'.");
        }
    }

    private AuditLogResponse toResponse(AuditLog log, Map<Long, String> names) {
        return new AuditLogResponse(
                log.getId(),
                log.getAction() == null ? null : log.getAction().name(),
                log.getEntityType(),
                log.getEntityId(),
                log.getDescription(),
                log.getOldValue(),
                log.getNewValue(),
                log.getActorUserId(),
                log.getActorUsername(),
                names.get(log.getActorUserId()),
                log.getIpAddress(),
                log.getUserAgent(),
                log.getCreatedAt());
    }
}
